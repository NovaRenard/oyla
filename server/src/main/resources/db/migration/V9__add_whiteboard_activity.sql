-- Stage 4: whiteboard remains a regular content activity and regular session exercise.
ALTER TABLE content_exercises DROP CONSTRAINT IF EXISTS chk_content_exercises_type;
ALTER TABLE content_exercises ADD CONSTRAINT chk_content_exercises_type
    CHECK (activity_type IN ('SINGLE_CHOICE', 'WHITEBOARD'));

CREATE TABLE whiteboard_exercise_configs (
    exercise_id UUID PRIMARY KEY REFERENCES content_exercises(id) ON DELETE RESTRICT,
    background_asset_id UUID REFERENCES media_assets(id) ON DELETE RESTRICT,
    child_drawing_initially_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    available_colors JSONB NOT NULL,
    default_color VARCHAR(16) NOT NULL,
    default_brush_size VARCHAR(16) NOT NULL,
    allow_eraser BOOLEAN NOT NULL DEFAULT TRUE,
    allow_clear BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_whiteboard_config_colors CHECK (
        jsonb_typeof(available_colors) = 'array'
        AND jsonb_array_length(available_colors) BETWEEN 4 AND 6
        AND available_colors <@ '["BLACK", "BLUE", "GREEN", "RED", "ORANGE", "PURPLE"]'::jsonb
        AND available_colors ? default_color
    ),
    CONSTRAINT chk_whiteboard_config_color CHECK (default_color IN ('BLACK', 'BLUE', 'GREEN', 'RED', 'ORANGE', 'PURPLE')),
    CONSTRAINT chk_whiteboard_config_brush CHECK (default_brush_size IN ('THIN', 'MEDIUM', 'THICK'))
);
CREATE INDEX idx_whiteboard_exercise_background ON whiteboard_exercise_configs(background_asset_id);

-- The state row is the authoritative board head. Strokes are retained after lesson completion.
CREATE TABLE whiteboard_states (
    session_exercise_id UUID PRIMARY KEY REFERENCES session_exercises(id) ON DELETE RESTRICT,
    child_drawing_enabled BOOLEAN NOT NULL,
    board_revision INTEGER NOT NULL DEFAULT 0,
    clear_revision INTEGER NOT NULL DEFAULT 0,
    next_sequence_number INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_whiteboard_state_revisions CHECK (board_revision >= 0 AND clear_revision >= 0 AND next_sequence_number >= 0)
);

CREATE TABLE whiteboard_strokes (
    id UUID PRIMARY KEY,
    session_exercise_id UUID NOT NULL REFERENCES session_exercises(id) ON DELETE RESTRICT,
    actor_device_id VARCHAR(255) NOT NULL,
    actor_role VARCHAR(16) NOT NULL,
    sequence_number INTEGER NOT NULL,
    tool VARCHAR(16) NOT NULL,
    color VARCHAR(16),
    brush_size VARCHAR(16) NOT NULL,
    points_json JSONB NOT NULL,
    clear_revision INTEGER NOT NULL,
    is_removed BOOLEAN NOT NULL DEFAULT FALSE,
    client_event_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_whiteboard_stroke_sequence UNIQUE(session_exercise_id, sequence_number),
    CONSTRAINT chk_whiteboard_stroke_role CHECK (actor_role IN ('SPECIALIST', 'CHILD')),
    CONSTRAINT chk_whiteboard_stroke_tool CHECK (tool IN ('PEN', 'ERASER')),
    CONSTRAINT chk_whiteboard_stroke_color CHECK (color IS NULL OR color IN ('BLACK', 'BLUE', 'GREEN', 'RED', 'ORANGE', 'PURPLE')),
    CONSTRAINT chk_whiteboard_stroke_brush CHECK (brush_size IN ('THIN', 'MEDIUM', 'THICK')),
    CONSTRAINT chk_whiteboard_stroke_points CHECK (jsonb_typeof(points_json) = 'array' AND jsonb_array_length(points_json) BETWEEN 1 AND 2048)
);
CREATE INDEX idx_whiteboard_strokes_active ON whiteboard_strokes(session_exercise_id, clear_revision, is_removed, sequence_number);
