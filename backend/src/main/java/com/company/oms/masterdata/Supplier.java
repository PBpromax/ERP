package com.company.oms.masterdata;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "supplier")
public class Supplier extends BaseEntity {
    @Column(unique=true, length=64)
    public String code;

    @Column(nullable=false, length=200)
    public String name;

    @Column(length=100)
    public String contactPerson;

    @Column(length=50)
    public String phone;

    @Column(length=500)
    public String address;

    public Boolean enabled;

}
