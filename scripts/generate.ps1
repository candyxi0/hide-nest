# HDM-003-R1 deterministic contract generation.
# Java and both TypeScript targets are generated from authoritative contract inputs.

param(
    [ValidateSet("all", "openapi", "events")]
    [string]$Target = "all",
    [switch]$Check
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$javaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot"
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

function Assert-LocalPrerequisites {
    $required = @(
        (Join-Path $repoRoot "mvnw.cmd"),
        (Join-Path $repoRoot ".mvn\wrapper\maven-wrapper.properties"),
        (Join-Path $repoRoot "node_modules\.bin\tsc.cmd"),
        (Join-Path $repoRoot "node_modules\.bin\prettier.cmd")
    )
    foreach ($path in $required) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Local dependency is missing; refusing implicit download: $path"
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $javaHome "bin\java.exe") -PathType Leaf)) {
        throw "Temurin 25 is missing: $javaHome"
    }
}

function New-TempRoot {
    $root = Join-Path $env:TEMP ("hdm003-r1-generate-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    return $root
}

function Invoke-Generation {
    param(
        [Parameter(Mandatory = $true)][string]$JavaOutput,
        [Parameter(Mandatory = $true)][string]$OpenApiTsOutput,
        [Parameter(Mandatory = $true)][string]$EventsTsOutput
    )
    $args = @(
        "-o",
        "-pl", "modules/contracts",
        "-Dhdm.java.output=$JavaOutput",
        "-Dhdm.openapi.ts.output=$OpenApiTsOutput",
        "-Dhdm.events.ts.output=$EventsTsOutput",
        "generate-sources"
    )
    Push-Location $repoRoot
    try {
        & (Join-Path $repoRoot "mvnw.cmd") @args
        if ($LASTEXITCODE -ne 0) {
            throw "Maven generator failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Get-TreeManifest {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Extension = ".ts"
    )
    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        throw "Generated directory is missing: $Root"
    }
    $manifest = @{}
    $files = if ([string]::IsNullOrEmpty($Extension)) {
        Get-ChildItem -LiteralPath $Root -Recurse -File
    } else {
        Get-ChildItem -LiteralPath $Root -Recurse -File -Filter "*$Extension"
    }
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($Root.Length).TrimStart("\", "/")
        $manifest[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    return $manifest
}

function Compare-GeneratedTrees {
    param(
        [Parameter(Mandatory = $true)][string]$Left,
        [Parameter(Mandatory = $true)][string]$Right,
        [string]$Extension = ".ts"
    )
    $leftManifest = Get-TreeManifest -Root $Left -Extension $Extension
    $rightManifest = Get-TreeManifest -Root $Right -Extension $Extension
    $allPaths = @($leftManifest.Keys) + @($rightManifest.Keys) | Sort-Object -Unique
    $same = $true
    foreach ($relative in $allPaths) {
        if (-not $leftManifest.ContainsKey($relative)) {
            Write-Host "DIFF: missing from left: $relative" -ForegroundColor Red
            $same = $false
        } elseif (-not $rightManifest.ContainsKey($relative)) {
            Write-Host "DIFF: extra in left: $relative" -ForegroundColor Red
            $same = $false
        } elseif ($leftManifest[$relative] -ne $rightManifest[$relative]) {
            Write-Host "DIFF: content differs: $relative" -ForegroundColor Red
            $same = $false
        }
    }
    return $same
}

function Sync-GeneratedTrees {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    $staging = Join-Path $env:TEMP ("hdm003-r1-sync-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    try {
        foreach ($file in Get-ChildItem -LiteralPath $Source -Recurse -File -Filter "*.ts") {
            $relative = $file.FullName.Substring($Source.Length).TrimStart("\", "/")
            $target = Join-Path $staging $relative
            New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $target -Force
        }
        if (Test-Path -LiteralPath $Destination) {
            Remove-Item -LiteralPath $Destination -Recurse -Force
        }
        New-Item -ItemType Directory -Path $Destination -Force | Out-Null
        foreach ($file in Get-ChildItem -LiteralPath $staging -Recurse -File) {
            $relative = $file.FullName.Substring($staging.Length).TrimStart("\", "/")
            $target = Join-Path $Destination $relative
            New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $target -Force
        }
    } finally {
        if (Test-Path -LiteralPath $staging) {
            Remove-Item -LiteralPath $staging -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Assert-ComparatorMutationCases {
    param([Parameter(Mandatory = $true)][string]$Source)
    $root = New-TempRoot
    try {
        $contentMutation = Join-Path $root "content"
        $deleteMutation = Join-Path $root "delete"
        $extraMutation = Join-Path $root "extra"
        foreach ($destination in @($contentMutation, $deleteMutation, $extraMutation)) {
            Copy-Item -LiteralPath $Source -Destination $destination -Recurse -Force
        }
        $firstFile = Get-ChildItem -LiteralPath $contentMutation -Recurse -File -Filter "*.ts" | Select-Object -First 1
        if ($null -eq $firstFile) { throw "Mutation fixture source has no TypeScript files" }
        Add-Content -LiteralPath $firstFile.FullName -Value "`n// mutation"
        if (Compare-GeneratedTrees -Left $Source -Right $contentMutation) { throw "Comparator accepted content mutation" }
        Remove-Item -LiteralPath (Get-ChildItem -LiteralPath $deleteMutation -Recurse -File -Filter "*.ts" | Select-Object -First 1).FullName -Force
        if (Compare-GeneratedTrees -Left $Source -Right $deleteMutation) { throw "Comparator accepted deletion mutation" }
        Set-Content -LiteralPath (Join-Path $extraMutation "old-generated-file.ts") -Value "export const stale = true;" -Encoding UTF8
        if (Compare-GeneratedTrees -Left $Source -Right $extraMutation) { throw "Comparator accepted extra-file mutation" }
    } finally {
        if (Test-Path -LiteralPath $root) {
            Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

Assert-LocalPrerequisites
$tempRoots = @()
try {
    if (-not $Check) {
        $root = New-TempRoot
        $tempRoots += $root
        $javaOutput = Join-Path $repoRoot "modules\contracts\target\generated-sources\api"
        $openApiOutput = Join-Path $root "openapi-ts"
        $eventOutput = Join-Path $root "event-ts"
        if ($Target -eq "all" -or $Target -eq "openapi") {
            if (Test-Path -LiteralPath (Join-Path $repoRoot "modules\contracts\target\generated-sources")) {
                Remove-Item -LiteralPath (Join-Path $repoRoot "modules\contracts\target\generated-sources") -Recurse -Force
            }
        }
        Invoke-Generation -JavaOutput $javaOutput -OpenApiTsOutput $openApiOutput -EventsTsOutput $eventOutput
        if ($Target -eq "all" -or $Target -eq "openapi") {
            Sync-GeneratedTrees -Source $openApiOutput -Destination (Join-Path $repoRoot "packages\api-client-ts\src\generated")
        }
        if ($Target -eq "all" -or $Target -eq "events") {
            Sync-GeneratedTrees -Source $eventOutput -Destination (Join-Path $repoRoot "apps\codex-adapter\src\generated\events")
        }
        Write-Host "GENERATION_PASS" -ForegroundColor Green
        exit 0
    }

    $first = New-TempRoot
    $second = New-TempRoot
    $tempRoots += $first
    $tempRoots += $second
    Invoke-Generation -JavaOutput (Join-Path $first "java") -OpenApiTsOutput (Join-Path $first "openapi-ts") -EventsTsOutput (Join-Path $first "event-ts")
    Invoke-Generation -JavaOutput (Join-Path $second "java") -OpenApiTsOutput (Join-Path $second "openapi-ts") -EventsTsOutput (Join-Path $second "event-ts")

    $checks = @()
    if ($Target -eq "all" -or $Target -eq "openapi") {
        $checks += (Compare-GeneratedTrees -Left (Join-Path $first "java") -Right (Join-Path $second "java") -Extension "")
        $checks += (Compare-GeneratedTrees -Left (Join-Path $first "openapi-ts") -Right (Join-Path $second "openapi-ts"))
        $checks += (Compare-GeneratedTrees -Left (Join-Path $first "openapi-ts") -Right (Join-Path $repoRoot "packages\api-client-ts\src\generated"))
        Assert-ComparatorMutationCases -Source (Join-Path $first "openapi-ts")
    }
    if ($Target -eq "all" -or $Target -eq "events") {
        $checks += (Compare-GeneratedTrees -Left (Join-Path $first "event-ts") -Right (Join-Path $second "event-ts"))
        $checks += (Compare-GeneratedTrees -Left (Join-Path $first "event-ts") -Right (Join-Path $repoRoot "apps\codex-adapter\src\generated\events"))
        Assert-ComparatorMutationCases -Source (Join-Path $first "event-ts")
    }
    if ($checks -contains $false) { throw "Generated tree comparison failed" }
    Write-Host "GENERATION_CHECK_PASS" -ForegroundColor Green
    exit 0
} finally {
    foreach ($root in $tempRoots) {
        if (Test-Path -LiteralPath $root) {
            Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}
