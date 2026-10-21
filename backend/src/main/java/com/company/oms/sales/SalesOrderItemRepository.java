package com.company.oms.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SalesOrderItemRepository extends JpaRepository<SalesOrderItem, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select i from SalesOrderItem i where i.id = :id and i.deleted = false")
    java.util.Optional<SalesOrderItem> findForReturn(Long id);
    List<SalesOrderItem> findByOrderIdAndDeletedFalse(Long orderId);
    List<SalesOrderItem> findByOrderIdInAndDeletedFalse(List<Long> orderIds);
    List<SalesOrderItem> findByOrderId(Long orderId);
    List<SalesOrderItem> findByDeletedFalse();

    @Query(value = """
            select i from SalesOrderItem i, SalesOrder o
            where o.id = i.orderId
              and (i.deleted = false or i.deleted is null)
              and (o.deleted = false or o.deleted is null)
              and (
                :keyword = ''
                or lower(coalesce(o.orderNo, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(o.customerName, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(i.productCode, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(i.productName, '')) like lower(concat('%', :keyword, '%'))
              )
              and (:unshippedOnly = false or coalesce(i.shippedQty, 0) < coalesce(i.qty, 0))
              and (:overdueOnly = false or (
                coalesce(i.dueDate, o.deliveryDate) < :today
                and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
              ))
              and (
                :fulfillmentStatus = 'all'
                or (locate(',overdue,', concat(',', concat(:fulfillmentStatus, ','))) > 0
                  and coalesce(i.dueDate, o.deliveryDate) < :today
                  and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0))
                or (locate(',未发货,', concat(',', concat(:fulfillmentStatus, ','))) > 0
                  and coalesce(i.shippedQty, 0) <= 0
                  and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
                  and (coalesce(i.dueDate, o.deliveryDate) is null or coalesce(i.dueDate, o.deliveryDate) >= :today))
                or (locate(',部分发货,', concat(',', concat(:fulfillmentStatus, ','))) > 0
                  and coalesce(i.shippedQty, 0) > 0
                  and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
                  and (coalesce(i.dueDate, o.deliveryDate) is null or coalesce(i.dueDate, o.deliveryDate) >= :today))
                or (locate(',已发送,', concat(',', concat(:fulfillmentStatus, ','))) > 0 and coalesce(i.shippedQty, 0) >= coalesce(i.qty, 0))
                or (locate(',逾期未发货,', concat(',', concat(:fulfillmentStatus, ','))) > 0
                  and coalesce(i.shippedQty, 0) <= 0
                  and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
                  and coalesce(i.dueDate, o.deliveryDate) < :today)
                or (locate(',逾期部分发货,', concat(',', concat(:fulfillmentStatus, ','))) > 0
                  and coalesce(i.shippedQty, 0) > 0
                  and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
                  and coalesce(i.dueDate, o.deliveryDate) < :today)
              )
            order by o.orderNo desc, o.id desc, i.createdAt desc, i.id desc
            """,
            countQuery = """
            select count(i) from SalesOrderItem i, SalesOrder o
            where o.id = i.orderId
              and (i.deleted = false or i.deleted is null)
              and (o.deleted = false or o.deleted is null)
              and (
                :keyword = ''
                or lower(coalesce(o.orderNo, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(o.customerName, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(i.productCode, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(i.productName, '')) like lower(concat('%', :keyword, '%'))
              )
              and (:unshippedOnly = false or coalesce(i.shippedQty, 0) < coalesce(i.qty, 0))
              and (:overdueOnly = false or (
                coalesce(i.dueDate, o.deliveryDate) < :today
                and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
              ))
              and (
                :fulfillmentStatus = 'all'
                or (locate(',overdue,', concat(',', concat(:fulfillmentStatus, ','))) > 0
                  and coalesce(i.dueDate, o.deliveryDate) < :today
                  and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0))
                or (locate(',未发货,', concat(',', concat(:fulfillmentStatus, ','))) > 0
                  and coalesce(i.shippedQty, 0) <= 0
                  and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
                  and (coalesce(i.dueDate, o.deliveryDate) is null or coalesce(i.dueDate, o.deliveryDate) >= :today))
                or (locate(',部分发货,', concat(',', concat(:fulfillmentStatus, ','))) > 0
                  and coalesce(i.shippedQty, 0) > 0
                  and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
                  and (coalesce(i.dueDate, o.deliveryDate) is null or coalesce(i.dueDate, o.deliveryDate) >= :today))
                or (locate(',已发送,', concat(',', concat(:fulfillmentStatus, ','))) > 0 and coalesce(i.shippedQty, 0) >= coalesce(i.qty, 0))
                or (locate(',逾期未发货,', concat(',', concat(:fulfillmentStatus, ','))) > 0
                  and coalesce(i.shippedQty, 0) <= 0
                  and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
                  and coalesce(i.dueDate, o.deliveryDate) < :today)
                or (locate(',逾期部分发货,', concat(',', concat(:fulfillmentStatus, ','))) > 0
                  and coalesce(i.shippedQty, 0) > 0
                  and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
                  and coalesce(i.dueDate, o.deliveryDate) < :today)
              )
            """)
    Page<SalesOrderItem> searchDeliveryBoard(@Param("keyword") String keyword,
                                             @Param("unshippedOnly") boolean unshippedOnly,
                                             @Param("overdueOnly") boolean overdueOnly,
                                             @Param("fulfillmentStatus") String fulfillmentStatus,
                                             @Param("today") LocalDate today,
                                             Pageable pageable);

    @Query("""
            select i from SalesOrderItem i, SalesOrder o
            where o.id = i.orderId
              and o.customerId = :customerId
              and (i.deleted = false or i.deleted is null)
              and (o.deleted = false or o.deleted is null)
              and coalesce(i.shippedQty, 0) < coalesce(i.qty, 0)
            order by o.orderNo desc, o.id desc, i.createdAt desc, i.id desc
            """)
    Page<SalesOrderItem> findAvailableForCustomer(@Param("customerId") Long customerId, Pageable pageable);

    boolean existsByProductIdAndDeletedFalse(Long productId);
}
