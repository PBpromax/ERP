package com.company.oms.finance;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "statement")
public class Statement extends BaseEntity {
    @Column(unique=true, length=64)
    public String statementNo;

    public Long customerId;

    @Column(length=200)
    public String customerName;

    @Column(name = "statement_month", length=7)
    public String month;

    @Column(precision=38, scale=18)
    public BigDecimal receivableAmount;

    @Column(precision=38, scale=18)
    public BigDecimal invoicedAmount;

    @Column(precision=38, scale=18)
    public BigDecimal receivedAmount;

    @Column(precision=38, scale=18)
    public BigDecimal historicalReceivedAmount;

    @Column(precision=38, scale=18)
    public BigDecimal arrearsAmount;

    @Column(length=30)
    public String status;

    @Column(length=20)
    public String settlementMethod;

    @Column(length=30)
    public String settlementStatus;

    public LocalDateTime registeredAt;

    public LocalDateTime paidAt;

    public LocalDateTime generatedAt;

    @Column(unique = true, length = 200)
    public String sourceKey;

    public Boolean historicalImported;
    public Boolean sourceAmountOnly;
    public Boolean receiptRebuilt;
    public Boolean collectionExcluded;
    public java.time.LocalDate sourceSortDate;
    public Long sourceSequence;
    public String sourceDateText;
    @Column(precision=38, scale=18)
    public BigDecimal sourceInvoicedAmount;
    public Long ledgerAccountId;

    @Column(length = 2000)
    public String sourceRemark;

}
