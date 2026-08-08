# Production deployment

This runbook deploys the web cabinet and API on one HTTPS domain. PostgreSQL and Ktor remain on the private Docker network. In a direct deployment only Caddy publishes ports; when a shared Cloudflare Tunnel is already the Internet edge, use the dedicated loopback-only override below.

## 1. Prepare the server and DNS

Use a supported Linux server with Docker Engine/Compose v2, at least 2 GB RAM, persistent disk for PostgreSQL and a public IPv4/IPv6 address. Create an `A`/`AAAA` record for `OYLA_DOMAIN` before startup. Open TCP ports 80 and 443 only; do not expose 5432 or 8080. Install Docker using the distribution instructions and clone this repository.

```bash
sudo mkdir -p /srv/apps/oyla
sudo chown -R "$(id -un)":"$(id -gn)" /srv/apps/oyla
git clone https://github.com/NovaRenard/Oyla.git /srv/apps/oyla/repo
cd /srv/apps/oyla/repo
cp deploy/.env.prod.example deploy/.env.prod
chmod 600 deploy/.env.prod
```

Edit `deploy/.env.prod`. Generate independent secrets without putting them in shell history:

```bash
openssl rand -base64 48   # JWT_SECRET
openssl rand -base64 48   # OYLA_SECRET_PEPPER
```

`JWT_SECRET`, `OYLA_SECRET_PEPPER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `POSTGRES_USER`, `OYLA_DOMAIN`, token TTLs and `BCRYPT_LOG_ROUNDS` are required. Set `OYLA_DOMAIN=oyla.saadsarbas.tech`; Caddy's automatic HTTPS works without a configured contact email. Keep `OYLA_COOKIE_SECURE=true` and `ALLOW_PUBLIC_REGISTRATION=false`.

## 2. Validate and start

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod config
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod build
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod up -d
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod ps
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod logs -f
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod down
```

On first boot Flyway runs before Ktor becomes healthy. Verify `https://<OYLA_DOMAIN>/health` returns `{"status":"ok"}` and `https://<OYLA_DOMAIN>/login` opens. Caddy obtains and renews HTTPS certificates automatically. The web application calls `/api/...` on the same origin; the refresh cookie is `HttpOnly`, `Secure`, `SameSite=Lax`, scoped to `/api/v1/auth`, and never accessible to JavaScript.

### Shared Cloudflare Tunnel

If the server already uses a host-networked shared Cloudflare Tunnel, do not publish a second Caddy on 80/443. Add a Cloudflared ingress rule for `oyla.saadsarbas.tech` pointing to `http://127.0.0.1:8082`, then use the override on every Compose command:

```bash
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.prod.cloudflared.yml --env-file deploy/.env.prod config
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.prod.cloudflared.yml --env-file deploy/.env.prod build
docker compose -f deploy/docker-compose.prod.yml -f deploy/docker-compose.prod.cloudflared.yml --env-file deploy/.env.prod up -d
```

This makes the Oyla Caddy router listen only on `127.0.0.1:8082`; Cloudflare provides public HTTPS and WSS while Caddy still routes `/health`, `/api/*`, and `/ws/*` to the private services.

## 3. Create the first center

Public `/register` and `POST /api/v1/auth/register-center` are disabled in production. Create the first owner with the CLI. It uses the same Flyway/database/business rules as Ktor, but does not accept a password argument.

Interactive (local terminal):

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod run --rm oyla-admin create-center \
  --center-name "Коррекционный центр Oyla Demo" \
  --owner-first-name "Саад" \
  --owner-last-name "Администратор" \
  --owner-email "admin@example.com" \
  --timezone "Asia/Almaty"
```

For Docker automation, set `OYLA_ADMIN_INITIAL_PASSWORD` in the invoking environment (not in the compose file), or pass a password through stdin:

```bash
printf '%s' "$OYLA_ADMIN_INITIAL_PASSWORD" | docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod run --rm -T oyla-admin \
  create-center --center-name "Центр" --owner-first-name "Администратор" --owner-email "admin@example.com" --timezone "Asia/Almaty" --password-stdin
```

Resetting an owner password revokes all of that user's refresh sessions and records an audit event:

```bash
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod run --rm oyla-admin \
  reset-password --email "admin@example.com"
```

For a non-Docker operator environment, set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `JWT_SECRET`, `OYLA_SECRET_PEPPER` and `OYLA_ADMIN_INITIAL_PASSWORD` first, then run:

```bash
# Linux/macOS
./gradlew :server:run --args='create-center --center-name "Центр" --owner-first-name "Администратор" --owner-email "admin@example.com" --timezone "Asia/Almaty"'

# Windows PowerShell
.\gradlew.bat :server:run --args="create-center --center-name \"Центр\" --owner-first-name \"Администратор\" --owner-email \"admin@example.com\" --timezone \"Asia/Almaty\""
```

## 4. Onboard and operate devices

1. Sign in at `/login` with the CLI-created owner.
2. In **Devices**, create an activation code.
3. Enter it on the Android tablet. The device appears in the center device list and sends heartbeats.
4. Verify online state after a heartbeat. A device is online for `DEVICE_ONLINE_WINDOW_SECONDS` after `last_seen_at`.
5. Block/unblock or unlink from the device page. Unlink revokes the old token.

An ACTIVE or BLOCKED `device_uid` cannot be claimed by another center; the code remains pending. Only an explicitly UNLINKED tablet can be reactivated, including into another center, and it receives a new token. Existing Android release URL configuration is unchanged; verify it separately before changing `BuildConfig` URLs.

## 5. Logs, update, rollback, backup and restore

Follow logs with the `logs -f` command above. To update, pull the intended revision, run `config`, then `build` and `up -d`; verify health and smoke tests. Roll back by checking out the previous known-good revision, rebuilding, and starting it again. Do not roll back a database migration without a tested restore plan.

Create a custom-format backup (Git Bash/WSL/Linux):

```bash
chmod +x deploy/scripts/*.sh
deploy/scripts/backup-db.sh
```

Backups are timestamped under `/srv/apps/oyla/backups/` by default (or under `OYLA_BACKUP_DIR` if set). For restore, stop app traffic (`docker compose ... stop reverse-proxy oyla-server` or use maintenance mode), then explicitly confirm:

```bash
deploy/scripts/restore-db.sh --confirm /srv/apps/oyla/backups/oyla-YYYYMMDDTHHMMSSZ.dump
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod up -d
```

## 6. Deployment smoke test

Without public credentials, verify these in the operator session after provisioning:

1. `/`, `/login`, and `/health` return HTTP 200; `/register` redirects to `/login` and public `register-center` returns `REGISTRATION_DISABLED`.
2. Run `oyla-admin create-center`; sign in as that owner and confirm the response sets the refresh cookie and `/api/v1/auth/me` succeeds.
3. Create a code, activate a tablet, and see it in the device list. Confirm heartbeat changes `last_seen_at`/online state.
4. Create a second center. It cannot see the first center's device; activation with the same ACTIVE device UID fails without consuming its code.
5. Verify a WebSocket connection through `wss://<OYLA_DOMAIN>/...` for the existing MVP routes, then unlink the tablet and confirm its old token receives an authorization error.

Do not run this with real credentials in a public CI job. Caddy forwards API and WebSocket upgrade requests to the private Ktor service, and Ktor trusts forwarded client addresses only in this topology.
