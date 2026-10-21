package com.company.oms.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.time.LocalDate;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    List<Receipt> findByPaymentIdIsNullAndDeletedFalse();
    List<Receipt> findByPaymentIdIsNullAndDeletedFalseAndReceiptDateGreaterThanEqualAndReceiptDateLessThan(
            LocalDate start, LocalDate end);
    List<Receipt> findByPaymentIdAndDeletedFalse(Long paymentId);
    List<Receipt> findByStatementIdAndDeletedFalse(Long statementId);
    List<Receipt> findByStatementIdInAndDeletedFalse(List<Long> statementIds);

    List<Receipt> findByLedgerAccountIdAndCustomerLevelPaymentTrueAndDeletedFalse(Long ledgerAccountId);

    List<Receipt> findByLedgerAccountIdAndPaymentBatchNoAndCustomerLevelPaymentTrueAndDeletedFalse(Long ledgerAccountId, String paymentBatchNo);

    List<Receipt> findByCustomerLevelPaymentTrueAndDeletedFalse();

    List<Receipt> findByPaymentBatchNoAndCustomerLevelPaymentTrueAndDeletedFalse(String paymentBatchNo);

    List<Receipt> findByReceiptNoStartingWith(String prefix);

    boolean existsByReceiptNo(String receiptNo);
}
