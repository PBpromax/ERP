package com.company.oms.finance;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@Order(80)
public class SettlementInvoiceLabelRepair implements CommandLineRunner {
    private final FinanceService financeService;
    public SettlementInvoiceLabelRepair(FinanceService financeService) {
        this.financeService = financeService;
    }
    @Override
    public void run(String... args) {
        int repaired = financeService.repairSettlementLabelsImportedAsInvoices();
        if (repaired > 0) org.slf4j.LoggerFactory.getLogger(getClass())
                .info("Corrected {} settlement labels imported as invoice numbers; receivables and payments unchanged", repaired);
    }
}
