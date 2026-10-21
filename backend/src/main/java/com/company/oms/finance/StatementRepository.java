package com.company.oms.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

public interface StatementRepository extends JpaRepository<Statement, Long> {
    interface CustomerCollectionTotal {
        Long getCustomerId();
        String getCustomerName();
        Long getStatementCount();
        BigDecimal getStatementAmount();
        BigDecimal getInvoicedAmount();
        BigDecimal getInvoicedUnpaidAmount();
        BigDecimal getReceivedAmount();
        BigDecimal getCurrentDebt();
        Long getUnpaidStatementCount();
        LocalDateTime getLatestStatementAt();
    }

    @org.springframework.data.jpa.repository.Query("""
            select s.customerId as customerId,
                   max(s.customerName) as customerName,
                   count(s.id) as statementCount,
                   sum(coalesce(s.receivableAmount, 0)) as statementAmount,
                   sum(coalesce(s.invoicedAmount, 0)) as invoicedAmount,
                   sum(case when coalesce(s.invoicedAmount, 0) > coalesce(s.receivedAmount, 0)
                            then coalesce(s.invoicedAmount, 0) - coalesce(s.receivedAmount, 0)
                            else 0 end) as invoicedUnpaidAmount,
                   sum(coalesce(s.receivedAmount, 0)) as receivedAmount,
                   sum(case when coalesce(s.arrearsAmount, 0) > 0
                            then coalesce(s.arrearsAmount, 0) else 0 end) as currentDebt,
                   sum(case when coalesce(s.arrearsAmount, 0) > 0 then 1 else 0 end) as unpaidStatementCount,
                   max(s.generatedAt) as latestStatementAt
            from Statement s
            where s.deleted = false
              and (s.collectionExcluded = false or s.collectionExcluded is null)
              and s.customerId is not null
            group by s.customerId
            """)
    List<CustomerCollectionTotal> collectionTotalsByCustomer();

    @org.springframework.data.jpa.repository.Query("""
            select s from Statement s
            where s.deleted = false
              and (s.collectionExcluded = false or s.collectionExcluded is null)
              and s.customerId is null
            """)
    List<Statement> findUnmappedCollectionStatements();

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Statement s where s.id = :id and s.deleted = false")
    Optional<Statement> findForReturn(Long id);
    List<Statement> findByMonthAndDeletedFalse(String month);
    Optional<Statement> findByCustomerIdAndMonthAndDeletedFalse(Long customerId, String month);
    List<Statement> findByCustomerIdAndDeletedFalse(Long customerId);
    List<Statement> findByCustomerIdInAndDeletedFalse(List<Long> customerIds);
    List<Statement> findByStatementNoStartingWith(String prefix);
    boolean existsByStatementNo(String statementNo);
    Optional<Statement> findBySourceKeyAndDeletedFalse(String sourceKey);
}
