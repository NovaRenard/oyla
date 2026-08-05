# SaaS foundation

## Tenant isolation

`Center` is the tenant boundary. Every new SaaS entity that represents center-owned data has a non-null `center_id`, and every web request first resolves the JWT user, then the JWT `active_center_id`, then reads the current `CenterMembership` from PostgreSQL. The current user status, center status, membership status, and role are checked on every center-scoped request; a JWT claim alone never permanently authorizes membership.

Device routes always query by both `center_id` and `device_id`. Consequently, a UUID from another center produces `DEVICE_NOT_FOUND` and cannot be read, modified, unlinked, or used to cancel an activation code. `centerId` is never taken from an arbitrary client request to scope data: it comes from a signed access token after `/centers/{centerId}/select` has verified membership.

## Data model and roles

Flyway migration `V4__create_saas_foundation.sql` introduces:

- `centers`: tenant name, unique slug, status, timezone, timestamps.
- `users`: normalized unique lowercase email, BCrypt password hash and login state.
- `center_memberships`: unique `(center_id, user_id)` with OWNER, ADMIN, METHODIST, or SPECIALIST role and membership status. A deferred PostgreSQL constraint trigger prevents a center from committing without an OWNER membership.
- `refresh_tokens`: only HMAC-SHA-256 hashes are stored; tokens are rotated on refresh and can be revoked.
- `devices`: center-owned tablet identity, device-token hash, activation/heartbeat metadata, and computed online state.
- `device_activation_codes`: hashed one-time 8-character activation codes with PENDING, USED, EXPIRED, or CANCELLED state.
- `audit_logs`: non-secret audit metadata for center, auth, device, and activation events.

OWNER and ADMIN can edit the center, create/cancel activation codes, update devices, block/unblock, and unlink them. METHODIST and SPECIALIST have read-only access to the currently implemented device list and cannot create codes. No membership-management endpoint is included yet.

## Web authentication

`POST /api/v1/auth/register-center` creates a center, active owner user, active OWNER membership, and a refresh-token session in one database transaction. Login accepts a case-insensitive email and stores it lowercase. Passwords use BCrypt (default cost 12; test cost 10).

Access JWTs are HMAC-512 signed and expire after 15 minutes by default. They carry user ID and the selected active center. Refresh tokens expire after 30 days by default, are high-entropy random values, are stored only as HMAC hashes, and rotate on `POST /api/v1/auth/refresh`. `POST /api/v1/auth/logout` revokes a supplied refresh token.

Set `JWT_SECRET` and a separate `OYLA_SECRET_PEPPER` to independent high-entropy values in every non-development deployment. Neither passwords, access/refresh/device tokens, nor full activation codes are logged. The application logger records only request method/path and status.

## Device activation and authentication

An OWNER or ADMIN creates an 8-character code from an alphabet without ambiguous `I`, `O`, `0`, or `1`. The only response that contains the full code is the creation response. PostgreSQL stores an HMAC hash, and the code expires after 10 minutes by default.

`POST /api/v1/device-auth/activate` normalizes the code and, in one transaction, locks the code row, validates it, creates or reactivates the unique `device_uid`, consumes the code, writes audit data, and stores only the hash of a newly generated device token. A second use is rejected. Invalid, expired, cancelled, and already-used codes return the same public `INVALID_ACTIVATION_CODE` response to avoid code-status enumeration.

Subsequent device calls use `Authorization: Bearer <device token>` and the dedicated Ktor `device-token` authentication provider. A blocked device receives `DEVICE_BLOCKED`; an unlinked device receives `DEVICE_UNLINKED`. Unlinking marks the existing token revoked, so it cannot access device functions. A heartbeat updates version/model and `last_seen_at`; `isOnline` is computed as `last_seen_at` less than 90 seconds ago, never stored as a boolean.

Public activation has an in-process sliding-window limit of five attempts per minute for each IP and device UID. This protects a single server instance. Before horizontal scaling, replace it with an atomic shared limiter such as Redis and configure trusted-proxy handling for client IPs.

## Endpoints

| Area | Endpoints |
| --- | --- |
| Web auth | `POST /api/v1/auth/register-center`, `login`, `refresh`, `logout`; `GET /api/v1/auth/me` |
| Centers | `GET /api/v1/centers`, `GET/PATCH /api/v1/centers/current`, `POST /api/v1/centers/{centerId}/select` |
| Web devices | `GET /api/v1/devices`, `GET/PATCH /api/v1/devices/{deviceId}`, `POST /api/v1/devices/{deviceId}/unlink` |
| Activation codes | `POST/GET /api/v1/devices/activation-codes`, `DELETE /api/v1/devices/activation-codes/{id}` |
| Tablet auth | `POST /api/v1/device-auth/activate`, `GET /api/v1/device-auth/me`, `POST /api/v1/device-auth/heartbeat` |

`GET /api/v1/devices` accepts optional `role`, `status`, and `isOnline=true|false` query parameters. New SaaS APIs use the error envelope below; old MVP session APIs retain their existing flat error contract for Android compatibility.

```json
{
  "error": {
    "code": "DEVICE_BLOCKED",
    "message": "Устройство заблокировано",
    "details": null,
    "requestId": "..."
  }
}
```

## Local run and migrations

Requirements: Docker Desktop. Copy `deploy/.env.example` to `deploy/.env`, replace the two secrets and database password, then run:

```powershell
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f oyla-server
```

Flyway runs automatically before the Ktor server accepts traffic. To apply migrations to a clean local database, start the stack with an empty Docker volume. To stop it, use:

```powershell
docker compose -f deploy/docker-compose.yml --env-file deploy/.env down
```

For a non-Docker local server, set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET`, and `OYLA_SECRET_PEPPER`, then run `./gradlew :server:run` from an environment with JDK 21.

## curl examples

```bash
# Register owner and center; save accessToken and refreshToken from the response.
curl -X POST http://localhost:8083/api/v1/auth/register-center \
  -H 'Content-Type: application/json' \
  -d '{"centerName":"Центр речи","firstName":"Алия","lastName":"Серикова","email":"aliya@example.com","password":"Password123"}'

curl -X POST http://localhost:8083/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"aliya@example.com","password":"Password123"}'

# Create a one-time CHILD activation code.
curl -X POST http://localhost:8083/api/v1/devices/activation-codes \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"deviceName":"Детский планшет — Кабинет 1","deviceRole":"CHILD"}'

# Tablet activation: keep deviceToken only in encrypted app storage.
curl -X POST http://localhost:8083/api/v1/device-auth/activate \
  -H 'Content-Type: application/json' \
  -d '{"activationCode":"ABC234XY","deviceUid":"stable-installation-id","appVersion":"1.1.0","androidVersion":"14","model":"Samsung Tab"}'

curl -X POST http://localhost:8083/api/v1/device-auth/heartbeat \
  -H "Authorization: Bearer $DEVICE_TOKEN" -H 'Content-Type: application/json' \
  -d '{"appVersion":"1.1.0","androidVersion":"14","model":"Samsung Tab"}'

curl -H "Authorization: Bearer $ACCESS_TOKEN" 'http://localhost:8083/api/v1/devices?isOnline=true'

curl -X PATCH http://localhost:8083/api/v1/devices/$DEVICE_ID \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"BLOCKED"}'

curl -X POST http://localhost:8083/api/v1/devices/$DEVICE_ID/unlink \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

## Tests

Run the server test suite with JDK 21:

```powershell
./gradlew :server:test
```

The route tests use the project’s existing in-memory repository pattern, including synchronization for the concurrent activation test. Production activation uses PostgreSQL row locking (`SELECT ... FOR UPDATE`) within the same transaction. Run the Docker stack against an empty PostgreSQL volume before release to verify Flyway on the target database engine.

## MVP migration debt

The original session, exercise, and WebSocket routes remain unchanged and keep their existing short-lived session tokens so that the released Android MVP continues to work. V4 adds nullable `sessions.center_id` and an index but does not populate it. No SaaS endpoint returns legacy global sessions or exercises.

The next migration should: (1) make Android use the activated `devices.id`; (2) set `sessions.center_id` from that device atomically on creation; (3) backfill historical sessions only when ownership can be proven; (4) scope all session/exercise queries and WebSockets by center; and finally (5) make `sessions.center_id` non-null. Device role changes currently inspect legacy active sessions by the stringified device UUID; this becomes authoritative once step 1 is complete.
