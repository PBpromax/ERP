package com.company.oms.system;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FieldPermissionService {
    private final SysUserRepository userRepository;
    private final FieldPermissionRepository fieldPermissionRepository;

    public FieldPermissionService(SysUserRepository userRepository, FieldPermissionRepository fieldPermissionRepository) {
        this.userRepository = userRepository;
        this.fieldPermissionRepository = fieldPermissionRepository;
    }

    public Map<String, Boolean> currentReadableFields() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof com.company.oms.security.AccountPrincipal) return Map.of("*", true);
        Set<String> roles = new LinkedHashSet<>();
        if (auth != null) {
            auth.getAuthorities().forEach(a -> roles.add(a.getAuthority().replace("ROLE_", "")));
        }
        return readableFieldsForRoles(roles);
    }

    public String currentRoleCacheKey() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return "anonymous";
        if (auth.getPrincipal() instanceof com.company.oms.security.AccountPrincipal) return "menu-account-money-visible";
        return auth.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    public Map<String, Boolean> readableFieldsForRoleCodes(String roleCodes) {
        if (roleCodes == null || roleCodes.isBlank()) return Map.of();
        return readableFieldsForRoles(Arrays.stream(roleCodes.split(","))
                .map(String::trim).filter(role -> !role.isBlank()).toList());
    }

    private Map<String, Boolean> readableFieldsForRoles(Collection<String> roles) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        // Admin role reads everything. Other roles are controlled by field_permission.
        if (roles.contains("ADMIN")) {
            result.put("*", true);
            return result;
        }
        for (String role : roles) {
            for (FieldPermission p : fieldPermissionRepository.findByRoleCodeAndDeletedFalse(role)) {
                result.put(p.fieldCode, Boolean.TRUE.equals(p.readable));
            }
        }
        return result;
    }

    public boolean canReadMoney() {
        Map<String, Boolean> permissions = currentReadableFields();
        return Boolean.TRUE.equals(permissions.get("*")) || Boolean.TRUE.equals(permissions.get("money.read"));
    }
}
