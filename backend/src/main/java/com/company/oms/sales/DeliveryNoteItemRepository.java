package com.company.oms.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DeliveryNoteItemRepository extends JpaRepository<DeliveryNoteItem, Long> {
    interface OrderItemLastDeliveryDate {
        Long getOrderItemId();
        LocalDate getLastDeliveryDate();
    }

    List<DeliveryNoteItem> findByDeliveryIdAndDeletedFalse(Long deliveryId);
    List<DeliveryNoteItem> findByDeliveryIdInAndDeletedFalse(List<Long> deliveryIds);
    List<DeliveryNoteItem> findByDeliveryId(Long deliveryId);
    List<DeliveryNoteItem> findByOrderItemIdAndDeletedFalse(Long orderItemId);
    List<DeliveryNoteItem> findByOrderItemIdInAndDeletedFalse(List<Long> orderItemIds);
    List<DeliveryNoteItem> findByOrderItemId(Long orderItemId);

    @Query("""
            select i.orderItemId as orderItemId, max(d.deliveryDate) as lastDeliveryDate
            from DeliveryNoteItem i, DeliveryNote d
            where d.id = i.deliveryId
              and i.orderItemId in :orderItemIds
              and (i.deleted = false or i.deleted is null)
              and (d.deleted = false or d.deleted is null)
              and d.status = '已发货'
              and d.deliveryDate is not null
            group by i.orderItemId
            """)
    List<OrderItemLastDeliveryDate> findLastShippedDatesByOrderItemIdIn(
            @Param("orderItemIds") List<Long> orderItemIds);
}
