# =============================================================================
# Codeleon — one-shot dev shutdown
# =============================================================================
# Stops backend + frontend windows (by title) and brings down Docker containers.
#
# Usage:
#   .\scripts\stop.ps1            # stop apps + docker (preserves volumes)
#   .\scripts\stop.ps1 -KeepDocker # only close backend/frontend windows
# =============================================================================

[CmdletBinding()]
param(
    [switch]$KeepDocker
)

$projectRoot = Split-Path -Parent $PSScriptRoot
function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }

Set-Location $projectRoot

Write-Step "Closing Codeleon backend / frontend windows"
$titles = @("Codeleon Backend", "Codeleon Frontend")
foreach ($title in $titles) {
    $procs = Get-Process | Where-Object { $_.MainWindowTitle -eq $title }
    foreach ($p in $procs) {
        try {
            Stop-Process -Id $p.Id -Force
            Write-Ok "Closed '$title' (pid $($p.Id))"
        } catch {
            Write-Host "    Could not close pid $($p.Id): $_" -ForegroundColor Yellow
        }
    }
}

if (-not $KeepDocker) {
    Write-Step "Stopping Docker containers (volumes preserved)"
    docker compose --profile ai down 2>$null
    Write-Ok "Containers stopped"
} else {
    Write-Step "Keeping Docker up (--KeepDocker)"
}

Write-Host ""
Write-Step "Done."
