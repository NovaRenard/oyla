-- Stage 8: generated games (docs/game-generation-contract.md). Content with game_config.generate = true gets a
-- fresh random variant (options, answer, settings) every time a lesson is created; the fields already set in
-- game_config are generator constraints. session_exercises.activity_type has no CHECK constraint.
ALTER TABLE content_exercises DROP CONSTRAINT IF EXISTS chk_content_exercises_type;
ALTER TABLE content_exercises ADD CONSTRAINT chk_content_exercises_type
    CHECK (activity_type IN ('SINGLE_CHOICE', 'WHITEBOARD', 'ODD_ONE', 'REMEMBER', 'MEMORY', 'SORT', 'WORD_BUILD', 'MAZE', 'POP', 'SYLLABLES', 'SHADOW', 'COUNT', 'MATH', 'PAIRS', 'PARTS', 'TYPE_WORD', 'PHONETICS', 'LABEL_SCENE', 'BASKET', 'CATEGORY_HOUSE', 'MISSING', 'PARKING', 'ARROWS'));

-- Existing SYSTEM games now generate their variants (constraints come from their titles and settings).
-- Their stored options stay as a reference; snapshots no longer copy them.
UPDATE content_exercises SET game_config = '{"generate":true,"level":2}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000301' AND ownership = 'SYSTEM' AND activity_type = 'MEMORY';
UPDATE content_exercises SET game_config = '{"showTargetMs":3000,"generate":true,"level":2,"category":"shape"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000302' AND ownership = 'SYSTEM' AND activity_type = 'REMEMBER';
UPDATE content_exercises SET game_config = '{"generate":true,"level":1}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000303' AND ownership = 'SYSTEM' AND activity_type = 'ODD_ONE';
UPDATE content_exercises SET game_config = '{"groupLabels":["Р","Л"],"generate":true,"level":1}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000304' AND ownership = 'SYSTEM' AND activity_type = 'SORT';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_cat","generate":true,"level":1}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000306' AND ownership = 'SYSTEM' AND activity_type = 'WORD_BUILD';
UPDATE content_exercises SET game_config = '{"generate":true,"level":1,"sound":"Р"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000307' AND ownership = 'SYSTEM' AND activity_type = 'POP';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_rocket","generate":true,"level":2}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000308' AND ownership = 'SYSTEM' AND activity_type = 'SYLLABLES';
UPDATE content_exercises SET game_config = '{"generate":true,"level":1}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000309' AND ownership = 'SYSTEM' AND activity_type = 'SHADOW';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_fish","generate":true,"level":1}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000310' AND ownership = 'SYSTEM' AND activity_type = 'COUNT';
UPDATE content_exercises SET game_config = '{"generate":true,"level":1,"category":"animal"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000611' AND ownership = 'SYSTEM' AND activity_type = 'ODD_ONE';
UPDATE content_exercises SET game_config = '{"generate":true,"level":2,"category":"fruit"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000612' AND ownership = 'SYSTEM' AND activity_type = 'ODD_ONE';
UPDATE content_exercises SET game_config = '{"generate":true,"level":1,"category":"transport"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000613' AND ownership = 'SYSTEM' AND activity_type = 'ODD_ONE';
UPDATE content_exercises SET game_config = '{"generate":true,"level":2,"category":"vegetable"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000614' AND ownership = 'SYSTEM' AND activity_type = 'ODD_ONE';
UPDATE content_exercises SET game_config = '{"generate":true,"level":3,"category":"animal"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000615' AND ownership = 'SYSTEM' AND activity_type = 'MEMORY';
UPDATE content_exercises SET game_config = '{"generate":true,"level":2,"category":"food"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000616' AND ownership = 'SYSTEM' AND activity_type = 'MEMORY';
UPDATE content_exercises SET game_config = '{"generate":true,"level":2,"sound":"Л"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000617' AND ownership = 'SYSTEM' AND activity_type = 'POP';
UPDATE content_exercises SET game_config = '{"generate":true,"level":2,"sound":"С"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000618' AND ownership = 'SYSTEM' AND activity_type = 'POP';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_apple","generate":true,"level":1}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000619' AND ownership = 'SYSTEM' AND activity_type = 'COUNT';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_duck","generate":true,"level":2}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000620' AND ownership = 'SYSTEM' AND activity_type = 'COUNT';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"shape_star_yellow","generate":true,"level":2}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000621' AND ownership = 'SYSTEM' AND activity_type = 'COUNT';
UPDATE content_exercises SET game_config = '{"generate":true,"level":2,"category":"animal"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000622' AND ownership = 'SYSTEM' AND activity_type = 'SHADOW';
UPDATE content_exercises SET game_config = '{"generate":true,"level":1,"category":"transport"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000623' AND ownership = 'SYSTEM' AND activity_type = 'SHADOW';
UPDATE content_exercises SET game_config = '{"groupLabels":["С","Ш"],"generate":true,"level":2}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000624' AND ownership = 'SYSTEM' AND activity_type = 'SORT';
UPDATE content_exercises SET game_config = '{"groupLabels":["Фрукты","Овощи"],"generate":true,"level":2}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000625' AND ownership = 'SYSTEM' AND activity_type = 'SORT';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_juice","generate":true,"level":1}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000626' AND ownership = 'SYSTEM' AND activity_type = 'WORD_BUILD';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_house","generate":true,"level":1}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000627' AND ownership = 'SYSTEM' AND activity_type = 'WORD_BUILD';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_onion","generate":true,"level":1}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000628' AND ownership = 'SYSTEM' AND activity_type = 'WORD_BUILD';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_cheese","generate":true,"level":1}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000629' AND ownership = 'SYSTEM' AND activity_type = 'WORD_BUILD';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_drum","generate":true,"level":3}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000630' AND ownership = 'SYSTEM' AND activity_type = 'SYLLABLES';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_car","generate":true,"level":3}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000631' AND ownership = 'SYSTEM' AND activity_type = 'SYLLABLES';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_dog","generate":true,"level":3}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000632' AND ownership = 'SYSTEM' AND activity_type = 'SYLLABLES';
UPDATE content_exercises SET game_config = '{"imageAssetKey":"exercise_moon","generate":true,"level":2}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000633' AND ownership = 'SYSTEM' AND activity_type = 'SYLLABLES';
UPDATE content_exercises SET title = 'Запомни и найди: фигуры', game_config = '{"showTargetMs":3000,"generate":true,"level":3,"category":"shape"}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000634' AND ownership = 'SYSTEM' AND activity_type = 'REMEMBER';
UPDATE content_exercises SET title = 'Запомни и найди: картинки', game_config = '{"showTargetMs":3000,"generate":true,"level":2}'::jsonb, updated_at = NOW() WHERE id = '00000000-0000-0000-0000-000000000635' AND ownership = 'SYSTEM' AND activity_type = 'REMEMBER';

-- New generated-only SYSTEM games, mirrored by InMemoryContentRepository. They have no stored options.
INSERT INTO content_exercises (id, legacy_key, center_id, ownership, activity_type, title, instruction_text, local_audio_asset_key, status, created_at, updated_at, game_config)
VALUES
 ('00000000-0000-0000-0000-000000000701',NULL,NULL,'SYSTEM','MATH','Сложение до 5','Сколько всего? Сложи и выбери число',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1,"mathOp":"+"}'::jsonb),
 ('00000000-0000-0000-0000-000000000702',NULL,NULL,'SYSTEM','MATH','Сложение до 10','Сколько всего? Сложи и выбери число',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2,"mathOp":"+"}'::jsonb),
 ('00000000-0000-0000-0000-000000000703',NULL,NULL,'SYSTEM','MATH','Вычитание до 10','Сколько осталось? Выбери число',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2,"mathOp":"-"}'::jsonb),
 ('00000000-0000-0000-0000-000000000704',NULL,NULL,'SYSTEM','MATH','Плюс и минус до 20','Реши пример и выбери ответ',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":3,"mathOp":"±"}'::jsonb),
 ('00000000-0000-0000-0000-000000000705',NULL,NULL,'SYSTEM','MATH','Умножение','Посчитай, сколько всего, и выбери число',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2,"mathOp":"×"}'::jsonb),
 ('00000000-0000-0000-0000-000000000706',NULL,NULL,'SYSTEM','MATH','Вычитание до 5','Сколько осталось? Выбери число',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1,"mathOp":"-"}'::jsonb),
 ('00000000-0000-0000-0000-000000000711',NULL,NULL,'SYSTEM','PAIRS','Стаканы и тарелки','Соедини каждый стакан с каждой тарелкой. Сколько получилось пар?',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1}'::jsonb),
 ('00000000-0000-0000-0000-000000000712',NULL,NULL,'SYSTEM','PAIRS','Стаканы и тарелки: больше пар','Соедини каждый стакан с каждой тарелкой. Сколько получилось пар?',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2}'::jsonb),
 ('00000000-0000-0000-0000-000000000721',NULL,NULL,'SYSTEM','PARTS','Угадай по частям','Посмотри на кусочки. Что это?',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1}'::jsonb),
 ('00000000-0000-0000-0000-000000000722',NULL,NULL,'SYSTEM','PARTS','Угадай по частям: похожие','Посмотри на кусочки. Что это?',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":3}'::jsonb),
 ('00000000-0000-0000-0000-000000000731',NULL,NULL,'SYSTEM','TYPE_WORD','Напечатай слово: 3 буквы','Напечатай слово по буквам',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1}'::jsonb),
 ('00000000-0000-0000-0000-000000000732',NULL,NULL,'SYSTEM','TYPE_WORD','Напечатай слово: 4–5 букв','Напечатай слово по буквам',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2}'::jsonb),
 ('00000000-0000-0000-0000-000000000733',NULL,NULL,'SYSTEM','TYPE_WORD','Напечатай слово: длинные слова','Напечатай слово по буквам',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":3}'::jsonb),
 ('00000000-0000-0000-0000-000000000741',NULL,NULL,'SYSTEM','PHONETICS','Раскрась звуки: гласные и согласные','Гласные раскрась красным, согласные — синим',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1}'::jsonb),
 ('00000000-0000-0000-0000-000000000742',NULL,NULL,'SYSTEM','PHONETICS','Раскрась звуки: твёрдые и мягкие','Гласные — красным, твёрдые — синим, мягкие — зелёным',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2}'::jsonb),
 ('00000000-0000-0000-0000-000000000743',NULL,NULL,'SYSTEM','PHONETICS','Раскрась звуки: длинные слова','Гласные — красным, твёрдые — синим, мягкие — зелёным',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":3}'::jsonb),
 ('00000000-0000-0000-0000-000000000751',NULL,NULL,'SYSTEM','LABEL_SCENE','Подпиши предметы','Перетащи каждое слово к своей картинке',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1}'::jsonb),
 ('00000000-0000-0000-0000-000000000752',NULL,NULL,'SYSTEM','LABEL_SCENE','Подпиши предметы: больше слов','Перетащи каждое слово к своей картинке',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2}'::jsonb),
 ('00000000-0000-0000-0000-000000000753',NULL,NULL,'SYSTEM','LABEL_SCENE','Подпиши предметы: шесть слов','Перетащи каждое слово к своей картинке',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":3}'::jsonb),
 ('00000000-0000-0000-0000-000000000761',NULL,NULL,'SYSTEM','BASKET','Собери корзинку','Положи в корзинку то, что нарисовано сверху',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1,"container":"basket"}'::jsonb),
 ('00000000-0000-0000-0000-000000000762',NULL,NULL,'SYSTEM','BASKET','Собери корзинку: три вида','Положи в корзинку то, что нарисовано сверху',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2,"container":"basket"}'::jsonb),
 ('00000000-0000-0000-0000-000000000763',NULL,NULL,'SYSTEM','BASKET','Собери корзинку: не ошибись','Положи в корзинку только то, что нарисовано сверху',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":3,"container":"basket"}'::jsonb),
 ('00000000-0000-0000-0000-000000000764',NULL,NULL,'SYSTEM','BASKET','Фрукты для сока','Положи в бутылку фрукты для сока',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2,"container":"bottle"}'::jsonb),
 ('00000000-0000-0000-0000-000000000771',NULL,NULL,'SYSTEM','CATEGORY_HOUSE','Домик: собери группу','Посели в домик только тех, кто подходит',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1}'::jsonb),
 ('00000000-0000-0000-0000-000000000772',NULL,NULL,'SYSTEM','CATEGORY_HOUSE','Домик: собери группу побольше','Посели в домик только тех, кто подходит',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2}'::jsonb),
 ('00000000-0000-0000-0000-000000000773',NULL,NULL,'SYSTEM','CATEGORY_HOUSE','Домик: полный дом','Посели в домик только тех, кто подходит',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":3}'::jsonb),
 ('00000000-0000-0000-0000-000000000781',NULL,NULL,'SYSTEM','MISSING','Что пропало?','Запомни картинки. Что пропало? Найди внизу',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1}'::jsonb),
 ('00000000-0000-0000-0000-000000000782',NULL,NULL,'SYSTEM','MISSING','Что пропало: два предмета','Запомни картинки. Что пропало? Найди внизу',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2}'::jsonb),
 ('00000000-0000-0000-0000-000000000783',NULL,NULL,'SYSTEM','MISSING','Что пропало: три предмета','Запомни картинки. Что пропало? Найди внизу',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":3}'::jsonb),
 ('00000000-0000-0000-0000-000000000786',NULL,NULL,'SYSTEM','PARKING','Парковка по цветам','Поставь каждую машинку на место своего цвета',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1}'::jsonb),
 ('00000000-0000-0000-0000-000000000787',NULL,NULL,'SYSTEM','PARKING','Парковка по цветам: 5 машинок','Поставь каждую машинку на место своего цвета',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2}'::jsonb),
 ('00000000-0000-0000-0000-000000000788',NULL,NULL,'SYSTEM','PARKING','Парковка по цветам: 7 машинок','Поставь каждую машинку на место своего цвета',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":3}'::jsonb),
 ('00000000-0000-0000-0000-000000000791',NULL,NULL,'SYSTEM','ARROWS','Дорожка стрелками','Поставь стрелки, чтобы Ойлик дошёл до конфеты, и нажми «Поехали»',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":1}'::jsonb),
 ('00000000-0000-0000-0000-000000000792',NULL,NULL,'SYSTEM','ARROWS','Дорожка стрелками: камни','Поставь стрелки, чтобы Ойлик обошёл камни и собрал конфеты',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":2}'::jsonb),
 ('00000000-0000-0000-0000-000000000793',NULL,NULL,'SYSTEM','ARROWS','Дорожка стрелками: три конфеты','Поставь стрелки, чтобы Ойлик собрал все конфеты по порядку',NULL,'ACTIVE',NOW(),NOW(),'{"generate":true,"level":3}'::jsonb)
ON CONFLICT (id) DO NOTHING;

INSERT INTO lesson_templates (id, center_id, ownership, name, description, status, created_at, updated_at)
VALUES
 ('00000000-0000-0000-0000-000000000906',NULL,'SYSTEM','Математика','Сложение, вычитание, умножение и пары','ACTIVE',NOW(),NOW()),
 ('00000000-0000-0000-0000-000000000907',NULL,'SYSTEM','Буквы и слова','Печатаем слова, раскрашиваем звуки и подписываем картинки','ACTIVE',NOW(),NOW()),
 ('00000000-0000-0000-0000-000000000908',NULL,'SYSTEM','Внимание и логика','Угадай по частям, что пропало, тени и стрелки','ACTIVE',NOW(),NOW()),
 ('00000000-0000-0000-0000-000000000909',NULL,'SYSTEM','Перетаскивание','Корзинка, домик, парковка, подписи и корзины','ACTIVE',NOW(),NOW())
ON CONFLICT (id) DO NOTHING;

INSERT INTO lesson_template_items (id, template_id, exercise_id, position)
VALUES
 ('00000000-0000-0000-0000-000000000961','00000000-0000-0000-0000-000000000906','00000000-0000-0000-0000-000000000310',1),
 ('00000000-0000-0000-0000-000000000962','00000000-0000-0000-0000-000000000906','00000000-0000-0000-0000-000000000701',2),
 ('00000000-0000-0000-0000-000000000963','00000000-0000-0000-0000-000000000906','00000000-0000-0000-0000-000000000706',3),
 ('00000000-0000-0000-0000-000000000964','00000000-0000-0000-0000-000000000906','00000000-0000-0000-0000-000000000619',4),
 ('00000000-0000-0000-0000-000000000965','00000000-0000-0000-0000-000000000906','00000000-0000-0000-0000-000000000711',5),
 ('00000000-0000-0000-0000-000000000966','00000000-0000-0000-0000-000000000906','00000000-0000-0000-0000-000000000702',6),
 ('00000000-0000-0000-0000-000000000967','00000000-0000-0000-0000-000000000906','00000000-0000-0000-0000-000000000705',7),
 ('00000000-0000-0000-0000-000000000971','00000000-0000-0000-0000-000000000907','00000000-0000-0000-0000-000000000731',1),
 ('00000000-0000-0000-0000-000000000972','00000000-0000-0000-0000-000000000907','00000000-0000-0000-0000-000000000306',2),
 ('00000000-0000-0000-0000-000000000973','00000000-0000-0000-0000-000000000907','00000000-0000-0000-0000-000000000741',3),
 ('00000000-0000-0000-0000-000000000974','00000000-0000-0000-0000-000000000907','00000000-0000-0000-0000-000000000751',4),
 ('00000000-0000-0000-0000-000000000975','00000000-0000-0000-0000-000000000907','00000000-0000-0000-0000-000000000308',5),
 ('00000000-0000-0000-0000-000000000976','00000000-0000-0000-0000-000000000907','00000000-0000-0000-0000-000000000732',6),
 ('00000000-0000-0000-0000-000000000977','00000000-0000-0000-0000-000000000907','00000000-0000-0000-0000-000000000742',7),
 ('00000000-0000-0000-0000-000000000981','00000000-0000-0000-0000-000000000908','00000000-0000-0000-0000-000000000721',1),
 ('00000000-0000-0000-0000-000000000982','00000000-0000-0000-0000-000000000908','00000000-0000-0000-0000-000000000303',2),
 ('00000000-0000-0000-0000-000000000983','00000000-0000-0000-0000-000000000908','00000000-0000-0000-0000-000000000781',3),
 ('00000000-0000-0000-0000-000000000984','00000000-0000-0000-0000-000000000908','00000000-0000-0000-0000-000000000302',4),
 ('00000000-0000-0000-0000-000000000985','00000000-0000-0000-0000-000000000908','00000000-0000-0000-0000-000000000791',5),
 ('00000000-0000-0000-0000-000000000986','00000000-0000-0000-0000-000000000908','00000000-0000-0000-0000-000000000309',6),
 ('00000000-0000-0000-0000-000000000987','00000000-0000-0000-0000-000000000908','00000000-0000-0000-0000-000000000615',7),
 ('00000000-0000-0000-0000-000000000991','00000000-0000-0000-0000-000000000909','00000000-0000-0000-0000-000000000761',1),
 ('00000000-0000-0000-0000-000000000992','00000000-0000-0000-0000-000000000909','00000000-0000-0000-0000-000000000771',2),
 ('00000000-0000-0000-0000-000000000993','00000000-0000-0000-0000-000000000909','00000000-0000-0000-0000-000000000786',3),
 ('00000000-0000-0000-0000-000000000994','00000000-0000-0000-0000-000000000909','00000000-0000-0000-0000-000000000625',4),
 ('00000000-0000-0000-0000-000000000995','00000000-0000-0000-0000-000000000909','00000000-0000-0000-0000-000000000752',5),
 ('00000000-0000-0000-0000-000000000996','00000000-0000-0000-0000-000000000909','00000000-0000-0000-0000-000000000762',6),
 ('00000000-0000-0000-0000-000000000997','00000000-0000-0000-0000-000000000909','00000000-0000-0000-0000-000000000772',7)
ON CONFLICT DO NOTHING;
