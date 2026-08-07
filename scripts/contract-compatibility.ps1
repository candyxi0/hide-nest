# HDM-003-R2 fail-closed semantic compatibility gate.

param(
    [string]$BaselinePath = "",
    [string]$CurrentSpec = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$javaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot"
$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

if ([string]::IsNullOrWhiteSpace($BaselinePath)) {
    $BaselinePath = Join-Path $repoRoot "contracts\compatibility-fixtures\baseline.yaml"
}
if ([string]::IsNullOrWhiteSpace($CurrentSpec)) {
    $CurrentSpec = Join-Path $repoRoot "contracts\openapi\hide-nest-api.yaml"
}

function New-DiffPom {
    param([string]$OldPath, [string]$NewPath, [string]$JsonName)
    $oldUri = (Get-Item -LiteralPath $OldPath).FullName -replace '\\', '/'
    $newUri = (Get-Item -LiteralPath $NewPath).FullName -replace '\\', '/'
    return @"
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.candyxi0.hidenest</groupId>
  <artifactId>hdm003-compatibility-temp</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <packaging>pom</packaging>
  <build>
    <plugins>
      <plugin>
        <groupId>org.openapitools.openapidiff</groupId>
        <artifactId>openapi-diff-maven</artifactId>
        <version>2.1.7</version>
        <configuration>
          <oldSpec>$oldUri</oldSpec>
          <newSpec>$newUri</newSpec>
          <failOnIncompatible>true</failOnIncompatible>
          <jsonOutputFileName>$JsonName</jsonOutputFileName>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
"@
}

function New-ToolErrorVerdict {
    param([string]$Label, [string]$Reason, [Nullable[int]]$ExitCode = $null, [string]$Output = "")
    return [pscustomobject]@{
        Label = $Label
        Verdict = "TOOL_ERROR"
        ExitCode = $ExitCode
        Incompatible = $null
        Reason = $Reason
        Output = $Output
    }
}

function Invoke-SemanticDiff {
    param([string]$OldPath, [string]$NewPath, [string]$Label)

    if (-not (Test-Path -LiteralPath $OldPath -PathType Leaf)) {
        return New-ToolErrorVerdict $Label "old specification is missing: $OldPath"
    }
    if (-not (Test-Path -LiteralPath $NewPath -PathType Leaf)) {
        return New-ToolErrorVerdict $Label "new specification is missing: $NewPath"
    }

    $tempDir = Join-Path ([IO.Path]::GetTempPath()) ("hdm003-r2-compat-" + [guid]::NewGuid().ToString("N"))
    try {
        New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
        $jsonName = "diff.json"
        $pomPath = Join-Path $tempDir "pom.xml"
        Set-Content -LiteralPath $pomPath -Value (New-DiffPom $OldPath $NewPath $jsonName) -Encoding UTF8
        Push-Location $tempDir
        try {
            $output = (& "$repoRoot\mvnw.cmd" -o -f $pomPath org.openapitools.openapidiff:openapi-diff-maven:2.1.7:diff 2>&1 | Out-String)
            $exitCode = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        $jsonPath = Join-Path $tempDir $jsonName
        if (-not (Test-Path -LiteralPath $jsonPath -PathType Leaf)) {
            return New-ToolErrorVerdict $Label "required JSON report was not produced" $exitCode $output
        }
        try {
            $report = Get-Content -Raw -Encoding UTF8 $jsonPath | ConvertFrom-Json
        } catch {
            return New-ToolErrorVerdict $Label "JSON report is unreadable: $($_.Exception.Message)" $exitCode $output
        }
        if ($null -eq $report) {
            return New-ToolErrorVerdict $Label "JSON report is empty" $exitCode $output
        }

        # openapi-diff-maven 2.1.7 writes this root Boolean. Any other shape is TOOL_ERROR.
        $property = $report.PSObject.Properties["incompatible"]
        if ($null -eq $property -or $property.Value -isnot [bool]) {
            return New-ToolErrorVerdict $Label "JSON report lacks Boolean incompatible verdict" $exitCode $output
        }
        $incompatible = [bool]$property.Value
        if ($exitCode -eq 0 -and -not $incompatible) {
            $verdict = "COMPATIBLE"
        } elseif ($exitCode -ne 0 -and $incompatible) {
            $verdict = "INCOMPATIBLE"
        } else {
            return New-ToolErrorVerdict $Label "Maven exit/report verdict disagree (exit=$exitCode incompatible=$incompatible)" $exitCode $output
        }
        return [pscustomobject]@{
            Label = $Label
            Verdict = $verdict
            ExitCode = $exitCode
            Incompatible = $incompatible
            Reason = "openapi-diff-maven:2.1.7 structured report"
            Output = $output
        }
    } catch {
        return New-ToolErrorVerdict $Label "tool startup or I/O error: $($_.Exception.Message)" $null ""
    } finally {
        if (Test-Path -LiteralPath $tempDir) {
            Remove-Item -LiteralPath $tempDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Assert-Verdict {
    param([pscustomobject]$Result, [ValidateSet("COMPATIBLE", "INCOMPATIBLE", "TOOL_ERROR")][string]$Expected)
    if ($Result.Verdict -ne $Expected) {
        throw "$($Result.Label) expected $Expected but got $($Result.Verdict); exit=$($Result.ExitCode); reason=$($Result.Reason); output=$($Result.Output)"
    }
    Write-Host "COMPATIBILITY_VERDICT label=$($Result.Label) verdict=$($Result.Verdict) exit=$($Result.ExitCode) incompatible=$($Result.Incompatible)" -ForegroundColor Green
}

Write-Host "=== HDM-003 R2 semantic compatibility ===" -ForegroundColor Cyan
$fixtureDir = Join-Path $repoRoot "contracts\compatibility-fixtures"
$baselineFixture = Join-Path $fixtureDir "baseline-fixture.yaml"
$optionalFixture = Join-Path $fixtureDir "add-optional-field.yaml"
$removedRequiredFixture = Join-Path $fixtureDir "remove-required-field.yaml"
$removedOperationFixture = Join-Path $fixtureDir "remove-operation.yaml"
foreach ($fixture in @($baselineFixture, $optionalFixture, $removedRequiredFixture, $removedOperationFixture)) {
    if (-not (Test-Path -LiteralPath $fixture -PathType Leaf)) { throw "compatibility fixture is missing: $fixture" }
}

$syntheticCompatible = Invoke-SemanticDiff $baselineFixture $optionalFixture "optional-field-and-3.1-union"
Assert-Verdict $syntheticCompatible "COMPATIBLE"
$syntheticRequiredBreak = Invoke-SemanticDiff $baselineFixture $removedRequiredFixture "removed-response-required-field"
Assert-Verdict $syntheticRequiredBreak "INCOMPATIBLE"
$syntheticOperationBreak = Invoke-SemanticDiff $baselineFixture $removedOperationFixture "removed-operation"
Assert-Verdict $syntheticOperationBreak "INCOMPATIBLE"

$toolErrorRoot = Join-Path ([IO.Path]::GetTempPath()) ("hdm003-r2-compat-tool-error-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $toolErrorRoot -Force | Out-Null
    $unparseableSpec = Join-Path $toolErrorRoot "unparseable.yaml"
    [IO.File]::WriteAllText($unparseableSpec, "openapi: [", [Text.UTF8Encoding]::new($false))
    $toolError = Invoke-SemanticDiff $baselineFixture $unparseableSpec "unparseable-spec-tool-error"
    Assert-Verdict $toolError "TOOL_ERROR"
} finally {
    if (Test-Path -LiteralPath $toolErrorRoot) {
        Remove-Item -LiteralPath $toolErrorRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Test-Path -LiteralPath $BaselinePath -PathType Leaf)) {
    Write-Host "INITIAL_CONTRACT_BASELINE baseline=$BaselinePath" -ForegroundColor Yellow
    exit 0
}

$baselineResult = Invoke-SemanticDiff $BaselinePath $CurrentSpec "baseline-vs-current"
Assert-Verdict $baselineResult "COMPATIBLE"
Write-Host "COMPATIBILITY_PASS compatible=$($syntheticCompatible.ExitCode)/$($baselineResult.ExitCode) incompatible=$($syntheticRequiredBreak.ExitCode)/$($syntheticOperationBreak.ExitCode) tool-error=$($toolError.Verdict)" -ForegroundColor Green
exit 0
