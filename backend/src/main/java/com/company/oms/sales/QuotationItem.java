package com.company.oms.sales;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "quotation_item")
public class QuotationItem extends BaseEntity {
    public Long quotationId;

    public Long productId;

    @Column(length=64)
    public String productCode;

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

    @Column(precision=18, scale=4)
    public BigDecimal taxRate;

    @Column(precision=38, scale=18)
    public BigDecimal amount;

}
