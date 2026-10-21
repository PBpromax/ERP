package com.company.oms.security;

import com.company.oms.common.BusinessException;
import com.company.oms.system.SysUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class TokenService {
    @Value("${oms.token-secret}") private String secret;
    private final LoginSessionRepository sessions;
    public TokenService(LoginSessionRepository sessions) { this.sessions = sessions; }
    @PostConstruct
    void validateSecret() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("OMS_TOKEN_SECRET 必须配置且至少包含32字节");
        }
    }
    public String createToken(SysUser user) {
        LoginSession session = new LoginSession();
        session.id = UUID.randomUUID().toString(); session.userId = user.id;
        session.expiresAt = Instant.now().plusSeconds(24 * 60 * 60);
        sessions.save(session);
        String payload = "v2:" + user.id + ":" + session.id + ":" + session.expiresAt.getEpochSecond();
        return Base64.getUrlEncoder().withoutPadding().encodeToString((payload + ":" + sign(payload)).getBytes(StandardCharsets.UTF_8));
    }
    public LoginSession parseSession(String token) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split(":");
            if (parts.length != 5 || !parts[0].equals("v2")) throw new BusinessException("请重新登录");
            String payload = String.join(":", Arrays.copyOf(parts, 4));
            if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), parts[4].getBytes(StandardCharsets.UTF_8)))
                throw new BusinessException("登录凭证无效");
            LoginSession session = sessions.findById(parts[2]).orElseThrow(() -> new BusinessException("登录已失效"));
            if (session.revoked || !session.userId.equals(Long.valueOf(parts[1]))
                    || session.expiresAt.getEpochSecond() != Long.parseLong(parts[3]) || !session.expiresAt.isAfter(Instant.now()))
                throw new BusinessException("登录已失效");
            return session;
        } catch (Exception ex) { throw new BusinessException("登录已失效，请重新登录"); }
    }
    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
