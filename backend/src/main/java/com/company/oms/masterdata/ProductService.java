package com.company.oms.masterdata;

import com.company.oms.common.BusinessException;
import com.company.oms.inventory.InventoryStockRepository;
import com.company.oms.production.ProductionPlanRepository;
import com.company.oms.quality.QualityReportRepository;
import com.company.oms.sales.QuotationItemRepository;
import com.company.oms.sales.SalesOrderItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

@Service
public class ProductService {
    private final ProductRepository productRepository;
    private final InventoryStockRepository inventoryStockRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final ProductionPlanRepository productionPlanRepository;
    private final QualityReportRepository qualityReportRepository;

    public ProductService(ProductRepository productRepository,
                          InventoryStockRepository inventoryStockRepository,
                          SalesOrderItemRepository salesOrderItemRepository,
                          QuotationItemRepository quotationItemRepository,
                          ProductionPlanRepository productionPlanRepository,
                          QualityReportRepository qualityReportRepository) {
        this.productRepository = productRepository;
        this.inventoryStockRepository = inventoryStockRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.quotationItemRepository = quotationItemRepository;
        this.productionPlanRepository = productionPlanRepository;
        this.qualityReportRepository = qualityReportRepository;
    }

    public List<Product> list() {
        return productRepository.findByDeletedFalseOrderByCreatedAtDescIdDesc();
    }

    public Product get(Long id) {
        return productRepository.findById(id)
                .filter(product -> !Boolean.TRUE.equals(product.deleted))
                .orElseThrow(() -> new BusinessException("产品不存在"));
    }

    @Transactional
    public Product create(Product product) {
        product.id = null;
        normalize(product);
        if (product.code != null && productRepository.existsByCode(product.code)) {
            throw new BusinessException("该产品编码已存在，请检查规格组合。");
        }
        return productRepository.save(product);
    }

    @Transactional
    public Product update(Long id, Product incoming) {
        Product existing = get(id);
        normalize(incoming);
        if (incoming.code != null && productRepository.existsByCodeAndIdNot(incoming.code, id)) {
            throw new BusinessException("该产品编码已存在，请检查规格组合。");
        }
        existing.code = incoming.code;
        existing.name = incoming.name;
        existing.categoryCode = incoming.categoryCode;
        existing.categoryName = incoming.categoryName;
        existing.spec = incoming.spec;
        existing.unit = incoming.unit;
        existing.defaultPrice = incoming.defaultPrice;
        existing.costPrice = incoming.costPrice;
        existing.safetyStock = incoming.safetyStock;
        existing.enabled = incoming.enabled;
        existing.status = incoming.status;
        existing.remark = incoming.remark;
        existing.aliases = new ArrayList<>(incoming.aliases);
        existing.tagValues = incoming.tagValues;
        return productRepository.save(existing);
    }

    @Transactional
    public Product setStatus(Long id, String status) {
        Product product = get(id);
        String normalized = normalizeStatus(status);
        product.status = normalized;
        product.enabled = "enabled".equals(normalized);
        return productRepository.save(product);
    }

    @Transactional
    public void delete(Long id, boolean force) {
        Product product = get(id);
        ProductReferenceInfo references = references(product.id);
        if (references.referenced && !force) {
            throw new BusinessException(references.usedInOrders
                    ? "该产品已经用于销售订单，确认后才可删除产品档案；历史订单不会被删除"
                    : "该产品已经被业务记录使用，确认后才可删除产品档案；历史记录不会被删除");
        }
        if (references.referenced) {
            product.deleted = true;
            product.status = "disabled";
            product.enabled = false;
            productRepository.save(product);
            return;
        }
        productRepository.delete(product);
    }

    public ProductReferenceInfo references(Long productId) {
        ProductReferenceInfo info = new ProductReferenceInfo();
        if (productId == null) return info;
        info.usedInOrders = salesOrderItemRepository.existsByProductIdAndDeletedFalse(productId);
        info.usedInQuotations = quotationItemRepository.existsByProductIdAndDeletedFalse(productId);
        info.usedInInventory = inventoryStockRepository.existsByProductIdAndDeletedFalse(productId);
        info.usedInProduction = productionPlanRepository.existsByProductIdAndDeletedFalse(productId);
        info.usedInQuality = qualityReportRepository.existsByProductIdAndDeletedFalse(productId);
        info.referenced = info.usedInOrders || info.usedInQuotations || info.usedInInventory
                || info.usedInProduction || info.usedInQuality;
        return info;
    }

    public boolean isReferenced(Long productId) {
        return references(productId).referenced;
    }

    public static class ProductReferenceInfo {
        public boolean referenced;
        public boolean usedInOrders;
        public boolean usedInQuotations;
        public boolean usedInInventory;
        public boolean usedInProduction;
        public boolean usedInQuality;
    }

    private void normalize(Product product) {
        product.code = trim(product.code);
        product.name = trim(product.name);
        product.categoryCode = trim(product.categoryCode);
        product.categoryName = trim(product.categoryName);
        product.spec = trim(product.spec);
        product.unit = trim(product.unit);
        product.remark = trim(product.remark);
        product.aliases = normalizeAliases(product.aliases);
        product.tagValues = trim(product.tagValues);
        if (product.categoryCode == null || product.categoryCode.isBlank()) throw new BusinessException("请选择产品大类");
        if (product.name == null || product.name.isBlank()) throw new BusinessException("产品名称不能为空");
        if (product.unit == null || product.unit.isBlank()) throw new BusinessException("单位不能为空");
        if (product.defaultPrice == null) product.defaultPrice = BigDecimal.ZERO;
        if (product.costPrice == null) product.costPrice = BigDecimal.ZERO;
        if (product.safetyStock == null) product.safetyStock = BigDecimal.ZERO;
        if (product.defaultPrice.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("默认价不能小于0");
        if (product.costPrice.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("成本价不能小于0");
        if (product.safetyStock.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("安全库存不能小于0");
        if (product.safetyStock.stripTrailingZeros().scale() > 0) throw new BusinessException("安全库存必须为整数");
        product.status = normalizeStatus(product.status);
        product.enabled = "enabled".equals(product.status);
    }

    private String normalizeStatus(String status) {
        return "disabled".equals(status) ? "disabled" : "enabled";
    }

    private String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> normalizeAliases(List<String> aliases) {
        if (aliases == null) return new ArrayList<>();
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String alias : aliases) {
            String normalized = trim(alias);
            if (normalized == null) continue;
            if (normalized.length() > 200) throw new BusinessException("单个产品别称不能超过200个字符");
            unique.putIfAbsent(normalized.toLowerCase(Locale.ROOT), normalized);
        }
        if (unique.size() > 50) throw new BusinessException("每个产品最多添加50个别称");
        return new ArrayList<>(unique.values());
    }
}
