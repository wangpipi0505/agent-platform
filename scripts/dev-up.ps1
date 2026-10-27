param(
  [string[]]$Services = @('platform-gateway','app-management','engine-runtime','skill-registry','model-gateway'),
  [switch]$WithMonitor,
  [switch]$WithWeb,
  [switch]$WithCache,
  [switch]$WithObjectStore,
  [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
# PowerShell -File 会把逗号分隔值当成单个字符串；这里统一拆开，兼容交互式数组和命令行写法。
$Services = @($Services | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Select-Object -Unique)
$agentRoot = Split-Path -Parent $PSScriptRoot
$agentEnvPath = Join-Path $agentRoot '.env'
if (-not (Test-Path -LiteralPath $agentEnvPath)) { throw '缺少 .env，请先运行 scripts/start-database.ps1 生成独立配置' }
Get-Content -LiteralPath $agentEnvPath | ForEach-Object { if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process') } }
$env:AGENT_ROOT = $agentRoot
$env:VITE_AUTH_MODE = 'dev'
$env:JAVA_HOME = if ($env:AGENT_JAVA_HOME) { $env:AGENT_JAVA_HOME } else { 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot' }
if (-not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) { throw '需要 Java 21：请设置 AGENT_JAVA_HOME 或安装到默认目录' }
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"

if ($WithMonitor -and $Services -notcontains 'monitor') { $Services += 'monitor' }
if ($WithCache) {
  try { & (Join-Path $PSScriptRoot 'start-cache.ps1') } catch { Write-Warning "缓存未启动：$($_.Exception.Message)" }
}
if ($WithObjectStore) {
  try { & (Join-Path $PSScriptRoot 'start-object-store.ps1') } catch { Write-Warning "对象存储未启动：$($_.Exception.Message)" }
}

# 默认每次启动都构建，避免旧 JAR 被静默复用；-SkipBuild 时显式检查产物是否比源码新。
$agentPorts = @{ 'platform-gateway'=18080; 'app-management'=18081; 'engine-runtime'=18082; 'skill-registry'=18083; 'model-gateway'=18084; 'monitor'=18085 }
if ($SkipBuild) {
  foreach ($service in $Services) {
    $jar = Join-Path $agentRoot "$service/target/$service-0.1.0-SNAPSHOT.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "缺少构建产物：$service；请去掉 -SkipBuild 重新构建" }
    $newestSource = Get-ChildItem -LiteralPath (Join-Path $agentRoot "$service/src/main") -Recurse -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($newestSource -and (Get-Item -LiteralPath $jar).LastWriteTime -lt $newestSource.LastWriteTime) {
      throw "构建产物落后于源码：$service（jar=$((Get-Item $jar).LastWriteTime)，source=$($newestSource.LastWriteTime)）；请重新构建或去掉 -SkipBuild"
    }
  }
}

& (Join-Path $PSScriptRoot 'start-backend.ps1') -Services $Services -SkipBuild:$SkipBuild

$deadline = (Get-Date).AddSeconds(75)
$pending = @($Services)
$failed = @{}
while ((Get-Date) -lt $deadline -and $pending.Count -gt 0) {
  Start-Sleep -Milliseconds 900
  foreach ($service in @($pending)) {
    $port = $agentPorts[$service]
    try {
      $response = Invoke-WebRequest -Uri "http://127.0.0.1:$port/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
      $statusCode = [int]$response.StatusCode
    } catch {
      # Windows PowerShell 5.1 没有 -SkipHttpErrorCheck；503 也会进入异常，需要从响应里取状态码。
      $statusCode = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    }
    # 503 表示进程已起但依赖（通常是 Redis）不可用；对“服务是否已监听”而言仍算启动成功。
    if ($statusCode -in 200, 503) {
      $pending = @($pending | Where-Object { $_ -ne $service })
      if ($statusCode -eq 503) { Write-Warning "$service 已启动，但健康检查为 503（依赖未就绪）" }
    }
  }
}

if ($pending.Count -gt 0) {
  Write-Host '以下服务未在 75 秒内就绪，输出日志尾部：' -ForegroundColor Red
  foreach ($service in $pending) {
    Write-Host "===== $service =====" -ForegroundColor Yellow
    $out = Join-Path $agentRoot ".local/logs/$service.out.log"
    $err = Join-Path $agentRoot ".local/logs/$service.err.log"
    if (Test-Path -LiteralPath $out) { Get-Content -LiteralPath $out -Tail 25 }
    if (Test-Path -LiteralPath $err) { Get-Content -LiteralPath $err -Tail 25 }
  }
  throw "服务未就绪：$($pending -join ', ')"
}

$webProcess = $null
if ($WithWeb) {
  if (Get-NetTCPConnection -LocalPort 15173 -State Listen -ErrorAction SilentlyContinue) {
    Write-Output '15173 已被占用：复用现有 Vite，不重复启动'
  } else {
    $webRoot = Join-Path $agentRoot 'web'
    $log = Join-Path $agentRoot '.local/logs/web.out.log'
    $webProcess = Start-Process -FilePath 'npm.cmd' -ArgumentList @('run','dev') -WorkingDirectory $webRoot -WindowStyle Hidden -RedirectStandardOutput $log -RedirectStandardError (Join-Path $agentRoot '.local/logs/web.err.log') -PassThru
    [pscustomobject]@{ pid=$webProcess.Id; port=15173; startedAt=(Get-Date).ToString('o') } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $agentRoot '.local/web.json') -Encoding utf8
    Write-Output "控制台已启动：PID=$($webProcess.Id)，等待 Vite 监听 15173..."
    $webDeadline = (Get-Date).AddSeconds(40)
    while ((Get-Date) -lt $webDeadline -and -not (Get-NetTCPConnection -LocalPort 15173 -State Listen -ErrorAction SilentlyContinue)) { Start-Sleep -Milliseconds 700 }
    if (-not (Get-NetTCPConnection -LocalPort 15173 -State Listen -ErrorAction SilentlyContinue)) {
      Get-Content -LiteralPath $log -Tail 30 -ErrorAction SilentlyContinue
      throw 'Vite 未在 40 秒内监听 15173，见 .local/logs/web.out.log'
    }
  }
}

Write-Host ''
Write-Host '开发环境已就绪：' -ForegroundColor Green
Write-Host "  管理台   http://127.0.0.1:15173/"
Write-Host "  网关     http://127.0.0.1:18080"
Write-Host "  模型     http://127.0.0.1:18084"
Write-Host "  登录身份 developer-v12（仅本机、仅开发模式）"
if ($Services -contains 'monitor') { Write-Host '  发布/审批/统计/审计已包含（monitor，需 Redis）' } else { Write-Host '  未启动 monitor：发布/审批/统计/审计不可用；加 -WithMonitor 启动' }
Write-Host '  停止：scripts/dev-down.ps1'
