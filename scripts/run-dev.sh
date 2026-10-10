#!/usr/bin/env sh
# Starts the platform locally against your MySQL (settings from ../.env). Usage: ./scripts/run-dev.sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -f "$ROOT/.env" ]; then
  echo "Missing .env - copy .env.example to .env and fill in DB_USERNAME / DB_PASSWORD first." >&2
  exit 1
fi
command -v mvn >/dev/null 2>&1 || { echo "Maven (mvn) is not on PATH." >&2; exit 1; }

# Must run from backend/: the dev profile serves the frontend from ../frontend and reads ../.env
cd "$ROOT/backend"
exec mvn -B spring-boot:run
