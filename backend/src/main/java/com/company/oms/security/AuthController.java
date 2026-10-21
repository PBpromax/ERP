package com.company.oms.security;

import com.company.oms.common.*;
import com.company.oms.system.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final SysUserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final LoginSessionRepository sessions;
    private final UserAccountService accounts;
    private final MenuPermissionService permissions;
    public AuthController(SysUserRepository users, PasswordEncoder passwords, TokenService tokens,
                          LoginSessionRepository sessions, UserAccountService accounts, MenuPermissionService permissions) {
        this.users = users; this.passwords = passwords; this.tokens = tokens;
        this.sessions = sessions; this.accounts = accounts; this.permissions = permissions;
    }
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        var user = users.findByUsernameAndDeletedFalse(request.username.trim())
                .orElseThrow(() -> new BusinessException("账户或密码错误"));
        if (!Boolean.TRUE.equals(user.enabled) || !passwords.matches(request.password, user.passwordHash))
            throw new BusinessException("账户或密码错误");
        Map<String, Object> profile = new LinkedHashMap<>(accounts.profile(user));
        profile.put("token", tokens.createToken(user));
        return ApiResponse.ok(profile);
    }
    @GetMapping("/me") public ApiResponse<Map<String, Object>> me() {
        return ApiResponse.ok(accounts.profile(accounts.find(permissions.principal().userId())));
    }
    @GetMapping("/field-permissions") public ApiResponse<Map<String, Boolean>> fields() { return ApiResponse.ok(Map.of("*", true)); }
    @PostMapping("/logout") public ApiResponse<Void> logout() {
        sessions.revoke(permissions.principal().sessionId()); return ApiResponse.ok();
    }
    @PostMapping("/change-password") @Transactional
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        var user = accounts.find(permissions.principal().userId());
        if (!passwords.matches(request.oldPassword, user.passwordHash)) throw new BusinessException("原密码错误");
        if (request.newPassword.length() < 6 || request.newPassword.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new BusinessException("新密码至少6位，不能超过72字节");
        if (!request.newPassword.equals(request.confirmPassword)) throw new BusinessException("两次新密码不一致");
        user.passwordHash = passwords.encode(request.newPassword); users.saveAndFlush(user); sessions.revokeForUser(user.id);
        return ApiResponse.ok();
    }
    public static class LoginRequest {
        @NotBlank(message="请输入登录账户") public String username;
        @NotBlank(message="请输入密码") public String password;
    }
    public static class ChangePasswordRequest {
        @NotBlank(message="请输入原密码") public String oldPassword;
        @NotBlank(message="请输入新密码") public String newPassword;
        @NotBlank(message="请再次输入新密码") public String confirmPassword;
    }
}
