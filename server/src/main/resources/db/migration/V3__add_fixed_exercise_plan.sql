-- A session used to have exactly one exercise. Keep that exercise as the
-- first item of the fixed plan, then allow the remaining plan positions.
ALTER TABLE session_exercises ADD COLUMN position INTEGER;
ALTER TABLE session_exercises ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE session_exercises
SET position = 1, is_current = TRUE
WHERE position IS NULL;

ALTER TABLE session_exercises ALTER COLUMN position SET NOT NULL;
ALTER TABLE session_exercises
    ADD CONSTRAINT chk_session_exercises_position CHECK (position BETWEEN 1 AND 5);
ALTER TABLE session_exercises DROP CONSTRAINT uq_session_exercises_session_id;
ALTER TABLE session_exercises
    ADD CONSTRAINT uq_session_exercises_session_position UNIQUE (session_id, position);
CREATE UNIQUE INDEX uq_session_exercises_one_current
    ON session_exercises(session_id) WHERE is_current = TRUE;
CREATE INDEX idx_session_exercises_session_position
    ON session_exercises(session_id, position);

-- Option identifiers are scoped to an exercise: the same distractor (for
-- example "cat") can occur in several exercises.
ALTER TABLE exercise_options DROP CONSTRAINT exercise_options_pkey;
ALTER TABLE exercise_options
    ADD CONSTRAINT exercise_options_pkey PRIMARY KEY (exercise_id, id);

INSERT INTO exercises (id, instruction_text, audio_asset_key, correct_option_id, is_active, created_at) VALUES
    ('sound-r-fish', 'Найди картинку, в названии которой есть звук «Р»', 'exercise_sound_r', 'fish', TRUE, NOW()),
    ('sound-l-lamp', 'Найди картинку, в названии которой есть звук «Л»', 'exercise_sound_l', 'lamp', TRUE, NOW()),
    ('sound-s-dog', 'Найди картинку, в названии которой есть звук «С»', 'exercise_sound_s', 'dog', TRUE, NOW()),
    ('sound-sh-ball', 'Найди картинку, в названии которой есть звук «Ш»', 'exercise_sound_sh', 'ball', TRUE, NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO exercise_options (id, exercise_id, label, image_asset_key, position) VALUES
    ('fish', 'sound-r-fish', 'рыба', 'exercise_fish', 1),
    ('apple', 'sound-r-fish', 'яблоко', 'exercise_apple', 2),
    ('duck', 'sound-r-fish', 'утка', 'exercise_duck', 3),
    ('elephant', 'sound-r-fish', 'слон', 'exercise_elephant', 4),
    ('lamp', 'sound-l-lamp', 'лампа', 'exercise_lamp', 1),
    ('cat', 'sound-l-lamp', 'кот', 'exercise_cat', 2),
    ('house', 'sound-l-lamp', 'дом', 'exercise_house', 3),
    ('fish', 'sound-l-lamp', 'рыба', 'exercise_fish', 4),
    ('dog', 'sound-s-dog', 'собака', 'exercise_dog', 1),
    ('cat', 'sound-s-dog', 'кот', 'exercise_cat', 2),
    ('house', 'sound-s-dog', 'дом', 'exercise_house', 3),
    ('fish', 'sound-s-dog', 'рыба', 'exercise_fish', 4),
    ('ball', 'sound-sh-ball', 'шар', 'exercise_ball', 1),
    ('cat', 'sound-sh-ball', 'кот', 'exercise_cat', 2),
    ('house', 'sound-sh-ball', 'дом', 'exercise_house', 3),
    ('fox', 'sound-sh-ball', 'лиса', 'exercise_fox', 4)
ON CONFLICT (exercise_id, id) DO NOTHING;
