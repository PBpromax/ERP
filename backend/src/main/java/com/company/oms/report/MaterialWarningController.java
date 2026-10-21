package com.company.oms.report;

import com.company.oms.common.ApiResponse;
import com.company.oms.masterdata.MaterialManagementService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class MaterialWarningController {
    private final MaterialManagementService service;
    public MaterialWarningController(MaterialManagementService service) { this.service = service; }
    @GetMapping("/reports/material-warnings")
    @PreAuthorize("@menuPermissionService.has('dashboard')")
    public ApiResponse<?> warnings() { return ApiResponse.ok(service.warnings()); }
}
