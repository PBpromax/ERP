package com.company.oms.finance;

import com.company.oms.common.ApiResponse;
import com.company.oms.common.AuditLogAction;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/finance/customer-summaries")
@PreAuthorize("@menuPermissionService.has('receipts')")
public class CustomerSummaryController {
    private final FinanceService financeService;

    public CustomerSummaryController(FinanceService financeService) {
        this.financeService = financeService;
    }

    @GetMapping
    public ApiResponse<?> list() {
        return ApiResponse.ok(financeService.customerSummaries());
    }

    @GetMapping("/{groupKey}")
    public ApiResponse<?> detail(@PathVariable String groupKey) {
        return ApiResponse.ok(financeService.customerSummaryDetail(groupKey));
    }

    @PostMapping("/{groupKey}/payments")
    @AuditLogAction(module = "收款", action = "公司收款自动核销")
    public ApiResponse<?> payment(@PathVariable String groupKey,
                                  @RequestBody FinanceService.CustomerPaymentRequest request) {
        return ApiResponse.ok(financeService.registerCustomerPayment(groupKey, request));
    }

    @PutMapping("/{groupKey}/payments/{batchNo}")
    @AuditLogAction(module = "收款", action = "修改公司到账记录")
    public ApiResponse<?> updatePayment(@PathVariable String groupKey,
                                        @PathVariable String batchNo,
                                        @RequestBody FinanceService.CustomerPaymentRequest request) {
        return ApiResponse.ok(financeService.updateCustomerPayment(groupKey, batchNo, request));
    }

    @DeleteMapping("/{groupKey}/payments/{batchNo}")
    @AuditLogAction(module = "收款", action = "删除公司到账记录")
    public ApiResponse<?> deletePayment(@PathVariable String groupKey, @PathVariable String batchNo) {
        financeService.deleteCustomerPayment(groupKey, batchNo);
        return ApiResponse.ok();
    }

    @PostMapping("/{groupKey}/payments/reallocate")
    @PreAuthorize("@menuPermissionService.has('receipts')")
    @AuditLogAction(module = "收款", action = "重算公司收款分配")
    public ApiResponse<?> reallocate(@PathVariable String groupKey) {
        financeService.replayCustomerPayments(groupKey);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{groupKey}/ledger-entries/{entryId}")
    @AuditLogAction(module = "收款", action = "作废历史欠款记录")
    public ApiResponse<?> voidLedgerEntry(@PathVariable String groupKey, @PathVariable Long entryId) {
        financeService.voidLedgerEntry(groupKey, entryId);
        return ApiResponse.ok();
    }
}
