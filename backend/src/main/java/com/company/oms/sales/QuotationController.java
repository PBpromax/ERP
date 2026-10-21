package com.company.oms.sales;

import com.company.oms.common.*;
import org.springframework.http.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/sales/quotations")
@PreAuthorize("@menuPermissionService.has('quotations')")
public class QuotationController extends CrudController<Quotation> {
    private final SalesService salesService;
    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final QuotationExportService quotationExportService;

    public QuotationController(QuotationRepository repository,
                               SalesService salesService,
                               QuotationItemRepository quotationItemRepository,
                               QuotationExportService quotationExportService) {
        super(repository);
        this.quotationRepository = repository;
        this.salesService = salesService;
        this.quotationItemRepository = quotationItemRepository;
        this.quotationExportService = quotationExportService;
    }

    @Override
    @GetMapping
    public ApiResponse<List<Quotation>> list() {
        List<Quotation> rows = quotationRepository.findByDeletedFalseOrderByCreatedAtDescIdDesc();
        Map<Long, LinkedHashSet<String>> productNames = new HashMap<>();
        Map<Long, LinkedHashSet<String>> productCodes = new HashMap<>();
        Map<Long, List<Map<String, Object>>> productItems = new HashMap<>();
        List<Long> quotationIds = rows.stream().map(item -> item.id).filter(Objects::nonNull).toList();
        if (!quotationIds.isEmpty()) {
            for (QuotationItem item : quotationItemRepository.findByQuotationIdInAndDeletedFalse(quotationIds)) {
                Map<String, Object> product = new LinkedHashMap<>();
                product.put("id", item.id);
                product.put("productId", item.productId);
                product.put("productCode", item.productCode);
                product.put("productName", item.productName);
                product.put("spec", item.spec);
                product.put("unit", item.unit);
                product.put("qty", item.qty);
                product.put("unitPrice", item.unitPrice);
                product.put("amount", item.amount);
                productItems.computeIfAbsent(item.quotationId, ignored -> new ArrayList<>()).add(product);
                if (item.productCode != null && !item.productCode.isBlank()) {
                    productCodes.computeIfAbsent(item.quotationId, ignored -> new LinkedHashSet<>()).add(item.productCode.trim());
                }
                if (item.productName != null && !item.productName.isBlank()) {
                    productNames.computeIfAbsent(item.quotationId, ignored -> new LinkedHashSet<>()).add(item.productName.trim());
                }
            }
        }
        rows.forEach(item -> {
            item.productCodes = String.join("、", productCodes.getOrDefault(item.id, new LinkedHashSet<>()));
            item.productNames = String.join("、", productNames.getOrDefault(item.id, new LinkedHashSet<>()));
            item.productItems = productItems.getOrDefault(item.id, List.of());
        });
        return ApiResponse.ok(rows);
    }

    @GetMapping("/next-no")
    public ApiResponse<String> nextNo(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(salesService.previewNextQuotationNo(date));
    }

    @AuditLogAction(module = "报价单", action = "保存报价明细")
    @PostMapping("/with-items")
    public ApiResponse<Quotation> saveWithItems(@RequestBody QuotationRequest request) {
        return ApiResponse.ok(salesService.saveQuotationWithItems(request.quotation, request.items));
    }

    @Override
    @AuditLogAction(module = "报价单", action = "删除报价")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        salesService.deleteQuotation(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(salesService.quotationDetail(id));
    }

    @AuditLogAction(module = "报价单", action = "发送报价")
    @PostMapping("/{id}/send")
    public ApiResponse<Quotation> send(@PathVariable Long id) {
        return ApiResponse.ok(salesService.sendQuotation(id));
    }

    @AuditLogAction(module = "报价单", action = "转订单")
    @PostMapping("/{id}/convert")
    public ApiResponse<SalesOrder> convert(@PathVariable Long id, @RequestBody(required = false) SalesService.QuotationConvertRequest request) {
        if (request == null) request = new SalesService.QuotationConvertRequest();
        return ApiResponse.ok(salesService.convertQuotationToOrder(id, request));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        Quotation quotation = quotationRepository.findById(id).orElseThrow(() -> new BusinessException("报价单不存在"));
        byte[] body = quotationExportService.export(id);
        String filename = (quotation.quoteNo == null || quotation.quoteNo.isBlank() ? "quotation" : quotation.quoteNo) + ".xlsx";
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    public static class QuotationRequest {
        public Quotation quotation;
        public List<QuotationItem> items;
    }
}
