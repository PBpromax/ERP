package com.company.oms.config;

import com.company.oms.common.AuditLogAction;
import com.company.oms.system.AuditLog;
import com.company.oms.system.AuditLogRepository;
import com.company.oms.system.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class AuditAspect {
    private final AuditLogRepository auditLogRepository;
    private final ObjectProvider<HttpServletRequest> requestProvider;

    public AuditAspect(AuditLogRepository auditLogRepository, ObjectProvider<HttpServletRequest> requestProvider) {
        this.auditLogRepository = auditLogRepository;
        this.requestProvider = requestProvider;
    }

    @Around("@annotation(action)")
    public Object around(ProceedingJoinPoint pjp, AuditLogAction action) throws Throwable {
        Object result;
        try {
            result = pjp.proceed();
            save(action, "SUCCESS", null);
            return result;
        } catch (Throwable ex) {
            save(action, "FAIL", ex.getMessage());
            throw ex;
        }
    }

    private void save(AuditLogAction action, String result, String error) {
        AuditLog log = new AuditLog();
        log.username = CurrentUser.username();
        log.module = action.module();
        log.action = action.action();
        HttpServletRequest request = requestProvider.getIfAvailable();
        log.businessKey = request == null ? "BATCH" : request.getRequestURI();
        log.detail = result + (error == null ? "" : ": " + error);
        log.ip = request == null ? "LOCAL" : request.getRemoteAddr();
        auditLogRepository.save(log);
    }
}
