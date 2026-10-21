package com.company.oms.quality;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "quality_report")
public class QualityReport extends BaseEntity {
    @Column(unique=true, length=64)
    public String reportNo;

    @Column(length=50)
    public String businessType;

    @Column(length=64)
    public String businessNo;

    @Column(length=64)
    public String orderNo;

    public Long productId;

    @Column(length=200)
    public String productName;

    @Column(length=100)
    public String batchNo;

    public LocalDate inspectionDate;

    @Column(length=100)
    public String inspector;

    @Column(length=50)
    public String conclusion;

    @Column(length=1000)
    public String remark;

}
