param([string[]]$Services=@('platform-gateway','app-management','engine-runtime','skill-registry','model-gateway','monitor'),[switch]$SkipBuild)
$ErrorActionPreference='Stop'
$agentRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $agentRoot
& (Join-Path $PSScriptRoot 'start-database.ps1')
Get-Content -LiteralPath (Join-Path $agentRoot '.env') | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$env:AGENT_ROOT=$agentRoot
$env:JAVA_HOME=if($env:AGENT_JAVA_HOME){$env:AGENT_JAVA_HOME}else{'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'}
if(-not(Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")){throw '请配置Java21；不修改全局JDK配置'}
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$agentPorts=@{'platform-gateway'=18080;'app-management'=18081;'engine-runtime'=18082;'skill-registry'=18083;'model-gateway'=18084;'monitor'=18085}
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
 if(Get-NetTCPConnection -LocalPort $agentPort -State Listen -ErrorAction SilentlyContinue){Write-Output "$agentService：端口$agentPort已占用，未接管或停止现有进程";continue}
 $agentJar=Join-Path $agentRoot "$agentService/target/$agentService-0.1.0-SNAPSHOT.jar"
 if(-not(Test-Path -LiteralPath $agentJar)){throw "缺少构建产物：$agentService"}
 # Windows NIO的AF_UNIX唤醒管道优先读取专用属性，而非java.io.tmpdir。
 # 仅对本工程进程设置，修复短TEMP路径造成的connect失败，不改系统环境。
 $agentArgs=@("-Djava.io.tmpdir=$agentTmp","-Djdk.net.unixdomain.tmpdir=$agentTmp",'-Xms64m','-Xmx384m','-XX:+UseZGC','-XX:+ZGenerational','-XX:MaxDirectMemorySize=128m','-jar',('"'+$agentJar+'"'))
 $agentProcess=Start-Process -FilePath "$env:JAVA_HOME/bin/java.exe" -ArgumentList $agentArgs -WorkingDirectory $agentRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $agentLogDir "$agentService.out.log") -RedirectStandardError (Join-Path $agentLogDir "$agentService.err.log") -PassThru
 $agentProcesses+=[pscustomobject]@{service=$agentService;pid=$agentProcess.Id;port=$agentPort;jar=$agentJar}
 Write-Output "$agentService 已启动，PID=$($agentProcess.Id)，端口=$agentPort"
}
$agentPidFile=Join-Path $agentRoot '.local/services.json'
$agentPrior=@();if(Test-Path -LiteralPath $agentPidFile){$agentPrior=@(Get-Content -LiteralPath $agentPidFile -Raw | ConvertFrom-Json)}
@($agentPrior+$agentProcesses)|ConvertTo-Json|Set-Content -LiteralPath $agentPidFile -Encoding utf8
