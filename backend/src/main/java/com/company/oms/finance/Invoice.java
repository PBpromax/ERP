package com.company.oms.finance;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "invoice")
public class Invoice extends BaseEntity {
    @Column(length=64)
    public String invoiceNo;

    public Long statementId;

    @Transient
    public String statementNo;

    public Long orderId;

    @Column(length=64)
    public String orderNo;

    public Long customerId;

    @Column(length=200)
    public String customerName;

    public LocalDate invoiceDate;

    @Column(precision=38, scale=18)
    public BigDecimal amount;

    @Column(precision=18, scale=4)
    public BigDecimal taxRate;

    @Column(length=30)
    public String status;

    @Column(length=1000)
    public String remark;

}
