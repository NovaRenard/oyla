#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" != "--confirm" || -z "${2:-}" ]]; then
  echo "Usage: $0 --confirm /absolute/or-relative/backup.dump" >&2
  echo "WARNING: restore replaces production database contents. Stop oyla-server and reverse-proxy or enable maintenance first." >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
env_file="${OYLA_ENV_FILE:-$repo_root/deploy/.env.prod}"
backup_file="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"

if [[ ! -r "$env_file" ]]; then echo "Production environment file not found: $env_file" >&2; exit 2; fi
if [[ ! -f "$backup_file" ]]; then echo "Backup file not found: $backup_file" >&2; exit 2; fi

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a
compose=(docker compose -f "$repo_root/deploy/docker-compose.prod.yml" --env-file "$env_file")

echo "Restoring $backup_file into $POSTGRES_DB. This is destructive."
"${compose[@]}" exec -T -e "PGPASSWORD=$POSTGRES_PASSWORD" oyla-db \
  pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --exit-on-error < "$backup_file"
echo "Restore completed. Start oyla-server and reverse-proxy, then run the smoke test."
