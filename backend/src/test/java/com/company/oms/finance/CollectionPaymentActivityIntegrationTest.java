package com.company.oms.finance;

import com.company.oms.masterdata.Customer;
import com.company.oms.masterdata.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class CollectionPaymentActivityIntegrationTest {
    @Autowired FinanceService finance;
    @Autowired CustomerRepository customers;
    @Autowired ReceiptPaymentRepository payments;
    @Autowired ReceiptRepository receipts;
    @Autowired CustomerLedgerAccountRepository accounts;
    @Autowired StatementRepository statements;
    final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    final LocalDate previousMonth = today.withDayOfMonth(1).minusDays(1);

    Customer customer() {
        Customer c = new Customer(); c.name = "最近付款测试-" + UUID.randomUUID();
        return customers.saveAndFlush(c);
    }
    ReceiptPayment payment(String key, LocalDate date, String method) {
        ReceiptPayment p = new ReceiptPayment(); p.groupKey = key; p.paymentNo = "DATE-" + UUID.randomUUID();
        p.paymentDate = date; p.method = method; p.amount = new BigDecimal("10");
        p.unallocatedAmount = p.amount; return payments.saveAndFlush(p);
    }
    Map<String, Object> row(String key) {
        return finance.collectionCompanyBoard().stream().filter(r -> key.equals(r.get("groupKey"))).findFirst().orElseThrow();
    }
    void latest(String key, LocalDate date, boolean paid) {
        var row = row(key); assertEquals(date, row.get("latestPaymentDate")); assertEquals(paid, row.get("paidThisMonth"));
    }

    @Test void latestUsesBusinessDateAndExcludesAdjustmentsDeletedFutureAndNonPositivePayments() {
        var c = customer(); String key = "customer-" + c.id;
        latest(key, null, false);
        payment(key, today.withDayOfMonth(1), "银行转账");
        payment(key, previousMonth, "现金"); // A newly registered backfill is still an older payment.
        payment(key, today, "账款调减");
        payment(key, today, "货物抵款");
        payment(key, today, "让利");
        payment(key, today.plusDays(1), "银行转账");
        var deleted = payment(key, today, "银行转账"); deleted.deleted = true; payments.saveAndFlush(deleted);
        var refund = payment(key, today, "退款"); refund.amount = new BigDecimal("-5"); payments.saveAndFlush(refund);
        var zero = payment(key, today, "银行转账"); zero.amount = BigDecimal.ZERO; payments.saveAndFlush(zero);
        latest(key, today.withDayOfMonth(1), true);
        assertEquals(false, row(key).get("hasUndatedPayments"));
    }

    @Test void correctingAndDeletingARealAdvancePaymentRefreshesTheMonthStatus() {
        var c = customer(); String key = "customer-" + c.id;
        var request = new FinanceService.CustomerPaymentRequest(); request.paymentDate = today;
        request.amount = new BigDecimal("50"); request.method = "银行转账";
        var created = finance.registerCustomerPayment(key, request);
        latest(key, today, true);
        request.paymentDate = previousMonth;
        finance.updateCustomerPayment(key, (String) created.get("paymentBatchNo"), request);
        latest(key, previousMonth, false);
        finance.deleteCustomerPayment(key, (String) created.get("paymentBatchNo"));
        latest(key, null, false);
    }

    @Test void originalLegacyReceiptsCountButPaymentAllocationsDoNotBecomeNewPayments() {
        var c = customer(); String key = "customer-" + c.id;
        var p = payment(key, previousMonth, "银行转账");
        Receipt allocation = new Receipt(); allocation.customerId = c.id; allocation.paymentId = p.id;
        allocation.amount = BigDecimal.TEN; allocation.receiptDate = today; receipts.saveAndFlush(allocation);
        latest(key, previousMonth, false);
        Receipt legacy = new Receipt(); legacy.customerId = c.id; legacy.amount = BigDecimal.TEN;
        legacy.receiptDate = today; legacy.method = "现金"; receipts.saveAndFlush(legacy);
        latest(key, today, true);
        legacy.deleted = true; receipts.saveAndFlush(legacy);
        latest(key, previousMonth, false);
    }

    @Test void uncertainDatesRemainUnknownAndNeverInventAPaymentThisMonth() {
        var c = customer(); String key = "customer-" + c.id;
        var undated = payment(key, null, "现金"); undated.sourceSortDate = today; payments.saveAndFlush(undated);
        var estimated = payment(key, today, "现金"); estimated.remark = "日期待核实"; payments.saveAndFlush(estimated);
        latest(key, null, false); assertEquals(true, row(key).get("hasUndatedPayments"));
        payment(key, previousMonth, "现金");
        latest(key, previousMonth, false); assertEquals(true, row(key).get("hasUndatedPayments"));
    }

    @Test void fallbackLedgerCompaniesAlsoReceivePaymentDates() {
        CustomerLedgerAccount account = new CustomerLedgerAccount(); account.sheetName = "未映射付款账页-" + UUID.randomUUID();
        account.sourceKey = UUID.randomUUID().toString(); accounts.saveAndFlush(account);
        Statement s = new Statement(); s.ledgerAccountId = account.id; s.customerName = account.sheetName;
        s.statementNo = "DATE-ST-" + UUID.randomUUID(); s.receivableAmount = new BigDecimal("100");
        s.arrearsAmount = s.receivableAmount; statements.saveAndFlush(s);
        String key = "ledger-" + account.id; payment(key, today, "银行转账");
        latest(key, today, true);
    }
}
