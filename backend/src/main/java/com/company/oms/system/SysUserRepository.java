package com.company.oms.system;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SysUserRepository extends JpaRepository<SysUser, Long> {
    Optional<SysUser> findByUsernameAndDeletedFalse(String username);
    boolean existsByUsername(String username);
}
