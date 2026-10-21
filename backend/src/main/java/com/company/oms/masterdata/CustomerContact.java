package com.company.oms.masterdata;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "customer_contact")
public class CustomerContact extends BaseEntity {
    public Long customerId;

    @Column(nullable = false, length = 100)
    public String name;

    @Column(length = 50)
    public String phone;

    @Column(length = 100)
    public String email;

    @Column(length = 100)
    public String position;

    public Boolean primaryContact;
    public Boolean enabled;
}
