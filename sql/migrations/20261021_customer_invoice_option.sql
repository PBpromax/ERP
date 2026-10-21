-- Nullable for existing customers: their API default is inferred from their existing credit code.
-- Do not rewrite credit codes, financial records or historical invoicing data.
BEGIN;
ALTER TABLE customer ADD COLUMN IF NOT EXISTS invoice_required boolean;
COMMIT;
