BEGIN;

ALTER TABLE customer_ledger_entry
    ADD COLUMN IF NOT EXISTS linked_statement_id BIGINT;

ALTER TABLE statement
    ADD COLUMN IF NOT EXISTS historical_received_amount NUMERIC(18, 2) DEFAULT 0;

ALTER TABLE statement_item
    ADD COLUMN IF NOT EXISTS adjustment_type VARCHAR(20);

CREATE INDEX IF NOT EXISTS idx_customer_ledger_entry_statement_active
    ON customer_ledger_entry (linked_statement_id, deleted);

UPDATE statement
SET historical_received_amount = 0
WHERE historical_received_amount IS NULL;

COMMIT;
