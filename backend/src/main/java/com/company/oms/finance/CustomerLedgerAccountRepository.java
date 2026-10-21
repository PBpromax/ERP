package com.company.oms.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerLedgerAccountRepository extends JpaRepository<CustomerLedgerAccount, Long> {
    Optional<CustomerLedgerAccount> findBySourceKey(String sourceKey);
    List<CustomerLedgerAccount> findByDeletedFalse();
    List<CustomerLedgerAccount> findByCustomerIdAndDeletedFalse(Long customerId);
    long countByDeletedFalse();
}
