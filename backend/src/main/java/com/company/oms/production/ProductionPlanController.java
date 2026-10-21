package com.company.oms.production;

import com.company.oms.common.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/production/plans")
@PreAuthorize("hasAnyRole('ADMIN','HR','CLERK','LOGISTICS','PRODUCTION')")
public class ProductionPlanController extends CrudController<ProductionPlan> {
    private final ProductionService productionService;

    public ProductionPlanController(ProductionPlanRepository repository, ProductionService productionService) {
        super(repository);
        this.productionService = productionService;
    }

    @Override
    @GetMapping
    public ApiResponse<List<ProductionPlan>> list() {
        return ApiResponse.ok(productionService.activePlans());
    }

    @Override
    @PostMapping
    public ApiResponse<ProductionPlan> create(@RequestBody ProductionPlan entity) {
        entity.id = null;
        return ApiResponse.ok(productionService.savePlan(entity));
    }

    @Override
    @PutMapping("/{id}")
    public ApiResponse<ProductionPlan> update(@PathVariable Long id, @RequestBody ProductionPlan entity) {
        entity.id = id;
        return ApiResponse.ok(productionService.savePlan(entity));
    }

    @AuditLogAction(module = "生产计划", action = "分配任务")
    @PostMapping("/{id}/assign")
    public ApiResponse<ProductionPlan> assign(@PathVariable Long id, @RequestBody ProductionService.AssignPlanRequest request) {
        return ApiResponse.ok(productionService.assignPlan(id, request));
    }

    @GetMapping("/{id}/assignments")
    public ApiResponse<List<Map<String, Object>>> assignments(@PathVariable Long id) {
        return ApiResponse.ok(productionService.planAssignments(id));
    }

    @AuditLogAction(module = "生产计划", action = "删除分配任务")
    @DeleteMapping("/assignments/{assignmentId}")
    public ApiResponse<Void> deleteAssignment(@PathVariable Long assignmentId) {
        productionService.deletePlanAssignment(assignmentId);
        return ApiResponse.ok();
    }

    @AuditLogAction(module = "生产计划", action = "完工入库")
    @PostMapping("/{id}/finish")
    public ApiResponse<ProductionPlan> finish(@PathVariable Long id) {
        return ApiResponse.ok(productionService.finishPlan(id));
    }
}
