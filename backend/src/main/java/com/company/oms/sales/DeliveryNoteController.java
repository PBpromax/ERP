package com.company.oms.sales;

import com.company.oms.common.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sales/deliveries")
@PreAuthorize("@menuPermissionService.has('deliveries')")
public class DeliveryNoteController extends CrudController<DeliveryNote> {
    private final SalesService salesService;
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final DeliveryNoteExportService deliveryNoteExportService;
    private final SalesReturnService salesReturnService;

    public DeliveryNoteController(DeliveryNoteRepository repository,
                                  SalesService salesService,
                                  DeliveryNoteExportService deliveryNoteExportService, SalesReturnService salesReturnService) {
        super(repository);
        this.deliveryNoteRepository = repository;
        this.salesService = salesService;
        this.deliveryNoteExportService = deliveryNoteExportService;
        this.salesReturnService = salesReturnService;
    }

    @GetMapping("/invoice-board")
    public ApiResponse<List<Map<String, Object>>> invoiceBoard() {
        return ApiResponse.ok(salesService.deliveryInvoiceRows());
    }

    @GetMapping("/invoice-board/page")
    public ApiResponse<PageResponse<Map<String, Object>>> invoiceBoardPage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "all") String filterMode,
            @RequestParam(defaultValue = "all") String fulfillmentStatus) {
        return ApiResponse.ok(salesService.deliveryInvoicePage(
                Math.max(0, page), Math.max(1, Math.min(size, 100)), keyword, filterMode, fulfillmentStatus));
    }

    @GetMapping("/invoice-board/candidates")
    public ApiResponse<List<Map<String, Object>>> invoiceBoardCandidates(
            @RequestParam Long customerId,
            @RequestParam(required = false) Long deliveryId) {
        return ApiResponse.ok(salesService.deliveryCandidates(customerId, deliveryId));
    }

    @PostMapping("/invoice-board/{orderId}/returns")
    @AuditLogAction(module = "发货管理", action = "登记退货")
    public ApiResponse<?> registerReturn(@PathVariable Long orderId, @RequestBody SalesReturnService.Request request) {
        return ApiResponse.ok(salesReturnService.register(orderId, request));
    }

    @DeleteMapping("/returns/{id}")
    @AuditLogAction(module = "发货管理", action = "删除未对账退货")
    public ApiResponse<?> deleteReturn(@PathVariable Long id) {
        salesReturnService.delete(id);
        return ApiResponse.ok();
    }

    @GetMapping("/invoice-board/{orderId}")
    public ApiResponse<Map<String, Object>> invoiceBoardDetail(@PathVariable Long orderId,
                                                                @RequestParam(required = false) Long itemId) {
        return ApiResponse.ok(salesService.deliveryInvoiceDetail(orderId, itemId));
    }

    @AuditLogAction(module = "发货管理", action = "新增发货信息")
    @PostMapping("/invoice-board/{orderId}/deliveries")
    public ApiResponse<DeliveryNote> addDelivery(@PathVariable Long orderId, @RequestBody SalesService.DeliveryRegisterRequest request) {
        return ApiResponse.ok(salesService.addDelivery(orderId, request));
    }

    @AuditLogAction(module = "发货管理", action = "合并新增发货单")
    @PostMapping("/batch")
    public ApiResponse<DeliveryNote> addBatchDelivery(@RequestBody SalesService.BatchDeliveryRequest request) {
        return ApiResponse.ok(salesService.addBatchDelivery(request));
    }

    @AuditLogAction(module = "发货管理", action = "删除发货信息")
    @DeleteMapping("/invoice-board/deliveries/{deliveryId}")
    public ApiResponse<Void> deleteDelivery(@PathVariable Long deliveryId) {
        salesService.deleteDeliveryInfo(deliveryId);
        return ApiResponse.ok();
    }

    @AuditLogAction(module = "发货管理", action = "修改发货信息")
    @PutMapping("/invoice-board/deliveries/{deliveryId}")
    public ApiResponse<DeliveryNote> updateDelivery(@PathVariable Long deliveryId,
                                                    @RequestBody SalesService.BatchDeliveryRequest request) {
        return ApiResponse.ok(salesService.updateDeliveryInfo(deliveryId, request));
    }

    @AuditLogAction(module = "送货单", action = "生成送货单")
    @PostMapping("/from-order/{orderId}")
    public ApiResponse<DeliveryNote> fromOrder(@PathVariable Long orderId, @RequestBody(required = false) DeliveryRequest request) {
        if (request == null) request = new DeliveryRequest();
        return ApiResponse.ok(salesService.createDeliveryFromOrder(orderId, request.items, request.printPrice));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(salesService.deliveryDetail(id));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        DeliveryNote delivery = deliveryNoteRepository.findById(id)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("发货信息不存在"));
        byte[] body = deliveryNoteExportService.export(id);
        String filename = (delivery.deliveryNo == null || delivery.deliveryNo.isBlank() ? "delivery-note" : delivery.deliveryNo) + ".xlsx";
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    @AuditLogAction(module = "送货单", action = "确认发货")
    @PostMapping("/{id}/confirm")
    public ApiResponse<DeliveryNote> confirm(@PathVariable Long id) {
        return ApiResponse.ok(salesService.confirmDelivery(id));
    }

    public static class DeliveryRequest {
        public Boolean printPrice;
        public List<DeliveryNoteItem> items;
    }
}
