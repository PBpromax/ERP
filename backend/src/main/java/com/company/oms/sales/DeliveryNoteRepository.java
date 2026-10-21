package com.company.oms.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.time.LocalDate;
import java.util.Optional;

public interface DeliveryNoteRepository extends JpaRepository<DeliveryNote, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeliveryNote d where d.id = :id and d.deleted = false")
    Optional<DeliveryNote> findForReconciliation(@Param("id") Long id);

    List<DeliveryNote> findByStatusAndDeliveryDateBetweenAndDeletedFalse(String status, LocalDate start, LocalDate end);
    List<DeliveryNote> findByOrderIdAndDeletedFalse(Long orderId);
    List<DeliveryNote> findByOrderId(Long orderId);
    List<DeliveryNote> findByDeliveryNoStartingWith(String prefix);
    boolean existsByDeliveryNo(String deliveryNo);
    Optional<DeliveryNote> findBySourceKeyAndDeletedFalse(String sourceKey);
}
