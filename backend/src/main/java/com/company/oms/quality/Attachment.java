package com.company.oms.quality;

import com.company.oms.common.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "attachment")
public class Attachment extends BaseEntity {
    @Column(length=50)
    public String businessType;

    public Long businessId;

    @Column(length=500)
    public String fileKey;

    @Column(length=255)
    public String originalName;

    @Column(length=100)
    public String contentType;

    public Long sizeBytes;

    @Column(length=100)
    public String uploadedBy;

}
