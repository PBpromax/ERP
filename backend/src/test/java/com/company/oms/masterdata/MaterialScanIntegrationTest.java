package com.company.oms.masterdata;

import com.company.oms.common.BusinessException;
import com.company.oms.inventory.MaterialStockRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import com.company.oms.system.*;
import com.company.oms.security.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:material-scan;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MaterialScanIntegrationTest {
    @Autowired MaterialManagementService service;
    @Autowired MaterialScanEntryRepository entries;
    @Autowired MaterialRepository materials;
    @Autowired MaterialStockRepository stocks;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SysUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired TokenService tokens;
    @Autowired MenuCatalog catalog;
    SysUser employee;
    @BeforeEach void employeeIdentity() {
        employee = employee("扫码员工", "material-entry");
        identify(employee);
    }
    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }
    SysUser employee(String name, String... codes) {
        SysUser u = new SysUser(); u.username = "worker" + UUID.randomUUID().toString().replace("-", "");
        u.realName = name; u.enabled = true; u.passwordHash = passwords.encode("scanpass123");
        u.permissionMode = "CUSTOM"; u.menuCodes.addAll(List.of(codes)); return users.saveAndFlush(u);
    }
    void identify(SysUser u) {
        var principal = new AccountPrincipal(u.id, u.username, "test", catalog.effective(u));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                u.menuCodes.stream().map(c -> new SimpleGrantedAuthority("MENU_" + c)).toList()));
    }
    String bearer(SysUser u) { return "Bearer " + tokens.createToken(u); }

    MaterialManagementService.MaterialView create() {
        var r = new MaterialManagementService.MaterialRequest();
        r.name = "扫码原料-" + UUID.randomUUID(); r.category = "颗粒"; r.unit = "公斤";
        r.initialQty = new BigDecimal("100"); r.safetyStock = new BigDecimal("95");
        return service.create(r);
    }
    MaterialManagementService.ScanRequest request(String direction, String qty) {
        return new MaterialManagementService.ScanRequest(UUID.randomUUID().toString(), direction, new BigDecimal(qty));
    }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postRequest(String path) { return post(path).servletPath(path); }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder getRequest(String path) { return get(path).servletPath(path); }
    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putRequest(String path) { return put(path).servletPath(path); }
    void qty(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }

    @Test void scansAddToManualTotalsAndLedgerAndCanBeRetriedWithoutDoubleCounting() {
        var m = create(); String token = service.scanCode(m.id()).token();
        assertEquals(token, service.scanCode(m.id()).token());
        var manual = new MaterialManagementService.DailyRequest();
        manual.purchaseQty = new BigDecimal("10"); manual.productionQty = new BigDecimal("3"); manual.lossQty = new BigDecimal("2");
        manual.stockVersion = m.stockVersion(); manual.versionNo = -1L;
        service.saveDaily(m.id(), service.today(), manual);
        var scan = request("OUT", "10.125");
        var receipt = service.registerScan(token, scan);
        assertEquals(receipt, service.registerScan(token, scan));
        service.registerScan(token, request("IN", "2.5"));
        qty("97.375", service.get(m.id()).currentQty());
        var day = service.dailyRecord(m.id(), service.today());
        qty("12.5", day.purchaseQty); qty("13.125", day.productionQty); qty("2", day.lossQty);
        var ledger = service.ledger(m.id(), null, null, 1, 100).items();
        assertEquals(6, ledger.size());
        assertEquals(2, ledger.stream().filter(t -> employee.id.equals(t.operatorUserId)).count());
        var scanned = ledger.stream().filter(t -> employee.id.equals(t.operatorUserId)).toList();
        assertTrue(scanned.stream().allMatch(t -> employee.realName.equals(t.operator) && employee.username.equals(t.operatorUsername)));
        assertEquals(employee.realName, receipt.operator()); assertEquals(employee.username, receipt.operatorUsername());
        assertThrows(BusinessException.class, () -> service.registerScan(token,
                new MaterialManagementService.ScanRequest(scan.requestId(), "OUT", BigDecimal.ONE)));
    }

    @Test void invalidQuantitiesAndInsufficientOrLockedStockRollBack() {
        var m = create(); String token = service.scanCode(m.id()).token();
        for (String value : List.of("0", "-1", "0.0001", "100.001", "1000000000000000")) {
            assertThrows(BusinessException.class, () -> service.registerScan(token, request("OUT", value)));
            qty("100", service.get(m.id()).currentQty());
            assertNull(service.dailyRecord(m.id(), service.today()));
        }
        var stock = stocks.findByMaterialIdAndDeletedFalse(m.id()).orElseThrow();
        stock.lockedQty = new BigDecimal("90"); stocks.saveAndFlush(stock);
        assertThrows(BusinessException.class, () -> service.registerScan(token, request("OUT", "11")));
        assertThrows(BusinessException.class, () -> service.registerScan(token, request("BAD", "1")));
        assertThrows(BusinessException.class, () -> service.registerScan(token,
                new MaterialManagementService.ScanRequest("bad-id", "IN", BigDecimal.ONE)));
        assertThrows(BusinessException.class, () -> service.registerScan(token,
                new MaterialManagementService.ScanRequest(UUID.randomUUID().toString(), "IN", null)));
        assertEquals(1, service.ledger(m.id(), null, null, 1, 100).total());
    }

    @Test void concurrentDifferentRequestsAccumulateAndConcurrentRetriesOnlyCountOnce() throws Exception {
        var m = create(); String token = service.scanCode(m.id()).token();
        var retry = request("OUT", "3");
        var requests = List.of(request("IN", "10"), request("OUT", "2"), retry, retry);
        var pool = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var r : requests) futures.add(pool.submit(() -> { start.await(); identify(employee); try { return service.registerScan(token, r); } finally { SecurityContextHolder.clearContext(); } }));
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
            qty("105", service.get(m.id()).currentQty());
            qty("10", service.dailyRecord(m.id(), service.today()).purchaseQty);
            qty("5", service.dailyRecord(m.id(), service.today()).productionQty);
            assertEquals(4, service.ledger(m.id(), null, null, 1, 100).total());
        } finally { pool.shutdownNow(); }
    }

    @Test void disabledIncompleteAndDeletedMaterialsCannotRegister() {
        var m = create(); String token = service.scanCode(m.id()).token();
        assertEquals(token, service.scanCode(m.id()).token());
        var entity = materials.findById(m.id()).orElseThrow(); entity.enabled = false; entity = materials.saveAndFlush(entity);
        assertThrows(BusinessException.class, () -> service.scanMaterial(token));
        assertThrows(BusinessException.class, () -> service.registerScan(token, request("IN", "1")));
        entity.enabled = true; entity.unit = null; entity = materials.saveAndFlush(entity);
        assertThrows(BusinessException.class, () -> service.registerScan(token, request("IN", "1")));
        entity.unit = "公斤"; entity.deleted = true; materials.saveAndFlush(entity);
        assertThrows(BusinessException.class, () -> service.registerScan(token, request("IN", "1")));
    }

    @Test void loginAndPermissionAreRequiredAndEmployeeCannotAccessManagement() throws Exception {
        var m = create(); String token = service.scanCode(m.id()).token();
        SecurityContextHolder.clearContext();
        assertEquals(401, mvc.perform(getRequest("/material-entry/" + token)).andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(postRequest("/material-entry/" + token + "/entries")
                .contentType("application/json").content(json.writeValueAsBytes(request("OUT", "1")))).andReturn().getResponse().getStatus());
        String auth = bearer(employee);
        var response = mvc.perform(getRequest("/material-entry/" + token).header("Authorization", auth)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var data = json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
        assertEquals(2, data.size()); assertEquals(m.name(), data.get("name").asText());
        response = mvc.perform(postRequest("/material-entry/" + token + "/entries").header("Authorization", auth)
                .contentType("application/json").content(json.writeValueAsBytes(Map.of("requestId", UUID.randomUUID().toString(),
                "direction", "OUT", "qty", "6", "operator", "伪造操作人", "operatorUserId", -1)))).andReturn().getResponse();
        assertEquals(200, response.getStatus()); qty("94", service.get(m.id()).currentQty());
        var txn = service.ledger(m.id(), null, null, 1, 100).items().get(0);
        assertEquals(employee.id, txn.operatorUserId); assertEquals(employee.realName, txn.operator); assertEquals(employee.username, txn.operatorUsername);
        assertEquals(employee.username, txn.createdBy); assertTrue(service.dailyRecord(m.id(), service.today()).updatedBy.contains(employee.username));
        assertEquals(403, mvc.perform(getRequest("/master/materials").header("Authorization", auth)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(getRequest("/system/users").header("Authorization", auth)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(postRequest("/master/materials/" + m.id() + "/scan-code").header("Authorization", auth)).andReturn().getResponse().getStatus());
        var noPermission = employee("无权限员工", "materials"); String denied = bearer(noPermission);
        assertEquals(403, mvc.perform(getRequest("/material-entry/" + token).header("Authorization", denied)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(postRequest("/material-entry/" + token + "/entries").header("Authorization", denied)
                .contentType("application/json").content(json.writeValueAsBytes(request("IN", "1")))).andReturn().getResponse().getStatus());
        employee.menuCodes.clear(); employee = users.saveAndFlush(employee);
        assertEquals(403, mvc.perform(postRequest("/material-entry/" + token + "/entries").header("Authorization", auth)
                .contentType("application/json").content(json.writeValueAsBytes(request("IN", "1")))).andReturn().getResponse().getStatus());
        employee.enabled = false; employee = users.saveAndFlush(employee);
        assertEquals(401, mvc.perform(getRequest("/material-entry/" + token).header("Authorization", auth)).andReturn().getResponse().getStatus());
        qty("94", service.get(m.id()).currentQty());
    }

    @Test void administratorCanCreateEmployeeGrantAndRevokeEntryPermission() throws Exception {
        var admin = employee("管理员", "users");
        var m = create(); String token = service.scanCode(m.id()).token();
        String account = "entry" + UUID.randomUUID().toString().replace("-", "");
        var body = new LinkedHashMap<String, Object>(Map.of("username", account, "realName", "张三", "password", "entry123",
                "enabled", true, "permissionMode", "CUSTOM", "menuCodes", List.of("material-entry")));
        var response = mvc.perform(postRequest("/system/users").header("Authorization", bearer(admin))
                .contentType("application/json").content(json.writeValueAsBytes(body))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var created = json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        Long id = created.path("id").asLong();
        response = mvc.perform(postRequest("/auth/login").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("username", account, "password", "entry123")))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var profile = json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertEquals(Set.of("material-entry", "material-entry-out", "material-entry-in"),
                new HashSet<>(json.convertValue(profile.path("user").path("effectiveMenuCodes"), List.class)));
        String auth = "Bearer " + profile.path("token").asText();
        response = mvc.perform(postRequest("/material-entry/" + token + "/entries").header("Authorization", auth)
                .contentType("application/json").content(json.writeValueAsBytes(request("IN", "5")))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var txn = service.ledger(m.id(), null, null, 1, 100).items().get(0);
        assertEquals(id, txn.operatorUserId); assertEquals("张三", txn.operator); assertEquals(account, txn.operatorUsername);
        body.put("password", ""); body.put("menuCodes", List.of()); body.put("realName", "张三改名");
        assertEquals(200, mvc.perform(putRequest("/system/users/" + id).header("Authorization", bearer(admin))
                .contentType("application/json").content(json.writeValueAsBytes(body))).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(getRequest("/material-entry/" + token).header("Authorization", auth)).andReturn().getResponse().getStatus());
        txn = service.ledger(m.id(), null, null, 1, 100).items().get(0);
        assertEquals("张三", txn.operator); assertEquals(id, txn.operatorUserId); qty("105", service.get(m.id()).currentQty());
    }

    @Test void selectorOnlyExposesEntryMetadataAndRequiresEntryPermission() throws Exception {
        var m = create();
        var incomplete = new Material(); incomplete.name = "待完善原料"; incomplete.enabled = true;
        incomplete = materials.saveAndFlush(incomplete);
        var disabled = create(); var disabledEntity = materials.findById(disabled.id()).orElseThrow();
        disabledEntity.enabled = false; materials.saveAndFlush(disabledEntity);
        var deleted = create(); var deletedEntity = materials.findById(deleted.id()).orElseThrow();
        deletedEntity.deleted = true; materials.saveAndFlush(deletedEntity);
        SecurityContextHolder.clearContext();
        assertEquals(401, mvc.perform(getRequest("/material-entry/materials")).andReturn().getResponse().getStatus());
        String auth = bearer(employee);
        var response = mvc.perform(getRequest("/material-entry/materials").header("Authorization", auth)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var data = json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        boolean found = false, foundIncomplete = false, foundDisabled = false;
        for (var row : data) {
            var fields = new HashSet<String>(); row.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("id", "name", "category", "unit", "ready", "enabled"), fields);
            assertNotEquals(deleted.id().longValue(), row.path("id").asLong());
            if (row.path("id").asLong() == m.id()) { found = true; assertTrue(row.path("ready").asBoolean()); }
            if (row.path("id").asLong() == incomplete.id) { foundIncomplete = true; assertFalse(row.path("ready").asBoolean()); }
            if (row.path("id").asLong() == disabled.id()) { foundDisabled = true; assertFalse(row.path("enabled").asBoolean()); }
        }
        assertTrue(found && foundIncomplete && foundDisabled);
        assertNull(materials.findById(m.id()).orElseThrow().scanToken);
        assertEquals(400, mvc.perform(getRequest("/material-entry/materials/" + incomplete.id).header("Authorization", auth)).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(getRequest("/material-entry/materials/" + disabled.id()).header("Authorization", auth)).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(getRequest("/material-entry/materials/" + deleted.id()).header("Authorization", auth)).andReturn().getResponse().getStatus());
        var denied = employee("无登记权限", "materials");
        for (String path : List.of("/material-entry/materials", "/material-entry/materials/" + m.id()))
            assertEquals(403, mvc.perform(getRequest(path).header("Authorization", bearer(denied))).andReturn().getResponse().getStatus());
    }

    @Test void selectedMaterialUsesCurrentAccountAndSharesSafeRegistrationWithQr() throws Exception {
        var m = create(); String token = service.scanCode(m.id()).token(); String auth = bearer(employee);
        String path = "/material-entry/materials/" + m.id();
        var response = mvc.perform(getRequest(path).header("Authorization", auth)).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var info = json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertEquals(2, info.size()); assertEquals(m.name(), info.path("name").asText()); assertEquals("公斤", info.path("unit").asText());
        var r = request("OUT", "2.125");
        response = mvc.perform(postRequest(path + "/entries").header("Authorization", auth)
                .contentType("application/json").content(json.writeValueAsBytes(r))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var receipt = json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertEquals(employee.realName, receipt.path("operator").asText()); assertEquals(employee.username, receipt.path("operatorUsername").asText());
        identify(employee); assertEquals(receipt.path("id").asLong(), service.registerScan(token, r).id());
        assertEquals(receipt.path("id").asLong(), service.registerMaterial(m.id(), r).id());
        service.registerMaterial(m.id(), request("IN", "4"));
        qty("101.875", service.get(m.id()).currentQty());
        qty("4", service.dailyRecord(m.id(), service.today()).purchaseQty);
        qty("2.125", service.dailyRecord(m.id(), service.today()).productionQty);
        assertEquals(3, service.ledger(m.id(), null, null, 1, 100).total());
        assertEquals(token, service.scanCode(m.id()).token());
        var denied = employee("其他权限", "materials");
        assertEquals(403, mvc.perform(postRequest(path + "/entries").header("Authorization", bearer(denied))
                .contentType("application/json").content(json.writeValueAsBytes(request("IN", "1")))).andReturn().getResponse().getStatus());
        employee.menuCodes.clear(); employee = users.saveAndFlush(employee);
        assertEquals(403, mvc.perform(postRequest(path + "/entries").header("Authorization", auth)
                .contentType("application/json").content(json.writeValueAsBytes(request("IN", "1")))).andReturn().getResponse().getStatus());
        qty("101.875", service.get(m.id()).currentQty());
    }

    @Test void inboundAndOutboundPermissionsProtectBothWebAndQrWithoutChangingStockOnDenial() throws Exception {
        var m = create(); String token = service.scanCode(m.id()).token();
        var outbound = employee("仅出库员工", "material-entry-out");
        var inbound = employee("仅入库员工", "material-entry-in");
        String outAuth = bearer(outbound), inAuth = bearer(inbound);
        String web = "/material-entry/materials/" + m.id(), qr = "/material-entry/" + token;
        for (String path : List.of(web, qr)) {
            assertEquals(200, mvc.perform(getRequest(path).header("Authorization", outAuth)).andReturn().getResponse().getStatus());
            assertEquals(200, mvc.perform(getRequest(path).header("Authorization", inAuth)).andReturn().getResponse().getStatus());
            assertEquals(403, mvc.perform(postRequest(path + "/entries").header("Authorization", outAuth)
                    .contentType("application/json").content(json.writeValueAsBytes(request("IN", "10")))).andReturn().getResponse().getStatus());
            assertEquals(403, mvc.perform(postRequest(path + "/entries").header("Authorization", inAuth)
                    .contentType("application/json").content(json.writeValueAsBytes(request("OUT", "10")))).andReturn().getResponse().getStatus());
        }
        qty("100", service.get(m.id()).currentQty()); assertNull(service.dailyRecord(m.id(), service.today()));
        assertEquals(1, service.ledger(m.id(), null, null, 1, 100).total());
        var out = request("OUT", "2.125");
        var response = mvc.perform(postRequest(web + "/entries").header("Authorization", outAuth)
                .contentType("application/json").content(json.writeValueAsBytes(out))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        var receipt = json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
        assertEquals(outbound.realName, receipt.path("operator").asText());
        response = mvc.perform(postRequest(qr + "/entries").header("Authorization", outAuth)
                .contentType("application/json").content(json.writeValueAsBytes(out))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals(receipt.path("id"), json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("id"));
        assertEquals(200, mvc.perform(postRequest(qr + "/entries").header("Authorization", inAuth)
                .contentType("application/json").content(json.writeValueAsBytes(request("IN", "4")))).andReturn().getResponse().getStatus());
        qty("101.875", service.get(m.id()).currentQty());
        assertEquals(3, service.ledger(m.id(), null, null, 1, 100).total());
        var ledger = service.ledger(m.id(), null, null, 1, 100).items();
        assertTrue(ledger.stream().anyMatch(t -> outbound.id.equals(t.operatorUserId) && outbound.realName.equals(t.operator)));
        assertTrue(ledger.stream().anyMatch(t -> inbound.id.equals(t.operatorUserId) && inbound.realName.equals(t.operator)));
    }

    @Test void administratorCanSplitLegacyPermissionAndChangesApplyToExistingLoginSession() throws Exception {
        var admin = employee("管理员", "users"); String adminAuth = bearer(admin), auth = bearer(employee);
        var m = create(); String path = "/material-entry/materials/" + m.id();
        var response = mvc.perform(getRequest("/auth/me").header("Authorization", auth)).andReturn().getResponse();
        var profile = json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data").path("user");
        assertEquals(Set.of("material-entry-out", "material-entry-in"), new HashSet<>(json.convertValue(profile.path("menuCodes"), List.class)));
        assertEquals(Set.of("material-entry"), users.findById(employee.id).orElseThrow().menuCodes, "Reading must not rewrite stored grants");
        var body = new LinkedHashMap<String, Object>(Map.of("username", employee.username, "realName", employee.realName,
                "enabled", true, "permissionMode", "CUSTOM", "menuCodes", List.of("material-entry-out")));
        assertEquals(200, mvc.perform(putRequest("/system/users/" + employee.id).header("Authorization", adminAuth)
                .contentType("application/json").content(json.writeValueAsBytes(body))).andReturn().getResponse().getStatus());
        assertEquals(Set.of("material-entry-out"), users.findById(employee.id).orElseThrow().menuCodes);
        assertEquals(403, mvc.perform(postRequest(path + "/entries").header("Authorization", auth)
                .contentType("application/json").content(json.writeValueAsBytes(request("IN", "2")))).andReturn().getResponse().getStatus());
        var out = request("OUT", "2");
        assertEquals(200, mvc.perform(postRequest(path + "/entries").header("Authorization", auth)
                .contentType("application/json").content(json.writeValueAsBytes(out))).andReturn().getResponse().getStatus());
        body.put("menuCodes", List.of("material-entry-in"));
        assertEquals(200, mvc.perform(putRequest("/system/users/" + employee.id).header("Authorization", adminAuth)
                .contentType("application/json").content(json.writeValueAsBytes(body))).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(postRequest(path + "/entries").header("Authorization", auth)
                .contentType("application/json").content(json.writeValueAsBytes(out))).andReturn().getResponse().getStatus(), "A revoked direction cannot retry an old request");
        assertEquals(200, mvc.perform(postRequest(path + "/entries").header("Authorization", auth)
                .contentType("application/json").content(json.writeValueAsBytes(request("IN", "3")))).andReturn().getResponse().getStatus());
        qty("101", service.get(m.id()).currentQty());
        body.put("menuCodes", List.of());
        assertEquals(200, mvc.perform(putRequest("/system/users/" + employee.id).header("Authorization", adminAuth)
                .contentType("application/json").content(json.writeValueAsBytes(body))).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(getRequest("/material-entry/materials").header("Authorization", auth)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(getRequest(path).header("Authorization", auth)).andReturn().getResponse().getStatus());
    }

    @Test void correctingUnitUpdatesWebAndQrMetadataAndNewReceiptsWithoutRotatingQrOrConvertingStock() {
        var m = create(); String token = service.scanCode(m.id()).token();
        var live = service.get(m.id());
        var update = new MaterialManagementService.MaterialRequest();
        update.name = live.name(); update.category = live.category(); update.unit = "袋";
        update.safetyStock = live.safetyStock(); update.enabled = live.enabled();
        update.versionNo = live.versionNo(); update.stockVersion = live.stockVersion();
        service.update(m.id(), update);
        qty("100", service.get(m.id()).currentQty());
        assertEquals("袋", service.scanMaterial(token).unit());
        assertEquals("袋", service.entryMaterial(m.id()).unit());
        assertEquals("袋", service.entryMaterials().stream().filter(item -> item.id().equals(m.id())).findFirst().orElseThrow().unit());
        var receipt = service.registerMaterial(m.id(), request("IN", "1.5"));
        assertEquals("袋", receipt.unit()); assertEquals(employee.realName, receipt.operator());
        qty("101.5", service.get(m.id()).currentQty());
        assertEquals(token, service.scanCode(m.id()).token());
    }

    @Test void anotherEmployeeCannotClaimAnExistingRequestAndHistoryKeepsOriginalIdentity() {
        var m = create(); String token = service.scanCode(m.id()).token(); var r = request("OUT", "2");
        var receipt = service.registerScan(token, r);
        var other = employee("另一位员工", "material-entry"); identify(other);
        assertThrows(BusinessException.class, () -> service.registerScan(token, r));
        identify(employee); assertEquals(receipt.id(), service.registerScan(token, r).id());
        employee.realName = "员工新名字"; employee.username = "renamed" + UUID.randomUUID().toString().replace("-", "");
        employee = users.saveAndFlush(employee); identify(employee);
        var retried = service.registerScan(token, r);
        assertEquals(receipt.id(), retried.id()); assertEquals(receipt.operator(), retried.operator());
        assertEquals(receipt.operatorUsername(), retried.operatorUsername()); qty("2", retried.qty());
        qty("98", service.get(m.id()).currentQty());
        var txn = service.ledger(m.id(), null, null, 1, 100).items().get(0);
        assertEquals(receipt.operator(), txn.operator); assertEquals(receipt.operatorUsername(), txn.operatorUsername);
    }
}
