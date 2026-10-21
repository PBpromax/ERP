package com.company.oms.system;

import com.company.oms.common.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/system/users")
@PreAuthorize("@menuPermissionService.has('users')")
public class SysUserController {
    private final UserAccountService accounts;
    private final SysUserRepository users;
    private final MenuCatalog catalog;
    public SysUserController(UserAccountService accounts, SysUserRepository users, MenuCatalog catalog) {
        this.accounts = accounts; this.users = users; this.catalog = catalog;
    }
    @GetMapping public ApiResponse<List<UserAccountService.UserView>> list() {
        return ApiResponse.ok(users.findAll().stream().filter(u -> !Boolean.TRUE.equals(u.deleted))
            .filter(accounts::visibleInManagement)
            .sorted(java.util.Comparator.comparing(u -> u.id)).map(accounts::view).toList());
    }
    @GetMapping("/permission-catalog") public ApiResponse<List<MenuCatalog.Entry>> catalog() { return ApiResponse.ok(catalog.entries()); }
    @GetMapping("/{id}") public ApiResponse<UserAccountService.UserView> get(@PathVariable Long id) { return ApiResponse.ok(accounts.view(accounts.findManaged(id))); }
    @PostMapping @AuditLogAction(module="用户管理", action="新增用户")
    public ApiResponse<UserAccountService.UserView> create(@RequestBody UserAccountService.UserRequest request) { return ApiResponse.ok(accounts.save(null, request)); }
    @PutMapping("/{id}") @AuditLogAction(module="用户管理", action="编辑用户")
    public ApiResponse<UserAccountService.UserView> update(@PathVariable Long id, @RequestBody UserAccountService.UserRequest request) { return ApiResponse.ok(accounts.save(id, request)); }
    @DeleteMapping("/{id}") @AuditLogAction(module="用户管理", action="删除用户")
    public ApiResponse<Void> delete(@PathVariable Long id) { accounts.delete(id); return ApiResponse.ok(); }
}
