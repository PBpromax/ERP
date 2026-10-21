package com.company.oms.notify;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "notify_record")
public class NotifyRecord extends BaseEntity {
    @Column(length=200)
    public String title;

    @Column(length=1000)
    public String content;

    @Column(length=50)
    public String type;

    @Column(length=30)
    public String level;

    @Column(length=30)
    public String status;

    @Column(length=50)
    public String businessType;

    @Column(length=64)
    public String businessNo;

}
