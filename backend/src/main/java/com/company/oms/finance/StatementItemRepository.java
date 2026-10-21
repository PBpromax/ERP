package com.company.oms.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StatementItemRepository extends JpaRepository<StatementItem, Long> {
    List<StatementItem> findByReturnIdAndDeletedFalse(Long returnId);
    List<StatementItem> findByReturnIdIsNotNullAndDeletedFalse();
    List<StatementItem> findByStatementIdAndDeletedFalse(Long statementId);
    List<StatementItem> findByStatementIdInAndDeletedFalse(List<Long> statementIds);
    List<StatementItem> findByDeliveryIdAndDeletedFalse(Long deliveryId);
    List<StatementItem> findByDeliveryIdInAndDeletedFalse(List<Long> deliveryIds);
}
