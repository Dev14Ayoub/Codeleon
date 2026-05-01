# =============================================================================
# Codeleon — one-shot dev launcher (Windows PowerShell 5.1+ compatible)
# =============================================================================
# Brings up the full stack:
#   1. Ensures .env exists (copies from .env.example if needed)
#   2. Starts Docker containers (postgres + redis, and qdrant + ollama if -Ai)
#   3. Waits for them to report healthy
#   4. Opens a new PowerShell window for the backend (Spring Boot)
#   5. Opens a new PowerShell window for the frontend (Vite)
#   6. Opens the browser to http://localhost:5173 once Vite is ready
#
# Usage:
#   .\scripts\start.ps1            # core stack only (no AI services)
#   .\scripts\start.ps1 -Ai        # also start qdrant + ollama, AI_ENABLED=true
#   .\scripts\start.ps1 -SkipDocker # skip docker (use when you only restart app)
#
# To stop everything: .\scripts\stop.ps1
# =============================================================================

[CmdletBinding()]
param(
    [switch]$Ai,
    [switch]$SkipDocker,
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkPath     = "C:\Users\pc\.jdks\openjdk-23.0.1"

function Write-Step($msg)    { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)      { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn($msg)    { Write-Host "    $msg" -ForegroundColor Yellow }
function Write-Err($msg)     { Write-Host "    $msg" -ForegroundColor Red }

Set-Location $projectRoot

# -----------------------------------------------------------------------------
# 1. .env
# -----------------------------------------------------------------------------
Write-Step "Checking .env"
if (-not (Test-Path ".env")) {
    if (Test-Path ".env.example") {
        Copy-Item ".env.example" ".env"
        Write-Ok "Created .env from .env.example"
    } else {
        Write-Err ".env.example missing, cannot continue"
        exit 1
    }
} else {
    Write-Ok ".env present"
}

# -----------------------------------------------------------------------------
# 2. Docker
# -----------------------------------------------------------------------------
if (-not $SkipDocker) {
    Write-Step "Checking Docker daemon"
    try {
        docker info --format '{{.ServerVersion}}' | Out-Null
    } catch {
        Write-Err "Docker daemon unreachable. Start Docker Desktop, then re-run."
        exit 1
    }
    Write-Ok "Docker is up"

    Write-Step "Starting core containers (postgres + redis)"
    docker compose up -d | Out-Null

    if ($Ai) {
        Write-Step "Starting AI containers (qdrant + ollama)"
        docker compose --profile ai up -d | Out-Null
    }

    Write-Step "Waiting for containers to become healthy"
    $services = @("codeleon-postgres", "codeleon-redis")
    if ($Ai) { $services += @("codeleon-qdrant", "codeleon-ollama") }

    $deadline = (Get-Date).AddSeconds(60)
    foreach ($svc in $services) {
        do {
            $status = docker inspect -f '{{.State.Health.Status}}' $svc 2>$null
            if ($status -eq "healthy") { break }
            Start-Sleep -Seconds 2
        } while ((Get-Date) -lt $deadline)

        if ($status -eq "healthy") {
            Write-Ok "$svc healthy"
        } else {
            Write-Warn "$svc not healthy after 60s (status=$status). Check 'docker compose logs $svc'."
        }
    }
} else {
    Write-Step "Skipping Docker (--SkipDocker)"
}

# -----------------------------------------------------------------------------
# 3. Backend window
# -----------------------------------------------------------------------------
Write-Step "Spawning backend window (Spring Boot)"
$aiFlag = if ($Ai) { "true" } else { "false" }
$backendCmd = @"
`$Host.UI.RawUI.WindowTitle = 'Codeleon Backend'
Set-Location '$projectRoot\backend'
`$env:JAVA_HOME    = '$jdkPath'
`$env:Path         = "`$env:JAVA_HOME\bin;`$env:Path"
`$env:POSTGRES_PORT = '5433'
`$env:AI_ENABLED   = '$aiFlag'
Write-Host '=== Codeleon backend (Spring Boot) ===' -ForegroundColor Cyan
Write-Host "JAVA_HOME=`$env:JAVA_HOME"
Write-Host "POSTGRES_PORT=`$env:POSTGRES_PORT  AI_ENABLED=`$env:AI_ENABLED"
Write-Host ''
mvn spring-boot:run
"@
Start-Process powershell -ArgumentList "-NoExit", "-Command", $backendCmd | Out-Null
Write-Ok "Backend window opened"

# -----------------------------------------------------------------------------
# 4. Frontend window
# -----------------------------------------------------------------------------
Write-Step "Spawning frontend window (Vite)"
$frontendCmd = @"
`$Host.UI.RawUI.WindowTitle = 'Codeleon Frontend'
Set-Location '$projectRoot\frontend-web'
if (-not (Test-Path 'node_modules')) {
    Write-Host '=== Installing npm deps (first run) ===' -ForegroundColor Cyan
    npm install
}
Write-Host '=== Codeleon frontend (Vite) ===' -ForegroundColor Cyan
npm run dev
"@
Start-Process powershell -ArgumentList "-NoExit", "-Command", $frontendCmd | Out-Null
Write-Ok "Frontend window opened"

# -----------------------------------------------------------------------------
# 5. Browser
# -----------------------------------------------------------------------------
if (-not $NoBrowser) {
    Write-Step "Waiting for Vite to be ready (localhost:5173)"
    $deadline = (Get-Date).AddSeconds(60)
    do {
        $ready = Test-NetConnection -ComputerName localhost -Port 5173 -InformationLevel Quiet -WarningAction SilentlyContinue
        if ($ready) { break }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    if ($ready) {
        Write-Ok "Vite is up, opening browser"
        Start-Process "http://localhost:5173"
    } else {
        Write-Warn "Vite did not respond within 60s. Open the frontend window to check."
    }
}

Write-Host ""
Write-Step "All done. Two new windows are running the backend and frontend."
Write-Host "    Stop everything with: .\scripts\stop.ps1" -ForegroundColor DarkGray
