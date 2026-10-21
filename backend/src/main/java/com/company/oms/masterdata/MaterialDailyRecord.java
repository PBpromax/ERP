package com.company.oms.masterdata;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "material_daily_record", uniqueConstraints = @UniqueConstraint(columnNames = {"materialId", "businessDate"}))
public class MaterialDailyRecord extends BaseEntity {
    @Column(nullable = false) public Long materialId;
    @Column(nullable = false) public LocalDate businessDate;
    @Column(nullable = false, precision = 18, scale = 3) public BigDecimal purchaseQty;
    @Column(nullable = false, precision = 18, scale = 3) public BigDecimal productionQty;
    @Column(nullable = false, precision = 18, scale = 3) public BigDecimal lossQty;
    @Column(length = 1000) public String remark;
    @Version public Long versionNo;
}
