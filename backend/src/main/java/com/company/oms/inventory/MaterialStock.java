package com.company.oms.inventory;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "material_stock")
public class MaterialStock extends BaseEntity {
    @Column(unique=true)
    public Long materialId;

    @Column(length=64)
    public String materialCode;

    @Column(length=200)
    public String materialName;

    @Column(precision=18, scale=3)
    public BigDecimal currentQty;

    @Column(precision=18, scale=3)
    public BigDecimal lockedQty;

    @Column(precision=18, scale=3)
    public BigDecimal safetyStock;

    @Column(precision=38, scale=18)
    public BigDecimal stockAmount;

    @Version
    public Long versionNo;

}
