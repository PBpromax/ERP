package com.company.oms.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MaterialDailyRecordRepository extends JpaRepository<MaterialDailyRecord, Long>, JpaSpecificationExecutor<MaterialDailyRecord> {
    Optional<MaterialDailyRecord> findByMaterialIdAndBusinessDate(Long materialId, LocalDate businessDate);
    List<MaterialDailyRecord> findByBusinessDate(LocalDate businessDate);
    List<MaterialDailyRecord> findByMaterialIdAndBusinessDateBetweenOrderByBusinessDateAsc(Long id, LocalDate start, LocalDate end);
    boolean existsByMaterialId(Long id);
}
