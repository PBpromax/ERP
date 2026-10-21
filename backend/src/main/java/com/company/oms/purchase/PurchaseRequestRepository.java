package com.company.oms.purchase;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, Long> {
    List<PurchaseRequest> findByRequestNoStartingWith(String prefix);

    boolean existsByRequestNo(String requestNo);
}
