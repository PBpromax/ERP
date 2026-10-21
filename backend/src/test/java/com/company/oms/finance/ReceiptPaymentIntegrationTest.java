package com.company.oms.finance;

import com.company.oms.common.BusinessException;
import com.company.oms.masterdata.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class ReceiptPaymentIntegrationTest {
    @Autowired FinanceService finance;
    @Autowired CustomerRepository customers;
    @Autowired CustomerLedgerAccountRepository accounts;
    @Autowired StatementRepository statements;
    @Autowired ReceiptPaymentRepository payments;
    @Autowired ReceiptRepository receipts;

    Customer customer() {
        Customer c = new Customer(); c.name = "收款重构测试" + UUID.randomUUID(); c = customers.save(c);
        CustomerLedgerAccount a = new CustomerLedgerAccount(); a.customerId=c.id; a.sheetName=c.name;
        a.sourceKey="TEST:"+c.id; a.statementAmount=BigDecimal.ZERO; a.currentDebt=BigDecimal.ZERO; accounts.save(a);
        return c;
    }
    Statement statement(Customer c, String date, String amount) {
        Statement s=new Statement(); s.customerId=c.id; s.customerName=c.name; s.generatedAt=LocalDate.parse(date).atStartOfDay();
        s.statementNo="TEST-"+UUID.randomUUID(); s.receivableAmount=new BigDecimal(amount); s.arrearsAmount=s.receivableAmount;
        s.receivedAmount=BigDecimal.ZERO; s.invoicedAmount=BigDecimal.ZERO; s.historicalReceivedAmount=BigDecimal.ZERO;
        return statements.saveAndFlush(s);
    }
    FinanceService.CustomerPaymentRequest request(String date,String amount,Long statementId) {
        var r=new FinanceService.CustomerPaymentRequest(); r.paymentDate=LocalDate.parse(date); r.amount=new BigDecimal(amount);
        r.statementId=statementId; r.method="银行转账"; return r;
    }
    String group(Customer c) { return "customer-"+c.id; }
    void amount(String expected,BigDecimal actual) { assertEquals(0,new BigDecimal(expected).compareTo(actual)); }

    @Test void advanceIsOnePaymentAndAppliesToLaterBillsWithoutInventingStatements() {
        var c=customer(); var created=finance.registerCustomerPayment(group(c),request("2026-01-01","250",null));
        var p=payments.findById((Long)created.get("id")).orElseThrow();
        amount("250",p.unallocatedAmount); assertTrue(receipts.findByPaymentIdAndDeletedFalse(p.id).isEmpty());
        var a=statement(c,"2026-02-01","100"); var b=statement(c,"2026-03-01","200");
        finance.replayCustomerPayments(group(c));
        amount("100",a.receivedAmount); amount("150",b.receivedAmount); amount("0",p.unallocatedAmount);
        assertEquals(2,receipts.findByPaymentIdAndDeletedFalse(p.id).size());
        var detail=finance.collectionCompanyDetail(group(c));
        var rows=(List<Map<String,Object>>)detail.get("payments");
        assertEquals(1,rows.size()); assertEquals("定期还款",rows.get(0).get("statementNo"));
        assertEquals(LocalDate.parse("2026-02-01"),receipts.findByPaymentIdAndDeletedFalse(p.id).get(0).allocationDate);
    }

    @Test void deletingAndEditingPaymentsAlwaysReplaysOldestBillFirst() {
        var c=customer(); var a=statement(c,"2026-01-01","100"); var b=statement(c,"2026-02-01","200");
        var first=finance.registerCustomerPayment(group(c),request("2026-03-01","80",null));
        var targeted=finance.registerCustomerPayment(group(c),request("2026-03-02","50",b.id));
        var last=finance.registerCustomerPayment(group(c),request("2026-03-03","170",null));
        amount("0",a.arrearsAmount); amount("0",b.arrearsAmount);
        finance.deleteCustomerPayment(group(c),(String)first.get("paymentBatchNo"));
        amount("100",a.receivedAmount); amount("120",b.receivedAmount); amount("80",b.arrearsAmount);
        var p=payments.findById((Long)targeted.get("id")).orElseThrow();
        assertNull(p.statementId);
        assertEquals(a.id,receipts.findByPaymentIdAndDeletedFalse(p.id).get(0).statementId);
        finance.updateCustomerPayment(group(c),(String)last.get("paymentBatchNo"),request("2026-03-03","70",null));
        amount("100",a.receivedAmount); amount("20",b.receivedAmount);
    }

    @Test void statementEntryAllowsAdvanceButRejectsAnotherCompany() {
        var a=customer();var b=customer();var bill=statement(a,"2026-01-01","100");
        var saved=finance.registerCustomerPayment(group(a),request("2026-02-01","101",bill.id));
        assertThrows(BusinessException.class,()->finance.registerCustomerPayment(group(b),request("2026-02-01","10",bill.id)));
        amount("1",payments.findById((Long)saved.get("id")).orElseThrow().unallocatedAmount);
        amount("0",bill.arrearsAmount);
    }

    @Test void multiplePaymentsToOneBillRetainSeparateHeadersAndExcessCredit() {
        var c=customer();var s=statement(c,"2026-01-01","100");
        finance.registerCustomerPayment(group(c),request("2026-02-01","40",null));
        var second=finance.registerCustomerPayment(group(c),request("2026-02-02","90",null));
        amount("100",s.receivedAmount);
        amount("30",payments.findById((Long)second.get("id")).orElseThrow().unallocatedAmount);
        var board=finance.collectionCompanyBoard().stream().filter(r->group(c).equals(r.get("groupKey"))).findFirst().orElseThrow();
        amount("130",(BigDecimal)board.get("receivedAmount"));
        amount("-30",(BigDecimal)board.get("arrearsAmount"));
        assertEquals(2,((List<?>)finance.collectionCompanyDetail(group(c)).get("payments")).size());
    }

    @Test void importedSummaryAmountAndNoHistoricalBaselineSurviveRecalculation() {
        var c=customer();var s=statement(c,"2022-01-01","300");
        s.sourceAmountOnly=true;s.receiptRebuilt=true;s.sourceInvoicedAmount=new BigDecimal("300");
        s.historicalReceivedAmount=new BigDecimal("100"); statements.saveAndFlush(s);
        finance.replayCustomerPayments(group(c));
        amount("300",s.receivableAmount);amount("300",s.invoicedAmount);amount("0",s.receivedAmount);
        finance.registerCustomerPayment(group(c),request("2022-02-01","80",null));
        amount("220",s.arrearsAmount);amount("0",s.historicalReceivedAmount);
    }

    @Test void previousTargetLinksDoNotJumpTheQueueAndOnePaymentSpansBills() {
        var c=customer();var first=statement(c,"2026-01-01","100");var second=statement(c,"2026-02-01","200");
        ReceiptPayment legacy=new ReceiptPayment();legacy.groupKey=group(c);legacy.customerId=c.id;
        legacy.paymentNo="OLD-"+UUID.randomUUID();legacy.paymentDate=LocalDate.of(2026,3,1);
        legacy.statementId=second.id;legacy.amount=new BigDecimal("150");payments.saveAndFlush(legacy);
        finance.replayCustomerPayments(group(c));
        amount("100",first.receivedAmount);amount("50",second.receivedAmount);
        assertNull(legacy.statementId);assertEquals(2,receipts.findByPaymentIdAndDeletedFalse(legacy.id).size());
    }

    @Test void undatedSourceRowsUseTheirQueuePositionWithoutInventingBusinessDates() {
        var c=customer();var a=statement(c,"2026-01-01","100");var b=statement(c,"2026-02-01","200");
        a.generatedAt=null;a.sourceSortDate=LocalDate.of(2026,1,1);a.sourceSequence=3L;
        ReceiptPayment payment=new ReceiptPayment();payment.groupKey=group(c);payment.customerId=c.id;
        payment.paymentNo="UNDATED-"+UUID.randomUUID();payment.sourceSortDate=LocalDate.of(2026,1,2);
        payment.sourceSequence=4L;payment.amount=new BigDecimal("150");payments.saveAndFlush(payment);
        finance.replayCustomerPayments(group(c));
        amount("100",a.receivedAmount);amount("50",b.receivedAmount);assertNull(a.generatedAt);assertNull(payment.paymentDate);
        assertTrue(receipts.findByPaymentIdAndDeletedFalse(payment.id).stream().allMatch(r->r.receiptDate==null));
    }

    @Test void archivedOperationalBillRetainsIdentityWithoutDuplicateCollectionDebt() {
        var c=customer();var archived=statement(c,"2026-01-01","100");archived.collectionExcluded=true;
        var source=statement(c,"2026-01-01","100");
        finance.registerCustomerPayment(group(c),request("2026-02-01","40",null));
        amount("40",source.receivedAmount);amount("0",archived.receivedAmount);
        var detail=finance.collectionCompanyDetail(group(c));
        assertEquals(1,((List<?>)detail.get("statements")).size());
        assertFalse(statements.findById(archived.id).orElseThrow().deleted);
    }

    @Test void deletingStatementPreservesAndReplaysLegacyReceiptWithoutPaymentHeader() {
        var c=customer();var wrong=statement(c,"2026-01-01","100");var next=statement(c,"2026-02-01","200");
        Receipt legacy=new Receipt();legacy.receiptNo="LEGACY-"+UUID.randomUUID();legacy.statementId=wrong.id;
        legacy.customerId=c.id;legacy.customerName=c.name;legacy.receiptDate=LocalDate.of(2026,3,1);
        legacy.amount=new BigDecimal("40");legacy.method="银行转账";legacy.customerLevelPayment=false;
        legacy=receipts.saveAndFlush(legacy);finance.recalcStatement(wrong.id);

        finance.deleteStatement(wrong.id);

        assertTrue(statements.findById(wrong.id).isEmpty());
        assertTrue(Boolean.TRUE.equals(receipts.findById(legacy.id).orElseThrow().deleted));
        var headers=payments.findByGroupKeyAndDeletedFalseOrderByPaymentDateAscIdAsc(group(c));
        assertEquals(1,headers.size());amount("40",headers.get(0).amount);amount("0",headers.get(0).unallocatedAmount);
        var allocations=receipts.findByPaymentIdAndDeletedFalse(headers.get(0).id);
        assertEquals(1,allocations.size());assertEquals(next.id,allocations.get(0).statementId);
        amount("40",next.receivedAmount);
    }
}
