package com.company.oms.finance;

import com.company.oms.common.ApiResponse;
import com.company.oms.common.AuditLogAction;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/finance/collections")
@PreAuthorize("@menuPermissionService.has('receipts')")
public class CollectionController {
    private final FinanceService financeService;

    public CollectionController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public ApiResponse<?> list() {
        return ApiResponse.ok(financeService.collectionBoard());
    }

    @GetMapping("/companies")
    public ApiResponse<?> companies() {
        return ApiResponse.ok(financeService.collectionCompanyBoard());
    }

    @GetMapping("/companies/{groupKey}")
    public ApiResponse<?> companyDetail(@PathVariable String groupKey) {
        return ApiResponse.ok(financeService.collectionCompanyDetail(groupKey));
    }

    @GetMapping("/{statementId}")
    public ApiResponse<?> detail(@PathVariable Long statementId) {
        return ApiResponse.ok(financeService.collectionDetail(statementId));
    }

    @PostMapping("/{statementId}/register")
    @AuditLogAction(module = "收款", action = "结算方式登记")
    public ApiResponse<?> register(@PathVariable Long statementId,
                                   @RequestBody FinanceService.CollectionRegisterRequest request) {
        return ApiResponse.ok(financeService.registerCollection(statementId, request));
    }

    @PostMapping("/{statementId}/receipts")
    @AuditLogAction(module = "收款", action = "到账登记")
    public ApiResponse<?> receipt(@PathVariable Long statementId, @RequestBody Receipt receipt) {
        return ApiResponse.ok(financeService.registerCollectionReceipt(statementId, receipt));
    }
}
