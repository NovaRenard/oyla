CREATE TABLE exercises (
    id VARCHAR(120) PRIMARY KEY,
    instruction_text VARCHAR(500) NOT NULL,
    audio_asset_key VARCHAR(120),
    correct_option_id VARCHAR(120) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE exercise_options (
    id VARCHAR(120) PRIMARY KEY,
    exercise_id VARCHAR(120) NOT NULL REFERENCES exercises(id),
    label VARCHAR(120) NOT NULL,
    image_asset_key VARCHAR(120) NOT NULL,
    position INTEGER NOT NULL
);

CREATE TABLE session_exercises (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES sessions(id),
    exercise_id VARCHAR(120) NOT NULL REFERENCES exercises(id),
    status VARCHAR(32) NOT NULL,
    shown_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_session_exercises_session_id UNIQUE (session_id)
);

CREATE TABLE attempts (
    id UUID PRIMARY KEY,
    session_exercise_id UUID NOT NULL REFERENCES session_exercises(id),
    selected_option_id VARCHAR(120) NOT NULL,
    attempt_number INTEGER NOT NULL,
    is_correct BOOLEAN NOT NULL,
    response_time_ms BIGINT NOT NULL,
    client_event_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_session_exercises_session_id ON session_exercises(session_id);
CREATE INDEX idx_attempts_session_exercise_id ON attempts(session_exercise_id);
CREATE INDEX idx_attempts_client_event_id ON attempts(client_event_id);

INSERT INTO exercises (id, instruction_text, audio_asset_key, correct_option_id, is_active, created_at)
VALUES ('sound-r-rocket', 'Найди картинку, в названии которой есть звук «Р»', 'exercise_sound_r', 'rocket', TRUE, NOW());

INSERT INTO exercise_options (id, exercise_id, label, image_asset_key, position) VALUES
    ('rocket', 'sound-r-rocket', 'ракета', 'exercise_rocket', 1),
    ('cat', 'sound-r-rocket', 'кот', 'exercise_cat', 2),
    ('house', 'sound-r-rocket', 'дом', 'exercise_house', 3),
    ('fox', 'sound-r-rocket', 'лиса', 'exercise_fox', 4);
