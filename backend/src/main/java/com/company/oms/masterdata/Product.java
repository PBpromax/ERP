package com.company.oms.masterdata;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "product")
public class Product extends BaseEntity {
    @Column(unique=true, length=64)
    public String code;

    @Column(nullable=false, length=200)
    public String name;

    @Column(length=20)
    public String categoryCode;

    @Column(length=100)
    public String categoryName;

    @Column(length=200)
    public String spec;

    @Column(length=30)
    public String unit;

    @Column(precision=38, scale=18)
    public BigDecimal defaultPrice;

    @Column(precision=38, scale=18)
    public BigDecimal costPrice;

    @Column(precision=18, scale=3)
    public BigDecimal safetyStock;

    public Boolean enabled;

    @Column(length=20)
    public String status;

    @Column(length=1000)
    public String remark;

    @Convert(converter = StringListJsonConverter.class)
    @Column(columnDefinition = "TEXT")
    public List<String> aliases = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    public String tagValues;

}
