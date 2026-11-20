# Oyla — правила работы

Oyla — Android-приложение (Kotlin + Jetpack Compose) для коррекционного центра, плюс Ktor-сервер (`server/`) и веб-клиент для специалистов (`web-app/`, React + Vite).

## Главное правило: после каждого изменения — собрать и запустить на планшете

Любое изменение в `app/` (код, ресурсы, дизайн, упражнения) заканчивается сборкой, установкой и запуском на подключённом планшете. Не сообщать «готово», пока сборка не прошла и приложение не запущено на устройстве. Если сборка или установка упала — сказать об этом и показать ошибку.

Подключённое устройство: планшет **A16**, Android 9 (API 28), экран 800×1280, adb id `MTK0002509241548310`.

```bash
# JDK лежит в проекте, системной java нет
export JAVA_HOME="$(pwd)/.tools/jdk21/jdk-21.0.12+8"
ADB=/c/Users/saads/AppData/Local/Android/Sdk/platform-tools/adb.exe

$ADB devices -l                                   # планшет должен быть в списке
./gradlew.bat :app:installDebug --console=plain -q   # сборка + установка (~3–4 мин с нуля)
$ADB shell input keyevent KEYCODE_WAKEUP && $ADB shell input swipe 640 700 640 200 300   # разбудить и снять блокировку свайпом
$ADB shell am start -n kz.oyla.app.debug/kz.oyla.app.MainActivity                      # запуск
$ADB exec-out screencap -p > shot.png             # скриншот для проверки (класть в scratchpad)
```

- Debug-сборка имеет id `kz.oyla.app.debug` («Oyla DEV») и стоит **рядом** с release `kz.oyla.app`. Release на планшете не трогать и не удалять.
- Экран планшета гаснет через 60 с — перед скриншотом будить. Чёрный скриншот = экран выключен, а не баг.
- Длинную сборку запускать в фоне, не опрашивать в цикле.
- Debug-сборка берёт адреса сервера из `local.properties` (`OYLA_API_BASE_URL`, `OYLA_WS_BASE_URL`). Сейчас там **локальный сервер** `http://127.0.0.1:8083` — на каждом устройстве нужен `adb -s <id> reverse tcp:8083 tcp:8083`.
- Release всегда ходит на `https://oyla.saadsarbas.tech`. Боевой сервер обновляется только ручным деплоем пользователя — не деплоить самому.

## Локальный стенд

```bash
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build   # Postgres + сервер на 127.0.0.1:8083
curl -s http://127.0.0.1:8083/health
```

- `deploy/.env` с локальными секретами создан и в git не попадает.
- Занятие ведут два устройства: специалист — эмулятор планшета `OylaSpecialist` (AVD в `~/.android/avd`, запуск `emulator -avd OylaSpecialist -no-snapshot-save`), ребёнок — физический планшет A16.
- Тестовый центр «Тестовый центр», специалист «Айгерим Серикова», ребёнок «Амир Б.» и свежие коды активации: `python -I deploy/scripts/seed_local_test.py .` (повторный запуск только выдаёт новые коды). Учётные данные владельца — в `deploy/.env.local-test` (не в git). Коды вводятся в «Oyla DEV» через `adb shell input text`.
- Для локального стенда в `deploy/docker-compose.yml` задан `MEDIA_STORAGE_PATH=/tmp/oyla-media` (без него сервер падает при старте).
- Экран A16 гаснет — перед скриншотом будить и снимать блокировку. На A16 нет синтезатора речи: озвучка заданий там не работает, пока не установлен «Синтезатор речи Google».

## Детский интерфейс (пакет `ui/play`)

- Макеты экранов: https://claude.ai/artifact/SRvuGS4cJ5b8AA3v71ngZL. Сцены свёрстаны в координатах артборда 1280×800 (`PlayCanvas`, `Int.u`, `Int.us`) и масштабируются под любой экран и под мини-копию на пульте специалиста.
- `ExerciseStage` — одно упражнение целиком (сцена + игра + Ойлик + празднование); его же рендерит пульт специалиста (`specialistView = true`).
- Типы игр: SINGLE_CHOICE, ODD_ONE, REMEMBER, MEMORY, SORT, WORD_BUILD, MAZE, WHITEBOARD. Многошаговые (MEMORY/SORT/WORD_BUILD/MAZE) судятся на клиенте для мгновенной реакции и шлются на сервер очередью (`submitGameAnswer`).
- Не использовать `alpha` в `graphicsLayer` на анимированных карточках и `rotationY = 180°`: на Android 9 такие слои рисуются обрезанными или пропадают.

## Устройство приложения

- Роли устройства: специалист и ребёнок (`ui/onboarding/RoleSelectionScreen.kt`), управляются через сервер и WebSocket.
- Типы активностей (`ActivityType` на сервере): `SINGLE_CHOICE` (выбор картинки) и `WHITEBOARD` (общая доска). Рендер на планшете — `ui/session/ActivityRenderer.kt`.
- Упражнения хранятся на сервере (миграции `server/src/main/resources/db/migration/`, библиотека контента), картинки — `app/src/main/res/drawable*/exercise_*`.
- Новый тип упражнения = сервер (`ContentModels.kt`, DTO, валидация, миграция) + Android-рендер + веб-редактор контента.

## Текущие направления работы

1. Переработка дизайна детского интерфейса (крупные элементы, понятно ребёнку без чтения).
2. Новые виды упражнений.
3. Известные слабые места: упражнение «выбор по звуку», белая доска (маленькая область, не все инструменты, ребёнку сложно).

## Язык

Общение и UI-тексты — на русском.
