package com.company.oms.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface QuotationItemRepository extends JpaRepository<QuotationItem, Long> {
    List<QuotationItem> findByQuotationIdAndDeletedFalse(Long quotationId);

    List<QuotationItem> findByQuotationId(Long quotationId);

    List<QuotationItem> findByQuotationIdInAndDeletedFalse(List<Long> quotationIds);

    boolean existsByProductIdAndDeletedFalse(Long productId);
}
