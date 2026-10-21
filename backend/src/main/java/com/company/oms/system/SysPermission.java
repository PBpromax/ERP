package com.company.oms.system;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "sys_permission")
public class SysPermission extends BaseEntity {
    @Column(nullable=false, unique=true, length=128)
    public String code;

    @Column(nullable=false, length=100)
    public String name;

    @Column(nullable=false, length=30)
    public String type;

    @Column(length=255)
    public String path;

    @Column(length=128)
    public String fieldCode;

}
