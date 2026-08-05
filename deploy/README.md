# Oyla server in Docker

Before the first run, copy `.env.example` to `.env` and replace the database password,
`JWT_SECRET`, and `OYLA_SECRET_PEPPER` with independent high-entropy values.

```powershell
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f oyla-server
docker compose -f deploy/docker-compose.yml --env-file deploy/.env down
```

The local health endpoint is `http://localhost:8083/health`.
