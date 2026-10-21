package com.company.oms.config;

import com.company.oms.sales.SalesOrderController;
import com.company.oms.sales.SalesOrderItemController;
import com.company.oms.system.MenuPermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Set;

/** Apply the account's live order permission after shared cached results have been read. */
@ControllerAdvice(assignableTypes = {SalesOrderController.class, SalesOrderItemController.class})
public class OrderPriceMaskingAdvice implements ResponseBodyAdvice<Object> {
    private static final Set<String> PRICE_FIELDS = Set.of(
            "unitPrice", "unitPriceText", "amount", "totalAmount", "taxAmount", "paidAmount");
    private final MenuPermissionService permissions;
    private final ObjectMapper mapper;

    public OrderPriceMaskingAdvice(MenuPermissionService permissions, ObjectMapper mapper) {
        this.permissions = permissions;
        this.mapper = mapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body == null || permissions.canReadOrderPrices()) return body;
        // Never redact the original entity/map: it may be used by another account or a shared cache.
        JsonNode copy = mapper.valueToTree(body);
        redact(copy);
        return copy;
    }

    private void redact(JsonNode node) {
        if (node instanceof ObjectNode object) object.remove(PRICE_FIELDS);
        if (node.isContainerNode()) node.forEach(this::redact);
    }
}
