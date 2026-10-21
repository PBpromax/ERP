package com.company.oms.finance;

import com.company.oms.common.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/finance/statements")
@PreAuthorize("@menuPermissionService.has('statements','invoices','receipts')")
public class StatementController extends CrudController<Statement> {
    private final FinanceService financeService;
    private final StatementRepository statementRepository;
    private final StatementExportService statementExportService;

    public StatementController(StatementRepository repository,
                               FinanceService financeService,
                               StatementExportService statementExportService) {
        super(repository);
        this.statementRepository = repository;
        this.financeService = financeService;
        this.statementExportService = statementExportService;
    }

    @Override
    @GetMapping
    public ApiResponse<List<Statement>> list() {
        return ApiResponse.ok(financeService.listStatements());
    }

    @AuditLogAction(module = "账单", action = "月底账单生成")
    @PostMapping("/generate")
    public ApiResponse<?> generate(@RequestParam String month) {
        return ApiResponse.ok(financeService.generateStatements(month));
    }

    @GetMapping("/candidates")
    public ApiResponse<?> candidates(@RequestParam String month) {
        return ApiResponse.ok(financeService.statementCandidateDeliveries(month));
    }

    @GetMapping("/customer-board")
    public ApiResponse<?> customerBoard() {
        return ApiResponse.ok(financeService.statementCustomerBoard());
    }

    @GetMapping("/customers/{customerId}/candidates")
    public ApiResponse<?> customerCandidates(@PathVariable Long customerId) {
        return ApiResponse.ok(financeService.customerStatementCandidates(customerId));
    }

    @AuditLogAction(module = "账单", action = "勾选送货记录生成对账单")
    @PostMapping("/generate-selected")
    public ApiResponse<?> generateSelected(@RequestBody FinanceService.StatementSelectionRequest request) {
        if (request.deliverySelections != null && !request.deliverySelections.isEmpty()) {
            return ApiResponse.ok(financeService.generateStatementsFromSelections(
                    request.month, request.deliverySelections));
        }
        return ApiResponse.ok(financeService.generateStatementsFromDeliveries(request.month, request.deliveryIds));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<?> detail(@PathVariable Long id) {
        return ApiResponse.ok(financeService.statementDetail(id));
    }

    @PutMapping("/{id}/amount")
    @AuditLogAction(module = "账单", action = "修改对账单金额")
    public ApiResponse<?> updateAmount(@PathVariable Long id,
                                       @RequestBody FinanceService.StatementAmountRequest request) {
        return ApiResponse.ok(financeService.updateStatementAmount(id, request));
    }

    @PostMapping("/{id}/returns")
    @AuditLogAction(module = "账单", action = "加入发货管理退货记录")
    public ApiResponse<?> appendReturns(@PathVariable Long id,
                                        @RequestBody FinanceService.StatementSelectionRequest request) {
        return ApiResponse.ok(financeService.appendSelectedReturns(id, request.deliverySelections));
    }

    @PostMapping("/{id}/adjustments")
    @AuditLogAction(module = "账单", action = "新增退货调整")
    public ApiResponse<?> createAdjustment(@PathVariable Long id,
                                           @RequestBody FinanceService.StatementAdjustmentRequest request) {
        return ApiResponse.ok(financeService.createStatementAdjustment(id, request));
    }

    @PutMapping("/{id}/adjustments/{itemId}")
    @AuditLogAction(module = "账单", action = "修改退货调整")
    public ApiResponse<?> updateAdjustment(@PathVariable Long id, @PathVariable Long itemId,
                                           @RequestBody FinanceService.StatementAdjustmentRequest request) {
        return ApiResponse.ok(financeService.updateStatementAdjustment(id, itemId, request));
    }

    @DeleteMapping("/{id}/adjustments/{itemId}")
    @AuditLogAction(module = "账单", action = "删除退货调整")
    public ApiResponse<?> deleteAdjustment(@PathVariable Long id, @PathVariable Long itemId) {
        financeService.deleteStatementAdjustment(id, itemId);
        return ApiResponse.ok();
    }

    @Override
    @AuditLogAction(module = "账单", action = "删除对账单并回档")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        financeService.deleteStatement(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        Statement statement = statementRepository.findById(id).orElseThrow(() -> new BusinessException("对账单不存在"));
        byte[] body = statementExportService.export(id);
        String filename = (statement.statementNo == null || statement.statementNo.isBlank() ? "statement" : statement.statementNo) + ".xlsx";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }
}
