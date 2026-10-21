package com.company.oms.masterdata;

import com.company.oms.common.CrudController;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/master/suppliers")
@PreAuthorize("hasAnyRole('ADMIN','HR','CLERK','LOGISTICS')")
public class SupplierController extends CrudController<Supplier> {
    public SupplierController(SupplierRepository repository) {
        super(repository);
    }
}
