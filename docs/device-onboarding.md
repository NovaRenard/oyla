# Подключение планшета Oyla

Этот документ описывает первый SaaS-сценарий: владелец создаёт центр в web-кабинете, подключает Android-планшет по одноразовому коду и управляет его доступом.

## Полный пользовательский сценарий

1. Откройте web-кабинет и перейдите на `/register`.
2. Укажите название центра, имя владельца, email и пароль. Создаётся центр и активное OWNER-членство, затем открывается `/overview`.
3. На `/devices` нажмите **«Подключить планшет»**, введите название и выберите роль: `CHILD` или `SPECIALIST`.
4. Сохраните показанный восьмизначный код. Он показывается один раз и по умолчанию действует 10 минут.
5. Откройте Oyla на планшете. На чистой установке показан экран **«Подключение планшета»**. Введите код; пробелы и дефисы можно не учитывать.
6. Приложение вызывает `POST /api/v1/device-auth/activate`, сохраняет identity и сразу открывает соответствующий граф: CHILD — текущий детский экран подключения, SPECIALIST — текущий экран специалиста.
7. После активации планшет немедленно отправляет heartbeat, затем делает это каждые 30 секунд. Он становится online в web-кабинете; online действует 90 секунд после последнего heartbeat по умолчанию.
8. В карточке `/devices/:deviceId` можно изменить имя и роль, заблокировать/разблокировать или отвязать устройство. Отвязка всегда требует подтверждения.

## Device identity на Android

`DeviceIdentity` содержит `deviceId`, `centerId`, `centerName`, `deviceName`, серверную роль, `deviceUid`, состояние активации и device token. `deviceUid` — UUID установки приложения, который создаётся один раз и не является hardware identifier.

Обычные метаданные identity хранятся в Preferences DataStore. Device token перед записью шифруется AES-GCM ключом из Android Keystore; DataStore получает только IV и ciphertext. Токен не показывается в UI и не выводится логами. При unlink локальная identity и активная сессия удаляются, но `deviceUid` сохраняется для идентификации этой установки при следующей активации.

## Проверка при запуске и состояния доступа

При каждом запуске Android делает следующее:

1. Загружает локальную identity. Если её нет — показывает активацию.
2. Для сохранённого токена вызывает `GET /api/v1/device-auth/me`.
3. При успехе обновляет имя, центр и роль из ответа сервера, затем открывает текущий граф роли.
4. При `DEVICE_BLOCKED` показывает отдельный экран блокировки. Этот экран не даёт перейти к занятиям и позволяет только повторить проверку.
5. При `DEVICE_UNLINKED` или подтверждённом `401` очищает identity и возвращает на активацию.
6. При timeout, отсутствии сети или другой временной ошибке не удаляет токен: показывается экран отсутствия связи с кнопкой повторной проверки.

Heartbeat принадлежит `ViewModel` и не запускается повторно, пока предыдущий цикл активен. Сетевая ошибка увеличивает интервал до пяти минут; `DEVICE_BLOCKED`, `DEVICE_UNLINKED` и подтверждённый `401` обрабатываются так же, как startup validation.

## Локальный запуск

### PostgreSQL и backend

Создайте `deploy/.env` на основе конфигурации развёртывания и задайте минимум:

```env
POSTGRES_PASSWORD=local-password
JWT_SECRET=replace-with-a-long-random-secret
OYLA_SECRET_PEPPER=replace-with-a-different-long-random-secret
OYLA_COOKIE_SECURE=false
```

Запустите стек:

```powershell
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f oyla-server
```

Либо для локального Ktor-сервера нужны `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET` и `OYLA_SECRET_PEPPER`:

```powershell
$env:JAVA_HOME = "D:\path\to\jdk-21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat :server:run
```

В production с TLS-терминацией перед Ktor задайте `OYLA_COOKIE_SECURE=true`. Web refresh token устанавливается как `HttpOnly; SameSite=Lax` cookie; access token не записывается в localStorage или sessionStorage.

### Web

```powershell
cd web-app
npm install
$env:OYLA_SERVER_URL = "http://localhost:8080" # адрес backend для Vite proxy
npm run dev
```

Откройте `http://localhost:5173`. По умолчанию Vite проксирует `/api` к `http://localhost:8080`, поэтому cookie остаётся same-origin для браузера. Для production соберите `npm run build` и раздавайте приложение вместе с backend/reverse proxy либо настройте same-site API origin через `VITE_API_BASE_URL`.

### Android

В `local.properties` для debug уже поддерживаются:

```properties
OYLA_API_BASE_URL=http://10.0.2.2:8080
OYLA_WS_BASE_URL=ws://10.0.2.2:8080
```

Для физического планшета используйте LAN-адрес компьютера и обеспечьте HTTPS или разрешённый debug cleartext configuration. Затем:

```powershell
$env:JAVA_HOME = "D:\path\to\jdk-21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
./gradlew.bat :app:assembleDebug
```

## Ручной smoke test

1. Запустите PostgreSQL/backend, затем web и Android debug build.
2. Зарегистрируйте два центра и войдите в первый.
3. Создайте CHILD activation code в первом центре, активируйте им планшет.
4. Убедитесь, что планшет появился в первом центре, получил роль CHILD и стал online в течение 30 секунд.
5. Проверьте, что второй центр не видит это устройство.
6. Заблокируйте планшет: следующий startup/heartbeat показывает экран блокировки. Разблокируйте и нажмите «Проверить снова».
7. Отвяжите планшет и подтвердите действие: при следующем запросе Android показывает экран активации; старый token больше не действует.
8. Создайте SPECIALIST code и убедитесь, что после активации открывается текущий экран специалиста без ручного role selector.

## Команды тестирования

```powershell
# web
cd web-app
npm test
npm run build

# Kotlin modules (JDK 21)
./gradlew.bat :server:test
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:assembleDebug
```

## Ограничения этапа

- Web polling статуса подключения выполняется раз в 3 секунды, потому что backend пока не отправляет activation event/WebSocket.
- QR-сканер не добавлялся: Android использует безопасный ручной ввод кода.
- Текущие MVP session/exercise endpoints сохранены. Их полная tenant-scoping миграция остаётся следующим этапом, как описано в `docs/saas-foundation.md`.
