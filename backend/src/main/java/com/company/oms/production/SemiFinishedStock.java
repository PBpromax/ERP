package com.company.oms.production;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "semi_finished_stock")
public class SemiFinishedStock extends BaseEntity {
    @Column(unique=true, length=64)
    public String code;

    @Column(length=200)
    public String name;

    @Column(length=200)
    public String spec;

    @Column(precision=38, scale=18)
    public BigDecimal laborPrice;

    @Column(precision=18, scale=3)
    public BigDecimal stockQty;

    @Transient
    public List<String> aliases = new ArrayList<>();
}
