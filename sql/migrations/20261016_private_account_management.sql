-- Account visibility is enforced by the server. Existing accounts remain normally visible.
BEGIN;
SET LOCAL lock_timeout = '10s';
ALTER TABLE public.sys_user ADD COLUMN IF NOT EXISTS management_private boolean NOT NULL DEFAULT false;
COMMIT;
