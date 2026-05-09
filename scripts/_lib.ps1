# =============================================================================
# Shared helpers for scripts/start.ps1 and scripts/stop.ps1.
#
# Dot-source from the calling script:
#     . (Join-Path $PSScriptRoot '_lib.ps1')
# =============================================================================

# Find the PIDs listening on a given TCP port and kill them, walking the
# process tree so we also catch children (e.g. mvn -> Spring Boot Java).
#
# Why this exists: stop.ps1 historically matched processes by window title,
# which only catches backends launched via the start.ps1 wrapper that sets
# the window title. A backend launched from a plain `mvn spring-boot:run`,
# or one spawned by an external tool (Claude Code preview_start, an IDE),
# kept holding the port after stop.ps1 ran. Subsequent start.ps1 invocations
# then crashed with "Port 8080 was already in use" — exactly the loop we
# want to be unable to fall into.
function Stop-PortListener {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)] [int]$Port,
        [string]$Label = "port $Port"
    )

    $conns = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if (-not $conns) {
        # Nothing listening — port is already free, which is what the
        # caller wants. Return $true so start.ps1 can treat "was empty"
        # and "we killed the holder" as the same success state.
        return $true
    }

    $rootPids = $conns | Select-Object -ExpandProperty OwningProcess -Unique
    Write-Host ("    Freeing $Label (pids " + ($rootPids -join ', ') + ")") -ForegroundColor Yellow

    foreach ($rootPid in $rootPids) {
        # Build a child-first kill list so we don't leave dangling daemons.
        $allPids = New-Object System.Collections.Generic.List[int]
        $queue = New-Object System.Collections.Generic.Queue[int]
        $queue.Enqueue([int]$rootPid)
        while ($queue.Count -gt 0) {
            $current = $queue.Dequeue()
            if ($allPids -notcontains $current) {
                $allPids.Add($current) | Out-Null
            }
            $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$current" -ErrorAction SilentlyContinue
            foreach ($child in $children) {
                if ($allPids -notcontains [int]$child.ProcessId) {
                    $queue.Enqueue([int]$child.ProcessId)
                }
            }
        }

        # Kill children first, then the root. Stop-Process refuses some
        # processes with "Access is denied"; fall back to the WMI terminate
        # method which works on the same processes from a non-elevated
        # shell because it goes through the WMI service.
        foreach ($targetPid in ($allPids | Sort-Object -Descending)) {
            $stopped = $false
            try {
                Stop-Process -Id $targetPid -Force -ErrorAction Stop
                $stopped = $true
            } catch {
                # Stop-Process failed — try WMI terminate.
                $rc = (Invoke-CimMethod -ClassName Win32_Process -MethodName Terminate -Arguments @{ } -ErrorAction SilentlyContinue `
                       | Where-Object { $false })  # placeholder so we still attempt below
                try {
                    $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$targetPid" -ErrorAction SilentlyContinue
                    if ($proc) {
                        $result = $proc | Invoke-CimMethod -MethodName Terminate
                        if ($result.ReturnValue -eq 0) {
                            $stopped = $true
                        }
                    }
                } catch {
                    # final fallback: taskkill /F
                    & taskkill.exe /F /PID $targetPid 2>$null | Out-Null
                    if ($LASTEXITCODE -eq 0) { $stopped = $true }
                }
            }
            if ($stopped) {
                Write-Host "      killed pid $targetPid" -ForegroundColor DarkGray
            } else {
                Write-Host "      could NOT kill pid $targetPid (try a manual taskkill /F /PID $targetPid in an admin shell)" -ForegroundColor Red
            }
        }
    }

    # Give Windows a moment to release the socket, then verify.
    Start-Sleep -Milliseconds 500
    $still = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($still) {
        Write-Host ("    $Label still held after kill attempt (pids " + (($still | Select-Object -ExpandProperty OwningProcess -Unique) -join ', ') + ")") -ForegroundColor Red
        return $false
    }
    Write-Host "    $Label is now free" -ForegroundColor Green
    return $true
}
