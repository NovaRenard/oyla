-- Generic external integration ownership. Credentials are stored as an AES-GCM
-- envelope; neither plaintext secrets nor external identifiers become Oyla IDs.
CREATE TABLE external_integrations (
    id UUID PRIMARY KEY,
    center_id UUID NOT NULL REFERENCES centers(id) ON DELETE RESTRICT,
    type VARCHAR(32) NOT NULL,
    name VARCHAR(160) NOT NULL,
    base_url VARCHAR(2048) NOT NULL,
    encrypted_credential TEXT,
    credential_nonce VARCHAR(64),
    credential_key_version VARCHAR(32),
    status VARCHAR(16) NOT NULL,
    last_connection_check_at TIMESTAMP WITH TIME ZONE,
    last_successful_sync_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_external_integrations_type CHECK (type IN ('CUSTOM_CRM')),
    CONSTRAINT chk_external_integrations_status CHECK (status IN ('ACTIVE', 'DISABLED', 'ERROR')),
    CONSTRAINT chk_external_integrations_credential_envelope CHECK (
        (encrypted_credential IS NULL AND credential_nonce IS NULL AND credential_key_version IS NULL)
        OR
        (encrypted_credential IS NOT NULL AND credential_nonce IS NOT NULL AND credential_key_version IS NOT NULL)
    ),
    CONSTRAINT chk_external_integrations_name CHECK (char_length(trim(name)) > 0)
);

CREATE INDEX idx_external_integrations_center ON external_integrations(center_id);
CREATE INDEX idx_external_integrations_center_type_status ON external_integrations(center_id, type, status);
CREATE INDEX idx_external_integrations_status ON external_integrations(status);

CREATE TABLE external_entity_links (
    id UUID PRIMARY KEY,
    center_id UUID NOT NULL REFERENCES centers(id) ON DELETE RESTRICT,
    integration_id UUID NOT NULL REFERENCES external_integrations(id) ON DELETE RESTRICT,
    entity_type VARCHAR(32) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    local_entity_id UUID NOT NULL REFERENCES children(id) ON DELETE RESTRICT,
    last_synced_at TIMESTAMP WITH TIME ZONE,
    external_updated_at TIMESTAMP WITH TIME ZONE,
    -- TRUE only while the current archive state was caused by CRM. A manual
    -- Oyla archive/restore clears this marker, so a later CRM ACTIVE does not
    -- silently reverse an Oyla decision.
    crm_archived_local BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_external_entity_links_type CHECK (entity_type IN ('CHILD')),
    CONSTRAINT chk_external_entity_links_external_id CHECK (char_length(trim(external_id)) > 0),
    CONSTRAINT uq_external_entity_links_external UNIQUE (integration_id, entity_type, external_id),
    CONSTRAINT uq_external_entity_links_local UNIQUE (integration_id, entity_type, local_entity_id)
);

CREATE INDEX idx_external_entity_links_center ON external_entity_links(center_id);
CREATE INDEX idx_external_entity_links_center_local ON external_entity_links(center_id, local_entity_id);
CREATE INDEX idx_external_entity_links_integration_local ON external_entity_links(integration_id, local_entity_id);
