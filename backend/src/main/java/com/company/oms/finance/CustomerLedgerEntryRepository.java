package com.company.oms.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerLedgerEntryRepository extends JpaRepository<CustomerLedgerEntry, Long> {
    Optional<CustomerLedgerEntry> findBySourceKey(String sourceKey);
    List<CustomerLedgerEntry> findByAccountIdAndDeletedFalse(Long accountId);
    List<CustomerLedgerEntry> findByAccountIdInAndDeletedFalse(List<Long> accountIds);
    List<CustomerLedgerEntry> findByLinkedStatementIdAndDeletedFalse(Long linkedStatementId);
}
