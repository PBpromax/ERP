package com.company.oms.sales;

import com.company.oms.common.*;
import com.company.oms.finance.*;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SalesReturnService {
    private final SalesReturnRepository returns;
    private final SalesOrderRepository orders;
    private final SalesOrderItemRepository orderItems;
    private final StatementItemRepository statementItems;

    public SalesReturnService(SalesReturnRepository returns, SalesOrderRepository orders,
                              SalesOrderItemRepository orderItems, StatementItemRepository statementItems) {
        this.returns = returns;
        this.orders = orders;
        this.orderItems = orderItems;
        this.statementItems = statementItems;
    }

    public Map<Long, BigDecimal> quantities() {
        return returns.findByDeletedFalse().stream().collect(Collectors.toMap(
                r -> r.orderItemId, r -> r.qty, BigDecimal::add));
    }

    public Map<Long, BigDecimal> quantities(Collection<Long> orderItemIds) {
        if (orderItemIds == null || orderItemIds.isEmpty()) return Map.of();
        return returns.findByOrderItemIdInAndDeletedFalse(orderItemIds).stream().collect(Collectors.toMap(
                r -> r.orderItemId, r -> r.qty, BigDecimal::add));
    }

    public List<Map<String, Object>> history(Long orderId, Long itemId) {
        return returns.findByOrderItemIdAndDeletedFalse(itemId).stream()
                .filter(r -> Objects.equals(r.orderId, orderId))
                .sorted(Comparator.comparing((SalesReturn r) -> r.returnDate).thenComparing(r -> r.id).reversed())
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("record", r);
                    row.put("reconciledQty", reconciledQty(r.id));
                    return row;
                }).toList();
    }

    @Transactional
    @CacheEvict(cacheNames = "deliveryBoardPages", allEntries = true)
    public SalesReturn register(Long orderId, Request request) {
        if (request == null || request.itemId == null) throw new BusinessException("请选择退货产品");
        SalesOrderItem item = orderItems.findForReturn(request.itemId)
                .filter(i -> Objects.equals(i.orderId, orderId))
                .orElseThrow(() -> new BusinessException("订单产品不存在"));
        SalesOrder order = orders.findById(orderId).filter(o -> !Boolean.TRUE.equals(o.deleted))
                .orElseThrow(() -> new BusinessException("订单不存在"));
        if (request.returnDate == null) throw new BusinessException("请填写退货日期");
        if (request.qty == null || request.qty.signum() <= 0 || request.qty.stripTrailingZeros().scale() > 0)
            throw new BusinessException("退货数量必须为正整数");
        BigDecimal returned = returns.findByOrderItemIdAndDeletedFalse(item.id).stream()
                .map(r -> r.qty).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal available = MoneyUtil.nvl(item.shippedQty).subtract(returned).max(BigDecimal.ZERO);
        if (request.qty.compareTo(available) > 0) throw new BusinessException("退货数量不能超过已发货未退货数量 " + available);
        if (MoneyUtil.nvl(item.unitPrice).signum() < 0) throw new BusinessException("订单单价不能为负数");
        if (request.remark != null && request.remark.length() > 500) throw new BusinessException("退货备注不能超过500字");
        SalesReturn r = new SalesReturn();
        r.orderId = order.id; r.orderItemId = item.id; r.customerId = order.customerId;
        r.customerName = order.customerName; r.orderNo = order.orderNo;
        r.productCode = item.productCode; r.productName = item.productName;
        r.spec = item.spec; r.unit = item.unit; r.returnDate = request.returnDate;
        r.qty = request.qty; r.unitPrice = MoneyUtil.nvl(item.unitPrice);
        r.amount = MoneyUtil.money(r.qty.multiply(r.unitPrice)); r.remark = request.remark;
        // Returns are independent events: never change shipment, fulfillment or stock counters here.
        return returns.save(r);
    }

    @Transactional
    @CacheEvict(cacheNames = "deliveryBoardPages", allEntries = true)
    public void delete(Long id) {
        SalesReturn r = returns.findForReconciliation(id).orElseThrow(() -> new BusinessException("退货记录不存在"));
        if (!statementItems.findByReturnIdAndDeletedFalse(id).isEmpty())
            throw new BusinessException("退货记录已参与对账，请先移除对账单中的退货行");
        r.deleted = true;
        returns.save(r);
    }

    public BigDecimal reconciledQty(Long id) {
        return statementItems.findByReturnIdAndDeletedFalse(id).stream()
                .map(i -> MoneyUtil.nvl(i.qty).negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<Map<String, Object>> candidates() {
        Map<Long, BigDecimal> used = statementItems.findByReturnIdIsNotNullAndDeletedFalse().stream()
                .collect(Collectors.toMap(i -> i.returnId, i -> MoneyUtil.nvl(i.qty).negate(), BigDecimal::add));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SalesReturn r : returns.findByDeletedFalse().stream()
                .sorted(Comparator.comparing((SalesReturn r) -> r.returnDate).thenComparing(r -> r.id)).toList()) {
            BigDecimal remaining = r.qty.subtract(used.getOrDefault(r.id, BigDecimal.ZERO));
            if (remaining.signum() <= 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "return-" + r.id); row.put("returnId", r.id); row.put("adjustmentType", "RETURN");
            row.put("deliveryNo", "退货记录"); row.put("orderNo", r.orderNo); row.put("orderId", r.orderId);
            row.put("customerId", r.customerId); row.put("customerName", r.customerName);
            row.put("deliveryDate", r.returnDate); row.put("deliveryMethod", "");
            row.put("qty", remaining.negate()); row.put("maxQty", remaining.negate());
            row.put("amount", MoneyUtil.money(remaining.multiply(r.unitPrice)).negate());
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("id", r.id); product.put("name", r.productName); product.put("spec", r.spec);
            product.put("code", r.productCode); product.put("unitPrice", r.unitPrice);
            row.put("products", List.of(product));
            row.put("quantitySegments", List.of(Map.of("qty", remaining, "unitPrice", r.unitPrice)));
            rows.add(row);
        }
        return rows;
    }

    public static class Request {
        public Long itemId;
        public LocalDate returnDate;
        public BigDecimal qty;
        public String remark;
    }
}
