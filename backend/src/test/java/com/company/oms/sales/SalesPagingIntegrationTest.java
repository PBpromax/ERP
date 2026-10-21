package com.company.oms.sales;

import com.company.oms.common.PageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SalesPagingIntegrationTest {
    @Autowired SalesService salesService;
    @Autowired SalesOrderRepository salesOrderRepository;
    @Autowired SalesOrderItemRepository salesOrderItemRepository;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    @AfterEach
    void clearPageCaches() {
        List.of("salesOrderPages", "deliveryBoardPages").forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    @Test
    void orderPagingExecutesKeywordStatusCountAndPageQueries() {
        String keyword = "PAGING-ORDER-QUERY";
        SalesOrder unshipped = order("RB21990101001", 99101L, "分页订单客户", keyword);
        item(unshipped.id, "PAGING-ONLY-PRODUCT", "10", "0", LocalDate.now().plusDays(3));

        SalesOrder partial = order("RB21990101002", 99101L, "分页订单客户", keyword);
        item(partial.id, "PARTIAL", "10", "4", LocalDate.now().plusDays(3));

        SalesOrder delivered = order("RB21990101003", 99101L, "分页订单客户", keyword);
        item(delivered.id, "DELIVERED", "10", "10", LocalDate.now().minusDays(1));

        SalesOrder zeroQtyDelivered = order("RB21990101004", 99101L, "分页订单客户", keyword);
        item(zeroQtyDelivered.id, "ZERO-QTY", "0", "0", LocalDate.now().minusDays(1));

        Page<SalesOrder> firstPage = salesOrderRepository.searchActiveOrders(
                keyword, "all", false, LocalDate.of(1900, 1, 1), LocalDate.of(2200, 1, 1),
                PageRequest.of(0, 2));
        assertEquals(4, firstPage.getTotalElements());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(2, firstPage.getTotalPages());

        assertEquals(1, salesOrderRepository.searchActiveOrders(
                "PAGING-ONLY-PRODUCT", "all", false, LocalDate.of(1900, 1, 1),
                LocalDate.of(2200, 1, 1), PageRequest.of(0, 10)).getTotalElements());
        assertEquals(2, salesOrderRepository.searchActiveOrders(
                keyword, "unfulfilled", false, LocalDate.of(1900, 1, 1),
                LocalDate.of(2200, 1, 1), PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, salesOrderRepository.searchActiveOrders(
                keyword, "未发货", false, LocalDate.of(1900, 1, 1),
                LocalDate.of(2200, 1, 1), PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, salesOrderRepository.searchActiveOrders(
                keyword, "部分发货", false, LocalDate.of(1900, 1, 1),
                LocalDate.of(2200, 1, 1), PageRequest.of(0, 10)).getTotalElements());
        assertEquals(2, salesOrderRepository.searchActiveOrders(
                keyword, "已送", false, LocalDate.of(1900, 1, 1),
                LocalDate.of(2200, 1, 1), PageRequest.of(0, 10)).getTotalElements());
        assertEquals(2, salesOrderRepository.searchActiveOrders(
                keyword, "未发货,部分发货", false, LocalDate.of(1900, 1, 1),
                LocalDate.of(2200, 1, 1), PageRequest.of(0, 10)).getTotalElements());

        PageResponse<?> response = salesService.simpleOrdersPage(0, 2, keyword, "all", null);
        assertEquals(4, response.total());
        assertEquals(2, response.items().size());
        assertEquals(2, response.totalPages());
    }

    @Test
    void deliveryPagingExecutesFulfillmentDateAndCustomerCandidateQueries() {
        LocalDate today = LocalDate.now();
        String keyword = "PAGING-DELIVERY-CUSTOMER";
        Long customerId = 99201L;
        SalesOrder order = order("RB21990201001", customerId, keyword, "DELIVERY-GROUP");
        SalesOrderItem overdue = item(order.id, "OVERDUE", "10", "0", today.minusDays(1));
        SalesOrderItem unshipped = item(order.id, "UNSHIPPED", "10", "0", today.plusDays(1));
        SalesOrderItem partial = item(order.id, "PARTIAL", "10", "3", today.plusDays(1));
        SalesOrderItem completed = item(order.id, "COMPLETED", "10", "10", today.minusDays(1));
        item(order.id, "ZERO-QTY", "0", "0", today.minusDays(1));

        SalesOrder otherCustomer = order("RB21990201002", 99202L, "其他分页客户", "OTHER-GROUP");
        SalesOrderItem otherItem = item(otherCustomer.id, "OTHER", "10", "0", today.plusDays(1));

        Page<SalesOrderItem> firstPage = salesOrderItemRepository.searchDeliveryBoard(
                keyword, false, false, "all", today, PageRequest.of(0, 2));
        assertEquals(5, firstPage.getTotalElements());
        assertEquals(2, firstPage.getContent().size());
        assertEquals(3, firstPage.getTotalPages());

        assertEquals(3, salesOrderItemRepository.searchDeliveryBoard(
                keyword, true, false, "all", today, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, salesOrderItemRepository.searchDeliveryBoard(
                keyword, false, true, "all", today, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, salesOrderItemRepository.searchDeliveryBoard(
                keyword, false, false, "overdue", today, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, salesOrderItemRepository.searchDeliveryBoard(
                keyword, false, false, "未发货", today, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, salesOrderItemRepository.searchDeliveryBoard(
                keyword, false, false, "部分发货", today, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(2, salesOrderItemRepository.searchDeliveryBoard(
                keyword, false, false, "已发送", today, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(2, salesOrderItemRepository.searchDeliveryBoard(
                keyword, false, false, "未发货,部分发货", today, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(3, salesOrderItemRepository.searchDeliveryBoard(
                keyword, false, false, "逾期未发货,已发送", today, PageRequest.of(0, 10)).getTotalElements());

        Page<SalesOrderItem> candidates = salesOrderItemRepository.findAvailableForCustomer(
                customerId, PageRequest.of(0, 10));
        Set<Long> candidateIds = candidates.getContent().stream()
                .map(candidate -> candidate.id)
                .collect(Collectors.toSet());
        assertEquals(3, candidates.getTotalElements());
        assertTrue(candidateIds.containsAll(List.of(overdue.id, unshipped.id, partial.id)));
        assertFalse(candidateIds.contains(completed.id));
        assertFalse(candidateIds.contains(otherItem.id));

        PageResponse<?> response = salesService.deliveryInvoicePage(0, 2, keyword, "all", "all");
        assertEquals(5, response.total());
        assertEquals(2, response.items().size());
    }

    @Test
    void orderWriteEvictsCachedPage() {
        String keyword = "CACHE-EVICTION-ORDER";
        assertEquals(0, salesService.simpleOrdersPage(0, 10, keyword, "all", null).total());

        SalesService.SimpleOrderRequest request = new SalesService.SimpleOrderRequest();
        request.orderNo = "RB21990301001";
        request.sourceOrderNo = keyword;
        request.customerName = "缓存失效客户";
        request.productCode = "CACHE-PRODUCT";
        request.productName = "缓存测试产品";
        request.unit = "件";
        request.qty = BigDecimal.ONE;
        request.unitPrice = BigDecimal.TEN;
        request.deliveryDate = LocalDate.now().plusDays(1);
        salesService.saveSimpleOrder(request);

        assertEquals(1, salesService.simpleOrdersPage(0, 10, keyword, "all", null).total());
    }

    @Test
    void orderPagingFiltersByOrderYear() {
        String keyword = "PAGING-YEAR-FILTER";
        SalesOrder current = order("RB21990401001", 99401L, "年份筛选客户", keyword);
        item(current.id, "CURRENT-YEAR", "1", "0", LocalDate.now().plusDays(1));

        SalesOrder previous = order("RB21990401002", 99401L, "年份筛选客户", keyword);
        previous.orderDate = LocalDate.now().minusYears(1);
        salesOrderRepository.save(previous);
        item(previous.id, "PREVIOUS-YEAR", "1", "0", LocalDate.now().minusYears(1));

        assertEquals(1, salesService.simpleOrdersPage(
                0, 10, keyword, "all", LocalDate.now().getYear()).total());
        assertEquals(1, salesService.simpleOrdersPage(
                0, 10, keyword, "all", LocalDate.now().getYear() - 1).total());
        assertEquals(2, salesService.simpleOrdersPage(0, 10, keyword, "all", null).total());
    }

    @Test
    void hundredRowPagesHaveNoGapsOrDuplicatesAndSearchCoversLaterPages() {
        String keyword = "HUNDRED-ROW-PAGING";
        for (int index = 0; index < 103; index++) {
            SalesOrder order = order("RB219905" + String.format("%05d", index), 99501L, keyword, keyword);
            item(order.id, "HUNDRED-PRODUCT-" + index, "10", "0", LocalDate.now().plusDays(1));
        }
        PageResponse<?> first = salesService.simpleOrdersPage(0, 100, keyword, "all", null);
        PageResponse<?> second = salesService.simpleOrdersPage(1, 100, keyword, "all", null);
        assertEquals(103, first.total());
        assertEquals(2, first.totalPages());
        assertEquals(100, first.items().size());
        assertEquals(3, second.items().size());
        Set<Object> orderIds = new java.util.HashSet<>();
        for (Object row : first.items()) assertTrue(orderIds.add(((java.util.Map<?, ?>) row).get("id")));
        for (Object row : second.items()) assertTrue(orderIds.add(((java.util.Map<?, ?>) row).get("id")));
        assertEquals(103, orderIds.size());
        assertEquals(1, salesService.simpleOrdersPage(0, 100, "HUNDRED-PRODUCT-102", "all", null).total());

        PageResponse<?> deliveryFirst = salesService.deliveryInvoicePage(0, 100, keyword, "all", "all");
        PageResponse<?> deliverySecond = salesService.deliveryInvoicePage(1, 100, keyword, "all", "all");
        assertEquals(103, deliveryFirst.total());
        assertEquals(100, deliveryFirst.items().size());
        assertEquals(3, deliverySecond.items().size());
        Set<Object> itemIds = new java.util.HashSet<>();
        for (Object row : deliveryFirst.items()) assertTrue(itemIds.add(((java.util.Map<?, ?>) row).get("itemId")));
        for (Object row : deliverySecond.items()) assertTrue(itemIds.add(((java.util.Map<?, ?>) row).get("itemId")));
        assertEquals(103, itemIds.size());
    }

    private SalesOrder order(String orderNo, Long customerId, String customerName, String sourceOrderNo) {
        SalesOrder order = new SalesOrder();
        order.orderNo = orderNo;
        order.sourceOrderNo = sourceOrderNo;
        order.customerId = customerId;
        order.customerName = customerName;
        order.orderDate = LocalDate.now();
        order.deliveryDate = LocalDate.now().plusDays(1);
        order.status = SalesService.DELIVERY_STATUS_UNSHIPPED;
        order.totalAmount = BigDecimal.TEN;
        return salesOrderRepository.save(order);
    }

    private SalesOrderItem item(Long orderId, String productName, String qty, String shippedQty,
                                LocalDate dueDate) {
        SalesOrderItem item = new SalesOrderItem();
        item.orderId = orderId;
        item.productCode = productName;
        item.productName = productName;
        item.unit = "件";
        item.qty = new BigDecimal(qty);
        item.shippedQty = new BigDecimal(shippedQty);
        item.unitPrice = BigDecimal.ONE;
        item.amount = item.qty;
        item.dueDate = dueDate;
        return salesOrderItemRepository.save(item);
    }
}
