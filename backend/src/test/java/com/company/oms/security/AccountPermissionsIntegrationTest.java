package com.company.oms.security;

import com.company.oms.system.*;
import com.company.oms.masterdata.*;
import com.company.oms.finance.*;
import com.company.oms.quality.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.datasource.url=jdbc:h2:mem:account-permissions;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class AccountPermissionsIntegrationTest {
    @LocalServerPort int port;
    @Autowired SysUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired MenuCatalog catalog;
    @Autowired LoginSessionRepository sessions;
    @Autowired ObjectMapper mapper;
    @Autowired CustomerRepository customers;
    @Autowired InvoiceRepository invoices;
    @Autowired AttachmentRepository attachments;
    @Autowired com.company.oms.sales.SalesOrderRepository orders;
    @Autowired com.company.oms.sales.SalesOrderItemRepository orderItems;
    final HttpClient client = HttpClient.newHttpClient();

    SysUser account(String mode, boolean owner, String... codes) {
        SysUser user = new SysUser(); user.username = "test" + UUID.randomUUID().toString().replace("-", "");
        user.realName = "测试人员"; user.passwordHash = passwords.encode("123456"); user.enabled = true;
        user.permissionMode = mode; user.owner = owner; user.menuCodes.addAll(List.of(codes));
        // Deliberately keep a powerful legacy role to prove it cannot bypass the new permissions.
        user.roleCodes = "ADMIN";
        return users.saveAndFlush(user);
    }
    HttpResponse<String> request(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api" + path))
            .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()).path("data"); }
    String login(SysUser user) throws Exception { return login(user.username, "123456"); }
    String login(String username, String password) throws Exception {
        var response = request("POST", "/auth/login", null, Map.of("username", username, "password", password));
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("passwordHash"));
        return data(response).path("token").asText();
    }
    Map<String, Object> edit(SysUser user, String mode, String... codes) {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("username", user.username); body.put("realName", user.realName);
        body.put("enabled", true); body.put("password", ""); body.put("permissionMode", mode); body.put("menuCodes", List.of(codes));
        return body;
    }
    com.company.oms.sales.SalesOrder orderFixture() {
        var order = new com.company.oms.sales.SalesOrder();
        order.orderNo = "PRICE-" + UUID.randomUUID(); order.customerName = "价格权限验收";
        order.orderDate = java.time.LocalDate.of(2026, 10, 16); order.status = "待审核";
        order.totalAmount = new java.math.BigDecimal("2469.13578");
        order.taxAmount = new java.math.BigDecimal("12.34567"); order.paidAmount = java.math.BigDecimal.ZERO;
        orders.saveAndFlush(order);
        for (int index = 0; index < 2; index++) {
            var item = new com.company.oms.sales.SalesOrderItem();
            item.orderId = order.id; item.productCode = "PERMISSION-" + index; item.productName = "验收产品";
            item.unit = "条"; item.qty = new java.math.BigDecimal("100"); item.shippedQty = java.math.BigDecimal.ZERO;
            item.unitPrice = new java.math.BigDecimal("12.3456789"); item.amount = new java.math.BigDecimal("1234.56789");
            item.dueDate = order.orderDate.plusDays(3); orderItems.saveAndFlush(item);
        }
        return order;
    }
    void assertNoOrderPrices(JsonNode node) {
        for (String field : List.of("unitPrice", "unitPriceText", "amount", "totalAmount", "taxAmount", "paidAmount"))
            assertFalse(node.has(field), "Leaked " + field + ": " + node);
        if (node.isContainerNode()) for (JsonNode child : node) assertNoOrderPrices(child);
    }
    @Test void orderPricesAreRedactedFromEveryReadRouteWithoutMutatingSharedPages() throws Exception {
        var order = orderFixture();
        var viewer = account("CUSTOM", false, "orders"); viewer.orderPricesVisible = false; users.saveAndFlush(viewer);
        String hidden = login(viewer), visible = login(account("CUSTOM", false, "orders"));
        String page = "/sales/orders/page?keyword=" + order.orderNo;
        var first = data(request("GET", page, visible, null));
        assertEquals("12.3456789", first.path("items").get(0).path("productItems").get(0).path("unitPrice").decimalValue().stripTrailingZeros().toPlainString());
        for (String path : List.of(page, "/sales/orders/simple", "/sales/orders", "/sales/orders/" + order.id,
                "/sales/orders/" + order.id + "/detail", "/sales/order-items", "/sales/order-items/" + orderItems.findByOrderIdAndDeletedFalse(order.id).get(0).id)) {
            var response = request("GET", path, hidden, null);
            assertEquals(200, response.statusCode(), path + ": " + response.body());
            assertNoOrderPrices(data(response));
        }
        assertEquals(first, data(request("GET", page, visible, null)), "Hidden reads must not modify the cached privileged page");
        assertEquals(2, data(request("GET", "/sales/orders/" + order.id + "/detail", hidden, null)).path("items").size());
    }
    @Test void orderPriceRevocationIsImmediateForExistingSessionsAndBlocksAllMutations() throws Exception {
        var order = orderFixture(); var viewer = account("ALL_BUSINESS", false);
        String token = login(viewer), manager = login(account("ALL_BUSINESS", false, "users"));
        var payload = edit(viewer, "ALL_BUSINESS"); payload.put("orderPricesVisible", false);
        var saved = request("PUT", "/system/users/" + viewer.id, manager, payload);
        assertEquals(200, saved.statusCode(), saved.body()); assertFalse(data(saved).path("orderPricesVisible").asBoolean());
        assertFalse(data(request("GET", "/auth/me", token, null)).path("user").path("orderPricesVisible").asBoolean());
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            for (String path : List.of("/sales/orders", "/sales/orders/" + order.id, "/sales/orders/simple", "/sales/orders/simple/" + order.id,
                    "/sales/orders/with-items", "/sales/orders/" + order.id + "/approve", "/sales/order-items", "/sales/order-items/1")) {
                assertEquals(403, request(method, path, token, Map.of()).statusCode(), method + " " + path);
            }
        }
        assertEquals(403, request("POST", "/sales/quotations/1/convert", token, Map.of()).statusCode());
        assertEquals(200, request("GET", "/sales/quotations", token, null).statusCode());
        assertEquals(200, request("GET", "/finance/collections/companies", token, null).statusCode());
        assertFalse(Boolean.TRUE.equals(orders.findById(order.id).orElseThrow().deleted));
        assertEquals(0, orders.findById(order.id).orElseThrow().totalAmount.compareTo(new java.math.BigDecimal("2469.13578")));
        // Old clients omitting the field must not grant it back.
        assertEquals(200, request("PUT", "/system/users/" + viewer.id, manager, edit(viewer, "CUSTOM", "orders")).statusCode());
        assertFalse(data(request("GET", "/auth/me", token, null)).path("user").path("orderPricesVisible").asBoolean());
        payload.put("orderPricesVisible", true);
        assertEquals(200, request("PUT", "/system/users/" + viewer.id, manager, payload).statusCode());
        assertTrue(data(request("GET", "/auth/me", token, null)).path("user").path("orderPricesVisible").asBoolean());
        assertTrue(data(request("GET", "/sales/orders/" + order.id + "/detail", token, null)).path("order").has("totalAmount"));
        assertEquals(200, request("POST", "/sales/orders", token, Map.of("orderNo", "WRITE-" + UUID.randomUUID())).statusCode());
    }
    @Test void orderPricePermissionDefaultsToExistingBehaviorAndCannotDisableTheOwner() throws Exception {
        var manager = account("ALL_BUSINESS", false, "users"); String managerToken = login(manager);
        String username = "new" + UUID.randomUUID().toString().replace("-", "");
        var created = request("POST", "/system/users", managerToken, Map.of("username", username,
                "realName", "新用户", "password", "123456", "permissionMode", "CUSTOM", "menuCodes", List.of("orders")));
        assertEquals(200, created.statusCode(), created.body()); assertTrue(data(created).path("orderPricesVisible").asBoolean());
        var owner = account("ALL_BUSINESS", true, "users"); String ownerToken = login(owner);
        var body = edit(owner, "ALL_BUSINESS", "users"); body.put("orderPricesVisible", false);
        assertEquals(400, request("PUT", "/system/users/" + owner.id, ownerToken, body).statusCode());
        assertTrue(data(request("GET", "/auth/me", ownerToken, null)).path("user").path("orderPricesVisible").asBoolean());
    }
    @Test void hiddenOrderExportContainsQuantitiesButNoPriceValuesOrFormulas() throws Exception {
        var order = orderFixture(); var viewer = account("CUSTOM", false, "orders");
        viewer.orderPricesVisible = false; users.saveAndFlush(viewer);
        for (boolean includePrices : List.of(false, true)) {
            String token = includePrices ? login(account("CUSTOM", false, "orders")) : login(viewer);
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/sales/orders/" + order.id + "/export"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.body()))) {
                var sheet = workbook.getSheetAt(0);
                assertEquals(100, sheet.getRow(11).getCell(4).getNumericCellValue());
                if (includePrices) assertEquals(12.3456789, sheet.getRow(11).getCell(5).getNumericCellValue());
                else for (var row : sheet) {
                    if (row.getRowNum() < 10) continue;
                    for (int column : List.of(5, 6)) {
                        var cell = row.getCell(column);
                        assertTrue(cell == null || cell.getCellType() == org.apache.poi.ss.usermodel.CellType.BLANK);
                    }
                }
            }
        }
    }
    @Test void missingLoginFieldsReturnReadablePromptsWithoutFrameworkDetails() throws Exception {
        for (String password : List.of("", "   ")) {
            var response = request("POST", "/auth/login", null, Map.of("username", "someone", "password", password));
            assertEquals(400, response.statusCode());
            assertEquals("请输入密码", mapper.readTree(response.body()).path("message").asText());
            assertFalse(response.body().contains("loginRequest"));
            assertFalse(response.body().contains("rejected value"));
        }
        for (Map<String, String> body : List.of(Map.of("username", "", "password", "123456"), Map.of("username", "   ", "password", "123456"), Map.of("password", "123456"))) {
            var response = request("POST", "/auth/login", null, body);
            assertEquals(400, response.statusCode());
            assertEquals("请输入登录账户", mapper.readTree(response.body()).path("message").asText());
        }
        var missingPassword = request("POST", "/auth/login", null, Map.of("username", "someone"));
        assertEquals(400, missingPassword.statusCode());
        assertEquals("请输入密码", mapper.readTree(missingPassword.body()).path("message").asText());
        var both = request("POST", "/auth/login", null, Map.of());
        assertEquals(400, both.statusCode());
        String prompt = mapper.readTree(both.body()).path("message").asText();
        assertTrue(prompt.contains("请输入登录账户")); assertTrue(prompt.contains("请输入密码"));
        assertFalse(prompt.contains("com.company"));
    }
    @Test void limitedAccountCanUsePagesButCannotBypassMissingMenusOrLegacyRoles() throws Exception {
        var user = account("CUSTOM", false, "customers", "products", "quotations", "orders", "deliveries", "statements", "invoices");
        String token = login(user);
        for (String path : List.of("/master/customers", "/master/products", "/sales/quotations", "/sales/orders/page", "/sales/deliveries/invoice-board/page", "/finance/statements/customer-board", "/finance/invoices/statement-board"))
            assertEquals(200, request("GET", path, token, null).statusCode(), path);
        for (String path : List.of("/reports/dashboard", "/reports/collection-summary", "/finance/receipts", "/finance/collections/companies", "/system/users", "/system/roles", "/inventory/product-stocks", "/files"))
            assertEquals(403, request("GET", path, token, null).statusCode(), path);
        assertEquals(401, request("GET", "/master/customers", null, null).statusCode());
        assertEquals(403, request("POST", "/finance/receipts", token, Map.of()).statusCode());
        var none = account("CUSTOM", false);
        String noneToken = login(none);
        assertEquals(200, request("GET", "/auth/me", noneToken, null).statusCode());
        assertEquals(403, request("GET", "/master/customers", noneToken, null).statusCode());
    }
    @Test void fullBusinessDoesNotGrantManagementAndFutureBusinessIsAutomatic() throws Exception {
        var user = account("ALL_BUSINESS", false);
        var futureCatalog = new ArrayList<>(catalog.entries());
        futureCatalog.add(new MenuCatalog.Entry("future", "新分类", "未来功能", "/future", true));
        futureCatalog.add(new MenuCatalog.Entry("future-system", "", "未来管理", "/future-system", false));
        assertTrue(catalog.effective(user, futureCatalog).contains("future"));
        assertTrue(catalog.effective(user).containsAll(Set.of("material-entry", "material-entry-in", "material-entry-out")));
        assertFalse(catalog.effective(user, futureCatalog).contains("users"));
        assertFalse(catalog.effective(user, futureCatalog).contains("future-system"));
        assertFalse(catalog.effective(account("CUSTOM", false, "orders"), futureCatalog).contains("future"));
        assertTrue(catalog.effective(account("ALL_BUSINESS", true), futureCatalog).contains("users"));
        String token = login(user);
        assertEquals(200, request("GET", "/reports/dashboard", token, null).statusCode());
        assertEquals(403, request("GET", "/system/users", token, null).statusCode());
    }
    @Test void materialMenuAndDashboardWarningsHaveSeparatePermissions() throws Exception {
        String materialToken = login(account("CUSTOM", false, "materials"));
        String dashboardToken = login(account("CUSTOM", false, "dashboard"));
        String unrelatedToken = login(account("CUSTOM", false, "products"));
        assertEquals(200, request("GET", "/master/materials", materialToken, null).statusCode());
        assertEquals(403, request("GET", "/reports/material-warnings", materialToken, null).statusCode());
        assertEquals(200, request("GET", "/reports/material-warnings", dashboardToken, null).statusCode());
        assertEquals(403, request("GET", "/master/materials", dashboardToken, null).statusCode());
        assertEquals(403, request("POST", "/master/materials", unrelatedToken, Map.of()).statusCode());
        var seed = data(request("GET", "/master/materials", materialToken, null)).get(0).path("id").asLong();
        for (String suffix : List.of("", "/daily-records", "/ledger", "/trend"))
            assertEquals(403, request("GET", "/master/materials/" + seed + suffix, unrelatedToken, null).statusCode());
        assertEquals(403, request("PUT", "/master/materials/" + seed + "/daily-records/2026-10-14", unrelatedToken, Map.of()).statusCode());
        assertEquals(403, request("POST", "/master/materials/" + seed + "/adjustments", unrelatedToken, Map.of()).statusCode());
        String fullToken = login(account("ALL_BUSINESS", false));
        assertEquals(200, request("GET", "/master/materials", fullToken, null).statusCode());
        assertTrue(data(request("GET", "/auth/me", fullToken, null)).path("user").path("effectiveMenuCodes").toString().contains("materials"));
    }
    @Test void newlySeededAdminCanManageMaterialsAndUsers() throws Exception {
        SysUser admin = users.findByUsernameAndDeletedFalse("admin").orElseThrow();
        assertTrue(admin.owner);
        assertTrue(catalog.effective(admin).containsAll(Set.of("materials", "dashboard", "users")));
    }
    @Test void privateAdministratorRemainsFullyAuthorizedButOtherManagersCannotReadOrModifyTheAccount() throws Exception {
        var regularOwner = account("ALL_BUSINESS", true, "users");
        var privateOwner = account("ALL_BUSINESS", false, "users");
        privateOwner.managementPrivate = true; users.saveAndFlush(privateOwner);
        String regularToken = login(regularOwner), privateToken = login(privateOwner);
        var list = request("GET", "/system/users", regularToken, null);
        assertEquals(200, list.statusCode());
        assertFalse(list.body().contains(privateOwner.username));
        assertTrue(list.body().contains(regularOwner.username));
        String target = "/system/users/" + privateOwner.id;
        for (String method : List.of("GET", "PUT", "DELETE")) {
            var response = request(method, target, regularToken,
                    method.equals("PUT") ? edit(privateOwner, "ALL_BUSINESS", "users") : null);
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("用户不存在"));
        }
        var privateMe = data(request("GET", "/auth/me", privateToken, null)).path("user");
        assertEquals(catalog.entries().size(), privateMe.path("effectiveMenuCodes").size());
        assertFalse(privateMe.path("owner").asBoolean());
        assertEquals(200, request("GET", target, privateToken, null).statusCode());
        assertTrue(request("GET", "/system/users", privateToken, null).body().contains(privateOwner.username));
        assertEquals(200, request("GET", "/reports/collection-summary", privateToken, null).statusCode());
        assertEquals(200, request("GET", "/master/materials", privateToken, null).statusCode());
        assertTrue(users.findById(privateOwner.id).orElseThrow().managementPrivate);
        assertFalse(users.findById(privateOwner.id).orElseThrow().deleted);
        // Ordinary user-management payloads cannot turn a visible account into a private owner.
        var payload = edit(regularOwner, "ALL_BUSINESS", "users"); payload.put("managementPrivate", true);
        assertEquals(200, request("PUT", "/system/users/" + regularOwner.id, regularToken, payload).statusCode());
        assertFalse(users.findById(regularOwner.id).orElseThrow().managementPrivate);
    }

    @Test void grantedManagerCanCreateEditAndDeleteButOwnerIsProtected() throws Exception {
        var owner = account("ALL_BUSINESS", true, "users");
        var manager = account("CUSTOM", false, "users");
        var target = account("CUSTOM", false, "customers");
        String token = login(manager);
        var response = request("GET", "/system/users", token, null);
        assertEquals(200, response.statusCode()); assertFalse(response.body().contains("passwordHash"));
        assertEquals(403, request("PUT", "/system/users/" + owner.id, token, edit(owner, "ALL_BUSINESS", "users")).statusCode());
        assertEquals(400, request("DELETE", "/system/users/" + owner.id, token, null).statusCode());
        var body = edit(target, "CUSTOM", "orders"); body.put("realName", "新的姓名");
        assertEquals(200, request("PUT", "/system/users/" + target.id, token, body).statusCode());
        assertEquals("新的姓名", users.findById(target.id).orElseThrow().realName);
        String targetToken = login(target);
        assertEquals(200, request("GET", "/sales/orders/page", targetToken, null).statusCode());
        var create = edit(target, "CUSTOM", "users");
        create.put("username", "created" + UUID.randomUUID().toString().replace("-", "")); create.put("password", "123456");
        var created = request("POST", "/system/users", token, create); assertEquals(200, created.statusCode(), created.body());
        assertEquals(400, request("POST", "/system/users", token, create).statusCode());
        assertEquals(200, request("DELETE", "/system/users/" + target.id, token, null).statusCode());
        assertTrue(users.findById(target.id).orElseThrow().deleted);
        assertEquals(401, request("GET", "/auth/me", targetToken, null).statusCode());
        assertEquals(400, request("POST", "/auth/login", null, Map.of("username", target.username, "password", "123456")).statusCode());
        var ownerBody = edit(owner, "CUSTOM");
        assertEquals(400, request("PUT", "/system/users/" + owner.id, login(owner), ownerBody).statusCode());
    }
    @Test void permissionUpdatesTakeEffectOnExistingSessionsAndReferencesDoNotGrantCrud() throws Exception {
        var manager = account("CUSTOM", false, "users"); var target = account("CUSTOM", false, "orders");
        String token = login(target);
        assertEquals(200, request("GET", "/references/customers", token, null).statusCode());
        assertEquals(200, request("GET", "/references/products", token, null).statusCode());
        assertEquals(403, request("GET", "/master/customers", token, null).statusCode());
        assertEquals(403, request("POST", "/master/products", token, Map.of()).statusCode());
        assertEquals(200, request("PUT", "/system/users/" + target.id, login(manager), edit(target, "CUSTOM", "customers")).statusCode());
        assertEquals(403, request("GET", "/sales/orders/page", token, null).statusCode());
        assertEquals(200, request("GET", "/master/customers", token, null).statusCode());
        var changed = data(request("GET", "/auth/me", token, null)).path("user").path("effectiveMenuCodes");
        assertEquals("customers", changed.get(0).asText());
        Customer customer = new Customer(); customer.name = "search-visible-" + UUID.randomUUID(); customer.enabled = true; customers.saveAndFlush(customer);
        var search = request("GET", "/search?keyword=search-visible", token, null);
        assertEquals(200, search.statusCode());
        for (JsonNode row : data(search)) assertEquals("/customers", row.path("path").asText());
    }
    @Test void logoutPasswordChangeRenameAndAdminResetRevokeCorrectSessions() throws Exception {
        var user = account("CUSTOM", false, "customers"); var manager = account("CUSTOM", false, "users");
        String first = login(user), second = login(user);
        assertEquals(200, request("POST", "/auth/logout", first, Map.of()).statusCode());
        assertEquals(401, request("GET", "/auth/me", first, null).statusCode());
        assertEquals(200, request("GET", "/auth/me", second, null).statusCode());
        var change = new LinkedHashMap<>(Map.of("oldPassword", "wrong", "newPassword", "654321", "confirmPassword", "654321"));
        assertEquals(400, request("POST", "/auth/change-password", second, change).statusCode());
        change.put("oldPassword", "123456"); change.put("confirmPassword", "different");
        assertEquals(400, request("POST", "/auth/change-password", second, change).statusCode());
        change.put("confirmPassword", "654321");
        String third = login(user);
        assertEquals(200, request("POST", "/auth/change-password", second, change).statusCode());
        assertEquals(401, request("GET", "/auth/me", second, null).statusCode());
        assertEquals(401, request("GET", "/auth/me", third, null).statusCode());
        assertEquals(400, request("POST", "/auth/login", null, Map.of("username", user.username, "password", "123456")).statusCode());
        String updated = login(user.username, "654321");
        var rename = edit(user, "CUSTOM", "customers"); rename.put("username", user.username + "new");
        String managerToken = login(manager);
        assertEquals(200, request("PUT", "/system/users/" + user.id, managerToken, rename).statusCode());
        assertEquals(401, request("GET", "/auth/me", updated, null).statusCode());
        String renamedToken = login(user.username + "new", "654321");
        rename.put("password", "123456");
        assertEquals(200, request("PUT", "/system/users/" + user.id, managerToken, rename).statusCode());
        assertEquals(401, request("GET", "/auth/me", renamedToken, null).statusCode());
        login(user.username + "new", "123456");
    }
    @Test void attachmentsAndSharedInvoiceStatementActionsUseTheirBusinessPermissions() throws Exception {
        var deliveryUser = account("CUSTOM", false, "deliveries");
        var invoiceUser = account("CUSTOM", false, "invoices");
        Invoice invoice = new Invoice(); invoice.invoiceNo = "ATTACHMENT-TEST"; invoices.saveAndFlush(invoice);
        Attachment attachment = new Attachment(); attachment.businessType = "INVOICE_ATTACHMENT"; attachment.businessId = invoice.id;
        attachment.fileKey = "test.txt"; attachment.originalName = "test.txt"; attachments.saveAndFlush(attachment);
        assertEquals(403, request("GET", "/files/download/" + attachment.id, login(deliveryUser), null).statusCode());
        String invoiceToken = login(invoiceUser);
        assertEquals(200, request("GET", "/files/by-business?businessType=INVOICE_ATTACHMENT&businessId=" + invoice.id, invoiceToken, null).statusCode());
        assertEquals(403, request("GET", "/finance/statements/customer-board", invoiceToken, null).statusCode());
        // Reaching validation rather than 403 proves shared detail/export/delete authorization.
        assertEquals(400, request("GET", "/finance/statements/999999/detail", invoiceToken, null).statusCode());
        assertEquals(400, request("DELETE", "/finance/statements/999999", invoiceToken, null).statusCode());
        assertEquals(403, request("PUT", "/finance/statements/999999/amount", invoiceToken, Map.of("receivableAmount", 1)).statusCode());
    }
}
