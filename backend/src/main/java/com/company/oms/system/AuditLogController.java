package com.company.oms.system;

import com.company.oms.common.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/system/audit-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogController extends CrudController<AuditLog> {
    private final AuditLogRepository auditLogRepository;

    public AuditLogController(AuditLogRepository repository) {
        super(repository);
        this.auditLogRepository = repository;
    }

    @GetMapping("/latest")
    public ApiResponse<?> latest() {
        return ApiResponse.ok(auditLogRepository.findTop200ByDeletedFalseOrderByCreatedAtDesc());
    }
}
