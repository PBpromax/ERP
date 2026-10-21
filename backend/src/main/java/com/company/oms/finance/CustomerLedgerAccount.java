package com.company.oms.finance;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "customer_ledger_account")
public class CustomerLedgerAccount extends BaseEntity {
    public Long customerId;

    @Column(unique = true, nullable = false, length = 200)
    public String sourceKey;

    @Column(unique = true, nullable = false, length = 200)
    public String sheetName;

    @Column(precision=38, scale=18)
    public BigDecimal statementAmount;

    @Column(precision=38, scale=18)
    public BigDecimal currentDebt;

    public Integer entryCount;
    public Integer openEntryCount;

    @Column(precision=38, scale=18)
    public BigDecimal creditBalance;

    public LocalDate invoiceCutoff;
    public LocalDateTime importedAt;
    @Column(length = 2000)
    public String reconciliationNote;
    @Column(precision=38, scale=18)
    public BigDecimal sourceNetAmount;
}
