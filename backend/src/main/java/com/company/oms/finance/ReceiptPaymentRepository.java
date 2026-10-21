package com.company.oms.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public interface ReceiptPaymentRepository extends JpaRepository<ReceiptPayment, Long> {
    interface GroupPaymentActivity {
        String getGroupKey();
        LocalDate getLatestPaymentDate();
        Long getUndatedPaymentCount();
    }

    @Query("select p.groupKey as groupKey, "
            + "max(case when p.remark is null or p.remark not like '%日期待核实%' then p.paymentDate else null end) as latestPaymentDate, "
            + "sum(case when p.paymentDate is null or p.remark like '%日期待核实%' then 1 else 0 end) as undatedPaymentCount "
            + "from ReceiptPayment p where p.deleted = false and p.amount > 0 "
            + "and (p.method is null or p.method not in :excludedMethods) "
            + "and (p.paymentDate is null or p.paymentDate <= :today) group by p.groupKey")
    List<GroupPaymentActivity> paymentActivityByGroup(LocalDate today, Collection<String> excludedMethods);

    interface GroupCreditTotal {
        String getGroupKey();
        BigDecimal getUnallocatedAmount();
    }

    @Query("select p.groupKey as groupKey, sum(coalesce(p.unallocatedAmount, 0)) as unallocatedAmount "
            + "from ReceiptPayment p where p.deleted = false group by p.groupKey")
    List<GroupCreditTotal> sumUnallocatedAmountByGroup();

    @Query("select coalesce(sum(p.amount), 0) from ReceiptPayment p where p.deleted = false "
            + "and p.groupKey in :groupKeys and p.paymentDate >= :start and p.paymentDate < :end")
    BigDecimal sumPaymentsInPeriod(Collection<String> groupKeys, LocalDate start, LocalDate end);

    List<ReceiptPayment> findByGroupKeyAndDeletedFalseOrderByPaymentDateAscIdAsc(String groupKey);
    Optional<ReceiptPayment> findByGroupKeyAndPaymentNoAndDeletedFalse(String groupKey, String paymentNo);
    boolean existsByPaymentNo(String paymentNo);
}
