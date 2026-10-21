package com.company.oms.sales;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    List<Quotation> findByDeletedFalseOrderByCreatedAtDescIdDesc();

    List<Quotation> findByQuoteNoStartingWith(String prefix);

    boolean existsByQuoteNo(String quoteNo);

    boolean existsByQuoteNoAndIdNot(String quoteNo, Long id);
    Optional<Quotation> findBySourceKeyAndDeletedFalse(String sourceKey);
}
