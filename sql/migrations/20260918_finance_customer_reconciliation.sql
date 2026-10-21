-- Schema only. Enterprise customer-to-ledger mappings are maintained privately.
BEGIN;
ALTER TABLE customer_ledger_account ADD COLUMN IF NOT EXISTS customer_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_customer_ledger_account_customer_id ON customer_ledger_account (customer_id);
COMMIT;
