package com.company.oms.sales;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeliveryBoardIntegrationTest {
    @Autowired SalesService salesService;
    @Autowired SalesOrderRepository salesOrderRepository;
    @Autowired SalesOrderItemRepository salesOrderItemRepository;

    @Test
    void fulfillmentStatusesRespectPlannedDateBoundaries() {
        LocalDate today = LocalDate.of(2035, 6, 15);

        assertEquals("逾期未发货", SalesService.fulfillmentStatus(decimal("10"), BigDecimal.ZERO, today.minusDays(1), today));
        assertEquals("未发货", SalesService.fulfillmentStatus(decimal("10"), BigDecimal.ZERO, today, today));
        assertEquals("未发货", SalesService.fulfillmentStatus(decimal("10"), BigDecimal.ZERO, today.plusDays(1), today));
        assertEquals("逾期部分发货", SalesService.fulfillmentStatus(decimal("10"), decimal("3"), today.minusDays(1), today));
        assertEquals("部分发货", SalesService.fulfillmentStatus(decimal("10"), decimal("3"), today, today));
        assertEquals("已发送", SalesService.fulfillmentStatus(decimal("10"), decimal("10"), today.minusDays(1), today));
        assertEquals("未发货", SalesService.fulfillmentStatus(decimal("10"), BigDecimal.ZERO, null, today));
    }

    @Test
    void eachProductUsesItsOwnLatestDeliveryAndDeletionFallsBack() {
        LocalDate today = LocalDate.now();
        SalesOrder order = new SalesOrder();
        order.orderNo = "RB20991231001";
        order.orderDate = today;
        order.deliveryDate = today.plusDays(5);
        order.status = SalesService.DELIVERY_STATUS_UNSHIPPED;
        order = salesOrderRepository.save(order);

        SalesOrderItem overdueItem = item(order.id, "OVERDUE", "10", today.minusDays(1));
        SalesOrderItem futureItem = item(order.id, "FUTURE", "10", today.plusDays(1));
        LocalDate firstDate = today.minusDays(3);
        LocalDate latestDate = today.minusDays(1);
        DeliveryNote first = deliver(order.id, overdueItem.id, "2", firstDate);
        DeliveryNote latest = deliver(order.id, overdueItem.id, "3", latestDate);
        deliver(order.id, futureItem.id, "10", today);

        Map<String, Object> overdueRow = row(overdueItem.id);
        Map<String, Object> futureRow = row(futureItem.id);
        assertEquals("逾期部分发货", overdueRow.get("fulfillmentStatus"));
        assertEquals(latestDate, overdueRow.get("lastDeliveryDate"));
        assertEquals(0, decimal("5").compareTo((BigDecimal) overdueRow.get("unshippedQty")));
        assertEquals("已发送", futureRow.get("fulfillmentStatus"));
        assertEquals(today, futureRow.get("lastDeliveryDate"));

        Map<String, Object> detail = salesService.deliveryInvoiceDetail(order.id, overdueItem.id);
        Map<?, ?> summary = (Map<?, ?>) detail.get("summary");
        assertEquals(latestDate, summary.get("lastDeliveryDate"));
        assertEquals("逾期部分发货", summary.get("fulfillmentStatus"));

        salesService.deleteDeliveryInfo(latest.id);
        Map<String, Object> afterDeletion = row(overdueItem.id);
        assertEquals(firstDate, afterDeletion.get("lastDeliveryDate"));
        assertEquals(0, decimal("8").compareTo((BigDecimal) afterDeletion.get("unshippedQty")));

        salesService.deleteDeliveryInfo(first.id);
        Map<String, Object> afterAllDeleted = row(overdueItem.id);
        assertNull(afterAllDeleted.get("lastDeliveryDate"));
        assertEquals("逾期未发货", afterAllDeleted.get("fulfillmentStatus"));
    }

    private SalesOrderItem item(Long orderId, String productCode, String qty, LocalDate dueDate) {
        SalesOrderItem item = new SalesOrderItem();
        item.orderId = orderId;
        item.productCode = productCode;
        item.productName = productCode;
        item.unit = "件";
        item.qty = decimal(qty);
        item.shippedQty = BigDecimal.ZERO;
        item.unitPrice = BigDecimal.ONE;
        item.amount = decimal(qty);
        item.dueDate = dueDate;
        return salesOrderItemRepository.save(item);
    }

    private DeliveryNote deliver(Long orderId, Long itemId, String qty, LocalDate date) {
        SalesService.DeliveryRegisterRequest request = new SalesService.DeliveryRegisterRequest();
        request.itemId = itemId;
        request.deliveryQty = decimal(qty);
        request.deliveryDate = date;
        request.deliveryMethod = "物流";
        return salesService.addDelivery(orderId, request);
    }

    private Map<String, Object> row(Long itemId) {
        List<Map<String, Object>> rows = salesService.deliveryInvoiceRows();
        return rows.stream()
                .filter(candidate -> itemId.equals(candidate.get("itemId")))
                .findFirst()
                .orElseThrow();
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
