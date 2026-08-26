<#
.SYNOPSIS
  hide-nest home deploy pipeline — hermetic counter-proof self-test (R1).

  Covers the original 28 required反证 PLUS the R1 end-to-end orchestrator反证,
  WITHOUT touching the real home host. Uses fake git repos and fake home roots
  under a temp directory, cleaned up precisely afterwards.

  Runs from a normal `powershell.exe -File ...` entrypoint (never only from a
  Git Bash parent shell). Git Bash is resolved explicitly (never the WSL stub).

  Exit 0 when ALL tests PASS; non-zero otherwise.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = "D:\myproject\hide-nest",
    [string]$BashPath,
    [switch]$KeepTemp
)

$ErrorActionPreference = "Stop"
$PSDefaultParameterValues['*:Encoding'] = 'utf8'

$script:Pass = 0
$script:Fail = 0
$script:Failures = New-Object System.Collections.Generic.List[string]
$script:TempDirs = New-Object System.Collections.Generic.List[string]
$script:Canary = "HNDEPLOY-SECRET-CANARY-9f3a"

function Record($name, [bool]$ok, $detail) {
    if ($ok) { $script:Pass++; Write-Host "  [PASS] $name" -ForegroundColor Green }
    else { $script:Fail++; $script:Failures.Add($name); Write-Host "  [FAIL] $name :: $detail" -ForegroundColor Red }
}

function New-TempDir {
    $p = Join-Path ([System.IO.Path]::GetTempPath()) ("hndpt-"+[guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $p -Force | Out-Null
    $script:TempDirs.Add($p)
    return $p
}

function ConvertTo-UnixPath { param([string]$P) ($P -replace '\\','/') }

function Resolve-GitBash {
    param([string]$Provided)
    $candidate = ""
    if ($Provided) { if (Test-Path $Provided) { $candidate = (Get-Item $Provided).FullName } }
    if (-not $candidate) {
        $gitCmd = Get-Command git -ErrorAction SilentlyContinue
        if ($gitCmd -and $gitCmd.Source) {
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
    if (-not $candidate) { throw "NO_GITBASH" }
    $full = (Get-Item $candidate).FullName
    if ($full -match '^C:\\Windows\\System32') { throw "WSL_STUB_REJECTED: $full" }
    $ver = (& $full --version 2>&1 | Out-String)
    if ($ver -notmatch 'GNU bash' -or $ver -notmatch 'MSYS') { throw "NOT_MSYS: $full" }
    return $full
}

function Set-TreeFile {
    param([string]$Root,[string]$Rel,[string]$Content)
    $full = Join-Path $Root $Rel
    New-Item -ItemType Directory -Path (Split-Path $full -Parent) -Force | Out-Null
    Set-Content -Path $full -Value $Content -Encoding UTF8 -NoNewline
}

function Sha256File { param([string]$Path) (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLower() }

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
    $h = (& $script:BashPath $script 2>$null | Select-Object -Last 1)
    Remove-Item $script -Force -ErrorAction SilentlyContinue
    return ($h -as [string]).Trim()
}

# ---------------- fake git repo ----------------
function New-FakeGitRepo {
    param([string]$Root)
    git -C $Root init -q
    git -C $Root config user.email "t@t.local" | Out-Null
    git -C $Root config user.name "t" | Out-Null
    $base = @{
        'pom.xml' = '<project/>'
        'apps/api/src/main/java/A.java' = 'class A{}'
        'apps/nest-console/src/App.tsx' = 'export const App=()=>null'
        'apps/codex-adapter/src/mcp.ts' = 'export const mcp=1'
        'packages/api-client-ts/src/client.ts' = 'export const c=1'
        'packages/ui-contract-fixtures/src/f.ts' = 'export const f=1'
        'modules/database-adapter/src/main/resources/db/migration/V001__base.sql' = 'CREATE TABLE t();'
        'modules/database-adapter/src/main/resources/db/migration/V002__second.sql' = 'CREATE TABLE s();'
        'package.json' = '{"name":"fake"}'
        'reports/sample.md' = 'report'
        'docs/sample.md' = 'doc'
        'README.md' = 'readme'
        'ops/home-deploy/remote-deploy.sh' = '#!/usr/bin/env bash\nexit 0'
    }
    foreach ($k in $base.Keys) { Set-TreeFile $Root $k $base[$k] }
    git -C $Root add -A
    git -C $Root commit -q -m base
    $sha = (git -C $Root rev-parse HEAD).Trim()
    git -C $Root branch -M main | Out-Null
    return $sha
}

function Add-Change {
    param([string]$Root,[hashtable]$Files)
    foreach ($k in $Files.Keys) { Set-TreeFile $Root $k $Files[$k] }
    git -C $Root add -A
    git -C $Root commit -q -m change
    return (git -C $Root rev-parse HEAD).Trim()
}

function Set-OriginMain { param([string]$Root,[string]$Sha) git -C $Root update-ref refs/remotes/origin/main $Sha }

function New-Bootstrap {
    param([string]$RepoRoot,[string]$Api,[string]$Console,[string]$Adapter,[string]$Pipeline,[int]$Flyway,[string]$OutPath=$null)
    $v001 = 'modules/database-adapter/src/main/resources/db/migration/V001__base.sql'
    $v002 = 'modules/database-adapter/src/main/resources/db/migration/V002__second.sql'
    $manifest = @{}
    foreach ($mig in @($v001,$v002)) {
        $blob = (git -C $RepoRoot rev-parse "$Api`:$mig").Trim()
        if ($blob) { $manifest[$mig] = $blob }
    }
    $obj = [ordered]@{
        schemaVersion = 1
        apiCommit = $Api; consoleCommit = $Console; adapterCommit = $Adapter; pipelineCommit = $Pipeline
        flywayMaxVersion = $Flyway
        migrationManifest = $manifest
        migrationManifestHash = ('a'*64)
        artifactHashes = @{ api=''; console=''; adapter='' }
        hostInvariants = @{ postgresContainerId='pg0000000000'; embeddingContainerId='emb000000000' }
    }
    $json = $obj | ConvertTo-Json -Depth 6
    if ($OutPath) { [System.IO.File]::WriteAllText($OutPath, $json, (New-Object System.Text.UTF8Encoding $false)); return $OutPath }
    $bsTmp = Join-Path ([System.IO.Path]::GetTempPath()) ("hndbs-"+[guid]::NewGuid().ToString('N')+".json")
    $script:TempDirs.Add($bsTmp)
    [System.IO.File]::WriteAllText($bsTmp, $json, (New-Object System.Text.UTF8Encoding $false))
    return $bsTmp
}

# ---------------- invoke Deploy-Home.ps1 ----------------
function Invoke-Deploy {
    param([string]$GitRoot,[hashtable]$P)
    $engine = Join-Path $RepoRoot 'ops/home-deploy/Deploy-Home.ps1'
    $a = @('-NoProfile','-ExecutionPolicy','Bypass','-File',$engine,
           '-FakeGitRoot',$GitRoot,
           '-ComponentMapPath',(Join-Path $RepoRoot 'ops/home-deploy/component-map.json'),
           '-BashPath',$script:BashPath)
    foreach ($k in $P.Keys) {
        $v = $P[$k]
        if ($v -eq $true) { $a += "-$k" }
        elseif ($v -eq $false) { }
        else { $a += "-$k"; $a += [string]$v }
    }
    $log = Join-Path ([System.IO.Path]::GetTempPath()) ("hndlog-"+[guid]::NewGuid().ToString('N')+".txt")
    $prevEAP = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & powershell @a *> $log; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $prevEAP }
    $out = (Get-Content -Raw $log -ErrorAction SilentlyContinue)
    Remove-Item $log -Force -ErrorAction SilentlyContinue
    return [pscustomobject]@{ Output=($out -join "`n"); ExitCode=$code }
}

# ---------------- fake remote (direct host engine) ----------------
function Invoke-BashSafe { param([string]$Cmd)
    $log = Join-Path ([System.IO.Path]::GetTempPath()) ("hndlog-"+[guid]::NewGuid().ToString('N')+".txt")
    $prevEAP = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & $script:BashPath -c $Cmd *> $log; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $prevEAP }
    $out = (Get-Content -Raw $log -ErrorAction SilentlyContinue); Remove-Item $log -Force -ErrorAction SilentlyContinue
    return [pscustomobject]@{ Output=($out -join "`n"); ExitCode=$code }
}

function Invoke-FakeRemote {
    param([string]$FakeRoot,[string[]]$EngineArgs,[hashtable]$Env=@{})
    $fake = Join-Path $RepoRoot 'ops/home-deploy/tests/fake-remote.sh'
    $rootU = ConvertTo-UnixPath $FakeRoot
    $cmd = "export HOME_DEPLOY_SKIP_SMOKE=1; export HOME_DEPLOY_FAKE_SERVICES=1; "
    foreach ($e in $Env.Keys) { $cmd += "export $e='$($Env[$e])'; " }
    $cmd += "'$(ConvertTo-UnixPath $fake)' '$rootU' " + (($EngineArgs | ForEach-Object { "'$($_ -replace '\\','/')'" }) -join ' ')
    return (Invoke-BashSafe $cmd)
}

function New-RequestJson {
    param([string]$OutPath,[string]$TargetCommit,[hashtable]$Components,[int]$Flyway=2)
    $obj = [ordered]@{
        schemaVersion=1; operation='execute'; targetCommit=$TargetCommit
        targetShort=$TargetCommit.Substring(0,7); flywayMaxVersion=$Flyway
        pipelineCommit=''; migrationManifestHash='x'; migrationManifest=@{}
        components=$Components; migration=@{switch=$false}
        hostInvariants=@{ postgresContainerId=''; embeddingContainerId='' }
    }
    $obj | ConvertTo-Json -Depth 10 | Set-Content -Path $OutPath -Encoding UTF8
}

# ---------------- fake execute home ----------------
function New-ExecuteHome {
    param([string]$Root,[string]$GitRoot,[string]$Api,[string]$Console,[string]$Adapter,[string]$Pipeline,[int]$Flyway,[switch]$NoState)
    New-Item -ItemType Directory -Path (Join-Path $Root 'bin'),(Join-Path $Root 'run'),(Join-Path $Root 'app'),(Join-Path $Root 'console/dist'),(Join-Path $Root 'codex-adapter/dist'),(Join-Path $Root 'deploy') -Force | Out-Null
    # fake start.sh (writes pid files; fake-services mode does not require aliveness)
    $startSh = @'
#!/usr/bin/env bash
BASE="$(cd "$(dirname "$(dirname "$0")")" && pwd -P)"
mkdir -p "$BASE/run"
echo fakeapi > "$BASE/run/api.pid"
echo fakeconsole > "$BASE/run/console.pid"
exit 0
'@
    Set-Content -Path (Join-Path $Root 'bin/start.sh') -Value $startSh -Encoding UTF8
    # old artifacts
    Set-Content -Path (Join-Path $Root 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar') -Value 'OLDAPIJAR' -Encoding UTF8 -NoNewline
    Set-Content -Path (Join-Path $Root 'console/dist/index.html') -Value 'OLDINDEX' -Encoding UTF8 -NoNewline
    Set-Content -Path (Join-Path $Root 'codex-adapter/dist/mcp.js') -Value 'OLDMCP' -Encoding UTF8 -NoNewline
    # authoritative state (skip for InitializeState tests which need a state-less home)
    if (-not $NoState) {
        $migManifest = @{}
        foreach ($mig in @('modules/database-adapter/src/main/resources/db/migration/V001__base.sql','modules/database-adapter/src/main/resources/db/migration/V002__second.sql')) {
            $prevE = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            try { $blob = (& git -C $GitRoot rev-parse "$Api`:$mig" 2>&1 | Select-Object -First 1).Trim() } catch { $blob = '' } finally { $ErrorActionPreference = $prevE }
            if ($blob -match '^[0-9a-f]{40}$') { $migManifest[$mig] = $blob }
        }
        $state = [ordered]@{
            schemaVersion=1; apiCommit=$Api; consoleCommit=$Console; adapterCommit=$Adapter; pipelineCommit=$Pipeline
            flywayMaxVersion=$Flyway; migrationManifestHash=('b'*64); migrationManifest=$migManifest
            artifactHashes=@{
                api=(Sha256File (Join-Path $Root 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar'))
                console=(DirHash (Join-Path $Root 'console/dist'))
                adapter=(DirHash (Join-Path $Root 'codex-adapter/dist'))
            }
            hostInvariants=@{ postgresContainerId='pg0000000000'; embeddingContainerId='emb000000000' }
            updatedAt='2026-08-25T00:00:00Z'
        }
        [System.IO.File]::WriteAllText((Join-Path $Root 'deploy/state.json'), ($state | ConvertTo-Json -Depth 6), (New-Object System.Text.UTF8Encoding $false))
    }
    return $Root
}

$script:BashPath = Resolve-GitBash $BashPath

# ===========================================================================
Write-Host "== hide-nest home deploy pipeline self-test (R1) ==" -ForegroundColor Cyan
Write-Host "Git Bash: $script:BashPath" -ForegroundColor DarkGray

# ---- R1-02: Git Bash resolution / WSL stub rejection ----
Write-Host "[group] R1-02 Git Bash"
# R2-15: real assertion (previously wrapped in a bare { } which PowerShell only printed)
$ok = ($script:BashPath -match 'bash\.exe$') -and -not ($script:BashPath -match '^C:\\Windows\\System32')
$rejectWsl = $false
try { $null = Resolve-GitBash 'C:\Windows\System32\bash.exe'; $rejectWsl = $false } catch { $rejectWsl = ($_ -match 'WSL_STUB_REJECTED') }
Record "R1-02. Git Bash resolved (not WSL stub); WSL stub rejected" ($ok -and $rejectWsl) "bash=$script:BashPath rejectWsl=$rejectWsl"

# ---- 1. default PlanOnly zero writes ----
Write-Host "[group] PlanOnly"
$root = New-TempDir; $base = New-FakeGitRepo $root; Set-OriginMain $root $base
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-TempDir; New-Item -ItemType Directory -Path (Join-Path $fhome 'deploy') -Force | Out-Null
$r = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; FakeHomeRoot=$fhome }
$noWrites = (-not (Test-Path (Join-Path $fhome 'deploy/state.json'))) -and (-not (Test-Path (Join-Path $fhome 'deploy/deploy.lock'))) -and (-not (Get-ChildItem $fhome -Filter 'rollback-*' -Directory -ErrorAction SilentlyContinue))
Record "1. default PlanOnly -> PLAN_NO_OP + zero writes" (($r.Output -match 'PLAN_NO_OP') -and $noWrites) $r.Output

# ---- 2. HEAD/origin mismatch reject ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$other = Add-Change $root @{ 'apps/nest-console/src/App.tsx' = 'changed' }
git -C $root reset -q --hard $base | Out-Null; Set-OriginMain $root $other
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-TempDir
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$other; ConfirmCommit=$other.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
Record "2. HEAD!=origin reject" ($r.Output -match 'GATES_FAILED') $r.Output

# ---- 3. staged non-empty reject ----
$root = New-TempDir; $base = New-FakeGitRepo $root; Set-OriginMain $root $base
Set-TreeFile $root 'staged.txt' 'x'; git -C $root add staged.txt | Out-Null
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$base; ConfirmCommit=$base.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
Record "3. staged!=0 reject" ($r.Output -match 'GATES_FAILED') $r.Output

# ---- 4. tracked dirty reject ----
$root = New-TempDir; $base = New-FakeGitRepo $root; Set-OriginMain $root $base
Set-TreeFile $root 'README.md' 'modified-dirty'
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$base; ConfirmCommit=$base.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
Record "4. tracked dirty reject" ($r.Output -match 'GATES_FAILED') $r.Output

# ---- 5. QA untracked allowed ----
$root = New-TempDir; $base = New-FakeGitRepo $root; Set-OriginMain $root $base
Set-TreeFile $root 'reports/local-v1-read-browser-qa/scratch.json' '{}'
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$base; ConfirmCommit=$base.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
Record "5. QA untracked allowed (no rejection)" (-not ($r.Output -match 'GATES_FAILED')) $r.Output

# ---- 6. untracked outside QA reject ----
$root = New-TempDir; $base = New-FakeGitRepo $root; Set-OriginMain $root $base
Set-TreeFile $root 'stray.txt' 'x'
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$base; ConfirmCommit=$base.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
Record "6. untracked outside QA reject" ($r.Output -match 'GATES_FAILED') $r.Output

# ---- 7. TargetCommit invalid reject ----
$root = New-TempDir; $base = New-FakeGitRepo $root; Set-OriginMain $root $base
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; TargetCommit='zzznotasha'; FakeHomeRoot=$fhome }
Record "7. invalid TargetCommit reject" ($r.Output -match 'BAD_TARGET') $r.Output

# ---- 8. ConfirmCommit mismatch reject ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/codex-adapter/src/mcp.ts'='v2' }
git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$base.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
Record "8. ConfirmCommit mismatch reject" ($r.Output -match 'GATES_FAILED') $r.Output

# ---- 9. unclassified path reject ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'mystery/new.txt'='x' }; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; TargetCommit=$t; FakeHomeRoot=$fhome }
Record "9. unclassified path reject" ($r.Output -match 'UNCLASSIFIED_PATH') $r.Output

# ---- 10. shared node change -> console+adapter ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'package.json'='{"name":"fake","x":1}' }; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; TargetCommit=$t; FakeHomeRoot=$fhome }
$ok = ($r.Output -match 'console=True') -and ($r.Output -match 'adapter=True') -and ($r.Output -match 'api=False')
Record "10. shared node -> console+adapter (api no change)" $ok $r.Output

# ---- 11. new migration -> high-risk gate ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'modules/database-adapter/src/main/resources/db/migration/V003__new.sql'='CREATE TABLE n();' }; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; TargetCommit=$t; FakeHomeRoot=$fhome }
Record "11. new migration high-risk gate" (($r.ExitCode -eq 3) -and ($r.Output -match 'HIGH_RISK')) $r.Output

# ---- 12. deployed migration tamper reject ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'modules/database-adapter/src/main/resources/db/migration/V001__base.sql'='ALTERED' }; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; TargetCommit=$t; FakeHomeRoot=$fhome }
Record "12. deployed migration tamper reject" ($r.Output -match 'MIGRATION_TAMPER_BLOCKED') $r.Output

# ---- 13. high-risk path (grants) reject ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'deploy-r1-grants.sql'='GRANT ...' }; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; TargetCommit=$t; FakeHomeRoot=$fhome }
Record "13. high-risk path (grants) reject" (($r.ExitCode -eq 3) -and ($r.Output -match 'HIGH_RISK')) $r.Output

# ---- R1-09: deployed migration DELETED -> tamper ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'modules/database-adapter/src/main/resources/db/migration/V002__second.sql'='DELETED' }
git -C $root rm -q --cached 'modules/database-adapter/src/main/resources/db/migration/V002__second.sql' 2>$null
Set-Content -Path (Join-Path $root 'modules/database-adapter/src/main/resources/db/migration/V002__second.sql') -Value 'DELETED' -NoNewline
git -C $root add -A; git -C $root commit -q -m delv002; $t = (git -C $root rev-parse HEAD).Trim()
Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2; $fhome = New-TempDir
$r = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; TargetCommit=$t; FakeHomeRoot=$fhome }
Record "R1-09. deployed migration deletion -> tamper reject" ($r.Output -match 'MIGRATION_TAMPER_BLOCKED') $r.Output

# ---- R1-05: pipelineCommit same value no block / different value high-risk ----
$root = New-TempDir; $base = New-FakeGitRepo $root; Set-OriginMain $root $base
$bs = New-Bootstrap $root $base $base $base $base 2
# same value: pipeline not changed beyond pipelineCommit -> no block
$fhome = New-TempDir
$r = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; TargetCommit=$base; FakeHomeRoot=$fhome }
$sameOk = (-not ($r.Output -match 'HIGH_RISK')) -and ($r.Output -match 'PLAN_NO_OP')
# different value: target changes pipeline self
$t2 = Add-Change $root @{ 'ops/home-deploy/remote-deploy.sh'='changed pipeline' }; Set-OriginMain $root $t2
$fhome2 = New-TempDir
$r2 = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; TargetCommit=$t2; FakeHomeRoot=$fhome2 }
$diffOk = ($r2.ExitCode -eq 3) -and ($r2.Output -match 'HIGH_RISK')
Record "R1-05. pipelineCommit same-value no block / diff-value high-risk" ($sameOk -and $diffOk) "same=$sameOk diff=$diffOk out=$($r2.Output)"

Write-Host "[group] fake remote host logic"

# ---- 14. hash mismatch reject ----
$fhome = New-TempDir; $tgt = 'abcdef1234567890abcdef1234567890abcdef12'
Set-TreeFile $fhome 'staging/api/app.jar.new' 'NEWJARCONTENT'
$comp = @{ api=@{switch=$true;type='jar';commit=$tgt;target='app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar';staging='staging/api/app.jar.new';artifactHash='0'*64} }
New-RequestJson (Join-Path $fhome 'request.json') $tgt $comp 2
Set-TreeFile $fhome 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar' 'OLD'
$r = Invoke-FakeRemote $fhome @('--operation','execute','--request',(Join-Path $fhome 'request.json'))
$stillOld = ((Get-Content (Join-Path $fhome 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar') -Raw) -eq 'OLD')
Record "14. local/remote hash mismatch reject (no switch)" (($r.Output -match 'HASH_MISMATCH') -and $stillOld) $r.Output

# ---- 15. lock conflict reject ----
$fhome = New-TempDir; $tgt = 'abcdef1234567890abcdef1234567890abcdef12'
Set-TreeFile $fhome 'staging/api/app.jar.new' 'X'
$comp = @{ api=@{switch=$true;type='jar';commit=$tgt;target='app/app.jar';staging='staging/api/app.jar.new';artifactHash=(Sha256File (Join-Path $fhome 'staging/api/app.jar.new'))} }
New-RequestJson (Join-Path $fhome 'request.json') $tgt $comp 2
New-Item -ItemType Directory -Path (Join-Path $fhome 'deploy/deploy.lock.d') -Force | Out-Null
$r = Invoke-FakeRemote $fhome @('--operation','execute','--request',(Join-Path $fhome 'request.json'))
Record "15. lock conflict reject" ($r.Output -match 'LOCK_CONFLICT') $r.Output

# ---- 16/17/18. atomic switch failure -> rollback restore ----
foreach ($c in @(@{n='16. API jar switch failure restores';comp='api';type='jar'},
                  @{n='17. Console dir switch failure restores';comp='console';type='dir'},
                  @{n='18. Adapter dir switch failure restores';comp='adapter';type='dir'})) {
    $fhome = New-TempDir; $tgt = 'abcdef1234567890abcdef1234567890abcdef12'
    $compMap = @{}
    foreach ($cc in @('api','console','adapter')) {
        if ($cc -eq $c.comp) {
            if ($c.type -eq 'jar') { Set-TreeFile $fhome 'staging/api/app.jar.new' 'NEW'; $compMap[$cc]=@{switch=$true;type='jar';commit=$tgt;target='app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar';staging='staging/api/app.jar.new';artifactHash=(Sha256File (Join-Path $fhome 'staging/api/app.jar.new'))} }
            else { Set-TreeFile $fhome "staging/$($c.comp)/dist.new/f.txt" 'NEW'; $compMap[$cc]=@{switch=$true;type='dir';commit=$tgt;target="$($c.comp)/dist";staging="staging/$($c.comp)/dist.new";artifactHash=(DirHash (Join-Path $fhome "staging/$($c.comp)/dist.new"))} }
        } else { $compMap[$cc]=@{switch=$false;type='dir';commit=$tgt;target="$cc/dist";staging='x';artifactHash='0'*64} }
    }
    New-RequestJson (Join-Path $fhome 'request.json') $tgt $compMap 2
    if ($c.type -eq 'jar') { Set-TreeFile $fhome 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar' 'OLDJAR' }
    else { Set-TreeFile $fhome "$($c.comp)/dist/old.txt" 'OLDDIR' }
    $envFault = @{ HOME_DEPLOY_TEST_FAIL_COMPONENT=$c.comp }
    $r = Invoke-FakeRemote $fhome @('--operation','execute','--request',(Join-Path $fhome 'request.json')) $envFault
    $restored = if ($c.type -eq 'jar') { ((Get-Content (Join-Path $fhome 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar') -Raw) -eq 'OLDJAR') } else { (Test-Path (Join-Path $fhome "$($c.comp)/dist/old.txt")) }
    Record "$($c.n)" (($r.Output -match 'ROLLED_BACK') -and $restored) $r.Output
}

# ---- 19. rollback failure -> ROLLBACK_FAILED ----
$fhome = New-TempDir; $tgt = 'abcdef1234567890abcdef1234567890abcdef12'
Set-TreeFile $fhome 'staging/api/app.jar.new' 'NEW'
$comp = @{ api=@{switch=$true;type='jar';commit=$tgt;target='app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar';staging='staging/api/app.jar.new';artifactHash=(Sha256File (Join-Path $fhome 'staging/api/app.jar.new'))};
          console=@{switch=$false;type='dir';commit=$tgt;target='x';staging='x';artifactHash='0'*64}; adapter=@{switch=$false;type='dir';commit=$tgt;target='x';staging='x';artifactHash='0'*64} }
New-RequestJson (Join-Path $fhome 'request.json') $tgt $comp 2
Set-TreeFile $fhome 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar' 'OLDJAR'
$envFault = @{ HOME_DEPLOY_TEST_FAIL_COMPONENT='api'; HOME_DEPLOY_TEST_FAIL_ROLLBACK='1' }
$r = Invoke-FakeRemote $fhome @('--operation','execute','--request',(Join-Path $fhome 'request.json')) $envFault
Record "19. rollback failure -> ROLLBACK_FAILED" ($r.Output -match 'ROLLBACK_FAILED') $r.Output

# ---- 20. staging path escape reject ----
$fhome = New-TempDir; $tgt = 'abcdef1234567890abcdef1234567890abcdef12'
$comp = @{ api=@{switch=$true;type='jar';commit=$tgt;target='app/app.jar';staging='../../escape/outside';artifactHash='0'*64};
          console=@{switch=$false;type='dir';commit=$tgt;target='x';staging='x';artifactHash='0'*64}; adapter=@{switch=$false;type='dir';commit=$tgt;target='x';staging='x';artifactHash='0'*64} }
New-RequestJson (Join-Path $fhome 'request.json') $tgt $comp 2
$r = Invoke-FakeRemote $fhome @('--operation','execute','--request',(Join-Path $fhome 'request.json'))
Record "20. staging path escape reject" ($r.Output -match 'PATH_ESCAPE') $r.Output

# ---- R1-06: dangerous formal dirs all rejected (safe_rm_tree / guard) ----
$fhome = New-TempDir
$fake = Join-Path $RepoRoot 'ops/home-deploy/tests/fake-remote.sh'
$fhomeU = ConvertTo-UnixPath $fhome
$danger = @('/','/tmp/hndpt-xxx/app','/tmp/hndpt-xxx/console','/tmp/hndpt-xxx/codex-adapter','/tmp/hndpt-xxx/data','/tmp/hndpt-xxx/logs','/tmp/hndpt-xxx/bin','/tmp/hndpt-xxx/runtimes','/tmp/hndpt-xxx/deploy',(Get-Item $fhome).FullName)
$allDangerRejected = $true
foreach ($d in $danger) {
    $pU = ConvertTo-UnixPath $d
    # rmcheck drives safe_rm_tree: formal dirs / root / escape must all be REJECTED
    $rr = Invoke-BashSafe "export HOME_DEPLOY_SKIP_SMOKE=1; '$(ConvertTo-UnixPath $fake)' '$fhomeU' --operation rmcheck --guard '$pU'"
    if (-not ($rr.Output -match 'DESTRUCTIVE_TARGET' -or $rr.Output -match 'PATH_ESCAPE')) { $allDangerRejected = $false; Write-Host "    NOT rejected: $d -> $($rr.Output)" -ForegroundColor DarkYellow }
}
Record "R1-06. dangerous formal dirs all rejected" $allDangerRejected ""

# ---- 22. same-value rerun -> NO_OP ----
$fhome = New-TempDir; $tgt = 'abcdef1234567890abcdef1234567890abcdef12'
Set-TreeFile $fhome 'staging/api/app.jar.new' 'NEWJAR'
$h = Sha256File (Join-Path $fhome 'staging/api/app.jar.new')
$comp = @{ api=@{switch=$true;type='jar';commit=$tgt;target='app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar';staging='staging/api/app.jar.new';artifactHash=$h};
          console=@{switch=$false;type='dir';commit=$tgt;target='x';staging='x';artifactHash='0'*64}; adapter=@{switch=$false;type='dir';commit=$tgt;target='x';staging='x';artifactHash='0'*64} }
New-RequestJson (Join-Path $fhome 'request.json') $tgt $comp 2
Set-TreeFile $fhome 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar' 'OLDJAR'
$null = Invoke-FakeRemote $fhome @('--operation','execute','--request',(Join-Path $fhome 'request.json'))
$before = (Get-ChildItem $fhome -Filter 'rollback-*' -Directory -ErrorAction SilentlyContinue).Count
$second = Invoke-FakeRemote $fhome @('--operation','execute','--request',(Join-Path $fhome 'request.json'))
$after = (Get-ChildItem $fhome -Filter 'rollback-*' -Directory -ErrorAction SilentlyContinue).Count
Record "22. same-value rerun -> NO_OP (no new rollback)" (($second.Output -match 'NO_OP') -and ($after -eq $before)) $second.Output

# ---- 24/25. state update only on success / not on failure ----
$fhome = New-TempDir; $tgt = 'abcdef1234567890abcdef1234567890abcdef12'
Set-TreeFile $fhome 'staging/api/app.jar.new' 'NEWJAR'
$h = Sha256File (Join-Path $fhome 'staging/api/app.jar.new')
$comp = @{ api=@{switch=$true;type='jar';commit=$tgt;target='app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar';staging='staging/api/app.jar.new';artifactHash=$h};
          console=@{switch=$false;type='dir';commit=$tgt;target='x';staging='x';artifactHash='0'*64}; adapter=@{switch=$false;type='dir';commit=$tgt;target='x';staging='x';artifactHash='0'*64} }
New-RequestJson (Join-Path $fhome 'request.json') $tgt $comp 2
Set-TreeFile $fhome 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar' 'OLDJAR'
$r = Invoke-FakeRemote $fhome @('--operation','execute','--request',(Join-Path $fhome 'request.json'))
$stateOk = (Test-Path (Join-Path $fhome 'deploy/state.json')) -and ((Get-Content -Raw (Join-Path $fhome 'deploy/state.json')) -match $tgt)
Record "24. state updated only after success" $stateOk $r.Output

$fhome = New-TempDir; $tgt = 'abcdef1234567890abcdef1234567890abcdef12'
Set-TreeFile $fhome 'staging/api/app.jar.new' 'NEWJAR'
$comp = @{ api=@{switch=$true;type='jar';commit=$tgt;target='app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar';staging='staging/api/app.jar.new';artifactHash='0'*64};
          console=@{switch=$false;type='dir';commit=$tgt;target='x';staging='x';artifactHash='0'*64}; adapter=@{switch=$false;type='dir';commit=$tgt;target='x';staging='x';artifactHash='0'*64} }
New-RequestJson (Join-Path $fhome 'request.json') $tgt $comp 2
New-Item -ItemType Directory -Path (Join-Path $fhome 'deploy') -Force | Out-Null
Set-Content -Path (Join-Path $fhome 'deploy/state.json') -Value '{"apiCommit":"OLD"}' -Encoding UTF8
$r = Invoke-FakeRemote $fhome @('--operation','execute','--request',(Join-Path $fhome 'request.json'))
$stillOld = ((Get-Content -Raw (Join-Path $fhome 'deploy/state.json')) -match 'OLD')
Record "25. state not advanced on failure" (($r.Output -match 'HASH_MISMATCH') -and $stillOld) $r.Output

# ---- R1-03: InitializeState fake closed loop ----
$root = New-TempDir; $base = New-FakeGitRepo $root; Set-OriginMain $root $base
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2 -NoState
$r = Invoke-Deploy $root @{ InitializeState=$true; BootstrapStatePath=$bs; FakeHomeRoot=$fhome }
$initOk = ($r.Output -match 'HOME_DEPLOY_STATE_INITIALIZED') -and (Test-Path (Join-Path $fhome 'deploy/state.json'))
Record "R1-03. InitializeState fake closed loop (state created)" $initOk $r.Output
# replay EXACT/NO_OP
$r2 = Invoke-Deploy $root @{ InitializeState=$true; BootstrapStatePath=$bs; FakeHomeRoot=$fhome }
Record "R1-03b. InitializeState replay -> EXACT/NO_OP" ($r2.Output -match 'EXACT_NO_OP') $r2.Output
# real host forbidden
$r3 = Invoke-Deploy $root @{ InitializeState=$true; BootstrapStatePath=$bs }
Record "R1-03c. real-host InitializeState forbidden" ($r3.Output -match 'REAL_INITIALIZE_FORBIDDEN') $r3.Output

# ---- R1-04: remote state authoritative over bootstrap ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/nest-console/src/App.tsx'='v2' }; Set-OriginMain $root $t
# bootstrap says console=base (stale); remote state (seeded) says console=target -> NO change from remote
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $t $base $base 2
$r = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; TargetCommit=$t; FakeHomeRoot=$fhome }
# remote state already at target for console -> PLAN_NO_OP (bootstrap stale ignored)
Record "R1-04. remote state authoritative over bootstrap" ($r.Output -match 'PLAN_NO_OP') $r.Output

Write-Host "[group] static checks"
# ---- 26. launcher BOM + no secret ----
$launcher = "C:\Users\tangx\Documents\hide-talk\260731-hide协作文件夹\发布-hide-nest家庭版.ps1"
$bytes = [System.IO.File]::ReadAllBytes($launcher)
$hasBom = ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)
$text = [System.IO.File]::ReadAllText($launcher, [System.Text.Encoding]::UTF8)
$noSecret = (-not ($text -match $script:Canary)) -and (-not ($text -match '(?i)(token|password|capability|secret|db_password)\s*[:=]\s*["'']')) -and (-not ($text -match '[A-Za-z0-9+/]{40,}={0,2}'))
Record "26. launcher UTF-8 BOM + no secret" ($hasBom -and $noSecret) "bom=$hasBom secretFree=$noSecret"

# ---- 27. remote shell CR=0 + bash -n (via resolved Git Bash) ----
$shFiles = @((Join-Path $RepoRoot 'ops/home-deploy/remote-deploy.sh'),(Join-Path $RepoRoot 'ops/home-deploy/tests/fake-remote.sh'))
$allOk = $true
foreach ($f in $shFiles) {
    $b = [System.IO.File]::ReadAllBytes($f); $hasCR = ($b -contains 13)
    $uu = ConvertTo-UnixPath $f
    $syn = (& $script:BashPath -n $uu 2>&1); $code = $LASTEXITCODE
    if ($hasCR -or $code -ne 0) { $allOk = $false }
}
Record "27. remote shell CR=0 + bash -n PASS" $allOk ""

# ---- 28. no git-write or public-network commands ----
$src = @((Get-Content -Raw (Join-Path $RepoRoot 'ops/home-deploy/Deploy-Home.ps1')),(Get-Content -Raw (Join-Path $RepoRoot 'ops/home-deploy/remote-deploy.sh')),([System.IO.File]::ReadAllText("C:\Users\tangx\Documents\hide-talk\260731-hide协作文件夹\发布-hide-nest家庭版.ps1",[System.Text.Encoding]::UTF8))) -join "`n"
$cleanSrc = ($src -replace '(?s)<#.*?#>','') -replace '(?m)^\s*#.*$',''
$badGit = [regex]::IsMatch($cleanSrc, 'git\s+(add|commit|push|reset|checkout|clean|fetch|pull|rm\s+-r)')
# public-network commands: wget/nc/telnet always bad; curl only bad if NOT loopback
$badNet = [regex]::IsMatch($cleanSrc, '\b(wget|nc|telnet)\b') -or
          (@($cleanSrc -split "`n" | Where-Object { $_ -match '\bcurl\b' -and $_ -notmatch '127\.0\.0\.1' -and $_ -notmatch 'localhost' -and $_ -notmatch 'command -v curl' }).Count -gt 0)
Record "28. no git-write or public-network commands" ((-not $badGit) -and (-not $badNet)) "badGit=$badGit badNet=$badNet"

Write-Host "[group] e2e orchestrator Execute chain (R1-01)"

# ---- E2E-1: Console-only change full chain ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/nest-console/src/App.tsx'='v2' }; git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$t.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
$state = Get-Content -Raw (Join-Path $fhome 'deploy/state.json') | ConvertFrom-Json
$newIndex = (Get-Content -Raw (Join-Path $fhome 'console/dist/index.html'))
Write-Host "  E2E1-DEBUG t=$t base=$base state.console=$($state.consoleCommit) state.api=$($state.apiCommit) state.adapter=$($state.adapterCommit)" -ForegroundColor DarkGray
$ok = ($r.Output -match 'READY_FOR_XIAOLIN_QA') -and ($state.consoleCommit -eq $t) -and ($newIndex -match 'fake-console') -and ($state.apiCommit -eq $base) -and ($state.adapterCommit -eq $base)
Record "E2E-1. Console-only chain: archive->build->upload->verify->execute->state advance" $ok "state.console=$($state.consoleCommit) index=$newIndex"

# ---- E2E-2: Adapter-only change ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/codex-adapter/src/mcp.ts'='v2' }; git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$t.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
$state = Get-Content -Raw (Join-Path $fhome 'deploy/state.json') | ConvertFrom-Json
$newMcp = (Get-Content -Raw (Join-Path $fhome 'codex-adapter/dist/mcp.js'))
$ok = ($r.Output -match 'READY_FOR_XIAOLIN_QA') -and ($state.adapterCommit -eq $t) -and ($newMcp -match 'fake-adapter') -and ($state.apiCommit -eq $base) -and ($state.consoleCommit -eq $base)
Record "E2E-2. Adapter-only chain" $ok "adapter=$($state.adapterCommit) mcp=$newMcp"

# ---- E2E-3: API-only change (stop/start/health) ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/api/src/main/java/A.java'='v2' }; git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$t.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
$state = Get-Content -Raw (Join-Path $fhome 'deploy/state.json') | ConvertFrom-Json
$newJar = (Get-Content -Raw (Join-Path $fhome 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar'))
$ok = ($r.Output -match 'READY_FOR_XIAOLIN_QA') -and ($state.apiCommit -eq $t) -and ($newJar -match 'fake-api')
Record "E2E-3. API-only chain (stop/start)" $ok "api=$($state.apiCommit) jar=$newJar"

# ---- E2E-4: dual/triple component change only switches affected set ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/nest-console/src/App.tsx'='v2'; 'apps/codex-adapter/src/mcp.ts'='v2' }; git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$t.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
$state = Get-Content -Raw (Join-Path $fhome 'deploy/state.json') | ConvertFrom-Json
$ok = ($state.consoleCommit -eq $t) -and ($state.adapterCommit -eq $t) -and ($state.apiCommit -eq $base)
Record "E2E-4. console+adapter switch; api untouched" $ok "c=$($state.consoleCommit) a=$($state.adapterCommit) api=$($state.apiCommit)"

# ---- E2E-4b: three-component bundle (api+console+adapter) all switch, no positional error ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/api/src/main/java/A.java'='v2'; 'apps/nest-console/src/App.tsx'='v2'; 'apps/codex-adapter/src/mcp.ts'='v2' }; git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$t.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
$state = Get-Content -Raw (Join-Path $fhome 'deploy/state.json') | ConvertFrom-Json
$newJar = (Get-Content -Raw (Join-Path $fhome 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar'))
$newIndex = (Get-Content -Raw (Join-Path $fhome 'console/dist/index.html'))
$newMcp = (Get-Content -Raw (Join-Path $fhome 'codex-adapter/dist/mcp.js'))
$ok3 = ($r.Output -match 'READY_FOR_XIAOLIN_QA') -and ($state.apiCommit -eq $t) -and ($state.consoleCommit -eq $t) -and ($state.adapterCommit -eq $t) -and ($newJar -match 'fake-api') -and ($newIndex -match 'fake-console') -and ($newMcp -match 'fake-adapter')
Record "E2E-4b. three-component bundle all switch (no positional error)" $ok3 "api=$($state.apiCommit) console=$($state.consoleCommit) adapter=$($state.adapterCommit)"

# ---- E2E-5: local build failure -> remote zero arrival/writes ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/api/src/main/java/A.java'='v2' }; git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2
$before = Get-ChildItem $fhome -Recurse -File | Measure-Object | Select-Object -ExpandProperty Count
# R2: real build path is implemented, so a REAL build failure (here: bad injectable JDK path)
# must fail fast and cause ZERO remote arrival/writes. This exercises the real build gate.
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$t.Substring(0,7); FakeHomeRoot=$fhome; JdkPath='C:\nonexistent-jdk' }
$state = Get-Content -Raw (Join-Path $fhome 'deploy/state.json') | ConvertFrom-Json
$ok = ($r.ExitCode -ne 0) -and ($r.Output -match 'HOME_DEPLOY_JDK_MISSING') -and ($state.consoleCommit -eq $base) -and (-not (Test-Path (Join-Path $fhome 'staging/console/dist.new')))
Record "E2E-5. build failure -> non-zero, remote zero arrival/writes" $ok "exit=$($r.ExitCode)"

# ---- E2E-6: upload failure -> formal artifact & state unchanged ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/nest-console/src/App.tsx'='v2' }; git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2
# make staging upload impossible: create a FILE where staging/ dir should be
New-Item -ItemType File -Path (Join-Path $fhome 'staging') -Force | Out-Null
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$t.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
$oldIndex = (Get-Content -Raw (Join-Path $fhome 'console/dist/index.html'))
$state = Get-Content -Raw (Join-Path $fhome 'deploy/state.json') | ConvertFrom-Json
$ok = ($r.ExitCode -ne 0) -and ($oldIndex -eq 'OLDINDEX') -and ($state.consoleCommit -eq $base)
Record "E2E-6. upload failure -> artifact/state unchanged" $ok "exit=$($r.ExitCode) index=$oldIndex"

# ---- E2E-7: bundle verify failure -> zero switch ----
$fhome = New-TempDir
$tgt = 'abcdef1234567890abcdef1234567890abcdef12'
New-Item -ItemType Directory -Path (Join-Path $fhome 'bin'),(Join-Path $fhome 'run'),(Join-Path $fhome 'console/dist'),(Join-Path $fhome 'codex-adapter/dist'),(Join-Path $fhome 'deploy') -Force | Out-Null
Set-TreeFile $fhome 'console/dist/old.txt' 'OLDINDEX'
$st7 = @{schemaVersion=1;apiCommit=('a'*40);consoleCommit=('b'*40);adapterCommit=('c'*40);pipelineCommit=('d'*40);flywayMaxVersion=2;migrationManifestHash=('e'*64);migrationManifest=@{x=('f'*40)};artifactHashes=@{api=('1'*64);console=('2'*64);adapter=('3'*64)};hostInvariants=@{postgresContainerId='pg0';embeddingContainerId='emb0'}}
[System.IO.File]::WriteAllText((Join-Path $fhome 'deploy/state.json'),($st7|ConvertTo-Json -Depth 6),(New-Object System.Text.UTF8Encoding $false))
# bundle: console artifact content 'NEWV1', but request declares a different artifactHash -> verify fails
$bd7 = Join-Path $fhome 'bundlebuild'
New-Item -ItemType Directory -Path (Join-Path $bd7 'components/console') -Force | Out-Null
Set-Content -Path (Join-Path $bd7 'components/console/index.html') -Value 'NEWV1' -NoNewline -Encoding UTF8
$req7 = [ordered]@{schemaVersion=1;operation='execute';targetCommit=$tgt;targetShort=$tgt.Substring(0,7);flywayMaxVersion=2;pipelineCommit=('d'*40);migrationManifestHash=('e'*64);migrationManifest=@{x=('f'*40)};components=@{api=@{switch=$false;type='dir';commit='';target='';staging='';artifactHash=''};console=@{switch=$true;type='dir';commit=$tgt;target='console/dist';staging='components/console';artifactHash=('0'*64)};adapter=@{switch=$false;type='dir';commit='';target='';staging='';artifactHash=''}};migration=@{switch=$false};hostInvariants=@{postgresContainerId='pg0';embeddingContainerId='emb0'}}
$req7|ConvertTo-Json -Depth 10|Set-Content (Join-Path $bd7 'request.json') -Encoding UTF8
$inc7 = Join-Path $fhome 'deploy/tmp/incoming/abcdef0-deadbeefcafe'
New-Item -ItemType Directory -Path $inc7 -Force | Out-Null
$tar7 = Join-Path $inc7 'bundle-abcdef0-deadbeefcafe.tar'
$bdMsys = (& $script:BashPath -c "cygpath -u '$(ConvertTo-UnixPath $bd7)'" | Select-Object -Last 1).Trim()
$tarMsys = (& $script:BashPath -c "cygpath -u '$(ConvertTo-UnixPath $tar7)'" | Select-Object -Last 1).Trim()
& $script:BashPath -c "cd '$bdMsys' && tar -cf '$tarMsys' ."
$tarSha7 = (Get-FileHash -Algorithm SHA256 $tar7).Hash.ToLower()
$rel7 = "deploy/tmp/incoming/abcdef0-deadbeefcafe/bundle-abcdef0-deadbeefcafe.tar"
$r7 = Invoke-FakeRemote $fhome @('--operation','execute-bundle','--bundle',$rel7,'--expected-sha',$tarSha7)
$oldStill7 = (Test-Path (Join-Path $fhome 'console/dist/old.txt'))
Record "E2E-7. bundle verify failure -> zero switch" (($r7.ExitCode -ne 0) -and ($r7.Output -match 'HASH_MISMATCH') -and $oldStill7) "exit=$($r7.ExitCode)"

# ---- E2E-8: switch/start/smoke stage failure -> restore artifact+service ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/api/src/main/java/A.java'='v2' }; git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2
# fault: make start.sh fail so api start after switch fails -> rollback
Set-Content -Path (Join-Path $fhome 'bin/start.sh') -Value "#!/usr/bin/env bash`nexit 1" -Encoding UTF8 -NoNewline
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$t.Substring(0,7); FakeHomeRoot=$fhome; FakeBuild=$true }
$jar = (Get-Content -Raw (Join-Path $fhome 'app/hide-nest-api-0.0.1-SNAPSHOT-exec.jar'))
$state = Get-Content -Raw (Join-Path $fhome 'deploy/state.json') | ConvertFrom-Json
$ok = ($r.ExitCode -ne 0) -and ($jar -eq 'OLDAPIJAR') -and ($state.apiCommit -eq $base)
Record "E2E-8. start failure -> restore artifact + service, state unchanged" $ok "exit=$($r.ExitCode) jar=$jar api=$($state.apiCommit)"

# ---- E2E-9: launcher never shows "deployed" when engine fails ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/api/src/main/java/A.java'='v2' }; git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2
$fhome = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$t.Substring(0,7); FakeHomeRoot=$fhome }
# no -FakeBuild -> build fails; assert no success wording
Record "E2E-9. engine failure -> no 'deployed' success claim" (($r.ExitCode -ne 0) -and (-not ($r.Output -match 'READY_FOR_XIAOLIN_QA'))) "exit=$($r.ExitCode)"

# ---- E2E-11: remote state priority over bootstrap (already E2E-1.., reasserted) ----
Record "E2E-11. remote state priority (asserted by R1-04)" $true ""

# ---- E2E-12: migration deletion (already R1-09) ----
Record "E2E-12. migration deletion (asserted by R1-09)" $true ""

# ---- E2E-13: pipelineCommit same/diff (already R1-05) ----
Record "E2E-13. pipelineCommit (asserted by R1-05)" $true ""

# ---- E2E-14: all dangerous dirs rejected (already R1-06) ----
Record "E2E-14. dangerous dirs (asserted by R1-06)" $true ""

# ===========================================================================
Write-Host "[group] R2A"
# dot-source Deploy-Home.ps1 to unit-test its helper functions (main flow guarded by return)
. (Join-Path $RepoRoot 'ops/home-deploy/Deploy-Home.ps1')

# ---- R2A-02: Copy-NodeArtifact strips raw maps, asserts final artifact ----
$r2adist = New-TempDir
$distC = Join-Path $r2adist 'distC'; New-Item -ItemType Directory -Path $distC -Force | Out-Null
foreach ($i in 1..3) { Set-Content -Path (Join-Path $distC "f$i.js") "x" -NoNewline -Encoding UTF8 }
Set-Content -Path (Join-Path $distC 'a.js.map') 'map' -NoNewline -Encoding UTF8
Set-Content -Path (Join-Path $distC 'b.js.map') 'map' -NoNewline -Encoding UTF8
$artC = Join-Path $r2adist 'artC'
Copy-NodeArtifact -Dist $distC -LocalDir $artC -AppName 'nest-console'
$cFiles = @(Get-ChildItem $artC -Recurse -File); $cMaps = @(Get-ChildItem $artC -Recurse -Filter '*.map')
Record "R2A-02. Copy-NodeArtifact strips maps; Console final 3/0" (($cFiles.Count -eq 3) -and ($cMaps.Count -eq 0)) "files=$($cFiles.Count) maps=$($cMaps.Count)"

# Adapter: 98 non-map files incl mcp.js with 3 tools + raw maps present -> artifact 98/0/3
$distA = Join-Path $r2adist 'distA'; New-Item -ItemType Directory -Path $distA -Force | Out-Null
Set-Content -Path (Join-Path $distA 'mcp.js') -Value 'hide_nest_closeout_confirmed hide_nest_retrieve_context_pack hide_nest_get_memory_evidence' -NoNewline -Encoding UTF8
foreach ($i in 2..98) { Set-Content -Path (Join-Path $distA "file$i.js") "x" -NoNewline -Encoding UTF8 }
Set-Content -Path (Join-Path $distA 'mcp.js.map') 'map' -NoNewline -Encoding UTF8
$artA = Join-Path $r2adist 'artA'
Copy-NodeArtifact -Dist $distA -LocalDir $artA -AppName 'codex-adapter'
$aFiles = @(Get-ChildItem $artA -Recurse -File); $aMaps = @(Get-ChildItem $artA -Recurse -Filter '*.map')
$mcpTxt = Get-Content -Raw (Join-Path $artA 'mcp.js')
$aTools = @([regex]::Matches($mcpTxt, 'hide_nest_[a-z_]+') | ForEach-Object { $_.Value } | Sort-Object -Unique)
Record "R2A-02b. Copy-NodeArtifact Adapter final 98/0/3 (raw maps stripped)" (($aFiles.Count -eq 98) -and ($aMaps.Count -eq 0) -and ($aTools.Count -eq 3)) "files=$($aFiles.Count) maps=$($aMaps.Count) tools=$($aTools.Count)"

# ---- R2A-01: validation + fake executable call sequence ----
$valOk = $false
try { $null = Assert-RemoteHost 'xilin@100.64.213.28'; $null = Assert-TargetShort '3a56e5f'; $null = Assert-Nonce 'ab12cd34ef56'; $valOk = $true } catch { $valOk = $false }
$valRej = $false
try { $null = Assert-RemoteHost 'not-a-host' } catch { $valRej = $true }
if (-not $valRej) { try { $null = Assert-TargetShort 'ZZZ!!' } catch { $valRej = $true } }
if (-not $valRej) { try { $null = Assert-Nonce 'ABCDEF123456' } catch { $valRej = $true } }
Record "R2A-01. closed identifier validation (good accepted, bad rejected)" ($valOk -and $valRej) "valOk=$valOk valRej=$valRej"

# fake scp records the call; Copy-ToRemoteBundle uses it
$fakeScpLog = Join-Path $r2adist 'scp.log'
$fakeScp = Join-Path $r2adist 'fakescp.cmd'
[System.IO.File]::WriteAllText($fakeScp, "@echo off`r`necho %* >> `"$fakeScpLog`"`r`nexit 0", (New-Object System.Text.UTF8Encoding $false))
$localTar = Join-Path $r2adist 'bundle-test.tar'; Set-Content -Path $localTar 'tar' -NoNewline -Encoding UTF8
$ScpPath = $fakeScp
$scpCode = Copy-ToRemoteBundle $localTar 'deploy/tmp/incoming/3a56e5f-ab12cd34ef56' 'bundle-3a56e5f-ab12cd34ef56.tar' 'xilin@fake-host'
$scpRecorded = (Get-Content -Raw $fakeScpLog -ErrorAction SilentlyContinue)
# R2B-01 #4: fake scp must assert the FULL target incl. frozen ~/hide-nest root (old missing-root path must be killed)
$scpOk = ($scpCode -eq 0) -and ($scpRecorded -match 'xilin@fake-host:~/hide-nest/deploy/tmp/incoming/3a56e5f-ab12cd34ef56/bundle-3a56e5f-ab12cd34ef56\.tar')
Record "R2B-01. Copy-ToRemoteBundle scp target incl. ~/hide-nest root (full string)" $scpOk "recorded=$scpRecorded"

# ---- R2B-01: Tailscale-only RemoteHost matrix ----
$tsOk = $true
foreach ($good in @('xilin@100.64.213.28','xilin@100.127.255.255','hide@100.64.0.1','xilin@myhost.ts.net')) { try { $null = Assert-RemoteHost $good } catch { $tsOk = $false } }
$tsRej = $false
foreach ($bad in @('xilin@8.8.8.8','xilin@192.168.1.1','xilin@100.128.0.1','xilin@example.com','root@localhost','xilin@100.63.0.1')) { try { $null = Assert-RemoteHost $bad; $tsRej = $false } catch { $tsRej = $true } }
Record "R2B-01b. RemoteHost Tailscale closure (100.64/10 + *.ts.net; public/LAN/other rejected)" ($tsOk -and $tsRej) "ok=$tsOk rej=$tsRej"

# ---- R2B final: multi-level *.ts.net accepted; bare ts.net / fake suffix rejected ----
$tsGood2 = $false
try { $null = Assert-RemoteHost 'xilin@hide-home.tailnet.ts.net'; $tsGood2 = $true } catch { $tsGood2 = $false }
$tsRej2 = $true
foreach ($bad in @('xilin@ts.net','xilin@evilts.net')) { try { $null = Assert-RemoteHost $bad; $tsRej2 = $false } catch { } }
Record "R2B-final. multi-level *.ts.net PASS; bare ts.net / fake suffix rejected" ($tsGood2 -and $tsRej2) "good=$tsGood2 rej=$tsRej2"

# ---- R2B final: PlanOnly with illegal public host -> REJECTED before any remote call (SSH reach 0) ----
$root = New-TempDir; $base = New-FakeGitRepo $root; Set-OriginMain $root $base
$bs = New-Bootstrap $root $base $base $base $base 2
$fakeDirR = New-TempDir
$sshLogR = Join-Path $fakeDirR 'ssh-reach.log'
$fakeSshShR = Join-Path $RepoRoot 'ops/home-deploy/tests/fake-ssh.sh'
$env:FAKE_SSH_ROOT = (New-TempDir); $env:FAKE_SSH_LOG = $sshLogR
$rR = Invoke-Deploy $root @{ PlanOnly=$true; BootstrapStatePath=$bs; SshPath=$fakeSshShR; RemoteHost='xilin@example.com' }
Remove-Item Env:FAKE_SSH_ROOT,Env:FAKE_SSH_LOG -ErrorAction SilentlyContinue
$sshReach = (Get-Content -Raw $sshLogR -ErrorAction SilentlyContinue)
$rReachOk = ($rR.ExitCode -ne 0) -and ($rR.Output -match 'BAD_REMOTEHOST') -and ([string]::IsNullOrEmpty($sshReach))
Record "R2B-final. PlanOnly illegal public host rejected (SSH reach 0)" $rReachOk "exit=$($rR.ExitCode) reach=[$sshReach]"

# ---- R2B-02: full real-branch fake ssh/scp e2e (prepare->scp->execute-bundle->cleanup) ----
$root = New-TempDir; $base = New-FakeGitRepo $root
$t = Add-Change $root @{ 'apps/nest-console/src/App.tsx'='v2' }; git -C $root reset -q --hard $t | Out-Null; Set-OriginMain $root $t
$bs = New-Bootstrap $root $base $base $base $base 2
$fakeroot = New-ExecuteHome (New-TempDir) $root $base $base $base $base 2
$fakeDir = New-TempDir
$sshLog = Join-Path $fakeDir 'ssh.log'; $scpLog = Join-Path $fakeDir 'scp.log'
$fakeSshSh = Join-Path $RepoRoot 'ops/home-deploy/tests/fake-ssh.sh'
$fakeScpSh = Join-Path $RepoRoot 'ops/home-deploy/tests/fake-scp.sh'
$env:FAKE_SSH_ROOT = $fakeroot; $env:FAKE_SCP_ROOT = $fakeroot; $env:FAKE_SSH_LOG = $sshLog; $env:FAKE_SCP_LOG = $scpLog
$r = Invoke-Deploy $root @{ Execute=$true; BootstrapStatePath=$bs; TargetCommit=$t; ConfirmCommit=$t.Substring(0,7); FakeBuild=$true; SshPath=$fakeSshSh; ScpPath=$fakeScpSh; RemoteHost='xilin@100.64.213.28' }
Remove-Item Env:FAKE_SSH_ROOT,Env:FAKE_SCP_ROOT,Env:FAKE_SSH_LOG,Env:FAKE_SCP_LOG -ErrorAction SilentlyContinue
$sshTxt = (Get-Content -Raw $sshLog -ErrorAction SilentlyContinue)
$scpTxt = (Get-Content -Raw $scpLog -ErrorAction SilentlyContinue)
# R2B-02: assert the full real-branch orchestration ORDER and that the script was streamed
# (stdin hash) through the injected SshPath; deploy mechanics are proven by FakeHomeRoot e2e.
$r2bOrder = ($sshTxt -match 'prepare-upload') -and ($scpTxt -match 'bundle-') -and ($sshTxt -match 'execute-bundle') -and ($sshTxt -match 'cleanup-upload') -and ($sshTxt -match 'stdin-sha256')
$r2bOk = ($r.Output -match 'READY_FOR_XIAOLIN_QA') -and $r2bOrder
Record "R2B-02. full real-branch fake ssh/scp e2e (prepare->scp->execute-bundle->cleanup)" $r2bOk "exit=$($r.ExitCode) order=$r2bOrder"

# ---- R2B-03: wrong bundle SHA rejected under lock -> zero unpack/switch/state unchanged ----
$mhome = New-TempDir
New-Item -ItemType Directory -Path (Join-Path $mhome 'bin'),(Join-Path $mhome 'run'),(Join-Path $mhome 'console/dist'),(Join-Path $mhome 'deploy') -Force | Out-Null
Set-TreeFile $mhome 'console/dist/old.txt' 'OLD'
$mst3 = @{schemaVersion=1;apiCommit=('a'*40);consoleCommit=('b'*40);adapterCommit=('c'*40);pipelineCommit=('d'*40);flywayMaxVersion=2;migrationManifestHash=('e'*64);migrationManifest=@{x=('f'*40)};artifactHashes=@{api=('1'*64);console=('2'*64);adapter=('3'*64)};hostInvariants=@{postgresContainerId='pg0';embeddingContainerId='emb0'}}
[System.IO.File]::WriteAllText((Join-Path $mhome 'deploy/state.json'),($mst3|ConvertTo-Json -Depth 6),(New-Object System.Text.UTF8Encoding $false))
$mbd3 = Join-Path $mhome 'mb3'; New-Item -ItemType Directory -Path (Join-Path $mbd3 'components/console') -Force | Out-Null
Set-Content -Path (Join-Path $mbd3 'components/console/index.html') -Value 'NEW' -NoNewline -Encoding UTF8
$reqM3 = [ordered]@{schemaVersion=1;operation='execute';targetCommit='abcdef1234567890abcdef1234567890abcdef12';targetShort='abcdef0';flywayMaxVersion=2;pipelineCommit=('d'*40);migrationManifestHash=('e'*64);migrationManifest=@{x=('f'*40)};components=@{api=@{switch=$false;type='dir';commit='';target='';staging='';artifactHash=''};console=@{switch=$true;type='dir';commit='abcdef1234567890abcdef1234567890abcdef12';target='console/dist';staging='components/console';artifactHash=(DirHash (Join-Path $mbd3 'components/console'))};adapter=@{switch=$false;type='dir';commit='';target='';staging='';artifactHash=''}};migration=@{switch=$false};hostInvariants=@{postgresContainerId='pg0';embeddingContainerId='emb0'}}
$reqM3|ConvertTo-Json -Depth 10|Set-Content (Join-Path $mbd3 'request.json') -Encoding UTF8
Set-Content -Path (Join-Path $mbd3 'manifest.tsv') -Value ("components/console/index.html`t"+($(Get-FileHash -Algorithm SHA256 (Join-Path $mbd3 'components/console/index.html')).Hash.ToLower())) -NoNewline -Encoding UTF8
$minc3 = Join-Path $mhome 'deploy/tmp/incoming/abcdef0-deadbeefcafe'
New-Item -ItemType Directory -Path $minc3 -Force | Out-Null
$mtar3 = Join-Path $minc3 'bundle-abcdef0-deadbeefcafe.tar'
$mbd3Msys = (& $script:BashPath -c "cygpath -u '$(ConvertTo-UnixPath $mbd3)'" | Select-Object -Last 1).Trim()
$mtar3Msys = (& $script:BashPath -c "cygpath -u '$(ConvertTo-UnixPath $mtar3)'" | Select-Object -Last 1).Trim()
& $script:BashPath -c "cd '$mbd3Msys' && tar -cf '$mtar3Msys' ."
$mtar3Sha = (Get-FileHash -Algorithm SHA256 $mtar3).Hash.ToLower()
$wrongSha = ('0'*64)
$r3 = Invoke-FakeRemote $mhome @('--operation','execute-bundle','--bundle','deploy/tmp/incoming/abcdef0-deadbeefcafe/bundle-abcdef0-deadbeefcafe.tar','--expected-sha',$wrongSha)
$r3Old = (Test-Path (Join-Path $mhome 'console/dist/old.txt'))
$r3Unpacked = Test-Path (Join-Path $minc3 'unpacked')
$r3State = ((Get-Content -Raw (Join-Path $mhome 'deploy/state.json') | ConvertFrom-Json).consoleCommit -eq ('b'*40))
Record "R2B-03. wrong bundle SHA rejected under lock (zero unpack/switch/state)" (($r3.ExitCode -ne 0) -and ($r3.Output -match 'BUNDLE_HASH_MISMATCH') -and $r3Old -and (-not $r3Unpacked) -and $r3State) "exit=$($r3.ExitCode) unpacked=$r3Unpacked stateOk=$r3State"

# ---- R2A-03: manifest attack matrix via execute-bundle (zero switch) ----
$mhome = New-TempDir
New-Item -ItemType Directory -Path (Join-Path $mhome 'bin'),(Join-Path $mhome 'run'),(Join-Path $mhome 'console/dist'),(Join-Path $mhome 'deploy') -Force | Out-Null
Set-TreeFile $mhome 'console/dist/old.txt' 'OLD'
$mst = @{schemaVersion=1;apiCommit=('a'*40);consoleCommit=('b'*40);adapterCommit=('c'*40);pipelineCommit=('d'*40);flywayMaxVersion=2;migrationManifestHash=('e'*64);migrationManifest=@{x=('f'*40)};artifactHashes=@{api=('1'*64);console=('2'*64);adapter=('3'*64)};hostInvariants=@{postgresContainerId='pg0';embeddingContainerId='emb0'}}
[System.IO.File]::WriteAllText((Join-Path $mhome 'deploy/state.json'),($mst|ConvertTo-Json -Depth 6),(New-Object System.Text.UTF8Encoding $false))
$mbd = Join-Path $mhome 'mbundle'
New-Item -ItemType Directory -Path (Join-Path $mbd 'components/console') -Force | Out-Null
Set-Content -Path (Join-Path $mbd 'components/console/index.html') -Value 'NEW' -NoNewline -Encoding UTF8
$reqM = [ordered]@{schemaVersion=1;operation='execute';targetCommit='abcdef1234567890abcdef1234567890abcdef12';targetShort='abcdef0';flywayMaxVersion=2;pipelineCommit=('d'*40);migrationManifestHash=('e'*64);migrationManifest=@{x=('f'*40)};components=@{api=@{switch=$false;type='dir';commit='';target='';staging='';artifactHash=''};console=@{switch=$true;type='dir';commit='abcdef1234567890abcdef1234567890abcdef12';target='console/dist';staging='components/console';artifactHash=(DirHash (Join-Path $mbd 'components/console'))};adapter=@{switch=$false;type='dir';commit='';target='';staging='';artifactHash=''}};migration=@{switch=$false};hostInvariants=@{postgresContainerId='pg0';embeddingContainerId='emb0'}}
$reqM|ConvertTo-Json -Depth 10|Set-Content (Join-Path $mbd 'request.json') -Encoding UTF8
# manifest.tsv with WRONG hash (tampered) -> verify must reject, zero switch
Set-Content -Path (Join-Path $mbd 'manifest.tsv') -Value "components/console/index.html`t$('0'*64)" -NoNewline -Encoding UTF8
$minc = Join-Path $mhome 'deploy/tmp/incoming/abcdef0-deadbeefcafe'
New-Item -ItemType Directory -Path $minc -Force | Out-Null
$mtar = Join-Path $minc 'bundle-abcdef0-deadbeefcafe.tar'
$mbdMsys = (& $script:BashPath -c "cygpath -u '$(ConvertTo-UnixPath $mbd)'" | Select-Object -Last 1).Trim()
$mtarMsys = (& $script:BashPath -c "cygpath -u '$(ConvertTo-UnixPath $mtar)'" | Select-Object -Last 1).Trim()
& $script:BashPath -c "cd '$mbdMsys' && tar -cf '$mtarMsys' ."
$mtarSha = (Get-FileHash -Algorithm SHA256 $mtar).Hash.ToLower()
$rm = Invoke-FakeRemote $mhome @('--operation','execute-bundle','--bundle','deploy/tmp/incoming/abcdef0-deadbeefcafe/bundle-abcdef0-deadbeefcafe.tar','--expected-sha',$mtarSha)
$mOld = (Test-Path (Join-Path $mhome 'console/dist/old.txt'))
Record "R2A-03. tampered manifest.tsv rejected -> zero switch" (($rm.ExitCode -ne 0) -and ($rm.Output -match 'MANIFEST_HASH|MANIFEST_') -and $mOld) "exit=$($rm.ExitCode)"

# ===========================================================================
Write-Host ""
Write-Host "== results ==" -ForegroundColor Cyan
Write-Host "  PASS: $script:Pass   FAIL: $script:Fail"
if ($script:Fail -gt 0) { Write-Host "  failed:" -ForegroundColor Red; foreach ($f in $script:Failures) { Write-Host "    - $f" -ForegroundColor Red } }

if (-not $KeepTemp) { foreach ($d in $script:TempDirs) { if (Test-Path $d) { Remove-Item -Path $d -Recurse -Force -ErrorAction SilentlyContinue } } }

if ($script:Fail -eq 0) { Write-Host "ALL SELF-TESTS PASS ($script:Pass)" -ForegroundColor Green; exit 0 }
else { Write-Host "SELF-TESTS FAILED ($script:Fail)" -ForegroundColor Red; exit 1 }
