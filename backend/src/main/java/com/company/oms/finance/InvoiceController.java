package com.company.oms.finance;

import com.company.oms.common.*;
import com.company.oms.masterdata.BusinessLicenseRecognitionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/finance/invoices")
@PreAuthorize("@menuPermissionService.has('invoices')")
public class InvoiceController extends CrudController<Invoice> {
    private final FinanceService financeService;
    private final BusinessLicenseRecognitionService recognitionService;

    public InvoiceController(InvoiceRepository repository, FinanceService financeService,
                             BusinessLicenseRecognitionService recognitionService) {
        super(repository);
        this.financeService = financeService;
        this.recognitionService = recognitionService;
    }

    @Override
    @GetMapping
    public ApiResponse<java.util.List<Invoice>> list() {
        return ApiResponse.ok(financeService.listInvoices());
    }

    @Override
    @AuditLogAction(module = "发票", action = "开票登记")
    @PostMapping
    public ApiResponse<Invoice> create(@RequestBody Invoice entity) {
        return ApiResponse.ok(financeService.registerInvoice(entity));
    }

    @Override
    @AuditLogAction(module = "发票", action = "修改开票登记")
    @PutMapping("/{id}")
    public ApiResponse<Invoice> update(@PathVariable Long id, @RequestBody Invoice entity) {
        return ApiResponse.ok(financeService.updateInvoice(id, entity));
    }

    @Override
    @AuditLogAction(module = "发票", action = "删除开票登记")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        financeService.deleteInvoice(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<?> detail(@PathVariable Long id) {
        return ApiResponse.ok(financeService.invoiceDetail(id));
    }

    @GetMapping("/statement-board")
    public ApiResponse<?> statementBoard() {
        return ApiResponse.ok(financeService.invoicedStatementBoard());
    }

    @GetMapping("/candidates")
    public ApiResponse<?> candidates() {
        return ApiResponse.ok(financeService.invoiceStatementCandidates());
    }

    @PostMapping("/recognize-document")
    public ApiResponse<BusinessLicenseRecognitionService.InvoiceDocumentInfo> recognizeDocument(
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(recognitionService.recognizeInvoiceDocument(file));
    }
}
