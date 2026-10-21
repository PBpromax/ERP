package com.company.oms.inventory;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "stock_lock")
public class StockLock extends BaseEntity {
    @Column(length=30)
    public String itemType;

    public Long itemId;

    @Column(length=50)
    public String sourceType;

    @Column(length=64)
    public String sourceNo;

    @Column(precision=18, scale=3)
    public BigDecimal qty;

    @Column(length=30)
    public String status;

}
