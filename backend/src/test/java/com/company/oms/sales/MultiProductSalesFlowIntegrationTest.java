package com.company.oms.sales;

import com.company.oms.common.BusinessException;
import com.company.oms.finance.FinanceService;
import com.company.oms.finance.Statement;
import com.company.oms.finance.StatementItem;
import com.company.oms.finance.StatementItemRepository;
import com.company.oms.masterdata.Customer;
import com.company.oms.masterdata.CustomerRepository;
import com.company.oms.masterdata.Product;
import com.company.oms.masterdata.ProductRepository;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MultiProductSalesFlowIntegrationTest {
    @Autowired SalesService salesService;
    @Autowired ProductRepository productRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired SalesOrderItemRepository salesOrderItemRepository;
    @Autowired DeliveryNoteRepository deliveryNoteRepository;
    @Autowired DeliveryNoteItemRepository deliveryNoteItemRepository;
    @Autowired FinanceService financeService;
    @Autowired StatementItemRepository statementItemRepository;
    @Autowired SalesOrderExportService salesOrderExportService;

    @Test
    void quotationWithTwoProductsConvertsToOneOrderAndOneListRow() {
        Product first = product("MP-Q-01", "报价产品甲");
        Product second = product("MP-Q-02", "报价产品乙");
        Quotation quotation = new Quotation();
        quotation.quoteNo = "RA20990101001";
        quotation.quoteDate = LocalDate.of(2099, 1, 1);
        Customer customer = customer("多产品测试客户");
        quotation.customerId = customer.id;
        quotation.customerName = customer.name;

        QuotationItem firstQuoteItem = quotationItem(first, "10", "2");
        QuotationItem secondQuoteItem = quotationItem(second, "20", "3");
        quotation = salesService.saveQuotationWithItems(quotation, List.of(firstQuoteItem, secondQuoteItem));

        SalesService.QuotationConvertRequest request = new SalesService.QuotationConvertRequest();
        request.orderNo = "RB20990101001";
        request.orderDate = LocalDate.of(2099, 1, 1);
        request.deliveryDate = LocalDate.of(2099, 1, 10);
        SalesOrder order = salesService.convertQuotationToOrder(quotation.id, request);

        List<SalesOrderItem> items = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id);
        assertEquals(2, items.size());
        Map<String, Object> row = salesService.simpleOrders().stream()
                .filter(candidate -> order.id.equals(candidate.get("id"))).findFirst().orElseThrow();
        assertEquals(2, row.get("itemCount"));
        assertTrue(String.valueOf(row.get("productCodes")).contains(first.code));
        assertTrue(String.valueOf(row.get("productCodes")).contains(second.code));
        assertTrue(String.valueOf(row.get("productNames")).contains(first.name));
        assertTrue(String.valueOf(row.get("productNames")).contains(second.name));
        assertEquals(2, ((List<?>) row.get("productItems")).size());
        assertMoney("80.00", (BigDecimal) row.get("amount"));
    }

    @Test
    void generatedSalesOrderDoesNotPutProductSpecInRemarkColumn() throws Exception {
        Product product = product("MP-EXPORT-01", "导出产品");
        product.spec = "不应出现在备注栏的规格";
        productRepository.save(product);
        SalesOrder order = order("RB20990101002", 9002L, "订单导出测试客户",
                List.of(orderItem(product, "9", "80")));

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(salesOrderExportService.export(order.id)))) {
            assertEquals(product.code, workbook.getSheetAt(0).getRow(11).getCell(0).getStringCellValue());
            assertEquals("", workbook.getSheetAt(0).getRow(11).getCell(7).getStringCellValue());
        }
    }

    @Test
    void orderRejectsCustomerOutsideMasterData() {
        Product product = product("MP-V-01", "客户校验产品");
        SalesOrder order = new SalesOrder();
        order.orderNo = "RB20990102001";
        order.orderDate = LocalDate.of(2099, 1, 2);
        order.customerId = Long.MAX_VALUE;
        order.customerName = "不存在客户";

        BusinessException exception = assertThrows(BusinessException.class,
                () -> salesService.saveOrderWithItems(order, List.of(orderItem(product, "1", "1"))));
        assertTrue(exception.getMessage().contains("客户资料中没有该客户"));
    }

    @Test
    void orderRejectsProductOutsideMasterData() {
        Customer customer = customer("产品校验客户");
        SalesOrder order = new SalesOrder();
        order.orderNo = "RB20990102002";
        order.orderDate = LocalDate.of(2099, 1, 2);
        order.customerId = customer.id;
        order.customerName = customer.name;
        SalesOrderItem item = new SalesOrderItem();
        item.productId = Long.MAX_VALUE;
        item.productName = "不存在产品";
        item.unit = "件";
        item.qty = BigDecimal.ONE;
        item.unitPrice = BigDecimal.ONE;

        BusinessException exception = assertThrows(BusinessException.class,
                () -> salesService.saveOrderWithItems(order, List.of(item)));
        assertTrue(exception.getMessage().contains("产品不存在"));
    }

    @Test
    void orderUsesProductSpecificDeliveryDatesWithoutCommonDate() {
        Customer customer = customer("独立交货日期客户");
        Product first = product("MP-DATE-01", "独立日期产品甲");
        Product second = product("MP-DATE-02", "独立日期产品乙");
        SalesOrder order = new SalesOrder();
        order.orderNo = "RB20990102003";
        order.orderDate = LocalDate.of(2099, 1, 2);
        order.customerId = customer.id;
        SalesOrderItem firstItem = orderItem(first, "10", "1");
        firstItem.dueDate = LocalDate.of(2099, 1, 10);
        SalesOrderItem secondItem = orderItem(second, "20", "1");
        secondItem.dueDate = LocalDate.of(2099, 1, 20);

        SalesOrder saved = salesService.saveOrderWithItems(order, List.of(firstItem, secondItem));
        List<SalesOrderItem> savedItems = salesOrderItemRepository.findByOrderIdAndDeletedFalse(saved.id);

        assertNull(saved.deliveryDate);
        assertEquals(LocalDate.of(2099, 1, 10), itemByProduct(savedItems, first.id).dueDate);
        assertEquals(LocalDate.of(2099, 1, 20), itemByProduct(savedItems, second.id).dueDate);
    }

    @Test
    void orderRejectsItemWithoutDeliveryDateWhenCommonDateIsAbsent() {
        Customer customer = customer("交货日期必填客户");
        Product product = product("MP-DATE-03", "交货日期必填产品");
        SalesOrder order = new SalesOrder();
        order.orderNo = "RB20990102004";
        order.orderDate = LocalDate.of(2099, 1, 2);
        order.customerId = customer.id;

        BusinessException exception = assertThrows(BusinessException.class,
                () -> salesService.saveOrderWithItems(order, List.of(orderItem(product, "1", "1"))));

        assertTrue(exception.getMessage().contains("计划交货日期"));
    }

    @Test
    void batchDeliveryCombinesSameCustomerOrdersAndKeepsItemDetailsIsolated() {
        Product first = product("MP-D-01", "合并发货产品甲");
        Product second = product("MP-D-02", "合并发货产品乙");
        Product third = product("MP-D-03", "合并发货产品丙");
        SalesOrder firstOrder = order("RB20990201001", 9101L, "同一客户有限公司",
                List.of(orderItem(first, "10", "2"), orderItem(second, "8", "3")));
        SalesOrder secondOrder = order("RB20990201002", 9101L, "同一客户有限公司",
                List.of(orderItem(third, "6", "4")));
        List<SalesOrderItem> firstItems = salesOrderItemRepository.findByOrderIdAndDeletedFalse(firstOrder.id);
        SalesOrderItem firstItem = itemByProduct(firstItems, first.id);
        SalesOrderItem secondItem = itemByProduct(firstItems, second.id);
        SalesOrderItem thirdItem = itemByProduct(salesOrderItemRepository.findByOrderIdAndDeletedFalse(secondOrder.id), third.id);

        DeliveryNote combined = salesService.addBatchDelivery(batch(LocalDate.of(2099, 2, 2),
                line(firstItem.id, "4"), line(thirdItem.id, "6")));
        SalesService.DeliveryRegisterRequest separateRequest = new SalesService.DeliveryRegisterRequest();
        separateRequest.itemId = secondItem.id;
        separateRequest.deliveryQty = decimal("2");
        separateRequest.deliveryDate = LocalDate.of(2099, 2, 3);
        separateRequest.deliveryMethod = "物流";
        DeliveryNote secondOnly = salesService.addDelivery(firstOrder.id, separateRequest);

        assertEquals(2, deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(combined.id).size());
        assertMoney("32.00", combined.totalAmount);
        assertMoney("10", combined.deliveryQty);
        assertDeliveryIds(salesService.deliveryInvoiceDetail(firstOrder.id, firstItem.id), combined.id);
        assertDeliveryIds(salesService.deliveryInvoiceDetail(secondOrder.id, thirdItem.id), combined.id);
        assertDeliveryIds(salesService.deliveryInvoiceDetail(firstOrder.id, secondItem.id), secondOnly.id);
        assertFalse(deliveryIds(salesService.deliveryInvoiceDetail(firstOrder.id, firstItem.id)).contains(secondOnly.id));

        Map<String, Object> firstDetailDelivery = deliveryRows(salesService.deliveryInvoiceDetail(firstOrder.id, firstItem.id)).get(0);
        Map<String, Object> thirdDetailDelivery = deliveryRows(salesService.deliveryInvoiceDetail(secondOrder.id, thirdItem.id)).get(0);
        assertMoney("4", (BigDecimal) firstDetailDelivery.get("deliveryQty"));
        assertMoney("6", (BigDecimal) thirdDetailDelivery.get("deliveryQty"));

        salesService.deleteDeliveryInfo(combined.id);
        assertMoney("0", salesOrderItemRepository.findById(firstItem.id).orElseThrow().shippedQty);
        assertMoney("0", salesOrderItemRepository.findById(thirdItem.id).orElseThrow().shippedQty);
        assertFalse(deliveryNoteRepository.existsById(combined.id));
    }

    @Test
    @SuppressWarnings("unchecked")
    void actualDeliveryDateIsCalculatedForEachProduct() {
        Product first = product("MP-ACTUAL-01", "实际日期产品甲");
        Product second = product("MP-ACTUAL-02", "实际日期产品乙");
        SalesOrder order = order("RB20990201003", 9102L, "实际日期客户",
                List.of(orderItem(first, "10", "1"), orderItem(second, "8", "1")));
        List<SalesOrderItem> orderItems = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id);
        SalesOrderItem firstItem = itemByProduct(orderItems, first.id);
        SalesOrderItem secondItem = itemByProduct(orderItems, second.id);
        LocalDate firstCompletedAt = LocalDate.of(2099, 2, 2);
        LocalDate secondPartiallyDeliveredAt = LocalDate.of(2099, 2, 3);

        salesService.addBatchDelivery(batch(firstCompletedAt, line(firstItem.id, "10")));
        salesService.addBatchDelivery(batch(secondPartiallyDeliveredAt, line(secondItem.id, "3")));

        List<Map<String, Object>> productRows = (List<Map<String, Object>>)
                salesService.simpleOrderRow(order.id).get("productItems");
        assertEquals(firstCompletedAt, productRow(productRows, first.id).get("actualDeliveryDate"));
        assertNull(productRow(productRows, second.id).get("actualDeliveryDate"));

        LocalDate secondCompletedAt = LocalDate.of(2099, 2, 5);
        salesService.addBatchDelivery(batch(secondCompletedAt, line(secondItem.id, "5")));
        productRows = (List<Map<String, Object>>) salesService.simpleOrderRow(order.id).get("productItems");
        assertEquals(firstCompletedAt, productRow(productRows, first.id).get("actualDeliveryDate"));
        assertEquals(secondCompletedAt, productRow(productRows, second.id).get("actualDeliveryDate"));
    }

    @Test
    void batchDeliveryRejectsDifferentCustomersBeforeWritingAnything() {
        Product product = product("MP-X-01", "跨客户测试产品");
        SalesOrder firstOrder = order("RB20990301001", 9201L, "客户甲", List.of(orderItem(product, "5", "1")));
        SalesOrder secondOrder = order("RB20990301002", 9202L, "客户乙", List.of(orderItem(product, "5", "1")));
        SalesOrderItem firstItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(firstOrder.id).get(0);
        SalesOrderItem secondItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(secondOrder.id).get(0);
        long before = deliveryNoteRepository.count();

        assertThrows(BusinessException.class, () -> salesService.addBatchDelivery(batch(LocalDate.now(),
                line(firstItem.id, "1"), line(secondItem.id, "1"))));
        assertEquals(before, deliveryNoteRepository.count());
        assertMoney("0", salesOrderItemRepository.findById(firstItem.id).orElseThrow().shippedQty);
        assertMoney("0", salesOrderItemRepository.findById(secondItem.id).orElseThrow().shippedQty);
    }

    @Test
    void batchDeliveryRejectsOverShipmentWithoutPartialWrite() {
        Product product = product("MP-O-01", "超量发货测试产品");
        SalesOrder order = order("RB20990302001", 9251L, "超量测试客户", List.of(orderItem(product, "5", "1")));
        SalesOrderItem item = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0);
        long before = deliveryNoteRepository.count();

        assertThrows(BusinessException.class, () -> salesService.addBatchDelivery(batch(LocalDate.now(), line(item.id, "6"))));
        assertEquals(before, deliveryNoteRepository.count());
        assertMoney("0", salesOrderItemRepository.findById(item.id).orElseThrow().shippedQty);
    }

    @Test
    void batchDeliveryRejectsFractionalQuantityWithoutWriting() {
        Product product = product("MP-I-01", "整数发货测试产品");
        SalesOrder order = order("RB20990302002", 9252L, "整数发货测试客户", List.of(orderItem(product, "5", "1")));
        SalesOrderItem item = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0);
        long before = deliveryNoteRepository.count();

        BusinessException exception = assertThrows(BusinessException.class,
                () -> salesService.addBatchDelivery(batch(LocalDate.now(), line(item.id, "1.5"))));

        assertTrue(exception.getMessage().contains("整数"));
        assertEquals(before, deliveryNoteRepository.count());
        assertMoney("0", salesOrderItemRepository.findById(item.id).orElseThrow().shippedQty);
    }

    @Test
    void statementItemsKeepActualOrderNumbersFromCombinedDelivery() {
        Product first = product("MP-F-01", "对账关联产品甲");
        Product second = product("MP-F-02", "对账关联产品乙");
        SalesOrder firstOrder = order("RB20990303001", 9261L, "对账关联客户", List.of(orderItem(first, "2", "5")));
        SalesOrder secondOrder = order("RB20990303002", 9261L, "对账关联客户", List.of(orderItem(second, "3", "7")));
        SalesOrderItem firstItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(firstOrder.id).get(0);
        SalesOrderItem secondItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(secondOrder.id).get(0);
        DeliveryNote combined = salesService.addBatchDelivery(batch(LocalDate.now(),
                line(firstItem.id, "2"), line(secondItem.id, "3")));

        Statement statement = financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(combined.id)).get(0);
        List<String> orderNos = statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .map(item -> item.orderNo).sorted().toList();
        assertEquals(List.of(firstOrder.orderNo, secondOrder.orderNo), orderNos);
        assertMoney("31.00", statement.receivableAmount);
    }

    @Test
    void individualModelsCanBeSelectedOutOfOrderAndKeepTheirOwnRemainingQuantities() {
        Product first = product("MP-LINE-A", "同单型号甲");
        Product second = product("MP-LINE-B", "同单型号乙");
        SalesOrder order = order("RB20990306001", 9271L, "逐型号对账客户",
                List.of(orderItem(first, "10", "0.115"), orderItem(second, "20", "0.4")));
        List<SalesOrderItem> orderItems = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id);
        DeliveryNote delivery = salesService.addBatchDelivery(batch(LocalDate.now(),
                line(itemByProduct(orderItems, first.id).id, "10"),
                line(itemByProduct(orderItems, second.id).id, "20")));
        List<DeliveryNoteItem> source = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id);
        Long firstId = source.stream().filter(i -> i.productId.equals(first.id)).findFirst().orElseThrow().id;
        Long secondId = source.stream().filter(i -> i.productId.equals(second.id)).findFirst().orElseThrow().id;
        Statement partial = financeService.generateStatementsFromSelections(YearMonth.now().toString(),
                List.of(modelSelection(delivery.id, secondId, "7"))).get(0);
        List<StatementItem> saved = statementItemRepository.findByStatementIdAndDeletedFalse(partial.id);
        assertEquals(1, saved.size());
        assertEquals(secondId, saved.get(0).deliveryItemId);
        assertMoney("2.80", partial.receivableAmount);
        Map<String, Object> pending = financeService.statementCandidateDeliveries(YearMonth.now().toString()).stream()
                .filter(r -> delivery.id.equals(r.get("id"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") List<Map<String, Object>> products = (List<Map<String, Object>>) pending.get("products");
        Map<String, Object> a = products.stream().filter(p -> firstId.equals(p.get("id"))).findFirst().orElseThrow();
        Map<String, Object> b = products.stream().filter(p -> secondId.equals(p.get("id"))).findFirst().orElseThrow();
        assertMoney("10", (BigDecimal) a.get("remainingQty"));
        assertMoney("1.15", (BigDecimal) a.get("amount"));
        assertMoney("13", (BigDecimal) b.get("remainingQty"));
        assertMoney("5.20", (BigDecimal) b.get("amount"));
        assertEquals(order.orderNo, b.get("orderNo"));
        Statement rest = financeService.generateStatementsFromSelections(YearMonth.now().toString(),
                List.of(modelSelection(delivery.id, secondId, "13"), modelSelection(delivery.id, firstId, "10"))).get(0);
        assertMoney("6.35", rest.receivableAmount);
        assertEquals(2, statementItemRepository.findByStatementIdAndDeletedFalse(rest.id).size());
        assertTrue(financeService.statementCandidateDeliveries(YearMonth.now().toString()).stream()
                .noneMatch(r -> delivery.id.equals(r.get("id"))));
        financeService.deleteStatement(partial.id);
        Map<String, Object> restored = financeService.statementCandidateDeliveries(YearMonth.now().toString()).stream()
                .filter(r -> delivery.id.equals(r.get("id"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked") List<Map<String, Object>> restoredProducts = (List<Map<String, Object>>) restored.get("products");
        assertEquals(1, restoredProducts.size());
        assertEquals(secondId, restoredProducts.get(0).get("id"));
        assertMoney("7", (BigDecimal) restoredProducts.get(0).get("remainingQty"));
        assertMoney("2.80", (BigDecimal) restored.get("amount"));
    }

    @Test
    void modelSelectionsRejectDuplicatesMixedModesWrongDeliveryAndInvalidQuantities() {
        Product first = product("MP-LINE-VALID-A", "校验型号甲");
        Product second = product("MP-LINE-VALID-B", "校验型号乙");
        SalesOrder order = order("RB20990306002", 9272L, "型号校验客户",
                List.of(orderItem(first, "2", "5"), orderItem(second, "20", "7")));
        List<SalesOrderItem> orderItems = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id);
        DeliveryNote delivery = salesService.addBatchDelivery(batch(LocalDate.now(),
                line(itemByProduct(orderItems, first.id).id, "2"), line(itemByProduct(orderItems, second.id).id, "10")));
        DeliveryNote another = salesService.addBatchDelivery(batch(LocalDate.now(),
                line(itemByProduct(orderItems, second.id).id, "10")));
        Long itemId = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id).stream()
                .filter(i -> first.id.equals(i.productId)).findFirst().orElseThrow().id;
        Long foreignId = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(another.id).get(0).id;
        for (String qty : List.of("0", "-1", "1.5", "3")) {
            assertThrows(BusinessException.class, () -> financeService.generateStatementsFromSelections(
                    YearMonth.now().toString(), List.of(modelSelection(delivery.id, itemId, qty))));
        }
        var item = modelSelection(delivery.id, itemId, "1");
        var whole = new FinanceService.StatementDeliverySelection(delivery.id, decimal("1"));
        for (var selections : List.of(List.of(item, item), List.of(item, whole), List.of(whole, item),
                List.of(modelSelection(delivery.id, foreignId, "1")))) {
            assertThrows(BusinessException.class, () -> financeService.generateStatementsFromSelections(
                    YearMonth.now().toString(), selections));
        }
        assertTrue(statementItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id).isEmpty());
    }

    private FinanceService.StatementDeliverySelection modelSelection(Long deliveryId, Long itemId, String qty) {
        var selection = new FinanceService.StatementDeliverySelection(deliveryId, decimal(qty));
        selection.deliveryItemId = itemId;
        return selection;
    }

    @Test
    void statementQuantityCanBeReducedAndUsesActualProductPrices() {
        Product first = product("MP-Q-01", "可改数量产品甲");
        Product second = product("MP-Q-02", "可改数量产品乙");
        SalesOrder firstOrder = order("RB20990304001", 9262L, "可改数量客户",
                List.of(orderItem(first, "2", "5")));
        SalesOrder secondOrder = order("RB20990304002", 9262L, "可改数量客户",
                List.of(orderItem(second, "3", "7")));
        SalesOrderItem firstItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(firstOrder.id).get(0);
        SalesOrderItem secondItem = salesOrderItemRepository.findByOrderIdAndDeletedFalse(secondOrder.id).get(0);
        DeliveryNote combined = salesService.addBatchDelivery(batch(LocalDate.now(),
                line(firstItem.id, "2"), line(secondItem.id, "3")));

        FinanceService.StatementDeliverySelection selection =
                new FinanceService.StatementDeliverySelection(combined.id, new BigDecimal("3"));
        Statement partial = financeService.generateStatementsFromSelections(
                YearMonth.now().toString(), List.of(selection)).get(0);

        assertMoney("17.00", partial.receivableAmount);
        List<StatementItem> partialItems = statementItemRepository
                .findByStatementIdAndDeletedFalse(partial.id);
        assertEquals(2, partialItems.size());
        assertMoney("3", partialItems.stream().map(item -> item.qty)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        Map<String, Object> candidate = financeService.statementCandidateDeliveries(
                        YearMonth.now().toString()).stream()
                .filter(row -> combined.id.equals(row.get("id")))
                .findFirst().orElseThrow();
        assertMoney("2", (BigDecimal) candidate.get("qty"));
        assertMoney("5", (BigDecimal) candidate.get("deliveredQty"));
        assertMoney("3", (BigDecimal) candidate.get("statementedQty"));
        assertMoney("2", (BigDecimal) candidate.get("maxQty"));
        assertMoney("14.00", (BigDecimal) candidate.get("amount"));

        FinanceService.StatementDeliverySelection excessive =
                new FinanceService.StatementDeliverySelection(combined.id, new BigDecimal("3"));
        assertThrows(BusinessException.class, () -> financeService.generateStatementsFromSelections(
                YearMonth.now().toString(), List.of(excessive)));
    }

    @Test
    void partialStatementsCanFinishAndDeletingAnEarlierPartRestoresTheCorrectProduct() {
        Product first = product("MP-SPLIT-01", "分次对账甲");
        Product second = product("MP-SPLIT-02", "分次对账乙");
        SalesOrderItem firstLine = orderItem(first, "2", "5");
        firstLine.spec = "MODEL-A";
        SalesOrderItem secondLine = orderItem(second, "3", "7");
        secondLine.spec = "MODEL-B";
        SalesOrder order = order("RB20990305001", 9270L, "分次对账客户", List.of(firstLine, secondLine));
        List<SalesOrderItem> orderLines = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id);
        DeliveryNote delivery = salesService.addBatchDelivery(batch(LocalDate.now(),
                line(itemByProduct(orderLines, first.id).id, "2"),
                line(itemByProduct(orderLines, second.id).id, "3")));
        Statement firstPart = financeService.generateStatementsFromSelections(YearMonth.now().toString(),
                List.of(new FinanceService.StatementDeliverySelection(delivery.id, decimal("2")))).get(0);
        Statement secondPart = financeService.generateStatementsFromSelections(YearMonth.now().toString(),
                List.of(new FinanceService.StatementDeliverySelection(delivery.id, decimal("3")))).get(0);
        assertMoney("10", firstPart.receivableAmount);
        assertMoney("21", secondPart.receivableAmount);
        assertTrue(financeService.statementCandidateDeliveries(YearMonth.now().toString()).stream()
                .noneMatch(row -> delivery.id.equals(row.get("id"))));

        financeService.deleteStatement(firstPart.id);
        Map<String, Object> restored = financeService.statementCandidateDeliveries(YearMonth.now().toString()).stream()
                .filter(row -> delivery.id.equals(row.get("id"))).findFirst().orElseThrow();
        assertMoney("2", (BigDecimal) restored.get("qty"));
        assertMoney("10", (BigDecimal) restored.get("amount"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) restored.get("products");
        assertEquals(1, products.size());
        assertEquals("MP-SPLIT-01", products.get(0).get("code"));
        assertEquals("MODEL-A", products.get(0).get("spec"));
        Statement replacement = financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(delivery.id)).get(0);
        StatementItem replacementItem = statementItemRepository.findByStatementIdAndDeletedFalse(replacement.id).get(0);
        assertEquals(first.name, replacementItem.productName);
        assertNotNull(replacementItem.deliveryItemId);
        assertMoney("10", replacementItem.amount);
    }

    @Test
    void quantitiesRejectInvalidInputsAndAlwaysCalculateQuantityTimesPrice() {
        Product product = product("MP-INTEGER-01", "整数数量产品");
        SalesOrder order = order("RB20990306001", 9271L, "整数数量客户", List.of(orderItem(product, "3", "3.55")));
        SalesOrderItem line = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).get(0);
        DeliveryNote delivery = salesService.addBatchDelivery(batch(LocalDate.now(), line(line.id, "3")));
        // Imported totals can disagree with the price; new statements must always use quantity * price.
        DeliveryNoteItem source = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id).get(0);
        source.amount = decimal("99");
        deliveryNoteItemRepository.save(source);
        for (String invalid : List.of("0", "-1", "4", "0.5", "1.001", "0.0001")) {
            assertThrows(BusinessException.class, () -> financeService.generateStatementsFromSelections(
                    YearMonth.now().toString(), List.of(new FinanceService.StatementDeliverySelection(delivery.id, decimal(invalid)))));
        }
        assertTrue(statementItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id).isEmpty());
        Statement partial = financeService.generateStatementsFromSelections(YearMonth.now().toString(),
                List.of(new FinanceService.StatementDeliverySelection(delivery.id, decimal("1.000")))).get(0);
        assertMoney("3.55", partial.receivableAmount);
        Map<String, Object> candidate = financeService.statementCandidateDeliveries(YearMonth.now().toString()).stream()
                .filter(row -> delivery.id.equals(row.get("id"))).findFirst().orElseThrow();
        assertMoney("2", (BigDecimal) candidate.get("maxQty"));
        assertMoney("7.10", (BigDecimal) candidate.get("amount"));
        Statement rest = financeService.generateStatementsFromDeliveries(YearMonth.now().toString(), List.of(delivery.id)).get(0);
        assertMoney("7.10", rest.receivableAmount);
        assertTrue(financeService.statementCandidateDeliveries(YearMonth.now().toString()).stream()
                .noneMatch(row -> delivery.id.equals(row.get("id"))));
    }

    @Test
    void editingOrderPreservesShippedItemIdAndQuantity() {
        Product first = product("MP-E-01", "编辑保护产品甲");
        Product second = product("MP-E-02", "编辑保护产品乙");
        SalesOrder order = order("RB20990401001", 9301L, "编辑保护客户",
                List.of(orderItem(first, "10", "2"), orderItem(second, "5", "3")));
        List<SalesOrderItem> originalItems = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id);
        SalesOrderItem shippedItem = itemByProduct(originalItems, first.id);
        SalesOrderItem otherItem = itemByProduct(originalItems, second.id);
        salesService.addBatchDelivery(batch(LocalDate.now(), line(shippedItem.id, "4")));

        SalesOrder editableOrder = (SalesOrder) salesService.orderDetail(order.id).get("order");
        List<SalesOrderItem> editableItems = new ArrayList<>(salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id));
        editableItems.stream().filter(item -> item.id.equals(shippedItem.id)).findFirst().orElseThrow().qty = decimal("12");
        editableItems.stream().filter(item -> item.id.equals(otherItem.id)).findFirst().orElseThrow().qty = decimal("7");
        salesService.saveOrderWithItems(editableOrder, editableItems);

        SalesOrderItem savedShippedItem = salesOrderItemRepository.findById(shippedItem.id).orElseThrow();
        assertMoney("12", savedShippedItem.qty);
        assertMoney("4", savedShippedItem.shippedQty);
        assertEquals(2, salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).size());
    }

    @Test
    void editingCannotRemoveOrReduceShippedProduct() {
        Product product = product("MP-G-01", "不可删除产品");
        Product otherProduct = product("MP-G-02", "可保留产品");
        SalesOrder order = order("RB20990501001", 9401L, "保护客户",
                List.of(orderItem(product, "10", "2"), orderItem(otherProduct, "2", "1")));
        List<SalesOrderItem> orderItems = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id);
        SalesOrderItem item = itemByProduct(orderItems, product.id);
        SalesOrderItem otherItem = itemByProduct(orderItems, otherProduct.id);
        salesService.addBatchDelivery(batch(LocalDate.now(), line(item.id, "4")));
        SalesOrder editableOrder = (SalesOrder) salesService.orderDetail(order.id).get("order");

        assertThrows(BusinessException.class, () -> salesService.saveOrderWithItems(editableOrder, List.of(otherItem)));
        item.qty = decimal("3");
        assertThrows(BusinessException.class, () -> salesService.saveOrderWithItems(editableOrder, List.of(item, otherItem)));
    }

    @Test
    void deliveryEditRecomputesOrderAndFinanceDataAndLocksStatementedQuantities() {
        Product first = product("MP-EDIT-D-01", "修改发货产品甲");
        Product second = product("MP-EDIT-D-02", "修改发货产品乙");
        SalesOrder order = order("RB20990502001", 9402L, "修改发货客户",
                List.of(orderItem(first, "10", "2"), orderItem(second, "8", "3")));
        List<SalesOrderItem> items = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id);
        SalesOrderItem firstItem = itemByProduct(items, first.id);
        SalesOrderItem secondItem = itemByProduct(items, second.id);
        LocalDate originalDate = LocalDate.now();
        DeliveryNote delivery = salesService.addBatchDelivery(batch(originalDate, line(firstItem.id, "4")));

        SalesService.BatchDeliveryRequest revised = batch(originalDate.plusDays(1),
                line(firstItem.id, "6"), line(secondItem.id, "2"));
        revised.deliveryMethod = "自提";
        revised.logisticsNo = "WL-EDIT-001";
        DeliveryNote saved = salesService.updateDeliveryInfo(delivery.id, revised);

        assertMoney("8", saved.deliveryQty);
        assertMoney("18.00", saved.totalAmount);
        assertEquals("自提", saved.deliveryMethod);
        assertEquals("WL-EDIT-001", saved.logisticsNo);
        assertEquals(2, deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(saved.id).size());
        assertMoney("6", salesOrderItemRepository.findById(firstItem.id).orElseThrow().shippedQty);
        assertMoney("2", salesOrderItemRepository.findById(secondItem.id).orElseThrow().shippedQty);

        Statement statement = financeService.generateStatementsFromDeliveries(
                YearMonth.now().toString(), List.of(saved.id)).get(0);
        SalesService.BatchDeliveryRequest invalidQuantity = batch(originalDate.plusDays(2),
                line(firstItem.id, "5"), line(secondItem.id, "2"));
        BusinessException exception = assertThrows(BusinessException.class,
                () -> salesService.updateDeliveryInfo(saved.id, invalidQuantity));
        assertTrue(exception.getMessage().contains("只能修改"));

        SalesService.BatchDeliveryRequest metadataOnly = batch(originalDate.plusDays(2),
                line(firstItem.id, "6"), line(secondItem.id, "2"));
        metadataOnly.deliveryMethod = "物流";
        DeliveryNote metadataSaved = salesService.updateDeliveryInfo(saved.id, metadataOnly);
        assertEquals(originalDate.plusDays(2), metadataSaved.deliveryDate);
        assertTrue(statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .allMatch(item -> originalDate.plusDays(2).equals(item.deliveryDate)));
        assertEquals(Boolean.TRUE, salesService.deliveryDetail(saved.id).get("quantityLocked"));
    }

    private Product product(String code, String name) {
        Product product = new Product();
        product.code = code;
        product.name = name;
        product.unit = "件";
        product.defaultPrice = BigDecimal.ONE;
        product.enabled = true;
        return productRepository.save(product);
    }

    private QuotationItem quotationItem(Product product, String qty, String price) {
        QuotationItem item = new QuotationItem();
        item.productId = product.id;
        item.productCode = product.code;
        item.productName = product.name;
        item.unit = product.unit;
        item.qty = decimal(qty);
        item.unitPrice = decimal(price);
        return item;
    }

    private SalesOrder order(String orderNo, Long customerId, String customerName, List<SalesOrderItem> items) {
        Customer customer = customerRepository.findAll().stream()
                .filter(existing -> customerName.equals(existing.name) && !Boolean.TRUE.equals(existing.deleted))
                .findFirst()
                .orElseGet(() -> customer(customerName));
        SalesOrder order = new SalesOrder();
        order.orderNo = orderNo;
        order.orderDate = LocalDate.now();
        order.deliveryDate = LocalDate.now().plusDays(7);
        order.customerId = customer.id;
        order.customerName = customer.name;
        return salesService.saveOrderWithItems(order, items);
    }

    private Customer customer(String name) {
        Customer customer = new Customer();
        customer.name = name;
        customer.enabled = true;
        return customerRepository.save(customer);
    }

    private SalesOrderItem orderItem(Product product, String qty, String price) {
        SalesOrderItem item = new SalesOrderItem();
        item.productId = product.id;
        item.productCode = product.code;
        item.productName = product.name;
        item.unit = product.unit;
        item.qty = decimal(qty);
        item.unitPrice = decimal(price);
        return item;
    }

    private SalesOrderItem itemByProduct(List<SalesOrderItem> items, Long productId) {
        return items.stream().filter(item -> productId.equals(item.productId)).findFirst().orElseThrow();
    }

    private Map<String, Object> productRow(List<Map<String, Object>> rows, Long productId) {
        return rows.stream().filter(row -> productId.equals(row.get("productId"))).findFirst().orElseThrow();
    }

    private SalesService.BatchDeliveryRequest batch(LocalDate date, SalesService.BatchDeliveryLine... lines) {
        SalesService.BatchDeliveryRequest request = new SalesService.BatchDeliveryRequest();
        request.deliveryDate = date;
        request.deliveryMethod = "物流";
        request.items = List.of(lines);
        return request;
    }

    private SalesService.BatchDeliveryLine line(Long orderItemId, String qty) {
        SalesService.BatchDeliveryLine line = new SalesService.BatchDeliveryLine();
        line.orderItemId = orderItemId;
        line.qty = decimal(qty);
        return line;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> deliveryRows(Map<String, Object> detail) {
        return (List<Map<String, Object>>) detail.get("deliveries");
    }

    private List<Long> deliveryIds(Map<String, Object> detail) {
        return deliveryRows(detail).stream().map(row -> (Long) row.get("id")).toList();
    }

    private void assertDeliveryIds(Map<String, Object> detail, Long... expectedIds) {
        assertEquals(List.of(expectedIds), deliveryIds(detail));
    }

    private BigDecimal decimal(String value) { return new BigDecimal(value); }
    private void assertMoney(String expected, BigDecimal actual) { assertEquals(0, decimal(expected).compareTo(actual)); }
}
