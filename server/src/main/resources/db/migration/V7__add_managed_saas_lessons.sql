ALTER TABLE sessions
    ADD COLUMN specialist_id UUID REFERENCES specialists(id) ON DELETE RESTRICT,
    ADD COLUMN specialist_device_uuid UUID REFERENCES devices(id) ON DELETE RESTRICT,
    ADD COLUMN started_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN is_managed BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE sessions
    ADD CONSTRAINT chk_managed_sessions_identity
    CHECK (
        NOT is_managed OR (
            center_id IS NOT NULL AND specialist_id IS NOT NULL AND specialist_device_uuid IS NOT NULL AND started_at IS NOT NULL
        )
    );

CREATE INDEX idx_sessions_managed_center_status ON sessions(center_id, status) WHERE is_managed = TRUE;
CREATE INDEX idx_sessions_managed_specialist_device ON sessions(specialist_device_uuid) WHERE is_managed = TRUE;

CREATE TABLE lesson_participants (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE RESTRICT,
    child_id UUID NOT NULL REFERENCES children(id) ON DELETE RESTRICT,
    device_id UUID NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_lesson_participants_session_child UNIQUE (session_id, child_id),
    CONSTRAINT uq_lesson_participants_session_device UNIQUE (session_id, device_id)
);

CREATE INDEX idx_lesson_participants_child ON lesson_participants(child_id);
CREATE INDEX idx_lesson_participants_device ON lesson_participants(device_id);
