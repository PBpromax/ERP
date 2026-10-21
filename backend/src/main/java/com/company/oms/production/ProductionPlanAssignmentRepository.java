package com.company.oms.production;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProductionPlanAssignmentRepository extends JpaRepository<ProductionPlanAssignment, Long> {
    List<ProductionPlanAssignment> findByPlanIdAndDeletedFalseOrderByCreatedAtAsc(Long planId);

    List<ProductionPlanAssignment> findByWorkerIdAndDeletedFalseOrderByCreatedAtDesc(Long workerId);

    List<ProductionPlanAssignment> findByPlanIdInAndDeletedFalse(List<Long> planIds);

    List<ProductionPlanAssignment> findByWorkerIdAndPlanIdInAndDeletedFalse(Long workerId, List<Long> planIds);

    Optional<ProductionPlanAssignment> findByPlanIdAndWorkerIdAndDeletedFalse(Long planId, Long workerId);
}
