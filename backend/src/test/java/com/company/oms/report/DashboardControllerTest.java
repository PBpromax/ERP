package com.company.oms.report;

import com.company.oms.finance.Statement;
import com.company.oms.finance.StatementItem;
import com.company.oms.sales.DeliveryNote;
import com.company.oms.sales.DeliveryNoteItem;
import com.company.oms.sales.SalesOrder;
import com.company.oms.sales.SalesOrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardControllerTest {


    @Test
    void monthlySalesTrendReturnsEveryMonthInTheRequestedRange() {
        SalesOrder january = order(1L, "100.00");
        january.orderDate = LocalDate.of(2026, 1, 5);
        SalesOrder december = order(2L, "250.00");
        december.orderDate = LocalDate.of(2026, 12, 10);
        SalesOrder historical = order(3L, "999.00");
        historical.orderDate = LocalDate.of(2025, 6, 1);
        SalesOrder deleted = order(4L, "888.00");
        deleted.orderDate = LocalDate.of(2026, 6, 1);
        deleted.deleted = true;

        List<BigDecimal> trend = DashboardController.monthlySalesTrend(
                List.of(january, december, historical, deleted),
                YearMonth.of(2025, 11), YearMonth.of(2026, 12));

        assertEquals(14, trend.size());
        assertMoney("100.00", trend.get(2));
        assertMoney("0.00", trend.get(7));
        assertMoney("250.00", trend.get(13));
    }

    @Test
    void dashboardYearFilterExcludesHistoricalOrders() {
        SalesOrder current = order(1L, "1000.00");
        current.orderDate = LocalDate.of(2026, 1, 1);
        SalesOrder historical = order(2L, "500.00");
        historical.orderDate = LocalDate.of(2025, 12, 31);
        SalesOrder undated = order(3L, "300.00");

        List<SalesOrder> filtered = DashboardController.ordersInYear(
                List.of(current, historical, undated), 2026);
        DashboardController.DashboardFinancialSummary summary = DashboardController.calculateFinancialSummary(
                filtered, List.of(), List.of(), List.of());

        assertEquals(List.of(current), filtered);
        assertMoney("1000.00", summary.salesTotal());
    }

    @Test
    void customerRankingUsesCurrentYearOrdersAndGroupsByCustomer() {
        SalesOrder first = order(1L, "100.00");
        first.customerId = 10L;
        first.customerName = "甲公司";
        first.orderDate = LocalDate.of(2026, 2, 1);
        SalesOrder second = order(2L, "250.00");
        second.customerId = 10L;
        second.customerName = "甲公司";
        second.orderDate = LocalDate.of(2026, 3, 1);
        SalesOrder other = order(3L, "300.00");
        other.customerId = 20L;
        other.customerName = "乙公司";
        other.orderDate = LocalDate.of(2026, 3, 2);
        SalesOrder historical = order(4L, "9999.00");
        historical.customerId = 30L;
        historical.customerName = "历史公司";
        historical.orderDate = LocalDate.of(2025, 12, 31);

        var ranking = DashboardController.customerSalesRanking(
                DashboardController.ordersInYear(List.of(first, second, other, historical), 2026), 10);

        assertEquals(2, ranking.size());
        assertEquals("甲公司", ranking.get(0).customerName());
        assertMoney("350.00", ranking.get(0).amount());
        assertEquals(2, ranking.get(0).orderCount());
        assertEquals("乙公司", ranking.get(1).customerName());
        assertMoney("300.00", ranking.get(1).amount());
    }

    @Test
    void customerDebtRankingUsesPositiveNetDebtAndSortsDescending() {
        var ranking = DashboardController.customerDebtRanking(List.of(
                collectionCompany("乙公司", "180.00", 2, "30.00"),
                collectionCompany("已结清公司", "0.00", 0, "0.00"),
                collectionCompany("甲公司", "350.00", 3, "50.00"),
                collectionCompany("预收公司", "-20.00", 0, "0.00")
        ), 10);

        assertEquals(2, ranking.size());
        assertEquals("甲公司", ranking.get(0).customerName());
        assertMoney("350.00", ranking.get(0).amount());
        assertEquals(3, ranking.get(0).unpaidCount());
        assertEquals("乙公司", ranking.get(1).customerName());
        assertMoney("180.00", ranking.get(1).amount());
    }

    @Test
    void collectionLedgerBalanceUsesLiveDebtNotStaleWorkbookAndPreservesCredits() {
        Map<String, Object> imported = collectionCompany("历史账页", "350.00", 3, "50.00");
        imported.put("sourceNetAmount", new BigDecimal("180.00"));
        Map<String, Object> credit = collectionCompany("预收账页", "-20.00", 0, "0.00");
        credit.put("sourceNetAmount", new BigDecimal("-20.00"));

        assertMoney("370.00", DashboardController.collectionLedgerBalanceTotal(List.of(
                imported,
                credit,
                collectionCompany("系统新客户", "40.00", 1, "30.00")
        )));
        assertMoney("50.00", DashboardController.collectionInvoiceUnpaidTotal(List.of(imported, credit)));
        imported.put("arrearsAmount", new BigDecimal("250.00"));
        assertMoney("230.00", DashboardController.collectionLedgerBalanceTotal(List.of(imported, credit)));
    }

    @Test
    void financialSummaryUsesOnlyOrderBackedAmountsAndAlwaysReconciles() {
        SalesOrder firstOrder = order(1L, "1000.00");
        SalesOrder secondOrder = order(2L, "500.00");

        DeliveryNote firstDelivery = delivery(11L, 1L, "800.00");
        DeliveryNote secondDelivery = delivery(12L, 2L, "200.00");
        DeliveryNote overDelivery = delivery(13L, 2L, "400.00");

        Statement invoicedStatement = statement(21L, "900.00", "900.00", "300.00");
        Statement cashStatement = statement(22L, "500.00", "0.00", "100.00");
        Statement historyOnlyStatement = statement(23L, "999.00", "999.00", "999.00");

        List<StatementItem> items = List.of(
                item(21L, 11L, "800.00"),
                item(21L, null, "100.00"),
                item(22L, 12L, "200.00"),
                item(22L, 13L, "300.00"),
                item(23L, null, "999.00")
        );

        DashboardController.DashboardFinancialSummary summary = DashboardController.calculateFinancialSummary(
                List.of(firstOrder, secondOrder),
                List.of(firstDelivery, secondDelivery, overDelivery),
                List.of(invoicedStatement, cashStatement, historyOnlyStatement),
                items
        );

        assertMoney("1500.00", summary.salesTotal());
        assertMoney("1300.00", summary.deliveredTotal());
        assertMoney("200.00", summary.undeliveredTotal());
        assertMoney("800.00", summary.deliveredInvoicedTotal());
        assertMoney("500.00", summary.deliveredUninvoicedTotal());
        assertMoney("1300.00", summary.deliveredReconciledTotal());
        assertMoney("0.00", summary.deliveredUnreconciledTotal());
        assertMoney("533.3333333333333333333333333333333", summary.invoicedUnpaidTotal());
        assertMoney("366.6666666666666666666666666666667", summary.paidTotal());
        assertMoney("933.3333333333333333333333333333333", summary.arrearsTotal());

        assertEquals(summary.arrearsTotal(), summary.salesTotal()
                .subtract(summary.paidTotal())
                .subtract(summary.undeliveredTotal()));
        assertEquals(summary.deliveredTotal(), summary.deliveredUninvoicedTotal()
                .add(summary.deliveredInvoicedTotal()));
        assertEquals(summary.deliveredTotal(), summary.deliveredUnreconciledTotal()
                .add(summary.deliveredReconciledTotal()));
    }

    @Test
    void combinedDeliveryUsesEachProductOrderInsteadOfTheHeaderOrder() {
        SalesOrder first = order(1L, "100.00");
        SalesOrder second = order(2L, "900.00");
        DeliveryNote combined = delivery(10L, 1L, "550.00");
        var summary = DashboardController.calculateFinancialSummary(
                List.of(first, second), List.of(combined), List.of(), List.of(),
                List.of(orderItem(101L, 1L), orderItem(102L, 2L)),
                List.of(deliveryItem(201L, 10L, 101L, "100.00"), deliveryItem(202L, 10L, 102L, "450.00")));
        assertMoney("1000.00", summary.salesTotal());
        assertMoney("550.00", summary.deliveredTotal());
        assertMoney("450.00", summary.undeliveredTotal());
        assertMoney("550.00", summary.deliveredUninvoicedTotal());
        assertMoney("550.00", summary.arrearsTotal());
    }

    @Test
    void cashPaymentsDoNotReduceUninvoicedAmountAndPartialDeliveryPaymentsReduceDebt() {
        var summary = DashboardController.calculateFinancialSummary(
                List.of(order(1L, "1000.00")), List.of(delivery(10L, 1L, "600.00")),
                List.of(statement(20L, "400.00", "0.00", "150.00")), List.of(item(20L, 10L, "400.00")));
        assertMoney("400.00", summary.undeliveredTotal());
        assertMoney("600.00", summary.deliveredUninvoicedTotal());
        assertMoney("150.00", summary.paidTotal());
        assertMoney("0.00", summary.invoicedUnpaidTotal());
        assertMoney("450.00", summary.arrearsTotal());
    }

    @Test
    void partialInvoiceAndPartialReceiptAreAmountsNotWholeOrderStatusFlags() {
        var summary = DashboardController.calculateFinancialSummary(
                List.of(order(1L, "1000.00")), List.of(delivery(10L, 1L, "800.00")),
                List.of(statement(20L, "600.00", "400.00", "150.00")), List.of(item(20L, 10L, "600.00")));
        assertMoney("400.00", summary.deliveredInvoicedTotal());
        assertMoney("400.00", summary.deliveredUninvoicedTotal());
        assertMoney("250.00", summary.invoicedUnpaidTotal());
        assertMoney("150.00", summary.paidTotal());
        assertMoney("650.00", summary.arrearsTotal());
    }

    @Test
    void deletedOrdersAndDeliveriesDoNotLeakIntoCombinedDeliveryTotals() {
        SalesOrder deleted = order(2L, "900.00");
        deleted.deleted = true;
        DeliveryNote combined = delivery(10L, 2L, "1000.00");
        DeliveryNote deletedDelivery = delivery(11L, 1L, "100.00");
        deletedDelivery.deleted = true;
        StatementItem validLine = item(20L, 10L, "100.00");
        validLine.deliveryItemId = 201L;
        StatementItem deletedLine = item(20L, 10L, "900.00");
        deletedLine.deliveryItemId = 202L;
        var summary = DashboardController.calculateFinancialSummary(
                List.of(order(1L, "100.00"), deleted), List.of(combined, deletedDelivery),
                List.of(statement(20L, "1000.00", "1000.00", "500.00")), List.of(validLine, deletedLine),
                List.of(orderItem(101L, 1L), orderItem(102L, 2L)),
                List.of(deliveryItem(201L, 10L, 101L, "100.00"), deliveryItem(202L, 10L, 102L, "900.00")));
        assertMoney("100.00", summary.salesTotal());
        assertMoney("100.00", summary.deliveredTotal());
        assertMoney("100.00", summary.deliveredInvoicedTotal());
        assertMoney("50.00", summary.paidTotal());
        assertMoney("50.00", summary.arrearsTotal());
    }

    @Test
    void emptyDashboardAndReturnedStatementKeepTheDebtIdentity() {
        var empty = DashboardController.calculateFinancialSummary(List.of(), List.of(), List.of(), List.of());
        assertMoney("0.00", empty.arrearsTotal());
        var summary = DashboardController.calculateFinancialSummary(
                List.of(order(1L, "100.00")), List.of(delivery(10L, 1L, "100.00")),
                List.of(statement(20L, "80.00", "40.00", "30.00")),
                List.of(item(20L, 10L, "100.00"), item(20L, null, "-20.00")));
        assertMoney("40.00", summary.deliveredInvoicedTotal());
        assertMoney("30.00", summary.paidTotal());
        // The requested dashboard formula uses the original order total, not the adjusted statement total.
        assertMoney("70.00", summary.arrearsTotal());
    }

    private static SalesOrderItem orderItem(Long id, Long orderId) {
        SalesOrderItem item = new SalesOrderItem();
        item.id = id;
        item.orderId = orderId;
        return item;
    }

    private static DeliveryNoteItem deliveryItem(Long id, Long deliveryId, Long orderItemId, String amount) {
        DeliveryNoteItem item = new DeliveryNoteItem();
        item.id = id;
        item.deliveryId = deliveryId;
        item.orderItemId = orderItemId;
        item.amount = new BigDecimal(amount);
        return item;
    }

    private static SalesOrder order(Long id, String amount) {
        SalesOrder order = new SalesOrder();
        order.id = id;
        order.totalAmount = new BigDecimal(amount);
        return order;
    }

    private static Map<String, Object> collectionCompany(String name, String arrears,
                                                         int unpaidCount, String invoicedUnpaid) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customerName", name);
        row.put("arrearsAmount", new BigDecimal(arrears));
        row.put("unpaidStatementCount", unpaidCount);
        row.put("invoicedUnpaidAmount", new BigDecimal(invoicedUnpaid));
        return row;
    }

    private static DeliveryNote delivery(Long id, Long orderId, String amount) {
        DeliveryNote delivery = new DeliveryNote();
        delivery.id = id;
        delivery.orderId = orderId;
        delivery.totalAmount = new BigDecimal(amount);
        delivery.status = "已发货";
        return delivery;
    }

    private static Statement statement(Long id, String receivable, String invoiced, String received) {
        Statement statement = new Statement();
        statement.id = id;
        statement.receivableAmount = new BigDecimal(receivable);
        statement.invoicedAmount = new BigDecimal(invoiced);
        statement.receivedAmount = new BigDecimal(received);
        return statement;
    }

    private static StatementItem item(Long statementId, Long deliveryId, String amount) {
        StatementItem item = new StatementItem();
        item.statementId = statementId;
        item.deliveryId = deliveryId;
        item.amount = new BigDecimal(amount);
        return item;
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
