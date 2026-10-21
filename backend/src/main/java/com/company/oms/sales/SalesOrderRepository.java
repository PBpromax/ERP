package com.company.oms.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
    interface CustomerSalesTotal {
        Long getCustomerId();
        BigDecimal getTotalAmount();
    }

    @Query("select o.customerId as customerId, sum(o.totalAmount) as totalAmount from SalesOrder o "
            + "where o.deleted = false and o.customerId is not null "
            + "and o.orderDate >= :startDate and o.orderDate < :endDate group by o.customerId")
    List<CustomerSalesTotal> sumActiveOrderAmountsByCustomerAndDateRange(
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    @Query("select sum(o.totalAmount) from SalesOrder o where o.deleted = false and o.customerId = :customerId "
            + "and o.orderDate >= :startDate and o.orderDate < :endDate")
    BigDecimal sumActiveOrderAmountByCustomerIdAndDateRange(@Param("customerId") Long customerId,
            @Param("startDate") LocalDate startDate, @Param("endDate") LocalDate endDate);

    List<SalesOrder> findByDeletedFalseOrderByOrderNoDescIdDesc();

    Page<SalesOrder> findByDeletedFalseOrderByOrderNoDescIdDesc(Pageable pageable);

    @Query(value = """
            select o from SalesOrder o
            where (o.deleted = false or o.deleted is null)
              and (:filterByYear = false or (o.orderDate >= :startDate and o.orderDate < :endDate))
              and (
                :keyword = ''
                or lower(coalesce(o.orderNo, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(o.sourceOrderNo, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(o.customerName, '')) like lower(concat('%', :keyword, '%'))
                or exists (
                  select i.id from SalesOrderItem i
                  where i.orderId = o.id and (i.deleted = false or i.deleted is null)
                    and (
                      lower(coalesce(i.productCode, '')) like lower(concat('%', :keyword, '%'))
                      or lower(coalesce(i.productName, '')) like lower(concat('%', :keyword, '%'))
                    )
                )
              )
              and (
                :deliveryStatus = 'all'
                or (locate(',unfulfilled,', concat(',', concat(:deliveryStatus, ','))) > 0 and exists (
                  select pending.id from SalesOrderItem pending
                  where pending.orderId = o.id and (pending.deleted = false or pending.deleted is null)
                    and coalesce(pending.shippedQty, 0) < coalesce(pending.qty, 0)
                ))
                or (locate(',未发货,', concat(',', concat(:deliveryStatus, ','))) > 0
                  and exists (select item.id from SalesOrderItem item where item.orderId = o.id and (item.deleted = false or item.deleted is null))
                  and not exists (
                    select shipped.id from SalesOrderItem shipped
                    where shipped.orderId = o.id and (shipped.deleted = false or shipped.deleted is null)
                      and coalesce(shipped.shippedQty, 0) > 0
                  )
                  and exists (
                    select pending.id from SalesOrderItem pending
                    where pending.orderId = o.id and (pending.deleted = false or pending.deleted is null)
                      and coalesce(pending.shippedQty, 0) < coalesce(pending.qty, 0)
                  ))
                or (locate(',部分发货,', concat(',', concat(:deliveryStatus, ','))) > 0
                  and exists (
                    select shipped.id from SalesOrderItem shipped
                    where shipped.orderId = o.id and (shipped.deleted = false or shipped.deleted is null)
                      and coalesce(shipped.shippedQty, 0) > 0
                  )
                  and exists (
                    select pending.id from SalesOrderItem pending
                    where pending.orderId = o.id and (pending.deleted = false or pending.deleted is null)
                      and coalesce(pending.shippedQty, 0) < coalesce(pending.qty, 0)
                  ))
                or (locate(',已送,', concat(',', concat(:deliveryStatus, ','))) > 0
                  and exists (select item.id from SalesOrderItem item where item.orderId = o.id and (item.deleted = false or item.deleted is null))
                  and not exists (
                    select pending.id from SalesOrderItem pending
                    where pending.orderId = o.id and (pending.deleted = false or pending.deleted is null)
                      and coalesce(pending.shippedQty, 0) < coalesce(pending.qty, 0)
                  ))
              )
            order by o.orderNo desc, o.id desc
            """,
            countQuery = """
            select count(o) from SalesOrder o
            where (o.deleted = false or o.deleted is null)
              and (:filterByYear = false or (o.orderDate >= :startDate and o.orderDate < :endDate))
              and (
                :keyword = ''
                or lower(coalesce(o.orderNo, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(o.sourceOrderNo, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(o.customerName, '')) like lower(concat('%', :keyword, '%'))
                or exists (
                  select i.id from SalesOrderItem i
                  where i.orderId = o.id and (i.deleted = false or i.deleted is null)
                    and (
                      lower(coalesce(i.productCode, '')) like lower(concat('%', :keyword, '%'))
                      or lower(coalesce(i.productName, '')) like lower(concat('%', :keyword, '%'))
                    )
                )
              )
              and (
                :deliveryStatus = 'all'
                or (locate(',unfulfilled,', concat(',', concat(:deliveryStatus, ','))) > 0 and exists (
                  select pending.id from SalesOrderItem pending
                  where pending.orderId = o.id and (pending.deleted = false or pending.deleted is null)
                    and coalesce(pending.shippedQty, 0) < coalesce(pending.qty, 0)
                ))
                or (locate(',未发货,', concat(',', concat(:deliveryStatus, ','))) > 0
                  and exists (select item.id from SalesOrderItem item where item.orderId = o.id and (item.deleted = false or item.deleted is null))
                  and not exists (
                    select shipped.id from SalesOrderItem shipped
                    where shipped.orderId = o.id and (shipped.deleted = false or shipped.deleted is null)
                      and coalesce(shipped.shippedQty, 0) > 0
                  )
                  and exists (
                    select pending.id from SalesOrderItem pending
                    where pending.orderId = o.id and (pending.deleted = false or pending.deleted is null)
                      and coalesce(pending.shippedQty, 0) < coalesce(pending.qty, 0)
                  ))
                or (locate(',部分发货,', concat(',', concat(:deliveryStatus, ','))) > 0
                  and exists (
                    select shipped.id from SalesOrderItem shipped
                    where shipped.orderId = o.id and (shipped.deleted = false or shipped.deleted is null)
                      and coalesce(shipped.shippedQty, 0) > 0
                  )
                  and exists (
                    select pending.id from SalesOrderItem pending
                    where pending.orderId = o.id and (pending.deleted = false or pending.deleted is null)
                      and coalesce(pending.shippedQty, 0) < coalesce(pending.qty, 0)
                  ))
                or (locate(',已送,', concat(',', concat(:deliveryStatus, ','))) > 0
                  and exists (select item.id from SalesOrderItem item where item.orderId = o.id and (item.deleted = false or item.deleted is null))
                  and not exists (
                    select pending.id from SalesOrderItem pending
                    where pending.orderId = o.id and (pending.deleted = false or pending.deleted is null)
                      and coalesce(pending.shippedQty, 0) < coalesce(pending.qty, 0)
                  ))
              )
            """)
    Page<SalesOrder> searchActiveOrders(@Param("keyword") String keyword,
                                        @Param("deliveryStatus") String deliveryStatus,
                                        @Param("filterByYear") boolean filterByYear,
                                        @Param("startDate") LocalDate startDate,
                                        @Param("endDate") LocalDate endDate,
                                        Pageable pageable);

    List<SalesOrder> findByOrderNoStartingWith(String prefix);

    boolean existsByOrderNo(String orderNo);

    boolean existsByOrderNoAndIdNot(String orderNo, Long id);
}
