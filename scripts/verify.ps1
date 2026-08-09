# HDM-003-R1 fail-closed verification entry point.

param(
    [string]$MavenSettingsPath,
    [string]$MavenLocalRepository
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$javaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot"
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"
$env:npm_config_registry = "https://registry.npmjs.org/"
$env:PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = "1"

if ([string]::IsNullOrWhiteSpace($MavenSettingsPath) -xor [string]::IsNullOrWhiteSpace($MavenLocalRepository)) {
    throw "MavenSettingsPath and MavenLocalRepository must be supplied together"
}

$mavenPrefix = @()
if (-not [string]::IsNullOrWhiteSpace($MavenSettingsPath)) {
    if (-not (Test-Path -LiteralPath $MavenSettingsPath -PathType Leaf)) {
        throw "Maven settings file does not exist"
    }
    if (-not (Test-Path -LiteralPath $MavenLocalRepository -PathType Container)) {
        throw "Maven local repository does not exist"
    }
    $mavenPrefix = @("-s", $MavenSettingsPath, "-Dmaven.repo.local=$MavenLocalRepository")
}

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
    $args = $mavenPrefix + @(
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

function Invoke-ScriptWithMavenIsolation {
    param([string]$Label, [string]$ScriptPath, [string[]]$Arguments)

    $previousMavenArgs = $env:MAVEN_ARGS
    try {
        if ($mavenPrefix.Count -gt 0) {
            $env:MAVEN_ARGS = "-s `"$MavenSettingsPath`" -Dmaven.repo.local=`"$MavenLocalRepository`""
        }
        Invoke-Checked $Label "powershell.exe" (@("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $ScriptPath) + $Arguments)
    } finally {
        $env:MAVEN_ARGS = $previousMavenArgs
    }
}

function Invoke-DbFailClosedJudge {
    $dbScript = Join-Path $repoRoot "scripts/db.ps1"
    $cases = @(
        @{ Action = "Clean"; Marker = "HDM005_DB_ACTION_FORBIDDEN" },
        @{ Action = "Repair"; Marker = "HDM005_DB_ACTION_FORBIDDEN" },
        @{ Action = "Unknown"; Marker = "HDM005_DB_ACTION_FORBIDDEN" }
    )

    foreach ($case in $cases) {
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $output = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $dbScript -Action $case.Action 2>&1
            $code = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        $text = $output -join "`n"
        if ($code -eq 0) {
            throw "db.ps1 action $($case.Action) unexpectedly succeeded"
        }
        if (-not $text.Contains($case.Marker)) {
            throw "db.ps1 action $($case.Action) did not emit $($case.Marker)"
        }
        Write-Host "db.ps1 action=$($case.Action) exit=$code marker=$($case.Marker)" -ForegroundColor Green
    }
}

Push-Location $repoRoot
try {
    Invoke-DbFailClosedJudge
    if ($mavenPrefix.Count -gt 0) {
        Invoke-Checked "database-generate-check" "powershell.exe" @(
            "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", (Join-Path $repoRoot "scripts/db.ps1"),
            "-Action", "GenerateCheck",
            "-MavenSettingsPath", $MavenSettingsPath,
            "-MavenLocalRepository", $MavenLocalRepository
        )
    }
    Invoke-Checked "maven-clean-verify" (Join-Path $repoRoot "mvnw.cmd") ($mavenPrefix + @("clean", "verify"))

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
    Invoke-ScriptWithMavenIsolation "generate-all" (Join-Path $repoRoot "scripts/generate.ps1") @("-Target", "all")
    Invoke-ScriptWithMavenIsolation "generate-all-check" (Join-Path $repoRoot "scripts/generate.ps1") @("-Target", "all", "-Check")
    Invoke-Checked "npm-typecheck-workspaces" "npm.cmd" @("run", "typecheck", "--workspaces", "--if-present")
    Invoke-Checked "npm-lint-workspaces" "npm.cmd" @("run", "lint", "--workspaces", "--if-present")
    Invoke-Checked "npm-test-workspaces" "npm.cmd" @("run", "test", "--workspaces", "--if-present")
    Invoke-Checked "npm-build-workspaces" "npm.cmd" @("run", "build", "--workspaces", "--if-present")
    Invoke-ScriptWithMavenIsolation "compatibility" (Join-Path $repoRoot "scripts/contract-compatibility.ps1") @()
    Invoke-ScriptWithMavenIsolation "format-check" (Join-Path $repoRoot "scripts/format-check.ps1") @()
    Write-Host "`nHDM003_VERIFY_PASS" -ForegroundColor Green
    exit 0
} finally {
    Pop-Location
}
