# Starts the platform locally against your MySQL (settings from ..\.env). Usage: .\scripts\run-dev.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path (Join-Path $root '.env'))) {
    Write-Host "Missing .env - copy .env.example to .env and fill in DB_USERNAME / DB_PASSWORD first." -ForegroundColor Yellow
    exit 1
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host "Maven (mvn) is not on PATH." -ForegroundColor Yellow
    exit 1
}

# Must run from backend/: the dev profile serves the frontend from ../frontend and reads ../.env
Push-Location (Join-Path $root 'backend')
try {
    mvn -B spring-boot:run
} finally {
    Pop-Location
}
