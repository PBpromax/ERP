package com.company.oms.production;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "production_worker")
public class ProductionWorker extends BaseEntity {
    @Column(nullable=false, length=100)
    public String name;

    @Column(unique=true, length=64)
    public String accessToken;
}
