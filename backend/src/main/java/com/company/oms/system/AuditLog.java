package com.company.oms.system;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "audit_log")
public class AuditLog extends BaseEntity {
    @Column(length=64)
    public String username;

    @Column(length=100)
    public String module;

    @Column(length=100)
    public String action;

    @Column(length=128)
    public String businessKey;

    @Column(length=2000)
    public String detail;

    @Column(length=64)
    public String ip;

}
