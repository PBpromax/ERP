-- Apply explicitly to the LOCAL snapshot database before starting with ddl-auto=validate.
BEGIN;
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS permission_mode varchar(24) NOT NULL DEFAULT 'CUSTOM';
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS owner boolean NOT NULL DEFAULT false;
CREATE TABLE IF NOT EXISTS sys_user_menu_permission (
    user_id bigint NOT NULL REFERENCES sys_user(id),
    menu_code varchar(64) NOT NULL,
    PRIMARY KEY (user_id, menu_code)
);
CREATE TABLE IF NOT EXISTS sys_login_session (
    id varchar(36) PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES sys_user(id),
    expires_at timestamp(6) with time zone NOT NULL,
    revoked boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS sys_login_session_user_id_idx ON sys_login_session(user_id);
CREATE UNIQUE INDEX IF NOT EXISTS sys_user_one_owner_idx ON sys_user(owner) WHERE owner = true;
CREATE UNIQUE INDEX IF NOT EXISTS sys_user_username_casefold_idx ON sys_user(lower(username));
COMMIT;
