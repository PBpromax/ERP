package com.company.oms.security;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface LoginSessionRepository extends JpaRepository<LoginSession, String> {
    @Modifying @Transactional
    @Query("update LoginSession s set s.revoked=true where s.userId=:userId")
    void revokeForUser(@Param("userId") Long userId);
    @Modifying @Transactional
    @Query("update LoginSession s set s.revoked=true where s.id=:id")
    void revoke(@Param("id") String id);
}
