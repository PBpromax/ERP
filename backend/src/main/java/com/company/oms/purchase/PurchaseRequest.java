package com.company.oms.purchase;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "purchase_request")
public class PurchaseRequest extends BaseEntity {
    @Column(unique=true, length=64)
    public String requestNo;

    public Long materialId;

    @Column(length=200)
    public String materialName;

    @Column(precision=18, scale=3)
    public BigDecimal requestQty;

    @Column(length=200)
    public String defaultSupplier;

    public LocalDate expectedArrivalDate;

    @Column(length=30)
    public String status;

    @Column(length=50)
    public String sourceType;

    @Column(length=64)
    public String sourceNo;

    @Column(length=1000)
    public String remark;

}
