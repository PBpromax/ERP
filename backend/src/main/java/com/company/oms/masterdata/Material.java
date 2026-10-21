package com.company.oms.masterdata;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "material")
public class Material extends BaseEntity {
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(unique = true, length = 64)
    public String scanToken;
    @Column(length=30)
    public String category;

    @Column(length=1000)
    public String remark;

    @Version
    public Long versionNo;

    @Column(unique=true, length=64)
    public String code;

    @Column(nullable=false, length=200)
    public String name;

    @Column(length=200)
    public String spec;

    @Column(length=30)
    public String unit;

    @Column(precision=18, scale=3)
    public BigDecimal safetyStock;

    @Column(length=200)
    public String defaultSupplier;

    @Column(length=200)
    public String supplier;

    @Column(length=64)
    public String supplierCode;

    @Column(precision=38, scale=18)
    public BigDecimal lastPurchasePrice;

    @Column(precision=18, scale=3)
    public BigDecimal minPurchaseQty;

    public Boolean enabled;

}
