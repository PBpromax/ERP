package com.company.oms.notify;

import com.company.oms.common.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/notify")
@PreAuthorize("hasAnyRole('ADMIN','HR','CLERK','LOGISTICS')")
public class NotifyController extends CrudController<NotifyRecord> {
    private final NotifyRecordRepository notifyRecordRepository;

    public NotifyController(NotifyRecordRepository repository) {
        super(repository);
        this.notifyRecordRepository = repository;
    }

    @GetMapping("/todo")
    public ApiResponse<List<NotifyRecord>> todo() {
        return ApiResponse.ok(notifyRecordRepository.findByStatusAndDeletedFalseOrderByCreatedAtDesc("未读"));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<NotifyRecord> read(@PathVariable Long id) {
        NotifyRecord record = notifyRecordRepository.findById(id).orElseThrow(() -> new BusinessException("消息不存在"));
        record.status = "已读";
        return ApiResponse.ok(notifyRecordRepository.save(record));
    }

    @PostMapping("/read-all")
    public ApiResponse<Void> readAll() {
        for (NotifyRecord record : notifyRecordRepository.findByStatusAndDeletedFalseOrderByCreatedAtDesc("未读")) {
            record.status = "已读";
            notifyRecordRepository.save(record);
        }
        return ApiResponse.ok();
    }
}
