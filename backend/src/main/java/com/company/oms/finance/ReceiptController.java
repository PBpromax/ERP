package com.company.oms.finance;

import com.company.oms.common.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/finance/receipts")
@PreAuthorize("@menuPermissionService.has('receipts')")
public class ReceiptController extends CrudController<Receipt> {
    private final FinanceService financeService;

    public ReceiptController(ReceiptRepository repository, FinanceService financeService) {
        super(repository);
        this.financeService = financeService;
    }

    @Override
    @AuditLogAction(module = "收款", action = "收款登记")
    @PostMapping
    public ApiResponse<Receipt> create(@RequestBody Receipt entity) {
        entity.id = null;
        entity.paymentId = null;
        entity.customerLevelPayment = false;
        return ApiResponse.ok(financeService.registerReceipt(entity));
    }

    @Override
    @AuditLogAction(module = "收款", action = "修改收款登记")
    @PutMapping("/{id}")
    public ApiResponse<Receipt> update(@PathVariable Long id, @RequestBody Receipt entity) {
        return ApiResponse.ok(financeService.updateReceipt(id, entity));
    }

    @Override
    @AuditLogAction(module = "收款", action = "删除收款登记")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        financeService.deleteReceipt(id);
        return ApiResponse.ok();
    }
}
