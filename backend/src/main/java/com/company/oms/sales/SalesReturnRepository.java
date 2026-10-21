package com.company.oms.sales;

import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface SalesReturnRepository extends JpaRepository<SalesReturn, Long> {
    List<SalesReturn> findByDeletedFalse();
    List<SalesReturn> findByOrderItemIdAndDeletedFalse(Long orderItemId);
    List<SalesReturn> findByOrderItemIdInAndDeletedFalse(Collection<Long> orderItemIds);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SalesReturn r where r.id = :id and r.deleted = false")
    Optional<SalesReturn> findForReconciliation(Long id);
}
