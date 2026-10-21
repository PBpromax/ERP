package com.company.oms.production;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WorkerProductionRecordRepository extends JpaRepository<WorkerProductionRecord, Long> {
    List<WorkerProductionRecord> findByWorkerIdAndDeletedFalseOrderByProductionDateDescCreatedAtDesc(Long workerId);

    List<WorkerProductionRecord> findByWorkerIdInAndSemiFinishedCodeAndDeletedFalse(List<Long> workerIds, String semiFinishedCode);
}
