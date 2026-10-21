package com.company.oms.sales;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "sales_return")
public class SalesReturn extends BaseEntity {
    public Long orderId;
    public Long orderItemId;
    public Long customerId;
    @Column(length = 200) public String customerName;
    @Column(length = 64) public String orderNo;
    @Column(length = 64) public String productCode;
    @Column(length = 200) public String productName;
    @Column(length = 200) public String spec;
    @Column(length = 30) public String unit;
    public LocalDate returnDate;
    @Column(precision = 18, scale = 3) public BigDecimal qty;
    @Column(precision=38, scale=18) public BigDecimal unitPrice;
    @Column(precision=38, scale=18) public BigDecimal amount;
    @Column(length = 500) public String remark;
}
