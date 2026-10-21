package com.company.oms.masterdata;

import com.company.oms.common.ApiResponse;
import com.company.oms.common.AuditLogAction;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.time.LocalDate;

@RestController
@RequestMapping("/master/materials")
@PreAuthorize("@menuPermissionService.has('materials')")
public class MaterialController {
    private final MaterialManagementService service;
    public MaterialController(MaterialManagementService service) { this.service = service; }

    @GetMapping public ApiResponse<?> list() { return ApiResponse.ok(service.list()); }
    @PostMapping("/{id}/scan-code")
    public ApiResponse<?> scanCode(@PathVariable Long id) { return ApiResponse.ok(service.scanCode(id)); }
    @GetMapping("/{id}") public ApiResponse<?> get(@PathVariable Long id) { return ApiResponse.ok(service.get(id)); }
    @PostMapping @AuditLogAction(module = "原料库存", action = "新增材料")
    public ApiResponse<?> create(@RequestBody MaterialManagementService.MaterialRequest r) { return ApiResponse.ok(service.create(r)); }
    @PutMapping("/{id}") @AuditLogAction(module = "原料库存", action = "编辑材料")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody MaterialManagementService.MaterialRequest r) { return ApiResponse.ok(service.update(id, r)); }
    @DeleteMapping("/{id}") @AuditLogAction(module = "原料库存", action = "删除材料")
    public ApiResponse<?> delete(@PathVariable Long id) { service.delete(id); return ApiResponse.ok(); }
    @GetMapping("/{id}/daily-records/{date}")
    public ApiResponse<?> daily(@PathVariable Long id, @PathVariable LocalDate date) { return ApiResponse.ok(service.dailyRecord(id, date)); }
    @PutMapping("/{id}/daily-records/{date}") @AuditLogAction(module = "原料库存", action = "登记或更正每日数量")
    public ApiResponse<?> saveDaily(@PathVariable Long id, @PathVariable LocalDate date, @RequestBody MaterialManagementService.DailyRequest r) {
        return ApiResponse.ok(service.saveDaily(id, date, r));
    }
    @GetMapping("/{id}/daily-records")
    public ApiResponse<?> records(@PathVariable Long id, @RequestParam(required = false) LocalDate start,
            @RequestParam(required = false) LocalDate end, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(service.records(id, start, end, page, size));
    }
    @GetMapping("/{id}/ledger")
    public ApiResponse<?> ledger(@PathVariable Long id, @RequestParam(required = false) LocalDate start,
            @RequestParam(required = false) LocalDate end, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(service.ledger(id, start, end, page, size));
    }
    @GetMapping("/{id}/trend") public ApiResponse<?> trend(@PathVariable Long id) { return ApiResponse.ok(service.trend(id)); }
    @PostMapping("/{id}/adjustments") @AuditLogAction(module = "原料库存", action = "调整库存")
    public ApiResponse<?> adjust(@PathVariable Long id, @RequestBody MaterialManagementService.AdjustmentRequest r) { return ApiResponse.ok(service.adjust(id, r)); }
}
