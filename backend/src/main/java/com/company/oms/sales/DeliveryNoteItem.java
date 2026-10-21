package com.company.oms.sales;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "delivery_note_item")
public class DeliveryNoteItem extends BaseEntity {
    public Long deliveryId;

    public Long orderItemId;

    public Long productId;

    @Column(length=200)
    public String productName;

    @Column(length=200)
    public String spec;

    @Column(length=30)
    public String unit;

    @Column(precision=18, scale=3)
    public BigDecimal qty;

    // Quantity already reconciled in the source ledger; does not create new receivables.
    @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    @Column(precision=18, scale=3)
    public BigDecimal historicalReconciledQty;

    @Column(precision=38, scale=18)
    public BigDecimal unitPrice;

    @Column(precision=38, scale=18)
    public BigDecimal amount;

}
