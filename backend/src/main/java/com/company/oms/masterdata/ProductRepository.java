package com.company.oms.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByDeletedFalseOrderByCreatedAtDescIdDesc();

    Optional<Product> findByCode(String code);

    Optional<Product> findByCodeAndDeletedFalse(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    boolean existsByCodeAndDeletedFalse(String code);

    boolean existsByCodeAndDeletedFalseAndIdNot(String code, Long id);
}
