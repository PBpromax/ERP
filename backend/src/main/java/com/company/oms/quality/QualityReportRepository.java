package com.company.oms.quality;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface QualityReportRepository extends JpaRepository<QualityReport, Long> {
    boolean existsByProductIdAndDeletedFalse(Long productId);

    List<QualityReport> findByReportNoStartingWith(String prefix);

    boolean existsByReportNo(String reportNo);
}
