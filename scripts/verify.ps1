# HDM-003-R1 fail-closed verification entry point.

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$javaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot"
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
$env:npm_config_registry = "https://registry.npmjs.org/"
$env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1"

function Invoke-Checked {
    param([string]$Label, [string]$Executable, [string[]]$Arguments)
    Write-Host "`n[$Label] $Executable $($Arguments -join ' ')" -ForegroundColor Yellow
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

function Invoke-OpenApiValidation {
    param([string]$InputSpec, [bool]$ExpectedSuccess, [string]$Label)
    $args = @(
        "-o",
        "org.openapitools:openapi-generator-maven-plugin:7.24.0:validate",
        "-Dopenapi.generator.maven.plugin.inputSpec=$InputSpec"
    )
    Write-Host "`n[$Label] mvnw.cmd $($args -join ' ')" -ForegroundColor Yellow
    $output = & (Join-Path $repoRoot "mvnw.cmd") @args 2>&1
    $code = $LASTEXITCODE
    if ($ExpectedSuccess -and $code -ne 0) {
        $output | Select-Object -Last 80
        throw "$Label expected OpenAPI validation success, got exit code $code"
    }
    if (-not $ExpectedSuccess -and $code -eq 0) {
        throw "$Label accepted a broken OpenAPI $ref"
    }
    Write-Host "$Label exit=$code" -ForegroundColor Green
}

Push-Location $repoRoot
try {
    Invoke-Checked "maven-clean-verify" (Join-Path $repoRoot "mvnw.cmd") @("clean", "verify")

    Invoke-OpenApiValidation (Join-Path $repoRoot "contracts/openapi/hide-nest-api.yaml") $true "openapi-valid"

    $negativeRoot = Join-Path $env:TEMP ("hdm003-r1-openapi-negative-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $negativeRoot -Force | Out-Null
    try {
        $negativeSpec = Join-Path $negativeRoot "broken.yaml"
        Copy-Item -LiteralPath (Join-Path $repoRoot "contracts/openapi/hide-nest-api.yaml") -Destination $negativeSpec -Force
        $brokenText = Get-Content -Raw -Encoding UTF8 $negativeSpec
        $brokenText = $brokenText.Replace("#/components/schemas/ProblemDetail", "#/components/schemas/__HDM003_MISSING__")
        [IO.File]::WriteAllText($negativeSpec, $brokenText, [Text.UTF8Encoding]::new($false))
        Invoke-OpenApiValidation $negativeSpec $false "openapi-broken-ref-negative"
    } finally {
        if (Test-Path -LiteralPath $negativeRoot) {
            Remove-Item -LiteralPath $negativeRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    Invoke-Checked "npm-ci" "npm.cmd" @("ci")
    Invoke-Checked "npm-ls-all" "npm.cmd" @("ls", "--all")
    Invoke-Checked "generate-all" "powershell.exe" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $repoRoot "scripts/generate.ps1"), "-Target", "all")
    Invoke-Checked "generate-all-check" "powershell.exe" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $repoRoot "scripts/generate.ps1"), "-Target", "all", "-Check")
    Invoke-Checked "npm-typecheck-workspaces" "npm.cmd" @("run", "typecheck", "--workspaces", "--if-present")
    Invoke-Checked "npm-lint-workspaces" "npm.cmd" @("run", "lint", "--workspaces", "--if-present")
    Invoke-Checked "npm-test-workspaces" "npm.cmd" @("run", "test", "--workspaces", "--if-present")
    Invoke-Checked "npm-build-workspaces" "npm.cmd" @("run", "build", "--workspaces", "--if-present")
    Invoke-Checked "compatibility" "powershell.exe" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $repoRoot "scripts/contract-compatibility.ps1"))
    Invoke-Checked "format-check" "powershell.exe" @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $repoRoot "scripts/format-check.ps1"))
    Write-Host "`nHDM003_VERIFY_PASS" -ForegroundColor Green
    exit 0
} finally {
    Pop-Location
}
