# format-check.ps1 - Format checking for hide-nest
# Runs Spotless check (Java) and Prettier check (Node)
# Exits non-zero on any formatting violation

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$exitCode = 0

Write-Host "=== hide-nest format check ===" -ForegroundColor Cyan

# 1. Spotless check (Java)
Write-Host "`n[1/2] Spotless check (Java)..." -ForegroundColor Yellow
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Push-Location $repoRoot
try {
    & "$repoRoot\mvnw.cmd" spotless:check
    if ($LASTEXITCODE -ne 0) { throw "Spotless check failed" }
    Write-Host "PASS: Spotless check" -ForegroundColor Green
} catch {
    Write-Host "FAIL: $_" -ForegroundColor Red
    $exitCode = 1
}
Pop-Location

# 2. Prettier check (Node) — offline-only via repo-local binary
Write-Host "`n[2/2] Prettier check (Node)..." -ForegroundColor Yellow
Push-Location $repoRoot
try {
    $prettierCmd = Join-Path $repoRoot "node_modules\.bin\prettier.cmd"
    if (-not (Test-Path $prettierCmd)) {
        throw "Local Prettier binary not found at $prettierCmd — run 'npm ci' first; offline mode forbids download"
    }
    & $prettierCmd --check `
        "package.json" `
        "package-lock.json" `
        "tsconfig.base.json" `
        "eslint.config.mjs" `
        ".prettierrc.json" `
        "apps/**/*.{ts,tsx,js,json,css,md}" `
        "packages/**/*.{ts,tsx,js,json,css,md}"
    if ($LASTEXITCODE -ne 0) { throw "Prettier check failed" }
    Write-Host "PASS: Prettier check" -ForegroundColor Green
} catch {
    Write-Host "FAIL: $_" -ForegroundColor Red
    $exitCode = 1
}
Pop-Location

if ($exitCode -eq 0) {
    Write-Host "`n=== FORMAT CHECK PASSED ===" -ForegroundColor Green
} else {
    Write-Host "`n=== FORMAT CHECK FAILED ===" -ForegroundColor Red
}
exit $exitCode
