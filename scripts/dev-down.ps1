param([switch]$KeepDatabase)
$ErrorActionPreference = 'Stop'
$agentRoot = Split-Path -Parent $PSScriptRoot

$webManifest = Join-Path $agentRoot '.local/web.json'
if (Test-Path -LiteralPath $webManifest) {
  $web = Get-Content -LiteralPath $webManifest -Raw | ConvertFrom-Json
  $process = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$web.pid)" -ErrorAction SilentlyContinue
  if ($process -and $process.CommandLine -match 'vite') {
    Stop-Process -Id ([int]$web.pid) -ErrorAction SilentlyContinue
    Write-Output "已停止控制台 PID=$($web.pid)"
  }
  Remove-Item -LiteralPath $webManifest -ErrorAction SilentlyContinue
}

& (Join-Path $PSScriptRoot 'stop-backend.ps1')
if (-not $KeepDatabase) {
  $dbScript = Join-Path $PSScriptRoot 'stop-database.ps1'
  if (Test-Path -LiteralPath $dbScript) { & $dbScript }
}
Write-Output '开发环境已停止（本工程登记的进程；数据库默认停止，-KeepDatabase 可保留）'
