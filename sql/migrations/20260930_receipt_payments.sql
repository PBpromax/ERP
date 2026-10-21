BEGIN;
CREATE TABLE IF NOT EXISTS receipt_payment (
 id BIGSERIAL PRIMARY KEY, created_at timestamp, updated_at timestamp,
 created_by varchar(255), updated_by varchar(255), deleted boolean NOT NULL DEFAULT false,
 payment_no varchar(100) NOT NULL UNIQUE, group_key varchar(200) NOT NULL,
 customer_id bigint, statement_id bigint, payment_date date,
 amount numeric(18,2), unallocated_amount numeric(18,2), method varchar(255),
 remark varchar(2000), source_key varchar(240) UNIQUE, source_hash varchar(64)
);
CREATE INDEX IF NOT EXISTS idx_payment_group ON receipt_payment(group_key, deleted, payment_date);
CREATE TABLE IF NOT EXISTS finance_import_row (
 source_key varchar(240) PRIMARY KEY, source_hash varchar(64) NOT NULL,
 group_key varchar(200), source_json text NOT NULL, statement_id bigint, payment_id bigint,
 created_at timestamp NOT NULL DEFAULT now()
);
ALTER TABLE receipt ADD COLUMN IF NOT EXISTS payment_id bigint REFERENCES receipt_payment(id);
ALTER TABLE receipt ADD COLUMN IF NOT EXISTS allocation_date date;
CREATE INDEX IF NOT EXISTS idx_receipt_payment ON receipt(payment_id, deleted);
ALTER TABLE statement ADD COLUMN IF NOT EXISTS source_amount_only boolean;
ALTER TABLE statement ADD COLUMN IF NOT EXISTS receipt_rebuilt boolean;
ALTER TABLE statement ADD COLUMN IF NOT EXISTS source_invoiced_amount numeric(18,2);
ALTER TABLE statement ADD COLUMN IF NOT EXISTS ledger_account_id bigint;
ALTER TABLE customer_ledger_account ADD COLUMN IF NOT EXISTS reconciliation_note varchar(2000);
ALTER TABLE customer_ledger_account ADD COLUMN IF NOT EXISTS source_net_amount numeric(18,2);
COMMIT;
