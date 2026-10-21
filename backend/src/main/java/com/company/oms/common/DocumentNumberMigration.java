package com.company.oms.common;

import com.company.oms.finance.*;
import com.company.oms.inventory.*;
import com.company.oms.notify.NotifyRecord;
import com.company.oms.notify.NotifyRecordRepository;
import com.company.oms.production.*;
import com.company.oms.purchase.*;
import com.company.oms.quality.*;
import com.company.oms.sales.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

@Component
public class DocumentNumberMigration implements CommandLineRunner {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final QuotationRepository quotationRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final StatementRepository statementRepository;
    private final ReceiptRepository receiptRepository;
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final QualityReportRepository qualityReportRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final StatementItemRepository statementItemRepository;
    private final InvoiceRepository invoiceRepository;
    private final WorkerProductionAllocationRepository allocationRepository;
    private final InventoryTxnRepository inventoryTxnRepository;
    private final StockLockRepository stockLockRepository;
    private final NotifyRecordRepository notifyRecordRepository;

    @Value("${oms.migration.legacy-backfills-enabled:false}")
    private boolean enabled;

    public DocumentNumberMigration(QuotationRepository quotationRepository,
                                   SalesOrderRepository salesOrderRepository,
                                   DeliveryNoteRepository deliveryNoteRepository,
                                   StatementRepository statementRepository,
                                   ReceiptRepository receiptRepository,
                                   PurchaseRequestRepository purchaseRequestRepository,
                                   QualityReportRepository qualityReportRepository,
                                   ProductionPlanRepository productionPlanRepository,
                                   StatementItemRepository statementItemRepository,
                                   InvoiceRepository invoiceRepository,
                                   WorkerProductionAllocationRepository allocationRepository,
                                   InventoryTxnRepository inventoryTxnRepository,
                                   StockLockRepository stockLockRepository,
                                   NotifyRecordRepository notifyRecordRepository) {
        this.quotationRepository = quotationRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.deliveryNoteRepository = deliveryNoteRepository;
        this.statementRepository = statementRepository;
        this.receiptRepository = receiptRepository;
        this.purchaseRequestRepository = purchaseRequestRepository;
        this.qualityReportRepository = qualityReportRepository;
        this.productionPlanRepository = productionPlanRepository;
        this.statementItemRepository = statementItemRepository;
        this.invoiceRepository = invoiceRepository;
        this.allocationRepository = allocationRepository;
        this.inventoryTxnRepository = inventoryTxnRepository;
        this.stockLockRepository = stockLockRepository;
        this.notifyRecordRepository = notifyRecordRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) return;
        List<Quotation> quotations = quotationRepository.findAll();
        List<SalesOrder> orders = salesOrderRepository.findAll();
        List<DeliveryNote> deliveries = deliveryNoteRepository.findAll();
        // An undated source bill must not acquire a fabricated date through renumbering.
        List<Statement> statements = statementRepository.findAll().stream()
                .filter(item -> item.sourceSequence == null || item.generatedAt != null).toList();
        List<Receipt> receipts = receiptRepository.findAll().stream()
                .filter(receipt -> receipt.paymentId == null).toList();
        List<PurchaseRequest> purchaseRequests = purchaseRequestRepository.findAll();
        List<QualityReport> qualityReports = qualityReportRepository.findAll();
        List<ProductionPlan> productionPlans = productionPlanRepository.findAll();

        if (allCanonical(quotations, item -> item.quoteNo, "A")
                && allCanonical(orders, item -> item.orderNo, "B")
                && allCanonical(deliveries, item -> item.deliveryNo, "C")
                && allCanonical(statements, item -> item.statementNo, "D")
                && allCanonical(receipts, item -> item.receiptNo, "E")
                && allCanonical(purchaseRequests, item -> item.requestNo, "F")
                && allCanonical(qualityReports, item -> item.reportNo, "G")
                && allCanonical(productionPlans, item -> item.planNo, "H")) {
            return;
        }

        NumberPlan<Quotation> quotePlan = plan(quotations, "A", item -> item.quoteNo,
                item -> date(item.quoteDate, item.createdAt), item -> item.id);
        NumberPlan<SalesOrder> orderPlan = plan(orders, "B", item -> item.orderNo,
                item -> date(item.orderDate, item.createdAt), item -> item.id);
        NumberPlan<DeliveryNote> deliveryPlan = plan(deliveries, "C", item -> item.deliveryNo,
                item -> date(item.deliveryDate, item.createdAt), item -> item.id);
        NumberPlan<Statement> statementPlan = plan(statements, "D", item -> item.statementNo,
                item -> date(item.generatedAt == null ? null : item.generatedAt.toLocalDate(), item.createdAt), item -> item.id);
        NumberPlan<Receipt> receiptPlan = plan(receipts, "E", item -> item.receiptNo,
                item -> date(item.receiptDate, item.createdAt), item -> item.id);
        NumberPlan<PurchaseRequest> purchasePlan = plan(purchaseRequests, "F", item -> item.requestNo,
                item -> date(null, item.createdAt), item -> item.id);
        NumberPlan<QualityReport> qualityPlan = plan(qualityReports, "G", item -> item.reportNo,
                item -> date(item.inspectionDate, item.createdAt), item -> item.id);
        NumberPlan<ProductionPlan> productionPlan = plan(productionPlans, "H", item -> item.planNo,
                item -> date(null, item.createdAt), item -> item.id);

        applyNumbers(quotePlan, (item, number) -> item.quoteNo = number, quotationRepository::saveAllAndFlush);
        applyNumbers(orderPlan, (item, number) -> item.orderNo = number, salesOrderRepository::saveAllAndFlush);
        applyNumbers(deliveryPlan, (item, number) -> item.deliveryNo = number, deliveryNoteRepository::saveAllAndFlush);
        applyNumbers(statementPlan, (item, number) -> item.statementNo = number, statementRepository::saveAllAndFlush);
        applyNumbers(receiptPlan, (item, number) -> item.receiptNo = number, receiptRepository::saveAllAndFlush);
        applyNumbers(purchasePlan, (item, number) -> item.requestNo = number, purchaseRequestRepository::saveAllAndFlush);
        applyNumbers(qualityPlan, (item, number) -> item.reportNo = number, qualityReportRepository::saveAllAndFlush);
        applyNumbers(productionPlan, (item, number) -> item.planNo = number, productionPlanRepository::saveAllAndFlush);

        updateReferences(quotePlan.oldToNew, orderPlan.oldToNew, deliveryPlan.oldToNew,
                statementPlan.oldToNew, receiptPlan.oldToNew, purchasePlan.oldToNew,
                qualityPlan.oldToNew, productionPlan.oldToNew);
    }

    private void updateReferences(Map<String, String> quoteNos,
                                  Map<String, String> orderNos,
                                  Map<String, String> deliveryNos,
                                  Map<String, String> statementNos,
                                  Map<String, String> receiptNos,
                                  Map<String, String> purchaseNos,
                                  Map<String, String> qualityNos,
                                  Map<String, String> planNos) {
        List<Quotation> quotations = quotationRepository.findAll();
        quotations.forEach(item -> item.convertedOrderNo = replace(item.convertedOrderNo, orderNos));
        quotationRepository.saveAll(quotations);

        List<SalesOrder> orders = salesOrderRepository.findAll();
        orders.forEach(item -> item.sourceQuoteNo = replace(item.sourceQuoteNo, quoteNos));
        salesOrderRepository.saveAll(orders);

        List<DeliveryNote> deliveries = deliveryNoteRepository.findAll();
        deliveries.forEach(item -> {
            item.orderNo = replace(item.orderNo, orderNos);
            item.statementNo = replace(item.statementNo, statementNos);
            item.qrCodeContent = "/delivery/" + item.deliveryNo;
        });
        deliveryNoteRepository.saveAll(deliveries);

        List<StatementItem> statementItems = statementItemRepository.findAll();
        statementItems.forEach(item -> {
            item.deliveryNo = replace(item.deliveryNo, deliveryNos);
            item.orderNo = replace(item.orderNo, orderNos);
        });
        statementItemRepository.saveAll(statementItems);

        List<Invoice> invoices = invoiceRepository.findAll();
        invoices.forEach(item -> {
            item.statementNo = replace(item.statementNo, statementNos);
            item.orderNo = replace(item.orderNo, orderNos);
        });
        invoiceRepository.saveAll(invoices);

        List<Receipt> receipts = receiptRepository.findAll();
        receipts.forEach(item -> item.orderNo = replace(item.orderNo, orderNos));
        receiptRepository.saveAll(receipts);

        List<ProductionPlan> plans = productionPlanRepository.findAll();
        plans.forEach(item -> item.sourceOrderNo = replace(item.sourceOrderNo, orderNos));
        productionPlanRepository.saveAll(plans);

        List<WorkerProductionAllocation> allocations = allocationRepository.findAll();
        allocations.forEach(item -> {
            item.planNo = replace(item.planNo, planNos);
            item.sourceOrderNo = replace(item.sourceOrderNo, orderNos);
        });
        allocationRepository.saveAll(allocations);

        List<QualityReport> reports = qualityReportRepository.findAll();
        reports.forEach(item -> {
            item.orderNo = replace(item.orderNo, orderNos);
            item.businessNo = replaceByType(item.businessNo, item.businessType, quoteNos, orderNos,
                    deliveryNos, statementNos, receiptNos, purchaseNos, qualityNos, planNos);
        });
        qualityReportRepository.saveAll(reports);

        List<PurchaseRequest> requests = purchaseRequestRepository.findAll();
        requests.forEach(item -> item.sourceNo = replaceAny(item.sourceNo, quoteNos, orderNos, deliveryNos,
                statementNos, receiptNos, purchaseNos, qualityNos, planNos));
        purchaseRequestRepository.saveAll(requests);

        List<InventoryTxn> transactions = inventoryTxnRepository.findAll();
        transactions.forEach(item -> item.sourceNo = replaceByType(item.sourceNo, item.sourceType, quoteNos,
                orderNos, deliveryNos, statementNos, receiptNos, purchaseNos, qualityNos, planNos));
        inventoryTxnRepository.saveAll(transactions);

        List<StockLock> locks = stockLockRepository.findAll();
        locks.forEach(item -> item.sourceNo = replaceByType(item.sourceNo, item.sourceType, quoteNos,
                orderNos, deliveryNos, statementNos, receiptNos, purchaseNos, qualityNos, planNos));
        stockLockRepository.saveAll(locks);

        List<NotifyRecord> notifications = notifyRecordRepository.findAll();
        notifications.forEach(item -> {
            String oldNo = item.businessNo;
            item.businessNo = replaceByType(oldNo, item.businessType, quoteNos, orderNos, deliveryNos,
                    statementNos, receiptNos, purchaseNos, qualityNos, planNos);
            if (oldNo != null && item.businessNo != null && !oldNo.equals(item.businessNo) && item.content != null) {
                item.content = item.content.replace(oldNo, item.businessNo);
            }
        });
        notifyRecordRepository.saveAll(notifications);
    }

    private <T> boolean allCanonical(List<T> items, Function<T, String> numberGetter, String typeCode) {
        Pattern pattern = Pattern.compile("R" + typeCode + "\\d{11}");
        return items.stream().allMatch(item -> {
            String number = numberGetter.apply(item);
            return number != null && pattern.matcher(number).matches();
        });
    }

    private <T> NumberPlan<T> plan(List<T> items,
                                   String typeCode,
                                   Function<T, String> oldNumberGetter,
                                   Function<T, LocalDate> dateGetter,
                                   Function<T, Long> idGetter) {
        List<T> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(dateGetter).thenComparing(item -> Optional.ofNullable(idGetter.apply(item)).orElse(0L)));
        Map<LocalDate, Integer> counters = new HashMap<>();
        Map<T, String> finalNumbers = new IdentityHashMap<>();
        Map<String, String> oldToNew = new LinkedHashMap<>();
        for (T item : sorted) {
            LocalDate date = dateGetter.apply(item);
            int sequence = counters.merge(date, 1, Integer::sum);
            if (sequence > 999) throw new BusinessException("单日单据超过999条，无法完成编号迁移：R" + typeCode + date.format(DATE_FORMAT));
            String newNumber = "R" + typeCode + date.format(DATE_FORMAT) + String.format("%03d", sequence);
            finalNumbers.put(item, newNumber);
            String oldNumber = oldNumberGetter.apply(item);
            if (oldNumber != null && !oldNumber.isBlank()) oldToNew.put(oldNumber, newNumber);
        }
        return new NumberPlan<>(sorted, finalNumbers, oldToNew, typeCode, idGetter);
    }

    private <T> void applyNumbers(NumberPlan<T> plan,
                                  BiConsumer<T, String> setter,
                                  Function<List<T>, ?> saveAllAndFlush) {
        for (T item : plan.items) {
            setter.accept(item, "TMP-" + plan.typeCode + "-" + Optional.ofNullable(plan.idGetter.apply(item)).orElse(0L));
        }
        saveAllAndFlush.apply(plan.items);
        for (T item : plan.items) setter.accept(item, plan.finalNumbers.get(item));
        saveAllAndFlush.apply(plan.items);
    }

    private LocalDate date(LocalDate preferred, LocalDateTime createdAt) {
        if (preferred != null) return preferred;
        if (createdAt != null) return createdAt.toLocalDate();
        return LocalDate.now();
    }

    private String replace(String value, Map<String, String> mapping) {
        if (value == null || value.isBlank()) return value;
        return mapping.getOrDefault(value, value);
    }

    @SafeVarargs
    private final String replaceAny(String value, Map<String, String>... mappings) {
        if (value == null || value.isBlank()) return value;
        for (Map<String, String> mapping : mappings) {
            if (mapping.containsKey(value)) return mapping.get(value);
        }
        return value;
    }

    private String replaceByType(String value,
                                 String businessType,
                                 Map<String, String> quoteNos,
                                 Map<String, String> orderNos,
                                 Map<String, String> deliveryNos,
                                 Map<String, String> statementNos,
                                 Map<String, String> receiptNos,
                                 Map<String, String> purchaseNos,
                                 Map<String, String> qualityNos,
                                 Map<String, String> planNos) {
        if (value == null || value.isBlank()) return value;
        String type = businessType == null ? "" : businessType.toUpperCase(Locale.ROOT);
        if (type.contains("QUOT")) return replace(value, quoteNos);
        if (type.contains("ORDER")) return replace(value, orderNos);
        if (type.contains("DELIVERY")) return replace(value, deliveryNos);
        if (type.contains("STATEMENT")) return replace(value, statementNos);
        if (type.contains("RECEIPT")) return replace(value, receiptNos);
        if (type.contains("PURCHASE")) return replace(value, purchaseNos);
        if (type.contains("QUALITY")) return replace(value, qualityNos);
        if (type.contains("PRODUCTION") || type.contains("PLAN")) return replace(value, planNos);
        return replaceAny(value, quoteNos, orderNos, deliveryNos, statementNos, receiptNos, purchaseNos, qualityNos, planNos);
    }

    private record NumberPlan<T>(List<T> items,
                                 Map<T, String> finalNumbers,
                                 Map<String, String> oldToNew,
                                 String typeCode,
                                 Function<T, Long> idGetter) {
    }
}
