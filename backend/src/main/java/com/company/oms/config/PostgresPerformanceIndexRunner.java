package com.company.oms.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Component
@Profile({"postgres", "prod"})
public class PostgresPerformanceIndexRunner implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(PostgresPerformanceIndexRunner.class);
    private static final List<String> INDEXES = List.of(
            "CREATE INDEX IF NOT EXISTS idx_sales_order_active_no ON sales_order (deleted, order_no DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS idx_sales_order_active_date_no ON sales_order (deleted, order_date DESC, order_no DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS idx_sales_order_customer_active ON sales_order (customer_id, deleted, id DESC)",
            "CREATE INDEX IF NOT EXISTS idx_sales_order_item_order_active ON sales_order_item (order_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_sales_order_item_active_order ON sales_order_item (deleted, order_id, id)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_note_order_active ON delivery_note (order_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_note_customer_status_active ON delivery_note (customer_id, status, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_note_date_status_active ON delivery_note (delivery_date, status, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_note_item_delivery_active ON delivery_note_item (delivery_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_delivery_note_item_order_item_active ON delivery_note_item (order_item_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_statement_customer_active ON statement (customer_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_statement_month_active ON statement (statement_month, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_statement_item_statement_active ON statement_item (statement_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_statement_item_delivery_active ON statement_item (delivery_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_invoice_statement_active ON invoice (statement_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_invoice_customer_active ON invoice (customer_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_receipt_statement_active ON receipt (statement_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_receipt_ledger_batch_active ON receipt (ledger_account_id, payment_batch_no, customer_level_payment, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_customer_ledger_entry_account_active ON customer_ledger_entry (account_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_customer_ledger_entry_statement_active ON customer_ledger_entry (linked_statement_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_customer_contact_customer_active ON customer_contact (customer_id, deleted)",
            "CREATE INDEX IF NOT EXISTS idx_production_plan_order_item_active ON production_plan (source_order_id, order_item_id, deleted)"
    );

    private final JdbcTemplate jdbcTemplate;

    public PostgresPerformanceIndexRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        for (String sql : INDEXES) {
            try {
                jdbcTemplate.execute(sql);
            } catch (DataAccessException exception) {
                log.warn("Skipping performance index that is not supported by this schema: {}", sql, exception);
            }
        }
    }
}
