package com.company.oms.notify;

import org.springframework.stereotype.Service;

@Service
public class NotifyService {
    private final NotifyRecordRepository repository;

    public NotifyService(NotifyRecordRepository repository) {
        this.repository = repository;
    }

    public NotifyRecord create(String title, String content, String type, String level, String businessType, String businessNo) {
        NotifyRecord record = new NotifyRecord();
        record.title = title;
        record.content = content;
        record.type = type;
        record.level = level;
        record.businessType = businessType;
        record.businessNo = businessNo;
        record.status = "未读";
        return repository.save(record);
    }
}
