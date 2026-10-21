package com.company.oms.production;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "worker_production_record")
public class WorkerProductionRecord extends BaseEntity {
    public Long workerId;

    @Column(length=100)
    public String workerName;

    public LocalDate productionDate;

    public Long semiFinishedStockId;

    @Column(length=64)
    public String semiFinishedCode;

    @Column(precision=18, scale=3)
    public BigDecimal qty;

    @Column(precision=18, scale=3)
    public BigDecimal qualifiedQty;

    public Boolean audited;

    @Column(precision=38, scale=18)
    public BigDecimal laborPrice;

    @Column(precision=38, scale=18)
    public BigDecimal amount;

    @Transient
    public BigDecimal allocatedQty;

    @Transient
    public Boolean fullyAllocated;
}
