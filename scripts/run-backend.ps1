# =============================================================================
# Codeleon backend wrapper — used by .claude/launch.json (preview_start).
#
# Sets the env vars Spring Boot needs (JDK 23, Postgres on 5433, AI enabled)
# and runs `mvn spring-boot:run` from the backend module. Kept tiny so the
# launch.json schema (no env/cwd field) stays clean.
# =============================================================================

$ErrorActionPreference = "Stop"

$env:JAVA_HOME    = "C:\Users\pc\.jdks\openjdk-23.0.1"
$env:Path         = "$env:JAVA_HOME\bin;$env:Path"
$env:POSTGRES_PORT = "5433"
$env:AI_ENABLED   = "true"

$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $repoRoot "backend")

Write-Host "=== Codeleon backend ===" -ForegroundColor Cyan
Write-Host ("JAVA_HOME=" + $env:JAVA_HOME)
Write-Host ("POSTGRES_PORT=" + $env:POSTGRES_PORT + "  AI_ENABLED=" + $env:AI_ENABLED)
Write-Host ""

mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Dnet.bytebuddy.experimental=true"
