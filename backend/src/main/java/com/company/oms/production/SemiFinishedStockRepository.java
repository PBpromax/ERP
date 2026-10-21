package com.company.oms.production;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SemiFinishedStockRepository extends JpaRepository<SemiFinishedStock, Long> {
    List<SemiFinishedStock> findByDeletedFalseOrderByCodeAsc();

    Optional<SemiFinishedStock> findByCodeAndDeletedFalse(String code);
}
