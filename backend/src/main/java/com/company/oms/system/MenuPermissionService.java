package com.company.oms.system;

import com.company.oms.security.AccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class MenuPermissionService {
    public AccountPrincipal principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AccountPrincipal p ? p : null;
    }
    public boolean has(String... codes) {
        var p = principal();
        return p != null && Arrays.stream(codes).anyMatch(p.menuCodes()::contains);
    }
    public boolean canReadOrderPrices() {
        var p = principal();
        return p != null && p.orderPricesVisible();
    }
    private boolean under(String path, String prefix) { return path.equals(prefix) || path.startsWith(prefix + "/"); }
    public boolean allows(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();
        if (Set.of("/auth/me", "/auth/logout", "/auth/change-password", "/auth/field-permissions").contains(path)) return principal() != null;
        if (under(path, "/system/users")) return has("users");
        if (under(path, "/master/customers")) return has("customers");
        if (under(path, "/master/products")) return has("products");
        if (under(path, "/master/materials")) return has("materials");
        if (under(path, "/material-entry")) return has("material-entry-in", "material-entry-out");
        if (Set.of("/references/customers", "/references/products").contains(path))
            return method.equals("GET") && has("customers", "products", "quotations", "orders", "deliveries", "statements", "invoices");
        if (under(path, "/sales/quotations") || under(path, "/sales/quotation-items")) {
            if (path.matches("/sales/quotations/\\d+/convert") && !method.equals("GET") && !canReadOrderPrices()) return false;
            return has("quotations");
        }
        if (under(path, "/sales/orders") || under(path, "/sales/order-items")) return has("orders") && (Set.of("GET", "HEAD").contains(method) || canReadOrderPrices());
        if (under(path, "/sales/deliveries") || under(path, "/sales/delivery-items")) return has("deliveries");
        if (under(path, "/finance/statements")) {
            boolean shared = (method.equals("GET") && path.matches("/finance/statements/\\d+(?:/(?:detail|export))?"))
                || (method.equals("DELETE") && path.matches("/finance/statements/\\d+"));
            boolean receiptAmount = method.equals("PUT") && path.matches("/finance/statements/\\d+/amount");
            return has("statements") || (shared && has("invoices", "receipts")) || (receiptAmount && has("receipts"));
        }
        if (under(path, "/finance/invoices")) return has("invoices");
        if (under(path, "/finance/receipts") || under(path, "/finance/collections")
                || under(path, "/finance/customer-summaries") || under(path, "/finance/ar-balance-snapshots")) return has("receipts");
        if (under(path, "/reports")) return has("dashboard");
        if (path.equals("/search") || path.equals("/qrcode")) return has("customers", "products", "quotations", "orders", "deliveries", "statements", "invoices");
        if (under(path, "/files")) {
            // Object/business authorization is additionally enforced in AttachmentController.
            return has("customers", "deliveries", "statements", "invoices") &&
                (path.equals("/files/upload") || path.equals("/files/by-business")
                    || path.matches("/files/download/\\d+") || (method.equals("DELETE") && path.matches("/files/\\d+")));
        }
        // Unregistered/hidden modules are not implicitly enabled by legacy roles.
        return false;
    }
}
