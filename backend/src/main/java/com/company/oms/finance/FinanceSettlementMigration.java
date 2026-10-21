package com.company.oms.finance;

import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(90)
public class FinanceSettlementMigration implements CommandLineRunner {
    private final FinanceService financeService;

    @Value("${oms.migration.legacy-backfills-enabled:false}")
    private boolean enabled;

    public FinanceSettlementMigration(FinanceService financeService) {
        this.financeService = financeService;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        financeService.backfillSettlementState();
    }
}
