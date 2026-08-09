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

# Keep production backups outside the working tree.  The default resolves to
# /srv/apps/oyla/backups when the repository is deployed in /srv/apps/oyla/repo.
backup_dir="${OYLA_BACKUP_DIR:-$(cd "$repo_root/.." && pwd)/backups}"
mkdir -p "$backup_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="$backup_dir/oyla-$timestamp.dump"
temporary_file="$backup_file.partial"
media_file="$backup_dir/oyla-$timestamp-media.tar.gz"
media_temporary_file="$media_file.partial"
compose=(docker compose -f "$repo_root/deploy/docker-compose.prod.yml" --env-file "$env_file")

trap 'rm -f "$temporary_file"' EXIT
"${compose[@]}" exec -T -e "PGPASSWORD=$POSTGRES_PASSWORD" oyla-db \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom --file=- > "$temporary_file"

if [[ ! -s "$temporary_file" ]]; then
  echo "Backup failed: pg_dump produced no data." >&2
  exit 1
fi
mv "$temporary_file" "$backup_file"

# PostgreSQL dumps do not contain user-uploaded files. The named volume is archived
# separately, read-only, and the final file is only published after tar succeeds.
trap 'rm -f "$temporary_file" "$media_temporary_file"' EXIT
docker run --rm -v oyla-media-data:/source:ro -v "$backup_dir":/backup alpine:3.20 \
  tar -C /source -czf "/backup/$(basename "$media_temporary_file")" .
if [[ ! -s "$media_temporary_file" ]]; then
  echo "Backup failed: media archive was empty." >&2
  exit 1
fi
mv "$media_temporary_file" "$media_file"
trap - EXIT
echo "Backups created: $backup_file and $media_file"
