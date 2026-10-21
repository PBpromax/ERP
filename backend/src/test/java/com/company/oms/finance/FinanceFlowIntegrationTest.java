package com.company.oms.finance;

import com.company.oms.common.BusinessException;
import com.company.oms.common.DocumentNumberService;
import com.company.oms.masterdata.Customer;
import com.company.oms.masterdata.CustomerRepository;
import com.company.oms.masterdata.Product;
import com.company.oms.masterdata.ProductRepository;
import com.company.oms.quality.Attachment;
import com.company.oms.quality.AttachmentRepository;
import com.company.oms.sales.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FinanceFlowIntegrationTest {
    @Autowired SalesService salesService;
    @Autowired FinanceService financeService;
    @Autowired CustomerRepository customerRepository;
    @Autowired ProductRepository productRepository;
    @Autowired QuotationRepository quotationRepository;
    @Autowired QuotationItemRepository quotationItemRepository;
    @Autowired SalesOrderItemRepository salesOrderItemRepository;
    @Autowired DeliveryNoteRepository deliveryNoteRepository;
    @Autowired StatementRepository statementRepository;
    @Autowired CustomerLedgerAccountRepository ledgerAccountRepository;
    @Autowired CustomerLedgerEntryRepository ledgerEntryRepository;
    @Autowired DocumentNumberService documentNumberService;
    @Autowired HistoricalStatementLinkMigration historicalStatementLinkMigration;
    @Autowired DeliveryNoteItemRepository deliveryNoteItemRepository;
    @Autowired StatementItemRepository statementItemRepository;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired ReceiptRepository receiptRepository;
    @Autowired ReceiptPaymentRepository paymentRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired SalesOrderRepository salesOrderRepository;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager precisionEntityManager;

    @Autowired SalesReturnRepository salesReturnRepository;

    @Test
    void dashboardPendingTotalMatchesActualBoardAcrossHistoryExclusionsReturnsAndStatementAdjustments() {
        var dashboard = new com.company.oms.report.DashboardController(
                salesOrderRepository, deliveryNoteRepository, statementRepository,
                statementItemRepository, salesOrderItemRepository, deliveryNoteItemRepository, financeService);
        BigDecimal baseline = financeService.pendingReconciliationTotal();
        Customer c = customer("首页待对账统一统计");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var order = quotationToOrder(c, "PENDING-CURRENT", new BigDecimal("100"), new BigDecimal("1.2346"));
        var line = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0);
        var delivery = historicalDelivery(order, line, "PENDING-CURRENT", today, "100");
        var deliveryItem = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id).get(0);
        deliveryItem.historicalReconciledQty = new BigDecimal("10");
        deliveryNoteItemRepository.saveAndFlush(deliveryItem);
        var partial = financeService.generateStatementsFromSelections(today.toString().substring(0, 7),
                List.of(new FinanceService.StatementDeliverySelection(delivery.id, new BigDecimal("20")))).get(0);
        var discount = new FinanceService.StatementAmountRequest();
        discount.receivableAmount = new BigDecimal("23.692");
        financeService.updateStatementAmount(partial.id, discount); // A discount does not make delivered quantity pending again.

        var prior = quotationToOrder(c, "PENDING-PRIOR", new BigDecimal("10"), new BigDecimal("2"));
        prior.orderDate = today.minusYears(1); salesOrderRepository.save(prior);
        var priorLine = salesOrderItemRepository.findByOrderIdAndDeletedFalse(prior.id).get(0);
        historicalDelivery(prior, priorLine, "PENDING-PRIOR", today.minusYears(1), "10");
        var excludedOrder = quotationToOrder(c, "PENDING-EXCLUDED", new BigDecimal("5"), new BigDecimal("3"));
        var excludedLine = salesOrderItemRepository.findByOrderIdAndDeletedFalse(excludedOrder.id).get(0);
        var excluded = historicalDelivery(excludedOrder, excludedLine, "PENDING-EXCLUDED", today, "5");
        excluded.reconciliationExcluded = true; deliveryNoteRepository.saveAndFlush(excluded);
        SalesReturn returned = new SalesReturn(); returned.customerId = c.id; returned.customerName = c.name;
        returned.orderId = order.id; returned.orderNo = order.orderNo; returned.productName = line.productName;
        returned.returnDate = today; returned.qty = new BigDecimal("2");
        returned.unitPrice = new BigDecimal("1.2346"); returned.amount = new BigDecimal("2.4692");
        salesReturnRepository.saveAndFlush(returned);

        BigDecimal expected = baseline.add(new BigDecimal("103.9528")); // 70 × 1.2346 + 10 × 2 − 2 × 1.2346
        assertPendingDashboard(dashboard, expected);
        var generated = financeService.generateStatementsFromSelections(today.toString().substring(0, 7),
                List.of(new FinanceService.StatementDeliverySelection(delivery.id, new BigDecimal("5")))).get(0);
        assertPendingDashboard(dashboard, expected.subtract(new BigDecimal("6.173")));
        financeService.deleteStatement(generated.id);
        assertPendingDashboard(dashboard, expected);
    }

    private void assertPendingDashboard(com.company.oms.report.DashboardController dashboard, BigDecimal expected) {
        assertMoney(expected.toPlainString(), financeService.pendingReconciliationTotal());
        var live = dashboard.collectionSummary().data;
        assertMoney(expected.toPlainString(), (BigDecimal) live.get("deliveredUnreconciledTotal"));
        assertMoney(expected.add((BigDecimal) live.get("collectionUnpaidTotal")).toPlainString(),
                (BigDecimal) live.get("currentOutstandingTotal"));
        @SuppressWarnings("unchecked")
        var summary = (Map<String, Object>) dashboard.dashboard().data.get("summary");
        assertMoney(expected.toPlainString(), (BigDecimal) summary.get("deliveredUnreconciledTotal"));
        assertMoney(expected.add((BigDecimal) summary.get("collectionUnpaidTotal")).toPlainString(),
                (BigDecimal) summary.get("currentOutstandingTotal"));
    }

    @Test
    void dashboardCollectionsRefreshAfterBalanceChangesWithoutCachedBaseline() {
        var dashboardController = new com.company.oms.report.DashboardController(
                salesOrderRepository, deliveryNoteRepository, statementRepository,
                statementItemRepository, salesOrderItemRepository, deliveryNoteItemRepository, financeService);
        BigDecimal before = (BigDecimal) dashboardController.collectionSummary().data.get("collectionUnpaidTotal");
        Statement statement = new Statement();
        statement.statementNo = "TEST-LIVE-DASHBOARD";
        statement.customerId = customer("首页自动同步验证").id;
        statement.receivableAmount = new BigDecimal("32000.00");
        statement.arrearsAmount = new BigDecimal("32000.00");
        statement.invoicedAmount = new BigDecimal("24000.00");
        statement.receivedAmount = BigDecimal.ZERO;
        statementRepository.saveAndFlush(statement);
        assertMoney(before.add(new BigDecimal("32000.00")).toPlainString(),
                (BigDecimal) dashboardController.collectionSummary().data.get("collectionUnpaidTotal"));
        statement.receivedAmount = new BigDecimal("10500.00");
        statement.arrearsAmount = new BigDecimal("21500.00");
        statementRepository.saveAndFlush(statement);
        assertMoney(before.add(new BigDecimal("21500.00")).toPlainString(),
                (BigDecimal) dashboardController.collectionSummary().data.get("collectionUnpaidTotal"));
        var refreshed = dashboardController.collectionSummary().data;
        assertEquals(((BigDecimal) refreshed.get("collectionUnpaidTotal"))
                        .add((BigDecimal) refreshed.get("deliveredUnreconciledTotal")),
                refreshed.get("currentOutstandingTotal"));
    }

    @Test
    void nonInvoiceRemarkCannotTurnCashReceivableIntoInvoiceDebt() {
        Statement statement = new Statement();
        statement.statementNo = "TEST-NON-INVOICE";
        statement.customerId = customer("不开票验证").id;
        statement.receivableAmount = new BigDecimal("4200");
        statement.settlementMethod = "CASH";
        statementRepository.save(statement);
        Invoice invoice = new Invoice();
        invoice.statementId = statement.id;
        invoice.invoiceNo = "不开票";
        invoice.amount = new BigDecimal("4200");
        assertThrows(BusinessException.class, () -> financeService.registerInvoice(invoice));
        assertEquals("CASH", statement.settlementMethod);
    }

    @Test
    void settlementLabelRepairPreservesDebtAndReconciliationAndIsIdempotent() {
        Statement statement = new Statement();
        statement.statementNo = "TEST-REPAIR-NON-INVOICE";
        statement.customerId = customer("结算备注修复验证").id;
        statement.receivableAmount = new BigDecimal("4200.00");
        statement.invoicedAmount = new BigDecimal("4200.00");
        statement.sourceInvoicedAmount = new BigDecimal("4200.00");
        statement.sourceAmountOnly = true;
        statement.receivedAmount = BigDecimal.ZERO;
        statement.arrearsAmount = new BigDecimal("4200.00");
        statement.settlementMethod = "INVOICE";
        statementRepository.saveAndFlush(statement);
        Invoice invalid = new Invoice();
        invalid.statementId = statement.id;
        invalid.invoiceNo = "不开票";
        invalid.amount = new BigDecimal("4200.00");
        invoiceRepository.saveAndFlush(invalid);
        DeliveryNote delivery = new DeliveryNote();
        delivery.deliveryNo = "TEST-REPAIR-DELIVERY";
        delivery.statementId = statement.id;
        delivery.statementNo = statement.statementNo;
        delivery.invoiced = true;
        delivery.invoiceNo = "不开票";
        deliveryNoteRepository.saveAndFlush(delivery);
        StatementItem item = new StatementItem();
        item.statementId = statement.id;
        item.deliveryId = delivery.id;
        item.amount = new BigDecimal("4200.00");
        statementItemRepository.saveAndFlush(item);
        assertEquals(1, financeService.repairSettlementLabelsImportedAsInvoices());
        assertEquals(0, statement.invoicedAmount.signum());
        assertEquals(new BigDecimal("4200.00"), statement.receivableAmount);
        assertEquals(new BigDecimal("4200.00"), statement.arrearsAmount);
        assertEquals(0, statement.receivedAmount.signum());
        assertEquals("CASH", statement.settlementMethod);
        assertTrue(invalid.deleted);
        assertFalse(delivery.invoiced);
        assertEquals(statement.id, delivery.statementId);
        assertEquals(statement.statementNo, delivery.statementNo);
        assertEquals(0, financeService.repairSettlementLabelsImportedAsInvoices());
    }

    @Test
    void privateCardIsNotAnInvoiceNumber() {
        Statement statement = new Statement();
        statement.statementNo = "TEST-PRIVATE-CARD";
        statement.customerId = customer("私卡验证").id;
        statement.receivableAmount = new BigDecimal("100.00");
        statementRepository.saveAndFlush(statement);
        Invoice invoice = new Invoice();
        invoice.statementId = statement.id;
        invoice.invoiceNo = "私卡";
        invoice.amount = new BigDecimal("100.00");
        assertThrows(BusinessException.class, () -> financeService.registerInvoice(invoice));
    }

    @Test
    void yearlyCollectionsIncludeOffsetsAdvancesAndLegacyButNotAllocationDuplicates() {
        BigDecimal before = financeService.collectionYearReceivedTotal(2026);
        Customer customer = customer("全年回款统计验证");
        String group = "customer-" + customer.id;
        ReceiptPayment payment = yearPayment(group, "YEAR-CASH", "100.00", LocalDate.of(2026, 2, 1), "现汇");
        yearPayment(group, "YEAR-OFFSET", "70.00", LocalDate.of(2026, 3, 1), "账款调减");
        yearPayment(group, "YEAR-ADVANCE", "30.00", LocalDate.of(2026, 4, 1), "货物抵款");
        yearPayment(group, "YEAR-OLD", "999.00", LocalDate.of(2025, 12, 31), "现金");
        yearPayment(group, "YEAR-NEXT", "888.00", LocalDate.of(2027, 1, 1), "现金");
        ReceiptPayment deleted = yearPayment(group, "YEAR-DELETED", "777.00", LocalDate.of(2026, 1, 1), "现金");
        deleted.deleted = true;
        paymentRepository.saveAndFlush(deleted);
        for (String amount : List.of("80.00", "20.00")) {
            Receipt allocation = new Receipt();
            allocation.paymentId = payment.id;
            allocation.receiptDate = payment.paymentDate;
            allocation.amount = new BigDecimal(amount);
            receiptRepository.saveAndFlush(allocation);
        }
        Receipt legacy = new Receipt();
        legacy.customerId = customer.id;
        legacy.customerLevelPayment = true;
        legacy.receiptDate = LocalDate.of(2026, 5, 1);
        legacy.amount = new BigDecimal("20.00");
        receiptRepository.saveAndFlush(legacy);
        assertMoney(before.add(new BigDecimal("220.00")).toPlainString(), financeService.collectionYearReceivedTotal(2026));
    }

    private ReceiptPayment yearPayment(String group, String no, String amount, LocalDate date, String method) {
        ReceiptPayment payment = new ReceiptPayment();
        payment.groupKey = group;
        payment.paymentNo = no;
        payment.amount = new BigDecimal(amount);
        payment.unallocatedAmount = payment.amount;
        payment.paymentDate = date;
        payment.method = method;
        return paymentRepository.saveAndFlush(payment);
    }

    @Test
    void nonCashReductionHasDistinctRecordTypeAndEstimatedDateWarning() {
        Customer customer = customer("调减验证");
        FinanceService.CustomerPaymentRequest request = new FinanceService.CustomerPaymentRequest();
        request.amount = new BigDecimal("20");
        request.paymentDate = LocalDate.now();
        request.method = "账款调减";
        request.remark = "日期待核实；原表抹零";
        Map<String, Object> result = financeService.registerCustomerPayment("customer-" + customer.id, request);
        assertEquals("ADJUSTMENT", result.get("recordType"));
        assertEquals(true, result.get("readOnly"));
        assertEquals(true, result.get("sourceDateEstimated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fractionalUnitPriceSurvivesDatabaseReloadDeliveryAndStatement() {
        SalesOrder order = quotationToOrder(customer("小数单价客户"), "QT-PRECISION", new BigDecimal("64000"), new BigDecimal("0.115"));
        Long orderId = order.id;
        precisionEntityManager.flush(); precisionEntityManager.clear();
        Quotation quote = quotationRepository.findAll().stream().filter(q -> "QT-PRECISION".equals(q.quoteNo)).findFirst().orElseThrow();
        assertMoney("0.115", quotationItemRepository.findByQuotationIdAndDeletedFalse(quote.id).get(0).unitPrice);
        order = salesOrderRepository.findById(orderId).orElseThrow();
        SalesOrderItem source = salesOrderItemRepository.findByOrderIdAndDeletedFalse(orderId).get(0);
        DeliveryNote first = deliver(order, source, "34000");
        DeliveryNote second = deliver(order, source, "30000");
        Long firstId = first.id, secondId = second.id;
        precisionEntityManager.flush(); precisionEntityManager.clear();
        assertMoney("0.115", deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(firstId).get(0).unitPrice);
        assertMoney("3910.00", deliveryNoteRepository.findById(firstId).orElseThrow().totalAmount);
        var candidate = financeService.statementCandidateDeliveries(YearMonth.now().toString()).stream()
                .filter(row -> firstId.equals(row.get("id"))).findFirst().orElseThrow();
        assertMoney("3910.00", (BigDecimal) candidate.get("amount"));
        assertMoney("0.115", (BigDecimal) ((List<Map<String, Object>>) candidate.get("products")).get(0).get("unitPrice"));
        Statement statement = financeService.generateStatementsFromDeliveries(YearMonth.now().toString(), List.of(firstId, secondId)).get(0);
        Long statementId = statement.id;
        precisionEntityManager.flush(); precisionEntityManager.clear();
        assertMoney("7360.00", statementRepository.findById(statementId).orElseThrow().receivableAmount);
        statementItemRepository.findByStatementIdAndDeletedFalse(statementId)
                .forEach(item -> assertMoney("0.115", item.unitPrice));
        StatementItem returned = financeService.createStatementAdjustment(statementId, adjustment("-100", "0.115"));
        Long returnId = returned.id;
        precisionEntityManager.flush(); precisionEntityManager.clear();
        returned = statementItemRepository.findById(returnId).orElseThrow();
        assertMoney("0.115", returned.unitPrice);
        assertMoney("-11.50", returned.amount);
        financeService.updateStatementAdjustment(statementId, returnId, adjustment("-100", "0.12345"));
        precisionEntityManager.flush(); precisionEntityManager.clear();
        assertMoney("0.12345", statementItemRepository.findById(returnId).orElseThrow().unitPrice);
        assertMoney("-12.345", statementItemRepository.findById(returnId).orElseThrow().amount);
        assertMoney("-0.123456", financeService.createStatementAdjustment(statementId, adjustment("-1", "0.123456")).amount);
    }

    @Test
    @SuppressWarnings("unchecked")
    void companyTurnoverIncludesOnlyCurrentYearActiveOrdersByCustomerWithoutDependingOnBilling() {
        LocalDate yearStart = LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfYear(1);
        Customer customer = customer("营业额客户");
        Customer other = customer("营业额客户");
        CustomerLedgerAccount account = new CustomerLedgerAccount();
        account.customerId = customer.id;
        account.sheetName = "营业额来源";
        account.sourceKey = "TEST:TURNOVER:MAIN";
        ledgerAccountRepository.save(account);
        BigDecimal[] amounts = {new BigDecimal("100.10"), new BigDecimal("200.20"), new BigDecimal("300.30")};
        String[] statuses = {"未发货", "部分发货", "已送"};
        for (int i = 0; i < amounts.length; i++) {
            SalesOrder order = new SalesOrder();
            order.customerId = customer.id; order.customerName = "旧客户名称";
            order.orderNo = "TURNOVER-" + i; order.totalAmount = amounts[i];
            order.orderDate = i == 2 ? yearStart.plusYears(1).minusDays(1) : yearStart.plusMonths(i);
            order.status = statuses[i];
            salesOrderRepository.save(order);
        }
        for (LocalDate excludedDate : new LocalDate[]{yearStart.minusDays(1), yearStart.plusYears(1), null}) {
            SalesOrder excluded = new SalesOrder();
            excluded.customerId = customer.id; excluded.totalAmount = new BigDecimal("7777");
            excluded.orderDate = excludedDate;
            salesOrderRepository.save(excluded);
        }
        SalesOrder deleted = new SalesOrder();
        deleted.customerId = customer.id; deleted.totalAmount = new BigDecimal("9999"); deleted.deleted = true;
        deleted.orderDate = yearStart;
        salesOrderRepository.save(deleted);
        SalesOrder unrelated = new SalesOrder();
        unrelated.customerId = other.id; unrelated.customerName = customer.name; unrelated.totalAmount = new BigDecimal("8888");
        unrelated.orderDate = yearStart;
        salesOrderRepository.save(unrelated);
        Map<String, Object> summary = (Map<String, Object>) financeService.customerSummaryDetail("customer-" + customer.id).get("summary");
        assertMoney("600.60", (BigDecimal) summary.get("totalSalesAmount"));
        assertMoney("0.00", (BigDecimal) summary.get("statementAmount"));

        amounts[0] = new BigDecimal("150.10");
        SalesOrder edited = salesOrderRepository.findByOrderNoStartingWith("TURNOVER-0").get(0);
        edited.totalAmount = amounts[0]; salesOrderRepository.saveAndFlush(edited);
        summary = (Map<String, Object>) financeService.customerSummaryDetail("customer-" + customer.id).get("summary");
        assertMoney("650.60", (BigDecimal) summary.get("totalSalesAmount"));

        CustomerLedgerAccount empty = new CustomerLedgerAccount();
        empty.customerId = customer("没有订单客户").id; empty.sheetName = "没有订单";
        empty.sourceKey = "TEST:TURNOVER:EMPTY";
        ledgerAccountRepository.save(empty);
        summary = (Map<String, Object>) financeService.customerSummaryDetail("customer-" + empty.customerId).get("summary");
        assertMoney("0.00", (BigDecimal) summary.get("totalSalesAmount"));
        CustomerLedgerAccount unmapped = new CustomerLedgerAccount();
        unmapped.sheetName = "未映射营业额账页"; unmapped.sourceKey = "TEST:TURNOVER:UNMAPPED"; ledgerAccountRepository.save(unmapped);
        summary = (Map<String, Object>) financeService.customerSummaryDetail("ledger-" + unmapped.id).get("summary");
        assertNull(summary.get("totalSalesAmount"));

        // Board totals use the same year/customer scope and do not count historical debt.
        ledgerEntryRepository.saveAll(List.of(
                ledgerEntry(account.id, "TEST:TURNOVER:MAIN:ENTRY", 1, "9000", "0", "9000"),
                ledgerEntry(empty.id, "TEST:TURNOVER:EMPTY:ENTRY", 1, "100", "0", "100"),
                ledgerEntry(unmapped.id, "TEST:TURNOVER:UNMAPPED:ENTRY", 1, "100", "0", "100")));
        List<Map<String, Object>> board = financeService.collectionCompanyBoard();
        assertMoney("650.60", (BigDecimal) board.stream()
                .filter(row -> ("customer-" + customer.id).equals(row.get("groupKey")))
                .findFirst().orElseThrow().get("totalSalesAmount"));
        assertMoney("0.00", (BigDecimal) board.stream()
                .filter(row -> ("customer-" + empty.customerId).equals(row.get("groupKey")))
                .findFirst().orElseThrow().get("totalSalesAmount"));
        assertNull(board.stream().filter(row -> ("ledger-" + unmapped.id).equals(row.get("groupKey")))
                .findFirst().orElseThrow().get("totalSalesAmount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void newCustomerAppearsInFinanceWithoutFabricatedTransactionsAndTracksOrders() {
        Customer customer = customer("新建零账款客户");
        Customer removed = customer("已删除零账款客户");
        removed.deleted = true;
        customerRepository.saveAndFlush(removed);
        String key = "customer-" + customer.id;
        var row = financeService.collectionCompanyBoard().stream()
                .filter(r -> key.equals(r.get("groupKey"))).findFirst().orElseThrow();
        assertEquals(0, row.get("statementCount"));
        for (String field : List.of("totalSalesAmount", "receivableAmount", "receivedAmount", "arrearsAmount", "creditBalance"))
            assertMoney("0", (BigDecimal) row.get(field));
        assertTrue(financeService.collectionCompanyBoard().stream()
                .noneMatch(r -> removed.id.equals(r.get("customerId"))));
        assertTrue(financeService.statementCustomerBoard().stream()
                .anyMatch(r -> customer.id.equals(r.get("customerId"))));
        var detail = financeService.collectionCompanyDetail(key);
        assertTrue(((List<?>) detail.get("statements")).isEmpty());
        assertTrue(((List<?>) detail.get("payments")).isEmpty());

        SalesOrder order = quotationToOrder(customer, "QT-NEW-FINANCE", new BigDecimal("10"), new BigDecimal("2.50"));
        SalesOrderItem item = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0);
        DeliveryNote delivery = deliver(order, item, "4");
        Statement statement = financeService.generateStatementsFromDeliveries(YearMonth.now().toString(), List.of(delivery.id)).get(0);
        assertEquals(customer.id, statement.customerId);
        customer.name = "新建客户改名";
        customerRepository.saveAndFlush(customer);
        var rows = financeService.collectionCompanyBoard().stream().filter(r -> key.equals(r.get("groupKey"))).toList();
        assertEquals(1, rows.size());
        assertEquals("新建客户改名", rows.get(0).get("customerName"));
        assertMoney("25", (BigDecimal) rows.get(0).get("totalSalesAmount"));
        assertMoney("10", (BigDecimal) rows.get(0).get("receivableAmount"));
        assertEquals(1, rows.get(0).get("statementCount"));
    }

    @Test
    void invoicedUnpaidTotalUsesAllCollectionHistoryWithoutDuplicatesOrNegativeBalances() {
        BigDecimal totalBefore = financeService.collectionInvoicedUnpaidTotal();
        Customer customer = customer("全部历史开票未付测试客户");
        CustomerLedgerAccount account = ledgerAccount(customer, "全部历开票未付测试账页", "2000", "2000");
        int historicalYear = LocalDate.now(ZoneId.of("Asia/Shanghai")).getYear() - 2;

        Statement historical = statement(customer, "RD-HISTORY-UNPAID-001",
                LocalDate.of(historicalYear, 1, 10), "300");
        historical.receivedAmount = new BigDecimal("100");
        historical.arrearsAmount = new BigDecimal("200");
        statementRepository.save(historical);

        Statement overpaid = statement(customer, "RD-HISTORY-UNPAID-002",
                LocalDate.of(historicalYear, 2, 10), "100");
        overpaid.receivedAmount = new BigDecimal("150");
        overpaid.arrearsAmount = BigDecimal.ZERO;
        statementRepository.save(overpaid);

        Statement excluded = statement(customer, "RD-HISTORY-UNPAID-003",
                LocalDate.of(historicalYear, 3, 10), "1000");
        excluded.collectionExcluded = true;
        statementRepository.save(excluded);

        CustomerLedgerEntry validHistory = ledgerEntry(account.id, "LEDGER:HISTORY:UNPAID:VALID",
                1, "400", "250", "150");
        CustomerLedgerEntry withoutInvoice = ledgerEntry(account.id, "LEDGER:HISTORY:UNPAID:NO-INVOICE",
                2, "500", "0", "500");
        withoutInvoice.invoiceNo = "未登记发票号";
        CustomerLedgerEntry linkedDuplicate = ledgerEntry(account.id, "LEDGER:HISTORY:UNPAID:LINKED",
                3, "700", "0", "700");
        linkedDuplicate.linkedStatementId = historical.id;
        CustomerLedgerEntry deleted = ledgerEntry(account.id, "LEDGER:HISTORY:UNPAID:DELETED",
                4, "900", "0", "900");
        deleted.deleted = true;
        ledgerEntryRepository.saveAllAndFlush(List.of(validHistory, withoutInvoice, linkedDuplicate, deleted));
        statementRepository.flush();

        Map<String, Object> row = financeService.collectionCompanyBoard().stream()
                .filter(item -> customer.id.equals(item.get("customerId")))
                .findFirst().orElseThrow();
        assertMoney("350.00", (BigDecimal) row.get("invoicedUnpaidAmount"));
        assertMoney("350.00", financeService.collectionInvoicedUnpaidTotal().subtract(totalBefore));
    }

    @Test
    void documentSequencesAreIndependentForEachDocumentType() {
        LocalDate date = LocalDate.of(2035, 1, 2);
        String firstQuoteNo = "RA20350102001";
        String firstOrderNo = "RB20350102001";

        assertEquals(firstQuoteNo, salesService.previewNextQuotationNo(date));
        assertEquals(firstOrderNo, salesService.previewNextOrderNo(date));
        assertEquals("RC20350102001", documentNumberService.nextDelivery(date, "test"));
        assertEquals("RD20350102001", documentNumberService.nextStatement(date, "test"));
        assertEquals("RE20350102001", documentNumberService.nextReceipt(date, "test"));
        assertEquals("RF20350102001", documentNumberService.nextPurchaseRequest(date, "test"));
        assertEquals("RG20350102001", documentNumberService.nextQualityReport(date, "test"));
        assertEquals("RH20350102001", documentNumberService.nextProductionPlan(date, "test"));

        Customer customer = customer("独立编号测试客户");
        Quotation quotation = new Quotation();
        quotation.quoteDate = date;
        quotation.customerId = customer.id;
        quotation.customerName = customer.name;

        Product product = new Product();
        product.code = "SEQ-PRODUCT";
        product.name = "独立编号测试产品";
        product.unit = "条";
        product.defaultPrice = BigDecimal.TEN;
        product.enabled = true;
        product = productRepository.save(product);

        QuotationItem item = new QuotationItem();
        item.productId = product.id;
        item.productCode = product.code;
        item.productName = product.name;
        item.unit = product.unit;
        item.qty = BigDecimal.ONE;
        item.unitPrice = BigDecimal.TEN;

        Quotation savedQuotation = salesService.saveQuotationWithItems(quotation, List.of(item));
        assertEquals(firstQuoteNo, savedQuotation.quoteNo);
        assertEquals("RA20350102002", salesService.previewNextQuotationNo(date));
        assertEquals(firstOrderNo, salesService.previewNextOrderNo(date));

        SalesService.QuotationConvertRequest request = new SalesService.QuotationConvertRequest();
        request.orderDate = date;
        request.deliveryDate = date;
        SalesOrder order = salesService.convertQuotationToOrder(savedQuotation.id, request);

        assertEquals(firstOrderNo, order.orderNo);
        assertEquals("RB20350102002", salesService.previewNextOrderNo(date));
    }

    @Test
    void completeBusinessFlowCoversSplitDeliveryStatementsAndCollections() {
        Customer firstCustomer = customer("测试客户甲");
        SalesOrder firstOrder = quotationToOrder(firstCustomer, "QT-FLOW-001", new BigDecimal("10"), new BigDecimal("100"));
        SalesOrderItem firstItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(firstOrder.id).get(0);
        assertEquals(SalesService.DELIVERY_STATUS_UNSHIPPED, firstOrder.status);
        assertNull(firstOrder.actualDeliveryDate);

        LocalDate firstDeliveryDate = LocalDate.now().minusDays(2);
        LocalDate secondDeliveryDate = LocalDate.now().minusDays(1);
        LocalDate finalDeliveryDate = LocalDate.now();
        DeliveryNote firstDelivery = deliver(firstOrder, firstItem, "3", firstDeliveryDate);
        SalesOrder afterFirstDelivery = (SalesOrder) salesService.orderDetail(firstOrder.id).get("order");
        assertEquals(SalesService.DELIVERY_STATUS_PARTIAL, afterFirstDelivery.status);
        assertNull(afterFirstDelivery.actualDeliveryDate);

        DeliveryNote secondDelivery = deliver(firstOrder, firstItem, "3", secondDeliveryDate);
        DeliveryNote thirdDelivery = deliver(firstOrder, firstItem, "4", finalDeliveryDate);
        assertEquals(3, deliveryNoteRepository.findByOrderIdAndDeletedFalse(firstOrder.id).size());
        SalesOrder deliveredOrder = (SalesOrder) salesService.orderDetail(firstOrder.id).get("order");
        assertEquals(SalesService.DELIVERY_STATUS_DELIVERED, deliveredOrder.status);
        assertEquals(finalDeliveryDate, deliveredOrder.actualDeliveryDate);

        salesService.deleteDeliveryInfo(thirdDelivery.id);
        SalesOrder reopenedOrder = (SalesOrder) salesService.orderDetail(firstOrder.id).get("order");
        assertEquals(SalesService.DELIVERY_STATUS_PARTIAL, reopenedOrder.status);
        assertNull(reopenedOrder.actualDeliveryDate);
        thirdDelivery = deliver(firstOrder, firstItem, "4", finalDeliveryDate);
        assertEquals(finalDeliveryDate,
                ((SalesOrder) salesService.orderDetail(firstOrder.id).get("order")).actualDeliveryDate);
        assertThrows(BusinessException.class, () -> deliver(firstOrder, firstItem, "1"));

        Customer secondCustomer = customer("测试客户乙");
        SalesOrder secondOrder = quotationToOrder(secondCustomer, "QT-FLOW-002", new BigDecimal("2"), new BigDecimal("50"));
        SalesOrderItem secondItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(secondOrder.id).get(0);
        DeliveryNote otherCustomerDelivery = deliver(secondOrder, secondItem, "2");

        assertThrows(BusinessException.class, () -> financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(firstDelivery.id, otherCustomerDelivery.id)));

        List<Statement> firstStatements = financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(firstDelivery.id, secondDelivery.id));
        Statement cashStatement = firstStatements.get(0);
        assertMoney("600.00", cashStatement.receivableAmount);

        Statement invoiceStatement = financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(thirdDelivery.id)).get(0);
        assertMoney("400.00", invoiceStatement.receivableAmount);
        assertThrows(BusinessException.class, () -> financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(firstDelivery.id)));
        assertThrows(BusinessException.class, () -> salesService.deleteDeliveryInfo(firstDelivery.id));

        List<Statement> automatic = financeService.generateStatements(YearMonth.now().toString());
        assertEquals(1, automatic.size());
        assertEquals(secondCustomer.id, automatic.get(0).customerId);
        assertTrue(financeService.generateStatements(YearMonth.now().toString()).isEmpty());

        FinanceService.CollectionRegisterRequest cash = new FinanceService.CollectionRegisterRequest();
        cash.method = FinanceService.METHOD_CASH;
        cash.arrived = false;
        financeService.registerCollection(cashStatement.id, cash);
        assertStatus(cashStatement.id, FinanceService.STATUS_CASH_PENDING, "600.00");
        receipt(cashStatement.id, "200");
        assertStatus(cashStatement.id, FinanceService.STATUS_PARTIALLY_PAID, "400.00");
        receipt(cashStatement.id, "400");
        assertStatus(cashStatement.id, FinanceService.STATUS_PAID, "0.00");

        FinanceService.CollectionRegisterRequest invoice = new FinanceService.CollectionRegisterRequest();
        invoice.method = FinanceService.METHOD_INVOICE;
        invoice.invoiceNo = "INV-FLOW-001";
        invoice.amount = new BigDecimal("400");
        invoice.transactionDate = LocalDate.now();
        financeService.registerCollection(invoiceStatement.id, invoice);
        Statement afterInvoice = statementRepository.findById(invoiceStatement.id).orElseThrow();
        assertEquals(FinanceService.STATUS_INVOICED_PENDING, afterInvoice.settlementStatus);
        assertMoney("400.00", afterInvoice.arrearsAmount);
        receipt(invoiceStatement.id, "100");
        assertStatus(invoiceStatement.id, FinanceService.STATUS_PARTIALLY_PAID, "300.00");
        receipt(invoiceStatement.id, "300");
        assertStatus(invoiceStatement.id, FinanceService.STATUS_PAID, "0.00");

        Map<String, Object> summary = financeService.customerSummaryDetail(firstCustomer.id);
        @SuppressWarnings("unchecked") Map<String, Object> totals = (Map<String, Object>) summary.get("summary");
        assertMoney("1000.00", (BigDecimal) totals.get("statementAmount"));
        assertMoney("0.00", (BigDecimal) totals.get("currentDebt"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> statementRows = (List<Map<String, Object>>) summary.get("statements");
        assertEquals(2, statementRows.size());
        assertTrue(statementRows.stream().allMatch(row -> FinanceService.STATUS_PAID.equals(row.get("settlementStatus"))));

        Map<String, Object> customerSummary = financeService.customerSummaries().stream()
                .filter(row -> firstCustomer.id.equals(row.get("customerId")))
                .findFirst()
                .orElseThrow();
        assertEquals("SETTLED", customerSummary.get("accountStatus"));
        assertEquals("已结清", customerSummary.get("status"));
    }

    @Test
    void customerSummaryUsesImportedLedgerAndOnlyTwoAccountStatuses() {
        CustomerLedgerAccount account = new CustomerLedgerAccount();
        account.sourceKey = "LEDGER:ACCOUNT:测试账页";
        account.sheetName = "测试账页";
        account.statementAmount = new BigDecimal("1000");
        account.currentDebt = new BigDecimal("200");
        account.entryCount = 2;
        account.openEntryCount = 1;
        account.creditBalance = BigDecimal.ZERO;
        account.invoiceCutoff = LocalDate.of(2026, 6, 19);
        account.importedAt = LocalDateTime.now();
        account = ledgerAccountRepository.save(account);

        CustomerLedgerEntry paid = ledgerEntry(account.id, "LEDGER:测试账页:ROW:3", 3, "800", "800", "0");
        CustomerLedgerEntry debt = ledgerEntry(account.id, "LEDGER:测试账页:ROW:4", 4, "200", "0", "200");
        ledgerEntryRepository.saveAll(List.of(paid, debt));

        Map<String, Object> row = financeService.customerSummaries().get(0);
        assertEquals("DEBT", row.get("accountStatus"));
        assertEquals("存在欠款", row.get("status"));
        assertMoney("1000.00", (BigDecimal) row.get("statementAmount"));
        assertMoney("200.00", (BigDecimal) row.get("currentDebt"));

        Map<String, Object> detail = financeService.customerSummaryDetail(account.id);
        @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) detail.get("ledgerEntries");
        assertEquals(2, entries.size());
        assertEquals("存在欠款", entries.get(0).get("status"));
        assertEquals("已结清", entries.get(1).get("status"));
        assertTrue((Boolean) entries.get(0).get("ledgerEntry"));
    }

    @Test
    void paidLedgerInvoiceSuppressesDuplicateStatementDebtButKeepsNewSystemDebt() {
        Customer customer = customer("台账去重测试客户");
        CustomerLedgerAccount account = new CustomerLedgerAccount();
        account.sourceKey = "LEDGER:ACCOUNT:台账去重测试客户";
        account.sheetName = customer.name;
        account.statementAmount = new BigDecimal("100");
        account.currentDebt = BigDecimal.ZERO;
        account.entryCount = 1;
        account.openEntryCount = 0;
        account.creditBalance = BigDecimal.ZERO;
        account.importedAt = LocalDateTime.now().minusSeconds(1);
        account = ledgerAccountRepository.save(account);
        Long accountId = account.id;

        Statement importedStatement = statement(customer, "RD20300101010", LocalDate.of(2030, 1, 1), "100");
        invoice(importedStatement, "26322000000000000100", "100");
        CustomerLedgerEntry paidEntry = ledgerEntry(accountId, "LEDGER:DEDUP:ROW:1", 1, "100", "100", "0");
        paidEntry.invoiceNo = "26322000000000000100";
        ledgerEntryRepository.save(paidEntry);

        statement(customer, "RD20300201010", LocalDate.of(2030, 2, 1), "30");
        Statement oldUnmatched = statement(customer, "RD20291201010", LocalDate.of(2029, 12, 1), "40");
        oldUnmatched.createdAt = account.importedAt.minusDays(1);
        statementRepository.save(oldUnmatched);

        Map<String, Object> summary = financeService.customerSummaries().stream()
                .filter(row -> customer.id.equals(row.get("customerId")))
                .findFirst()
                .orElseThrow();
        assertMoney("70.00", (BigDecimal) summary.get("currentDebt"));
        assertEquals("存在欠款", summary.get("status"));

        CustomerLedgerEntry linkedEntry = ledgerEntryRepository.findById(paidEntry.id).orElseThrow();
        assertEquals(importedStatement.id, linkedEntry.linkedStatementId);
        Statement linkedStatement = statementRepository.findById(importedStatement.id).orElseThrow();
        assertMoney("100.00", linkedStatement.historicalReceivedAmount);
        assertMoney("0.00", linkedStatement.arrearsAmount);

        Map<String, Object> detail = financeService.customerSummaryDetail("customer-" + customer.id);
        @SuppressWarnings("unchecked") List<Map<String, Object>> statements = (List<Map<String, Object>>) detail.get("statements");
        @SuppressWarnings("unchecked") List<Map<String, Object>> ledgerEntries = (List<Map<String, Object>>) detail.get("ledgerEntries");
        BigDecimal visibleDebt = statements.stream().map(row -> (BigDecimal) row.get("arrearsAmount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(ledgerEntries.stream().map(row -> (BigDecimal) row.get("arrearsAmount"))
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertMoney("70.00", visibleDebt);
    }

    @Test
    void customerPaymentAllocatesOldestStatementsFirstAndCanBeReversedAsOneBatch() {
        Customer customer = customer("客户级收款测试");
        CustomerLedgerAccount account = new CustomerLedgerAccount();
        account.sourceKey = "LEDGER:ACCOUNT:客户级收款测试";
        account.sheetName = customer.name;
        account.statementAmount = new BigDecimal("300");
        account.currentDebt = new BigDecimal("300");
        account.entryCount = 2;
        account.openEntryCount = 2;
        account.creditBalance = BigDecimal.ZERO;
        account = ledgerAccountRepository.save(account);

        Statement first = statement(customer, "RD20300101001", LocalDate.of(2030, 1, 1), "100");
        Statement second = statement(customer, "RD20300201001", LocalDate.of(2030, 2, 1), "200");
        invoice(first, "26322000000000000001", "100");
        invoice(second, "26322000000000000002", "200");
        CustomerLedgerEntry firstEntry = ledgerEntry(account.id, "LEDGER:PAYMENT:ROW:1", 1, "100", "0", "100");
        firstEntry.transactionDate = LocalDate.of(2030, 1, 1);
        firstEntry.invoiceNo = "26322000000000000001";
        CustomerLedgerEntry secondEntry = ledgerEntry(account.id, "LEDGER:PAYMENT:ROW:2", 2, "200", "0", "200");
        secondEntry.transactionDate = LocalDate.of(2030, 2, 1);
        secondEntry.invoiceNo = "26322000000000000002";
        ledgerEntryRepository.saveAll(List.of(firstEntry, secondEntry));

        FinanceService.CustomerPaymentRequest request = new FinanceService.CustomerPaymentRequest();
        request.amount = new BigDecimal("250");
        request.paymentDate = LocalDate.of(2030, 3, 1);
        request.method = "银行转账";
        request.bankSerial = "BANK-FIFO-001";
        Map<String, Object> result = financeService.registerCustomerPayment(account.id, request);

        @SuppressWarnings("unchecked") List<Map<String, Object>> allocations = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(2, allocations.size());
        assertMoney("100.00", (BigDecimal) allocations.get(0).get("amount"));
        assertEquals(first.statementNo, allocations.get(0).get("statementNo"));
        assertMoney("150.00", (BigDecimal) allocations.get(1).get("amount"));
        assertEquals(second.statementNo, allocations.get(1).get("statementNo"));
        assertStatus(first.id, FinanceService.STATUS_PAID, "0.00");
        assertStatus(second.id, FinanceService.STATUS_PARTIALLY_PAID, "50.00");
        Map<String, Object> afterPayment = financeService.customerSummaries().stream()
                .filter(row -> customer.id.equals(row.get("customerId"))).findFirst().orElseThrow();
        assertMoney("50.00", (BigDecimal) afterPayment.get("currentDebt"));
        assertMoney("300.00", ledgerAccountRepository.findById(account.id).orElseThrow().currentDebt);

        String batchNo = String.valueOf(result.get("paymentBatchNo"));
        assertEquals(2, receiptRepository
                .findByLedgerAccountIdAndPaymentBatchNoAndCustomerLevelPaymentTrueAndDeletedFalse(account.id, batchNo).size());
        financeService.deleteCustomerPayment(account.id, batchNo);

        assertStatus(first.id, FinanceService.STATUS_INVOICED_PENDING, "100.00");
        assertStatus(second.id, FinanceService.STATUS_INVOICED_PENDING, "200.00");
        assertMoney("300.00", ledgerAccountRepository.findById(account.id).orElseThrow().currentDebt);
        assertTrue(receiptRepository
                .findByLedgerAccountIdAndPaymentBatchNoAndCustomerLevelPaymentTrueAndDeletedFalse(account.id, batchNo).isEmpty());
    }

    @Test
    void similarOrAmbiguousInvoiceNumbersNeverCreateHistoricalLinks() {
        Customer customer = customer("发票精确匹配测试客户");
        CustomerLedgerAccount account = ledgerAccount(customer, customer.name, "4000", "0");
        Statement statement = statement(customer, "RD20300102001", LocalDate.of(2030, 1, 2), "4000");
        invoice(statement, "2632200000525554781", "4000");

        CustomerLedgerEntry similar = ledgerEntry(account.id, "LEDGER:EXACT:SIMILAR", 1, "4000", "4000", "0");
        similar.invoiceNo = "26322000005275554781";
        ledgerEntryRepository.save(similar);

        Map<String, Object> summary = financeService.customerSummaries().stream()
                .filter(row -> customer.id.equals(row.get("customerId"))).findFirst().orElseThrow();
        assertMoney("4000.00", (BigDecimal) summary.get("currentDebt"));
        assertNull(ledgerEntryRepository.findById(similar.id).orElseThrow().linkedStatementId);

        Statement duplicate = statement(customer, "RD20300102002", LocalDate.of(2030, 1, 3), "50");
        invoice(duplicate, "2632200000525554781", "50");
        similar.invoiceNo = "2632200000525554781";
        ledgerEntryRepository.save(similar);
        financeService.customerSummaryDetail("customer-" + customer.id);
        assertNull(ledgerEntryRepository.findById(similar.id).orElseThrow().linkedStatementId);
    }

    @Test
    void historicalBaselineIsFrozenAndOrdinaryReceiptsContinueReducingDebt() {
        Customer customer = customer("历史到账基线测试客户");
        CustomerLedgerAccount account = ledgerAccount(customer, customer.name, "100", "0");
        Statement statement = statement(customer, "RD20300301001", LocalDate.of(2030, 3, 1), "100");
        invoice(statement, "26322000000000900001", "100");
        receipt(statement.id, "20");

        CustomerLedgerEntry historical = ledgerEntry(account.id, "LEDGER:BASELINE:1", 1, "100", "50", "0");
        historical.invoiceNo = "26322000000000900001";
        ledgerEntryRepository.save(historical);

        Map<String, Object> detail = financeService.customerSummaryDetail("customer-" + customer.id);
        Statement linked = statementRepository.findById(statement.id).orElseThrow();
        assertMoney("30.00", linked.historicalReceivedAmount);
        assertMoney("50.00", linked.receivedAmount);
        assertMoney("50.00", linked.arrearsAmount);

        @SuppressWarnings("unchecked") List<Map<String, Object>> payments = (List<Map<String, Object>>) detail.get("payments");
        Map<String, Object> baseline = payments.stream()
                .filter(row -> "HISTORICAL_BASELINE".equals(row.get("recordType"))).findFirst().orElseThrow();
        assertMoney("30.00", (BigDecimal) baseline.get("amount"));
        assertEquals(Boolean.TRUE, baseline.get("readOnly"));

        receipt(statement.id, "50");
        Statement paid = statementRepository.findById(statement.id).orElseThrow();
        assertMoney("30.00", paid.historicalReceivedAmount);
        assertMoney("100.00", paid.receivedAmount);
        assertMoney("0.00", paid.arrearsAmount);
        receipt(statement.id, "0.01");
        @SuppressWarnings("unchecked") Map<String, Object> totals = (Map<String, Object>) financeService
                .customerSummaryDetail("customer-" + customer.id).get("summary");
        assertMoney("0.01", (BigDecimal) totals.get("creditBalance"));
    }

    @Test
    void ledgerVoidingAndStatementDeletionRestoreHistoricalEntries() {
        Customer customer = customer("历史欠款作废测试客户");
        CustomerLedgerAccount account = ledgerAccount(customer, customer.name, "82.80", "82.80");
        CustomerLedgerEntry voidable = ledgerEntry(account.id, "LEDGER:VOID:9", 9, "82.80", "0", "82.80");
        ledgerEntryRepository.save(voidable);

        financeService.voidLedgerEntry("customer-" + customer.id, voidable.id);
        assertEquals(Boolean.TRUE, ledgerEntryRepository.findById(voidable.id).orElseThrow().deleted);
        assertMoney("0.00", ledgerAccountRepository.findById(account.id).orElseThrow().currentDebt);

        CustomerLedgerEntry paid = ledgerEntry(account.id, "LEDGER:VOID:PAID", 10, "10", "1", "9");
        ledgerEntryRepository.save(paid);
        assertThrows(BusinessException.class,
                () -> financeService.voidLedgerEntry("customer-" + customer.id, paid.id));

        Statement linkedStatement = statement(customer, "RD20300401001", LocalDate.of(2030, 4, 1), "10");
        CustomerLedgerEntry linked = ledgerEntry(account.id, "LEDGER:VOID:LINKED", 11, "10", "0", "10");
        linked.linkedStatementId = linkedStatement.id;
        ledgerEntryRepository.save(linked);
        assertThrows(BusinessException.class,
                () -> financeService.voidLedgerEntry("customer-" + customer.id, linked.id));

        financeService.deleteStatement(linkedStatement.id);
        assertTrue(statementRepository.findById(linkedStatement.id).isEmpty());
        CustomerLedgerEntry restored = ledgerEntryRepository.findById(linked.id).orElseThrow();
        assertFalse(Boolean.TRUE.equals(restored.deleted));
        assertNull(restored.linkedStatementId);

        Statement protectedStatement = statement(customer, "RD20300401002", LocalDate.of(2030, 4, 2), "10");
        paid.linkedStatementId = protectedStatement.id;
        ledgerEntryRepository.save(paid);
        financeService.deleteStatement(protectedStatement.id);
        assertTrue(statementRepository.findById(protectedStatement.id).isEmpty());
        CustomerLedgerEntry paidRestored = ledgerEntryRepository.findById(paid.id).orElseThrow();
        assertFalse(Boolean.TRUE.equals(paidRestored.deleted));
        assertNull(paidRestored.linkedStatementId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void deletingInvoicedStatementRollsBackDeliveriesInvoicesAndPaymentAllocations() {
        Customer customer = customer("删除对账全链路回档客户");
        SalesOrder order = quotationToOrder(customer, "QT-DELETE-STATEMENT", new BigDecimal("10"), new BigDecimal("10"));
        SalesOrderItem orderItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0);
        DeliveryNote delivery = deliver(order, orderItem, "10");
        Statement wrong = financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(delivery.id)).get(0);
        Statement next = statement(customer, "RD-DELETE-STATEMENT-NEXT",
                LocalDate.now().plusDays(1), "200");

        Invoice invoice = new Invoice();
        invoice.statementId = wrong.id;
        invoice.invoiceNo = "26322000000000990001";
        invoice.invoiceDate = LocalDate.now();
        invoice.amount = new BigDecimal("100");
        invoice.customerId = customer.id;
        invoice.customerName = customer.name;
        invoice = invoiceRepository.save(invoice);
        Attachment invoiceAttachment = attachment(invoice.id, "delete-statement-invoice.pdf");

        CustomerLedgerAccount account = ledgerAccount(customer, "删除对账全链路回档账页", "10", "0");
        CustomerLedgerEntry linkedHistory = ledgerEntry(account.id, "LEDGER:DELETE:STATEMENT:LINKED",
                12, "10", "10", "0");
        linkedHistory.invoiceNo = invoice.invoiceNo;
        linkedHistory.linkedStatementId = wrong.id;
        ledgerEntryRepository.save(linkedHistory);
        wrong.historicalReceivedAmount = new BigDecimal("10");
        statementRepository.save(wrong);
        financeService.recalcStatement(wrong.id);

        FinanceService.CustomerPaymentRequest paymentRequest = new FinanceService.CustomerPaymentRequest();
        paymentRequest.amount = new BigDecimal("60");
        paymentRequest.paymentDate = LocalDate.now().plusDays(2);
        paymentRequest.method = "银行转账";
        Map<String, Object> paymentResult = financeService.registerCustomerPayment(
                "customer-" + customer.id, paymentRequest);
        Long paymentId = (Long) paymentResult.get("id");
        assertEquals(wrong.id, receiptRepository.findByPaymentIdAndDeletedFalse(paymentId).get(0).statementId);
        assertTrue(((List<Map<String, Object>>) financeService.customerStatementCandidates(customer.id)
                .get("deliveries")).stream().noneMatch(row -> delivery.id.equals(row.get("id"))));

        financeService.deleteStatement(wrong.id);

        assertTrue(statementRepository.findById(wrong.id).isEmpty());
        assertTrue(invoiceRepository.findById(invoice.id).isEmpty());
        assertTrue(attachmentRepository.findById(invoiceAttachment.id).isEmpty());
        CustomerLedgerEntry restoredHistory = ledgerEntryRepository.findById(linkedHistory.id).orElseThrow();
        assertFalse(Boolean.TRUE.equals(restoredHistory.deleted));
        assertNull(restoredHistory.linkedStatementId);

        DeliveryNote restoredDelivery = deliveryNoteRepository.findById(delivery.id).orElseThrow();
        assertNull(restoredDelivery.statementId);
        assertNull(restoredDelivery.statementNo);
        assertNull(restoredDelivery.invoiceNo);
        assertFalse(Boolean.TRUE.equals(restoredDelivery.invoiced));
        List<Map<String, Object>> restoredCandidates = (List<Map<String, Object>>) financeService
                .customerStatementCandidates(customer.id).get("deliveries");
        assertTrue(restoredCandidates.stream().anyMatch(row -> delivery.id.equals(row.get("id"))));

        ReceiptPayment payment = paymentRepository.findById(paymentId).orElseThrow();
        assertFalse(Boolean.TRUE.equals(payment.deleted));
        assertMoney("0.00", payment.unallocatedAmount);
        List<Receipt> allocations = receiptRepository.findByPaymentIdAndDeletedFalse(paymentId);
        assertEquals(1, allocations.size());
        assertEquals(next.id, allocations.get(0).statementId);
        assertMoney("60.00", allocations.get(0).amount);
        assertMoney("60.00", statementRepository.findById(next.id).orElseThrow().receivedAmount);
        Map<String, Object> companyDetail = financeService.collectionCompanyDetail("customer-" + customer.id);
        assertTrue(((List<Map<String, Object>>) companyDetail.get("statements")).stream()
                .noneMatch(row -> wrong.id.equals(row.get("id"))));
    }

    @Test
    void returnAdjustmentsSupportCrudAndEnforceFinancialFloor() {
        Customer customer = customer("退货调整测试客户");
        Statement statement = statement(customer, "RD20300501001", LocalDate.of(2030, 5, 1), "200");
        StatementItem deliveryItem = statementItem(statement, "2", "100", "200");

        FinanceService.StatementAdjustmentRequest create = adjustment("-1", "20");
        StatementItem created = financeService.createStatementAdjustment(statement.id, create);
        assertEquals(FinanceService.ADJUSTMENT_RETURN, created.adjustmentType);
        assertNull(created.deliveryId);
        assertMoney("-20.00", created.amount);
        assertMoney("180.00", statementRepository.findById(statement.id).orElseThrow().receivableAmount);
        assertEquals(1, statementItemRepository.findByDeliveryIdAndDeletedFalse(deliveryItem.deliveryId).size());

        FinanceService.StatementAdjustmentRequest edit = adjustment("-2", "30");
        StatementItem updated = financeService.updateStatementAdjustment(statement.id, created.id, edit);
        assertMoney("-60.00", updated.amount);
        assertMoney("140.00", statementRepository.findById(statement.id).orElseThrow().receivableAmount);

        financeService.deleteStatementAdjustment(statement.id, created.id);
        assertMoney("200.00", statementRepository.findById(statement.id).orElseThrow().receivableAmount);

        invoice(statement, "26322000000000900002", "150");
        financeService.recalcStatement(statement.id);
        assertThrows(BusinessException.class,
                () -> financeService.createStatementAdjustment(statement.id, adjustment("-1", "60")));
        assertThrows(BusinessException.class,
                () -> financeService.createStatementAdjustment(statement.id, adjustment("-3", "100")));
    }

    @Test
    void statementAmountUpdateKeepsItemsExportTotalAndPaymentQueueConsistent() {
        Customer customer = customer("对账单金额修改测试客户");
        Statement statement = statement(customer, "RD20300502001", LocalDate.of(2030, 5, 2), "200");
        statementItem(statement, "2", "100", "200");

        FinanceService.StatementAmountRequest request = new FinanceService.StatementAmountRequest();
        request.receivableAmount = new BigDecimal("250");
        financeService.updateStatementAmount(statement.id, request);
        assertMoney("250.00", statementRepository.findById(statement.id).orElseThrow().receivableAmount);
        StatementItem adjustment = statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .filter(item -> FinanceService.ADJUSTMENT_AMOUNT.equals(item.adjustmentType))
                .findFirst().orElseThrow();
        assertMoney("50.00", adjustment.amount);

        FinanceService.CustomerPaymentRequest payment = new FinanceService.CustomerPaymentRequest();
        payment.amount = new BigDecimal("100");
        payment.paymentDate = LocalDate.of(2030, 5, 3);
        financeService.registerCustomerPayment("customer-" + customer.id, payment);

        request.receivableAmount = new BigDecimal("90");
        assertThrows(BusinessException.class, () -> financeService.updateStatementAmount(statement.id, request));

        request.receivableAmount = new BigDecimal("150");
        financeService.updateStatementAmount(statement.id, request);
        Statement updated = statementRepository.findById(statement.id).orElseThrow();
        assertMoney("150.00", updated.receivableAmount);
        assertMoney("100.00", updated.receivedAmount);
        assertMoney("50.00", updated.arrearsAmount);
        adjustment = statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .filter(item -> FinanceService.ADJUSTMENT_AMOUNT.equals(item.adjustmentType))
                .findFirst().orElseThrow();
        assertMoney("-50.00", adjustment.amount);

        request.receivableAmount = new BigDecimal("200");
        financeService.updateStatementAmount(statement.id, request);
        assertTrue(statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .noneMatch(item -> FinanceService.ADJUSTMENT_AMOUNT.equals(item.adjustmentType)));
    }

    @Test
    void sourceAmountOnlyStatementCanBeEditedWithoutFabricatingProductDetails() {
        Customer customer = customer("历史对账金额修改测试客户");
        Statement statement = statement(customer, "RD20300503001", LocalDate.of(2030, 5, 3), "100");
        statement.sourceAmountOnly = true;
        statementRepository.save(statement);

        FinanceService.StatementAmountRequest request = new FinanceService.StatementAmountRequest();
        request.receivableAmount = new BigDecimal("80");
        financeService.updateStatementAmount(statement.id, request);

        assertMoney("80.00", statementRepository.findById(statement.id).orElseThrow().receivableAmount);
        assertTrue(statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).isEmpty());
    }

    @Test
    void statementCandidateShowsProductCodeAndSpecification() {
        Customer customer = customer("型号编码候选测试客户");
        SalesOrder order = quotationToOrder(customer, "QT-IDENTITY", new BigDecimal("2"), new BigDecimal("50"));
        SalesOrderItem orderItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0);
        orderItem.spec = "SPEC-01";
        salesOrderItemRepository.save(orderItem);
        deliver(order, orderItem, "2");

        @SuppressWarnings("unchecked") List<Map<String, Object>> candidates = (List<Map<String, Object>>)
                financeService.customerStatementCandidates(customer.id).get("deliveries");
        assertEquals(1, candidates.size());
        assertEquals("TEST-PRODUCT / SPEC-01", candidates.get(0).get("productIdentities"));
    }

    @Test
    void historicalRepairRemovesCrossOrderDuplicateAndLinksPendingDeliveriesWithoutChangingStatementTotal() {
        Customer customer = customer("晓利科技历史修复测试");
        SalesOrder order = quotationToOrder(customer, "QT-HISTORY-REPAIR", new BigDecimal("10"), new BigDecimal("5"));
        SalesOrderItem orderItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0);

        DeliveryNote duplicate = historicalDelivery(order, orderItem,
                HistoricalStatementLinkMigration.DUPLICATE_DELIVERY_SOURCE_KEY, LocalDate.of(2026, 5, 12), "1");
        DeliveryNote pending = historicalDelivery(order, orderItem,
                "HISTORY:DELIVERY:TEST:PENDING", LocalDate.of(2026, 6, 10), "4");

        Statement statement = new Statement();
        statement.statementNo = "RD20260612999";
        statement.customerId = customer.id;
        statement.customerName = customer.name;
        statement.generatedAt = LocalDate.of(2026, 6, 12).atStartOfDay();
        statement.receivableAmount = new BigDecimal("100.00");
        statement.sourceKey = HistoricalStatementLinkMigration.TARGET_STATEMENT_SOURCE_KEY;
        statement.historicalImported = true;
        statement = statementRepository.save(statement);

        StatementItem adjustment = new StatementItem();
        adjustment.statementId = statement.id;
        adjustment.productName = HistoricalStatementLinkMigration.ADJUSTMENT_PRODUCT_NAME;
        adjustment.amount = new BigDecimal("100.00");
        adjustment.unitPrice = adjustment.amount;
        statementItemRepository.save(adjustment);

        HistoricalStatementLinkMigration.RepairResult result = historicalStatementLinkMigration.repair();

        assertTrue(result.duplicateRemoved());
        assertEquals(1, result.linkedDeliveries());
        assertMoney("20.00", result.linkedAmount());
        assertTrue(deliveryNoteRepository.findBySourceKeyAndDeletedFalse(duplicate.sourceKey).isEmpty());
        assertFalse(statementItemRepository.findByDeliveryIdAndDeletedFalse(pending.id).isEmpty());
        StatementItem remainingAdjustment = statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .filter(item -> HistoricalStatementLinkMigration.ADJUSTMENT_PRODUCT_NAME.equals(item.productName))
                .findFirst().orElseThrow();
        assertMoney("80.00", remainingAdjustment.amount);
        assertMoney("100.00", statementRepository.findById(statement.id).orElseThrow().receivableAmount);

        HistoricalStatementLinkMigration.RepairResult secondRun = historicalStatementLinkMigration.repair();
        assertFalse(secondRun.duplicateRemoved());
        assertEquals(0, secondRun.linkedDeliveries());
    }

    @Test
    @SuppressWarnings("unchecked")
    void historicalReconciledQuantityOnlyReducesCandidatesAndSurvivesStatementDeletion() {
        Customer customer = customer("历史已对账数量客户");
        SalesOrder order = quotationToOrder(customer, "QT-HISTORY-BASELINE", new BigDecimal("10"), new BigDecimal("5"));
        SalesOrderItem oi = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0);
        DeliveryNote delivery = deliver(order, oi, "10");
        DeliveryNoteItem di = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id).get(0);
        di.historicalReconciledQty = new BigDecimal("4");
        deliveryNoteItemRepository.saveAndFlush(di);
        var before = (Map<String, Object>) financeService.customerSummaryDetail("customer-" + customer.id).get("summary");
        assertMoney("0", (BigDecimal) before.get("currentBalance"));
        var rows = (List<Map<String, Object>>) financeService.customerStatementCandidates(customer.id).get("deliveries");
        assertMoney("6", (BigDecimal) rows.get(0).get("qty"));
        assertMoney("30", (BigDecimal) rows.get(0).get("amount"));
        assertTrue(financeService.isDeliveryStatemented(delivery.id));
        assertThrows(BusinessException.class, () -> salesService.deleteDeliveryInfo(delivery.id));
        assertThrows(BusinessException.class, () -> financeService.generateStatementsFromSelections(
                YearMonth.now().toString(), List.of(new FinanceService.StatementDeliverySelection(delivery.id, new BigDecimal("7")))));
        Statement remainder = financeService.generateStatementsFromDeliveries(YearMonth.now().toString(), List.of(delivery.id)).get(0);
        assertMoney("30", remainder.receivableAmount);
        assertTrue(((List<?>) financeService.customerStatementCandidates(customer.id).get("deliveries")).isEmpty());
        financeService.deleteStatement(remainder.id);
        rows = (List<Map<String, Object>>) financeService.customerStatementCandidates(customer.id).get("deliveries");
        assertMoney("6", (BigDecimal) rows.get(0).get("qty"));
        assertMoney("4", deliveryNoteItemRepository.findById(di.id).orElseThrow().historicalReconciledQty);
    }

    @Test
    void partiallyStatementedDeliveryExposesOnlyRemainingBalanceAndExcludedDeliveryStaysHidden() {
        Customer customer = customer("部分对账余额测试客户");
        SalesOrder order = quotationToOrder(customer, "QT-PARTIAL-STATEMENT", new BigDecimal("10"), new BigDecimal("5"));
        SalesOrderItem orderItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0);
        DeliveryNote delivery = deliver(order, orderItem, "10");

        Statement historical = statement(customer, "RD20300101998", LocalDate.of(2030, 1, 1), "20");
        StatementItem allocated = new StatementItem();
        allocated.statementId = historical.id;
        allocated.deliveryId = delivery.id;
        allocated.deliveryNo = delivery.deliveryNo;
        allocated.orderNo = delivery.orderNo;
        allocated.productName = orderItem.productName;
        allocated.unit = orderItem.unit;
        allocated.qty = new BigDecimal("4");
        allocated.unitPrice = new BigDecimal("5");
        allocated.amount = new BigDecimal("20");
        allocated.deliveryDate = delivery.deliveryDate;
        statementItemRepository.save(allocated);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) financeService
                .customerStatementCandidates(customer.id).get("deliveries");
        assertEquals(1, candidates.size());
        assertMoney("10", (BigDecimal) candidates.get(0).get("deliveredQty"));
        assertMoney("4", (BigDecimal) candidates.get(0).get("statementedQty"));
        assertMoney("6", (BigDecimal) candidates.get(0).get("qty"));
        assertMoney("30", (BigDecimal) candidates.get(0).get("amount"));

        Map<String, Object> boardRow = financeService.statementCustomerBoard().stream()
                .filter(row -> customer.id.equals(row.get("customerId")))
                .findFirst().orElseThrow();
        assertEquals(1, boardRow.get("pendingDeliveryCount"));
        assertMoney("30", (BigDecimal) boardRow.get("pendingAmount"));

        Statement residual = financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(delivery.id)).get(0);
        assertMoney("30", residual.receivableAmount);
        StatementItem residualItem = statementItemRepository.findByStatementIdAndDeletedFalse(residual.id).get(0);
        assertMoney("6", residualItem.qty);
        assertMoney("30", residualItem.amount);
        assertTrue(((List<?>) financeService.customerStatementCandidates(customer.id).get("deliveries")).isEmpty());
        assertThrows(BusinessException.class, () -> financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(delivery.id)));

        financeService.deleteStatement(residual.id);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> restored = (List<Map<String, Object>>) financeService
                .customerStatementCandidates(customer.id).get("deliveries");
        assertEquals(1, restored.size());
        assertMoney("6", (BigDecimal) restored.get(0).get("qty"));

        delivery.reconciliationExcluded = true;
        delivery.reconciliationExclusionReason = "历史差额不用补";
        deliveryNoteRepository.save(delivery);
        assertTrue(((List<?>) financeService.customerStatementCandidates(customer.id).get("deliveries")).isEmpty());
        assertThrows(BusinessException.class, () -> financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(delivery.id)));
    }

    @Test
    void invoiceBoardFiltersSettlementMethodAndExposesOnlyOutstandingCandidates() {
        Customer customer = customer("开票看板测试客户");
        Statement pending = statement(customer, "RD20310101001", LocalDate.of(2031, 1, 1), "100");
        pending.invoicedAmount = BigDecimal.ZERO;
        statementRepository.save(pending);
        invoice(pending, "26322000000000100001", "40");
        financeService.recalcStatement(pending.id);

        Statement paid = statement(customer, "RD20310101002", LocalDate.of(2031, 1, 2), "80");
        paid.invoicedAmount = BigDecimal.ZERO;
        statementRepository.save(paid);
        invoice(paid, "26322000000000100002", "80");
        financeService.recalcStatement(paid.id);
        receipt(paid.id, "80");

        Statement cash = statement(customer, "RD20310101003", LocalDate.of(2031, 1, 3), "60");
        cash.settlementMethod = FinanceService.METHOD_CASH;
        cash.settlementStatus = FinanceService.STATUS_CASH_PENDING;
        cash.status = "现汇待到账";
        cash.invoicedAmount = BigDecimal.ZERO;
        statementRepository.save(cash);

        Statement unregistered = statement(customer, "RD20310101004", LocalDate.of(2031, 1, 4), "50");
        unregistered.settlementMethod = null;
        unregistered.settlementStatus = FinanceService.STATUS_UNREGISTERED;
        unregistered.status = "未登记";
        unregistered.invoicedAmount = BigDecimal.ZERO;
        statementRepository.save(unregistered);

        List<Map<String, Object>> board = financeService.invoicedStatementBoard();
        assertEquals(List.of(pending.id, paid.id), board.stream().map(row -> (Long) row.get("id")).sorted().toList());
        assertTrue(board.stream().allMatch(row -> FinanceService.METHOD_INVOICE.equals(row.get("settlementMethod"))));

        List<Map<String, Object>> candidates = financeService.invoiceStatementCandidates();
        assertTrue(candidates.stream().anyMatch(row -> pending.id.equals(row.get("id"))));
        assertTrue(candidates.stream().anyMatch(row -> unregistered.id.equals(row.get("id"))));
        assertTrue(candidates.stream().noneMatch(row -> paid.id.equals(row.get("id"))));
        assertTrue(candidates.stream().noneMatch(row -> cash.id.equals(row.get("id"))));
    }

    @Test
    void companyPaymentGroupsMultipleLedgersAndAllocatesAcrossHistoryAndNewStatement() {
        Customer customer = customer("多账本公司测试");
        CustomerLedgerAccount firstAccount = ledgerAccount(customer, "多账本公司测试-A", "100", "100");
        CustomerLedgerAccount secondAccount = ledgerAccount(customer, "多账本公司测试-B", "150", "150");
        CustomerLedgerEntry firstEntry = ledgerEntry(firstAccount.id, "LEDGER:MULTI:A", 1, "100", "0", "100");
        firstEntry.transactionDate = LocalDate.of(2030, 1, 1);
        firstEntry.invoiceNo = null;
        CustomerLedgerEntry secondEntry = ledgerEntry(secondAccount.id, "LEDGER:MULTI:B", 2, "150", "0", "150");
        secondEntry.transactionDate = LocalDate.of(2030, 2, 1);
        secondEntry.invoiceNo = null;
        ledgerEntryRepository.saveAll(List.of(firstEntry, secondEntry));

        Statement current = statement(customer, "RD20300301001", LocalDate.of(2030, 3, 1), "200");
        current.invoicedAmount = BigDecimal.ZERO;
        statementRepository.save(current);
        invoice(current, "26322000000000200001", "200");
        financeService.recalcStatement(current.id);

        Map<String, Object> boardRow = financeService.collectionCompanyBoard().stream()
                .filter(row -> customer.id.equals(row.get("customerId")))
                .findFirst().orElseThrow();
        assertMoney("450.00", (BigDecimal) boardRow.get("receivableAmount"));
        assertMoney("450.00", (BigDecimal) boardRow.get("arrearsAmount"));
        assertEquals(3, boardRow.get("statementCount"));

        FinanceService.CustomerPaymentRequest request = new FinanceService.CustomerPaymentRequest();
        request.amount = new BigDecimal("300");
        request.paymentDate = LocalDate.of(2030, 4, 1);
        Map<String, Object> result = financeService.registerCustomerPayment("customer-" + customer.id, request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allocations = (List<Map<String, Object>>) result.get("allocations");
        assertEquals(3, allocations.size());
        assertMoney("100.00", (BigDecimal) allocations.get(0).get("amount"));
        assertMoney("150.00", (BigDecimal) allocations.get(1).get("amount"));
        assertMoney("50.00", (BigDecimal) allocations.get(2).get("amount"));
        assertMoney("0.00", ledgerAccountRepository.findById(firstAccount.id).orElseThrow().currentDebt);
        assertMoney("0.00", ledgerAccountRepository.findById(secondAccount.id).orElseThrow().currentDebt);
        assertStatus(current.id, FinanceService.STATUS_PARTIALLY_PAID, "150.00");

        financeService.deleteCustomerPayment("customer-" + customer.id, String.valueOf(result.get("paymentBatchNo")));
        assertMoney("100.00", ledgerAccountRepository.findById(firstAccount.id).orElseThrow().currentDebt);
        assertMoney("150.00", ledgerAccountRepository.findById(secondAccount.id).orElseThrow().currentDebt);
        assertStatus(current.id, FinanceService.STATUS_INVOICED_PENDING, "200.00");
    }

    @Test
    void companyPaymentCanBeEditedAndDeletedWithImmediateSummaryRefresh() {
        Customer customer = customer("公司到账修改测试");
        CustomerLedgerAccount account = ledgerAccount(customer, customer.name, "300", "300");
        ledgerEntryRepository.save(ledgerEntry(account.id, "LEDGER:PAYMENT:EDIT", 1, "300", "0", "300"));
        String groupKey = "customer-" + customer.id;

        FinanceService.CustomerPaymentRequest create = new FinanceService.CustomerPaymentRequest();
        create.amount = new BigDecimal("100");
        create.paymentDate = LocalDate.of(2030, 5, 1);
        create.method = "银行转账";
        create.bankSerial = "BANK-EDIT-OLD";
        Map<String, Object> created = financeService.registerCustomerPayment(groupKey, create);
        String batchNo = String.valueOf(created.get("paymentBatchNo"));

        FinanceService.CustomerPaymentRequest edit = new FinanceService.CustomerPaymentRequest();
        edit.amount = new BigDecimal("160");
        edit.paymentDate = LocalDate.of(2030, 5, 2);
        edit.method = "承兑";
        edit.bankSerial = "BANK-EDIT-NEW";
        edit.remark = "金额更正";
        financeService.updateCustomerPayment(groupKey, batchNo, edit);

        Map<String, Object> detail = financeService.collectionCompanyDetail(groupKey);
        @SuppressWarnings("unchecked") Map<String, Object> summary = (Map<String, Object>) detail.get("summary");
        @SuppressWarnings("unchecked") List<Map<String, Object>> payments = (List<Map<String, Object>>) detail.get("payments");
        assertMoney("140.00", (BigDecimal) summary.get("currentDebt"));
        assertEquals(1, payments.size());
        assertEquals("PAYMENT", payments.get(0).get("recordType"));
        assertEquals(batchNo, payments.get(0).get("recordNo"));
        assertMoney("160.00", (BigDecimal) payments.get(0).get("amount"));
        assertEquals("承兑", payments.get(0).get("method"));
        assertEquals("定期还款", payments.get(0).get("statementNo"));

        financeService.deleteCustomerPayment(groupKey, batchNo);
        detail = financeService.collectionCompanyDetail(groupKey);
        summary = (Map<String, Object>) detail.get("summary");
        payments = (List<Map<String, Object>>) detail.get("payments");
        assertMoney("300.00", (BigDecimal) summary.get("currentDebt"));
        assertTrue(payments.isEmpty());
    }

    @Test
    void statementReceiptAppearsInCompanyFlowAndSupportsEditAndDelete() {
        Customer customer = customer("单张到账流水测试");
        Statement statement = statement(customer, "RD20300601001", LocalDate.of(2030, 6, 1), "200");
        Receipt saved = receipt(statement.id, "50");
        String groupKey = "customer-" + customer.id;

        Map<String, Object> detail = financeService.collectionCompanyDetail(groupKey);
        @SuppressWarnings("unchecked") Map<String, Object> summary = (Map<String, Object>) detail.get("summary");
        @SuppressWarnings("unchecked") List<Map<String, Object>> payments = (List<Map<String, Object>>) detail.get("payments");
        assertMoney("150.00", (BigDecimal) summary.get("currentDebt"));
        assertEquals(1, payments.size());
        assertEquals("PAYMENT", payments.get(0).get("recordType"));
        assertEquals(saved.paymentId, payments.get(0).get("id"));
        assertEquals("定期还款", payments.get(0).get("statementNo"));

        Receipt edit = new Receipt();
        edit.amount = new BigDecimal("80");
        edit.receiptDate = LocalDate.of(2030, 6, 2);
        edit.method = "银行转账";
        edit.bankSerial = "BANK-STATEMENT-EDIT";
        financeService.updateReceipt(saved.id, edit);

        detail = financeService.collectionCompanyDetail(groupKey);
        summary = (Map<String, Object>) detail.get("summary");
        payments = (List<Map<String, Object>>) detail.get("payments");
        assertMoney("120.00", (BigDecimal) summary.get("currentDebt"));
        assertMoney("80.00", (BigDecimal) payments.get(0).get("amount"));
        assertEquals("定期还款", payments.get(0).get("statementNo"));

        financeService.deleteReceipt(saved.id);
        detail = financeService.collectionCompanyDetail(groupKey);
        summary = (Map<String, Object>) detail.get("summary");
        payments = (List<Map<String, Object>>) detail.get("payments");
        assertMoney("200.00", (BigDecimal) summary.get("currentDebt"));
        assertTrue(payments.isEmpty());
    }

    @Test
    void renamedCustomerInvoiceAttachmentsDoNotHideNewStatementDebt() {
        Customer customer = customer("客户旧名称");
        CustomerLedgerAccount account = ledgerAccount(customer, "客户旧名称", "100", "0");
        CustomerLedgerEntry historical = ledgerEntry(account.id, "LEDGER:RENAME:OLD", 1, "100", "100", "0");
        historical.invoiceNo = "26322000000000300001";
        ledgerEntryRepository.save(historical);

        Statement current = statement(customer, "RD20320101001", LocalDate.of(2032, 1, 1), "300");
        current.invoicedAmount = BigDecimal.ZERO;
        statementRepository.save(current);
        invoice(current, "26322000000000300002", "300");
        financeService.recalcStatement(current.id);
        Invoice savedInvoice = invoiceRepository.findByStatementIdAndDeletedFalse(current.id).get(0);
        attachment(savedInvoice.id, "invoice-a.pdf");
        attachment(savedInvoice.id, "invoice-b.jpg");

        customer.name = "客户最新全称有限公司";
        customerRepository.save(customer);

        Map<String, Object> detail = financeService.customerSummaryDetail("customer-" + customer.id);
        @SuppressWarnings("unchecked") Map<String, Object> customerInfo = (Map<String, Object>) detail.get("customer");
        @SuppressWarnings("unchecked") Map<String, Object> summary = (Map<String, Object>) detail.get("summary");
        assertEquals("客户最新全称有限公司", customerInfo.get("name"));
        assertMoney("300.00", (BigDecimal) summary.get("currentDebt"));

        Map<String, Object> statementDetail = financeService.statementDetail(current.id);
        @SuppressWarnings("unchecked") Map<Long, List<Attachment>> attachments =
                (Map<Long, List<Attachment>>) statementDetail.get("invoiceAttachments");
        assertEquals(2, attachments.get(savedInvoice.id).size());
    }

    @Test
    void unmatchedLedgerKeepsIndependentStableGroupKey() {
        CustomerLedgerAccount account = new CustomerLedgerAccount();
        account.sourceKey = "LEDGER:ACCOUNT:未匹配保留测试";
        account.sheetName = "未匹配保留测试";
        account.statementAmount = new BigDecimal("90");
        account.currentDebt = new BigDecimal("90");
        account.entryCount = 1;
        account.openEntryCount = 1;
        account.creditBalance = BigDecimal.ZERO;
        account.importedAt = LocalDateTime.now();
        account = ledgerAccountRepository.save(account);
        Long accountId = account.id;
        ledgerEntryRepository.save(ledgerEntry(account.id, "LEDGER:UNMATCHED:1", 1, "90", "0", "90"));

        Map<String, Object> row = financeService.customerSummaries().stream()
                .filter(item -> ("ledger-" + accountId).equals(item.get("groupKey")))
                .findFirst().orElseThrow();
        assertNull(row.get("customerId"));
        assertEquals("未匹配保留测试", row.get("customerName"));
        assertMoney("90.00", (BigDecimal) row.get("currentDebt"));
    }

    private Customer customer(String name) {
        Customer customer = new Customer();
        customer.name = name;
        customer.enabled = true;
        return customerRepository.save(customer);
    }

    private CustomerLedgerAccount ledgerAccount(Customer customer, String sheetName, String amount, String debt) {
        CustomerLedgerAccount account = new CustomerLedgerAccount();
        account.customerId = customer.id;
        account.sourceKey = "LEDGER:ACCOUNT:" + sheetName;
        account.sheetName = sheetName;
        account.statementAmount = new BigDecimal(amount);
        account.currentDebt = new BigDecimal(debt);
        account.entryCount = 1;
        account.openEntryCount = new BigDecimal(debt).compareTo(BigDecimal.ZERO) > 0 ? 1 : 0;
        account.creditBalance = BigDecimal.ZERO;
        account.importedAt = LocalDateTime.now().minusDays(1);
        return ledgerAccountRepository.save(account);
    }

    private Attachment attachment(Long invoiceId, String name) {
        Attachment attachment = new Attachment();
        attachment.businessType = "INVOICE_ATTACHMENT";
        attachment.businessId = invoiceId;
        attachment.fileKey = "test-" + name;
        attachment.originalName = name;
        attachment.contentType = "application/octet-stream";
        attachment.sizeBytes = 10L;
        return attachmentRepository.save(attachment);
    }

    private SalesOrder quotationToOrder(Customer customer, String quoteNo, BigDecimal qty, BigDecimal price) {
        Quotation quotation = new Quotation();
        quotation.quoteNo = quoteNo;
        quotation.quoteDate = LocalDate.now();
        quotation.customerId = customer.id;
        quotation.customerName = customer.name;
        quotation.status = "已确认";
        quotation.totalAmount = qty.multiply(price);
        quotation = quotationRepository.save(quotation);

        QuotationItem item = new QuotationItem();
        item.quotationId = quotation.id;
        item.productCode = "TEST-PRODUCT";
        item.productName = "测试产品";
        item.unit = "条";
        item.qty = qty;
        item.unitPrice = price;
        item.amount = qty.multiply(price);
        quotationItemRepository.save(item);

        SalesService.QuotationConvertRequest request = new SalesService.QuotationConvertRequest();
        request.orderDate = LocalDate.now();
        request.deliveryDate = LocalDate.now();
        return salesService.convertQuotationToOrder(quotation.id, request);
    }

    private DeliveryNote deliver(SalesOrder order, SalesOrderItem item, String qty) {
        return deliver(order, item, qty, LocalDate.now());
    }

    private DeliveryNote deliver(SalesOrder order, SalesOrderItem item, String qty, LocalDate deliveryDate) {
        SalesService.DeliveryRegisterRequest request = new SalesService.DeliveryRegisterRequest();
        request.itemId = item.id;
        request.deliveryQty = new BigDecimal(qty);
        request.deliveryDate = deliveryDate;
        request.deliveryMethod = "物流";
        return salesService.addDelivery(order.id, request);
    }

    private DeliveryNote historicalDelivery(SalesOrder order, SalesOrderItem orderItem, String sourceKey,
                                              LocalDate date, String qtyValue) {
        BigDecimal qty = new BigDecimal(qtyValue);
        DeliveryNote delivery = new DeliveryNote();
        delivery.deliveryNo = "RC" + date.toString().replace("-", "") + String.format("%03d", deliveryNoteRepository.count() % 1000);
        delivery.orderId = order.id;
        delivery.orderNo = order.orderNo;
        delivery.customerId = order.customerId;
        delivery.customerName = order.customerName;
        delivery.status = "已发货";
        delivery.deliveryDate = date;
        delivery.deliveryQty = qty;
        delivery.totalAmount = qty.multiply(orderItem.unitPrice);
        delivery.historicalImported = true;
        delivery.sourceKey = sourceKey;
        delivery = deliveryNoteRepository.save(delivery);

        DeliveryNoteItem item = new DeliveryNoteItem();
        item.deliveryId = delivery.id;
        item.orderItemId = orderItem.id;
        item.productName = orderItem.productName;
        item.unit = orderItem.unit;
        item.qty = qty;
        item.unitPrice = orderItem.unitPrice;
        item.amount = delivery.totalAmount;
        deliveryNoteItemRepository.save(item);
        orderItem.shippedQty = orderItem.shippedQty.add(qty);
        salesOrderItemRepository.save(orderItem);
        return delivery;
    }

    private Receipt receipt(Long statementId, String amount) {
        Receipt receipt = new Receipt();
        receipt.amount = new BigDecimal(amount);
        receipt.receiptDate = LocalDate.now();
        return financeService.registerCollectionReceipt(statementId, receipt);
    }

    private Statement statement(Customer customer, String statementNo, LocalDate date, String amount) {
        Statement statement = new Statement();
        statement.statementNo = statementNo;
        statement.customerId = customer.id;
        statement.customerName = customer.name;
        statement.generatedAt = date.atStartOfDay();
        statement.receivableAmount = new BigDecimal(amount);
        statement.invoicedAmount = new BigDecimal(amount);
        statement.receivedAmount = BigDecimal.ZERO;
        statement.arrearsAmount = new BigDecimal(amount);
        statement.settlementMethod = FinanceService.METHOD_INVOICE;
        statement.settlementStatus = FinanceService.STATUS_INVOICED_PENDING;
        statement.status = "已开票待回款";
        return statementRepository.save(statement);
    }

    private StatementItem statementItem(Statement statement, String qty, String unitPrice, String amount) {
        StatementItem item = new StatementItem();
        item.statementId = statement.id;
        item.deliveryId = 900000L + statement.id;
        item.deliveryNo = "RC-TEST-" + statement.id;
        item.orderNo = "RB-TEST-" + statement.id;
        item.productName = "测试产品";
        item.spec = "TEST-SPEC";
        item.unit = "件";
        item.qty = new BigDecimal(qty);
        item.unitPrice = new BigDecimal(unitPrice);
        item.amount = new BigDecimal(amount);
        item.deliveryDate = statement.generatedAt.toLocalDate();
        return statementItemRepository.save(item);
    }

    private FinanceService.StatementAdjustmentRequest adjustment(String qty, String unitPrice) {
        FinanceService.StatementAdjustmentRequest request = new FinanceService.StatementAdjustmentRequest();
        request.adjustmentDate = LocalDate.of(2030, 5, 2);
        request.documentNo = "RETURN-001";
        request.productName = "测试退货产品";
        request.spec = "RETURN-SPEC";
        request.unit = "件";
        request.qty = new BigDecimal(qty);
        request.unitPrice = new BigDecimal(unitPrice);
        return request;
    }

    private void invoice(Statement statement, String invoiceNo, String amount) {
        Invoice invoice = new Invoice();
        invoice.statementId = statement.id;
        invoice.invoiceNo = invoiceNo;
        invoice.invoiceDate = statement.generatedAt.toLocalDate();
        invoice.amount = new BigDecimal(amount);
        invoice.customerId = statement.customerId;
        invoice.customerName = statement.customerName;
        invoiceRepository.save(invoice);
    }

    private CustomerLedgerEntry ledgerEntry(Long accountId, String sourceKey, int row, String amount, String paid, String remaining) {
        CustomerLedgerEntry entry = new CustomerLedgerEntry();
        entry.accountId = accountId;
        entry.sourceKey = sourceKey;
        entry.sourceRow = row;
        entry.transactionDate = LocalDate.of(2026, 6, row);
        entry.sourceDate = entry.transactionDate.toString();
        entry.summary = "开票";
        entry.invoiceNo = "2632200000000000000" + row;
        entry.amount = new BigDecimal(amount);
        entry.paidAmount = new BigDecimal(paid);
        entry.remainingAmount = new BigDecimal(remaining);
        return entry;
    }

    private void assertStatus(Long statementId, String status, String arrears) {
        Statement statement = statementRepository.findById(statementId).orElseThrow();
        assertEquals(status, statement.settlementStatus);
        assertMoney(arrears, statement.arrearsAmount);
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
