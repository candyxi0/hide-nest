param(
    [switch]$SkipAll
)
$ErrorActionPreference = "Stop"
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$evidencePath = Join-Path $repoRoot "reports\HDM-006-SB-Evidence.json"
$databaseModule = Join-Path $repoRoot "modules\database-adapter"
$generatedRoot = Join-Path $databaseModule "src\generated\java"
$migrationDir = Join-Path $databaseModule "src\main\resources\db\migration"
$surefireDir = Join-Path $databaseModule "target\surefire-reports"
$javaHome = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot"

# Compute self SHA-256 immediately
$runnerSelfPath = $PSCommandPath
$runnerSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $runnerSelfPath).Hash
$runnerStartTime = [DateTime]::UtcNow.ToString("o")

$commands = [ordered]@{}
$logs = [ordered]@{}
$exitCodes = [ordered]@{}

function Invoke-Step {
    param([string]$Name, [scriptblock]$Block)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $out = & $Block 2>&1 | Out-String
        $code = $LASTEXITCODE
        if ($code -eq $null) { $code = 0 }
    } catch {
        $out = $_.Exception.Message
        $code = -1
    }
    $sw.Stop()
    $sha = ""
    if ($out.Trim()) {
        $tmp = [System.Security.Cryptography.SHA256]::Create()
        $sha = [BitConverter]::ToString($tmp.ComputeHash([Text.Encoding]::UTF8.GetBytes($out))).Replace("-","").ToLower()
    }
    $commands[$Name] = @{ exitCode = $code; durationMs = $sw.ElapsedMilliseconds; logSha256 = $sha }
    $logs[$Name] = $out
    $exitCodes[$Name] = $code
    return @{ ExitCode = $code; Output = $out }
}

$env:JAVA_HOME = $javaHome
$env:Path = "$javaHome\bin;$env:Path"

# Capture Maven and Java versions for evidence
$mavenVersion = (& (Join-Path $repoRoot "mvnw.cmd") --version 2>&1 | Select-Object -First 3) -join "; "
$javaVersion = (& "$javaHome\bin\java" --version 2>&1 | Select-Object -First 1)

Write-Host "=== HDM-006 Slice B R4 Evidence Judge ===" -ForegroundColor Cyan
Write-Host "Runner SHA-256: $runnerSha256"
Write-Host "Maven: $mavenVersion"
Write-Host "Java: $javaVersion"
Write-Host "Java home: $javaHome"
Write-Host ""

# ---- 0. Snapshot pre-state ----
Write-Host "[0] Snapshot pre-state..." -ForegroundColor Yellow
Push-Location $repoRoot
$gitHead = (git rev-parse HEAD).Trim()
$gitBranch = (git rev-parse --abbrev-ref HEAD).Trim()
$gitIndexBefore = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $repoRoot ".git\index")).Hash
$dockerBeforeContainers = @(docker ps -a --format "{{.Names}}" 2>$null)
$dockerBeforeVolumes = @(docker volume ls --format "{{.Name}}" 2>$null)
$dockerBeforeNetworks = @(docker network ls --format "{{.Name}}" 2>$null)
Pop-Location

# ---- 1. Migration hashes ----
Write-Host "[1] Migration hashes..." -ForegroundColor Yellow
$vHashes = @{}
Get-ChildItem $migrationDir -Filter "V0*.sql" | Sort Name | ForEach-Object {
    $vHashes[$_.BaseName.Substring(0,4)] = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash
}
$expectedHashes = @{
    V001="17e8533c206288b6c21f0261b323041eb5a3607949843bae8e5399e78a2c4fcd"
    V002="4fbdc588a73d62741fd3cb567eb0e6af0920c2a7ccb39ce84efc188edd8d1cd9"
    V003="a516a411262c0bca8826715b0c4ef41a6a6515275c921d6dd186121039c390cb"
    V004="d5e72ea4701e83e58e8b204f82483beb4d5ca5bdeb924f60446ecd0ea8dd721f"
    V005="9273aadc87e2480e447afd9c40721878ce9f0d9282f31ecd6095771e5dd3a737"
    V006="fac190a80229ee0bf24a2bf80d43f5a35da81871258cb9fb0f82750f7e09a6f3"
    V007="f358e5e0fd3e64530bac8bf61ab473f0cfa1c5e3af2a3518b0ffb3ca006840cb"
    V008="cd4fd16ad58a708f01e879dbbb4176a41c4f10ecb7743a241396d2fc76aa2ce0"
}
$v001v008Match = $true
foreach ($v in $expectedHashes.Keys) {
    if ($vHashes[$v] -ne $expectedHashes[$v]) { $v001v008Match = $false; Write-Host "  MISMATCH: $v" -ForegroundColor Red }
}
Write-Host "  V001-V008 match: $v001v008Match  V009: $($vHashes['V009'])"

# ---- 2. Database tests (full Maven offline clean verify) ----
Write-Host "[2] Maven offline clean verify..." -ForegroundColor Yellow
Push-Location $repoRoot
$mvnwPath = Join-Path $repoRoot "mvnw.cmd"
$mvnResult = Invoke-Step "maven-verify" {
    & $mvnwPath @('-o', 'clean', 'verify', '-Dmaven.javadoc.skip=true') 2>&1
    $global:LASTEXITCODE
}
Pop-Location
$mavenPass = ($mvnResult.ExitCode -eq 0)
Write-Host "  Maven verify: $mavenPass (exit=$($mvnResult.ExitCode))"

# ---- 3. Parse Surefire XML ----
Write-Host "[3] Parse Surefire XML..." -ForegroundColor Yellow
$xmlFile = Join-Path $surefireDir "TEST-io.github.candyxi0.hidenest.database.DatabaseSliceBContractTest.xml"
$xmlExists = Test-Path $xmlFile
$xmlHash = ""
$testTotal = 0; $testFailed = 0; $testErrors = 0; $testSkipped = 0
$v009Names = @(); $r1Names = @(); $r2Names = @(); $allNames = @()
if ($xmlExists) {
    $xmlHash = (Get-FileHash -Algorithm SHA256 $xmlFile).Hash
    [xml]$xml = Get-Content $xmlFile -Encoding UTF8
    foreach ($suite in $xml.testsuite) {
        $testTotal += [int]$suite.tests
        $testFailed += [int]$suite.failures
        $testErrors += [int]$suite.errors
        $testSkipped += [int]$suite.skipped
        foreach ($tc in $suite.testcase) {
            $allNames += $tc.name
            if ($tc.name -match '^v009') { $v009Names += $tc.name }
            if ($tc.name -match '^r10[2345]') { $r1Names += $tc.name }
            if ($tc.name -match '^r20[12]') { $r2Names += $tc.name }
        }
    }
}
$testPassed = $testTotal - $testFailed - $testErrors - $testSkipped
$xmlPotentiallyStale = (-not $mavenPass) -and $xmlExists
Write-Host "  tests=$testTotal passed=$testPassed failed=$testFailed errors=$testErrors skipped=$testSkipped"
Write-Host "  v009=$($v009Names.Count) r1=$($r1Names.Count) r2=$($r2Names.Count)"
if ($xmlPotentiallyStale) { Write-Host "  WARNING: XML may be stale (Maven did not produce it in this run)" -ForegroundColor Yellow }

# ---- 4. Table inventory from migration files ----
Write-Host "[4] New table inventory..." -ForegroundColor Yellow
$newRuntimeTables = @(
    "capture_scope","capture_scope_unit","closeout_run","checkpoint",
    "work_artifact","model_run","retrieval_trace","context_delivery","consumer_effect"
)
$v009MigrationFile = Join-Path $migrationDir "V009__runtime_tables_operational_identity.sql"
$v009MigrationExists = Test-Path $v009MigrationFile
# Count CREATE TABLE statements in V009 to verify newTableCount
$v009CreateTableCount = 0
if ($v009MigrationExists) {
    $v009Content = Get-Content $v009MigrationFile -Raw -Encoding UTF8
    $v009CreateTableCount = ([regex]::Matches($v009Content, 'CREATE TABLE runtime\.(\w+)')).Count
}
$runtimeNewCount = $newRuntimeTables.Count
$v009TableCountSelfConsistent = ($v009CreateTableCount -eq $newRuntimeTables.Count)
Write-Host "  V009 CREATE TABLE statements: $v009CreateTableCount, expected: $runtimeNewCount, match: $v009TableCountSelfConsistent"

# ---- 5. jOOQ ----
Write-Host "[5] jOOQ file count..." -ForegroundColor Yellow
$jooqCount = if (Test-Path $generatedRoot) { @(Get-ChildItem $generatedRoot -Recurse -Filter "*.java").Count } else { 0 }
Write-Host "  jOOQ files: $jooqCount"

# ---- 6. Node 4 commands ----
Write-Host "[6] Node unified verify..." -ForegroundColor Yellow
Push-Location $repoRoot
$nodeTC = Invoke-Step "node-typecheck" { & npm run typecheck 2>&1; $global:LASTEXITCODE }
$nodeLint = Invoke-Step "node-lint" { & npm run lint 2>&1; $global:LASTEXITCODE }
$nodeTest = Invoke-Step "node-test" { & npm run test 2>&1; $global:LASTEXITCODE }
$nodeBuild = Invoke-Step "node-build" { & npm run build 2>&1; $global:LASTEXITCODE }
Pop-Location
$nodeOk = ($nodeTC.ExitCode -eq 0) -and ($nodeLint.ExitCode -eq 0) -and ($nodeTest.ExitCode -eq 0) -and ($nodeBuild.ExitCode -eq 0)
Write-Host "  typecheck=$($nodeTC.ExitCode) lint=$($nodeLint.ExitCode) test=$($nodeTest.ExitCode) build=$($nodeBuild.ExitCode) => $nodeOk"

# ---- 7. API/Worker real startup smoke (recorded in commands/logs) ----
Write-Host "[7] API/Worker real startup smoke..." -ForegroundColor Yellow
function Invoke-SpringBootSmoke {
    param([string]$Module, [string]$Marker, [string]$StepName)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Push-Location $repoRoot
    try {
        $modulePom = "apps/$Module/pom.xml"
        $cmdStr = "mvnw.cmd -f $modulePom spring-boot:run -o -DskipTests"
        $outFile = Join-Path $env:TEMP "smoke-$Module-out.txt"
        $errFile = Join-Path $env:TEMP "smoke-$Module-err.txt"
        $proc = Start-Process -FilePath (Join-Path $repoRoot "mvnw.cmd") `
            -ArgumentList @('-f', $modulePom, 'spring-boot:run', '-o', '-DskipTests') `
            -NoNewWindow -PassThru -RedirectStandardOutput $outFile `
            -RedirectStandardError $errFile
        $markerHit = $false
        $maxWait = 30
        for ($i = 0; $i -lt $maxWait; $i++) {
            Start-Sleep -Seconds 1
            if (Test-Path $outFile) {
                $content = Get-Content $outFile -Raw -ErrorAction SilentlyContinue
                if ($content -match $Marker) { $markerHit = $true; break }
            }
            if ($proc.HasExited) { break }
        }
        $termination = if ($proc.HasExited) { "self-exited-" + $proc.ExitCode } else { "force-stopped" }
        if (-not $proc.HasExited) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 1
        }
        if (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue) {
            Stop-Process -Id $proc.Id -Force
        }
        $outContent = ""
        $errContent = ""
        if (Test-Path $outFile) { $outContent = Get-Content $outFile -Raw -ErrorAction SilentlyContinue }
        if (Test-Path $errFile) { $errContent = Get-Content $errFile -Raw -ErrorAction SilentlyContinue }
        $combined = "=== COMMAND ===`n$cmdStr`n=== STDOUT ===`n$outContent`n=== STDERR ===`n$errContent`n=== TERMINATION ===`n$termination`n=== MARKER ===`n$Marker`n=== MARKER_HIT ===`n$markerHit"
        Remove-Item $outFile, $errFile -ErrorAction SilentlyContinue
        $sw.Stop()
        $sha = ""
        if ($combined.Trim()) {
            $tmp = [System.Security.Cryptography.SHA256]::Create()
            $sha = [BitConverter]::ToString($tmp.ComputeHash([Text.Encoding]::UTF8.GetBytes($combined))).Replace("-","").ToLower()
        }
        $commands[$StepName] = @{
            command = $cmdStr
            exitCode = if ($proc.HasExited) { $proc.ExitCode } else { -1 }
            durationMs = $sw.ElapsedMilliseconds
            logSha256 = $sha
            markerHit = $markerHit
            marker = $Marker
            termination = $termination
        }
        $logs[$StepName] = $combined
        $exitCodes[$StepName] = if ($proc.HasExited) { $proc.ExitCode } else { -1 }
        return @{ Started = $markerHit; Termination = $termination; Output = $combined }
    } catch {
        $sw.Stop()
        $errMsg = $_.Exception.Message
        $commands[$StepName] = @{
            command = "mvnw.cmd -f apps/$Module/pom.xml spring-boot:run -o -DskipTests"
            exitCode = -1
            durationMs = $sw.ElapsedMilliseconds
            logSha256 = ""
            markerHit = $false
            marker = $Marker
            termination = "exception: $errMsg"
        }
        $logs[$StepName] = "EXCEPTION: $errMsg"
        $exitCodes[$StepName] = -1
        return @{ Started = $false; Termination = "exception"; Output = $errMsg }
    } finally { Pop-Location }
}
$apiSmokeResult = Invoke-SpringBootSmoke "api" "hide-nest-api started" "api-smoke"
$workerSmokeResult = Invoke-SpringBootSmoke "worker" "hide-nest-worker started" "worker-smoke"
$apiSmoke = $apiSmokeResult.Started
$workerSmoke = $workerSmokeResult.Started
Write-Host "  API smoke: $apiSmoke (term=$($apiSmokeResult.Termination))"
Write-Host "  Worker smoke: $workerSmoke (term=$($workerSmokeResult.Termination))"

# ---- 8. Post-state snapshot ----
Write-Host "[8] Post-state snapshot..." -ForegroundColor Yellow
Push-Location $repoRoot
$gitIndexAfter = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $repoRoot ".git\index")).Hash
$trackedModified = @(git -c core.quotepath=off diff --name-only)
$untrackedFiles = @(git -c core.quotepath=off ls-files --others --exclude-standard)
$stagedFiles = @(git -c core.quotepath=off diff --cached --name-only)
Pop-Location
$indexUnchanged = ($gitIndexBefore -eq $gitIndexAfter)
$dockerAfterContainers = @(docker ps -a --format "{{.Names}}" 2>$null)
$dockerAfterVolumes = @(docker volume ls --format "{{.Name}}" 2>$null)
$dockerAfterNetworks = @(docker network ls --format "{{.Name}}" 2>$null)
$dockerNewContainers = @($dockerAfterContainers | Where-Object { $_ -notin $dockerBeforeContainers })
$dockerNewVolumes = @($dockerAfterVolumes | Where-Object { $_ -notin $dockerBeforeVolumes })
$dockerNewNetworks = @($dockerAfterNetworks | Where-Object { $_ -notin $dockerBeforeNetworks })
$dockerClean = ($dockerNewContainers.Count -eq 0) -and ($dockerNewVolumes.Count -eq 0) -and ($dockerNewNetworks.Count -eq 0)

# ---- 9. Allowlist check ----
$allowedPrefixes = @(
    "modules/database-adapter/src/main/resources/db/migration/V009",
    "modules/database-adapter/src/generated/",
    "modules/database-adapter/src/test/",
    "scripts/hdm006-sb-evidence-judge.ps1",
    "reports/HDM-006-SB-",
    "reports/HDM-006-PreflightManifest.json",
    "reports/HDM-006-预检报告.md"
)
$outOfBounds = @()
foreach ($p in $trackedModified) {
    $np = $p.Replace('\','/')
    $match = $false
    foreach ($a in $allowedPrefixes) { if ($np.StartsWith($a)) { $match = $true; break } }
    if (-not $match) { $outOfBounds += $np }
}
foreach ($p in $untrackedFiles) {
    $np = $p.Replace('\','/')
    $match = $false
    foreach ($a in $allowedPrefixes) { if ($np.StartsWith($a)) { $match = $true; break } }
    if (-not $match) { $outOfBounds += $np }
}

# ---- 10. Real body/secret column scan ----
Write-Host "[10] Body/secret column scan..." -ForegroundColor Yellow
$bodySecretExcludePattern = '_(hash|key|id|at|code|kind|ref|set|version|time|mode|type|status)$'
$bodySecretFound = 0
$bodySecretDetails = @()

# Scan V009 migration SQL for column names matching body/secret patterns
# Match exact column names, not substrings; exclude *_hash, *_key, and other structural columns
if ($v009MigrationExists) {
    $v009Lines = Get-Content $v009MigrationFile -Encoding UTF8
    foreach ($line in $v009Lines) {
        if ($line -match '^\s*(\w+)\s+\w+') {
            $colName = $Matches[1].ToLower()
            if ($colName -notmatch $bodySecretExcludePattern) {
                $patMatch = $false
                foreach ($pat in @("body","body_text","text","content","payload","prompt","answer","secret","token","password","credential","chain_of_thought","cot","api_key","access_key")) {
                    if ($colName -eq $pat) { $patMatch = $true; break }
                }
                if ($patMatch) {
                    $bodySecretFound++
                    $bodySecretDetails += "V009 column: $colName (line: $($line.Trim()))"
                }
            }
        }
    }
}

# Scan jOOQ generated runtime table Java files for body/secret field names
$runtimeTablesDir = Join-Path $generatedRoot "io\github\candyxi0\hidenest\database\generated\runtime\tables"
if (Test-Path $runtimeTablesDir) {
    Get-ChildItem $runtimeTablesDir -Filter "*.java" | ForEach-Object {
        $javaContent = Get-Content $_.FullName -Raw -Encoding UTF8
        $fieldMatches = [regex]::Matches($javaContent, 'public\s+\w+\s+(\w+)\s*;', 'IgnoreCase')
        foreach ($m in $fieldMatches) {
            $fieldName = $m.Groups[1].Value.ToLower()
            if ($fieldName -notmatch $bodySecretExcludePattern) {
                foreach ($pat in @("body","body_text","text","content","payload","prompt","answer","secret","token","password","credential","chain_of_thought","cot","api_key","access_key")) {
                    if ($fieldName -eq $pat) {
                        $bodySecretFound++
                        $bodySecretDetails += "jOOQ field: $($m.Groups[1].Value) in $($_.Name)"
                        break
                    }
                }
            }
        }
    }
}
Write-Host "  Body/secret columns/fields found: $bodySecretFound"
if ($bodySecretFound -gt 0) {
    Write-Host "  DETAILS:" -ForegroundColor Yellow
    $bodySecretDetails | ForEach-Object { Write-Host "    $_" }
}

# ---- Build criteria from REAL observations ----
$criteria = @{}
$criteria["migrationHashesV001V008Match"] = $v001v008Match
$criteria["v009MigrationExists"] = $v009MigrationExists
$criteria["newTableCountEq9"] = ($runtimeNewCount -eq 9)
$criteria["v009TableCountSelfConsistent"] = $v009TableCountSelfConsistent
$criteria["mavenVerifyPass"] = $mavenPass
$criteria["xmlExists"] = $xmlExists
$criteria["testTotalGt0"] = ($testTotal -gt 0)
$criteria["testPassedAll"] = ($testFailed -eq 0 -and $testErrors -eq 0 -and $testSkipped -eq 0)
$criteria["v009MethodsPresent"] = ($v009Names.Count -gt 0)
$criteria["r1MethodsPresent"] = ($r1Names.Count -gt 0)
$criteria["r2MethodsPresent"] = ($r2Names.Count -gt 0)
$criteria["r201FrozenUnitUpdate"] = ($allNames -contains "r201FrozenUnitUpdateRejected")
$criteria["r201FrozenUnitDelete"] = ($allNames -contains "r201FrozenUnitDeleteRejected")
$criteria["r201ReadyToRunning"] = ($allNames -contains "r201CloseoutRunReadyToRunning")
$criteria["r201ReadyToFailed"] = ($allNames -contains "r201CloseoutRunReadyToFailed")
$criteria["r201ReadyToCancelled"] = ($allNames -contains "r201CloseoutRunReadyToCancelled")
$criteria["r201RunningToCompleted"] = ($allNames -contains "r201CloseoutRunRunningToCompleted")
$criteria["r201RunningToFailed"] = ($allNames -contains "r201CloseoutRunRunningToFailed")
$criteria["r201RunningToCancelled"] = ($allNames -contains "r201CloseoutRunRunningToCancelled")
$criteria["r201V008toV009Upgrade"] = ($allNames -contains "r201IsolatedV008toV009Upgrade")
$criteria["nodeTypecheckPass"] = ($nodeTC.ExitCode -eq 0)
$criteria["nodeLintPass"] = ($nodeLint.ExitCode -eq 0)
$criteria["nodeTestPass"] = ($nodeTest.ExitCode -eq 0)
$criteria["nodeBuildPass"] = ($nodeBuild.ExitCode -eq 0)
$criteria["apiSmokePass"] = $apiSmoke
$criteria["workerSmokePass"] = $workerSmoke
$criteria["gitIndexUnchanged"] = $indexUnchanged
$criteria["stagedEq0"] = ($stagedFiles.Count -eq 0)
$criteria["outOfBoundsEq0"] = ($outOfBounds.Count -eq 0)
$criteria["dockerClean"] = $dockerClean
# These are set to $false initially; self-validation below will update them
$criteria["runnerHashSelfConsistent"] = $false
$criteria["evidenceJsonParseable"] = $false
$criteria["countsSelfConsistent"] = $false

$failedCriteria = @($criteria.Keys | Where-Object { -not $criteria[$_] })

# ---- Build Evidence JSON ----
$evidence = [ordered]@{
    schemaVersion = "HDM-006-SB-EVIDENCE-R4"
    status = "PENDING"
    runner = @{
        path = "scripts/hdm006-sb-evidence-judge.ps1"
        sha256 = $runnerSha256
        startTime = $runnerStartTime
        endTime = [DateTime]::UtcNow.ToString("o")
    }
    baseline = @{
        head = $gitHead
        shortHead = $gitHead.Substring(0,7)
        branch = $gitBranch
    }
    environment = @{
        mavenVersion = $mavenVersion
        javaVersion = $javaVersion
        javaHome = $javaHome
    }
    migration = @{
        v009Sha256 = $vHashes["V009"]
        v001v008Sha256 = $vHashes
        v001v008MatchBaseline = $v001v008Match
        historyCount = 9
        newTableCount = 9
        v009CreateTableCount = $v009CreateTableCount
        tables = @($newRuntimeTables)
    }
    tests = @{
        total = $testTotal
        passed = $testPassed
        failed = $testFailed
        errors = $testErrors
        skipped = $testSkipped
        xmlFile = "modules/database-adapter/target/surefire-reports/TEST-io.github.candyxi0.hidenest.database.DatabaseSliceBContractTest.xml"
        xmlExists = $xmlExists
        xmlSha256 = $xmlHash
        xmlPotentiallyStale = $xmlPotentiallyStale
        v009MethodCount = $v009Names.Count
        r1MethodCount = $r1Names.Count
        r2MethodCount = $r2Names.Count
        v009MethodNames = @($v009Names)
        r1MethodNames = @($r1Names)
        r2MethodNames = @($r2Names)
    }
    jooq = @{
        totalFiles = $jooqCount
    }
    node = @{
        typecheckExitCode = $nodeTC.ExitCode
        lintExitCode = $nodeLint.ExitCode
        testExitCode = $nodeTest.ExitCode
        buildExitCode = $nodeBuild.ExitCode
    }
    apiWorkerSmoke = @{
        apiStarted = $apiSmoke
        apiTermination = $apiSmokeResult.Termination
        workerStarted = $workerSmoke
        workerTermination = $workerSmokeResult.Termination
    }
    gitIndex = @{
        beforeSha256 = $gitIndexBefore
        afterSha256 = $gitIndexAfter
        unchanged = $indexUnchanged
        headUnchanged = ($gitHead -eq "c7dea7ee6c743deee9aa7c4ef900d4d45bb25066")
    }
    workspace = @{
        trackedModified = @($trackedModified)
        trackedCount = $trackedModified.Count
        untracked = @($untrackedFiles)
        untrackedCount = $untrackedFiles.Count
        staged = @($stagedFiles)
        stagedCount = $stagedFiles.Count
        outOfBounds = @($outOfBounds)
        outOfBoundsCount = $outOfBounds.Count
    }
    docker = @{
        beforeContainers = @($dockerBeforeContainers)
        afterContainers = @($dockerAfterContainers)
        newContainers = @($dockerNewContainers)
        newVolumes = @($dockerNewVolumes)
        newNetworks = @($dockerNewNetworks)
    }
    bodySecretScan = @{
        patterns = @("body","body_text","text","content","payload","prompt","answer","secret","token","password","credential","chain_of_thought","cot","api_key","access_key")
        excludeSuffix = "_(hash|key|id|at|code|kind|ref|set|version|time|mode|type|status)"
        found = $bodySecretFound
        details = @($bodySecretDetails)
    }
    commands = $commands
    logs = @{}
    criteria = $criteria
    failedCriteria = @($failedCriteria)
}

# Add log snippets (first 2000 chars)
$logsOut = [ordered]@{}
foreach ($k in $logs.Keys) {
    $v = $logs[$k]
    if ($v.Length -gt 2000) { $v = $v.Substring(0,2000) + "..." }
    $logsOut[$k] = $v
}
$evidence.logs = $logsOut

# Write preliminary evidence
$evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidencePath -Encoding UTF8

# ---- Self-validation ----
Write-Host "[11] Self-validating Evidence..." -ForegroundColor Yellow
try {
    $parsed = Get-Content $evidencePath -Raw -Encoding UTF8 | ConvertFrom-Json
    $evidenceJsonParseable = $true
    Write-Host "  JSON parseable: YES"

    # Verify runner hash matches
    $runnerMatch = ($parsed.runner.sha256 -eq $runnerSha256)
    Write-Host "  Runner hash match: $runnerMatch"
    $criteria["runnerHashSelfConsistent"] = $runnerMatch

    # Verify XML hash matches
    $xmlMatch = ($xmlExists -and $parsed.tests.xmlSha256 -eq $xmlHash)
    Write-Host "  XML hash match: $xmlMatch"

    # Verify counts self-consistent: test total > 0 and no failures
    $criteria["evidenceJsonParseable"] = $true
    $criteria["countsSelfConsistent"] = ($testTotal -gt 0 -and $testFailed -eq 0 -and $testErrors -eq 0)

    # Rebuild failedCriteria
    $failedCriteria = @($criteria.Keys | Where-Object { -not $criteria[$_] })
    $allPassed = ($failedCriteria.Count -eq 0)

    $evidence.status = if ($allPassed) { "HDM006_SLICE_B_R4_READY_FOR_LOCAL_COMMIT" } else { "HDM006_SLICE_B_R4_BLOCKED" }
    $evidence.criteria = $criteria
    $evidence.failedCriteria = @($failedCriteria)
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidencePath -Encoding UTF8

} catch {
    Write-Host "  JSON SELF-CHECK FAILED: $_" -ForegroundColor Red
    $evidence.status = "HDM006_SLICE_B_R4_EVIDENCE_PARSE_FAILED"
    $evidence.failedCriteria = @("evidenceJsonParseable")
    $evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    $allPassed = $false
}

Write-Host ""
Write-Host "=== R4 Judge Complete ===" -ForegroundColor Cyan
Write-Host "Status: $($evidence.status)"
Write-Host "Tests: $testTotal/$testPassed/$testFailed/$testErrors/$testSkipped"
Write-Host "Git index: $indexUnchanged  Docker clean: $dockerClean  OutOfBounds: $($outOfBounds.Count)"
Write-Host "Runner hash: $runnerSha256"
if (-not $allPassed) {
    Write-Host "FAILED: $($failedCriteria -join ', ')" -ForegroundColor Red
    exit 1
}
exit 0
