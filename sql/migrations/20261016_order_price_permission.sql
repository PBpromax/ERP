-- Existing accounts retain their current order access. The flag is independent of menu mode.
BEGIN;
SET LOCAL lock_timeout = '10s';
ALTER TABLE public.sys_user ADD COLUMN IF NOT EXISTS order_prices_visible boolean NOT NULL DEFAULT true;
COMMIT;
