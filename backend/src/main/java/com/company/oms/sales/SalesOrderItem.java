package com.company.oms.sales;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "sales_order_item")
public class SalesOrderItem extends BaseEntity {
    public Long orderId;

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

    @Column(precision=18, scale=3)
    public BigDecimal shippedQty;

    @Column(precision=38, scale=18)
    public BigDecimal unitPrice;

    @Column(precision=18, scale=4)
    public BigDecimal taxRate;

    @Column(precision=38, scale=18)
    public BigDecimal amount;

    public LocalDate dueDate;

}
