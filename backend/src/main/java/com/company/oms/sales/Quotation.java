package com.company.oms.sales;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "quotation")
public class Quotation extends BaseEntity {
    @Column(unique=true, length=64)
    public String quoteNo;

    public Long customerId;

    @Column(length=200)
    public String customerName;

    @Column(length=500)
    public String customerAddress;

    @Column(length=50)
    public String customerPhone;

    @Column(length=100)
    public String customerEmail;

    @Column(length=100)
    public String contactPerson;

    @Column(length=30)
    public String status;

    public LocalDate quoteDate;

    public LocalDate validUntil;

    @Column(precision=18, scale=4)
    public BigDecimal taxRate;

    @Column(precision=38, scale=18)
    public BigDecimal totalAmount;

    @Column(length=1000)
    public String remark;

    public Integer versionNo;

    @Column(length=64)
    public String convertedOrderNo;

    @Column(unique = true, length = 160)
    public String sourceKey;

    public Boolean historicalImported;

    @Transient
    public String productNames;

    @Transient
    public String productCodes;

    @Transient
    public List<Map<String, Object>> productItems;

}
