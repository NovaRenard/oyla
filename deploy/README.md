# Oyla server in Docker

Before the first run, copy `.env.example` to `.env` and replace `POSTGRES_PASSWORD`.

```powershell
docker compose -f deploy/docker-compose.yml up -d --build
docker compose -f deploy/docker-compose.yml logs -f oyla-server
docker compose -f deploy/docker-compose.yml down
```

The local health endpoint is `http://localhost:8083/health`.
