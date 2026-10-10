#!/usr/bin/env bash
# Backs up the MySQL database and the upload storage folder.
#
#   scripts/backup.sh [destination-dir] [keep-days]
#
# Reads DB_URL, DB_USERNAME, DB_PASSWORD and STORAGE_ROOT from .env (or the environment). Needs mysqldump.
# Daily cron example:  15 2 * * *  cd /opt/hybrid-platform && scripts/backup.sh /var/backups/hybrid 14
# The password goes through a temporary options file (mode 600), never on the command line.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="${1:-$ROOT/backups}"
KEEP_DAYS="${2:-14}"

if [[ -f "$ROOT/.env" ]]; then
  while IFS='=' read -r key value; do
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    if [[ -z "${!key:-}" ]]; then export "$key=${value%$'\r'}"; fi
  done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$ROOT/.env")
fi

DB_URL="${DB_URL:-jdbc:mysql://localhost:3306/hybrid_platform}"
: "${DB_USERNAME:?DB_USERNAME is not set (.env or environment)}"
STORAGE_ROOT="${STORAGE_ROOT:-./data/storage}"

if [[ ! "$DB_URL" =~ ^jdbc:mysql://([^:/?]+)(:([0-9]+))?/([^?]+) ]]; then
  echo "Cannot parse DB_URL: $DB_URL" >&2; exit 1
fi
DB_HOST="${BASH_REMATCH[1]}"; DB_PORT="${BASH_REMATCH[3]:-3306}"; DB_NAME="${BASH_REMATCH[4]}"

command -v mysqldump >/dev/null || { echo "mysqldump was not found on PATH" >&2; exit 1; }
mkdir -p "$DEST"
STAMP="$(date +%Y%m%d-%H%M%S)"
OPTS="$(mktemp)"
chmod 600 "$OPTS"
trap 'rm -f "$OPTS"' EXIT
printf '[client]\nuser=%s\npassword=%s\nhost=%s\nport=%s\n' "$DB_USERNAME" "${DB_PASSWORD:-}" "$DB_HOST" "$DB_PORT" > "$OPTS"

SQL_GZ="$DEST/db-$DB_NAME-$STAMP.sql.gz"
mysqldump --defaults-extra-file="$OPTS" --single-transaction --quick --routines --triggers --no-tablespaces \
  --set-gtid-purged=OFF --default-character-set=utf8mb4 "$DB_NAME" | gzip -9 > "$SQL_GZ"
echo "Database saved to $SQL_GZ"

case "$STORAGE_ROOT" in
  /*) STORAGE_PATH="$STORAGE_ROOT" ;;
  *) STORAGE_PATH="$ROOT/backend/$STORAGE_ROOT"; [[ -d "$STORAGE_PATH" ]] || STORAGE_PATH="$ROOT/$STORAGE_ROOT" ;;
esac
if [[ -d "$STORAGE_PATH" ]]; then
  tar -czf "$DEST/storage-$STAMP.tar.gz" -C "$STORAGE_PATH" .
  echo "Uploads saved to $DEST/storage-$STAMP.tar.gz"
else
  echo "Warning: storage folder not found ($STORAGE_ROOT); only the database was backed up." >&2
fi

find "$DEST" -maxdepth 1 -type f \( -name 'db-*' -o -name 'storage-*' \) -mtime +"$KEEP_DAYS" -print -delete
