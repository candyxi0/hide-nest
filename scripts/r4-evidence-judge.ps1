param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
$candidateBase = Join-Path $env:TEMP ("hdm005-r5-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
$generatedRelPath = "modules/database-adapter/src/generated/java"
$results = @{}
$resultFile = Join-Path $RepoRoot "reports/HDM-005-SliceB-R4-JudgeEvidence.json"

# Compute producerScriptSha256 BEFORE any candidate work
$producerScriptSha256 = (Get-FileHash -Algorithm SHA256 -Path $PSCommandPath).Hash.ToLower()

function Invoke-CandidateJudge {
    param([string]$CandidateRoot, [string]$Label, [string]$CommandKind = "GenerateCheck")

    if ($CommandKind -eq "GenerateCheck") {
        # Use candidate's scripts/db.ps1
        $candidateScript = Join-Path $CandidateRoot 'scripts/db.ps1'
        $scriptArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $candidateScript, "-Action", "GenerateCheck")
        $relScript = "scripts/db.ps1"
        $cmdKindStr = "powershell -File scripts/db.ps1 -Action GenerateCheck"
    } else {
        # Boundary: use candidate's mvnw.cmd to run architecture boundary test
        $candidateScript = Join-Path $CandidateRoot 'mvnw.cmd'
        $scriptArgs = @("test", "-pl", "modules/architecture-tests", "-am", "-Dtest=DatabaseBoundaryTest", "-DfailIfNoTests=false")
        $relScript = "mvnw.cmd"
        $cmdKindStr = "mvnw.cmd test -pl modules/architecture-tests -am -Dtest=DatabaseBoundaryTest"
    }

    # Resolved-path-inside-candidate assertion
    $resolvedScript = [IO.Path]::GetFullPath($candidateScript)
    if (-not $resolvedScript.StartsWith([IO.Path]::GetFullPath($CandidateRoot), [StringComparison]::OrdinalIgnoreCase)) {
        throw "R4_SCRIPT_ESCAPE_CANDIDATE label=$Label script=$resolvedScript candidate=$CandidateRoot"
    }

    $logFile = Join-Path $env:TEMP "hdm005-r5-$Label.log"
    $logFile = [string]$logFile
    Write-Host "R4_JUDGE label=$Label script=$resolvedScript kind=$CommandKind"
    $previousPref = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        Push-Location $CandidateRoot
        try {
            if ($CommandKind -eq "GenerateCheck") {
                & powershell @scriptArgs *> $logFile
            } else {
                $oldJavaHome = $env:JAVA_HOME
                $oldPath = $env:Path
                try {
                    $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot'
                    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
                    & $candidateScript @scriptArgs *> $logFile
                } finally {
                    $env:JAVA_HOME = $oldJavaHome
                    $env:Path = $oldPath
                }
            }
            $exitCode = $LASTEXITCODE
        } finally {
            Pop-Location
        }
    } finally {
        $ErrorActionPreference = $previousPref
    }
    $logBytes = [IO.File]::ReadAllBytes($logFile)
    $sha256 = (Get-FileHash -Algorithm SHA256 -InputStream ([IO.MemoryStream]::new($logBytes))).Hash.ToLower()
    $results[$Label] = @{
        commandKind = $cmdKindStr
        exitCode     = $exitCode
        logSha256    = $sha256
        logBytes     = $logBytes.Length
        candidate    = $Label
        entryPoint   = $relScript
    }
    Write-Host "R4_RESULT $Label exit=$exitCode sha256=$sha256 bytes=$($logBytes.Length)"
    Remove-Item $logFile -Force -ErrorAction SilentlyContinue
}

function New-Candidate {
    param([string]$Suffix)
    $path = [string]("$candidateBase-$Suffix")
    Write-Host "Building candidate: $path"
    $null = & robocopy $RepoRoot $path /E /NFL /NDL /NJH /NJS /nc /ns /np /XD .git target node_modules 2>&1
    if ($LASTEXITCODE -ge 8) { throw "Robocopy failed $LASTEXITCODE for $Suffix" }
    return $path
}

# Find a generated file to mutate (from repo, not candidate)
$allFiles = @(Get-ChildItem -Path (Join-Path $RepoRoot $generatedRelPath) -Recurse -Filter "*.java" | Select-Object -First 1)
if ($allFiles.Count -eq 0) { throw "No generated files found" }
$mutateFileName = $allFiles[0].Name

try {
    # ---- Baseline ----
    $c0 = New-Candidate "baseline"
    Write-Host "Baseline script: $([IO.Path]::GetFullPath((Join-Path $c0 'scripts/db.ps1')))"
    Invoke-CandidateJudge $c0 "baseline"

    # ---- Delete ----
    $c1 = New-Candidate "delete"
    $delTarget = Join-Path $c1 $generatedRelPath
    $delFile = Get-ChildItem -Path $delTarget -Recurse -Filter $mutateFileName | Select-Object -First 1
    [IO.File]::Delete($delFile.FullName)
    Write-Host "Deleted: $($delFile.Name)"
    Invoke-CandidateJudge $c1 "delete"

    # ---- Modify ----
    $c2 = New-Candidate "modify"
    $modTarget = Join-Path $c2 $generatedRelPath
    $modFile = Get-ChildItem -Path $modTarget -Recurse -Filter $mutateFileName | Select-Object -First 1
    $origText = [IO.File]::ReadAllText($modFile.FullName, [Text.Encoding]::UTF8)
    $mutText = $origText + "`n// R4-MUTATION-" + [guid]::NewGuid().ToString("N")
    [IO.File]::WriteAllText($modFile.FullName, $mutText, [Text.Encoding]::UTF8)
    Write-Host "Modified: $($modFile.Name)"
    Invoke-CandidateJudge $c2 "modify"

    # ---- Boundary: extra file in formal Java module referencing generated types ----
    $c3 = New-Candidate "boundary"
    $boundaryFile = Join-Path $c3 "modules/memory/src/main/java/io/github/candyxi0/hidenest/memory/R4BoundaryLeak.java"
    $boundaryDir = Split-Path $boundaryFile -Parent
    if (-not (Test-Path $boundaryDir)) { New-Item -ItemType Directory -Force -Path $boundaryDir | Out-Null }
    @"
package io.github.candyxi0.hidenest.memory;
import io.github.candyxi0.hidenest.database.generated.memory.Tables;
public class R4BoundaryLeak {
    public void leak() { System.out.println(Tables.MEMORY_RECORD.getName()); }
}
"@ | Out-File -FilePath $boundaryFile -Encoding utf8
    Write-Host "Boundary leak: R4BoundaryLeak.java in modules/memory"
    Invoke-CandidateJudge $c3 "boundary" "BoundaryVerify"

    # ---- Validate gates ----
    $baselineOk  = $results["baseline"].exitCode -eq 0
    $deleteOk    = $results["delete"].exitCode -ne 0
    $modifyOk    = $results["modify"].exitCode -ne 0
    $boundaryOk  = $results["boundary"].exitCode -ne 0

    # ---- Dynamic database test count from Surefire XML ----
    $surefireDir = Join-Path $RepoRoot "modules/database-adapter/target/surefire-reports"
    $dbTotal  = 0
    $dbFailed = 0
    if (Test-Path $surefireDir) {
        Get-ChildItem -Path $surefireDir -Filter "TEST-*.xml" | ForEach-Object {
            $xml = [xml](Get-Content -Raw $_.FullName)
            $suite = $xml.testsuite
            if ($suite) {
                $dbTotal  += [int]$suite.tests
                $dbFailed += [int]$suite.failures + [int]$suite.errors
            }
        }
    }
    $dbPassed = $dbTotal - $dbFailed

    # ---- Build evidence ----
    $evidence = @{
        schemaVersion         = "hdm005.slice-b.r4-judge-evidence.v1"
        timestamp             = (Get-Date -Format "yyyy-MM-ddTHH:mm:sszzz")
        producerScriptSha256  = $producerScriptSha256
        gates                 = @{
            baseline = $results["baseline"]
            delete   = $results["delete"]
            modify   = $results["modify"]
            boundary = $results["boundary"]
        }
        databaseTests         = @{
            total  = $dbTotal
            passed = $dbPassed
            failed = $dbFailed
        }
        allPass = ($baselineOk -and $deleteOk -and $modifyOk -and $boundaryOk)
    }

    # ---- Write evidence JSON ----
    $evidence | ConvertTo-Json -Depth 4 | Out-File -FilePath $resultFile -Encoding utf8

    # ---- Self-check: ConvertFrom-Json and validate all *Sha256 are 64 lowercase hex ----
    $reRead = Get-Content -Raw -Path $resultFile | ConvertFrom-Json
    $sha256Pattern = '^[0-9a-f]{64}$'
    function Find-Sha256Fields {
        param($Obj, [string]$Prefix)
        $found = @()
        foreach ($prop in $Obj.PSObject.Properties) {
            $fullName = if ($Prefix) { "$Prefix.$($prop.Name)" } else { $prop.Name }
            if ($prop.Name -like '*Sha256') {
                $found += @{ Path = $fullName; Value = $prop.Value }
            }
            if ($prop.Value -is [PSCustomObject]) {
                $found += Find-Sha256Fields $prop.Value $fullName
            }
        }
        return $found
    }
    $sha256Fields = Find-Sha256Fields $reRead ""
    foreach ($f in $sha256Fields) {
        if ($f.Value -notmatch $sha256Pattern) {
            throw "R4_SHA256_FORMAT_VIOLATION field=$($f.Path) value=$($f.Value)"
        }
    }
    Write-Host "R4_SELFCHECK ConvertFrom-Json OK, Sha256 fields verified: $($sha256Fields.Count)"

    # ---- Secret scan: evidence must not contain passwords, JDBC URLs, SQL, canary text, or absolute temp paths ----
    $evidenceText = Get-Content -Raw -Path $resultFile
    $secrets = @('password', 'jdbc:postgresql', 'SELECT ', 'INSERT ', 'UPDATE ', 'DELETE ', 'canary', '\\Temp\\', '\\TEMP\\')
    foreach ($secret in $secrets) {
        if ($evidenceText -match $secret) {
            throw "R4_SECRET_LEAK detected pattern: $secret"
        }
    }
    Write-Host "R4_SECRET_SCAN clean"

    # ---- Delete temporary log files and candidates ----
    Remove-Item -Recurse -Force "$candidateBase*" -ErrorAction SilentlyContinue

    Write-Host "=== R4 Judge Evidence Results ==="
    Write-Output "baseline:  exit=$($results['baseline'].exitCode) sha256=$($results['baseline'].logSha256)"
    Write-Output "delete:    exit=$($results['delete'].exitCode) sha256=$($results['delete'].logSha256)"
    Write-Output "modify:    exit=$($results['modify'].exitCode) sha256=$($results['modify'].logSha256)"
    Write-Output "boundary:  exit=$($results['boundary'].exitCode) sha256=$($results['boundary'].logSha256)"
    Write-Output "databaseTests: total=$dbTotal passed=$dbPassed failed=$dbFailed"
    Write-Host "Evidence: $resultFile"

    # ---- baseline/delete/modify/boundary exits: 0/nonzero/nonzero/nonzero; else runner exits nonzero ----
    if ($baselineOk -and $deleteOk -and $modifyOk -and $boundaryOk) {
        Write-Host "R4_EVIDENCE_JUDGE_PASS 0/NONZERO/NONZERO/NONZERO"
        exit 0
    } else {
        Write-Host "R4_EVIDENCE_JUDGE_FAIL"
        exit 1
    }
} finally {
    Remove-Item -Recurse -Force "$candidateBase*" -ErrorAction SilentlyContinue
}
