package com.company.oms.finance;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "ar_balance_snapshot")
public class ArBalanceSnapshot extends BaseEntity {
    public Long customerId;

    @Column(length=200)
    public String customerName;

    public LocalDate snapshotDate;

    @Column(precision=38, scale=18)
    public BigDecimal receivableAmount;

    @Column(precision=38, scale=18)
    public BigDecimal receivedAmount;

    @Column(precision=38, scale=18)
    public BigDecimal arrearsAmount;

}
