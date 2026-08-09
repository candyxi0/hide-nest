param(
    [Parameter(Mandatory = $true)]
    [string]$Action,
    [string]$MavenSettingsPath,
    [string]$MavenLocalRepository
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$databaseModule = Join-Path $repoRoot "modules/database-adapter"
$generatedRoot = Join-Path $databaseModule "src/generated/java"
$image = "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c"
$containerPrefix = "hdm005-sliceb-"
$allowedActions = @("Validate", "Migrate", "Generate", "GenerateCheck", "Test")
$forbiddenActions = @("Clean", "Repair")

$normalizedAction = $allowedActions | Where-Object { $_.Equals($Action, [StringComparison]::OrdinalIgnoreCase) }
$forbiddenAction = $forbiddenActions | Where-Object { $_.Equals($Action, [StringComparison]::OrdinalIgnoreCase) }

if ($null -ne $forbiddenAction -or $null -eq $normalizedAction) {
    [Console]::Error.WriteLine("HDM005_DB_ACTION_FORBIDDEN action=$Action")
    exit 20
}

if ([string]::IsNullOrWhiteSpace($MavenSettingsPath) -xor [string]::IsNullOrWhiteSpace($MavenLocalRepository)) {
    [Console]::Error.WriteLine("HDM005_MAVEN_ISOLATION_INVALID")
    exit 22
}

$mavenPrefix = @()
if (-not [string]::IsNullOrWhiteSpace($MavenSettingsPath)) {
    if (-not (Test-Path -LiteralPath $MavenSettingsPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $MavenLocalRepository -PathType Container)) {
        [Console]::Error.WriteLine("HDM005_MAVEN_ISOLATION_INVALID")
        exit 22
    }
    $mavenPrefix = @("-s", $MavenSettingsPath, "-Dmaven.repo.local=$MavenLocalRepository")
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Executable,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & $Executable @Arguments
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($code -ne 0) {
        throw "$Label failed with exit code $code"
    }
}

function Invoke-Maven {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    Push-Location $repoRoot
    try {
        Invoke-Native "Maven" (Join-Path $repoRoot "mvnw.cmd") ($mavenPrefix + $Arguments)
    } finally {
        Pop-Location
    }
}

function Start-DisposableDatabase {
    $name = $containerPrefix + [guid]::NewGuid().ToString("N").Substring(0, 12)
    $password = [guid]::NewGuid().ToString("N") + [guid]::NewGuid().ToString("N")
    $arguments = @(
        "run", "-d", "--name", $name,
        "--label", "io.github.candyxi0.hidenest.slice=HDM-005-B",
        "--tmpfs", "/var/lib/postgresql:rw,noexec,nosuid,size=536870912",
        "-p", "127.0.0.1::5432",
        "-e", "POSTGRES_USER=hide_nest_migrator",
        "-e", "POSTGRES_PASSWORD=$password",
        "-e", "POSTGRES_DB=hide_nest",
        $image
    )

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $containerOutput = @(& docker @arguments 2>&1)
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    $containerId = $containerOutput | Select-Object -First 1
    if ($code -ne 0 -or [string]::IsNullOrWhiteSpace([string]$containerId)) {
        throw "Docker database start failed"
    }

    try {
        $ready = $false
        for ($attempt = 0; $attempt -lt 60; $attempt++) {
            $previousPreference = $ErrorActionPreference
            try {
                $ErrorActionPreference = "Continue"
                & docker exec $name pg_isready -U hide_nest_migrator -d hide_nest *> $null
                $readyCode = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $previousPreference
            }
            if ($readyCode -eq 0) {
                $ready = $true
                break
            }
            Start-Sleep -Milliseconds 500
        }
        if (-not $ready) {
            throw "PostgreSQL readiness timeout"
        }

        Invoke-Native "Database role bootstrap" "docker" @(
            "exec", "-e", "PGPASSWORD=$password", $name,
            "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", "hide_nest_migrator", "-d", "hide_nest",
            "-c", "CREATE ROLE hide_nest_api NOLOGIN; CREATE ROLE hide_nest_worker NOLOGIN;"
        )

        $portLine = (& docker port $name "5432/tcp").Trim()
        if ($LASTEXITCODE -ne 0 -or $portLine -notmatch ":(?<port>[0-9]+)$") {
            throw "PostgreSQL mapped port unavailable"
        }

        return [pscustomobject]@{
            Name = $name
            Id = ([string]$containerId).Trim()
            Url = "jdbc:postgresql://127.0.0.1:$($Matches.port)/hide_nest"
            User = "hide_nest_migrator"
            Password = $password
        }
    } catch {
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            & docker rm -f $name *> $null
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        throw
    }
}

function Stop-DisposableDatabase {
    param([Parameter(Mandatory = $true)][object]$Database)

    if (-not ([string]$Database.Name).StartsWith($containerPrefix, [StringComparison]::Ordinal)) {
        throw "Refusing to remove an unexpected container"
    }
    $currentId = (& docker inspect $Database.Name --format "{{.Id}}" 2>$null)
    if ($LASTEXITCODE -eq 0 -and ([string]$currentId).Trim() -ne $Database.Id) {
        throw "Disposable container identity changed"
    }

    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        & docker rm -f $Database.Name *> $null
    } finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Use-DatabaseEnvironment {
    param(
        [Parameter(Mandatory = $true)][object]$Database,
        [Parameter(Mandatory = $true)][scriptblock]$Operation
    )

    $oldUrl = $env:HDM005_DB_URL
    $oldUser = $env:HDM005_DB_USER
    $oldPassword = $env:HDM005_DB_PASSWORD
    try {
        $env:HDM005_DB_URL = $Database.Url
        $env:HDM005_DB_USER = $Database.User
        $env:HDM005_DB_PASSWORD = $Database.Password
        & $Operation
    } finally {
        $env:HDM005_DB_URL = $oldUrl
        $env:HDM005_DB_USER = $oldUser
        $env:HDM005_DB_PASSWORD = $oldPassword
    }
}

function Invoke-FlywayMigrate {
    Invoke-Maven @(
        "-pl", "modules/database-adapter", "-Pdatabase-tools",
        "org.flywaydb:flyway-maven-plugin:12.4.0:migrate"
    )
}

function Get-TreeManifest {
    param([Parameter(Mandatory = $true)][string]$Root)

    if (-not (Test-Path -LiteralPath $Root -PathType Container)) {
        return @()
    }
    return @(Get-ChildItem -LiteralPath $Root -Recurse -File | ForEach-Object {
        [pscustomobject]@{
            Path = $_.FullName.Substring($Root.Length).TrimStart("\").Replace("\", "/")
            Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash
        }
    } | Sort-Object Path)
}

function Assert-TreeEqual {
    param(
        [Parameter(Mandatory = $true)][string]$Left,
        [Parameter(Mandatory = $true)][string]$Right,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $leftManifest = @(Get-TreeManifest $Left)
    $rightManifest = @(Get-TreeManifest $Right)
    if ($leftManifest.Count -eq 0 -or $leftManifest.Count -ne $rightManifest.Count) {
        throw "$Label file count mismatch"
    }
    for ($index = 0; $index -lt $leftManifest.Count; $index++) {
        if ($leftManifest[$index].Path -ne $rightManifest[$index].Path -or
            $leftManifest[$index].Hash -ne $rightManifest[$index].Hash) {
            throw "$Label mismatch at $($leftManifest[$index].Path)"
        }
    }
}

function Invoke-JooqGeneration {
    param([Parameter(Mandatory = $true)][string]$OutputDirectory)

    $oldOutput = $env:HDM005_JOOQ_OUTPUT
    try {
        $env:HDM005_JOOQ_OUTPUT = $OutputDirectory
        Invoke-Maven @(
            "-pl", "modules/database-adapter", "-Pdatabase-tools",
            "org.jooq:jooq-codegen-maven:3.21.5:generate"
        )
    } finally {
        $env:HDM005_JOOQ_OUTPUT = $oldOutput
    }
}

function Invoke-GenerationJudge {
    param([bool]$UpdateTracked)

    $database = $null
    $temporaryRoot = Join-Path $env:TEMP ("hdm005-jooq-" + [guid]::NewGuid().ToString("N"))
    $generationA = Join-Path $temporaryRoot "a"
    $generationB = Join-Path $temporaryRoot "b"
    New-Item -ItemType Directory -Force -Path $generationA, $generationB | Out-Null
    try {
        $database = Start-DisposableDatabase
        Use-DatabaseEnvironment $database {
            Invoke-FlywayMigrate
            Invoke-JooqGeneration $generationA
            Invoke-JooqGeneration $generationB
        }
        Assert-TreeEqual $generationA $generationB "jOOQ A/B"

        if ($UpdateTracked) {
            $resolvedModule = [IO.Path]::GetFullPath($databaseModule).TrimEnd("\")
            $resolvedGenerated = [IO.Path]::GetFullPath($generatedRoot).TrimEnd("\")
            if (-not $resolvedGenerated.StartsWith($resolvedModule + "\", [StringComparison]::OrdinalIgnoreCase) -or
                [IO.Path]::GetFileName($resolvedGenerated) -ne "java") {
                throw "Generated output path escaped the database module"
            }
            if (Test-Path -LiteralPath $resolvedGenerated) {
                [IO.Directory]::Delete($resolvedGenerated, $true)
            }
            New-Item -ItemType Directory -Force -Path $resolvedGenerated | Out-Null
            Copy-Item -Path (Join-Path $generationA "*") -Destination $resolvedGenerated -Recurse -Force
        } else {
            Assert-TreeEqual $generationA $generatedRoot "jOOQ generated/tracked"
        }
    } finally {
        if ($null -ne $database) {
            Stop-DisposableDatabase $database
        }
        if (Test-Path -LiteralPath $temporaryRoot) {
            [IO.Directory]::Delete($temporaryRoot, $true)
        }
    }
}

$javaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot"
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:Path
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

try {
    switch ($normalizedAction) {
        "Validate" {
            $database = $null
            try {
                $database = Start-DisposableDatabase
                Use-DatabaseEnvironment $database {
                    Invoke-FlywayMigrate
                    Invoke-Maven @(
                        "-pl", "modules/database-adapter", "-Pdatabase-tools",
                        "org.flywaydb:flyway-maven-plugin:12.4.0:validate"
                    )
                }
            } finally {
                if ($null -ne $database) { Stop-DisposableDatabase $database }
            }
        }
        "Migrate" {
            $database = $null
            try {
                $database = Start-DisposableDatabase
                Use-DatabaseEnvironment $database { Invoke-FlywayMigrate }
            } finally {
                if ($null -ne $database) { Stop-DisposableDatabase $database }
            }
        }
        "Generate" { Invoke-GenerationJudge $true }
        "GenerateCheck" { Invoke-GenerationJudge $false }
        "Test" {
            Invoke-Maven @("-pl", "modules/database-adapter", "-am", "test")
        }
    }
    Write-Output "HDM005_DB_ACTION_PASS action=$normalizedAction"
    exit 0
} catch {
    $reason = ([string]$_.Exception.Message).Replace("`r", " ").Replace("`n", " ")
    [Console]::Error.WriteLine("HDM005_DB_ACTION_FAILED action=$normalizedAction type=$($_.Exception.GetType().Name) reason=$reason")
    exit 23
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:Path = $oldPath
}
