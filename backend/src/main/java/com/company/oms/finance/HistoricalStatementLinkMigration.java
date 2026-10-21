package com.company.oms.finance;

import com.company.oms.sales.DeliveryNote;
import com.company.oms.sales.DeliveryNoteItem;
import com.company.oms.sales.DeliveryNoteItemRepository;
import com.company.oms.sales.DeliveryNoteRepository;
import com.company.oms.sales.SalesOrder;
import com.company.oms.sales.SalesOrderItem;
import com.company.oms.sales.SalesOrderItemRepository;
import com.company.oms.sales.SalesOrderRepository;
import com.company.oms.sales.SalesService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
@Order(80)
public class HistoricalStatementLinkMigration implements CommandLineRunner {
    static final String DUPLICATE_DELIVERY_SOURCE_KEY = "HISTORY:DELIVERY:ROW:511:PART:3";
    static final String TARGET_STATEMENT_SOURCE_KEY = "HISTORY:STATEMENT:INVOICE:晓利科技:26322000004731540091";
    static final String ADJUSTMENT_PRODUCT_NAME = "历史对账金额调整";
    static final String MIGRATION_MARKER = "[历史发货关联修复-v1]";

    private final StatementRepository statementRepository;
    private final StatementItemRepository statementItemRepository;
    private final DeliveryNoteRepository deliveryRepository;
    private final DeliveryNoteItemRepository deliveryItemRepository;
    private final SalesOrderRepository orderRepository;
    private final SalesOrderItemRepository orderItemRepository;

    @Value("${oms.migration.legacy-backfills-enabled:false}")
    private boolean enabled;

    public HistoricalStatementLinkMigration(StatementRepository statementRepository,
                                            StatementItemRepository statementItemRepository,
                                            DeliveryNoteRepository deliveryRepository,
                                            DeliveryNoteItemRepository deliveryItemRepository,
                                            SalesOrderRepository orderRepository,
                                            SalesOrderItemRepository orderItemRepository) {
        this.statementRepository = statementRepository;
        this.statementItemRepository = statementItemRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryItemRepository = deliveryItemRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) return;
        repair();
    }

    public RepairResult repair() {
        boolean duplicateRemoved = removeDuplicateCrossOrderDelivery();
        Statement statement = statementRepository.findBySourceKeyAndDeletedFalse(TARGET_STATEMENT_SOURCE_KEY).orElse(null);
        if (statement == null || containsMarker(statement.sourceRemark)) {
            return new RepairResult(duplicateRemoved, 0, BigDecimal.ZERO);
        }

        LocalDate cutoff = statement.generatedAt == null ? null : statement.generatedAt.toLocalDate();
        if (cutoff == null) return new RepairResult(duplicateRemoved, 0, BigDecimal.ZERO);

        StatementItem adjustment = statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .filter(item -> item.deliveryId == null && ADJUSTMENT_PRODUCT_NAME.equals(item.productName))
                .findFirst()
                .orElse(null);
        if (adjustment == null || money(adjustment.amount).compareTo(BigDecimal.ZERO) <= 0) {
            return new RepairResult(duplicateRemoved, 0, BigDecimal.ZERO);
        }

        List<DeliveryNote> candidates = deliveryRepository.findAll().stream()
                .filter(delivery -> !Boolean.TRUE.equals(delivery.deleted))
                .filter(delivery -> Boolean.TRUE.equals(delivery.historicalImported))
                .filter(delivery -> delivery.sourceKey != null && delivery.sourceKey.startsWith("HISTORY:DELIVERY:"))
                .filter(delivery -> "已发货".equals(delivery.status))
                .filter(delivery -> Objects.equals(statement.customerId, delivery.customerId))
                .filter(delivery -> delivery.deliveryDate != null && !delivery.deliveryDate.isAfter(cutoff))
                .filter(delivery -> statementItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id).isEmpty())
                .sorted(Comparator.comparing((DeliveryNote delivery) -> delivery.deliveryDate)
                        .thenComparing(delivery -> delivery.id))
                .toList();
        BigDecimal candidateAmount = money(candidates.stream()
                .map(this::deliveryAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (candidateAmount.compareTo(money(adjustment.amount)) > 0) {
            throw new IllegalStateException("晓利科技历史发货金额超过可冲减的历史调整金额，已停止自动修复");
        }

        for (DeliveryNote delivery : candidates) {
            linkDelivery(statement, delivery);
        }
        adjustment.amount = money(adjustment.amount).subtract(candidateAmount);
        adjustment.unitPrice = adjustment.amount;
        if (adjustment.amount.compareTo(BigDecimal.ZERO) == 0) {
            statementItemRepository.delete(adjustment);
        } else {
            statementItemRepository.save(adjustment);
        }
        statement.sourceRemark = appendMarker(statement.sourceRemark,
                MIGRATION_MARKER + " 补关联" + candidates.size() + "条发货，金额" + candidateAmount.toPlainString());
        statementRepository.save(statement);
        return new RepairResult(duplicateRemoved, candidates.size(), candidateAmount);
    }

    private boolean removeDuplicateCrossOrderDelivery() {
        DeliveryNote delivery = deliveryRepository.findBySourceKeyAndDeletedFalse(DUPLICATE_DELIVERY_SOURCE_KEY).orElse(null);
        if (delivery == null) return false;
        if (!statementItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id).isEmpty()) {
            throw new IllegalStateException("重复历史发货已进入对账单，无法自动删除：" + delivery.deliveryNo);
        }
        List<DeliveryNoteItem> items = deliveryItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id);
        for (DeliveryNoteItem item : items) {
            if (item.orderItemId == null) continue;
            orderItemRepository.findById(item.orderItemId).ifPresent(orderItem -> {
                orderItem.shippedQty = moneyQty(orderItem.shippedQty).subtract(moneyQty(item.qty)).max(BigDecimal.ZERO);
                orderItemRepository.save(orderItem);
            });
        }
        deliveryItemRepository.deleteAll(items);
        deliveryRepository.delete(delivery);
        refreshOrderStatus(delivery.orderId);
        return true;
    }

    private void refreshOrderStatus(Long orderId) {
        if (orderId == null) return;
        SalesOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || "已取消".equals(order.status)) return;
        List<SalesOrderItem> items = orderItemRepository.findByOrderIdAndDeletedFalse(orderId);
        boolean any = items.stream().anyMatch(item -> moneyQty(item.shippedQty).compareTo(BigDecimal.ZERO) > 0);
        boolean all = !items.isEmpty() && items.stream()
                .allMatch(item -> moneyQty(item.shippedQty).compareTo(moneyQty(item.qty)) >= 0);
        order.status = all
                ? SalesService.DELIVERY_STATUS_DELIVERED
                : any ? SalesService.DELIVERY_STATUS_PARTIAL : SalesService.DELIVERY_STATUS_UNSHIPPED;
        order.actualDeliveryDate = all
                ? deliveryRepository.findByOrderIdAndDeletedFalse(order.id).stream()
                        .map(delivery -> delivery.deliveryDate)
                        .filter(Objects::nonNull)
                        .max(LocalDate::compareTo)
                        .orElse(null)
                : null;
        orderRepository.save(order);
    }

    private void linkDelivery(Statement statement, DeliveryNote delivery) {
        for (DeliveryNoteItem item : deliveryItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id)) {
            StatementItem statementItem = new StatementItem();
            statementItem.statementId = statement.id;
            statementItem.deliveryId = delivery.id;
            statementItem.deliveryNo = delivery.deliveryNo;
            statementItem.orderNo = delivery.orderNo;
            statementItem.productName = item.productName;
            statementItem.spec = item.spec;
            statementItem.unit = item.unit;
            statementItem.qty = item.qty;
            statementItem.unitPrice = item.unitPrice;
            statementItem.amount = item.amount;
            statementItem.deliveryDate = delivery.deliveryDate;
            statementItemRepository.save(statementItem);
        }
        delivery.statementId = statement.id;
        delivery.statementNo = statement.statementNo;
        delivery.invoiced = true;
        deliveryRepository.save(delivery);
    }

    private BigDecimal deliveryAmount(DeliveryNote delivery) {
        return deliveryItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id).stream()
                .map(item -> money(item.amount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean containsMarker(String value) {
        return value != null && value.contains(MIGRATION_MARKER);
    }

    private String appendMarker(String value, String marker) {
        return value == null || value.isBlank() ? marker : value + "；" + marker;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal moneyQty(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public record RepairResult(boolean duplicateRemoved, int linkedDeliveries, BigDecimal linkedAmount) {
    }
}
