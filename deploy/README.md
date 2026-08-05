# Oyla Docker deployment

`docker-compose.yml` remains the local development stack. Production uses `docker-compose.prod.yml`: PostgreSQL, Ktor and web are private services and Caddy is the only public service on 80/443.

```powershell
docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.yml --env-file deploy/.env logs -f oyla-server
docker compose -f deploy/docker-compose.yml --env-file deploy/.env down
```

The local health endpoint is `http://localhost:8083/health`.

For production, copy `.env.prod.example` to `.env.prod`, fill every placeholder, and follow [the production runbook](../docs/production-deployment.md). Never commit `.env.prod`; keep `OYLA_COOKIE_SECURE=true` and `ALLOW_PUBLIC_REGISTRATION=false`.
