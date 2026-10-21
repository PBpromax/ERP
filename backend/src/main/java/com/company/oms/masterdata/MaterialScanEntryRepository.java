package com.company.oms.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MaterialScanEntryRepository extends JpaRepository<MaterialScanEntry, Long> {
    Optional<MaterialScanEntry> findByMaterialIdAndRequestId(Long materialId, String requestId);
}
