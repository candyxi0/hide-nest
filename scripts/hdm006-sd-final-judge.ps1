<#
.SYNOPSIS
HDM-006 Slice D final mechanical evidence runner (commander repair).

.DESCRIPTION
Runs every final gate from the current checkout, derives all verdicts from command
results or current files, and fails closed. It never edits production/test/schema/
generated files and only writes the final evidence and its Markdown report.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$scriptPath = $PSCommandPath
$repoRoot = (Resolve-Path (Join-Path (Split-Path -Parent $scriptPath) '..')).Path
$tmpDir = Join-Path $repoRoot 'reports\.tmp-hdm006-sd-r1'
$evidenceFile = Join-Path $repoRoot 'reports\HDM-006-SD-FinalEvidence.json'
$reportFile = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'reports') -File -Filter 'HDM-006-SD-D2-*.md' | Select-Object -First 1).FullName
$evidenceCandidate = Join-Path $tmpDir 'evidence-candidate.json'
$expectedHeadPrefix = '2425883'
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot'
$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'

function Get-TextSha256([string]$text) {
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
        return ([System.BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '')
    } finally {
        $algorithm.Dispose()
    }
}

function Get-LogicalIndexHash {
    $entries = @(& git -C $repoRoot ls-files -s) -join "`n"
    return Get-TextSha256 $entries
}

$startedAt = Get-Date
$runnerSha = (Get-FileHash -LiteralPath $scriptPath -Algorithm SHA256).Hash
$head = (& git -C $repoRoot rev-parse HEAD).Trim()
$indexPath = Join-Path $repoRoot '.git\index'
$gitIndexRawStart = (Get-FileHash -LiteralPath $indexPath -Algorithm SHA256).Hash
$gitIndexStart = Get-LogicalIndexHash
$statusStart = @(& git -C $repoRoot status --porcelain=v1 --untracked-files=all)
$criteria = [ordered]@{}
$commands = [ordered]@{}
$logHashes = [ordered]@{}
$taskPids = [System.Collections.Generic.List[int]]::new()

function Get-Set([string[]]$values) {
    $set = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($value in @($values)) {
        if (-not [string]::IsNullOrWhiteSpace($value)) { [void]$set.Add($value.Trim()) }
    }
    return $set
}

function Get-RepoRelativePath([string]$path) {
    $full = [System.IO.Path]::GetFullPath($path)
    $prefix = $repoRoot.TrimEnd('\') + '\'
    if (-not $full.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path is outside repository: $full"
    }
    return $full.Substring($prefix.Length).Replace('\', '/')
}

function Compare-Set([System.Collections.Generic.HashSet[string]]$before,
                     [System.Collections.Generic.HashSet[string]]$after) {
    $added = @($after | Where-Object { -not $before.Contains($_) } | Sort-Object)
    $removed = @($before | Where-Object { -not $after.Contains($_) } | Sort-Object)
    return [ordered]@{ added = $added; removed = $removed }
}

function Set-Criterion([string]$name, [bool]$passed, [string]$detail) {
    $criteria[$name] = [ordered]@{ passed = $passed; detail = $detail }
    $mark = if ($passed) { 'PASS' } else { 'FAIL' }
    Write-Host "[$mark] $name - $detail"
}

function Invoke-Logged([string]$name, [string]$command) {
    $logPath = Join-Path $tmpDir "$name.log"
    $output = @(& cmd.exe /d /s /c $command 2>&1)
    $exitCode = $LASTEXITCODE
    $output | Set-Content -LiteralPath $logPath -Encoding UTF8
    $hash = (Get-FileHash -LiteralPath $logPath -Algorithm SHA256).Hash
    $logHashes[$name] = $hash
    $commands[$name] = [ordered]@{
        command = $command
        exitCode = $exitCode
        logSha256 = $hash
        tail = @($output | Select-Object -Last 12 | ForEach-Object { "$_" })
    }
    return [ordered]@{ exitCode = $exitCode; output = $output; logPath = $logPath }
}

function Invoke-JavaSmoke([string]$name, [string]$jarPath, [string[]]$arguments) {
    $stdout = Join-Path $tmpDir "$name.stdout.log"
    $stderr = Join-Path $tmpDir "$name.stderr.log"
    $allArgs = @('-jar', $jarPath) + @($arguments)
    $process = Start-Process -FilePath $javaExe -ArgumentList $allArgs -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $taskPids.Add($process.Id)
    $output = @()
    if (Test-Path -LiteralPath $stdout) { $output += Get-Content -LiteralPath $stdout -Encoding UTF8 }
    if (Test-Path -LiteralPath $stderr) { $output += Get-Content -LiteralPath $stderr -Encoding UTF8 }
    $combined = Join-Path $tmpDir "$name.log"
    $output | Set-Content -LiteralPath $combined -Encoding UTF8
    $hash = (Get-FileHash -LiteralPath $combined -Algorithm SHA256).Hash
    $logHashes[$name] = $hash
    $commands[$name] = [ordered]@{
        command = "java -jar $jarPath $($arguments -join ' ')".Trim()
        pid = $process.Id
        exitCode = $process.ExitCode
        logSha256 = $hash
        tail = @($output | Select-Object -Last 12 | ForEach-Object { "$_" })
    }
    return [ordered]@{ exitCode = $process.ExitCode; output = $output; pid = $process.Id }
}

function Get-ProductionJavaFiles {
    return @(Get-ChildItem -LiteralPath $repoRoot -Recurse -File -Filter '*.java' |
        Where-Object { $_.FullName -match '\\src\\main\\java\\' -and $_.FullName -notmatch '\\target\\' })
}

function Get-NonAllowedStatus([string[]]$statusLines) {
    $allowed = @(
        'scripts/hdm006-sd-final-judge.ps1',
        'reports/HDM-006-SD-FinalEvidence.json',
        (Get-RepoRelativePath $reportFile)
    )
    return @($statusLines | Where-Object {
        $line = "$_"
        $path = if ($line.Length -ge 4) { $line.Substring(3).Trim('"') } else { $line }
        -not ($allowed -contains $path.Replace('\', '/'))
    })
}

function New-Evidence([string]$status, [string[]]$failedCriteria, $testData,
                      $nodeData, $smokeData, $staticData, $workspaceData) {
    return [ordered]@{
        status = $status
        producerScriptSha256 = $runnerSha
        producerStartedAt = $startedAt.ToString('o')
        producerCompletedAt = (Get-Date).ToString('o')
        head = $head
        criteria = $criteria
        failedCriteria = @($failedCriteria)
        commands = $commands
        logHashes = $logHashes
        java = $testData
        node = $nodeData
        smoke = $smokeData
        static = $staticData
        workspace = $workspaceData
    }
}

function Write-Report($evidence) {
    $failedText = if ($evidence.failedCriteria.Count -eq 0) { '[]' } else { $evidence.failedCriteria -join ', ' }
    $content = @(
        '# HDM-006 Slice D Final Mechanical Evidence',
        '',
        "Status: $($evidence.status)",
        "Baseline HEAD: $($evidence.head)",
        "runner SHA-256: $($evidence.producerScriptSha256)",
        '',
        '## Mechanical gate results',
        '',
        '| Gate | Result |',
        '|---|---|',
        "| Maven offline clean verify | exit=$($evidence.java.mavenExitCode) |",
        "| Surefire XML/tests/failures/errors/skipped | $($evidence.java.xmlFileCount) / $($evidence.java.total.run) / $($evidence.java.total.failures) / $($evidence.java.total.errors) / $($evidence.java.total.skipped) |",
        "| Node typecheck/lint/test/build | $($evidence.node.typecheck) / $($evidence.node.lint) / $($evidence.node.test) / $($evidence.node.build) |",
        "| API/Worker default/Worker misconfigured | $($evidence.smoke.api) / $($evidence.smoke.workerDefault) / $($evidence.smoke.workerMisconfigured) |",
        "| V001-V009/V010/generated | $($evidence.static.v001v009) / $($evidence.static.v010) / $($evidence.static.generated) |",
        "| Production guard/handler/OPERATIONAL producer | $($evidence.static.productionGuardCount) / $($evidence.static.productionHandlerCount) / $($evidence.static.operationalProducerCount) |",
        "| Body/secret/trivial assertion hits | $($evidence.static.bodyLeakCount) / $($evidence.static.secretLeakCount) / $($evidence.static.trivialAssertionCount) |",
        "| Git index/staged/out-of-scope | $($evidence.workspace.gitIndex) / $($evidence.workspace.stagedCount) / $($evidence.workspace.outOfScopeChanges) |",
        "| Added Docker containers-volumes-networks | $($evidence.workspace.dockerAdded.containers.Count)-$($evidence.workspace.dockerAdded.volumes.Count)-$($evidence.workspace.dockerAdded.networks.Count) |",
        "| Task process residue | $($evidence.workspace.taskProcessResidue) |",
        '',
        "failedCriteria: $failedText",
        '',
        'This report is generated from the same-run Evidence by scripts/hdm006-sd-final-judge.ps1. Any failed real gate produces BLOCKED.'
    ) -join [Environment]::NewLine
    $content | Set-Content -LiteralPath $reportFile -Encoding UTF8
}

Write-Host '=== HDM-006 Slice D final mechanical gate ==='
if (-not $head.StartsWith($expectedHeadPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "HEAD mismatch: expected $expectedHeadPrefix, got $head"
}
if (-not (Test-Path -LiteralPath $javaExe)) { throw "JDK 25 java.exe missing: $javaExe" }

if (Test-Path -LiteralPath $tmpDir) { Remove-Item -LiteralPath $tmpDir -Recurse -Force }
New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null

$dockerBefore = [ordered]@{
    containers = Get-Set @(& docker ps -aq 2>$null)
    volumes = Get-Set @(& docker volume ls -q 2>$null)
    networks = Get-Set @(& docker network ls -q 2>$null)
}

# T01/T02 - baseline and full Java gate.
Set-Criterion 'headMatch' $true "HEAD=$head"
$maven = Invoke-Logged 'maven-clean-verify' 'mvnw.cmd clean verify -o -DforkCount=1'
Set-Criterion 'mavenExitZero' ($maven.exitCode -eq 0) "exit=$($maven.exitCode)"

$xmlFiles = @(Get-ChildItem -LiteralPath $repoRoot -Recurse -File -Filter 'TEST-*.xml' |
    Where-Object { $_.FullName -match '\\target\\surefire-reports\\' })
$suiteData = [ordered]@{}
$xmlHashes = [ordered]@{}
$total = [ordered]@{ run = 0; failures = 0; errors = 0; skipped = 0 }
foreach ($file in $xmlFiles) {
    [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw -Encoding UTF8
    $suite = $xml.testsuite
    $relative = Get-RepoRelativePath $file.FullName
    $entry = [ordered]@{
        run = [int]$suite.tests
        failures = [int]$suite.failures
        errors = [int]$suite.errors
        skipped = [int]$suite.skipped
        sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    }
    $suiteData[$relative] = $entry
    $xmlHashes[$relative] = $entry.sha256
    $total.run += $entry.run
    $total.failures += $entry.failures
    $total.errors += $entry.errors
    $total.skipped += $entry.skipped
}
$requiredSuites = @(
    'BackoffCalculatorTest', 'OutboxWorkerCoordinatorTest', 'OutboxWorkerAssemblyTest',
    'DatabaseSliceD1OutboxMechanicsTest', 'DatabaseSliceBContractTest',
    'DatabaseSliceC2AEvidenceMemoryAdapterTest', 'DatabaseSliceC2BRuntimeAdapterTest',
    'SliceCCoordinatorTest', 'ArchitectureTest', 'DatabaseBoundaryTest', 'PortBoundaryTest'
)
$missingSuites = @($requiredSuites | Where-Object {
    $name = $_
    -not ($xmlFiles | Where-Object { $_.Name -like "*$name*" } | Select-Object -First 1)
})
Set-Criterion 'requiredSuitesPresent' ($missingSuites.Count -eq 0) "missing=$($missingSuites -join ',')"
Set-Criterion 'allJavaTestsPass' (
    $maven.exitCode -eq 0 -and $xmlFiles.Count -gt 0 -and
    $total.failures -eq 0 -and $total.errors -eq 0 -and $total.skipped -eq 0
) "xml=$($xmlFiles.Count), tests=$($total.run), failures=$($total.failures), errors=$($total.errors), skipped=$($total.skipped)"
$javaData = [ordered]@{
    mavenExitCode = $maven.exitCode
    xmlFileCount = $xmlFiles.Count
    requiredSuites = $requiredSuites
    missingSuites = $missingSuites
    total = $total
    suites = $suiteData
    xmlHashes = $xmlHashes
}

# T03 - Node and real application process gates.
$nodeData = [ordered]@{}
foreach ($gate in @('typecheck', 'lint', 'test', 'build')) {
    $result = Invoke-Logged "node-$gate" "npm.cmd --offline run $gate"
    $nodeData[$gate] = if ($result.exitCode -eq 0) { 'PASS' } else { "FAIL(exit=$($result.exitCode))" }
    Set-Criterion "node-$gate" ($result.exitCode -eq 0) "exit=$($result.exitCode)"
}

$apiJar = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'apps\api\target') -File -Filter '*-exec.jar' | Select-Object -First 1)
$workerJar = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'apps\worker\target') -File -Filter '*-exec.jar' | Select-Object -First 1)
$smokeData = [ordered]@{ api = 'NOT_RUN'; workerDefault = 'NOT_RUN'; workerMisconfigured = 'NOT_RUN' }
if ($apiJar.Count -eq 1 -and $workerJar.Count -eq 1) {
    $apiSmoke = Invoke-JavaSmoke 'api-smoke' $apiJar[0].FullName @()
    $apiStarted = @($apiSmoke.output | Select-String -SimpleMatch 'hide-nest-api started').Count -gt 0
    $smokeData.api = if ($apiSmoke.exitCode -eq 0 -and $apiStarted) { 'PASS' } else { "FAIL(exit=$($apiSmoke.exitCode),started=$apiStarted)" }
    Set-Criterion 'apiSmoke' ($apiSmoke.exitCode -eq 0 -and $apiStarted) "exit=$($apiSmoke.exitCode), started=$apiStarted"

    $workerSmoke = Invoke-JavaSmoke 'worker-default-smoke' $workerJar[0].FullName @()
    $workerStarted = @($workerSmoke.output | Select-String -SimpleMatch 'hide-nest-worker started').Count -gt 0
    $smokeData.workerDefault = if ($workerSmoke.exitCode -eq 0 -and $workerStarted) { 'PASS' } else { "FAIL(exit=$($workerSmoke.exitCode),started=$workerStarted)" }
    Set-Criterion 'workerDefaultSmoke' ($workerSmoke.exitCode -eq 0 -and $workerStarted) "exit=$($workerSmoke.exitCode), started=$workerStarted"

    $workerBad = Invoke-JavaSmoke 'worker-enabled-failclosed' $workerJar[0].FullName @('--hide.outbox.worker.enabled=true')
    $correctReason = @($workerBad.output | Select-String -SimpleMatch 'no OutboxEffectHandler').Count -gt 0
    $smokeData.workerMisconfigured = if ($workerBad.exitCode -ne 0 -and $correctReason) { 'PASS' } else { "FAIL(exit=$($workerBad.exitCode),reason=$correctReason)" }
    Set-Criterion 'workerMisconfiguredFailClosed' ($workerBad.exitCode -ne 0 -and $correctReason) "exit=$($workerBad.exitCode), correctReason=$correctReason"
} else {
    Set-Criterion 'apiSmoke' $false "execJarCount=$($apiJar.Count)"
    Set-Criterion 'workerDefaultSmoke' $false "execJarCount=$($workerJar.Count)"
    Set-Criterion 'workerMisconfiguredFailClosed' $false 'worker exec jar unavailable'
}

# T04 - immutable inputs and real static scans.
$migrationRoot = Join-Path $repoRoot 'modules\database-adapter\src\main\resources\db\migration'
$v001v009Files = @(Get-ChildItem -LiteralPath $migrationRoot -File |
    Where-Object { $_.Name -match '^V00[1-9]__.+\.sql$' } | Sort-Object Name)
$changedFrozenMigrations = @()
foreach ($file in $v001v009Files) {
    & git -C $repoRoot diff --quiet HEAD -- $file.FullName
    if ($LASTEXITCODE -ne 0) { $changedFrozenMigrations += $file.Name }
}
Set-Criterion 'v001v009Match' ($v001v009Files.Count -eq 9 -and $changedFrozenMigrations.Count -eq 0) "files=$($v001v009Files.Count), changed=$($changedFrozenMigrations -join ',')"
$v010 = @(Get-ChildItem -LiteralPath $migrationRoot -File -Filter 'V010__*.sql')
$v010Data = if ($v010.Count -eq 1) {
    [ordered]@{ path = (Get-RepoRelativePath $v010[0].FullName); bytes = $v010[0].Length; sha256 = (Get-FileHash -LiteralPath $v010[0].FullName -Algorithm SHA256).Hash }
} else { $null }
Set-Criterion 'v010Present' ($v010.Count -eq 1) "count=$($v010.Count), bytes=$(if ($v010Data) {$v010Data.bytes} else {0})"

$generatedRoot = Join-Path $repoRoot 'modules\database-adapter\src\generated'
$generatedFiles = @(Get-ChildItem -LiteralPath $generatedRoot -Recurse -File -ErrorAction SilentlyContinue)
$generatedChanged = @(& git -C $repoRoot diff --name-only HEAD -- 'modules/database-adapter/src/generated' |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
Set-Criterion 'generatedTreeMatch' ($generatedChanged.Count -eq 0) "files=$($generatedFiles.Count), changed=$($generatedChanged -join ',')"

$productionJava = @(Get-ProductionJavaFiles)
$guardHits = @($productionJava | Select-String -Pattern '\bimplements\s+CompletionGuardPort\b')
$handlerHits = @($productionJava | Select-String -Pattern '\bimplements\s+OutboxEffectHandler\b')
$operationalHits = @($productionJava | Select-String -Pattern '["'']OPERATIONAL["'']')

$outboxBoundaryFiles = @(
    (Join-Path $repoRoot 'modules\runtime\src\main\java\io\github\candyxi0\hidenest\runtime\domain\ClaimedOutboxEvent.java'),
    (Join-Path $repoRoot 'modules\runtime\src\main\java\io\github\candyxi0\hidenest\runtime\domain\OutboxFailureSettlement.java'),
    (Join-Path $repoRoot 'modules\runtime\src\main\java\io\github\candyxi0\hidenest\runtime\domain\OutboxTerminalSettlement.java'),
    (Join-Path $repoRoot 'modules\application\src\main\java\io\github\candyxi0\hidenest\application\outbox\ProcessedEffect.java')
)
$bodyLeakHits = @()
foreach ($file in $outboxBoundaryFiles) {
    if (-not (Test-Path -LiteralPath $file)) { continue }
    $text = Get-Content -LiteralPath $file -Raw -Encoding UTF8
    $withoutComments = [regex]::Replace($text, '(?s)/\*.*?\*/|//[^\r\n]*', '')
    if ($withoutComments -match '(?i)body_?text|prompt|answer|chain.?of.?thought') { $bodyLeakHits += $file }
}
$secretPatterns = '(?i)-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|sk-[A-Za-z0-9]{20,}'
$secretHits = @($productionJava | Select-String -Pattern $secretPatterns)
$testJava = @(Get-ChildItem -LiteralPath $repoRoot -Recurse -File -Filter '*.java' |
    Where-Object { $_.FullName -match '\\src\\test\\java\\' -and $_.FullName -notmatch '\\target\\' })
$trivialAssertions = @($testJava | Select-String -Pattern 'assertTrue\s*\(\s*true\s*\)')

Set-Criterion 'productionGuardZero' ($guardHits.Count -eq 0) "count=$($guardHits.Count)"
Set-Criterion 'productionHandlerZero' ($handlerHits.Count -eq 0) "count=$($handlerHits.Count)"
Set-Criterion 'operationalProducerZero' ($operationalHits.Count -eq 0) "count=$($operationalHits.Count)"
Set-Criterion 'outboxBodyLeakZero' ($bodyLeakHits.Count -eq 0) "count=$($bodyLeakHits.Count)"
Set-Criterion 'secretLeakZero' ($secretHits.Count -eq 0) "count=$($secretHits.Count)"
Set-Criterion 'trivialAssertionZero' ($trivialAssertions.Count -eq 0) "count=$($trivialAssertions.Count)"

$staticData = [ordered]@{
    v001v009 = if ($v001v009Files.Count -eq 9 -and $changedFrozenMigrations.Count -eq 0) { 'MATCH' } else { 'MISMATCH' }
    frozenMigrationCount = $v001v009Files.Count
    changedFrozenMigrations = $changedFrozenMigrations
    v010 = if ($v010.Count -eq 1) { 'MATCH' } else { 'MISSING_OR_DUPLICATE' }
    v010Evidence = $v010Data
    generated = if ($generatedChanged.Count -eq 0) { 'MATCH' } else { 'MISMATCH' }
    generatedFileCount = $generatedFiles.Count
    generatedChanged = $generatedChanged
    productionScanFileCount = $productionJava.Count
    productionGuardCount = $guardHits.Count
    productionHandlerCount = $handlerHits.Count
    operationalProducerCount = $operationalHits.Count
    bodyLeakCount = $bodyLeakHits.Count
    bodyLeakPaths = @($bodyLeakHits | ForEach-Object { Get-RepoRelativePath "$_" })
    secretLeakCount = $secretHits.Count
    trivialAssertionCount = $trivialAssertions.Count
}

# T05 - end snapshots, dynamic failure list and JSON self-validation.
$dockerAfter = [ordered]@{
    containers = Get-Set @(& docker ps -aq 2>$null)
    volumes = Get-Set @(& docker volume ls -q 2>$null)
    networks = Get-Set @(& docker network ls -q 2>$null)
}
$dockerAdded = [ordered]@{
    containers = (Compare-Set $dockerBefore.containers $dockerAfter.containers).added
    volumes = (Compare-Set $dockerBefore.volumes $dockerAfter.volumes).added
    networks = (Compare-Set $dockerBefore.networks $dockerAfter.networks).added
}
Set-Criterion 'dockerNoNewResources' (
    $dockerAdded.containers.Count -eq 0 -and $dockerAdded.volumes.Count -eq 0 -and $dockerAdded.networks.Count -eq 0
) "added=$($dockerAdded.containers.Count)-$($dockerAdded.volumes.Count)-$($dockerAdded.networks.Count)"

$gitIndexRawEnd = (Get-FileHash -LiteralPath $indexPath -Algorithm SHA256).Hash
$gitIndexEnd = Get-LogicalIndexHash
$staged = @(& git -C $repoRoot diff --cached --name-only | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
$statusEnd = @(& git -C $repoRoot status --porcelain=v1 --untracked-files=all)
$nonAllowedBefore = Get-Set @(Get-NonAllowedStatus $statusStart)
$nonAllowedAfter = Get-Set @(Get-NonAllowedStatus $statusEnd)
$workspaceDelta = Compare-Set $nonAllowedBefore $nonAllowedAfter
$outOfScopeChanges = $workspaceDelta.added.Count + $workspaceDelta.removed.Count
$processResidue = @($taskPids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue }).Count
Set-Criterion 'gitIndexUnchanged' ($gitIndexStart -eq $gitIndexEnd) "start=$gitIndexStart, end=$gitIndexEnd"
Set-Criterion 'stagedZero' ($staged.Count -eq 0) "count=$($staged.Count)"
Set-Criterion 'outOfScopeDeltaZero' ($outOfScopeChanges -eq 0) "added=$($workspaceDelta.added.Count), removed=$($workspaceDelta.removed.Count)"
Set-Criterion 'taskProcessResidueZero' ($processResidue -eq 0) "count=$processResidue"

$workspaceData = [ordered]@{
    gitIndexStart = $gitIndexStart
    gitIndexEnd = $gitIndexEnd
    gitIndexRawStart = $gitIndexRawStart
    gitIndexRawEnd = $gitIndexRawEnd
    gitIndex = if ($gitIndexStart -eq $gitIndexEnd) { 'MATCH' } else { 'MISMATCH' }
    stagedCount = $staged.Count
    outOfScopeChanges = $outOfScopeChanges
    outOfScopeAdded = $workspaceDelta.added
    outOfScopeRemoved = $workspaceDelta.removed
    dockerAdded = $dockerAdded
    taskPids = @($taskPids)
    taskProcessResidue = $processResidue
}

$failedCriteria = @($criteria.Keys | Where-Object { -not $criteria[$_].passed })
$status = if ($failedCriteria.Count -eq 0) {
    'HDM006_SLICE_D_FINAL_EVIDENCE_COMMANDER_READY_FOR_LOCAL_COMMIT'
} else {
    'BLOCKED_HDM006_SLICE_D_FINAL_EVIDENCE_COMMANDER'
}
$evidence = New-Evidence $status $failedCriteria $javaData $nodeData $smokeData $staticData $workspaceData
$evidence | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $evidenceCandidate -Encoding UTF8

$parsed = Get-Content -LiteralPath $evidenceCandidate -Raw -Encoding UTF8 | ConvertFrom-Json
$selfValid = (
    $parsed.status -eq $status -and
    $parsed.producerScriptSha256 -match '^[A-F0-9]{64}$' -and
    [int]$parsed.java.total.run -ge 0 -and
    [int]$parsed.java.total.failures -ge 0 -and
    @($parsed.failedCriteria).Count -eq $failedCriteria.Count
)
if (-not $selfValid) { throw 'Evidence self-validation failed' }
Copy-Item -LiteralPath $evidenceCandidate -Destination $evidenceFile -Force
Write-Report $parsed

Remove-Item -LiteralPath $tmpDir -Recurse -Force
Write-Host "FINAL STATUS: $status"
Write-Host "FAILED CRITERIA: $($failedCriteria -join ', ')"
if ($failedCriteria.Count -gt 0) { exit 1 }
exit 0
