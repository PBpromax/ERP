package com.company.oms.finance;

import com.company.oms.common.CrudController;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/finance/ar-balance-snapshots")
@PreAuthorize("@menuPermissionService.has('receipts')")
public class ArBalanceSnapshotController extends CrudController<ArBalanceSnapshot> {
    public ArBalanceSnapshotController(ArBalanceSnapshotRepository repository) {
        super(repository);
    }
}
