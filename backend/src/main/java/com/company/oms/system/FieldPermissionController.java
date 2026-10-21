package com.company.oms.system;

import com.company.oms.common.ApiResponse;
import com.company.oms.common.CrudController;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/system/field-permissions")
@PreAuthorize("hasRole('ADMIN')")
public class FieldPermissionController extends CrudController<FieldPermission> {
    public FieldPermissionController(FieldPermissionRepository repository) {
        super(repository);
    }

    @Override
    @PostMapping
    @CacheEvict(cacheNames = {"masterCustomers", "masterProducts"}, allEntries = true)
    public ApiResponse<FieldPermission> create(@RequestBody FieldPermission entity) {
        return super.create(entity);
    }

    @Override
    @PutMapping("/{id}")
    @CacheEvict(cacheNames = {"masterCustomers", "masterProducts"}, allEntries = true)
    public ApiResponse<FieldPermission> update(@PathVariable Long id, @RequestBody FieldPermission entity) {
        return super.update(id, entity);
    }

    @Override
    @DeleteMapping("/{id}")
    @CacheEvict(cacheNames = {"masterCustomers", "masterProducts"}, allEntries = true)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        return super.delete(id);
    }
}
