# =============================================================================
# Codeleon backend wrapper - used by .claude/launch.json (preview_start)
# and by scripts/start.ps1.
#
# Loads the project root .env into the current process so Spring Boot picks
# up every key (Postgres creds, AI flags, OAuth client ids/secrets, etc.),
# pins the JDK 23 install Codeleon needs, then runs mvn spring-boot:run.
#
# Plain ASCII only - PowerShell 5.1 parser is sensitive to non-ASCII chars
# in files saved as UTF-8 without BOM.
# =============================================================================

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot

# -----------------------------------------------------------------------------
# 1. Load .env (if present) into this process's environment.
#    Spring Boot does NOT read .env on its own. docker-compose auto-loads it,
#    the Spring backend does not, so we have to bridge the two here.
# -----------------------------------------------------------------------------

$envFile = Join-Path $repoRoot '.env'
if (Test-Path $envFile) {
    $loaded = 0
    foreach ($line in Get-Content $envFile) {
        $trimmed = $line.Trim()
        if (-not $trimmed) { continue }
        if ($trimmed.StartsWith('#')) { continue }
        if ($trimmed -match '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            $key = $matches[1]
            $value = $matches[2]
            # Strip surrounding quotes if present (KEY="value with spaces").
            if ($value -match '^"(.*)"$') { $value = $matches[1] }
            elseif ($value -match "^'(.*)'$") { $value = $matches[1] }
            Set-Item -Path ('Env:' + $key) -Value $value
            $loaded++
        }
    }
    Write-Host ('Loaded ' + $loaded + ' variables from .env') -ForegroundColor DarkGray
} else {
    Write-Host ('.env not found at ' + $envFile + ' - running with application.yml defaults') -ForegroundColor Yellow
}

# -----------------------------------------------------------------------------
# 2. Pin the JDK Codeleon needs. Always wins over any .env JAVA_HOME so a
#    developer cannot accidentally point at the wrong JDK.
# -----------------------------------------------------------------------------

$env:JAVA_HOME = 'C:\Users\pc\.jdks\openjdk-23.0.1'
$env:Path = $env:JAVA_HOME + '\bin;' + $env:Path

# -----------------------------------------------------------------------------
# 3. Sensible defaults for the Codeleon dev stack. .env values take precedence
#    so a developer who already set them locally is not overridden.
# -----------------------------------------------------------------------------

if (-not $env:POSTGRES_PORT) { $env:POSTGRES_PORT = '5433' }
if (-not $env:AI_ENABLED)    { $env:AI_ENABLED    = 'true' }

# -----------------------------------------------------------------------------
# 4. Banner: visible confirmation that OAuth credentials, Postgres port and
#    AI flag actually made it into the JVM. Secrets are sliced to the first
#    8 characters so they do not leak in screenshots / shared logs.
# -----------------------------------------------------------------------------

function Show-Truncated {
    param([string]$Name, [string]$Value)
    if (-not $Value) { return ($Name + '=(unset)') }
    if ($Value.Length -le 12) { return ($Name + '=' + $Value) }
    return ($Name + '=' + $Value.Substring(0, 8) + '...')
}

Set-Location (Join-Path $repoRoot 'backend')

Write-Host '=== Codeleon backend ===' -ForegroundColor Cyan
Write-Host ('JAVA_HOME=' + $env:JAVA_HOME)
Write-Host ('POSTGRES_PORT=' + $env:POSTGRES_PORT + '  AI_ENABLED=' + $env:AI_ENABLED)
Write-Host (Show-Truncated 'GITHUB_CLIENT_ID' $env:GITHUB_CLIENT_ID)
Write-Host (Show-Truncated 'GOOGLE_CLIENT_ID' $env:GOOGLE_CLIENT_ID)
Write-Host ''

mvn spring-boot:run '-Dspring-boot.run.jvmArguments=-Dnet.bytebuddy.experimental=true'
