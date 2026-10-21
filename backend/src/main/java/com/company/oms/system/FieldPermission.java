package com.company.oms.system;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "field_permission")
public class FieldPermission extends BaseEntity {
    @Column(nullable=false, length=64)
    public String roleCode;

    @Column(nullable=false, length=128)
    public String fieldCode;

    public Boolean readable;

}
