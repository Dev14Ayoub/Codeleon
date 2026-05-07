# =============================================================================
# Codeleon backend wrapper — used by .claude/launch.json (preview_start)
# and by scripts/start.ps1.
#
# Loads the project root .env into the current process so Spring Boot picks
# up every key (Postgres creds, AI flags, OAuth client ids/secrets, etc.),
# pins the JDK 23 install Codeleon needs, then runs `mvn spring-boot:run`.
# =============================================================================

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot

# -----------------------------------------------------------------------------
# 1. Load .env (if present) into this process's environment.
#    Spring Boot does NOT read .env on its own — it only knows about the
#    OS-level env vars at JVM start. docker-compose auto-loads .env, the
#    Spring backend does not, so we have to bridge the two here.
# -----------------------------------------------------------------------------

$envFile = Join-Path $repoRoot ".env"
if (Test-Path $envFile) {
    $loaded = 0
    foreach ($line in Get-Content $envFile) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        if ($trimmed.StartsWith("#")) { continue }
        if ($trimmed -match "^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$") {
            $key = $matches[1]
            $value = $matches[2]
            # Strip surrounding quotes if present (KEY="value with spaces").
            if ($value -match '^"(.*)"$') { $value = $matches[1] }
            elseif ($value -match "^'(.*)'$") { $value = $matches[1] }
            Set-Item -Path "Env:$key" -Value $value
            $loaded++
        }
    }
    Write-Host "Loaded $loaded variables from .env" -ForegroundColor DarkGray
} else {
    Write-Host ".env not found at $envFile — running with defaults from application.yml" -ForegroundColor Yellow
}

# -----------------------------------------------------------------------------
# 2. Pin the JDK Codeleon needs (system JAVA_HOME may point at JDK 17 which
#    cannot build the 21 source level). Always wins over .env's JAVA_HOME.
# -----------------------------------------------------------------------------

$env:JAVA_HOME = "C:\Users\pc\.jdks\openjdk-23.0.1"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# -----------------------------------------------------------------------------
# 3. Sensible defaults for the Codeleon dev stack. .env values take precedence
#    so a developer who already set them locally is not overridden.
# -----------------------------------------------------------------------------

if (-not $env:POSTGRES_PORT) { $env:POSTGRES_PORT = "5433" }
if (-not $env:AI_ENABLED)    { $env:AI_ENABLED    = "true" }

# -----------------------------------------------------------------------------
# 4. Banner: helpful to verify visually that OAuth credentials, Postgres port,
#    and AI flag are picked up. Secrets are truncated so they do not leak in
#    captured screenshots / shared logs.
# -----------------------------------------------------------------------------

function Show-Truncated($name, $value) {
    if (-not $value) { return "$name=(unset)" }
    if ($value.Length -le 12) { return "$name=$value" }
    return "$name=$($value.Substring(0, 8))..."
}

Set-Location (Join-Path $repoRoot "backend")

Write-Host "=== Codeleon backend ===" -ForegroundColor Cyan
Write-Host ("JAVA_HOME=" + $env:JAVA_HOME)
Write-Host ("POSTGRES_PORT=" + $env:POSTGRES_PORT + "  AI_ENABLED=" + $env:AI_ENABLED)
Write-Host (Show-Truncated "GITHUB_CLIENT_ID" $env:GITHUB_CLIENT_ID)
Write-Host (Show-Truncated "GOOGLE_CLIENT_ID" $env:GOOGLE_CLIENT_ID)
Write-Host ""

mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dnet.bytebuddy.experimental=true"
