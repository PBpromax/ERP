package com.company.oms.system;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "sys_role")
public class SysRole extends BaseEntity {
    @Column(nullable=false, unique=true, length=64)
    public String code;

    @Column(nullable=false, length=100)
    public String name;

    @Column(length=500)
    public String description;

}
