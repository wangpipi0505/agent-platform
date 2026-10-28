param([string[]]$Services=@('platform-gateway','app-management','engine-runtime','skill-registry','model-gateway','monitor'),[switch]$SkipBuild,[hashtable]$ServiceEnvironmentFiles=@{})
$ErrorActionPreference='Stop'
function Read-AgentEnvironmentFile([string]$Path,[switch]$Strict){
 $values=@{}
 $lineNumber=0
 foreach($line in Get-Content -LiteralPath $Path -Encoding UTF8){
  $lineNumber++
  if($line -match '^\s*(#|$)'){continue}
  if($Strict){
   if($line -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){throw "环境文件第${lineNumber}行格式错误；必须为KEY=VALUE（不显示行内容）"}
   if($values.ContainsKey($Matches[1])){throw "环境文件含重复配置键：$($Matches[1])"}
  } elseif($line -notmatch '^([^#=]+)=(.*)$'){continue}
  $values[$Matches[1]]=$Matches[2]
 }
 return $values
}
function Get-AgentServiceEnvironmentPlan([string]$Root,[string[]]$Services,[hashtable]$Files,[hashtable]$ServiceIds,[hashtable]$GlobalEnvironment){
 $readBindings={param([string]$Text,[string]$Service)
  $bindings=@{}
  if(-not $Text){return $bindings}
  try {
   $parsed=$Text | ConvertFrom-Json -ErrorAction Stop
   if($parsed -isnot [pscustomobject] -or $Text.Trim() -notmatch '^\{.*\}$'){throw 'invalid'}
   foreach($property in $parsed.PSObject.Properties){
    if($property.Name -cnotmatch '^AGENT_SECRET_[A-Z0-9_]+$' -or $property.Name -eq 'AGENT_SECRET_BINDINGS' -or $property.Value -isnot [string] -or [string]::IsNullOrWhiteSpace($property.Value)){throw 'invalid'}
    $bindings[$property.Name]=$property.Value
   }
  } catch {throw "${Service}的AGENT_SECRET_BINDINGS必须为密钥变量名到非空租户的JSON对象（不显示值）"}
  return $bindings
 }
 if(-not $Services -or $Services.Count -eq 0){throw '至少指定一个服务'}
 foreach($service in @($Services)+@($Files.Keys)){
  if(-not $ServiceIds.ContainsKey($service)){throw "未知服务：$service"}
 }
 $raw=@{}
 $owned=@{}
 $privateNames=@{}
 $references=@{}
 foreach($service in $Files.Keys){
  $path=$Files[$service]
  if($path -isnot [string] -or $path -notmatch '^([A-Za-z]:[\\/]|\\\\)'){throw "${service}环境文件必须使用绝对路径"}
  $rootPrefix=[IO.Path]::GetFullPath($Root).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
  $fullPath=[IO.Path]::GetFullPath($path)
  if(-not $fullPath.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase)){throw "${service}环境文件必须位于本仓忽略目录"}
  $item=Get-Item -LiteralPath $fullPath -ErrorAction SilentlyContinue
  if(-not $item -or $item.PSIsContainer -or $item.PSProvider.Name -ne 'FileSystem'){throw "${service}环境文件不存在或不是普通文件"}
  # 本仓前缀不能证明链接的实际归属；路径中有重解析点就拒绝，防止越界读取秘密文件。
  $entry=$item
  while($entry -and $entry.FullName -ne $Root){
   if($entry.Attributes -band [IO.FileAttributes]::ReparsePoint){throw "${service}环境文件路径不能包含链接或重解析点"}
   $entry=if($entry -is [IO.FileInfo]){$entry.Directory}else{$entry.Parent}
  }
  $parent=Split-Path -Parent $fullPath
  & git -C $Root check-ignore --no-index --quiet -- $parent
  if($LASTEXITCODE -ne 0){throw "${service}环境文件必须位于本仓忽略目录"}
  $raw[$service]=Read-AgentEnvironmentFile $fullPath -Strict
  $owned[$service]=@{}
  $references[$service]=@{}
  foreach($key in $raw[$service].Keys){
   if($key -like 'AGENT_SECRET_*' -and $key -ne 'AGENT_SECRET_BINDINGS'){
    if($key -cnotmatch '^AGENT_SECRET_[A-Z0-9_]+$'){throw "${service}含不合法的私有密钥变量名"}
    $owned[$service][$key]=$true
   }
   foreach($reference in [regex]::Matches($raw[$service][$key],'env:([A-Za-z_][A-Za-z0-9_]*)')){
    $secretName=$reference.Groups[1].Value
    if($secretName -cnotmatch '^AGENT_SECRET_[A-Z0-9_]+$' -or $secretName -eq 'AGENT_SECRET_BINDINGS'){throw "${service}仅支持env:AGENT_SECRET_*密钥引用"}
    $owned[$service][$secretName]=$true
    $references[$service][$secretName]=$true
   }
  }
  if($raw[$service].ContainsKey('AGENT_SECRET_BINDINGS')){
   $fileBindings=& $readBindings $raw[$service]['AGENT_SECRET_BINDINGS'] $service
   foreach($name in $fileBindings.Keys){$owned[$service][$name]=$true}
  }
  foreach($name in $owned[$service].Keys){$privateNames[$name]=$true}
 }
 $base=@{}
 foreach($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()){$base[$entry.Key]=$entry.Value}
 foreach($key in $GlobalEnvironment.Keys){$base[$key]=$GlobalEnvironment[$key]}
 $overrides=@{}
 foreach($service in (@($Services)+@($Files.Keys) | Select-Object -Unique)){
  $values=@{'AGENT_SECURITY_SERVICEID'=$ServiceIds[$service]}
  if($raw.ContainsKey($service)){foreach($key in $raw[$service].Keys){$values[$key]=$raw[$service][$key]}}
  if($Files.Count -gt 0){
   # 私有密钥及指向它的继承引用必须一起隔离；只改服务文件里的同名引用仍会漏出全局引用。
   foreach($key in $base.Keys){
    if($values.ContainsKey($key)){continue}
    foreach($reference in [regex]::Matches([string]$base[$key],'env:([A-Za-z_][A-Za-z0-9_]*)')){
     $name=$reference.Groups[1].Value
     if($privateNames.ContainsKey($name) -and (-not $owned.ContainsKey($service) -or -not $owned[$service].ContainsKey($name))){$values[$key]=$null}
    }
   }
   foreach($name in $privateNames.Keys){
    if($owned.ContainsKey($service) -and $owned[$service].ContainsKey($name)){
     if(-not $values.ContainsKey($name)){$values[$name]=$base[$name]}
    } else {$values[$name]=$null}
   }
   $bindingText=if($values.ContainsKey('AGENT_SECRET_BINDINGS')){$values['AGENT_SECRET_BINDINGS']}else{$base['AGENT_SECRET_BINDINGS']}
   $bindings=& $readBindings $bindingText $service
   foreach($name in @($bindings.Keys)){
    if($privateNames.ContainsKey($name) -and (-not $owned.ContainsKey($service) -or -not $owned[$service].ContainsKey($name))){$bindings.Remove($name)}
   }
   if($references.ContainsKey($service)){
    foreach($name in $references[$service].Keys){
     if(-not $bindings.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($values[$name])){throw "${service}密钥引用缺少环境值或租户绑定：env:$name"}
    }
   }
   $values['AGENT_SECRET_BINDINGS']=$bindings | ConvertTo-Json -Compress
  }
  # 服务身份由模块决定，环境文件不能冒充其它服务。
  $values['AGENT_SECURITY_SERVICEID']=$ServiceIds[$service]
  $overrides[$service]=$values
 }
 return @{Overrides=$overrides;PrivateNames=@($privateNames.Keys);Files=$raw}
}
function Start-AgentServiceProcess([hashtable]$Environment,[hashtable]$ProcessParameters){
 $original=@{}
 foreach($name in $Environment.Keys){$original[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
 try {
  foreach($name in $Environment.Keys){[Environment]::SetEnvironmentVariable($name,$Environment[$name],'Process')}
  # Windows PowerShell 5.1 没有 Start-Process -Environment，创建后立即恢复父环境。
  Start-Process @ProcessParameters
 } finally {
  foreach($name in $original.Keys){[Environment]::SetEnvironmentVariable($name,$original[$name],'Process')}
 }
}
$agentRoot=Split-Path -Parent $PSScriptRoot
$agentPorts=@{'platform-gateway'=18080;'app-management'=18081;'engine-runtime'=18082;'skill-registry'=18083;'model-gateway'=18084;'monitor'=18085}
# 目录名是工程内的模块名；JWT 受众校验只接受设计里的六个服务 id。
$agentServiceIds=@{'platform-gateway'='gateway';'app-management'='app';'engine-runtime'='engine';'skill-registry'='skill';'model-gateway'='model';'monitor'='monitor'}
$agentGlobalEnvironment=@{}
if(Test-Path -LiteralPath (Join-Path $agentRoot '.env')){$agentGlobalEnvironment=Read-AgentEnvironmentFile (Join-Path $agentRoot '.env')}
# 所有服务文件先校验；坏配置不能触发数据库、构建或部分服务启动。
$agentEnvironmentPlan=Get-AgentServiceEnvironmentPlan $agentRoot $Services $ServiceEnvironmentFiles $agentServiceIds $agentGlobalEnvironment
$agentOriginalEnvironment=@{}
foreach($agentEntry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()){$agentOriginalEnvironment[$agentEntry.Key]=$agentEntry.Value}
try {
Set-Location -LiteralPath $agentRoot
& (Join-Path $PSScriptRoot 'start-database.ps1')
$agentGlobalEnvironment=Read-AgentEnvironmentFile (Join-Path $agentRoot '.env')
foreach($agentKey in $agentGlobalEnvironment.Keys){[Environment]::SetEnvironmentVariable($agentKey,$agentGlobalEnvironment[$agentKey],'Process')}
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
   if($ServiceEnvironmentFiles.Count -gt 0){throw "${agentService}已运行；服务环境配置需要新进程，请先通过本仓停止脚本停止后重试"}
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
 if($agentEnvironmentPlan.Files.ContainsKey($agentService)){
  Write-Output "${agentService}配置覆盖键：$(($agentEnvironmentPlan.Files[$agentService].Keys | Sort-Object) -join ', ')"
 }
 $agentProcessParameters=@{FilePath="$env:JAVA_HOME/bin/java.exe";ArgumentList=$agentArgs;WorkingDirectory=$agentRoot;WindowStyle='Hidden';RedirectStandardOutput=(Join-Path $agentLogDir "$agentService.out.log");RedirectStandardError=(Join-Path $agentLogDir "$agentService.err.log");PassThru=$true}
 $agentProcess=Start-AgentServiceProcess $agentEnvironmentPlan.Overrides[$agentService] $agentProcessParameters
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
} finally {
 # 全局.env和启动器的覆盖也必须恢复；失败退出不得污染调用者的进程环境。
 $agentCurrentEnvironment=[Environment]::GetEnvironmentVariables('Process')
 foreach($agentKey in $agentCurrentEnvironment.Keys){
  if(-not $agentOriginalEnvironment.ContainsKey($agentKey)){[Environment]::SetEnvironmentVariable($agentKey,$null,'Process')}
 }
 foreach($agentKey in $agentOriginalEnvironment.Keys){[Environment]::SetEnvironmentVariable($agentKey,$agentOriginalEnvironment[$agentKey],'Process')}
}
