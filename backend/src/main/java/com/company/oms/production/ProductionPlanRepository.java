package com.company.oms.production;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ProductionPlanRepository extends JpaRepository<ProductionPlan, Long> {
    List<ProductionPlan> findBySourceOrderIdAndDeletedFalse(Long sourceOrderId);

    List<ProductionPlan> findBySourceOrderIdAndOrderItemIdAndDeletedFalse(Long sourceOrderId, Long orderItemId);

    List<ProductionPlan> findByAssignedWorkerIdAndDeletedFalseOrderByCreatedAtDesc(Long assignedWorkerId);

    List<ProductionPlan> findByPlanNoStartingWith(String prefix);

    boolean existsByPlanNo(String planNo);

    boolean existsByProductIdAndDeletedFalse(Long productId);
}
