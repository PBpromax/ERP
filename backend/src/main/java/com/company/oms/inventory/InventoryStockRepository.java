package com.company.oms.inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface InventoryStockRepository extends JpaRepository<InventoryStock, Long> {
    Optional<InventoryStock> findByProductIdAndDeletedFalse(Long productId);

    boolean existsByProductIdAndDeletedFalse(Long productId);
}
