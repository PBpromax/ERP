package com.company.oms.system;

import com.company.oms.common.CrudController;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/system/roles")
@PreAuthorize("hasRole('ADMIN')")
public class SysRoleController extends CrudController<SysRole> {
    public SysRoleController(SysRoleRepository repository) {
        super(repository);
    }
}
