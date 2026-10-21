package com.company.oms.masterdata;

import com.company.oms.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/material-entry")
@org.springframework.security.access.prepost.PreAuthorize("@menuPermissionService.has('material-entry-in', 'material-entry-out')")
public class MaterialScanController {
    private final MaterialManagementService service;
    public MaterialScanController(MaterialManagementService service) { this.service = service; }

    @GetMapping("/materials")
    public ApiResponse<?> materials() { return ApiResponse.ok(service.entryMaterials()); }

    @GetMapping("/materials/{id}")
    public ApiResponse<?> materialById(@PathVariable Long id) { return ApiResponse.ok(service.entryMaterial(id)); }

    @PostMapping("/materials/{id}/entries")
    public ApiResponse<?> registerById(@PathVariable Long id, @RequestBody MaterialManagementService.ScanRequest request) {
        return ApiResponse.ok(service.registerMaterial(id, request));
    }

    @GetMapping("/{token}")
    public ApiResponse<?> material(@PathVariable String token) { return ApiResponse.ok(service.scanMaterial(token)); }

    @PostMapping("/{token}/entries")
    public ApiResponse<?> register(@PathVariable String token, @RequestBody MaterialManagementService.ScanRequest request) {
        return ApiResponse.ok(service.registerScan(token, request));
    }
}
