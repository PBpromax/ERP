package com.company.oms.quality;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByBusinessTypeAndBusinessIdInAndDeletedFalse(String businessType, List<Long> businessIds);
    List<Attachment> findByBusinessTypeAndBusinessIdAndDeletedFalse(String businessType, Long businessId);
}
