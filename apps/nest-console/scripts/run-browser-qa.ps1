# ===========================================================================
# Local V1 生产 React 浏览器 QA 一键脚本
# ---------------------------------------------------------------------------
# 职责：
#   1) 校验仓库/依赖（绝不安装任何东西）
#   2) 清理仅本 QA 输出目录
#   3) production build
#   4) 解析浏览器（-BrowserPath 或本地 Playwright cache → Edge → Chrome，绝不下载）
#   5) 启动 vite preview（仅绑定 127.0.0.1）
#   6) 调用仓库本地 Playwright CLI
#   7) 无论成功失败都清理它启动的 Vite preview 进程
#   8) Playwright 非零时自身非零退出
# ===========================================================================
param(
  [Parameter(Position = 0)]
  [string]$BrowserPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# 路径与常量
# ---------------------------------------------------------------------------
$ScriptDir   = $PSScriptRoot
$ConsoleDir  = (Resolve-Path (Join-Path $ScriptDir '..')).Path
$RepoRoot    = (Resolve-Path (Join-Path $ScriptDir '..\..\..')).Path
$QaDir       = Join-Path $RepoRoot 'reports\local-v1-read-browser-qa'
$PreviewHost = '127.0.0.1'
$PreviewPort = 4173
$BaseUrl     = "http://${PreviewHost}:${PreviewPort}"

function Fail([string]$Message, [int]$Code = 1) {
  [Console]::Error.WriteLine($Message)
  exit $Code
}

# ---------------------------------------------------------------------------
# 1) 校验仓库与依赖存在（禁止安装）
# ---------------------------------------------------------------------------
if (-not (Test-Path (Join-Path $RepoRoot 'package.json'))) {
  Fail "仓库根 package.json 不存在: $RepoRoot" 2
}
if (-not (Test-Path (Join-Path $ConsoleDir 'package.json'))) {
  Fail "nest-console package.json 不存在: $ConsoleDir" 2
}
$PlaywrightCli = Join-Path $RepoRoot 'node_modules\@playwright\test\cli.js'
if (-not (Test-Path $PlaywrightCli)) {
  Fail "未找到 Playwright CLI: $PlaywrightCli（禁止安装依赖）" 2
}
$AxePkg = Join-Path $RepoRoot 'node_modules\@axe-core\playwright'
if (-not (Test-Path $AxePkg)) {
  Fail "未找到 @axe-core/playwright: $AxePkg（禁止安装依赖）" 2
}
$ViteBin = Join-Path $RepoRoot 'node_modules\vite\bin\vite.js'
if (-not (Test-Path $ViteBin)) {
  Fail "未找到 vite: $ViteBin（禁止安装依赖）" 2
}

# ---------------------------------------------------------------------------
# 2) 清理仅本 QA 输出目录（不删除其他报告）
# ---------------------------------------------------------------------------
if (Test-Path $QaDir) {
  Remove-Item -Recurse -Force -Path $QaDir
}
New-Item -ItemType Directory -Force -Path $QaDir | Out-Null

$BuildOut    = Join-Path $QaDir 'build.stdout.log'
$BuildErr    = Join-Path $QaDir 'build.stderr.log'
$PreviewOut  = Join-Path $QaDir 'preview.stdout.log'
$PreviewErr  = Join-Path $QaDir 'preview.stderr.log'
$PwOut       = Join-Path $QaDir 'playwright.stdout.log'
$PwErr       = Join-Path $QaDir 'playwright.stderr.log'
$ExitCodeFile = Join-Path $QaDir 'exit-codes.log'

# ---------------------------------------------------------------------------
# 4) 浏览器解析：-BrowserPath 优先；否则 Playwright cache → Edge → Chrome
# ---------------------------------------------------------------------------
function Resolve-Browser {
  param([string]$Explicit)

  if ($Explicit) {
    if (Test-Path -LiteralPath $Explicit -PathType Leaf) {
      $env:HIDE_NEST_QA_BROWSER_EXECUTABLE = (Resolve-Path -LiteralPath $Explicit).Path
      return
    }
    throw "指定的 -BrowserPath 不存在或不是文件: $Explicit"
  }

  # 1) 本地 Playwright cache：只有找到真实可执行文件才接受。
  # 仅存在旧版本 chromium* 目录并不代表当前 Playwright 所需版本可用。
  $pwCache = Join-Path $env:LOCALAPPDATA 'ms-playwright'
  if (Test-Path $pwCache) {
    $cachedBrowser = Get-ChildItem -LiteralPath $pwCache -Recurse -File -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -eq 'chrome-headless-shell.exe' -or $_.Name -eq 'chrome.exe' } |
      Sort-Object FullName -Descending |
      Select-Object -First 1
    if ($cachedBrowser) {
      $env:HIDE_NEST_QA_BROWSER_EXECUTABLE = $cachedBrowser.FullName
      return
    }
  }

  # 2) Edge（channel: msedge）
  $pf86 = [Environment]::GetFolderPath('ProgramFilesX86')
  $pf   = [Environment]::GetFolderPath('ProgramFiles')
  $edgeCandidates = @(
    (Join-Path $pf86 'Microsoft\Edge\Application\msedge.exe'),
    (Join-Path $pf   'Microsoft\Edge\Application\msedge.exe')
  )
  foreach ($c in $edgeCandidates) {
    if (Test-Path -LiteralPath $c) {
      $env:HIDE_NEST_QA_BROWSER_EXECUTABLE = (Resolve-Path -LiteralPath $c).Path
      return
    }
  }

  # 3) Chrome（channel: chrome）
  $chromeCandidates = @(
    (Join-Path $pf86 'Google\Chrome\Application\chrome.exe'),
    (Join-Path $pf   'Google\Chrome\Application\chrome.exe')
  )
  foreach ($c in $chromeCandidates) {
    if (Test-Path -LiteralPath $c) {
      $env:HIDE_NEST_QA_BROWSER_EXECUTABLE = (Resolve-Path -LiteralPath $c).Path
      return
    }
  }

  throw "未找到任何可用 chromium 浏览器（Playwright cache / Edge / Chrome）。请用 -BrowserPath 指定浏览器可执行文件；本脚本绝不下载浏览器。"
}

# ---------------------------------------------------------------------------
# 7) 清理它启动的 Vite preview 进程（进程树 + 端口兜底）
# ---------------------------------------------------------------------------
function Stop-PreviewTree {
  if ($previewProc -and -not $previewProc.HasExited) {
    try { & taskkill.exe /PID $previewProc.Id /T /F 2>$null | Out-Null } catch { }
  }
  $listeners = Get-NetTCPConnection -LocalPort $PreviewPort -State Listen -ErrorAction SilentlyContinue
  foreach ($l in $listeners) {
    if ($l.OwningProcess) {
      try { & taskkill.exe /PID $l.OwningProcess /T /F 2>$null | Out-Null } catch { }
    }
  }
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
$previewProc = $null
$playwrightExit = 1

try {
  # 3) production build
  $build = Start-Process -FilePath $env:ComSpec -ArgumentList @('/c', 'npm', 'run', 'build') `
    -WorkingDirectory $ConsoleDir -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $BuildOut -RedirectStandardError $BuildErr
  Add-Content -Path $ExitCodeFile -Value "build=$($build.ExitCode)"
  if ($build.ExitCode -ne 0) {
    throw "production build 失败（exit $($build.ExitCode)）。日志见 $BuildOut / $BuildErr"
  }

  # 4) 解析浏览器
  Resolve-Browser -Explicit $BrowserPath

  # 5) 启动 vite preview（仅绑定 127.0.0.1）
  $previewProc = Start-Process -FilePath 'node' `
    -ArgumentList @($ViteBin, 'preview', '--host', $PreviewHost, '--port', "$PreviewPort", '--strictPort') `
    -WorkingDirectory $ConsoleDir -PassThru `
    -RedirectStandardOutput $PreviewOut -RedirectStandardError $PreviewErr

  # 等待预览就绪
  $ready = $false
  for ($i = 0; $i -lt 60; $i++) {
    if ($previewProc.HasExited) { break }
    try {
      $resp = Invoke-WebRequest -Uri $BaseUrl -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
      if ($resp.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
    Start-Sleep -Milliseconds 500
  }
  if (-not $ready) {
    throw "vite preview 未能在 $BaseUrl 就绪。日志见 $PreviewOut / $PreviewErr"
  }

  # 6) 调用仓库本地 Playwright CLI
  $config = Join-Path $ConsoleDir 'playwright.config.ts'
  $pw = Start-Process -FilePath 'node' `
    -ArgumentList @($PlaywrightCli, 'test', '--config', $config) `
    -WorkingDirectory $ConsoleDir -NoNewWindow -Wait -PassThru `
    -RedirectStandardOutput $PwOut -RedirectStandardError $PwErr
  $playwrightExit = $pw.ExitCode
  Add-Content -Path $ExitCodeFile -Value "playwright=$playwrightExit"
}
finally {
  Stop-PreviewTree
}

# 8) Playwright 非零时自身非零退出
if ($playwrightExit -ne 0) {
  [Console]::Error.WriteLine("Playwright 测试失败（exit $playwrightExit）。日志见 $PwOut / $PwErr")
  exit $playwrightExit
}

Write-Host "浏览器 QA 完成。产物目录: $QaDir"
exit 0
