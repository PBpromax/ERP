BEGIN;
ALTER TABLE statement ADD COLUMN IF NOT EXISTS collection_excluded boolean;
ALTER TABLE statement ADD COLUMN IF NOT EXISTS source_sort_date date;
ALTER TABLE statement ADD COLUMN IF NOT EXISTS source_sequence bigint;
ALTER TABLE statement ADD COLUMN IF NOT EXISTS source_date_text varchar(255);
ALTER TABLE receipt_payment ADD COLUMN IF NOT EXISTS source_sort_date date;
ALTER TABLE receipt_payment ADD COLUMN IF NOT EXISTS source_sequence bigint;
ALTER TABLE receipt_payment ADD COLUMN IF NOT EXISTS source_date_text varchar(255);
COMMIT;
