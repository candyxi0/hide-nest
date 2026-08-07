# verify.ps1 - Unified verification script for hide-nest HDM-002
# Runs Java build, Node typecheck, lint, test, and production build
# Exits non-zero on any failure

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$exitCode = 0

Write-Host "=== hide-nest verify ===" -ForegroundColor Cyan

# 1. Java 25 check
Write-Host "`n[1/7] Checking Java 25..." -ForegroundColor Yellow
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$javaVersion = & java -version 2>&1 | Select-String "25.0.4"
$ErrorActionPreference = $prevEAP
if (-not $javaVersion) {
    Write-Host "FAIL: Java 25 not found" -ForegroundColor Red
    exit 1
}
Write-Host "PASS: Java 25 found" -ForegroundColor Green

# 2. Maven clean verify
Write-Host "`n[2/7] Maven clean verify..." -ForegroundColor Yellow
Push-Location $repoRoot
try {
    & "$repoRoot\mvnw.cmd" clean verify
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    Write-Host "PASS: Maven clean verify" -ForegroundColor Green
} catch {
    Write-Host "FAIL: $_" -ForegroundColor Red
    $exitCode = 1
}
Pop-Location
if ($exitCode -ne 0) { exit $exitCode }

# 3. npm lock check
Write-Host "`n[3/7] npm ci..." -ForegroundColor Yellow
Push-Location $repoRoot
try {
    $env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1"
    & npm ci
    if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
    Write-Host "PASS: npm ci" -ForegroundColor Green
} catch {
    Write-Host "FAIL: $_" -ForegroundColor Red
    $exitCode = 1
}
Pop-Location
if ($exitCode -ne 0) { exit $exitCode }

# 4. typecheck
Write-Host "`n[4/7] Node typecheck..." -ForegroundColor Yellow
Push-Location $repoRoot
try {
    & npm run typecheck --workspaces --if-present
    if ($LASTEXITCODE -ne 0) { throw "typecheck failed" }
    Write-Host "PASS: typecheck" -ForegroundColor Green
} catch {
    Write-Host "FAIL: $_" -ForegroundColor Red
    $exitCode = 1
}
Pop-Location
if ($exitCode -ne 0) { exit $exitCode }

# 5. lint
Write-Host "`n[5/7] Node lint..." -ForegroundColor Yellow
Push-Location $repoRoot
try {
    & npm run lint --workspaces --if-present
    if ($LASTEXITCODE -ne 0) { throw "lint failed" }
    Write-Host "PASS: lint" -ForegroundColor Green
} catch {
    Write-Host "FAIL: $_" -ForegroundColor Red
    $exitCode = 1
}
Pop-Location
if ($exitCode -ne 0) { exit $exitCode }

# 6. test
Write-Host "`n[6/7] Node test..." -ForegroundColor Yellow
Push-Location $repoRoot
try {
    & npm run test --workspaces --if-present
    if ($LASTEXITCODE -ne 0) { throw "test failed" }
    Write-Host "PASS: test" -ForegroundColor Green
} catch {
    Write-Host "FAIL: $_" -ForegroundColor Red
    $exitCode = 1
}
Pop-Location
if ($exitCode -ne 0) { exit $exitCode }

# 7. production build
Write-Host "`n[7/7] Node production build..." -ForegroundColor Yellow
Push-Location $repoRoot
try {
    & npm run build --workspaces --if-present
    if ($LASTEXITCODE -ne 0) { throw "build failed" }
    Write-Host "PASS: production build" -ForegroundColor Green
} catch {
    Write-Host "FAIL: $_" -ForegroundColor Red
    $exitCode = 1
}
Pop-Location

if ($exitCode -eq 0) {
    Write-Host "`n=== ALL CHECKS PASSED ===" -ForegroundColor Green
} else {
    Write-Host "`n=== VERIFICATION FAILED ===" -ForegroundColor Red
}
exit $exitCode
