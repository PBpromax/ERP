package com.company.oms.inventory;

import com.company.oms.common.*;
import com.company.oms.masterdata.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;

@RestController
@RequestMapping("/inventory")
@PreAuthorize("hasAnyRole('ADMIN','HR','CLERK','LOGISTICS','WAREHOUSE')")
public class InventoryController {
    private final InventoryStockRepository productStockRepository;
    private final MaterialStockRepository materialStockRepository;
    private final InventoryTxnRepository txnRepository;
    private final ProductRepository productRepository;
    private final MaterialRepository materialRepository;
    private final InventoryService inventoryService;

    public InventoryController(InventoryStockRepository productStockRepository,
                               MaterialStockRepository materialStockRepository,
                               InventoryTxnRepository txnRepository,
                               ProductRepository productRepository,
                               MaterialRepository materialRepository,
                               InventoryService inventoryService) {
        this.productStockRepository = productStockRepository;
        this.materialStockRepository = materialStockRepository;
        this.txnRepository = txnRepository;
        this.productRepository = productRepository;
        this.materialRepository = materialRepository;
        this.inventoryService = inventoryService;
    }

    @GetMapping("/product-stocks")
    public ApiResponse<?> productStocks() {
        return ApiResponse.ok(productStockRepository.findAll().stream().filter(e -> e.deleted == null || !e.deleted).toList());
    }

    @GetMapping("/material-stocks")
    public ApiResponse<?> materialStocks() {
        return ApiResponse.ok(materialStockRepository.findAll().stream().filter(e -> e.deleted == null || !e.deleted).toList());
    }

    @GetMapping("/transactions")
    public ApiResponse<?> transactions() {
        return ApiResponse.ok(txnRepository.findAll().stream().filter(e -> e.deleted == null || !e.deleted).toList());
    }

    @AuditLogAction(module = "成品库存", action = "新增库存")
    @PostMapping("/product-stocks")
    public ApiResponse<InventoryStock> createProductStock(@RequestBody StockCreateRequest req) {
        Product product = resolveProduct(req);
        return ApiResponse.ok(inventoryService.createProductStock(product, req.currentQty, req.lockedQty,
                req.safetyStock, req.stockAmount, req.sourceNo, req.remark));
    }

    @AuditLogAction(module = "原料库存", action = "新增库存")
    @PostMapping("/material-stocks")
    public ApiResponse<MaterialStock> createMaterialStock(@RequestBody StockCreateRequest req) {
        if (req.materialId == null) throw new BusinessException("请选择材料");
        Material material = materialRepository.findById(req.materialId).orElseThrow(() -> new BusinessException("材料不存在"));
        return ApiResponse.ok(inventoryService.createMaterialStock(material, req.currentQty, req.lockedQty,
                req.safetyStock, req.stockAmount, req.sourceNo, req.remark));
    }

    @AuditLogAction(module = "库存流水", action = "新增流水")
    @PostMapping("/transactions")
    public ApiResponse<?> createTransaction(@RequestBody TransactionCreateRequest req) {
        if (req.itemType == null || req.itemType.isBlank()) throw new BusinessException("请选择库存类型");
        if (req.itemId == null) throw new BusinessException("请选择库存物料");
        if ("PRODUCT".equalsIgnoreCase(req.itemType)) {
            Product product = productRepository.findById(req.itemId).orElseThrow(() -> new BusinessException("产品不存在"));
            return ApiResponse.ok(inventoryService.createManualProductTransaction(product, req.txnType, req.qty,
                    req.sourceType, req.sourceNo, req.remark));
        }
        if ("MATERIAL".equalsIgnoreCase(req.itemType)) {
            Material material = materialRepository.findById(req.itemId).orElseThrow(() -> new BusinessException("材料不存在"));
            return ApiResponse.ok(inventoryService.createManualMaterialTransaction(material, req.txnType, req.qty,
                    req.sourceType, req.sourceNo, req.remark));
        }
        throw new BusinessException("库存类型只能选择成品或原料");
    }

    @AuditLogAction(module = "出入库管理", action = "出入库")
    @PostMapping("/product-stocks/{stockId}/movement")
    public ApiResponse<InventoryStock> productMovement(@PathVariable Long stockId, @RequestBody ProductMovementRequest req) {
        return ApiResponse.ok(inventoryService.registerProductMovement(stockId, req.movementType, req.qty, req.operator));
    }

    @AuditLogAction(module = "成品库存", action = "删除库存")
    @DeleteMapping("/product-stocks/{stockId}")
    public ApiResponse<Void> deleteProductStock(@PathVariable Long stockId) {
        InventoryStock stock = productStockRepository.findById(stockId).orElseThrow(() -> new BusinessException("成品库存不存在"));
        productStockRepository.delete(stock);
        return ApiResponse.ok();
    }

    @AuditLogAction(module = "成品库存", action = "库存调整")
    @PostMapping("/product-stocks/{productId}/adjust")
    public ApiResponse<InventoryStock> adjustProduct(@PathVariable Long productId, @RequestBody AdjustRequest req) {
        Product product = productRepository.findById(productId).orElseThrow(() -> new BusinessException("产品不存在"));
        return ApiResponse.ok(inventoryService.adjustProduct(product, req.targetQty, req.sourceNo, req.remark));
    }

    @AuditLogAction(module = "原料库存", action = "库存调整")
    @PostMapping("/material-stocks/{materialId}/adjust")
    public ApiResponse<MaterialStock> adjustMaterial(@PathVariable Long materialId, @RequestBody AdjustRequest req) {
        Material material = materialRepository.findById(materialId).orElseThrow(() -> new BusinessException("材料不存在"));
        return ApiResponse.ok(inventoryService.adjustMaterial(material, req.targetQty, req.sourceNo, req.remark));
    }

    public static class AdjustRequest {
        public BigDecimal targetQty;
        public String sourceNo;
        public String remark;
    }

    public static class StockCreateRequest {
        public Long productId;
        public String productCode;
        public String productName;
        public Long materialId;
        public BigDecimal currentQty;
        public BigDecimal lockedQty;
        public BigDecimal safetyStock;
        public BigDecimal stockAmount;
        public String sourceNo;
        public String remark;
    }

    public static class TransactionCreateRequest {
        public String itemType;
        public Long itemId;
        public String txnType;
        public BigDecimal qty;
        public String sourceType;
        public String sourceNo;
        public String remark;
    }

    public static class ProductMovementRequest {
        public String movementType;
        public BigDecimal qty;
        public String operator;
    }

    private Product resolveProduct(StockCreateRequest req) {
        if (req.productId != null) {
            return productRepository.findById(req.productId).orElseThrow(() -> new BusinessException("产品不存在"));
        }
        if (req.productCode != null && !req.productCode.isBlank()) {
            return productRepository.findAll().stream()
                    .filter(p -> !Boolean.TRUE.equals(p.deleted))
                    .filter(p -> req.productCode.equals(p.code))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("产品编号不存在"));
        }
        if (req.productName != null && !req.productName.isBlank()) {
            return productRepository.findAll().stream()
                    .filter(p -> !Boolean.TRUE.equals(p.deleted))
                    .filter(p -> req.productName.equals(p.name))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("产品不存在"));
        }
        throw new BusinessException("请选择产品");
    }
}
