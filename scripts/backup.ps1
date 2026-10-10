<#
  Backs up the MySQL database and the upload storage folder.

  Usage (from the project folder):
    powershell -ExecutionPolicy Bypass -File scripts\backup.ps1 [-Destination D:\backups] [-KeepDays 14]

  Reads DB_URL, DB_USERNAME, DB_PASSWORD and STORAGE_ROOT from .env (or the environment).
  Needs mysqldump on PATH (MySQL Server / MySQL Shell installs it). Schedule it daily with Task Scheduler.
  The password is passed through a temporary options file, never on the command line.
#>
param(
    [string]$Destination = (Join-Path $PSScriptRoot '..\backups'),
    [int]$KeepDays = 14
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..')

function Read-DotEnv([string]$path) {
    $values = @{}
    if (Test-Path $path) {
        foreach ($line in Get-Content $path) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') { $values[$Matches[1]] = $Matches[2].Trim() }
        }
    }
    return $values
}
$envFile = Read-DotEnv (Join-Path $root '.env')
function Setting([string]$name, [string]$default = '') {
    $v = [Environment]::GetEnvironmentVariable($name)
    if ([string]::IsNullOrEmpty($v)) { $v = $envFile[$name] }
    if ([string]::IsNullOrEmpty($v)) { $v = $default }
    return $v
}

$dbUrl = Setting 'DB_URL' 'jdbc:mysql://localhost:3306/hybrid_platform'
$user = Setting 'DB_USERNAME'
$password = Setting 'DB_PASSWORD'
$storage = Setting 'STORAGE_ROOT' './data/storage'
if (-not $user) { throw 'DB_USERNAME is not set (.env or environment).' }
if ($dbUrl -notmatch '^jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)') { throw "Cannot parse DB_URL: $dbUrl" }
$dbHost = $Matches[1]; $port = if ($Matches[2]) { $Matches[2] } else { '3306' }; $database = $Matches[3]

if (-not (Get-Command mysqldump -ErrorAction SilentlyContinue)) { throw 'mysqldump was not found on PATH.' }
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$sqlFile = Join-Path $Destination "db-$database-$stamp.sql"
$options = [IO.Path]::GetTempFileName()
try {
    Set-Content -Path $options -Encoding ascii -Value "[client]`nuser=$user`npassword=$password`nhost=$dbHost`nport=$port"
    & mysqldump "--defaults-extra-file=$options" --single-transaction --quick --routines --triggers --no-tablespaces `
        --set-gtid-purged=OFF --default-character-set=utf8mb4 "--result-file=$sqlFile" $database
    if ($LASTEXITCODE -ne 0) { throw "mysqldump failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item $options -Force -ErrorAction SilentlyContinue
}
Compress-Archive -Path $sqlFile -DestinationPath "$sqlFile.zip" -Force
Remove-Item $sqlFile
Write-Host "Database saved to $sqlFile.zip"

$storagePath = if ([IO.Path]::IsPathRooted($storage)) { $storage } else { Join-Path (Join-Path $root 'backend') $storage }
if (-not (Test-Path $storagePath)) { $storagePath = Join-Path $root $storage }
if (Test-Path $storagePath) {
    $zip = Join-Path $Destination "storage-$stamp.zip"
    Compress-Archive -Path (Join-Path $storagePath '*') -DestinationPath $zip -Force
    Write-Host "Uploads saved to $zip"
} else {
    Write-Warning "Storage folder not found ($storage); only the database was backed up."
}

Get-ChildItem $Destination -File | Where-Object { $_.Name -match '^(db-|storage-)' -and $_.LastWriteTime -lt (Get-Date).AddDays(-$KeepDays) } |
    ForEach-Object { Remove-Item $_.FullName; Write-Host "Removed old backup $($_.Name)" }
