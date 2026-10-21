package com.company.oms.production;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "production_plan_assignment")
public class ProductionPlanAssignment extends BaseEntity {
    public Long planId;

    public Long workerId;

    @Column(length=100)
    public String workerName;

    @Column(precision=18, scale=3)
    public BigDecimal qty;
}
