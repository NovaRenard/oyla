-- Stage 3 content library. The legacy exercises table is retained for old sessions,
-- while all new editable content uses UUID identities and tenant-scoped ownership.
CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    center_id UUID REFERENCES centers(id) ON DELETE RESTRICT,
    ownership VARCHAR(16) NOT NULL,
    type VARCHAR(16) NOT NULL,
    storage_key VARCHAR(120) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_media_assets_ownership CHECK (
        (ownership = 'SYSTEM' AND center_id IS NULL) OR (ownership = 'CENTER' AND center_id IS NOT NULL)
    ),
    CONSTRAINT chk_media_assets_type CHECK (type IN ('IMAGE', 'AUDIO')),
    CONSTRAINT chk_media_assets_size CHECK (size_bytes > 0)
);
CREATE INDEX idx_media_assets_center ON media_assets(center_id);

CREATE TABLE content_exercises (
    id UUID PRIMARY KEY,
    legacy_key VARCHAR(120) UNIQUE,
    center_id UUID REFERENCES centers(id) ON DELETE RESTRICT,
    ownership VARCHAR(16) NOT NULL,
    activity_type VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    instruction_text VARCHAR(1000) NOT NULL,
    instruction_audio_asset_id UUID REFERENCES media_assets(id) ON DELETE RESTRICT,
    local_audio_asset_key VARCHAR(120),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_content_exercises_ownership CHECK (
        (ownership = 'SYSTEM' AND center_id IS NULL) OR (ownership = 'CENTER' AND center_id IS NOT NULL)
    ),
    CONSTRAINT chk_content_exercises_type CHECK (activity_type IN ('SINGLE_CHOICE')),
    CONSTRAINT chk_content_exercises_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_content_exercises_text CHECK (char_length(trim(title)) > 0 AND char_length(trim(instruction_text)) > 0)
);
CREATE INDEX idx_content_exercises_center_status ON content_exercises(center_id, status);
CREATE INDEX idx_content_exercises_system_status ON content_exercises(ownership, status);

CREATE TABLE content_exercise_options (
    id UUID PRIMARY KEY,
    exercise_id UUID NOT NULL REFERENCES content_exercises(id) ON DELETE RESTRICT,
    label VARCHAR(160),
    image_asset_id UUID REFERENCES media_assets(id) ON DELETE RESTRICT,
    local_image_asset_key VARCHAR(120),
    sort_order INTEGER NOT NULL,
    is_correct BOOLEAN NOT NULL,
    CONSTRAINT uq_content_exercise_options_position UNIQUE(exercise_id, sort_order),
    CONSTRAINT chk_content_exercise_options_position CHECK (sort_order BETWEEN 1 AND 6),
    CONSTRAINT chk_content_exercise_options_content CHECK (
        (label IS NOT NULL AND char_length(trim(label)) > 0) OR image_asset_id IS NOT NULL OR local_image_asset_key IS NOT NULL
    )
);
CREATE INDEX idx_content_exercise_options_exercise ON content_exercise_options(exercise_id, sort_order);

CREATE TABLE lesson_templates (
    id UUID PRIMARY KEY,
    center_id UUID REFERENCES centers(id) ON DELETE RESTRICT,
    ownership VARCHAR(16) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_lesson_templates_ownership CHECK (
        (ownership = 'SYSTEM' AND center_id IS NULL) OR (ownership = 'CENTER' AND center_id IS NOT NULL)
    ),
    CONSTRAINT chk_lesson_templates_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT chk_lesson_templates_name CHECK (char_length(trim(name)) > 0)
);
CREATE INDEX idx_lesson_templates_center_status ON lesson_templates(center_id, status);

CREATE TABLE lesson_template_items (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES lesson_templates(id) ON DELETE RESTRICT,
    exercise_id UUID NOT NULL REFERENCES content_exercises(id) ON DELETE RESTRICT,
    position INTEGER NOT NULL,
    CONSTRAINT uq_lesson_template_items_position UNIQUE(template_id, position),
    CONSTRAINT chk_lesson_template_items_position CHECK (position BETWEEN 1 AND 30)
);
CREATE INDEX idx_lesson_template_items_template ON lesson_template_items(template_id, position);
CREATE INDEX idx_lesson_template_items_exercise ON lesson_template_items(exercise_id);

-- Session history must be independent from mutable content. Old session rows are converted
-- below; new managed rows store their JSON snapshot at insert time.
ALTER TABLE sessions ADD COLUMN lesson_template_id UUID REFERENCES lesson_templates(id) ON DELETE RESTRICT;
ALTER TABLE sessions ADD COLUMN template_name_snapshot VARCHAR(160);
ALTER TABLE session_exercises DROP CONSTRAINT IF EXISTS session_exercises_exercise_id_fkey;
ALTER TABLE session_exercises DROP CONSTRAINT IF EXISTS chk_session_exercises_position;
ALTER TABLE session_exercises ADD CONSTRAINT chk_session_exercises_position CHECK (position BETWEEN 1 AND 30);
ALTER TABLE session_exercises ADD COLUMN source_exercise_id UUID;
ALTER TABLE session_exercises ADD COLUMN activity_type VARCHAR(32) NOT NULL DEFAULT 'SINGLE_CHOICE';
ALTER TABLE session_exercises ADD COLUMN snapshot_json JSONB;
CREATE INDEX idx_session_exercises_source ON session_exercises(source_exercise_id);

-- Deterministic SYSTEM migration for the five existing APK-backed exercises.
INSERT INTO content_exercises (id, legacy_key, center_id, ownership, activity_type, title, instruction_text, local_audio_asset_key, status, created_at, updated_at)
VALUES
 ('00000000-0000-0000-0000-000000000101','sound-r-rocket',NULL,'SYSTEM','SINGLE_CHOICE','Звук «Р»: ракета','Найди картинку, в названии которой есть звук «Р»','exercise_sound_r','ACTIVE',NOW(),NOW()),
 ('00000000-0000-0000-0000-000000000102','sound-r-fish',NULL,'SYSTEM','SINGLE_CHOICE','Звук «Р»: рыба','Найди картинку, в названии которой есть звук «Р»','exercise_sound_r','ACTIVE',NOW(),NOW()),
 ('00000000-0000-0000-0000-000000000103','sound-l-lamp',NULL,'SYSTEM','SINGLE_CHOICE','Звук «Л»: лампа','Найди картинку, в названии которой есть звук «Л»','exercise_sound_l','ACTIVE',NOW(),NOW()),
 ('00000000-0000-0000-0000-000000000104','sound-s-dog',NULL,'SYSTEM','SINGLE_CHOICE','Звук «С»: собака','Найди картинку, в названии которой есть звук «С»','exercise_sound_s','ACTIVE',NOW(),NOW()),
 ('00000000-0000-0000-0000-000000000105','sound-sh-ball',NULL,'SYSTEM','SINGLE_CHOICE','Звук «Ш»: шар','Найди картинку, в названии которой есть звук «Ш»','exercise_sound_sh','ACTIVE',NOW(),NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO content_exercise_options (id, exercise_id, label, local_image_asset_key, sort_order, is_correct)
SELECT
    (substring(md5(eo.exercise_id || ':' || eo.id) from 1 for 8) || '-' || substring(md5(eo.exercise_id || ':' || eo.id) from 9 for 4) || '-4' || substring(md5(eo.exercise_id || ':' || eo.id) from 14 for 3) || '-8' || substring(md5(eo.exercise_id || ':' || eo.id) from 17 for 3) || '-' || substring(md5(eo.exercise_id || ':' || eo.id) from 20 for 12))::uuid,
    ce.id, eo.label, eo.image_asset_key, eo.position, eo.id = e.correct_option_id
FROM exercises e
JOIN content_exercises ce ON ce.legacy_key = e.id
JOIN exercise_options eo ON eo.exercise_id = e.id
ON CONFLICT (exercise_id, sort_order) DO NOTHING;

INSERT INTO lesson_templates (id, center_id, ownership, name, description, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000201',NULL,'SYSTEM','Базовое занятие Oyla','Пять исходных упражнений Oyla','ACTIVE',NOW(),NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO lesson_template_items (id, template_id, exercise_id, position)
VALUES
 ('00000000-0000-0000-0000-000000000211','00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-000000000101',1),
 ('00000000-0000-0000-0000-000000000212','00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-000000000102',2),
 ('00000000-0000-0000-0000-000000000213','00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-000000000103',3),
 ('00000000-0000-0000-0000-000000000214','00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-000000000104',4),
 ('00000000-0000-0000-0000-000000000215','00000000-0000-0000-0000-000000000201','00000000-0000-0000-0000-000000000105',5)
ON CONFLICT (template_id, position) DO NOTHING;

UPDATE session_exercises se
SET source_exercise_id = ce.id,
    snapshot_json = jsonb_build_object(
        'sourceExerciseId', ce.id::text, 'activityType', 'SINGLE_CHOICE', 'title', ce.title,
        'instructionText', e.instruction_text, 'audioUrl', NULL, 'localAudioAssetKey', e.audio_asset_key,
        'options', (
            SELECT jsonb_agg(jsonb_build_object(
                'id', eo.id, 'label', eo.label, 'localImageAssetKey', eo.image_asset_key, 'position', eo.position
            ) ORDER BY eo.position)
            FROM exercise_options eo WHERE eo.exercise_id = e.id
        ), 'correctOptionId', e.correct_option_id
    )
FROM exercises e
JOIN content_exercises ce ON ce.legacy_key = e.id
WHERE se.exercise_id = e.id AND se.snapshot_json IS NULL;

UPDATE sessions
SET lesson_template_id = '00000000-0000-0000-0000-000000000201',
    template_name_snapshot = 'Базовое занятие Oyla'
WHERE is_managed = TRUE AND lesson_template_id IS NULL;
