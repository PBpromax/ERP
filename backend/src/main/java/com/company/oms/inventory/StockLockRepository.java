package com.company.oms.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockLockRepository extends JpaRepository<StockLock, Long> {}
