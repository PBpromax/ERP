package com.company.oms.inventory;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "inventory_txn")
public class InventoryTxn extends BaseEntity {
    public java.time.LocalDate businessDate;

    @Column(length=30)
    public String materialMovementType;

    public Long dailyRecordId;

    @Column(length=30)
    public String itemType;

    public Long itemId;

    @Column(length=64)
    public String itemCode;

    @Column(length=200)
    public String itemName;

    @Column(length=50)
    public String txnType;

    @Column(precision=18, scale=3)
    public BigDecimal qty;

    @Column(precision=18, scale=3)
    public BigDecimal beforeQty;

    @Column(precision=18, scale=3)
    public BigDecimal afterQty;

    @Column(length=50)
    public String sourceType;

    @Column(length=64)
    public String sourceNo;

    @Column(length=1000)
    public String remark;

    @Column(length=100)
    public String operator;

    public Long operatorUserId;

    @Column(length=64)
    public String operatorUsername;

}
