package com.company.oms.sales;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "sales_order")
public class SalesOrder extends BaseEntity {
    @Column(unique=true, length=64)
    public String orderNo;

    @Column(length=64)
    public String sourceOrderNo;

    @Column(length=64)
    public String sourceQuoteNo;

    public Long customerId;

    @Column(length=200)
    public String customerName;

    @Column(length=500)
    public String customerAddress;

    @Column(length=50)
    public String customerPhone;

    @Column(length=100)
    public String customerEmail;

    @Column(length=100)
    public String contactPerson;

    @Column(length=30)
    public String status;

    public LocalDate orderDate;

    public LocalDate deliveryDate;

    public LocalDate actualDeliveryDate;

    @Column(precision=38, scale=18)
    public BigDecimal totalAmount;

    @Column(precision=38, scale=18)
    public BigDecimal taxAmount;

    @Column(precision=38, scale=18)
    public BigDecimal paidAmount;

    @Column(length=1000)
    public String remark;

    @Column(length=100)
    public String auditor;

    public LocalDateTime auditTime;

}
