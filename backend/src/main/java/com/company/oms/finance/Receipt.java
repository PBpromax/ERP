package com.company.oms.finance;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "receipt")
public class Receipt extends BaseEntity {
    public Long paymentId;
    public LocalDate allocationDate;
    @jakarta.persistence.Transient
    public String paymentNo;
    @Column(unique=true, length=64)
    public String receiptNo;

    public Long statementId;

    public Long invoiceId;

    public Long orderId;

    @Column(length=64)
    public String orderNo;

    public Long customerId;

    @Column(length=200)
    public String customerName;

    public LocalDate receiptDate;

    @Column(precision=38, scale=18)
    public BigDecimal amount;

    @Column(length=50)
    public String method;

    @Column(length=100)
    public String bankSerial;

    @Column(length=1000)
    public String remark;

    @Column(length=80)
    public String paymentBatchNo;

    public Long ledgerAccountId;

    public Long ledgerEntryId;

    public LocalDate ledgerPreviousPaymentDate;

    public Boolean customerLevelPayment;

}
