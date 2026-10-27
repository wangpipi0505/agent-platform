param([string[]]$Services=@('platform-gateway','app-management','engine-runtime','skill-registry','model-gateway','monitor'),[switch]$SkipBuild)
$ErrorActionPreference='Stop'
$agentRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $agentRoot
& (Join-Path $PSScriptRoot 'start-database.ps1')
Get-Content -LiteralPath (Join-Path $agentRoot '.env') | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$env:AGENT_ROOT=$agentRoot
# 本脚本只服务本机隔离开发环境；源码默认要求显式安全模式，缺省时补 dev-hmac。
# 每个服务还必须声明自己的 service id，否则 ServiceTokens 校验会以 SERVICE_ID_INVALID 拒绝启动。
if(-not $env:AGENT_SECURITY_MODE){$env:AGENT_SECURITY_MODE='dev-hmac'}
# Git Bash/WSL 常同时注入 NO_PROXY 与 no_proxy，Windows 环境块大小写不敏感，
# PowerShell 5.1 的 Start-Process 枚举时会因重复键失败。这里收敛为单个大写变量，
# 既保留代理语义，又让后续 Start-Process 能正常创建 Java 进程。
foreach($agentProxy in 'NO_PROXY','HTTP_PROXY','HTTPS_PROXY'){
 $agentProxyValue=[Environment]::GetEnvironmentVariable($agentProxy,'Process')
 Remove-Item -LiteralPath "Env:$agentProxy" -ErrorAction SilentlyContinue
 Remove-Item -LiteralPath "Env:$($agentProxy.ToLowerInvariant())" -ErrorAction SilentlyContinue
 if($agentProxyValue){[Environment]::SetEnvironmentVariable($agentProxy,$agentProxyValue,'Process')}
}
$env:JAVA_HOME=if($env:AGENT_JAVA_HOME){$env:AGENT_JAVA_HOME}else{'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'}
if(-not(Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")){throw '请配置Java21；不修改全局JDK配置'}
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$agentPorts=@{'platform-gateway'=18080;'app-management'=18081;'engine-runtime'=18082;'skill-registry'=18083;'model-gateway'=18084;'monitor'=18085}
# 目录名是工程内的模块名；JWT 受众校验只接受设计里的六个服务 id。
$agentServiceIds=@{'platform-gateway'='gateway';'app-management'='app';'engine-runtime'='engine';'skill-registry'='skill';'model-gateway'='model';'monitor'='monitor'}
$agentLogDir=Join-Path $agentRoot '.local/logs'
New-Item -ItemType Directory -Path $agentLogDir -Force | Out-Null
$agentTmp='V:/.local/tmp'
New-Item -ItemType Directory -Path $agentTmp -Force | Out-Null
if(-not $SkipBuild){
 & mvn.cmd -B -ntp -pl ($Services -join ',') -am package -DskipTests "-Dmaven.repo.local=$agentRoot/.tools/m2"
 if($LASTEXITCODE -ne 0){throw '新工程构建失败，未启动新进程'}
}
$agentProcesses=@()
foreach($agentService in $Services){
 if(-not $agentPorts.ContainsKey($agentService)){throw "未知服务：$agentService"}
 $agentPort=$agentPorts[$agentService]
 $agentJar=Join-Path $agentRoot "$agentService/target/$agentService-0.1.0-SNAPSHOT.jar"
 if(-not(Test-Path -LiteralPath $agentJar)){throw "缺少构建产物：$agentService"}
 $agentListener=Get-NetTCPConnection -LocalPort $agentPort -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
 if($agentListener){
  $agentExisting=Get-CimInstance Win32_Process -Filter "ProcessId=$($agentListener.OwningProcess)" -ErrorAction SilentlyContinue
  if($agentExisting -and $agentExisting.Name -eq 'java.exe' -and $agentExisting.CommandLine.Contains($agentJar)){
   $agentProcesses+=[pscustomobject]@{service=$agentService;pid=$agentExisting.ProcessId;port=$agentPort;jar=$agentJar}
   Write-Output "$agentService：复用已运行的本工程进程，PID=$($agentExisting.ProcessId)，端口=$agentPort"
  } else {
   Write-Output "$agentService：端口${agentPort}被其他进程占用，未接管或停止"
  }
  continue
 }
 # Windows NIO的AF_UNIX唤醒管道优先读取专用属性，而非java.io.tmpdir。
 # 仅对本工程进程设置，修复短TEMP路径造成的connect失败，不改系统环境。
 $agentArgs=@("-Djava.io.tmpdir=$agentTmp","-Djdk.net.unixdomain.tmpdir=$agentTmp",'-Xms64m','-Xmx384m','-XX:+UseZGC','-XX:+ZGenerational','-XX:MaxDirectMemorySize=128m','-jar',('"'+$agentJar+'"'))
 $env:AGENT_SECURITY_SERVICEID=$agentServiceIds[$agentService]
 $agentProcess=Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList $agentArgs -WorkingDirectory $agentRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $agentLogDir "$agentService.out.log") -RedirectStandardError (Join-Path $agentLogDir "$agentService.err.log") -PassThru
 $agentProcesses+=[pscustomobject]@{service=$agentService;pid=$agentProcess.Id;port=$agentPort;jar=$agentJar}
 Write-Output "$agentService 已启动，PID=$($agentProcess.Id)，端口=$agentPort"
}
$agentPidFile=Join-Path $agentRoot '.local/services.json'
$agentPrior=@()
if(Test-Path -LiteralPath $agentPidFile){
 try {
  $agentParsed=Get-Content -LiteralPath $agentPidFile -Raw | ConvertFrom-Json
  $agentPrior=@($agentParsed | Where-Object { $_.service -and $_.pid })
 } catch { Write-Warning "忽略无法解析的服务清单：$($_.Exception.Message)" }
}
# 去重后按服务名写入；旧清单可能是嵌套数组（PowerShell ConvertTo-Json 的历史事故），
# 这里只接受带 service/pid 的扁平对象，避免再次把 Count/value 包装写回。
$agentMerged=@{}
foreach($agentEntry in @($agentPrior)+@($agentProcesses)){ if($agentEntry.service){$agentMerged[[string]$agentEntry.service]=$agentEntry} }
@($agentMerged.Values | Sort-Object service) | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $agentPidFile -Encoding utf8
