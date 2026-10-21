package com.company.oms.system;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FieldPermissionRepository extends JpaRepository<FieldPermission, Long> {
    List<FieldPermission> findByRoleCodeAndDeletedFalse(String roleCode);
    Optional<FieldPermission> findFirstByRoleCodeAndFieldCodeAndDeletedFalse(String roleCode, String fieldCode);
}
