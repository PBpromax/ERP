package com.company.oms.notify;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NotifyRecordRepository extends JpaRepository<NotifyRecord, Long> {
    List<NotifyRecord> findByStatusAndDeletedFalseOrderByCreatedAtDesc(String status);
}
