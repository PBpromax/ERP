package com.company.oms.masterdata;

import com.company.oms.common.BusinessException;
import com.company.oms.sales.Quotation;
import com.company.oms.sales.QuotationItem;
import com.company.oms.sales.QuotationItemRepository;
import com.company.oms.sales.SalesOrder;
import com.company.oms.sales.SalesOrderItemRepository;
import com.company.oms.sales.SalesService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductWithoutCodeIntegrationTest {
    @Autowired ProductService productService;
    @Autowired ProductRepository productRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired SalesService salesService;
    @Autowired SalesOrderItemRepository salesOrderItemRepository;
    @Autowired QuotationItemRepository quotationItemRepository;
    @Autowired EntityManager entityManager;

    @Test
    void productAliasesAreTrimmedDeduplicatedAndPersisted() {
        Product product = product("产品别称测试", "TEST-ALIAS-001");
        product.aliases = List.of(" 客户旧称 ", "客户旧称", "内部简称");

        Product saved = productService.create(product);
        assertEquals(List.of("客户旧称", "内部简称"), saved.aliases);

        entityManager.flush();
        entityManager.clear();
        assertEquals(List.of("客户旧称", "内部简称"), productService.get(saved.id).aliases);
    }

    @Test
    void multipleProductsWithoutCodesCanBeCreated() {
        Product first = productService.create(product("台账待编号产品一", null));
        Product second = productService.create(product("台账待编号产品二", "  "));

        assertNull(first.code);
        assertNull(second.code);
        assertNotEquals(first.id, second.id);
    }

    @Test
    void duplicateNonBlankCodeIsStillRejected() {
        productService.create(product("已编号产品一", "TEST-UNIQUE-001"));
        assertThrows(BusinessException.class,
                () -> productService.create(product("已编号产品二", "TEST-UNIQUE-001")));
    }

    @Test
    void productWithoutCodeCanBeQuotedAndConvertedToOrder() {
        Product product = productService.create(product("台账待编号销售产品", null));
        Quotation quotation = new Quotation();
        quotation.quoteDate = LocalDate.of(2036, 2, 3);
        Customer customer = customer("待编号产品测试客户");
        quotation.customerId = customer.id;
        quotation.customerName = customer.name;

        QuotationItem item = new QuotationItem();
        item.productId = product.id;
        item.productName = product.name;
        item.unit = product.unit;
        item.qty = new BigDecimal("12");
        item.unitPrice = new BigDecimal("3.50");

        Quotation saved = salesService.saveQuotationWithItems(quotation, List.of(item));
        QuotationItem savedItem = quotationItemRepository.findByQuotationIdAndDeletedFalse(saved.id).get(0);
        assertNull(savedItem.productCode);

        SalesService.QuotationConvertRequest request = new SalesService.QuotationConvertRequest();
        request.orderDate = quotation.quoteDate;
        request.deliveryDate = quotation.quoteDate.plusDays(5);
        SalesOrder order = salesService.convertQuotationToOrder(saved.id, request);
        assertNotNull(order.id);
        Quotation converted = (Quotation) salesService.quotationDetail(saved.id).get("quotation");
        assertEquals("已转订单", converted.status);
    }

    @Test
    void referencedProductRequiresConfirmationAndKeepsOrderHistoryAfterDeletion() {
        Product product = productService.create(product("订单历史删除测试产品", "TEST-DELETE-001"));
        Quotation quotation = new Quotation();
        quotation.quoteDate = LocalDate.of(2036, 2, 4);
        Customer customer = customer("产品删除测试客户");
        quotation.customerId = customer.id;
        quotation.customerName = customer.name;

        QuotationItem item = new QuotationItem();
        item.productId = product.id;
        item.productCode = product.code;
        item.productName = product.name;
        item.unit = product.unit;
        item.qty = new BigDecimal("8");
        item.unitPrice = new BigDecimal("2.50");

        Quotation saved = salesService.saveQuotationWithItems(quotation, List.of(item));
        SalesService.QuotationConvertRequest request = new SalesService.QuotationConvertRequest();
        request.orderDate = quotation.quoteDate;
        request.deliveryDate = quotation.quoteDate.plusDays(3);
        SalesOrder order = salesService.convertQuotationToOrder(saved.id, request);

        ProductService.ProductReferenceInfo references = productService.references(product.id);
        assertTrue(references.referenced);
        assertTrue(references.usedInOrders);
        assertThrows(BusinessException.class, () -> productService.delete(product.id, false));

        productService.delete(product.id, true);

        Product deleted = productRepository.findById(product.id).orElseThrow();
        assertTrue(deleted.deleted);
        assertThrows(BusinessException.class, () -> productService.get(product.id));
        assertEquals("订单历史删除测试产品",
                salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0).productName);
    }

    private Product product(String name, String code) {
        Product product = new Product();
        product.code = code;
        product.name = name;
        product.categoryCode = "Y";
        product.categoryName = "原材料";
        product.spec = name;
        product.unit = "条";
        product.defaultPrice = BigDecimal.ZERO;
        product.costPrice = BigDecimal.ZERO;
        product.safetyStock = BigDecimal.ZERO;
        return product;
    }

    private Customer customer(String name) {
        Customer customer = new Customer();
        customer.name = name;
        customer.enabled = true;
        return customerRepository.save(customer);
    }
}
