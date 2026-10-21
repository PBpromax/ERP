package com.company.oms.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MaterialStockRepository extends JpaRepository<MaterialStock, Long> {
    Optional<MaterialStock> findByMaterialIdAndDeletedFalse(Long materialId);
}
