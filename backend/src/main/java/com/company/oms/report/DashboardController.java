package com.company.oms.report;

import com.company.oms.common.ApiResponse;
import com.company.oms.common.MoneyUtil;
import com.company.oms.finance.Statement;
import com.company.oms.finance.StatementItem;
import com.company.oms.finance.StatementItemRepository;
import com.company.oms.finance.StatementRepository;
import com.company.oms.finance.FinanceService;
import com.company.oms.sales.*;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/reports")
@PreAuthorize("@menuPermissionService.has('dashboard')")
public class DashboardController {
    private final SalesOrderRepository salesOrderRepository;
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final StatementRepository statementRepository;
    private final StatementItemRepository statementItemRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final DeliveryNoteItemRepository deliveryNoteItemRepository;
    private final FinanceService financeService;

    public DashboardController(SalesOrderRepository salesOrderRepository,
                               DeliveryNoteRepository deliveryNoteRepository,
                               StatementRepository statementRepository,
                               StatementItemRepository statementItemRepository,
                               SalesOrderItemRepository salesOrderItemRepository,
                               DeliveryNoteItemRepository deliveryNoteItemRepository,
                               FinanceService financeService) {
        this.salesOrderRepository = salesOrderRepository;
        this.deliveryNoteRepository = deliveryNoteRepository;
        this.statementRepository = statementRepository;
        this.statementItemRepository = statementItemRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.deliveryNoteItemRepository = deliveryNoteItemRepository;
        this.financeService = financeService;
    }

    // Keep live collections separate from cached, expensive sales/trend calculations.
    @GetMapping("/collection-summary")
    public ApiResponse<Map<String, Object>> collectionSummary() {
        List<Map<String, Object>> companies = financeService.collectionCompanyBoard();
        int year = YearMonth.now(ZoneId.of("Asia/Shanghai")).getYear();
        DashboardFinancialSummary financial = calculateFinancialSummary(
                ordersInYear(salesOrderRepository.findAll().stream()
                        .filter(o -> !Boolean.TRUE.equals(o.deleted)).toList(), year),
                deliveryNoteRepository.findAll().stream()
                        .filter(d -> !Boolean.TRUE.equals(d.deleted)).toList(),
                statementRepository.findAll().stream()
                        .filter(s -> !Boolean.TRUE.equals(s.deleted)).toList(),
                statementItemRepository.findAll().stream()
                        .filter(i -> !Boolean.TRUE.equals(i.deleted)).toList(),
                salesOrderItemRepository.findAll(), deliveryNoteItemRepository.findAll());
        BigDecimal unpaid = collectionLedgerBalanceTotal(companies);
        BigDecimal pending = financeService.pendingReconciliationTotal();
        return ApiResponse.ok(Map.of(
                "collectionUnpaidTotal", unpaid,
                "deliveredReconciledTotal", financial.deliveredReconciledTotal(),
                "deliveredUnreconciledTotal", pending,
                "currentOutstandingTotal", unpaid.add(pending),
                "invoicedUnpaidTotal", collectionInvoiceUnpaidTotal(companies),
                "yearReceivedTotal", financeService.collectionYearReceivedTotal(year),
                "collectionYear", year));
    }

    @GetMapping("/dashboard")
    @Cacheable(cacheNames = "dashboard", key = "'current'")
    public ApiResponse<Map<String, Object>> dashboard() {
        YearMonth current = YearMonth.now(ZoneId.of("Asia/Shanghai"));
        Map<String, Object> data = new LinkedHashMap<>();

        List<SalesOrder> salesOrders = salesOrderRepository.findAll().stream()
                .filter(o -> o.deleted == null || !o.deleted)
                .toList();
        List<DeliveryNote> deliveryNotes = deliveryNoteRepository.findAll().stream()
                .filter(d -> d.deleted == null || !d.deleted)
                .toList();
        List<Statement> statements = statementRepository.findAll().stream()
                .filter(s -> s.deleted == null || !s.deleted)
                .toList();
        List<StatementItem> statementItems = statementItemRepository.findAll().stream()
                .filter(item -> item.deleted == null || !item.deleted)
                .toList();
        List<Map<String, Object>> collectionCompanies = financeService.collectionCompanyBoard();

        List<SalesOrder> currentYearSalesOrders = ordersInYear(salesOrders, current.getYear());
        DashboardFinancialSummary financial = calculateFinancialSummary(
                currentYearSalesOrders, deliveryNotes, statements, statementItems,
                salesOrderItemRepository.findAll(), deliveryNoteItemRepository.findAll());

        BigDecimal pending = financeService.pendingReconciliationTotal();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("salesTotal", financial.salesTotal());
        summary.put("deliveredTotal", financial.deliveredTotal());
        summary.put("undeliveredTotal", financial.undeliveredTotal());
        summary.put("deliveredUninvoicedTotal", financial.deliveredUninvoicedTotal());
        summary.put("deliveredInvoicedTotal", financial.deliveredInvoicedTotal());
        summary.put("deliveredReconciledTotal", financial.deliveredReconciledTotal());
        summary.put("deliveredUnreconciledTotal", pending);
        summary.put("invoicedUnpaidTotal", collectionInvoiceUnpaidTotal(collectionCompanies));
        summary.put("collectionUnpaidTotal", collectionLedgerBalanceTotal(collectionCompanies));
        summary.put("paidTotal", financial.paidTotal());
        summary.put("yearReceivedTotal", financeService.collectionYearReceivedTotal(current.getYear()));
        summary.put("collectionYear", current.getYear());
        summary.put("currentOutstandingTotal", collectionLedgerBalanceTotal(collectionCompanies)
                .add(pending));
        summary.put("arrearsTotal", financial.arrearsTotal());

        // Compatibility keys retained for existing clients of the dashboard endpoint.
        summary.put("invoicedTotal", financial.deliveredInvoicedTotal());
        summary.put("receivedTotal", financial.paidTotal());
        summary.put("uninvoicedTotal", financial.deliveredUninvoicedTotal());
        data.put("summary", summary);

        YearMonth defaultTrendStart = current.minusMonths(11);
        YearMonth earliestOrderMonth = salesOrders.stream()
                .filter(order -> order.orderDate != null)
                .map(order -> YearMonth.from(order.orderDate))
                .filter(month -> !month.isAfter(current))
                .min(YearMonth::compareTo)
                .orElse(defaultTrendStart);
        YearMonth trendStart = earliestOrderMonth.isBefore(defaultTrendStart)
                ? earliestOrderMonth : defaultTrendStart;
        YearMonth trendEnd = current;
        List<String> months = new ArrayList<>();
        for (YearMonth ym = trendStart; !ym.isAfter(trendEnd); ym = ym.plusMonths(1)) {
            months.add(ym.toString());
        }
        data.put("trend", Map.of(
                "months", months,
                "sales", monthlySalesTrend(salesOrders, trendStart, trendEnd),
                "startMonth", trendStart.toString(),
                "endMonth", trendEnd.toString()
        ));
        data.put("customerRankingYear", current.getYear());
        data.put("customerRanking", customerSalesRanking(currentYearSalesOrders, 10));
        data.put("customerDebtRanking", customerDebtRanking(collectionCompanies, 10));

        return ApiResponse.ok(data);
    }

    static List<BigDecimal> monthlySalesTrend(Collection<SalesOrder> salesOrders,
                                              YearMonth startMonth, YearMonth endMonth) {
        if (startMonth.isAfter(endMonth)) return List.of();
        int monthCount = Math.toIntExact(ChronoUnit.MONTHS.between(startMonth, endMonth) + 1);
        List<BigDecimal> monthlySales = new ArrayList<>(Collections.nCopies(monthCount, BigDecimal.ZERO));
        for (SalesOrder order : salesOrders) {
            if (order.orderDate == null || Boolean.TRUE.equals(order.deleted)) {
                continue;
            }
            YearMonth orderMonth = YearMonth.from(order.orderDate);
            if (orderMonth.isBefore(startMonth) || orderMonth.isAfter(endMonth)) continue;
            int monthIndex = Math.toIntExact(ChronoUnit.MONTHS.between(startMonth, orderMonth));
            monthlySales.set(monthIndex, monthlySales.get(monthIndex).add(MoneyUtil.nvl(order.totalAmount)));
        }
        return monthlySales.stream().map(MoneyUtil::money).toList();
    }

    static List<CustomerSalesRankingRow> customerSalesRanking(List<SalesOrder> salesOrders, int limit) {
        if (salesOrders == null || salesOrders.isEmpty() || limit <= 0) return List.of();
        return salesOrders.stream()
                .filter(order -> !Boolean.TRUE.equals(order.deleted))
                .collect(Collectors.groupingBy(DashboardController::customerRankingKey,
                        LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(orders -> {
                    String customerName = orders.stream()
                            .map(order -> order.customerName == null ? "" : order.customerName.trim())
                            .filter(name -> !name.isBlank())
                            .findFirst().orElse("未知客户");
                    BigDecimal amount = MoneyUtil.money(orders.stream()
                            .map(order -> MoneyUtil.nvl(order.totalAmount))
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
                    return new CustomerSalesRankingRow(customerName, amount, orders.size());
                })
                .filter(row -> row.amount().signum() > 0)
                .sorted(Comparator.comparing(CustomerSalesRankingRow::amount).reversed()
                        .thenComparing(CustomerSalesRankingRow::customerName))
                .limit(limit)
                .toList();
    }

    static List<CustomerDebtRankingRow> customerDebtRanking(Collection<Map<String, Object>> collectionCompanies,
                                                            int limit) {
        if (collectionCompanies == null || collectionCompanies.isEmpty() || limit <= 0) return List.of();
        return collectionCompanies.stream()
                .map(row -> new CustomerDebtRankingRow(
                        displayCustomerName(row.get("customerName")),
                        moneyValue(row.get("arrearsAmount")),
                        integerValue(row.get("unpaidStatementCount"))))
                .filter(row -> row.amount().signum() > 0)
                .sorted(Comparator.comparing(CustomerDebtRankingRow::amount).reversed()
                        .thenComparing(CustomerDebtRankingRow::customerName))
                .limit(limit)
                .toList();
    }

    static BigDecimal collectionLedgerBalanceTotal(Collection<Map<String, Object>> collectionCompanies) {
        if (collectionCompanies == null) return MoneyUtil.money(BigDecimal.ZERO);
        // The original workbook balance is a comparison baseline, never a live debt calculation.
        return MoneyUtil.money(collectionCompanies.stream()
                .map(row -> moneyValue(row.get("arrearsAmount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    static BigDecimal collectionInvoiceUnpaidTotal(Collection<Map<String, Object>> collectionCompanies) {
        if (collectionCompanies == null) return MoneyUtil.money(BigDecimal.ZERO);
        return MoneyUtil.money(collectionCompanies.stream()
                .map(row -> moneyValue(row.get("invoicedUnpaidAmount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static String displayCustomerName(Object value) {
        String name = value == null ? "" : value.toString().trim();
        return name.isBlank() ? "未知客户" : name;
    }

    private static BigDecimal moneyValue(Object value) {
        if (value instanceof BigDecimal amount) return MoneyUtil.money(amount);
        if (value instanceof Number amount) return MoneyUtil.money(new BigDecimal(amount.toString()));
        return MoneyUtil.money(BigDecimal.ZERO);
    }

    private static int integerValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static String customerRankingKey(SalesOrder order) {
        if (order.customerId != null) return "id:" + order.customerId;
        String name = order.customerName == null ? "" : order.customerName.trim();
        return "name:" + (name.isBlank() ? "未知客户" : name.toLowerCase(Locale.ROOT));
    }

    static DashboardFinancialSummary calculateFinancialSummary(List<SalesOrder> salesOrders,
                                                                List<DeliveryNote> deliveryNotes,
                                                                List<Statement> statements,
                                                                List<StatementItem> statementItems) {
        return calculateFinancialSummary(salesOrders, deliveryNotes, statements, statementItems, List.of(), List.of());
    }

    static DashboardFinancialSummary calculateFinancialSummary(List<SalesOrder> salesOrders,
                                                                List<DeliveryNote> deliveryNotes,
                                                                List<Statement> statements,
                                                                List<StatementItem> statementItems,
                                                                List<SalesOrderItem> orderItems,
                                                                List<DeliveryNoteItem> deliveryItems) {
        Map<Long, BigDecimal> orderAmounts = salesOrders.stream()
                .filter(order -> order.id != null && !Boolean.TRUE.equals(order.deleted))
                .collect(Collectors.toMap(order -> order.id, order -> nonNegative(order.totalAmount),
                        BigDecimal::max, LinkedHashMap::new));
        BigDecimal salesTotal = sum(orderAmounts.values());

        Map<Long, SalesOrderItem> orderItemsById = orderItems.stream()
                .filter(item -> item.id != null)
                .collect(Collectors.toMap(item -> item.id, item -> item));
        Map<Long, List<DeliveryNoteItem>> itemsByDelivery = deliveryItems.stream()
                .filter(item -> item.deliveryId != null && !Boolean.TRUE.equals(item.deleted))
                .collect(Collectors.groupingBy(item -> item.deliveryId));
        Map<String, Long> orderIdsByNo = salesOrders.stream()
                .filter(order -> order.orderNo != null && orderAmounts.containsKey(order.id))
                .collect(Collectors.toMap(order -> order.orderNo, order -> order.id, (first, ignored) -> first));
        Map<Long, Long> eligibleItemOrders = new HashMap<>();
        Map<Long, Map<Long, BigDecimal>> amountsByDeliveryOrder = new HashMap<>();

        Map<Long, BigDecimal> deliveredByOrder = new LinkedHashMap<>();
        for (DeliveryNote delivery : deliveryNotes) {
            if (delivery.id == null || Boolean.TRUE.equals(delivery.deleted) || !"已发货".equals(delivery.status)) {
                continue;
            }
            Map<Long, BigDecimal> deliveryAmounts = new LinkedHashMap<>();
            List<DeliveryNoteItem> lines = itemsByDelivery.getOrDefault(delivery.id, List.of());
            if (lines.isEmpty()) {
                // Compatibility for legacy deliveries with only a header and no product lines.
                if (orderAmounts.containsKey(delivery.orderId)) {
                    deliveryAmounts.put(delivery.orderId, nonNegative(delivery.totalAmount));
                }
            } else {
                for (DeliveryNoteItem line : lines) {
                    SalesOrderItem orderItem = orderItemsById.get(line.orderItemId);
                    Long orderId = orderItem == null ? delivery.orderId : orderItem.orderId;
                    if (orderItem != null && Boolean.TRUE.equals(orderItem.deleted)) continue;
                    if (!orderAmounts.containsKey(orderId)) continue;
                    deliveryAmounts.merge(orderId, nonNegative(line.amount), BigDecimal::add);
                    if (line.id != null) eligibleItemOrders.put(line.id, orderId);
                }
            }
            deliveryAmounts.forEach((id, amount) -> deliveredByOrder.merge(id, amount, BigDecimal::add));
            amountsByDeliveryOrder.put(delivery.id, deliveryAmounts);
        }

        BigDecimal deliveredTotal = orderAmounts.entrySet().stream()
                .map(entry -> entry.getValue().min(nonNegative(deliveredByOrder.get(entry.getKey()))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        deliveredTotal = MoneyUtil.money(deliveredTotal);
        BigDecimal undeliveredTotal = MoneyUtil.money(salesTotal.subtract(deliveredTotal).max(BigDecimal.ZERO));

        Map<Long, Statement> activeStatements = statements.stream()
                .filter(statement -> statement.id != null && !Boolean.TRUE.equals(statement.deleted))
                .collect(Collectors.toMap(statement -> statement.id, statement -> statement,
                        (first, ignored) -> first, LinkedHashMap::new));
        Map<Long, BigDecimal> orderAmountByStatement = new LinkedHashMap<>();
        for (StatementItem item : statementItems) {
            if (Boolean.TRUE.equals(item.deleted) || !activeStatements.containsKey(item.statementId)) continue;
            Map<Long, BigDecimal> deliveryOrders = amountsByDeliveryOrder.get(item.deliveryId);
            if (deliveryOrders == null || deliveryOrders.isEmpty()) continue;
            if (item.deliveryItemId != null && !eligibleItemOrders.containsKey(item.deliveryItemId)) continue;
            Long orderId = orderIdsByNo.get(item.orderNo);
            if (item.deliveryItemId == null && item.orderNo != null
                    && (orderId == null || !deliveryOrders.containsKey(orderId))) continue;
            orderAmountByStatement.merge(item.statementId, MoneyUtil.nvl(item.amount), BigDecimal::add);
        }

        BigDecimal deliveredInvoicedTotal = BigDecimal.ZERO;
        BigDecimal paidTotal = BigDecimal.ZERO;
        BigDecimal deliveredReconciledTotal = BigDecimal.ZERO;
        BigDecimal invoicedUnpaidTotal = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> entry : orderAmountByStatement.entrySet()) {
            Statement statement = activeStatements.get(entry.getKey());
            BigDecimal statementOrderAmount = nonNegative(entry.getValue())
                    .min(nonNegative(statement.receivableAmount));
            deliveredReconciledTotal = deliveredReconciledTotal.add(statementOrderAmount);
            // Invoices/receipts belong to the whole statement. If it also contains historical
            // balances without orders, allocate proportionally rather than count their payments twice.
            BigDecimal invoiced = orderBackedAmount(statement.invoicedAmount, statementOrderAmount, statement.receivableAmount);
            BigDecimal paid = orderBackedAmount(statement.receivedAmount, statementOrderAmount, statement.receivableAmount);

            deliveredInvoicedTotal = deliveredInvoicedTotal.add(invoiced);
            paidTotal = paidTotal.add(paid);
            invoicedUnpaidTotal = invoicedUnpaidTotal.add(invoiced.subtract(paid).max(BigDecimal.ZERO));
        }

        deliveredInvoicedTotal = MoneyUtil.money(deliveredInvoicedTotal.min(deliveredTotal));
        paidTotal = MoneyUtil.money(paidTotal.min(deliveredTotal));
        BigDecimal arrearsTotal = MoneyUtil.money(deliveredTotal.subtract(paidTotal).max(BigDecimal.ZERO));
        invoicedUnpaidTotal = MoneyUtil.money(invoicedUnpaidTotal.min(arrearsTotal));
        BigDecimal deliveredUninvoicedTotal = MoneyUtil.money(
                deliveredTotal.subtract(deliveredInvoicedTotal).max(BigDecimal.ZERO));

        return new DashboardFinancialSummary(
                salesTotal,
                deliveredTotal,
                undeliveredTotal,
                deliveredInvoicedTotal,
                deliveredUninvoicedTotal,
                invoicedUnpaidTotal,
                paidTotal,
                arrearsTotal,
                MoneyUtil.money(deliveredReconciledTotal.min(deliveredTotal)),
                MoneyUtil.money(deliveredTotal.subtract(deliveredReconciledTotal.min(deliveredTotal)).max(BigDecimal.ZERO))
        );
    }

    static List<SalesOrder> ordersInYear(Collection<SalesOrder> salesOrders, int year) {
        return salesOrders.stream()
                .filter(order -> order.orderDate != null && order.orderDate.getYear() == year)
                .toList();
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        return MoneyUtil.nvl(value).max(BigDecimal.ZERO);
    }

    private static BigDecimal orderBackedAmount(BigDecimal amount, BigDecimal orderAmount, BigDecimal statementAmount) {
        BigDecimal total = nonNegative(statementAmount);
        if (total.signum() == 0) return MoneyUtil.money(BigDecimal.ZERO);
        BigDecimal numerator = nonNegative(amount).min(total).multiply(orderAmount);
        try { return numerator.divide(total); }
        catch (ArithmeticException repeatingDecimal) {
            return numerator.divide(total, java.math.MathContext.DECIMAL128);
        }
    }

    private static BigDecimal sum(Collection<BigDecimal> values) {
        return MoneyUtil.money(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    record DashboardFinancialSummary(BigDecimal salesTotal,
                                     BigDecimal deliveredTotal,
                                     BigDecimal undeliveredTotal,
                                     BigDecimal deliveredInvoicedTotal,
                                     BigDecimal deliveredUninvoicedTotal,
                                     BigDecimal invoicedUnpaidTotal,
                                     BigDecimal paidTotal,
                                     BigDecimal arrearsTotal,
                                     BigDecimal deliveredReconciledTotal,
                                     BigDecimal deliveredUnreconciledTotal) {
    }

    record CustomerSalesRankingRow(String customerName, BigDecimal amount, int orderCount) {
    }

    record CustomerDebtRankingRow(String customerName, BigDecimal amount, int unpaidCount) {
    }

}
