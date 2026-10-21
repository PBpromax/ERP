package com.company.oms.config;

import com.company.oms.common.ApiResponse;
import com.company.oms.system.FieldPermissionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;

/**
 * Backend-side field masking. The frontend also hides fields, but backend masking prevents leakage by direct API calls.
 */
@ControllerAdvice
public class FieldMaskingAdvice implements ResponseBodyAdvice<Object> {
    private final FieldPermissionService fieldPermissionService;
    private final Set<String> moneyFields;

    public FieldMaskingAdvice(FieldPermissionService fieldPermissionService,
                              @Value("${oms.field-permission.money-fields}") String moneyFieldCsv) {
        this.fieldPermissionService = fieldPermissionService;
        this.moneyFields = new HashSet<>(Arrays.asList(moneyFieldCsv.split(",")));
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (request.getURI().getPath().contains("/auth/login") || fieldPermissionService.canReadMoney()) {
            return body;
        }
        mask(body, Collections.newSetFromMap(new IdentityHashMap<>()));
        return body;
    }

    private void mask(Object obj, Set<Object> seen) {
        if (obj == null || seen.contains(obj)) return;
        if (obj instanceof String || obj instanceof Number || obj instanceof Boolean || obj instanceof Enum<?>) return;
        seen.add(obj);

        if (obj instanceof ApiResponse<?> apiResponse) {
            mask(apiResponse.data, seen);
            return;
        }
        if (obj instanceof Collection<?> c) {
            for (Object item : c) mask(item, seen);
            return;
        }
        if (obj instanceof Map<?, ?> m) {
            for (Object value : m.values()) mask(value, seen);
            return;
        }

        Class<?> clazz = obj.getClass();
        if (clazz.getName().startsWith("java.")) return;
        for (Field f : clazz.getFields()) {
            try {
                Object value = f.get(obj);
                if (moneyFields.contains(f.getName()) && (value == null || value instanceof BigDecimal)) {
                    f.set(obj, null);
                } else {
                    mask(value, seen);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
