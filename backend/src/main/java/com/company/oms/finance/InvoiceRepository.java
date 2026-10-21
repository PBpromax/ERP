package com.company.oms.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByStatementIdAndDeletedFalse(Long statementId);
    List<Invoice> findByStatementIdInAndDeletedFalse(List<Long> statementIds);
}
