package com.company.oms.system;

import com.company.oms.common.BusinessException;
import com.company.oms.security.LoginSessionRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class UserAccountService {
    public record UserView(Long id, String username, String realName, Boolean enabled, Boolean owner,
                           String permissionMode, Set<String> menuCodes, Set<String> effectiveMenuCodes, Boolean orderPricesVisible) {}
    public static class UserRequest {
        public String username;
        public String realName;
        public String password;
        public Boolean enabled = true;
        public Boolean orderPricesVisible;
        public String permissionMode = "CUSTOM";
        public Set<String> menuCodes = new LinkedHashSet<>();
    }
    private final SysUserRepository repository;
    private final PasswordEncoder passwords;
    private final MenuCatalog catalog;
    private final MenuPermissionService permissions;
    private final LoginSessionRepository sessions;
    public UserAccountService(SysUserRepository repository, PasswordEncoder passwords, MenuCatalog catalog,
                              MenuPermissionService permissions, LoginSessionRepository sessions) {
        this.repository = repository; this.passwords = passwords; this.catalog = catalog;
        this.permissions = permissions; this.sessions = sessions;
    }
    public UserView view(SysUser u) {
        return new UserView(u.id, u.username, u.realName, u.enabled, u.owner, u.permissionMode,
            catalog.editableCodes(u.menuCodes), catalog.effective(u), orderPricesVisible(u));
    }
    public static boolean orderPricesVisible(SysUser u) {
        return Boolean.TRUE.equals(u.owner) || Boolean.TRUE.equals(u.orderPricesVisible);
    }
    public Map<String, Object> profile(SysUser u) {
        return Map.of("user", view(u), "menuCatalog", catalog.entries(), "fieldPermissions", Map.of("*", true));
    }
    public SysUser find(Long id) {
        return repository.findById(id).filter(u -> !Boolean.TRUE.equals(u.deleted))
            .orElseThrow(() -> new BusinessException("用户不存在"));
    }
    public boolean visibleInManagement(SysUser user) {
        return !Boolean.TRUE.equals(user.managementPrivate)
                || (permissions.principal() != null && user.id.equals(permissions.principal().userId()));
    }
    public SysUser findManaged(Long id) {
        SysUser user = find(id);
        if (!visibleInManagement(user)) throw new BusinessException("用户不存在");
        return user;
    }
    private void checkOwner(SysUser u) {
        if (Boolean.TRUE.equals(u.owner) && (permissions.principal() == null || !u.id.equals(permissions.principal().userId())))
            throw new AccessDeniedException("只有老板本人可以编辑老板账号");
    }
    @Transactional
    public UserView save(Long id, UserRequest request) {
        SysUser u = id == null ? new SysUser() : findManaged(id);
        checkOwner(u);
        if (request.username == null || request.username.isBlank() || request.username.trim().length() > 64
                || request.realName == null || request.realName.isBlank() || request.realName.trim().length() > 100)
            throw new BusinessException("请填写姓名和登录账户，账户最多64字，姓名最多100字");
        String username = request.username.trim();
        if (repository.findAll().stream().anyMatch(existing -> existing.username.equalsIgnoreCase(username) && !Objects.equals(existing.id, id)))
            throw new BusinessException("登录账户已存在");
        boolean changedAccount = id != null && !Objects.equals(u.username, username);
        boolean changedPassword = request.password != null && !request.password.isBlank();
        if (id == null && !changedPassword) throw new BusinessException("新增用户必须填写密码");
        if (changedPassword && (request.password.length() < 6 || request.password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72))
            throw new BusinessException("密码至少6位，不能超过72字节");
        if (request.permissionMode == null || !Set.of("CUSTOM", "ALL_BUSINESS").contains(request.permissionMode)) throw new BusinessException("权限模式无效");
        Set<String> codes = request.menuCodes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(request.menuCodes);
        catalog.validate(codes);
        codes = catalog.editableCodes(codes);
        if (Boolean.TRUE.equals(u.owner)) {
            if (!Boolean.TRUE.equals(request.enabled) || !"ALL_BUSINESS".equals(request.permissionMode) || !codes.contains("users") || Boolean.FALSE.equals(request.orderPricesVisible))
                throw new BusinessException("老板账号必须保持启用和全部权限");
        }
        u.username = username; u.realName = request.realName.trim(); u.enabled = Boolean.TRUE.equals(request.enabled);
        u.permissionMode = request.permissionMode; u.menuCodes.clear(); u.menuCodes.addAll(codes);
        if (request.orderPricesVisible != null) u.orderPricesVisible = request.orderPricesVisible;
        if (Boolean.TRUE.equals(u.owner)) u.orderPricesVisible = true;
        u.roleCodes = "";
        if (changedPassword) u.passwordHash = passwords.encode(request.password);
        repository.saveAndFlush(u);
        if (changedAccount || changedPassword || !u.enabled) sessions.revokeForUser(u.id);
        return view(u);
    }
    @Transactional
    public void delete(Long id) {
        SysUser u = findManaged(id);
        if (Boolean.TRUE.equals(u.owner)) throw new BusinessException("老板账号不能删除");
        if (permissions.principal() != null && id.equals(permissions.principal().userId())) throw new BusinessException("不能删除当前登录账号");
        u.deleted = true; u.enabled = false; repository.save(u); sessions.revokeForUser(id);
    }
}
