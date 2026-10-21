-- Preserve existing anonymous history; new employee registrations store identity snapshots.
BEGIN;
ALTER TABLE inventory_txn ADD COLUMN IF NOT EXISTS operator_user_id bigint;
ALTER TABLE inventory_txn ADD COLUMN IF NOT EXISTS operator_username varchar(64);
ALTER TABLE material_scan_entry ADD COLUMN IF NOT EXISTS operator_user_id bigint;
ALTER TABLE material_scan_entry ADD COLUMN IF NOT EXISTS operator_name varchar(100);
COMMIT;
