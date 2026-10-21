package com.company.oms.sales;

import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(95)
public class SalesOrderDeliveryStateMigration implements CommandLineRunner {
    private final SalesService salesService;

    @Value("${oms.migration.legacy-backfills-enabled:false}")
    private boolean enabled;

    public SalesOrderDeliveryStateMigration(SalesService salesService) {
        this.salesService = salesService;
    }

    @Override
    public void run(String... args) {
        if (!enabled) return;
        salesService.backfillOrderDeliveryStates();
    }
}
