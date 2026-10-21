package com.company.oms.production;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WorkerProductionAllocationRepository extends JpaRepository<WorkerProductionAllocation, Long> {
    List<WorkerProductionAllocation> findByRecordIdAndDeletedFalseOrderByCreatedAtAsc(Long recordId);

    List<WorkerProductionAllocation> findByRecordIdInAndDeletedFalse(List<Long> recordIds);

    List<WorkerProductionAllocation> findByAssignmentIdInAndDeletedFalse(List<Long> assignmentIds);

    List<WorkerProductionAllocation> findByAssignmentIdAndDeletedFalse(Long assignmentId);

    void deleteByAssignmentId(Long assignmentId);
}
