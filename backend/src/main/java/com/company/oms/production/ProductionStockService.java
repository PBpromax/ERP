package com.company.oms.production;

import com.company.oms.masterdata.Product;
import com.company.oms.masterdata.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProductionStockService {
    private final SemiFinishedStockRepository stockRepository;
    private final ProductRepository productRepository;

    public ProductionStockService(SemiFinishedStockRepository stockRepository,
                                  ProductRepository productRepository) {
        this.stockRepository = stockRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public List<SemiFinishedStock> listSyncedFromProducts() {
        syncFromProducts();
        List<SemiFinishedStock> stocks = stockRepository.findByDeletedFalseOrderByCodeAsc();
        Map<String, List<String>> aliasesByCode = productRepository.findByDeletedFalseOrderByCreatedAtDescIdDesc().stream()
                .filter(product -> product.code != null && !product.code.isBlank())
                .collect(Collectors.toMap(product -> product.code.trim(), product -> product.aliases, (first, second) -> first));
        stocks.forEach(stock -> stock.aliases = aliasesByCode.getOrDefault(stock.code, List.of()));
        return stocks;
    }

    private void syncFromProducts() {
        Map<String, SemiFinishedStock> stockByCode = stockRepository.findAll().stream()
                .filter(stock -> stock.code != null && !stock.code.isBlank())
                .collect(Collectors.toMap(stock -> stock.code.trim(), stock -> stock, (first, second) -> first));

        for (Product product : productRepository.findByDeletedFalseOrderByCreatedAtDescIdDesc()) {
            if (product.code == null || product.code.isBlank()) continue;
            String code = product.code.trim();
            SemiFinishedStock stock = stockByCode.get(code);
            if (stock == null) {
                stock = new SemiFinishedStock();
                stock.code = code;
                stock.name = product.name;
                stock.spec = product.spec;
                stock.stockQty = BigDecimal.ZERO;
                stock.deleted = false;
                stockRepository.save(stock);
                continue;
            }
            if (Boolean.TRUE.equals(stock.deleted)) {
                stock.deleted = false;
            }
            stock.name = product.name;
            stock.spec = product.spec;
            stockRepository.save(stock);
        }
    }
}
