package com.company.oms.inventory;

import com.company.oms.common.BusinessException;
import com.company.oms.common.MoneyUtil;
import com.company.oms.masterdata.Material;
import com.company.oms.masterdata.Product;
import com.company.oms.notify.NotifyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class InventoryService {
    private final InventoryStockRepository productStockRepository;
    private final MaterialStockRepository materialStockRepository;
    private final InventoryTxnRepository txnRepository;
    private final NotifyService notifyService;

    public InventoryService(InventoryStockRepository productStockRepository,
                            MaterialStockRepository materialStockRepository,
                            InventoryTxnRepository txnRepository,
                            NotifyService notifyService) {
        this.productStockRepository = productStockRepository;
        this.materialStockRepository = materialStockRepository;
        this.txnRepository = txnRepository;
        this.notifyService = notifyService;
    }

    public InventoryStock ensureProductStock(Product product) {
        return productStockRepository.findByProductIdAndDeletedFalse(product.id).orElseGet(() -> {
            InventoryStock s = new InventoryStock();
            s.productId = product.id;
            s.productCode = product.code;
            s.productName = product.name;
            s.currentQty = BigDecimal.ZERO;
            s.lockedQty = BigDecimal.ZERO;
            s.safetyStock = MoneyUtil.nvl(product.safetyStock);
            s.stockAmount = BigDecimal.ZERO;
            return productStockRepository.save(s);
        });
    }

    public MaterialStock ensureMaterialStock(Material material) {
        return materialStockRepository.findByMaterialIdAndDeletedFalse(material.id).orElseGet(() -> {
            MaterialStock s = new MaterialStock();
            s.materialId = material.id;
            s.materialCode = material.code;
            s.materialName = material.name;
            s.currentQty = BigDecimal.ZERO;
            s.lockedQty = BigDecimal.ZERO;
            s.safetyStock = MoneyUtil.nvl(material.safetyStock);
            s.stockAmount = BigDecimal.ZERO;
            return materialStockRepository.save(s);
        });
    }

    public BigDecimal productAvailableQty(Long productId) {
        InventoryStock s = productStockRepository.findByProductIdAndDeletedFalse(productId).orElse(null);
        if (s == null) return BigDecimal.ZERO;
        return MoneyUtil.nvl(s.currentQty).subtract(MoneyUtil.nvl(s.lockedQty));
    }

    public BigDecimal materialAvailableQty(Long materialId) {
        MaterialStock s = materialStockRepository.findByMaterialIdAndDeletedFalse(materialId).orElse(null);
        if (s == null) return BigDecimal.ZERO;
        return MoneyUtil.nvl(s.currentQty).subtract(MoneyUtil.nvl(s.lockedQty));
    }

    @Transactional
    public InventoryStock createProductStock(Product product, BigDecimal currentQty, BigDecimal lockedQty,
                                             BigDecimal safetyStock, BigDecimal stockAmount,
                                             String sourceNo, String remark) {
        productStockRepository.findByProductIdAndDeletedFalse(product.id).ifPresent(s -> {
            throw new BusinessException("该产品库存台账已存在");
        });
        InventoryStock s = new InventoryStock();
        s.productId = product.id;
        s.productCode = product.code;
        s.productName = product.name;
        s.currentQty = BigDecimal.ZERO;
        s.lockedQty = MoneyUtil.nvl(lockedQty);
        s.safetyStock = safetyStock == null ? MoneyUtil.nvl(product.safetyStock) : safetyStock;
        s.stockAmount = MoneyUtil.nvl(stockAmount);
        productStockRepository.save(s);

        BigDecimal targetQty = MoneyUtil.nvl(currentQty);
        s.currentQty = targetQty;
        productStockRepository.save(s);
        saveTxn("PRODUCT", product.id, product.code, product.name, "ADJUST", targetQty,
                BigDecimal.ZERO, targetQty, "INIT", sourceNo, blankDefault(remark, "新增成品库存"));
        checkProductSafety(s);
        return s;
    }

    @Transactional
    public MaterialStock createMaterialStock(Material material, BigDecimal currentQty, BigDecimal lockedQty,
                                             BigDecimal safetyStock, BigDecimal stockAmount,
                                             String sourceNo, String remark) {
        materialStockRepository.findByMaterialIdAndDeletedFalse(material.id).ifPresent(s -> {
            throw new BusinessException("该材料库存台账已存在");
        });
        MaterialStock s = new MaterialStock();
        s.materialId = material.id;
        s.materialCode = material.code;
        s.materialName = material.name;
        s.currentQty = BigDecimal.ZERO;
        s.lockedQty = MoneyUtil.nvl(lockedQty);
        s.safetyStock = safetyStock == null ? MoneyUtil.nvl(material.safetyStock) : safetyStock;
        s.stockAmount = MoneyUtil.nvl(stockAmount);
        materialStockRepository.save(s);

        BigDecimal targetQty = MoneyUtil.nvl(currentQty);
        s.currentQty = targetQty;
        materialStockRepository.save(s);
        saveTxn("MATERIAL", material.id, material.code, material.name, "ADJUST", targetQty,
                BigDecimal.ZERO, targetQty, "INIT", sourceNo, blankDefault(remark, "新增原料库存"));
        checkMaterialSafety(s);
        return s;
    }

    @Transactional
    public InventoryStock registerProductMovement(Long stockId, String movementType, BigDecimal qty, String operator) {
        InventoryStock stock = productStockRepository.findById(stockId)
                .filter(s -> s.deleted == null || !s.deleted)
                .orElseThrow(() -> new BusinessException("成品库存不存在"));
        String normalizedType = movementType == null ? "" : movementType.trim().toUpperCase();
        if (!"IN".equals(normalizedType) && !"OUT".equals(normalizedType)) {
            throw new BusinessException("操作只能选择出库或入库");
        }
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("数量必须大于 0");
        }
        BigDecimal before = MoneyUtil.nvl(stock.currentQty);
        BigDecimal after = "IN".equals(normalizedType) ? before.add(qty) : before.subtract(qty);
        if (after.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("出库数量不能超过当前库存");
        }
        stock.currentQty = after;
        productStockRepository.save(stock);
        saveTxn("PRODUCT", stock.productId, stock.productCode, stock.productName, normalizedType, qty,
                before, after, "MANUAL", null, "出入库管理", operator);
        return stock;
    }

    @Transactional
    public InventoryStock createManualProductTransaction(Product product, String txnType, BigDecimal qty,
                                                        String sourceType, String sourceNo, String remark) {
        if (txnType == null || txnType.isBlank()) throw new BusinessException("请选择库存动作");
        return applyProductTransaction(product, txnType, qty, sourceType, sourceNo, remark);
    }

    @Transactional
    public MaterialStock createManualMaterialTransaction(Material material, String txnType, BigDecimal qty,
                                                        String sourceType, String sourceNo, String remark) {
        if (txnType == null || txnType.isBlank()) throw new BusinessException("请选择库存动作");
        return applyMaterialTransaction(material, txnType, qty, sourceType, sourceNo, remark);
    }

    @Transactional
    public InventoryStock productInbound(Product product, BigDecimal qty, String sourceType, String sourceNo, String remark) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("入库数量必须大于 0");
        InventoryStock s = ensureProductStock(product);
        BigDecimal before = MoneyUtil.nvl(s.currentQty);
        s.currentQty = before.add(qty);
        productStockRepository.save(s);
        saveTxn("PRODUCT", product.id, product.code, product.name, "IN", qty, before, s.currentQty, sourceType, sourceNo, remark);
        checkProductSafety(s);
        return s;
    }

    @Transactional
    public InventoryStock productOutbound(Product product, BigDecimal qty, String sourceType, String sourceNo, String remark) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("出库数量必须大于 0");
        InventoryStock s = ensureProductStock(product);
        BigDecimal before = MoneyUtil.nvl(s.currentQty);
        BigDecimal available = before.subtract(MoneyUtil.nvl(s.lockedQty));
        if (available.compareTo(qty) < 0) {
            throw new BusinessException("成品库存不足：" + product.name + " 可用 " + available + "，需要 " + qty);
        }
        s.currentQty = before.subtract(qty);
        productStockRepository.save(s);
        saveTxn("PRODUCT", product.id, product.code, product.name, "OUT", qty, before, s.currentQty, sourceType, sourceNo, remark);
        checkProductSafety(s);
        return s;
    }

    @Transactional
    public MaterialStock materialInbound(Material material, BigDecimal qty, String sourceType, String sourceNo, String remark) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("入库数量必须大于 0");
        MaterialStock s = ensureMaterialStock(material);
        BigDecimal before = MoneyUtil.nvl(s.currentQty);
        s.currentQty = before.add(qty);
        materialStockRepository.save(s);
        saveTxn("MATERIAL", material.id, material.code, material.name, "IN", qty, before, s.currentQty, sourceType, sourceNo, remark);
        checkMaterialSafety(s);
        return s;
    }

    @Transactional
    public MaterialStock materialOutbound(Material material, BigDecimal qty, String sourceType, String sourceNo, String remark) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("领料数量必须大于 0");
        MaterialStock s = ensureMaterialStock(material);
        BigDecimal before = MoneyUtil.nvl(s.currentQty);
        BigDecimal available = before.subtract(MoneyUtil.nvl(s.lockedQty));
        if (available.compareTo(qty) < 0) {
            throw new BusinessException("原料库存不足：" + material.name + " 可用 " + available + "，需要 " + qty);
        }
        s.currentQty = before.subtract(qty);
        materialStockRepository.save(s);
        saveTxn("MATERIAL", material.id, material.code, material.name, "OUT", qty, before, s.currentQty, sourceType, sourceNo, remark);
        checkMaterialSafety(s);
        return s;
    }

    @Transactional
    public InventoryStock adjustProduct(Product product, BigDecimal targetQty, String sourceNo, String remark) {
        InventoryStock s = ensureProductStock(product);
        BigDecimal before = MoneyUtil.nvl(s.currentQty);
        s.currentQty = MoneyUtil.nvl(targetQty);
        productStockRepository.save(s);
        saveTxn("PRODUCT", product.id, product.code, product.name, "ADJUST", s.currentQty.subtract(before), before, s.currentQty, "ADJUST", sourceNo, remark);
        checkProductSafety(s);
        return s;
    }

    @Transactional
    public MaterialStock adjustMaterial(Material material, BigDecimal targetQty, String sourceNo, String remark) {
        MaterialStock s = ensureMaterialStock(material);
        BigDecimal before = MoneyUtil.nvl(s.currentQty);
        s.currentQty = MoneyUtil.nvl(targetQty);
        materialStockRepository.save(s);
        saveTxn("MATERIAL", material.id, material.code, material.name, "ADJUST", s.currentQty.subtract(before), before, s.currentQty, "ADJUST", sourceNo, remark);
        checkMaterialSafety(s);
        return s;
    }

    private void saveTxn(String itemType, Long itemId, String itemCode, String itemName, String txnType,
                         BigDecimal qty, BigDecimal before, BigDecimal after, String sourceType, String sourceNo, String remark) {
        saveTxn(itemType, itemId, itemCode, itemName, txnType, qty, before, after, sourceType, sourceNo, remark, null);
    }

    private void saveTxn(String itemType, Long itemId, String itemCode, String itemName, String txnType,
                         BigDecimal qty, BigDecimal before, BigDecimal after, String sourceType, String sourceNo,
                         String remark, String operator) {
        InventoryTxn txn = new InventoryTxn();
        txn.itemType = itemType;
        txn.itemId = itemId;
        txn.itemCode = itemCode;
        txn.itemName = itemName;
        txn.txnType = txnType;
        txn.qty = qty;
        txn.beforeQty = before;
        txn.afterQty = after;
        txn.sourceType = sourceType;
        txn.sourceNo = sourceNo;
        txn.remark = remark;
        txn.operator = operator;
        txnRepository.save(txn);
    }

    private void checkProductSafety(InventoryStock s) {
        if (MoneyUtil.nvl(s.currentQty).compareTo(MoneyUtil.nvl(s.safetyStock)) < 0) {
            notifyService.create("成品库存预警", s.productName + " 当前库存低于安全库存", "库存预警", "高", "PRODUCT", s.productCode);
        }
    }

    private void checkMaterialSafety(MaterialStock s) {
        if (MoneyUtil.nvl(s.currentQty).compareTo(MoneyUtil.nvl(s.safetyStock)) < 0) {
            notifyService.create("原料库存预警", s.materialName + " 当前库存低于安全库存", "库存预警", "高", "MATERIAL", s.materialCode);
        }
    }

    private InventoryStock applyProductTransaction(Product product, String txnType, BigDecimal qty,
                                                   String sourceType, String sourceNo, String remark) {
        String normalizedType = txnType.trim().toUpperCase();
        String normalizedSourceType = blankDefault(sourceType, "MANUAL");
        return switch (normalizedType) {
            case "IN" -> productInbound(product, qty, normalizedSourceType, sourceNo, remark);
            case "OUT" -> productOutbound(product, qty, normalizedSourceType, sourceNo, remark);
            case "ADJUST" -> adjustProduct(product, qty, sourceNo, remark);
            default -> throw new BusinessException("库存动作只能选择入库、出库或调整");
        };
    }

    private MaterialStock applyMaterialTransaction(Material material, String txnType, BigDecimal qty,
                                                   String sourceType, String sourceNo, String remark) {
        String normalizedType = txnType.trim().toUpperCase();
        String normalizedSourceType = blankDefault(sourceType, "MANUAL");
        return switch (normalizedType) {
            case "IN" -> materialInbound(material, qty, normalizedSourceType, sourceNo, remark);
            case "OUT" -> materialOutbound(material, qty, normalizedSourceType, sourceNo, remark);
            case "ADJUST" -> adjustMaterial(material, qty, sourceNo, remark);
            default -> throw new BusinessException("库存动作只能选择入库、出库或调整");
        };
    }

    private String blankDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
