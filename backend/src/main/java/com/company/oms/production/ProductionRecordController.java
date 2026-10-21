package com.company.oms.production;

import com.company.oms.common.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/production/records")
@PreAuthorize("hasAnyRole('ADMIN','HR','CLERK','LOGISTICS','PRODUCTION')")
public class ProductionRecordController extends CrudController<ProductionWorker> {
    private final ProductionService productionService;

    public ProductionRecordController(ProductionWorkerRepository repository, ProductionService productionService) {
        super(repository);
        this.productionService = productionService;
    }

    @Override
    public ApiResponse<ProductionWorker> create(@RequestBody ProductionWorker entity) {
        entity.id = null;
        return ApiResponse.ok(productionService.saveWorker(entity));
    }

    @Override
    public ApiResponse<ProductionWorker> update(@PathVariable Long id, @RequestBody ProductionWorker entity) {
        entity.id = id;
        return ApiResponse.ok(productionService.saveWorker(entity));
    }

    @GetMapping("/{workerId}/items")
    public ApiResponse<List<WorkerProductionRecord>> items(@PathVariable Long workerId) {
        return ApiResponse.ok(productionService.workerRecords(workerId));
    }

    @GetMapping("/{workerId}/tasks")
    public ApiResponse<List<Map<String, Object>>> tasks(@PathVariable Long workerId) {
        return ApiResponse.ok(productionService.workerTasks(workerId));
    }

    @GetMapping("/{workerId}/assigned-products")
    public ApiResponse<List<Map<String, Object>>> assignedProducts(@PathVariable Long workerId) {
        return ApiResponse.ok(productionService.workerAssignedProducts(workerId));
    }

    @AuditLogAction(module = "生产记录", action = "新增工人生产记录")
    @PostMapping("/{workerId}/items")
    public ApiResponse<WorkerProductionRecord> addItem(@PathVariable Long workerId, @RequestBody RecordRequest request) {
        return ApiResponse.ok(productionService.addWorkerRecord(workerId, request));
    }

    @AuditLogAction(module = "生产记录", action = "删除工人生产记录")
    @DeleteMapping("/items/{recordId}")
    public ApiResponse<Void> deleteItem(@PathVariable Long recordId) {
        productionService.deleteWorkerRecord(recordId);
        return ApiResponse.ok();
    }

    @AuditLogAction(module = "生产记录", action = "审核工人生产记录")
    @PostMapping("/items/{recordId}/audit")
    public ApiResponse<WorkerProductionRecord> auditItem(@PathVariable Long recordId, @RequestBody AuditRequest request) {
        return ApiResponse.ok(productionService.auditWorkerRecord(recordId, request.qualifiedQty));
    }

    @GetMapping("/items/{recordId}/allocation-candidates")
    public ApiResponse<List<Map<String, Object>>> allocationCandidates(@PathVariable Long recordId) {
        return ApiResponse.ok(productionService.allocationCandidates(recordId));
    }

    @GetMapping("/items/{recordId}/allocations")
    public ApiResponse<List<Map<String, Object>>> allocations(@PathVariable Long recordId) {
        return ApiResponse.ok(productionService.recordAllocations(recordId));
    }

    @AuditLogAction(module = "生产记录", action = "分配工人生产记录")
    @PostMapping("/items/{recordId}/allocate")
    public ApiResponse<WorkerProductionRecord> allocate(@PathVariable Long recordId, @RequestBody AllocationRequest request) {
        return ApiResponse.ok(productionService.allocateWorkerRecord(recordId, request));
    }

    @GetMapping("/{workerId}/entry")
    public ApiResponse<Map<String, Object>> workerEntry(@PathVariable Long workerId, HttpServletRequest request) {
        ProductionWorker worker = repository.findById(workerId)
                .filter(w -> w.deleted == null || !w.deleted)
                .orElseThrow(() -> new BusinessException("工人不存在"));
        if (worker.accessToken == null || worker.accessToken.isBlank()) {
            worker = productionService.saveWorker(worker);
        }
        String url = publicBaseUrl(request) + "/worker/production/" + worker.accessToken;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workerId", worker.id);
        row.put("workerName", worker.name);
        row.put("token", worker.accessToken);
        row.put("url", url);
        return ApiResponse.ok(row);
    }

    @GetMapping("/worker-entry/{token}")
    @PreAuthorize("permitAll()")
    public ApiResponse<Map<String, Object>> publicWorkerEntry(@PathVariable String token) {
        ProductionWorker worker = productionService.workerByToken(token);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workerId", worker.id);
        row.put("workerName", worker.name);
        return ApiResponse.ok(row);
    }

    @GetMapping("/worker-entry/{token}/items")
    @PreAuthorize("permitAll()")
    public ApiResponse<List<WorkerProductionRecord>> publicWorkerItems(@PathVariable String token) {
        ProductionWorker worker = productionService.workerByToken(token);
        return ApiResponse.ok(productionService.workerRecords(worker.id));
    }

    @GetMapping("/worker-entry/{token}/stocks")
    @PreAuthorize("permitAll()")
    public ApiResponse<List<Map<String, Object>>> publicStocks(@PathVariable String token) {
        ProductionWorker worker = productionService.workerByToken(token);
        return ApiResponse.ok(productionService.workerAssignedProducts(worker.id));
    }

    @PostMapping("/worker-entry/{token}/items")
    @PreAuthorize("permitAll()")
    public ApiResponse<WorkerProductionRecord> publicAddWorkerItem(@PathVariable String token, @RequestBody RecordRequest request) {
        return ApiResponse.ok(productionService.addWorkerRecordByToken(token, request));
    }

    private String publicBaseUrl(HttpServletRequest request) {
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        String forwardedHost = request.getHeader("X-Forwarded-Host");
        String proto = forwardedProto == null || forwardedProto.isBlank() ? request.getScheme() : forwardedProto;
        String host = forwardedHost == null || forwardedHost.isBlank() ? request.getHeader("Host") : forwardedHost;
        return proto + "://" + host;
    }

    public static class RecordRequest {
        public LocalDate productionDate;
        public Long semiFinishedStockId;
        public String semiFinishedCode;
        public BigDecimal qty;
    }

    public static class AuditRequest {
        public BigDecimal qualifiedQty;
    }

    public static class AllocationRequest {
        public Long assignmentId;
        public BigDecimal qty;
    }
}
