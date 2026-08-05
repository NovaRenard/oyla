CREATE TABLE centers (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(180) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Almaty',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_centers_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED'))
);

CREATE INDEX idx_centers_status ON centers(status);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'BLOCKED')),
    CONSTRAINT chk_users_email_lowercase CHECK (email = LOWER(email))
);

CREATE INDEX idx_users_status ON users(status);

CREATE TABLE center_memberships (
    id UUID PRIMARY KEY,
    center_id UUID NOT NULL REFERENCES centers(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_center_memberships_center_user UNIQUE (center_id, user_id),
    CONSTRAINT chk_center_memberships_role CHECK (role IN ('OWNER', 'ADMIN', 'METHODIST', 'SPECIALIST')),
    CONSTRAINT chk_center_memberships_status CHECK (status IN ('ACTIVE', 'INVITED', 'BLOCKED'))
);

CREATE INDEX idx_center_memberships_center_status ON center_memberships(center_id, status);
CREATE INDEX idx_center_memberships_user_status ON center_memberships(user_id, status);

CREATE OR REPLACE FUNCTION ensure_center_has_owner() RETURNS TRIGGER AS $$
DECLARE
    checked_center_id UUID;
BEGIN
    checked_center_id := COALESCE(NEW.center_id, OLD.center_id);
    IF EXISTS (SELECT 1 FROM centers WHERE id = checked_center_id)
       AND NOT EXISTS (
            SELECT 1 FROM center_memberships
            WHERE center_id = checked_center_id AND role = 'OWNER'
       ) THEN
        RAISE EXCEPTION 'Center % must retain at least one OWNER membership', checked_center_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_center_must_have_owner
AFTER INSERT OR UPDATE OR DELETE ON center_memberships
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION ensure_center_has_owner();

CREATE OR REPLACE FUNCTION ensure_new_center_has_owner() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM center_memberships WHERE center_id = NEW.id AND role = 'OWNER'
    ) THEN
        RAISE EXCEPTION 'Center % must have an OWNER membership', NEW.id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER trg_new_center_must_have_owner
AFTER INSERT ON centers
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION ensure_new_center_has_owner();

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_refresh_tokens_user_active ON refresh_tokens(user_id, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    center_id UUID NOT NULL REFERENCES centers(id) ON DELETE RESTRICT,
    name VARCHAR(160) NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    device_uid VARCHAR(255) UNIQUE,
    token_hash VARCHAR(128) UNIQUE,
    token_revoked_at TIMESTAMP WITH TIME ZONE,
    app_version VARCHAR(80),
    android_version VARCHAR(80),
    model VARCHAR(160),
    last_seen_at TIMESTAMP WITH TIME ZONE,
    activated_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_devices_role CHECK (role IN ('SPECIALIST', 'CHILD')),
    CONSTRAINT chk_devices_status CHECK (status IN ('ACTIVE', 'BLOCKED', 'UNLINKED'))
);

CREATE INDEX idx_devices_center_id ON devices(center_id);
CREATE INDEX idx_devices_center_status ON devices(center_id, status);
CREATE INDEX idx_devices_center_role ON devices(center_id, role);
CREATE INDEX idx_devices_last_seen_at ON devices(last_seen_at);

CREATE TABLE device_activation_codes (
    id UUID PRIMARY KEY,
    center_id UUID NOT NULL REFERENCES centers(id) ON DELETE RESTRICT,
    created_by_user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    device_name VARCHAR(160) NOT NULL,
    device_role VARCHAR(16) NOT NULL,
    code_hash VARCHAR(128) NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_device_activation_codes_role CHECK (device_role IN ('SPECIALIST', 'CHILD')),
    CONSTRAINT chk_device_activation_codes_status CHECK (status IN ('PENDING', 'USED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX idx_device_activation_codes_center_status ON device_activation_codes(center_id, status);
CREATE INDEX idx_device_activation_codes_pending_expiry ON device_activation_codes(status, expires_at) WHERE status = 'PENDING';
CREATE INDEX idx_device_activation_codes_lookup ON device_activation_codes(code_hash);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    center_id UUID REFERENCES centers(id) ON DELETE SET NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id UUID,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    ip_address VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_audit_logs_actor_type CHECK (actor_type IN ('USER', 'DEVICE', 'SYSTEM'))
);

CREATE INDEX idx_audit_logs_center_created_at ON audit_logs(center_id, created_at DESC);
CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_action ON audit_logs(action);

-- MVP sessions are deliberately not moved into SaaS scope in this change.  The nullable
-- column is the first migration step; it must be backfilled and made NOT NULL only after
-- Android session creation is switched to the activated device identity.
ALTER TABLE sessions ADD COLUMN center_id UUID REFERENCES centers(id) ON DELETE SET NULL;
CREATE INDEX idx_sessions_center_id ON sessions(center_id);
