package com.company.oms.finance;

import com.company.oms.common.MoneyUtil;
import com.company.oms.masterdata.*;
import com.company.oms.sales.*;
import jakarta.persistence.EntityManager;
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
class ExactMoneyIntegrationTest {
    @Autowired SalesService sales;
    @Autowired FinanceService finance;
    @Autowired CustomerRepository customers;
    @Autowired ProductRepository products;
    @Autowired QuotationRepository quotations;
    @Autowired QuotationItemRepository quoteItems;
    @Autowired StatementRepository statements;
    @Autowired ReceiptPaymentRepository payments;
    @Autowired EntityManager entityManager;
    void amount(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }

    @Test void quotationCalculationAndReloadRetainMoreThanThreeDecimals() {
        Customer c = new Customer(); c.name = "完整精度报价-" + UUID.randomUUID(); customers.saveAndFlush(c);
        Product p = new Product(); p.name = "完整精度产品"; p.code = "EXACT-" + UUID.randomUUID(); products.saveAndFlush(p);
        Quotation q = new Quotation(); q.customerId = c.id; q.quoteDate = LocalDate.now();
        QuotationItem item = new QuotationItem(); item.productId = p.id; item.productName = p.name;
        item.qty = new BigDecimal("3.25"); item.unitPrice = new BigDecimal("0.123456");
        q = sales.saveQuotationWithItems(q, List.of(item)); Long id = q.id;
        entityManager.flush(); entityManager.clear();
        amount("0.401232", quotations.findById(id).orElseThrow().totalAmount);
        var saved = quoteItems.findByQuotationIdAndDeletedFalse(id).get(0);
        amount("0.123456", saved.unitPrice); amount("0.401232", saved.amount);
    }

    @Test void receiptsAllocationAndBalancesKeepTheFullFraction() {
        Customer c = new Customer(); c.name = "完整精度收款-" + UUID.randomUUID(); customers.saveAndFlush(c);
        Statement s = new Statement(); s.customerId = c.id; s.customerName = c.name;
        s.statementNo = "EXACT-ST-" + UUID.randomUUID(); s.generatedAt = LocalDate.now().atStartOfDay();
        s.receivableAmount = new BigDecimal("1.2346"); s.arrearsAmount = s.receivableAmount;
        s.receivedAmount = BigDecimal.ZERO; s.historicalReceivedAmount = BigDecimal.ZERO; statements.saveAndFlush(s);
        var request = new FinanceService.CustomerPaymentRequest(); request.paymentDate = LocalDate.now();
        request.amount = new BigDecimal("0.123456"); request.method = "银行转账";
        var result = finance.registerCustomerPayment("customer-" + c.id, request);
        Long statementId = s.id, paymentId = (Long) result.get("id");
        entityManager.flush(); entityManager.clear();
        amount("0.123456", payments.findById(paymentId).orElseThrow().amount);
        s = statements.findById(statementId).orElseThrow();
        amount("0.123456", s.receivedAmount); amount("1.111144", s.arrearsAmount);
        amount("1.2346", MoneyUtil.money(new BigDecimal("1.2346")));
    }
}
