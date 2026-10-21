package com.company.oms.finance;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "statement_item")
public class StatementItem extends BaseEntity {
    public Long statementId;

    public Long deliveryId;

    public Long deliveryItemId;

    public Long returnId;

    @Column(length=64)
    public String deliveryNo;

    @Column(length=64)
    public String orderNo;

    @Column(length=200)
    public String productName;

    @Column(length=200)
    public String spec;

    @Column(length=30)
    public String unit;

    @Column(precision=18, scale=3)
    public BigDecimal qty;

    @Column(precision=38, scale=18)
    public BigDecimal unitPrice;

    @Column(precision=38, scale=18)
    public BigDecimal amount;

    public LocalDate deliveryDate;

    @Column(length=20)
    public String adjustmentType;

}
