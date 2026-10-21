package com.company.oms.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Locale;

@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ServerTimingAdvice implements ResponseBodyAdvice<Object> {
    private final HttpServletRequest servletRequest;

    public ServerTimingAdvice(HttpServletRequest servletRequest) {
        this.servletRequest = servletRequest;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        Object startedAt = servletRequest.getAttribute(RequestTimingFilter.STARTED_AT_ATTRIBUTE);
        if (startedAt instanceof Long startedAtNanos) {
            double elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000.0;
            response.getHeaders().set("Server-Timing",
                    "app;dur=" + String.format(Locale.ROOT, "%.1f", elapsedMs));
        }
        return body;
    }
}
