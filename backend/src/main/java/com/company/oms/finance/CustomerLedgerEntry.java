package com.company.oms.finance;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "customer_ledger_entry")
public class CustomerLedgerEntry extends BaseEntity {
    @Column(unique = true, nullable = false, length = 240)
    public String sourceKey;

    public Long accountId;
    public Integer sourceRow;
    public LocalDate transactionDate;

    @Column(length = 100)
    public String sourceDate;

    @Column(length = 100)
    public String summary;

    @Column(precision=38, scale=18)
    public BigDecimal amount;

    @Column(length = 200)
    public String invoiceNo;

    @Column(length = 2000)
    public String notes;

    @Column(precision=38, scale=18)
    public BigDecimal paidAmount;

    @Column(precision=38, scale=18)
    public BigDecimal remainingAmount;

    public LocalDate lastPaymentDate;

    public Long linkedStatementId;
}
