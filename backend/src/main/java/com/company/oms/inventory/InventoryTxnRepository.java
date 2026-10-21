package com.company.oms.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryTxnRepository extends JpaRepository<InventoryTxn, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<InventoryTxn> {
    boolean existsByItemTypeAndItemId(String itemType, Long itemId);
}
