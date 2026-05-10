# =============================================================================
# Codeleon — one-shot dev launcher (Windows PowerShell 5.1+ compatible)
# =============================================================================
# Brings up the FULL stack with no flags:
#   1. Ensures .env exists (copies from .env.example if needed)
#   2. Frees ports 8080 and 5173 if leftover processes hold them
#   3. Starts Docker containers (postgres + redis + qdrant + ollama)
#   4. Waits for them to report healthy
#   5. Opens a new PowerShell window for the backend (Spring Boot)
#   6. Opens a new PowerShell window for the frontend (Vite)
#   7. Opens the browser to http://localhost:5173 once Vite is ready
#
# Usage:
#   .\scripts\start.ps1            # ← THE button: full stack + AI services
#   .\scripts\start.ps1 -NoAi      # opt out of qdrant + ollama (rare)
#   .\scripts\start.ps1 -SkipDocker # skip docker (use when you only restart app)
#
# AI services are on by default because the PFE demo needs them. If you
# really only want postgres + redis + backend + frontend, pass -NoAi.
#
# To stop everything: .\scripts\stop.ps1
# =============================================================================

[CmdletBinding()]
param(
    [switch]$NoAi,
    [switch]$SkipDocker,
    [switch]$NoBrowser
)
# AI services are on by default — opt out with -NoAi.
$Ai = -not $NoAi

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$jdkPath     = "C:\Users\pc\.jdks\openjdk-23.0.1"

. (Join-Path $PSScriptRoot '_lib.ps1')

function Write-Step($msg)    { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)      { Write-Host "    $msg" -ForegroundColor Green }
function Write-Warn($msg)    { Write-Host "    $msg" -ForegroundColor Yellow }
function Write-Err($msg)     { Write-Host "    $msg" -ForegroundColor Red }

Set-Location $projectRoot

# -----------------------------------------------------------------------------
# 0. Free the dev ports if anything is still holding them.
# -----------------------------------------------------------------------------
# Without this we crash later with "Port 8080 was already in use" whenever a
# previous mvn / java / vite process leaked — typically when the previous
# launcher window was closed without running stop.ps1, or when an external
# tool started its own backend (preview_start, an IDE) and forgot to clean
# up. The helper kills the whole listening process tree.
Write-Step "Releasing dev ports if held by stale processes"
[void](Stop-PortListener -Port 8080 -Label 'backend port 8080')
[void](Stop-PortListener -Port 5173 -Label 'frontend port 5173')

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
    # PowerShell try/catch does not catch native command exit codes, so we
    # have to check $LASTEXITCODE explicitly. We also discard stderr to keep
    # the output clean — the exit code alone tells us whether the daemon is
    # reachable.
    docker info --format '{{.ServerVersion}}' *>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Docker daemon unreachable. Start Docker Desktop and wait for"
        Write-Err "the icon to turn green, then re-run this script. If the daemon"
        Write-Err "refuses to start, try: wsl --shutdown, then relaunch Docker Desktop."
        Write-Err "If you have Postgres + Redis running outside Docker, re-run with -SkipDocker."
        exit 1
    }
    Write-Ok "Docker is up"

    Write-Step "Starting core containers (postgres + redis)"
    docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        Write-Err "docker compose up failed. See output above."
        exit 1
    }

    if ($Ai) {
        Write-Step "Starting AI containers (qdrant + ollama)"
        docker compose --profile ai up -d
        if ($LASTEXITCODE -ne 0) {
            Write-Err "docker compose up (ai profile) failed. See output above."
            exit 1
        }
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
# We pass the spawned command via -EncodedCommand (base64 UTF-16LE) instead of
# -Command to avoid any embedded-quote / backslash-escape weirdness when the
# new powershell.exe parses its argv. The script that runs in the child window
# is plain readable PowerShell; only the transport is encoded.

function Invoke-NewPSWindow {
    param([string]$Script)
    $bytes = [System.Text.Encoding]::Unicode.GetBytes($Script)
    $encoded = [Convert]::ToBase64String($bytes)
    Start-Process powershell -ArgumentList "-NoExit", "-EncodedCommand", $encoded | Out-Null
}

Write-Step "Spawning backend window (Spring Boot)"
# Delegate to run-backend.ps1 so .env loading + JDK pinning + defaults
# stay in a single source of truth (also used by .claude/launch.json).
$backendScript = @"
`$Host.UI.RawUI.WindowTitle = 'Codeleon Backend'
& '$projectRoot\scripts\run-backend.ps1'
"@
Invoke-NewPSWindow -Script $backendScript
Write-Ok "Backend window opened"

# -----------------------------------------------------------------------------
# 4. Frontend window
# -----------------------------------------------------------------------------
Write-Step "Spawning frontend window (Vite)"
$frontendScript = @"
`$Host.UI.RawUI.WindowTitle = 'Codeleon Frontend'
Set-Location '$projectRoot\frontend-web'
if (-not (Test-Path 'node_modules')) {
    Write-Host '=== Installing npm deps (first run) ===' -ForegroundColor Cyan
    npm install
}
Write-Host '=== Codeleon frontend (Vite) ===' -ForegroundColor Cyan
npm run dev
"@
Invoke-NewPSWindow -Script $frontendScript
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
