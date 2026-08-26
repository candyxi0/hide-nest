<#
.SYNOPSIS
  hide-nest home one-click deploy pipeline — LOCAL orchestrator (Windows).

  Runs Git gates, Auto component classification, offline build orchestration,
  artifact staging, upload, remote verify/execute and report generation.

  SAFETY:
    * Default is -PlanOnly. Without -Execute, NO remote write is ever produced.
    * Execute requires: HEAD == origin/main == TargetCommit, staged=0, tracked
      worktree clean, no unknown untracked outside the QA directory, and
      -ConfirmCommit exactly matching the target commit.
    * Builds from `git archive <TargetCommit>` — never from the dirty worktree.
    * Unknown params/components/paths/remote states fail closed.
    * Remote 0600 state.json is authoritative; bootstrap is only for first init.
    * Unimplemented branches ALWAYS exit non-zero (no false success).
    * This script NEVER runs git add/commit/push/reset/checkout/clean.
    * Never prints token/capability/secret/env bodies.
#>
[CmdletBinding()]
param(
    [string]$RemoteHost = "xilin@100.64.213.28",
    [string]$TargetCommit,
    [switch]$PlanOnly,
    [switch]$Execute,
    [string]$ConfirmCommit,
    [string]$BootstrapStatePath,
    [switch]$InitializeState,
    [string]$ComponentMapPath,
    [string]$BashPath,            # injectable Git Bash (R1-02)
    [string]$JdkPath,             # injectable JDK home (R2-01)
    [string]$NodePath,            # injectable Node.exe (R2-02/03)
    [string]$NpmPath,             # injectable npm cli js (R2-02/03)
    [string]$SshPath,             # injectable ssh.exe (R2A-01)
    [string]$ScpPath,             # injectable scp.exe (R2A-01)
    # ---- test / verification hooks (fail-closed; default to real repo/host) ----
    [string]$FakeGitRoot,
    [string]$FakeHomeRoot,
    [string]$FakeRemoteEngine,
    [string]$WorkRoot,
    [switch]$FakeBuild           # injectable fake build (goes through real orchestration flow)
)

$ErrorActionPreference = "Stop"
$PSDefaultParameterValues['*:Encoding'] = 'utf8'

$SCRIPT:RepoRoot = ""
if ($FakeGitRoot) { $SCRIPT:RepoRoot = $FakeGitRoot }
else { $SCRIPT:RepoRoot = (git rev-parse --show-toplevel 2>$null) }
if (-not $SCRIPT:RepoRoot) { throw "HOME_DEPLOY_NOT_GIT_REPO: unable to locate git repo root" }

if (-not $ComponentMapPath) { $ComponentMapPath = Join-Path $SCRIPT:RepoRoot "ops/home-deploy/component-map.json" }
if (-not (Test-Path $ComponentMapPath)) { throw "HOME_DEPLOY_MISSING_COMPONENT_MAP: $ComponentMapPath" }

if ($PlanOnly -and $Execute) { throw "HOME_DEPLOY_BAD_ARGS: PlanOnly and Execute are mutually exclusive" }
if ($InitializeState -and $Execute) { throw "HOME_DEPLOY_BAD_ARGS: InitializeState and Execute are mutually exclusive" }
$isExecute = $Execute -and (-not $PlanOnly) -and (-not $InitializeState)
$isPlan = (-not $isExecute) -and (-not $InitializeState)
if ($ConfirmCommit -and -not $isExecute) { throw "HOME_DEPLOY_BAD_ARGS: ConfirmCommit requires -Execute" }

# R2-04/R2B-02: fake/real must not mix. A fake build may only ever target a fake transport:
# either a FakeHomeRoot, or injected fake SshPath+ScpPath executables (real-branch反证).
# Sending a fake artifact to a real host with real ssh/scp is rejected. (FakeGitRoot alone is
# a benign git-source selection used by plan/classification and does not risk remote writes.)
if ($FakeBuild -and -not $FakeHomeRoot -and -not ($SshPath -and $ScpPath)) { throw "HOME_DEPLOY_MIXED_FAKE_REAL: FakeBuild requires FakeHomeRoot or fake SshPath+ScpPath" }
if ($FakeRemoteEngine -and -not $FakeHomeRoot) { throw "HOME_DEPLOY_MIXED_FAKE_REAL: FakeRemoteEngine requires FakeHomeRoot" }

$componentMap = Get-Content -Raw -Encoding UTF8 $ComponentMapPath | ConvertFrom-Json

$global:qaDirRel = "reports/local-v1-read-browser-qa"
$global:workDirs = New-Object System.Collections.Generic.List[string]

function ConvertTo-UnixPath { param([string]$P) ($P -replace '\\','/') }

function Get-MsysPath {
    param([string]$WinPath)
    $u = ConvertTo-UnixPath $WinPath
    $m = (& $SCRIPT:BashPath -c "cygpath -u '$u'" 2>$null | Select-Object -Last 1)
    return (($m -as [string]).Trim())
}

function Resolve-GitBash {
    param([string]$Provided)
    $candidate = ""
    if ($Provided) {
        if (Test-Path $Provided) { $candidate = (Get-Item $Provided).FullName }
    }
    if (-not $candidate) {
        $gitCmd = Get-Command git -ErrorAction SilentlyContinue
        if ($gitCmd -and $gitCmd.Source) {
            # walk up from git.exe directory checking <ancestor>\bin\bash.exe and <ancestor>\bash.exe
            $dir = Split-Path $gitCmd.Source -Parent
            for ($i = 0; $i -lt 6 -and $dir; $i++) {
                foreach ($c in @((Join-Path $dir 'bin/bash.exe'),(Join-Path $dir 'bash.exe'))) {
                    if (Test-Path $c) { $candidate = (Get-Item $c).FullName; break }
                }
                if ($candidate) { break }
                $parent = Split-Path $dir -Parent
                if ($parent -eq $dir) { break }
                $dir = $parent
            }
        }
    }
    if (-not $candidate) { foreach ($c in @('C:\Program Files\Git\bin\bash.exe','D:\Git\bin\bash.exe')) { if (Test-Path $c) { $candidate = (Get-Item $c).FullName; break } } }
    if (-not $candidate) { throw "HOME_DEPLOY_NO_GITBASH: could not locate Git Bash (bash.exe)" }
    $full = (Get-Item $candidate).FullName
    if ($full -match '^C:\\Windows\\System32') { throw "HOME_DEPLOY_BAD_GITBASH: rejected WSL stub $full" }
    $ver = (& $full --version 2>&1 | Out-String)
    if ($ver -notmatch 'GNU bash' -or $ver -notmatch 'MSYS') { throw "HOME_DEPLOY_BAD_GITBASH: $full is not Git Bash/MSYS. version=$ver" }
    return $full
}

# ---- R1-02: resolve Git Bash once; every bash invocation uses this (never WSL stub)
$SCRIPT:BashPath = Resolve-GitBash $BashPath

function Get-Git {
    param([string[]]$ArgsList)
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $out = @(& git -C $SCRIPT:RepoRoot @ArgsList 2>&1) } finally { $ErrorActionPreference = $prev }
    return ($out | ForEach-Object { "$_" })
}

function Resolve-Ref {
    param([string]$Ref)
    if (-not $Ref) { return "" }
    $out = (Get-Git @('rev-parse','--verify',"$Ref`^{commit}") 2>$null)
    $sha = ($out | Select-Object -First 1).Trim().Trim('"')
    if ($sha -notmatch '^[0-9a-f]{40}$') { return "" }
    return $sha
}

function ConvertTo-GlobRegex {
    param([string]$Pattern)
    $p = $Pattern.Replace('\','/')
    $sb = New-Object System.Text.StringBuilder
    $i = 0
    while ($i -lt $p.Length) {
        $c = $p[$i]
        if ($c -eq '*') {
            if ($i+1 -lt $p.Length -and $p[$i+1] -eq '*') {
                if ($i+2 -lt $p.Length -and $p[$i+2] -eq '/') { [void]$sb.Append('(?:.*/)?'); $i += 3; continue }
                else { [void]$sb.Append('.*'); $i += 2; continue }
            } else { [void]$sb.Append('[^/]*'); $i += 1; continue }
        }
        if ($c -eq '?') { [void]$sb.Append('[^/]'); $i += 1; continue }
        if ('\^$.|+()[]{}'.Contains($c)) { [void]$sb.Append('\') }
        [void]$sb.Append($c); $i += 1
    }
    return ('^' + $sb.ToString() + '$')
}

function Test-PathPattern { param([string]$RelPath,[string]$Pattern) if ($RelPath -eq $Pattern) { return $true }; return ([regex]::IsMatch($RelPath, (ConvertTo-GlobRegex $Pattern), [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)) }
function Test-PathAny { param([string]$RelPath,[string[]]$Patterns) foreach ($pat in $Patterns) { if (Test-PathPattern $RelPath $pat) { return $true } }; return $false }

# --- State ---------------------------------------------------------------
function Get-BootstrapState {
    if ($BootstrapStatePath -and (Test-Path $BootstrapStatePath)) {
        return (Get-Content -Raw -Encoding UTF8 $BootstrapStatePath | ConvertFrom-Json)
    }
    return $null
}

function Test-ValidState {
    # R2-10: strict closed schema. Every required field must be present/valid and NO
    # extra fields are allowed. Corrupt / missing / unknown -> fail closed.
    param($St)
    if (-not $St) { return $false }
    $allowed = @('schemaVersion','apiCommit','consoleCommit','adapterCommit','pipelineCommit',
                 'flywayMaxVersion','migrationManifestHash','migrationManifest','artifactHashes',
                 'hostInvariants','updatedAt')
    $props = @($St.PSObject.Properties.Name)
    foreach ($p in $props) { if ($allowed -notcontains $p) { return $false } }   # extra-field rejection
    if ([string]$St.schemaVersion -ne '1') { return $false }
    foreach ($k in @('apiCommit','consoleCommit','adapterCommit','pipelineCommit')) {
        if ([string]$St.$k -notmatch '^[0-9a-f]{40}$') { return $false }
    }
    if ([string]$St.flywayMaxVersion -notmatch '^\d+$') { return $false }
    if ([string]$St.migrationManifestHash -notmatch '^[0-9a-f]{64}$') { return $false }
    if (-not $St.migrationManifest -or -not $St.migrationManifest.PSObject.Properties -or $St.migrationManifest.PSObject.Properties.Count -lt 1) { return $false }
    if (-not $St.artifactHashes) { return $false }
    foreach ($c in @('api','console','adapter')) {
        if ([string]$St.artifactHashes.$c -notmatch '^[0-9a-f]{64}$') { return $false }
    }
    if (-not $St.hostInvariants) { return $false }
    foreach ($f in @('postgresContainerId','embeddingContainerId')) {
        if ($null -eq $St.hostInvariants.$f) { return $false }
    }
    return $true
}

function Get-AuthoritativeState {
    # R1-04: remote 0600 state.json is authoritative. bootstrap only for first init.
    if ($InitializeState) { return (Get-BootstrapState) }
    $remote = Invoke-RemoteEngine @('--operation','plan')
    if ($remote.ExitCode -ne 0) { throw "HOME_DEPLOY_REMOTE_PLAN_FAILED: $($remote.Output)" }
    $planJson = $null
    try { $planJson = ($remote.Output | Out-String) | ConvertFrom-Json } catch { throw "HOME_DEPLOY_REMOTE_PLAN_PARSE_FAILED: $($remote.Output)" }
    if ($planJson.hasState) {
        $st = $planJson.state
        if (-not (Test-ValidState $st)) { throw "HOME_DEPLOY_BAD_STATE: remote state invalid or unknown schema" }
        return $st
    }
    return (Get-BootstrapState)
}

# --- Git gates -------------------------------------------------------------
function Test-GitGates {
    param([string]$TargetSha, [string]$ConfirmSha)
    function AddGate($name,$ok,$msg){ $script:gates += [pscustomobject]@{Name=$name;Ok=$ok;Msg=$msg} }
    $script:gates = @()
    $head = Resolve-Ref 'HEAD'
    $origin = Resolve-Ref 'origin/main'
    AddGate "HEAD==Target" ($head -eq $TargetSha) "HEAD=$head target=$TargetSha"
    AddGate "origin/main==Target" ($origin -eq $TargetSha) "origin=$origin target=$TargetSha"
    $staged = @(Get-Git @('diff','--cached','--name-only') | Where-Object { $_.Trim() -ne "" })
    AddGate "staged=0" ($staged.Count -eq 0) "staged=$($staged.Count)"
    $trackedDirty = @(Get-Git @('diff','--name-only') | Where-Object { $_.Trim() -ne "" })
    AddGate "tracked-clean" ($trackedDirty.Count -eq 0) "dirty=$($trackedDirty.Count)"
    $untracked = @(Get-Git @('ls-files','--others','--exclude-standard') | Where-Object { $_.Trim() -ne "" })
    $badUntracked = @($untracked | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith($global:qaDirRel) })
    AddGate "untracked-only-QA" ($badUntracked.Count -eq 0) "unknownUntracked=$($badUntracked.Count)"
    $confirmOk = ($ConfirmSha -and ($ConfirmSha -eq $TargetSha))
    AddGate "ConfirmCommit-match" $confirmOk "confirm=$ConfirmSha target=$TargetSha"
    foreach ($g in $script:gates) { Write-Host ("gate:{0}={1} ({2})" -f $g.Name, $g.Ok, $g.Msg) }
    return $script:gates
}

# --- Classification ---------------------------------------------------------
function Get-Classification {
    param([string]$TargetSha, $State)
    $apiCommit  = $State.apiCommit
    $consoleCommit = $State.consoleCommit
    $adapterCommit = $State.adapterCommit
    $pipeCommit = $State.pipelineCommit

    $allChanged = New-Object System.Collections.Generic.HashSet[string]
    $perComponent = @{ api=@(); console=@(); adapter=@() }
    # R2-11: pipeline self paths are NEVER part of api/console/adapter diffs. They are
    # classified only against pipelineCommit (below). Exclude them here so they cannot
    # leak into allChanged / highRisk / unknown for the component baselines.
    $isPipelineSelf = { param($p) Test-PathAny $p @($componentMap.pipelineSelfPaths) }
    foreach ($comp in @('api','console','adapter')) {
        $dep = switch ($comp) { 'api' {$apiCommit}; 'console'{$consoleCommit}; 'adapter'{$adapterCommit} }
        if (-not $dep) { continue }
        $changed = @(Get-Git @('diff','--name-only',$dep,$TargetSha) |
                     Where-Object { $_.Trim() -ne "" } |
                     ForEach-Object { $_.Trim().Trim('"') } |
                     Where-Object { -not (& $isPipelineSelf $_) })
        foreach ($p in $changed) { [void]$allChanged.Add($p) }
        $perComponent[$comp] = @($changed)
    }

    $highRisk = New-Object System.Collections.Generic.HashSet[string]
    $unknown = @()
    $impacted = @{ api=$false; console=$false; adapter=$false; migration=$false }

    foreach ($p in $allChanged) {
        # R2-11: pipeline self paths are handled only via pipelineCommit diff; skip entirely here.
        if (& $isPipelineSelf $p) { continue }
        # high-risk boundary paths (grants etc.) — these are classified, never "unknown"
        $isHighRisk = $false
        if (Test-PathAny $p @($componentMap.highRiskPaths)) { $isHighRisk = $true; [void]$highRisk.Add($p) }

        $whitelisted = $false
        foreach ($k in $componentMap.noRuntimeImpactWhitelist.PSObject.Properties.Name) {
            if (Test-PathPattern $p $k) { $whitelisted = $true; break }
        }
        if ($whitelisted) { continue }

        $shared = Test-PathAny $p @($componentMap.sharedNodePaths)
        if ($shared) { $impacted.console = $true; $impacted.adapter = $true }
        $migHit = Test-PathAny $p @($componentMap.componentMap.migration.impactPaths)
        if ($migHit) { $impacted.migration = $true; $impacted.api = $true }

        $matched = $false
        foreach ($comp in @('api','console','adapter')) {
            if (Test-PathAny $p @($componentMap.componentMap.$comp.impactPaths)) { $impacted.$comp = $true; $matched = $true }
        }
        if (-not $matched -and -not $shared -and -not $migHit -and -not $isHighRisk) { $unknown += $p }
    }

    # R1-05: pipeline self change computed against pipelineCommit (never mixed into
    # api/console/adapter component baselines). Same-value pipeline commit is NOT a block.
    if ($pipeCommit) {
        $pipeChanged = @(Get-Git @('diff','--name-only',$pipeCommit,$TargetSha)) |
                       Where-Object { $_.Trim() -ne "" } |
                       Where-Object { Test-PathAny ($_.Trim().Trim('"')) @($componentMap.pipelineSelfPaths) }
        foreach ($p in $pipeChanged) { [void]$highRisk.Add($p.Trim().Trim('"')) }
    }

    $ownImpact = @{
        api     = @($componentMap.componentMap.api.impactPaths)
        console = @($componentMap.componentMap.console.impactPaths) + @($componentMap.sharedNodePaths)
        adapter = @($componentMap.componentMap.adapter.impactPaths) + @($componentMap.sharedNodePaths)
    }
    foreach ($comp in @('api','console','adapter')) {
        $ownHit = $false
        foreach ($p in $perComponent[$comp]) { if (Test-PathAny $p $ownImpact[$comp]) { $ownHit = $true; break } }
        if (-not $ownHit) { $impacted.$comp = $false }
    }

    return [pscustomobject]@{ allChanged=@($allChanged); perComponent=$perComponent; highRisk=@($highRisk); unknown=@($unknown); impacted=$impacted }
}

# --- Migration gates (R1-09: bidirectional modify/delete/rename) ----------
function Get-MigrationVerdict {
    param([string]$TargetSha, $State)
    $boundary = if ($State.flywayMaxVersion) { [int]$State.flywayMaxVersion } else { [int]$componentMap.deployedMigrationBoundary }
    $migDir = $componentMap.migrationDir
    $result = [pscustomobject]@{ NewMigrations=@(); Tampered=@(); Verdict='NO_CHANGE' }

    $deployed = @{}
    if ($State.migrationManifest -and $State.migrationManifest.PSObject.Properties) {
        foreach ($prop in $State.migrationManifest.PSObject.Properties) { $deployed[$prop.Name] = [string]$prop.Value }
    }
    $target = @{}
    $targetMigs = @(Get-Git @('ls-tree','-r','--name-only',$TargetSha,'--',$migDir)) |
                  Where-Object { $_.Trim() -match 'V(\d+)__.*\.sql$' }
    foreach ($mf in $targetMigs) {
        $name = $mf.Trim().Trim('"')
        $target[$name] = (Get-Git @('rev-parse',"${TargetSha}:$name")).Trim()
    }

    foreach ($name in $deployed.Keys) {
        if (-not $target.ContainsKey($name)) { $result.Tampered += $name }   # deleted
    }
    foreach ($name in $target.Keys) {
        $m = [regex]::Match((Split-Path $name -Leaf), '^V(\d+)__')
        $ver = [int]$m.Groups[1].Value
        if ($deployed.ContainsKey($name)) {
            if ($target[$name] -ne $deployed[$name]) { $result.Tampered += $name }   # modified
        } else {
            if ($ver -le $boundary) { $result.Tampered += $name }   # new file, deployed version -> tamper
            else { $result.NewMigrations += $name }                # new migration
        }
    }

    if ($result.Tampered.Count -gt 0) { $result.Verdict = 'BLOCKED' }
    elseif ($result.NewMigrations.Count -gt 0) { $result.Verdict = 'HIGH_RISK_NEW_MIGRATION' }
    return $result
}

# --- Remote engine invocation ----------------------------------------------
function Invoke-RemoteEngine {
    param([string[]]$ArgsList)
    $engine = if ($FakeRemoteEngine) { $FakeRemoteEngine } else { Join-Path $PSScriptRoot 'remote-deploy.sh' }
    $log = Join-Path ([System.IO.Path]::GetTempPath()) ("hndlog-"+[guid]::NewGuid().ToString('N')+".txt")
    $prevEAP = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    if ($FakeHomeRoot) {
        $rootUnix = ConvertTo-UnixPath $FakeHomeRoot
        $a = @($engine,'--root',$rootUnix) + $ArgsList
        $prevSkip = $env:HOME_DEPLOY_SKIP_SMOKE; $prevFake = $env:HOME_DEPLOY_FAKE_SERVICES
        $env:HOME_DEPLOY_SKIP_SMOKE = '1'; $env:HOME_DEPLOY_FAKE_SERVICES = '1'
        try { & $SCRIPT:BashPath $a *> $log; $code = $LASTEXITCODE }
        finally {
            $ErrorActionPreference = $prevEAP
            if ($null -eq $prevSkip) { Remove-Item Env:HOME_DEPLOY_SKIP_SMOKE -ErrorAction SilentlyContinue } else { $env:HOME_DEPLOY_SKIP_SMOKE = $prevSkip }
            if ($null -eq $prevFake) { Remove-Item Env:HOME_DEPLOY_FAKE_SERVICES -ErrorAction SilentlyContinue } else { $env:HOME_DEPLOY_FAKE_SERVICES = $prevFake }
        }
    } else {
        # R2B-02: real SSH unified through the resolved SshPath (no hardcoded ssh, no cmd /c
        # string concatenation). remote-deploy.sh is streamed to ssh stdin as RAW UTF-8/LF bytes
        # (byte-array pipe avoids PowerShell's LF->CRLF native-pipe conversion). Args are passed
        # as separate argv elements; target/nonce/path are closed-validated by the caller.
        $argsString = ($ArgsList | ForEach-Object { "'$($_ -replace '\\','/')'" }) -join ' '
        $scriptBody = ((Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'remote-deploy.sh')) -replace "`r","")
        $tmpBody = Join-Path $env:TEMP ("hndbody-"+[guid]::NewGuid().ToString('N')+".sh")
        [System.IO.File]::WriteAllText($tmpBody, $scriptBody, (New-Object System.Text.UTF8Encoding $false))
        $bytes = [System.IO.File]::ReadAllBytes($tmpBody)
        $ssh = if ($SshPath) { $SshPath } else { 'ssh' }
        $sshArgs = @('-o','BatchMode=yes', $RemoteHost, "bash -s -- $argsString")
        try {
            $res = Invoke-SshScript -SshPath $ssh -ArgsList $sshArgs -StdinBytes $bytes
            $code = $res.ExitCode
            [System.IO.File]::WriteAllText($log, $res.Output, (New-Object System.Text.UTF8Encoding $false))
        }
        finally { $ErrorActionPreference = $prevEAP; Remove-Item $tmpBody -Force -ErrorAction SilentlyContinue }
    }
    $out = (Get-Content -Raw $log -ErrorAction SilentlyContinue)
    Remove-Item $log -Force -ErrorAction SilentlyContinue
    return [pscustomobject]@{ Output=($out -join "`n"); ExitCode=$code }
}

function DirHash {
    param([string]$Dir)
    $u = ConvertTo-UnixPath $Dir
    $script = Join-Path $env:TEMP ("hndhash-"+[guid]::NewGuid().ToString('N')+".sh")
    $body = @'
#!/usr/bin/env bash
cd '{U}' || exit 1
find . -type f -print0 | sort -z | while IFS= read -r -d '' f; do rel="${f#./}"; printf '%s:%s\n' "$rel" "$(sha256sum "$f" | cut -d' ' -f1)"; done | sha256sum | cut -d' ' -f1
'@ -replace '\{U\}', $u
    [System.IO.File]::WriteAllText($script, $body, (New-Object System.Text.UTF8Encoding $false))
    $h = (& $SCRIPT:BashPath $script 2>$null | Select-Object -Last 1)
    Remove-Item $script -Force -ErrorAction SilentlyContinue
    return ($h -as [string]).Trim()
}

# --- Build (R1-01: real orchestration flow; fake build is an injected stage) ---
function Build-Affected {
    param([string[]]$Affected, [string]$ArchiveDir, [string]$ArtDir, [string]$TargetSha)
    $result = @{}
    foreach ($comp in $Affected) {
        if ($comp -eq 'api') {
            $localPath = Join-Path $ArtDir 'hide-nest-api-0.0.1-SNAPSHOT-exec.jar'
            New-Item -ItemType Directory -Path (Split-Path $localPath -Parent) -Force | Out-Null
            if ($FakeBuild) {
                Set-Content -Path $localPath -Value ("fake-api-" + $TargetSha) -NoNewline -Encoding UTF8
            } else {
                # R2-01: real offline API build from the snapshot root (JDK25, mvnw -o clean verify)
                $javaHome = if ($JdkPath) { $JdkPath } else { 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4-hotspot' }
                if (-not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) { throw "HOME_DEPLOY_JDK_MISSING: $javaHome" }
                $mvnw = Join-Path $ArchiveDir 'mvnw.cmd'
                if (-not (Test-Path $mvnw)) { throw "HOME_DEPLOY_MVN_MISSING" }
                $prevJH = $env:JAVA_HOME; $prevPath = $env:PATH
                $env:JAVA_HOME = $javaHome; $env:PATH = "$javaHome\bin;" + $env:PATH
                $code = 0
                try { Push-Location $ArchiveDir; & $mvnw -o clean verify 2>&1 | Out-Null; $code = $LASTEXITCODE; Pop-Location }
                finally { if ($null -eq $prevJH) { Remove-Item Env:JAVA_HOME -ErrorAction SilentlyContinue } else { $env:JAVA_HOME = $prevJH }; if ($null -eq $prevPath) { Remove-Item Env:PATH -ErrorAction SilentlyContinue } else { $env:PATH = $prevPath } }
                if ($code -ne 0) { throw "HOME_DEPLOY_API_BUILD_FAILED (exit $code)" }
                $jars = @(Get-ChildItem -Path (Join-Path $ArchiveDir 'apps/api/target') -Filter '*-exec.jar' -File -ErrorAction SilentlyContinue)
                if ($jars.Count -ne 1) { throw "HOME_DEPLOY_API_JAR_AMBIGUOUS: found $($jars.Count) exec jars" }
                if ($jars[0].Name -ne 'hide-nest-api-0.0.1-SNAPSHOT-exec.jar') { throw "HOME_DEPLOY_API_JAR_NAME_MISMATCH: $($jars[0].Name)" }
                $localPath = $jars[0].FullName
            }
            $result['api'] = @{ type='jar'; target='app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar'; staging='components/api/hide-nest-api-0.0.1-SNAPSHOT-exec.jar'; artifactHash=((Get-FileHash -Algorithm SHA256 $localPath).Hash.ToLower()); commit=$TargetSha; localPath=$localPath }
        } elseif ($comp -eq 'console') {
            $localDir = Join-Path $ArtDir 'console-dist'
            New-Item -ItemType Directory -Path $localDir -Force | Out-Null
            if ($FakeBuild) { Set-Content -Path (Join-Path $localDir 'index.html') -Value ("fake-console-"+$TargetSha) -NoNewline -Encoding UTF8 }
            else {
                # R2-02: real offline Console build (Node24/npm11 offline four gates)
                Build-NodeApp 'nest-console' $ArchiveDir $localDir $TargetSha
            }
            $result['console'] = @{ type='dir'; target='console/dist'; staging='components/console'; artifactHash=(DirHash $localDir); commit=$TargetSha; localPath=$localDir }
        } elseif ($comp -eq 'adapter') {
            $localDir = Join-Path $ArtDir 'adapter-dist'
            New-Item -ItemType Directory -Path $localDir -Force | Out-Null
            if ($FakeBuild) { Set-Content -Path (Join-Path $localDir 'mcp.js') -Value ("fake-adapter-"+$TargetSha) -NoNewline -Encoding UTF8 }
            else {
                # R2-03: real offline Adapter build (Node24/npm11 offline four gates, exclude .map)
                Build-NodeApp 'codex-adapter' $ArchiveDir $localDir $TargetSha
            }
            $result['adapter'] = @{ type='dir'; target='codex-adapter/dist'; staging='components/adapter'; artifactHash=(DirHash $localDir); commit=$TargetSha; localPath=$localDir }
        }
    }
    return $result
}

function Build-NodeApp {
    param([string]$AppName,[string]$ArchiveDir,[string]$LocalDir,[string]$TargetSha)
    $node = if ($NodePath) { $NodePath } else { (Get-Command node -ErrorAction SilentlyContinue).Source }
    if (-not $node -or -not (Test-Path $node)) { throw "HOME_DEPLOY_NODE_MISSING: $node" }
    $appDir = Join-Path $ArchiveDir ("apps/" + $AppName)
    if (-not (Test-Path (Join-Path $appDir 'package.json'))) { throw "HOME_DEPLOY_NODE_APP_MISSING: $appDir" }
    # npm CLI is a local tool, not part of the target snapshot: resolve from the real repo
    # (or injected NpmPath). The snapshot root has no node_modules.
    $npmCli = if ($NpmPath) { $NpmPath } else { Join-Path $SCRIPT:RepoRoot 'node_modules/npm/bin/npm-cli.js' }
    if (-not (Test-Path $npmCli)) { $npmCli = 'C:/Program Files/nodejs/node_modules/npm/bin/npm-cli.js' }
    if (-not (Test-Path $npmCli)) {
        $npmCmd = Get-Command npm -ErrorAction SilentlyContinue
        if ($npmCmd -and $npmCmd.Source) { $npmCli = $npmCmd.Source }
    }
    if (-not $npmCli -or -not (Test-Path $npmCli)) { throw "HOME_DEPLOY_NPM_CLI_MISSING" }
    # offline install from cache (no public network) if node_modules absent
    if (-not (Test-Path (Join-Path $appDir 'node_modules'))) {
        Push-Location $appDir
        try { & $node $npmCli ci --offline --ignore-scripts --no-audit --no-fund 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { throw "HOME_DEPLOY_NPM_CI_FAILED" } } finally { Pop-Location }
    }
    # R2-02/03: build workspace deps (api-client-ts, ui-contract-fixtures) first so the app's
    # TypeScript types resolve (snapshot has no prebuilt dist for workspace packages).
    foreach ($depWs in @('@hide-nest/api-client-ts','@hide-nest/ui-contract-fixtures')) {
        Push-Location $ArchiveDir
        try { & $node $npmCli run build --workspace $depWs --offline 2>&1 | Out-Null; if ($LASTEXITCODE -ne 0) { throw "HOME_DEPLOY_NODE_DEP_BUILD_FAILED: $depWs" } } finally { Pop-Location }
    }
    foreach ($gate in @('typecheck','lint','test','build')) {
        Push-Location $appDir
        try { & $node $npmCli run $gate --offline 2>&1 | Out-Null; $c = $LASTEXITCODE } finally { Pop-Location }
        if ($c -ne 0) { throw "HOME_DEPLOY_NODE_$($gate.ToUpper())_FAILED" }
    }
    $dist = Join-Path $appDir 'dist'
    if (-not (Test-Path $dist) -or (Get-ChildItem $dist -Recurse -File -ErrorAction SilentlyContinue).Count -eq 0) { throw "HOME_DEPLOY_NODE_DIST_EMPTY: $AppName" }
    Copy-NodeArtifact -Dist $dist -LocalDir $LocalDir -AppName $AppName
}

# R2A-02: copy raw dist -> deploy artifact, stripping every *.map, then assert on the FINAL
# artifact only (never the raw dist). Adapter: 98 non-map files / 0 maps / exactly 3 tools.
# Console: 3 files / 0 maps.
function Copy-NodeArtifact {
    param([string]$Dist,[string]$LocalDir,[string]$AppName)
    New-Item -ItemType Directory -Path $LocalDir -Force | Out-Null
    Get-ChildItem $Dist -Recurse -File | Where-Object { $_.Extension -ne '.map' } | ForEach-Object {
        $rel = $_.FullName.Substring($Dist.Length).TrimStart('\','/')
        $dest = Join-Path $LocalDir $rel
        New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force | Out-Null
        Copy-Item $_.FullName $dest -Force
    }
    $artifactFiles = @(Get-ChildItem $LocalDir -Recurse -File -ErrorAction SilentlyContinue)
    if ($artifactFiles.Count -eq 0) { throw "HOME_DEPLOY_NODE_ARTIFACT_EMPTY: $AppName" }
    if ((Get-ChildItem $LocalDir -Recurse -Filter '*.map' -ErrorAction SilentlyContinue).Count -gt 0) { throw "HOME_DEPLOY_NODE_ARTIFACT_MAP: $AppName" }
    if ($AppName -eq 'codex-adapter') {
        if ($artifactFiles.Count -ne 98) { throw "HOME_DEPLOY_ADAPTER_FILE_COUNT: expected 98 got $($artifactFiles.Count)" }
        $mcpPath = Join-Path $LocalDir 'mcp.js'
        if (-not (Test-Path $mcpPath)) { throw "HOME_DEPLOY_ADAPTER_MCP_MISSING" }
        $mcpText = Get-Content -Raw $mcpPath
        $tools = @([regex]::Matches($mcpText, 'hide_nest_[a-z_]+') | ForEach-Object { $_.Value } | Sort-Object -Unique)
        if ($tools.Count -ne 3) { throw "HOME_DEPLOY_ADAPTER_TOOLS: expected 3 got $($tools.Count)" }
    } elseif ($AppName -eq 'nest-console') {
        if ($artifactFiles.Count -ne 3) { throw "HOME_DEPLOY_CONSOLE_FILE_COUNT: expected 3 got $($artifactFiles.Count)" }
    }
}

function Sha256File {
    param([string]$Path)
    (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLower()
}

# R2A-01: closed validation of RemoteHost / targetShort / nonce.
function Assert-RemoteHost {
    # R2B-01: RemoteHost must be user@<Tailscale host> only — Tailscale CGNAT 100.64.0.0/10
    # (100.64..100.127) or a MULTI-LEVEL *.ts.net magic DNS name (e.g. hide-home.tailnet.ts.net).
    # Bare ts.net, public IPs, LANs, and arbitrary/fake-suffix domains are rejected.
    param([string]$Value)
    $re = '^([a-z0-9][a-z0-9_.-]*)@(' +
          '100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])' +
          '|([a-z0-9-]+\.)+ts\.net)$'
    if ($Value -notmatch $re) { throw "HOME_DEPLOY_BAD_REMOTEHOST: $Value" }
    return $Value
}
function Assert-TargetShort {
    param([string]$Short)
    if ($Short -notmatch '^[0-9a-f]{7}$' -and $Short -notmatch '^[0-9a-f]{40}$') { throw "HOME_DEPLOY_BAD_TARGETSHORT: $Short" }
    return $Short
}
function Assert-Nonce {
    param([string]$Nonce)
    if ($Nonce -notmatch '^[0-9a-f]{12}$') { throw "HOME_DEPLOY_BAD_NONCE: $Nonce" }
    return $Nonce
}

# R2A-01: direct ssh/scp invocations (no cmd string concatenation). Fake executables can be
# injected via SshPath/ScpPath for the call-matrix反证. A .sh/.bash SshPath/ScpPath is run via
# the resolved Git Bash so argv is passed as separate elements (no cmd %* quoting loss).
function Invoke-Ssh {
    param([string[]]$ArgsList)
    $ssh = if ($SshPath) { $SshPath } else { 'ssh' }
    if ($SshPath -and $SshPath -match '\.(sh|bash)$') { & $SCRIPT:BashPath $SshPath @ArgsList 2>&1 }
    else { & $ssh @ArgsList 2>&1 }
    return $LASTEXITCODE
}
function Invoke-Scp {
    param([string[]]$ArgsList)
    $scp = if ($ScpPath) { $ScpPath } else { 'scp' }
    if ($ScpPath -and $ScpPath -match '\.(sh|bash)$') { & $SCRIPT:BashPath $ScpPath @ArgsList 2>&1 }
    else { & $scp @ArgsList 2>&1 }
    return $LASTEXITCODE
}

# R2B-02: run ssh (or a .sh fake via Git Bash) with RAW byte stdin (no CRLF conversion, no
# cmd /c string concatenation). Each arg is quoted for direct OS argv parsing (no shell
# injection); the ssh invocation is a single direct process, never a shell command string.
function Invoke-SshScript {
    param([string]$SshPath,[string[]]$ArgsList,[byte[]]$StdinBytes)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    if ($SshPath -match '\.(sh|bash)$') { $psi.FileName = $SCRIPT:BashPath }
    else { $psi.FileName = $SshPath }
    $argv = @()
    if ($SshPath -match '\.(sh|bash)$') { $argv += $SshPath }
    $argv += $ArgsList
    $quoted = ($argv | ForEach-Object {
        if ($_ -match '[\s"]') { '"' + ($_ -replace '"','\"') + '"' } else { $_ }
    }) -join ' '
    $psi.Arguments = $quoted
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    try { $p.StandardInput.BaseStream.Write($StdinBytes, 0, $StdinBytes.Length); $p.StandardInput.Close() } catch { }
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    return [pscustomobject]@{ ExitCode=$p.ExitCode; Output=($out + $err) }
}

# R2B-01: RemoteBase is FROZEN to ~/hide-nest (not user-input). The scp target is
# exactly user@host:~/hide-nest/deploy/tmp/incoming/<target>-<nonce>/<tar>.
$script:RemoteBase = "~/hide-nest"

function Copy-ToRemoteBundle {
    param([string]$LocalTar,[string]$IncomingRel,[string]$TarName,[string]$RemoteHost)
    $dest = "$RemoteHost`:$script:RemoteBase/$IncomingRel/$TarName"
    $r = Invoke-Scp @('-o','BatchMode=yes', $LocalTar, $dest)
    return $r
}

# R2-05/06/07: build the single deploy bundle with NAMED parameters receiving the full
# artifact IDictionary directly (never $Artifacts.Keys as positional). Frozen layout:
#   request.json
#   manifest.tsv
#   components/api/...
#   components/console/...
#   components/adapter/...
# Iteration is in fixed order api,console,adapter via ContainsKey — never depends on
# Hashtable Keys enumeration or positional binding.
function New-DeployBundle {
    param(
        [Parameter(Mandatory=$true)][hashtable]$Artifacts,
        [Parameter(Mandatory=$true)][string]$RequestPath,
        [Parameter(Mandatory=$true)][string]$BundlePath
    )
    New-Item -ItemType Directory -Path $BundlePath -Force | Out-Null
    foreach ($comp in @('api','console','adapter')) {
        if ($Artifacts.ContainsKey($comp)) {
            $a = $Artifacts[$comp]
            $destDir = Join-Path $BundlePath ("components/" + $comp)
            New-Item -ItemType Directory -Path $destDir -Force | Out-Null
            if ($a.type -eq 'jar') { Copy-Item $a.localPath (Join-Path $destDir (Split-Path $a.localPath -Leaf)) -Force }
            else { Copy-Item (Join-Path $a.localPath '*') $destDir -Recurse -Force }
        }
    }
    Copy-Item $RequestPath (Join-Path $BundlePath 'request.json') -Force
    # path-bound manifest (relativePath<TAB>SHA256 per line) over components/**
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($comp in @('api','console','adapter')) {
        $cdir = Join-Path $BundlePath ("components/" + $comp)
        if (Test-Path $cdir) {
            foreach ($f in (Get-ChildItem $cdir -Recurse -File | Sort-Object FullName)) {
                $rel = ("components/" + $comp + "/" + $f.FullName.Substring($cdir.Length).TrimStart('\','/')) -replace '\\','/'
                $lines.Add(("{0}`t{1}" -f $rel, (Sha256File $f.FullName)))
            }
        }
    }
    [System.IO.File]::WriteAllText((Join-Path $BundlePath 'manifest.tsv'), (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding $false))
}

function New-Request {
    param([string]$Path,[string]$TargetSha,[string]$TargetShort,$State,$Artifact)
    $components = @{}
    foreach ($comp in @('api','console','adapter')) {
        if ($Artifact.ContainsKey($comp)) {
            $a = $Artifact[$comp]
            $components[$comp] = @{ switch=$true; type=$a.type; commit=$a.commit; target=$a.target; staging=$a.staging; artifactHash=$a.artifactHash }
        } else {
            $components[$comp] = @{ switch=$false; type='dir'; commit=''; target=''; staging=''; artifactHash='' }
        }
    }
    $obj = [ordered]@{
        schemaVersion=1; operation='execute'; targetCommit=$TargetSha; targetShort=$TargetShort
        flywayMaxVersion=[int]$State.flywayMaxVersion
        pipelineCommit=[string]$State.pipelineCommit
        migrationManifestHash=[string]$State.migrationManifestHash
        migrationManifest=$State.migrationManifest
        components=$components
        migration=@{ switch=$false }
        hostInvariants=@{ postgresContainerId=[string]$State.hostInvariants.postgresContainerId; embeddingContainerId=[string]$State.hostInvariants.embeddingContainerId }
    }
    $obj | ConvertTo-Json -Depth 10 | Set-Content -Path $Path -Encoding UTF8
}

# --- Invoke-Execute (R1-01 real chain) --------------------------------------
function Invoke-Execute {
    param([string]$TargetSha,[string]$TargetShort,$State,$Class)
    $affected = @('api','console','adapter') | Where-Object { $Class.impacted.$_ }
    if ($affected.Count -eq 0) {
        AddResult "verdict" "HOME_DEPLOY_NO_OP_EXECUTE" $true
        Write-Output "HOME_DEPLOY_NO_OP_EXECUTE"
        return
    }

    $autoWork = $false
    if (-not $WorkRoot) { $WorkRoot = Join-Path $env:TEMP ("hndwork-"+[guid]::NewGuid().ToString('N')); $autoWork = $true }
    New-Item -ItemType Directory -Path $WorkRoot -Force | Out-Null
    $global:workDirs.Add($WorkRoot)

    try {
        # 1. Materialize the target-commit snapshot via a temporary INDEX CHECKOUT with
        #    core.autocrlf=false. Unlike `git archive` (raw blob EOL), this applies a
        #    deterministic LF materialization that byte-matches the passing working-tree
        #    checkout, so generator-determinism gates (jOOQ tracked match) are not tripped
        #    by EOL/snapshot drift. Never reads the dirty worktree; never hand-edits generated.
        $srcDir = Join-Path $WorkRoot 'src'
        New-Item -ItemType Directory -Path $srcDir -Force | Out-Null
        $tmpIndex = Join-Path $WorkRoot 'tmpindex'
        $prevGIF = $env:GIT_INDEX_FILE
        $env:GIT_INDEX_FILE = $tmpIndex
        try {
            git -C $SCRIPT:RepoRoot -c core.autocrlf=false read-tree $TargetSha 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "HOME_DEPLOY_ARCHIVE_FAILED" }
            $prefix = (ConvertTo-UnixPath $srcDir) + '/'
            git -C $SCRIPT:RepoRoot -c core.autocrlf=false checkout-index -a -f "--prefix=$prefix" 2>&1 | Out-Null
            if ($LASTEXITCODE -ne 0) { throw "HOME_DEPLOY_ARCHIVE_EXTRACT_FAILED" }
            # arch gates resolve the repo root by presence of pom.xml + .git; the snapshot
            # has no .git, so init an empty repo metadata dir (content untouched) to satisfy it.
            git -C $srcDir init -q 2>&1 | Out-Null
        } finally {
            if ($null -eq $prevGIF) { Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue } else { $env:GIT_INDEX_FILE = $prevGIF }
            Remove-Item $tmpIndex -Force -ErrorAction SilentlyContinue
        }

        # 2. build affected components (injectable fake build; real offline fails closed)
        $artDir = Join-Path $WorkRoot 'artifacts'
        $artifact = Build-Affected $affected $srcDir $artDir $TargetSha

        # 3. manifest
        $manifest = $artifact | ForEach-Object { "$($_.Key)=$($_.Value.artifactHash)" } | Out-String
        Set-Content -Path (Join-Path $WorkRoot 'manifest.txt') -Value $manifest -Encoding UTF8

        # 4. request JSON (at WorkRoot; New-DeployBundle copies it into the bundle)
        $reqPath = Join-Path $WorkRoot 'request.json'
        New-Request $reqPath $TargetSha $TargetShort $State $artifact

        # 5. package the SINGLE bundle (R2-05/06/07): named params, full IDictionary,
        #    frozen layout components/<comp>/..., bundle-relative staging.
        $bundleDir = Join-Path $WorkRoot 'bundle'
        New-DeployBundle -Artifacts $artifact -RequestPath $reqPath -BundlePath $bundleDir

        $nonce = [guid]::NewGuid().ToString('N').Substring(0,12)
        $incomingRel = "deploy/tmp/incoming/$TargetShort-$nonce"
        $tarName = "bundle-$TargetShort-$nonce.tar"
        $tarFile = Join-Path $WorkRoot $tarName
        $bundleMsys = Get-MsysPath $bundleDir
        $tarMsys = Get-MsysPath $tarFile
        & $SCRIPT:BashPath -c "cd '$bundleMsys' && tar -cf '$tarMsys' ." 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "HOME_DEPLOY_BUNDLE_TAR_FAILED" }
        $tarSha = (Get-FileHash -Algorithm SHA256 $tarFile).Hash.ToLower()

        # 6. upload bundle to the unique incoming dir.
        $remoteBundle = ""
        $e = $null
        if ($FakeHomeRoot) {
            $destIncoming = Join-Path $FakeHomeRoot ($incomingRel -replace '/','\')
            New-Item -ItemType Directory -Path $destIncoming -Force | Out-Null
            Copy-Item $tarFile (Join-Path $destIncoming $tarName) -Force
            # R2A-01 #6: pass a BASE-relative bundle path (same form as the real branch), so
            # op_execute_bundle resolves it under BASE via resolve_under (no Windows/MSYS/abs-external).
            $remoteBundle = "$incomingRel/$tarName"
            # 7. remote execute-bundle (fake): SHA verified, unpacked under lock, request read
            #    from the unpacked path (Windows/MSYS paths rejected by resolve_staging).
            $e = Invoke-RemoteEngine @('--operation','execute-bundle','--bundle',$remoteBundle,'--expected-sha',$tarSha)
        } else {
            # R2A-01: REAL ssh/scp code path (closed identifiers, mechanical incoming path).
            # This round never runs against the real host (REAL_RUN_NOT_RUN); fake SshPath/ScpPath
            # exercise the identical call sequence in tests.
            Assert-RemoteHost $RemoteHost | Out-Null
            Assert-TargetShort $TargetShort | Out-Null
            Assert-Nonce $nonce | Out-Null
            # 6a. prepare-upload: create the precise incoming dir, reject conflict
            $prep = Invoke-RemoteEngine @('--operation','prepare-upload','--incoming',$incomingRel)
            if ($prep.ExitCode -ne 0) { throw "HOME_DEPLOY_PREPARE_UPLOAD_FAILED: $($prep.Output)" }
            # 6b. scp the single unique tar (direct scp.exe call, no cmd string concatenation)
            $scpCode = Copy-ToRemoteBundle $tarFile $incomingRel $tarName $RemoteHost
            if ($scpCode -ne 0) {
                $null = Invoke-RemoteEngine @('--operation','cleanup-upload','--incoming',$incomingRel)
                throw "HOME_DEPLOY_SCP_FAILED: exit $scpCode"
            }
            # 6c. execute-bundle with a BASE-relative bundle path (resolved under BASE remotely)
            $remoteBundle = "$incomingRel/$tarName"
            try {
                $e = Invoke-RemoteEngine @('--operation','execute-bundle','--bundle',$remoteBundle,'--expected-sha',$tarSha)
            } finally {
                # 6d. cleanup this run's incoming on success/failure
                $null = Invoke-RemoteEngine @('--operation','cleanup-upload','--incoming',$incomingRel)
            }
        }

        AddResult "remote-execute-exit" $e.ExitCode ($e.ExitCode -eq 0)
        if ($e.ExitCode -ne 0) { throw "HOME_DEPLOY_EXECUTE_FAILED: $($e.Output)" }
        AddResult "remote-execute-output" (($e.Output | Out-String).Trim()) $true

        # 8. runtime report (kept in WorkRoot; summary emitted)
        Set-Content -Path (Join-Path $WorkRoot 'deploy-report.json') -Value (@{ targetCommit=$TargetSha; affected=($affected -join ','); bundleSha=$tarSha; remoteOutput=($e.Output | Out-String) } | ConvertTo-Json) -Encoding UTF8
    } finally {
        # 9. always clean auto-generated temp workdir (even on failure)
        if ($autoWork) { Remove-Item $WorkRoot -Recurse -Force -ErrorAction SilentlyContinue }
    }

    Write-Output "HOME_DEPLOY_BASIC_GATES_PASS_READY_FOR_XIAOLIN_QA"
}

# -----------------------------------------------------------------------------
# MAIN
# -----------------------------------------------------------------------------
# Dot-source guard: when this file is dot-sourced (for direct unit testing of its
# helper functions), define everything and return without running the deploy flow.
if ($MyInvocation.InvocationName -eq '.') { return }
$results = [System.Collections.Generic.List[object]]::new()
function AddResult($name,$value,$ok){ $script:results.Add([pscustomobject]@{Name=$name;Value=$value;Ok=$ok}) }

AddResult "mode" $(if($isExecute){"execute"}elseif($InitializeState){"initialize"}else{"plan"}) $true
AddResult "bash" $SCRIPT:BashPath $true

# R2B final: RemoteHost is closed-validated BEFORE any remote call (Get-AuthoritativeState /
# Invoke-RemoteEngine). PlanOnly / Execute / InitializeState all validate first, so an illegal
# public/LAN host yields SSH reach = 0.
$null = Assert-RemoteHost $RemoteHost

# Resolve target
if ($TargetCommit) {
    $targetSha = Resolve-Ref $TargetCommit
    if (-not $targetSha) { throw "HOME_DEPLOY_BAD_TARGET: TargetCommit not resolvable / not a valid sha: $TargetCommit" }
} else {
    $targetSha = Resolve-Ref 'origin/main'
    if (-not $targetSha) { throw "HOME_DEPLOY_BAD_TARGET: origin/main not resolvable" }
}
AddResult "target" $targetSha $true
$targetShort = $targetSha.Substring(0,7)

# Authoritative state (R1-04: remote state wins; bootstrap only first init)
$state = Get-AuthoritativeState

# InitializeState path (R1-03) — fake-verifiable; real host forbidden this task
if ($InitializeState) {
    if ($FakeHomeRoot) {
        $init = Invoke-RemoteEngine @('--operation','initialize','--bootstrap',(ConvertTo-UnixPath $BootstrapStatePath))
        AddResult "initialize-exit" $init.ExitCode ($init.ExitCode -eq 0)
        AddResult "initialize-output" (($init.Output | Out-String).Trim()) ($init.ExitCode -eq 0)
        if ($init.ExitCode -ne 0) { throw "HOME_DEPLOY_INITIALIZE_FAILED: $($init.Output)" }
        Write-Output (($init.Output | Out-String).Trim())
        exit 0
    } else {
        # real host initialization is deferred to Task49C
        throw "HOME_DEPLOY_REAL_INITIALIZE_FORBIDDEN: real InitializeState deferred to Task49C"
    }
}

$confirmSha = ""
if ($ConfirmCommit) {
    $confirmSha = Resolve-Ref $ConfirmCommit
    if (-not $confirmSha) { throw "HOME_DEPLOY_BAD_CONFIRM: ConfirmCommit not resolvable" }
}

if ($isExecute) {
    $gates = Test-GitGates $targetSha $confirmSha
    foreach ($g in $gates) { AddResult ("gate:"+$g.Name) $g.Msg $g.Ok; if (-not $g.Ok) { throw "HOME_DEPLOY_GATES_FAILED: gate $($g.Name) failed" } }
} else {
    AddResult "execute-gates" "not-run (plan)" $true
}

$class = Get-Classification $targetSha $state
AddResult "impacted" (($class.impacted.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ",") $true
AddResult "highRisk" ($class.highRisk -join ",") ($class.highRisk.Count -eq 0)
AddResult "unknown" ($class.unknown -join ",") ($class.unknown.Count -eq 0)
if ($class.unknown.Count -gt 0) { throw "HOME_DEPLOY_UNCLASSIFIED_PATH: $($class.unknown -join ',')" }

$mig = Get-MigrationVerdict $targetSha $state
AddResult "migration" $mig.Verdict $true
if ($mig.Verdict -eq 'BLOCKED') { throw "HOME_DEPLOY_MIGRATION_TAMPER_BLOCKED: $($mig.Tampered -join ',')" }

$hasChange = ($class.impacted.api -or $class.impacted.console -or $class.impacted.adapter -or $class.impacted.migration)

# R1-10 freeze: new migration -> HIGH_RISK, never reaches execute; launcher cannot continue
if ($class.highRisk.Count -gt 0 -or $mig.Verdict -eq 'HIGH_RISK_NEW_MIGRATION') {
    AddResult "verdict" "HOME_DEPLOY_HIGH_RISK_CONFIRMATION_REQUIRED" $false
    Write-Output "HOME_DEPLOY_HIGH_RISK_CONFIRMATION_REQUIRED"
    Write-Output "highRiskPaths=$($class.highRisk -join ',') migration=$($mig.Verdict)"
    exit 3
}

if ($isPlan) {
    if (-not $hasChange) {
        AddResult "verdict" "PLAN_NO_OP" $true
        Write-Output "PLAN_NO_OP"
        Write-Output "api=$($class.impacted.api) console=$($class.impacted.console) adapter=$($class.impacted.adapter) migration=$($class.impacted.migration)"
    } else {
        AddResult "verdict" "PLAN_CHANGES" $true
        Write-Output "PLAN_CHANGES"
        Write-Output "api=$($class.impacted.api) console=$($class.impacted.console) adapter=$($class.impacted.adapter) migration=$($class.impacted.migration)"
    }
    exit 0
}

# Execute path (R1-01): real orchestration chain
Invoke-Execute $targetSha $targetShort $state $class
exit 0
