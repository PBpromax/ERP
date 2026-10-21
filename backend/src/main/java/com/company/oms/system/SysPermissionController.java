package com.company.oms.system;

import com.company.oms.common.CrudController;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/system/permissions")
@PreAuthorize("hasRole('ADMIN')")
public class SysPermissionController extends CrudController<SysPermission> {
    public SysPermissionController(SysPermissionRepository repository) {
        super(repository);
    }
}
