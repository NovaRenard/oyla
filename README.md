# Oyla

Native Android tablet application plus a Kotlin/Ktor session server for specialist and child devices.

## Local Android configuration

For two physical tablets, find the computer's LAN IPv4 address and add these lines to the untracked `local.properties` file (do not commit them):

```properties
OYLA_API_BASE_URL=http://YOUR_COMPUTER_LAN_IP:8083
OYLA_WS_BASE_URL=ws://YOUR_COMPUTER_LAN_IP:8083
```

`debug` permits local HTTP only. `release` always uses `https://api.oyla.kz` and `wss://api.oyla.kz`.

## Run the server from Android Studio

Open the root project, select the Gradle run task `server > application > run`, or run:

```powershell
.\gradlew.bat :server:run
```

The server reads `PORT`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, and `SESSION_CODE_TTL_MINUTES`. Defaults are intended for local PostgreSQL only.

## Docker

See [deploy/README.md](deploy/README.md). Docker publishes the server at port `8083` by default.

## Two-tablet smoke test

1. Start Docker and verify `http://COMPUTER_LAN_IP:8083/health` from the LAN.
2. Put both Android 9+ tablets on the same Wi-Fi and install the debug APK.
3. Configure the same LAN IP in `local.properties`, rebuild, and install on both tablets.
4. On the specialist tablet create a lesson and enter a child name.
5. Enter the displayed four-digit code on the child tablet. The child reaches the waiting screen and the specialist screen updates immediately.

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.
