package com.company.oms.masterdata;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerContactRepository extends JpaRepository<CustomerContact, Long> {
    List<CustomerContact> findByCustomerIdAndDeletedFalseOrderByPrimaryContactDescIdAsc(Long customerId);
    List<CustomerContact> findByCustomerIdInAndDeletedFalse(List<Long> customerIds);
    List<CustomerContact> findByCustomerId(Long customerId);
    boolean existsByCustomerIdAndNameAndDeletedFalse(Long customerId, String name);
}
