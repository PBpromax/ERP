package com.company.oms.production;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "production_plan")
public class ProductionPlan extends BaseEntity {
    @Column(unique=true, length=64)
    public String planNo;

    public Long sourceOrderId;

    @Column(length=64)
    public String sourceOrderNo;

    public Long orderItemId;

    public Long productId;

    @Column(length=64)
    public String productCode;

    @Column(length=200)
    public String productName;

    @Column(length=200)
    public String productSpec;

    @Column(precision=18, scale=3)
    public BigDecimal planQty;

    public Long assignedWorkerId;

    @Column(length=100)
    public String assignedWorkerName;

    @Column(precision=18, scale=3)
    public BigDecimal assignedQty;

    @Column(length=30)
    public String status;

    public Integer progress;

    public LocalDate expectedFinishDate;

    public LocalDate startDate;

    public LocalDate finishDate;

    @Column(length=1000)
    public String remark;

    @Transient
    public String assignmentSummary;

    @Transient
    public BigDecimal unassignedQty;

    @Transient
    public BigDecimal completedQty;

}
