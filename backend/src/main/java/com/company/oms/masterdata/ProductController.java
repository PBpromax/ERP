package com.company.oms.masterdata;

import com.company.oms.common.CrudController;
import com.company.oms.common.ApiResponse;
import com.company.oms.common.AuditLogAction;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

import java.util.List;

@RestController
@RequestMapping("/master/products")
@PreAuthorize("@menuPermissionService.has('products')")
public class ProductController extends CrudController<Product> {
    private final ProductService productService;

    public ProductController(ProductRepository repository, ProductService productService) {
        super(repository);
        this.productService = productService;
    }

    @Override
    @GetMapping
    @PreAuthorize("@menuPermissionService.has('products')")
    @Cacheable(cacheNames = "masterProducts", key = "@fieldPermissionService.currentRoleCacheKey()")
    public ApiResponse<List<Product>> list() {
        return ApiResponse.ok(productService.list());
    }

    @Override
    @GetMapping("/{id}")
    @PreAuthorize("@menuPermissionService.has('products')")
    public ApiResponse<Product> get(@PathVariable Long id) {
        return ApiResponse.ok(productService.get(id));
    }

    @GetMapping("/{id}/references")
    @PreAuthorize("@menuPermissionService.has('products')")
    public ApiResponse<ProductService.ProductReferenceInfo> references(@PathVariable Long id) {
        productService.get(id);
        return ApiResponse.ok(productService.references(id));
    }

    @Override
    @AuditLogAction(module = "产品资料", action = "新增产品")
    @PostMapping
    @PreAuthorize("@menuPermissionService.has('products')")
    @CacheEvict(cacheNames = "masterProducts", allEntries = true)
    public ApiResponse<Product> create(@RequestBody Product entity) {
        return ApiResponse.ok(productService.create(entity));
    }

    @Override
    @AuditLogAction(module = "产品资料", action = "编辑产品")
    @PutMapping("/{id}")
    @PreAuthorize("@menuPermissionService.has('products')")
    @CacheEvict(cacheNames = "masterProducts", allEntries = true)
    public ApiResponse<Product> update(@PathVariable Long id, @RequestBody Product entity) {
        return ApiResponse.ok(productService.update(id, entity));
    }

    @Override
    @AuditLogAction(module = "产品资料", action = "删除产品")
    @DeleteMapping("/{id}")
    @PreAuthorize("@menuPermissionService.has('products')")
    @CacheEvict(cacheNames = "masterProducts", allEntries = true)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        productService.delete(id, false);
        return ApiResponse.ok();
    }

    @AuditLogAction(module = "产品资料", action = "确认删除已使用产品")
    @DeleteMapping("/{id}/force")
    @PreAuthorize("@menuPermissionService.has('products')")
    @CacheEvict(cacheNames = "masterProducts", allEntries = true)
    public ApiResponse<Void> forceDelete(@PathVariable Long id) {
        productService.delete(id, true);
        return ApiResponse.ok();
    }

    @AuditLogAction(module = "产品资料", action = "切换产品状态")
    @PostMapping("/{id}/status")
    @PreAuthorize("@menuPermissionService.has('products')")
    @CacheEvict(cacheNames = "masterProducts", allEntries = true)
    public ApiResponse<Product> status(@PathVariable Long id, @RequestBody StatusRequest request) {
        return ApiResponse.ok(productService.setStatus(id, request.status));
    }

    public static class StatusRequest {
        public String status;
    }
}
