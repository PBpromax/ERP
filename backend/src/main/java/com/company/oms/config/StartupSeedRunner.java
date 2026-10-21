package com.company.oms.config;

import com.company.oms.system.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class StartupSeedRunner implements CommandLineRunner {
    @Value("${OMS_BOOTSTRAP_ADMIN_USERNAME:admin}") private String adminUsername;
    @Value("${OMS_BOOTSTRAP_ADMIN_PASSWORD:${oms.bootstrap.admin-password:}}") private String adminPassword;
    @Value("${OMS_BOOTSTRAP_ADMIN_NAME:系统管理员}") private String adminName;
    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final FieldPermissionRepository fieldPermissionRepository;
    private final PasswordEncoder passwordEncoder;

    public StartupSeedRunner(SysUserRepository userRepository,
                             SysRoleRepository roleRepository,
                             FieldPermissionRepository fieldPermissionRepository,
                             PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.fieldPermissionRepository = fieldPermissionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        role("ADMIN", "公司负责人", "可查看所有界面，并可进入系统管理");
        role("SALES", "销售员", "只能查看销售管理");
        role("HR", "人事", "可查看除系统管理外的所有业务界面");
        role("CLERK", "文员", "可查看除系统管理外的所有业务界面");
        role("PRODUCTION", "生产", "只能查看生产管理");
        role("LOGISTICS", "后勤", "可查看除系统管理外的所有业务界面");
        role("WAREHOUSE", "仓库", "只能查看库存管理");
        hideLegacyRole("FINANCE");
        hideLegacyRole("QUALITY");
        if (userRepository.count() == 0) {
            if (adminPassword == null || adminPassword.isBlank() || adminPassword.length() < 12
                    || adminPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalStateException("首次启动须设置 OMS_BOOTSTRAP_ADMIN_PASSWORD（至少12字符，UTF-8不超过72字节）");
            }
            user(adminUsername, adminPassword, adminName, "ADMIN");
        }
        money("ADMIN", true);
        money("SALES", true);
        money("HR", true);
        money("CLERK", true);
        money("PRODUCTION", false);
        money("LOGISTICS", true);
        money("WAREHOUSE", false);
    }

    private void role(String code, String name, String desc) {
        SysRole r = roleRepository.findByCode(code).orElseGet(SysRole::new);
        r.code = code;
        r.name = name;
        r.description = desc;
        r.deleted = false;
        roleRepository.save(r);
    }

    private void hideLegacyRole(String code) {
        roleRepository.findByCode(code).ifPresent(role -> {
            role.deleted = true;
            roleRepository.save(role);
        });
    }

    private void user(String username, String password, String realName, String roles) {
        SysUser u = new SysUser();
        u.username = username;
        u.passwordHash = passwordEncoder.encode(password);
        u.realName = realName;
        u.roleCodes = roles;
        u.enabled = true;
        u.owner = "ADMIN".equals(roles);
        u.permissionMode = u.owner ? "ALL_BUSINESS" : "CUSTOM";
        if (u.owner) u.menuCodes.add("users");
        userRepository.save(u);
    }

    private void money(String role, boolean readable) {
        FieldPermission p = fieldPermissionRepository.findFirstByRoleCodeAndFieldCodeAndDeletedFalse(role, "money.read")
                .orElseGet(FieldPermission::new);
        p.roleCode = role;
        p.fieldCode = "money.read";
        p.readable = readable;
        fieldPermissionRepository.save(p);
    }
}
