package com.company.oms.quality;

import com.company.oms.common.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDate;

@RestController
@RequestMapping("/quality/reports")
@PreAuthorize("hasAnyRole('ADMIN','HR','CLERK','LOGISTICS')")
public class QualityReportController extends CrudController<QualityReport> {
    private final DocumentNumberService documentNumberService;

    public QualityReportController(QualityReportRepository repository, DocumentNumberService documentNumberService) {
        super(repository);
        this.documentNumberService = documentNumberService;
    }

    @Override
    @AuditLogAction(module = "质检报告", action = "新增质检")
    @PostMapping
    public ApiResponse<QualityReport> create(@RequestBody QualityReport entity) {
        if (entity.reportNo == null || entity.reportNo.isBlank()) {
            if (entity.inspectionDate == null) entity.inspectionDate = LocalDate.now();
            entity.reportNo = documentNumberService.nextQualityReport(entity.inspectionDate, "质检报告单号生成失败，请稍后重试");
        }
        return super.create(entity);
    }
}
