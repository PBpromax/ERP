package com.company.oms.sales;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "delivery_note")
public class DeliveryNote extends BaseEntity {
    @Column(unique=true, length=64)
    public String deliveryNo;

    public Integer deliverySeq;

    public Long orderId;

    @Column(length=64)
    public String orderNo;

    public Long customerId;

    @Column(length=200)
    public String customerName;

    @Column(length=30)
    public String status;

    public LocalDate deliveryDate;

    @Column(precision=38, scale=18)
    public BigDecimal totalAmount;

    public Boolean printPrice;

    @Column(length=500)
    public String qrCodeContent;

    @Column(length=100)
    public String confirmer;

    public LocalDateTime confirmTime;

    @Column(length=1000)
    public String remark;

    @Column(precision=18, scale=3)
    public BigDecimal deliveryQty;

    @Column(length=100)
    public String deliveryMethod;

    @Column(length=100)
    public String logisticsNo;

    @Column(length=1000)
    public String deliveryInfo;

    public Boolean historicalImported;

    public Boolean inventoryPosted;

    @Column(length=4000)
    public String sourceComment;

    @Column(unique = true, length=160)
    public String sourceKey;

    public Boolean invoiced;

    @Column(length=100)
    public String invoiceNo;

    public LocalDate invoiceDate;

    @Column(precision=38, scale=18)
    public BigDecimal invoiceAmount;

    public Long statementId;

    @Column(length=64)
    public String statementNo;

    public Boolean reconciliationExcluded;

    @Column(length=500)
    public String reconciliationExclusionReason;

}
