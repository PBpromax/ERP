package com.company.oms.sales;

import com.company.oms.common.*;
import org.springframework.http.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sales/orders")
@PreAuthorize("@menuPermissionService.has('orders')")
public class SalesOrderController extends CrudController<SalesOrder> {
    @org.springframework.beans.factory.annotation.Autowired
    private com.company.oms.system.MenuPermissionService menuPermissions;
    private final SalesService salesService;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderExportService salesOrderExportService;

    public SalesOrderController(SalesOrderRepository repository,
                                SalesService salesService,
                                SalesOrderExportService salesOrderExportService) {
        super(repository);
        this.salesOrderRepository = repository;
        this.salesService = salesService;
        this.salesOrderExportService = salesOrderExportService;
    }

    @GetMapping("/simple")
    public ApiResponse<List<Map<String, Object>>> simpleList() {
        return ApiResponse.ok(salesService.simpleOrders());
    }

    @GetMapping("/page")
    public ApiResponse<PageResponse<Map<String, Object>>> page(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "all") String deliveryStatus,
            @RequestParam(required = false) Integer year) {
        return ApiResponse.ok(salesService.simpleOrdersPage(
                Math.max(0, page), Math.max(1, Math.min(size, 100)), keyword, deliveryStatus, year));
    }

    @GetMapping("/next-no")
    public ApiResponse<String> nextNo(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(salesService.previewNextOrderNo(date));
    }

    @AuditLogAction(module = "销售订单", action = "新增销售订单")
    @PostMapping("/simple")
    public ApiResponse<Map<String, Object>> createSimple(@RequestBody SalesService.SimpleOrderRequest request) {
        SalesOrder saved = salesService.saveSimpleOrder(request);
        return ApiResponse.ok(salesService.simpleOrderRow(saved.id));
    }

    @AuditLogAction(module = "销售订单", action = "编辑销售订单")
    @PutMapping("/simple/{id}")
    public ApiResponse<Map<String, Object>> updateSimple(@PathVariable Long id, @RequestBody SalesService.SimpleOrderRequest request) {
        SalesOrder saved = salesService.updateSimpleOrder(id, request);
        return ApiResponse.ok(salesService.simpleOrderRow(saved.id));
    }

    @AuditLogAction(module = "销售订单", action = "保存订单明细")
    @PostMapping("/with-items")
    public ApiResponse<SalesOrder> saveWithItems(@RequestBody OrderRequest request) {
        return ApiResponse.ok(salesService.saveOrderWithItems(request.order, request.items));
    }

    @Override
    @AuditLogAction(module = "销售订单", action = "删除销售订单")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        salesService.deleteSalesOrder(id);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(salesService.orderDetail(id));
    }

    @AuditLogAction(module = "销售订单", action = "审核订单")
    @PostMapping("/{id}/approve")
    public ApiResponse<SalesOrder> approve(@PathVariable Long id) {
        return ApiResponse.ok(salesService.approveOrder(id));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id) {
        SalesOrder order = salesOrderRepository.findById(id).orElseThrow(() -> new BusinessException("销售订单不存在"));
        byte[] body = salesOrderExportService.export(id, menuPermissions.canReadOrderPrices());
        String filename = (order.orderNo == null || order.orderNo.isBlank() ? "sales-order" : order.orderNo) + ".xlsx";
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    public static class OrderRequest {
        public SalesOrder order;
        public List<SalesOrderItem> items;
    }
}
