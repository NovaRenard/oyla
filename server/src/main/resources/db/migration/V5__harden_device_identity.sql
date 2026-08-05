-- SaaS devices are installation identities, not anonymous rows. V4 already creates every
-- device through activation; this makes that invariant explicit for future SQL callers too.
ALTER TABLE devices
    ADD CONSTRAINT chk_devices_identity_present
    CHECK (device_uid IS NOT NULL AND token_hash IS NOT NULL);

-- The UNIQUE constraint on device_uid is the durable duplicate-prevention layer; this index
-- makes the guarded lookup during activation explicit and efficient on large installations.
CREATE INDEX IF NOT EXISTS idx_devices_device_uid ON devices(device_uid);
