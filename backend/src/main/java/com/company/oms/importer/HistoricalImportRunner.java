package com.company.oms.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class HistoricalImportRunner implements ApplicationRunner {
    private final HistoricalImportService importService;
    private final ObjectMapper objectMapper;

    @Value("${oms.history-import.enabled:false}")
    private boolean enabled;

    @Value("${oms.history-import.plan-file:}")
    private String planFile;

    @Value("${oms.history-import.report-file:}")
    private String reportFile;

    public HistoricalImportRunner(HistoricalImportService importService, ObjectMapper objectMapper) {
        this.importService = importService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) return;
        if (planFile == null || planFile.isBlank()) throw new IllegalArgumentException("未配置历史导入计划文件");
        Map<String, Object> report = importService.importPlan(Path.of(planFile));
        if (reportFile != null && !reportFile.isBlank()) {
            Path target = Path.of(reportFile);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
        }
        System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
