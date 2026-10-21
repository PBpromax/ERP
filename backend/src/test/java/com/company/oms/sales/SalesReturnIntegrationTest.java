package com.company.oms.sales;

import com.company.oms.common.BusinessException;
import com.company.oms.finance.*;
import com.company.oms.masterdata.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SalesReturnIntegrationTest {
    @Autowired SalesReturnService returns;
    @Autowired SalesReturnRepository returnRepository;
    @Autowired SalesService sales;
    @Autowired FinanceService finance;
    @Autowired SalesOrderRepository orders;
    @Autowired SalesOrderItemRepository items;
    @Autowired CustomerRepository customers;
    @Autowired StatementItemRepository statementItems;
    @Autowired ReceiptPaymentRepository payments;
    @Autowired ReceiptRepository receipts;
    @Autowired CustomerLedgerAccountRepository ledgerAccounts;
    SalesOrder order;
    SalesOrderItem item;
    DeliveryNote delivery;

    @BeforeEach
    void prepare() {
        Customer customer = new Customer(); customer.name = "独立退货测试";
        customer = customers.save(customer);
        order = new SalesOrder(); order.orderNo = "RETURN-TEST-" + UUID.randomUUID();
        order.customerId = customer.id; order.customerName = customer.name;
        order.orderDate = LocalDate.of(2030, 7, 1); order.status = "未发货";
        order = orders.save(order);
        item = new SalesOrderItem(); item.orderId = order.id; item.productName = "提绳管";
        item.productCode = "TEST-CODE"; item.spec = "Φ10*6.5*80mm"; item.unit = "个";
        item.qty = new BigDecimal("1000"); item.shippedQty = BigDecimal.ZERO;
        item.unitPrice = new BigDecimal("0.115"); item.amount = new BigDecimal("115");
        item = items.save(item);
        SalesService.DeliveryRegisterRequest request = new SalesService.DeliveryRegisterRequest();
        request.itemId = item.id; request.deliveryDate = LocalDate.of(2030, 7, 2);
        request.deliveryQty = new BigDecimal("1000"); request.deliveryMethod = "物流";
        delivery = sales.addDelivery(order.id, request);
    }

    SalesReturn register(String qty) {
        SalesReturnService.Request request = new SalesReturnService.Request(); request.itemId = item.id;
        request.returnDate = LocalDate.of(2030, 7, 5); request.qty = new BigDecimal(qty);
        return returns.register(order.id, request);
    }

    FinanceService.StatementDeliverySelection selection(SalesReturn r, String qty) {
        var s = new FinanceService.StatementDeliverySelection(); s.returnId = r.id; s.qty = new BigDecimal(qty); return s;
    }

    Statement reconcile(SalesReturn r, String shippedQty, String returnedQty) {
        return finance.generateStatementsFromSelections("2030-07", List.of(
                new FinanceService.StatementDeliverySelection(delivery.id, new BigDecimal(shippedQty)),
                selection(r, returnedQty))).get(0);
    }

    @Test
    void returnIsIndependentAndPartialReconciliationCanBeReleased() {
        SalesReturn r = register("200");
        assertEquals(0, r.amount.compareTo(new BigDecimal("23.00")));
        var board = sales.deliveryInvoiceRows().stream().filter(row -> item.id.equals(row.get("itemId"))).findFirst().orElseThrow();
        assertEquals(0, ((BigDecimal) board.get("returnedQty")).compareTo(new BigDecimal("200")));
        assertEquals(0, ((BigDecimal) board.get("unshippedQty")).signum());
        assertEquals("已发送", board.get("fulfillmentStatus"));
        assertEquals(0, items.findById(item.id).orElseThrow().shippedQty.compareTo(new BigDecimal("1000")));
        var candidate = returns.candidates().stream().filter(row -> r.id.equals(row.get("returnId"))).findFirst().orElseThrow();
        assertEquals("退货记录", candidate.get("deliveryNo")); assertEquals("", candidate.get("deliveryMethod"));
        assertEquals(r.returnDate, candidate.get("deliveryDate"));
        Statement statement = reconcile(r, "500", "-100");
        assertEquals(0, statement.receivableAmount.compareTo(new BigDecimal("46.00")));
        StatementItem returnLine = statementItems.findByReturnIdAndDeletedFalse(r.id).get(0);
        assertNull(returnLine.deliveryId); assertNull(returnLine.deliveryItemId);
        assertEquals(0, returnLine.unitPrice.compareTo(new BigDecimal("0.115")));
        assertEquals(0, returns.reconciledQty(r.id).compareTo(new BigDecimal("100")));
        finance.deleteStatementAdjustment(statement.id, returnLine.id);
        assertEquals(0, returns.reconciledQty(r.id).signum());
        finance.deleteStatement(statement.id);
        Statement second = reconcile(r, "1000", "-200");
        assertEquals(0, second.receivableAmount.compareTo(new BigDecimal("92.00")));
        assertTrue(returns.candidates().stream().noneMatch(row -> r.id.equals(row.get("returnId"))));
        finance.deleteStatement(second.id);
        assertTrue(returns.candidates().stream().anyMatch(row -> r.id.equals(row.get("returnId"))));
        returns.delete(r.id);
        assertTrue(returnRepository.findById(r.id).orElseThrow().deleted);
        assertEquals(0, items.findById(item.id).orElseThrow().shippedQty.compareTo(new BigDecimal("1000")));
    }

    @Test void rejectsFractionalReturnQuantity() { assertThrows(BusinessException.class, () -> register("1.5")); }
    @Test void rejectsMoreThanShipped() { assertThrows(BusinessException.class, () -> register("1001")); }
    @Test void rejectsDuplicateReconciliation() {
        SalesReturn r = register("100"); reconcile(r, "500", "-100");
        assertThrows(BusinessException.class, () -> reconcile(r, "500", "-1"));
    }
    @Test void reconciledReturnCannotBeDeleted() {
        SalesReturn r = register("100"); reconcile(r, "500", "-100");
        assertThrows(BusinessException.class, () -> returns.delete(r.id));
    }
    @Test void importedReturnLineCannotBeEditedAsManualAdjustment() {
        SalesReturn r = register("100"); Statement statement = reconcile(r, "500", "-100");
        Long lineId = statementItems.findByReturnIdAndDeletedFalse(r.id).get(0).id;
        assertThrows(BusinessException.class, () -> finance.updateStatementAdjustment(statement.id, lineId, new FinanceService.StatementAdjustmentRequest()));
    }
    @Test void negativeNetStatementRejected() {
        SalesReturn r = register("200");
        assertThrows(BusinessException.class, () -> reconcile(r, "100", "-200"));
    }
    @Test void monthlyGenerationIncludesReturns() {
        SalesReturn r = register("200");
        Statement statement = finance.generateStatements("2030-07").stream()
                .filter(s -> order.customerId.equals(s.customerId)).findFirst().orElseThrow();
        assertEquals(0, statement.receivableAmount.compareTo(new BigDecimal("92.00")));
        assertEquals(1, statementItems.findByReturnIdAndDeletedFalse(r.id).size());
    }
    @Test void rejectsReturnFromDifferentCustomer() {
        SalesReturn r = register("100"); r.customerId = -10L; returnRepository.save(r);
        assertThrows(BusinessException.class, () -> reconcile(r, "500", "-100"));
    }
    @Test void fullyReconciledShipmentCanReceiveLaterReturnOnExistingStatement() {
        Statement statement = finance.generateStatementsFromDeliveries("2030-07", List.of(delivery.id)).get(0);
        SalesReturn r = register("200");
        finance.appendSelectedReturns(statement.id, List.of(selection(r, "-200")));
        assertEquals(0, statement.receivableAmount.compareTo(new BigDecimal("92.00")));
        assertEquals(0, items.findById(item.id).orElseThrow().shippedQty.compareTo(new BigDecimal("1000")));
    }
    @Test void returnCannotReduceStatementBelowIssuedInvoice() {
        Statement statement = finance.generateStatementsFromDeliveries("2030-07", List.of(delivery.id)).get(0);
        Invoice invoice = new Invoice(); invoice.statementId = statement.id; invoice.invoiceNo = "RETURN-TEST-INVOICE";
        invoice.amount = new BigDecimal("110"); finance.registerInvoice(invoice);
        SalesReturn r = register("200");
        assertThrows(BusinessException.class, () -> finance.appendSelectedReturns(statement.id, List.of(selection(r, "-200"))));
    }

    @Test void advanceAppliesOnlyAfterReturnDeductionAndKeepsExcessCredit() {
        CustomerLedgerAccount account = new CustomerLedgerAccount();
        account.customerId = order.customerId; account.sheetName = order.customerName;
        account.sourceKey = "TEST:" + order.customerId;
        account.statementAmount = BigDecimal.ZERO; account.currentDebt = BigDecimal.ZERO;
        ledgerAccounts.saveAndFlush(account);
        var request = new FinanceService.CustomerPaymentRequest();
        request.paymentDate = LocalDate.of(2020, 1, 1);
        request.amount = new BigDecimal("100");
        var created = finance.registerCustomerPayment("customer-" + order.customerId, request);
        SalesReturn r = register("200");
        Statement statement = reconcile(r, "1000", "-200");
        assertEquals(0, statement.receivableAmount.compareTo(new BigDecimal("92")));
        assertEquals(0, statement.receivedAmount.compareTo(new BigDecimal("92")));
        assertEquals(0, statement.arrearsAmount.signum());
        var payment = payments.findById((Long) created.get("id")).orElseThrow();
        assertEquals(0, payment.unallocatedAmount.compareTo(new BigDecimal("8")));
        var allocations = receipts.findByPaymentIdAndDeletedFalse(payment.id);
        assertEquals(1, allocations.size());
        assertEquals(statement.id, allocations.get(0).statementId);
        assertEquals(0, allocations.get(0).amount.compareTo(new BigDecimal("92")));
    }
}
