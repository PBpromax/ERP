package com.company.oms.production;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "worker_production_allocation")
public class WorkerProductionAllocation extends BaseEntity {
    public Long recordId;

    public Long workerId;

    public Long planId;

    public Long assignmentId;

    @Column(length=64)
    public String planNo;

    @Column(length=64)
    public String sourceOrderNo;

    @Column(length=64)
    public String productCode;

    @Column(precision=18, scale=3)
    public BigDecimal qty;
}
