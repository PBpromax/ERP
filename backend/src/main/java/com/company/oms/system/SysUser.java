package com.company.oms.system;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "sys_user")
public class SysUser extends BaseEntity {
    @Column(nullable=false, unique=true, length=64)
    public String username;

    @Column(nullable=false, length=255)
    @JsonIgnore
    public String passwordHash;

    @Column(length=100)
    public String realName;

    @Column(length=50)
    public String phone;

    @Column(length=120)
    public String email;

    @Column(length=500)
    public String roleCodes;

    public Boolean enabled;

    @Column(nullable=false, length=24)
    public String permissionMode = "CUSTOM";

    @Column(nullable=false)
    public Boolean owner = false;

    @Column(nullable=false)
    public Boolean orderPricesVisible = true;

    // Provisioned by an operator; ordinary user-management requests cannot change this scope.
    @JsonIgnore
    @Column(nullable=false)
    public Boolean managementPrivate = false;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sys_user_menu_permission", joinColumns = @JoinColumn(name = "user_id"),
            uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "menu_code"}))
    @Column(name = "menu_code", nullable = false, length = 64)
    public Set<String> menuCodes = new LinkedHashSet<>();

}
