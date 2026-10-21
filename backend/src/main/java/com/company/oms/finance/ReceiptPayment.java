package com.company.oms.finance;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One actual payment; Receipt rows are allocations, never additional income. */
@Entity
@Table(name = "receipt_payment")
public class ReceiptPayment extends BaseEntity {
    @Column(nullable = false, unique = true, length = 100)
    public String paymentNo;
    @Column(nullable = false, length = 200)
    public String groupKey;
    public Long customerId;
    public Long statementId;
    public LocalDate paymentDate;
    public LocalDate sourceSortDate;
    public Long sourceSequence;
    public String sourceDateText;
    @Column(precision=38, scale=18)
    public BigDecimal amount;
    @Column(precision=38, scale=18)
    public BigDecimal unallocatedAmount;
    public String method;
    @Column(length = 2000)
    public String remark;
    @Column(unique = true, length = 240)
    public String sourceKey;
    @Column(length = 64)
    public String sourceHash;
}
