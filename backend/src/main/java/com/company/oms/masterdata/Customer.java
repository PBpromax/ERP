package com.company.oms.masterdata;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "customer")
public class Customer extends BaseEntity {
    @Column(nullable=false, length=200)
    public String name;

    @Column(length=100)
    public String contactPerson;

    @Column(length=50)
    public String phone;

    @Column(length=100)
    public String email;

    @Column(length=500)
    public String address;

    @Column(length=100)
    public String legalRepresentative;

    @Column(length=100)
    public String unifiedSocialCreditCode;

    // Null preserves legacy records; the API infers their initial choice without rewriting them.
    public Boolean invoiceRequired;

    @com.fasterxml.jackson.annotation.JsonGetter("invoiceRequired")
    public boolean requiresInvoice() {
        return invoiceRequired != null ? invoiceRequired : hasCreditCode(unifiedSocialCreditCode);
    }

    @com.fasterxml.jackson.annotation.JsonGetter("unifiedSocialCreditCode")
    public String creditCodeForDisplay() {
        return hasCreditCode(unifiedSocialCreditCode) ? unifiedSocialCreditCode.trim() : null;
    }

    public static boolean hasCreditCode(String value) {
        if (value == null || value.isBlank()) return false;
        return !java.util.Set.of("不开票", "不开发票", "无需开票").contains(value.trim());
    }

    @Column(length=200)
    public String invoiceTitle;

    @Column(length=100)
    public String taxNo;

    @Column(length=100)
    public String publicBankAccount;

    @Column(length=200)
    public String publicBankName;

    @Column(length=100)
    public String publicBankNo;

    @Column(length=30)
    public String defaultPaymentTerm;

    @Column(precision=18, scale=4)
    public BigDecimal taxRate;

    @Column(length=100)
    public String settlementMethod;

    @Column(precision=38, scale=18)
    public BigDecimal creditLimit;

    public Boolean enabled;

    public LocalDate lastOrderDate;

    @Transient
    public List<CustomerContact> contacts;

    @Transient
    public String contactNames;

}
