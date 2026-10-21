package com.company.oms.config;

import com.company.oms.common.ApiResponse;
import com.company.oms.finance.FinanceService;
import com.company.oms.sales.SalesService;
import com.company.oms.security.TokenService;
import com.company.oms.system.SysUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
public class PerformanceWarmup {
    private static final Logger log = LoggerFactory.getLogger(PerformanceWarmup.class);
    @org.springframework.beans.factory.annotation.Autowired private com.company.oms.system.MenuCatalog menuCatalog;
    @org.springframework.beans.factory.annotation.Autowired private com.company.oms.security.LoginSessionRepository loginSessions;
    private final SalesService salesService;
    private final FinanceService financeService;
    private final ObjectMapper objectMapper;
    private final TokenService tokenService;
    private final SysUserRepository userRepository;
    private final ApplicationContext applicationContext;

    public PerformanceWarmup(SalesService salesService, FinanceService financeService,
                             ObjectMapper objectMapper, TokenService tokenService,
                             SysUserRepository userRepository, ApplicationContext applicationContext) {
        this.salesService = salesService;
        this.financeService = financeService;
        this.objectMapper = objectMapper;
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.applicationContext = applicationContext;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmDefaultSalesPages() {
        try {
            salesService.simpleOrdersPage(0, 100, "", "all", java.time.LocalDate.now().getYear());
            salesService.deliveryInvoicePage(0, 100, "", "all", "all");
            objectMapper.writeValueAsBytes(ApiResponse.ok(financeService.collectionCompanyBoard()));
            warmCollectionHttpPath();
            log.info("Default sales pages and collection company board warmed successfully");
        } catch (Exception exception) {
            log.warn("Default page warmup failed; requests will use their normal database paths", exception);
        }
    }

    private void warmCollectionHttpPath() throws Exception {
        if (!(applicationContext instanceof ServletWebServerApplicationContext webContext)) return;
        var admin = userRepository.findAll().stream()
                .filter(user -> !Boolean.TRUE.equals(user.deleted) && Boolean.TRUE.equals(user.enabled) && menuCatalog.effective(user).contains("receipts"))
                .findFirst().orElse(null);
        if (admin == null) return;
        String contextPath = applicationContext.getEnvironment()
                .getProperty("server.servlet.context-path", "");
        String warmupToken = tokenService.createToken(admin);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + webContext.getWebServer().getPort()
                        + contextPath + "/finance/collections/companies"))
                .header("Authorization", "Bearer " + warmupToken)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try { response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofByteArray());
        } finally { loginSessions.revoke(tokenService.parseSession(warmupToken).id); }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Collection board HTTP warmup returned " + response.statusCode());
        }
    }
}
