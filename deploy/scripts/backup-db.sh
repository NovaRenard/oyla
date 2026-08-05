#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
env_file="${OYLA_ENV_FILE:-$repo_root/deploy/.env.prod}"

if [[ ! -r "$env_file" ]]; then
  echo "Production environment file not found: $env_file" >&2
  exit 2
fi

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

backup_dir="$repo_root/backups"
mkdir -p "$backup_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="$backup_dir/oyla-$timestamp.dump"
temporary_file="$backup_file.partial"
compose=(docker compose -f "$repo_root/deploy/docker-compose.prod.yml" --env-file "$env_file")

trap 'rm -f "$temporary_file"' EXIT
"${compose[@]}" exec -T -e "PGPASSWORD=$POSTGRES_PASSWORD" oyla-db \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --file=- > "$temporary_file"

if [[ ! -s "$temporary_file" ]]; then
  echo "Backup failed: pg_dump produced no data." >&2
  exit 1
fi
mv "$temporary_file" "$backup_file"
trap - EXIT
echo "Backup created: $backup_file"
