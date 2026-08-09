CREATE TABLE children (
    id UUID PRIMARY KEY,
    center_id UUID NOT NULL REFERENCES centers(id) ON DELETE RESTRICT,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100),
    birth_date DATE,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_children_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_children_center_id ON children(center_id);
CREATE INDEX idx_children_center_status ON children(center_id, status);
CREATE INDEX idx_children_center_name ON children(center_id, first_name, last_name);

CREATE TABLE specialists (
    id UUID PRIMARY KEY,
    center_id UUID NOT NULL REFERENCES centers(id) ON DELETE RESTRICT,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100),
    specialization VARCHAR(160),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_specialists_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE INDEX idx_specialists_center_id ON specialists(center_id);
CREATE INDEX idx_specialists_center_status ON specialists(center_id, status);
CREATE INDEX idx_specialists_center_name ON specialists(center_id, first_name, last_name);
