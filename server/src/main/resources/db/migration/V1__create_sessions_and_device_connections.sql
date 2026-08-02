CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    connection_code VARCHAR(4) NOT NULL,
    child_name VARCHAR(80) NOT NULL,
    status VARCHAR(32) NOT NULL,
    specialist_device_id VARCHAR(255) NOT NULL,
    specialist_token VARCHAR(255) NOT NULL,
    child_device_id VARCHAR(255),
    child_token VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    connected_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_sessions_connection_code ON sessions(connection_code);
CREATE INDEX idx_sessions_active_code ON sessions(connection_code, expires_at)
    WHERE status IN ('WAITING_FOR_CHILD', 'READY');
CREATE UNIQUE INDEX uq_sessions_active_connection_code ON sessions(connection_code)
    WHERE status IN ('WAITING_FOR_CHILD', 'READY');

CREATE TABLE device_connections (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id),
    device_id VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    connected BOOLEAN NOT NULL,
    last_seen_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_device_connections_session_device_role UNIQUE (session_id, device_id, role)
);

CREATE INDEX idx_device_connections_session_id ON device_connections(session_id);
