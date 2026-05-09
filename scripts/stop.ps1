# =============================================================================
# Codeleon — one-shot dev shutdown
# =============================================================================
# Stops everything launched by start.ps1 (or by anything else holding the
# Codeleon dev ports) and brings down the Docker containers.
#
# Historically this script only matched processes by window title, which
# missed mvn / java orphans launched from a plain shell or by an external
# tool (an IDE, a previous Claude Code preview_start, etc.). The next
# start.ps1 then crashed with "Port 8080 was already in use". The shared
# helper Stop-PortListener walks the listening process tree on a given
# port and kills everything, so the next start has a clean slate.
#
# Usage:
#   .\scripts\stop.ps1            # stop apps + docker (preserves volumes)
#   .\scripts\stop.ps1 -KeepDocker # only close backend/frontend
# =============================================================================

[CmdletBinding()]
param(
    [switch]$KeepDocker
)

$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot '_lib.ps1')

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "    $msg" -ForegroundColor Green }

Set-Location $projectRoot

Write-Step "Closing Codeleon backend / frontend windows by title"
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

Write-Step "Releasing Codeleon dev ports"
[void](Stop-PortListener -Port 8080 -Label 'backend port 8080')
[void](Stop-PortListener -Port 5173 -Label 'frontend port 5173')

if (-not $KeepDocker) {
    Write-Step "Stopping Docker containers (volumes preserved)"
    docker compose --profile ai down 2>$null
    Write-Ok "Containers stopped"
} else {
    Write-Step "Keeping Docker up (--KeepDocker)"
}

Write-Host ""
Write-Step "Done."
