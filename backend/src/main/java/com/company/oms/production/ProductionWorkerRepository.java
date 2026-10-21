package com.company.oms.production;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ProductionWorkerRepository extends JpaRepository<ProductionWorker, Long> {
    Optional<ProductionWorker> findByAccessTokenAndDeletedFalse(String accessToken);
}
