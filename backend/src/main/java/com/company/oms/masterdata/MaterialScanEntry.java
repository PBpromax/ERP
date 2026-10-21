package com.company.oms.masterdata;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "material_scan_entry", uniqueConstraints = @UniqueConstraint(columnNames = {"materialId", "requestId"}))
public class MaterialScanEntry extends BaseEntity {
    @Column(nullable = false) public Long materialId;
    @Column(nullable = false, length = 36) public String requestId;
    @Column(nullable = false, length = 3) public String direction;
    @Column(nullable = false, precision = 18, scale = 3) public BigDecimal qty;
    @Column(nullable = false, precision = 18, scale = 3) public BigDecimal afterQty;
    @Column(nullable = false) public LocalDate businessDate;
    public Long operatorUserId;
    @Column(length = 100) public String operatorName;
}
