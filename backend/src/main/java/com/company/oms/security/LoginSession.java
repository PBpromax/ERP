package com.company.oms.security;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sys_login_session", indexes = @Index(columnList = "user_id"))
public class LoginSession {
    @Id @Column(length = 36) public String id;
    @Column(name="user_id", nullable = false) public Long userId;
    @Column(nullable = false) public Instant expiresAt;
    @Column(nullable = false) public boolean revoked;
}
