param([Parameter(Mandatory=$true)][string]$PrivateFixture)
$ErrorActionPreference='Stop'
$evidenceRoot=$PSScriptRoot
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$packageRun=Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'runs') -Directory | Where-Object {$_.Name -like 'package-*'} | Sort-Object Name | Select-Object -Last 1
if(-not $packageRun){throw '必须先构建并记录本卡当前服务JAR'}
$package=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $packageRun.FullName 'run.json') | ConvertFrom-Json
if($package.exitCode -ne 0 -or $package.jars.Count -ne 4){throw '本卡当前JAR构建记录不完整'}
foreach($entry in $package.sourceSha256.PSObject.Properties){
 if((Get-FileHash -LiteralPath (Join-Path $repo $entry.Name) -Algorithm SHA256).Hash -ne $entry.Value){throw '生产源码在构建后变化；禁止以旧JAR验证当前源码'}
}
foreach($jar in $package.jars){if((Get-FileHash -LiteralPath $jar.path -Algorithm SHA256).Hash -ne $jar.sha256){throw 'JAR在构建记录后变化；禁止混用产物'}}
$runId='ex01-'+(Get-Date -Format yyyyMMdd-HHmmss-fff)
$run=Join-Path $PSScriptRoot ('runs/live-'+$runId)
$private=Join-Path $repo ('.local/ex01-private/'+$runId)
$databaseName=('agent_platform_'+$runId.Replace('-','_')).ToLowerInvariant()
$databaseLine='AGENT_DB_URL=jdbc:postgresql://127.0.0.1:25432/'+$databaseName
$isolatedDatabaseCreated=$false
if((Test-Path -LiteralPath $run) -or (Test-Path -LiteralPath $private)){throw '本次目录已存在，禁止覆盖既有记录或凭据文件'}
New-Item -ItemType Directory -Path $run | Out-Null
New-Item -ItemType Directory -Path $private -Force | Out-Null
$runnerHashes=@{}
foreach($name in @('run-live.ps1','ServiceConfigurationProbe.java','ConfiguredOntologyClientProbe.java','ConfiguredRateLimitProbe.java')){
 $runnerHashes[$name]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $name) -Algorithm SHA256).Hash
}
$identity=[Security.Principal.WindowsIdentity]::GetCurrent().User
$acl=Get-Acl -LiteralPath $private
$acl.SetAccessRuleProtection($true,$false)
foreach($sid in @($identity,(New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))){
 $rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
 $acl.AddAccessRule($rule)
}
Set-Acl -LiteralPath $private -AclObject $acl
try {$fixture=Get-Content -LiteralPath $PrivateFixture -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop}
catch {throw '授权凭据文件无法读取或解析；不显示文件内容'}
$base=[uri]$fixture.base
if(-not $base.IsLoopback -or $base.Port -ne 18182 -or $base.AbsolutePath -ne '/onto'){throw '本卡只允许授权的本仓本体API'}
if($fixture.payload.platformTenant -ne 'local-v12' -or $fixture.payload.environment -ne 'ex02'){throw '真实夹具租户或环境不符'}
foreach($key in @($fixture.readonly.key,$fixture.binder.key)){
 if(-not $key -or $key -match '[\r\n]'){throw '授权服务凭据缺失或格式错误；不显示值'}
}
$original=@{}
foreach($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()){$original[$entry.Key]=$entry.Value}
$source=Join-Path $repo 'scripts/start-backend.ps1'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($source,[ref]$tokens,[ref]$errors)
if($errors.Count){throw '生产启动脚本语法错误'}
foreach($name in @('Read-AgentEnvironmentFile','Get-AgentServiceEnvironmentPlan','Start-AgentServiceProcess')){
 $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
 . ([scriptblock]::Create($definition.Extent.Text))
}
$global=Read-AgentEnvironmentFile (Join-Path $repo '.env')
if($global.ContainsKey('SPRING_DATASOURCE_URL') -or $global.ContainsKey('SPRING_APPLICATION_JSON')){throw '全局.env含标准优先级覆盖；本次隔离验证不能在不改该文件的条件下准确判定'}
foreach($name in $global.Keys){[Environment]::SetEnvironmentVariable($name,$global[$name],'Process')}
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
$serviceIds=@{'app-management'='app';'engine-runtime'='engine';'skill-registry'='skill';'platform-gateway'='gateway'}
$ports=@{'app-management'=18081;'engine-runtime'=18082;'skill-registry'=18083;'platform-gateway'=18080}
$owned=@{}
$facts=New-Object 'System.Collections.Generic.List[object]'
$initialHead=(& git -C $repo rev-parse HEAD)
$environmentSha=(Get-FileHash -LiteralPath (Join-Path $repo '.env') -Algorithm SHA256).Hash
$utf8=New-Object Text.UTF8Encoding($true)
function Write-PrivateEnvironment([string]$Name,[string[]]$Lines){
 $path=Join-Path $private $Name
 [IO.File]::WriteAllLines($path,$Lines,$utf8)
 return $path
}
function Start-RegisteredServices([string[]]$Services,[hashtable]$Files){
 foreach($service in $Services){
  if(Get-NetTCPConnection -LocalPort $ports[$service] -State Listen -ErrorAction SilentlyContinue){throw "${service}端口已有监听；禁止接管"}
 }
 if($Files.Count){& $source -Services $Services -SkipBuild -ServiceEnvironmentFiles $Files}
 else {& $source -Services $Services -SkipBuild}
 $manifest=Get-Content -LiteralPath (Join-Path $repo '.local/services.json') -Raw | ConvertFrom-Json
 foreach($service in $Services){
  $entry=@($manifest | Where-Object {$_.service -eq $service}) | Select-Object -Last 1
  if(-not $entry){throw '生产启动脚本未登记PID'}
  $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)" -ErrorAction SilentlyContinue
  $jar=[IO.Path]::GetFullPath((Join-Path $repo "$service/target/$service-0.1.0-SNAPSHOT.jar"))
  if(-not $process -or $process.Name -ne 'java.exe' -or -not $process.CommandLine.Contains($jar)){throw '登记进程与本仓本次JAR不符'}
  $owned[$service]=[int]$entry.pid
  $facts.Add([pscustomobject]@{stage='registered-start';service=$service;pid=[int]$entry.pid;jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash;startedAt=$process.CreationDate.ToString('o');head=(& git -C $repo rev-parse HEAD)})
 }
}
function Stop-OwnedServices([string[]]$Services){
 $manifest=Get-Content -LiteralPath (Join-Path $repo '.local/services.json') -Raw | ConvertFrom-Json
 foreach($service in $Services){
  if(-not $owned.ContainsKey($service)){continue}
  $entry=@($manifest | Where-Object {$_.service -eq $service}) | Select-Object -Last 1
  if(-not $entry -or [int]$entry.pid -ne $owned[$service]){throw 'PID登记已改变；拒绝停止未知进程'}
  & (Join-Path $repo 'scripts/stop-backend.ps1') -Services @($service)
  $deadline=(Get-Date).AddSeconds(15)
  while((Get-Process -Id $owned[$service] -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline){Start-Sleep -Milliseconds 100}
  if(Get-Process -Id $owned[$service] -ErrorAction SilentlyContinue){throw '本卡登记进程在停止后仍未退出'}
  $owned.Remove($service)
 }
}
Add-Type -AssemblyName System.Net.Http
function Wait-Ready([string]$Service){
 $client=New-Object System.Net.Http.HttpClient
 $client.Timeout=[TimeSpan]::FromSeconds(2)
 try {
  $deadline=(Get-Date).AddSeconds(80)
  while((Get-Date) -lt $deadline){
   if(-not (Get-Process -Id $owned[$Service] -ErrorAction SilentlyContinue)){throw "${Service}在就绪前退出；只检查本仓私有日志"}
   try {
    $response=$client.GetAsync("http://127.0.0.1:$($ports[$Service])/actuator/health").GetAwaiter().GetResult()
    $status=[int]$response.StatusCode
    $response.Dispose()
    if($status -eq 200){$facts.Add([pscustomobject]@{stage='http-ready';service=$Service;httpStatus=200});return}
   } catch {}
   Start-Sleep -Milliseconds 500
  }
  throw "${Service}未在有界窗口返回健康200"
 } finally {$client.Dispose()}
}
function Invoke-PrivateProbe([string]$Stage,[string]$Service,[hashtable]$Plan,[string[]]$Arguments,[string]$SafePrefix){
 $output=Join-Path $private ($Stage+'.out.log')
 $errorLog=Join-Path $private ($Stage+'.err.log')
 $parameters=@{FilePath=$java;ArgumentList=$Arguments;WorkingDirectory=$repo;WindowStyle='Hidden';RedirectStandardOutput=$output;RedirectStandardError=$errorLog;PassThru=$true}
 $process=Start-AgentServiceProcess $Plan.Overrides[$Service] $parameters
 # 5.1跨函数取得的Process在超时等待后可能丢ExitCode；启动后先保住句柄。
 [void]$process.Handle
 # 诊断子进程也先登记，再有界等待；超时只终止来源核对过的本卡探针。
 [pscustomobject]@{stage=$Stage;pid=$process.Id;sourceDirectory=$evidenceRoot;startedAt=(Get-Date).ToString('o')} |
  ConvertTo-Json | Set-Content -LiteralPath (Join-Path $private ($Stage+'.process.json')) -Encoding UTF8
 $deadline=(Get-Date).AddSeconds(70)
 while(-not $process.WaitForExit(1000) -and (Get-Date) -lt $deadline){}
 if(-not $process.HasExited){
  $actual=Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)" -ErrorAction SilentlyContinue
  if($actual -and $actual.Name -eq 'java.exe' -and $actual.CommandLine.Contains($evidenceRoot)){Stop-Process -Id $process.Id}
  throw "${Stage}探针超时；已按本卡登记与命令归属处理"
 }
 # File API返回纯字符串，避免Get-Content附加的PSDrive/Provider对象混入JSON。
 $safe=@([IO.File]::ReadAllLines($output,[Text.Encoding]::UTF8) | Where-Object {$_.StartsWith($SafePrefix)})
 $facts.Add([pscustomobject]@{stage=$Stage;service=$Service;exitCode=$process.ExitCode;safeFacts=$safe})
 Write-Output ('EX01_PROBE='+$Stage+'; exit='+$process.ExitCode+'; safeFacts='+$safe.Count)
 if($process.ExitCode -ne 0 -or $safe.Count -ne 1){throw "${Stage}未通过；保留本仓受限日志，不回显原始内容"}
}
function Invoke-ConfigurationProbe([string]$Service,[hashtable]$Plan,[string]$Mode='bounded'){
 $arguments=@('-Xmx128m','-Djdk.net.unixdomain.tmpdir=V:/.local/tmp','--add-modules','jdk.attach',('"'+(Join-Path $evidenceRoot 'ServiceConfigurationProbe.java')+'"'),[string]$owned[$Service],$serviceIds[$Service],$Mode)
 Invoke-PrivateProbe ('jmx-'+$serviceIds[$Service]+'-'+$Mode) $Service $Plan $arguments 'EX01_JMX_PASS'
}
function Get-PackagedClasspath([string]$Service){
 $jarPath=Join-Path $repo "$Service/target/$Service-0.1.0-SNAPSHOT.jar"
 $jarHash=(Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash
 $directory=Join-Path $private ('packaged/'+$Service+'-'+$jarHash.Substring(0,12))
 if(-not (Test-Path -LiteralPath $directory)){
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  [IO.Compression.ZipFile]::ExtractToDirectory($jarPath,$directory)
 }
 return @{classpath=((Join-Path $directory 'BOOT-INF/classes')+';'+(Join-Path $directory 'BOOT-INF/lib/*'));yaml=(Join-Path $directory 'BOOT-INF/classes/application.yml')}
}
$publicLines=@($databaseLine,('AGENT_ONTOLOGY_URL='+$base.GetLeftPart([UriPartial]::Authority)),'AGENT_PIN_ENVIRONMENT=ex02',
 'SPRING_APPLICATION_ADMIN_ENABLED=true',('EX01_TENANT='+$fixture.payload.platformTenant),('EX01_SPACE='+$fixture.payload.spaceId),
 ('EX01_RELEASE='+$fixture.payload.releaseId),('EX01_APP='+$runId))
$app=Write-PrivateEnvironment 'app.env' ($publicLines+@('AGENT_ONTOLOGY_API_KEY_REF=env:AGENT_SECRET_ONTO_APP_BINDER_KEY',
 ('AGENT_SECRET_ONTO_APP_BINDER_KEY='+$fixture.binder.key),'AGENT_SECRET_BINDINGS={"AGENT_SECRET_ONTO_APP_BINDER_KEY":"local-v12"}'))
$engine=Write-PrivateEnvironment 'engine.env' ($publicLines+@('AGENT_ONTOLOGY_API_KEY_REF=env:AGENT_SECRET_ONTO_ENGINE_READ_KEY',
 ('AGENT_SECRET_ONTO_ENGINE_READ_KEY='+$fixture.readonly.key),'AGENT_SECRET_BINDINGS={"AGENT_SECRET_ONTO_ENGINE_READ_KEY":"local-v12"}',
 'AGENT_ENGINE_QUOTA_ENABLED=true','AGENT_ENGINE_QUOTA_WINDOW_SECONDS=97','AGENT_ENGINE_QUOTA_TENANT_TOKENS=301',
 'AGENT_ENGINE_QUOTA_APP_TOKENS=211','AGENT_ENGINE_QUOTA_CREDENTIAL_TOKENS=101'))
$gateway=Write-PrivateEnvironment 'gateway.env' @($databaseLine,'SPRING_APPLICATION_ADMIN_ENABLED=true','AGENT_INTEGRATION_RATE_LIMIT_ENABLED=true',
 'AGENT_INTEGRATION_RATE_LIMIT_WINDOW_SECONDS=17','AGENT_INTEGRATION_RATE_LIMIT_MAX_REQUESTS=2')
$skill=Write-PrivateEnvironment 'skill.env' @($databaseLine,'SPRING_APPLICATION_ADMIN_ENABLED=true','AGENT_SCHEMA_MODE=STRICT',
 'AGENT_SCHEMA_MAX_EXPANDED_WORK=128','AGENT_PROCESS_ENABLED=false','AGENT_SKILL_SERVICE_TICKET_MODE=AUTHORITY')
$files=@{'app-management'=$app;'engine-runtime'=$engine;'platform-gateway'=$gateway;'skill-registry'=$skill}
$complete=$false
try {
 # 所有真实服务都先用本卡唯一新空库，不能因没有engine调度器就推定skill启动无旧数据副作用。
 $pgListener=Get-NetTCPConnection -LocalPort 25432 -State Listen -ErrorAction Stop | Select-Object -First 1
 if($pgListener.OwningProcess -ne 15204){throw 'AP数据库登记PID变化；禁止向未核实实例创建库'}
 $oldPassword=[Environment]::GetEnvironmentVariable('PGPASSWORD','Process')
 $previousPreference=$ErrorActionPreference
 try {
  $env:PGPASSWORD=$env:AGENT_DB_PASSWORD
  $ErrorActionPreference='Continue'
  & (Join-Path $repo '.tools/postgresql-16.15/pgsql/bin/createdb.exe') -h 127.0.0.1 -p 25432 -U $env:AGENT_DB_USER -T template0 -E UTF8 $databaseName *> (Join-Path $private 'createdb.raw.log')
  $databaseExit=$LASTEXITCODE
 } finally {$ErrorActionPreference=$previousPreference;[Environment]::SetEnvironmentVariable('PGPASSWORD',$oldPassword,'Process')}
 if($databaseExit -ne 0){throw '本卡唯一新空库创建失败；未覆盖或清理任何既有库'}
 $isolatedDatabaseCreated=$true
 $facts.Add([pscustomobject]@{stage='isolated-empty-database-created';database=$databaseName;exitCode=$databaseExit;postgresPid=15204;businessRowsSeeded=$false})
 $env:EX01_EXPECTED_DATASOURCE_URL=$databaseLine.Substring('AGENT_DB_URL='.Length)
 $env:SPRING_DATASOURCE_URL=$env:EX01_EXPECTED_DATASOURCE_URL
 [Environment]::SetEnvironmentVariable('SPRING_APPLICATION_JSON',$null,'Process')
 # 旧参数入口真实启动；临时本地JMX开关仅用于读取白名单配置，未传服务文件。
 $env:SPRING_APPLICATION_ADMIN_ENABLED='true'
 Start-RegisteredServices @('skill-registry') @{}
 Wait-Ready 'skill-registry'
 $legacyPlan=Get-AgentServiceEnvironmentPlan $repo @('skill-registry') @{} $serviceIds $global
 Invoke-ConfigurationProbe 'skill-registry' $legacyPlan 'legacy'
 Stop-OwnedServices @('skill-registry')
 [Environment]::SetEnvironmentVariable('SPRING_APPLICATION_ADMIN_ENABLED',$original['SPRING_APPLICATION_ADMIN_ENABLED'],'Process')
 # 正式生产JAR装配必须因PROCESS开启但白名单空而退出，不能使用测试便捷构造替代。
 $env:SPRING_APPLICATION_JSON='{"agent":{"process":{"enabled":true,"allowed-executables":""}}}'
 Start-RegisteredServices @('skill-registry') @{}
 $negativeProcess=[Diagnostics.Process]::GetProcessById($owned['skill-registry'])
 [void]$negativeProcess.Handle
 $deadline=(Get-Date).AddSeconds(70)
 while(-not $negativeProcess.WaitForExit(1000) -and (Get-Date) -lt $deadline){}
 if(-not $negativeProcess.HasExited){throw '正式PROCESS空白名单启动负例没有退出'}
 $negativeText=(Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repo '.local/logs/skill-registry.out.log'))+(Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $repo '.local/logs/skill-registry.err.log'))
 $negativeRejected=$negativeText.Contains('agent.process.enabled=true 时必须配置 agent.process.allowed-executables 白名单')
 $facts.Add([pscustomobject]@{stage='formal-process-empty-whitelist';exitCode=$negativeProcess.ExitCode;exactConstructorRejection=$negativeRejected;processExited=$true;
  environmentMode='SPRING_APPLICATION_JSON process switch; no ServiceEnvironmentFiles';isolatedDatabaseSeenInBootLog=$negativeText.Contains($databaseName)})
 if($negativeProcess.ExitCode -eq 0 -or -not $negativeRejected){throw '正式PROCESS装配未在预期白名单校验处失败'}
 Stop-OwnedServices @('skill-registry')
 # 清除标准优先级覆盖，正向只验证服务文件里的AGENT_DB_URL，不能让旧探针覆盖掩盖接口问题。
 [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_URL',$null,'Process')
 [Environment]::SetEnvironmentVariable('SPRING_APPLICATION_JSON',$null,'Process')
 $facts.Add([pscustomobject]@{stage='standard-overrides-cleared-before-service-files';datasourceOverrideAbsent=($null -eq $env:SPRING_DATASOURCE_URL);applicationJsonAbsent=($null -eq $env:SPRING_APPLICATION_JSON)})
 # 对抗性父环境同时已有两把真实专用key；生产启动路径仍须逐服务隔离并恢复父值。
 $env:AGENT_SECRET_ONTO_APP_BINDER_KEY=$fixture.binder.key
 $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY=$fixture.readonly.key
 $env:AGENT_SECRET_BINDINGS='{"AGENT_SECRET_ONTO_APP_BINDER_KEY":"local-v12","AGENT_SECRET_ONTO_ENGINE_READ_KEY":"local-v12"}'
 $env:AGENT_ONTOLOGY_API_KEY_REF='env:AGENT_SECRET_ONTO_ENGINE_READ_KEY'
 Start-RegisteredServices @('app-management','engine-runtime','skill-registry','platform-gateway') $files
 foreach($service in @('app-management','engine-runtime','skill-registry','platform-gateway')){Wait-Ready $service}
 $plan=Get-AgentServiceEnvironmentPlan $repo @('app-management','engine-runtime','skill-registry','platform-gateway') $files $serviceIds $global
 foreach($service in @('app-management','engine-runtime','skill-registry','platform-gateway')){Invoke-ConfigurationProbe $service $plan}
 $parentRestored=($env:AGENT_SECRET_ONTO_APP_BINDER_KEY -eq $fixture.binder.key -and $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY -eq $fixture.readonly.key)
 $facts.Add([pscustomobject]@{stage='actual-launch-parent-restored';bothOriginalKeysPreserved=$parentRestored})
 if(-not $parentRestored){throw '实际启动后父环境密钥未恢复'}
 foreach($stage in @('readonly-negative','app-pin','engine-read')){
  $service=if($stage -eq 'app-pin'){'app-management'}else{'engine-runtime'}
  $packaged=Get-PackagedClasspath $service
  $arguments=@('-Xmx192m','-Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-cp',('"'+$packaged.classpath+'"'),('"'+(Join-Path $PSScriptRoot 'ConfiguredOntologyClientProbe.java')+'"'),$stage,('"'+$packaged.yaml+'"'))
  Invoke-PrivateProbe $stage $service $plan $arguments 'EX01_CLIENT_PASS'
 }
 $packaged=Get-PackagedClasspath 'platform-gateway'
 $rateArguments=@('-Xmx192m','-Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-cp',('"'+$packaged.classpath+'"'),('"'+(Join-Path $PSScriptRoot 'ConfiguredRateLimitProbe.java')+'"'),('"'+$packaged.yaml+'"'),'bounded')
 Invoke-PrivateProbe 'configured-rate-bounded' 'platform-gateway' $plan $rateArguments 'EX01_RATE_PASS'
 Stop-OwnedServices @('engine-runtime','platform-gateway')
 $engineText=[IO.File]::ReadAllText($engine).Replace('TOKENS=301','TOKENS=0').Replace('TOKENS=211','TOKENS=0').Replace('TOKENS=101','TOKENS=0')
 [IO.File]::WriteAllText($engine,$engineText,$utf8)
 [IO.File]::WriteAllText($gateway,[IO.File]::ReadAllText($gateway).Replace('MAX_REQUESTS=2','MAX_REQUESTS=0'),$utf8)
 Start-RegisteredServices @('engine-runtime','platform-gateway') $files
 foreach($service in @('engine-runtime','platform-gateway')){Wait-Ready $service}
 $unlimitedPlan=Get-AgentServiceEnvironmentPlan $repo @('engine-runtime','platform-gateway') $files $serviceIds $global
 foreach($service in @('engine-runtime','platform-gateway')){Invoke-ConfigurationProbe $service $unlimitedPlan 'unlimited'}
 $rateArguments[$rateArguments.Count-1]='unlimited'
 Invoke-PrivateProbe 'configured-rate-unlimited' 'platform-gateway' $unlimitedPlan $rateArguments 'EX01_RATE_PASS'
 $complete=$true
} catch {
 $facts.Add([pscustomobject]@{stage='safe-failure-location';exceptionType=$_.Exception.GetType().FullName;scriptStackTrace=$_.ScriptStackTrace})
 throw
} finally {
 $cleanupOk=$false
 try {Stop-OwnedServices @('platform-gateway','app-management','engine-runtime','skill-registry');$cleanupOk=$true} finally {
  $secretFound=$false
  $scanPaths=@()
  foreach($service in $serviceIds.Keys){foreach($suffix in @('out','err')){$scanPaths+=Join-Path $repo ('.local/logs/'+$service+'.'+$suffix+'.log')}}
  $scanPaths+=@(Get-ChildItem -LiteralPath $private -File | Where-Object {$_.Name -match '\.(out|err)\.log$'} | ForEach-Object {$_.FullName})
  $scanPaths+=@(Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse | Where-Object {$_.Extension -in @('.json','.md','.csv','.java','.ps1','.txt')} | ForEach-Object {$_.FullName})
  foreach($path in $scanPaths){
   if(Test-Path -LiteralPath $path){$text=[IO.File]::ReadAllText($path);if($text.Contains($fixture.binder.key) -or $text.Contains($fixture.readonly.key)){$secretFound=$true}}
  }
  $finalEnvironmentSha=(Get-FileHash -LiteralPath (Join-Path $repo '.env') -Algorithm SHA256).Hash
  $aclProtected=(Get-Acl -LiteralPath $private).AreAccessRulesProtected
  $complete=$complete -and $cleanupOk -and -not $secretFound -and $aclProtected -and $environmentSha -eq $finalEnvironmentSha
  $record=[pscustomobject]@{status=$(if($complete){'PASS'}else{'INCOMPLETE'});runId=$runId;headAtStart=$initialHead;headAtEnd=(& git -C $repo rev-parse HEAD);
   environmentSha256Before=$environmentSha;environmentSha256After=$finalEnvironmentSha;ownedServicesCleanupSucceeded=$cleanupOk;
   facts=$facts.ToArray();privateDirectory=$private;protectedAcl=$aclProtected;serviceLogsContainEitherIssuedKey=$secretFound;
   packageRecord=(Join-Path $packageRun.FullName 'run.json');packageSourceAndJarHashesMatchedBeforeStart=$true;
   runnerSha256AtStart=$runnerHashes;
   issuedKeyScanFiles=$scanPaths.Count;issuedKeyScanScope='四服务当前日志、本卡诊断stdout/stderr与本卡可版本化文本；不扫描合法含密钥的受限服务环境文件';
   isolatedDatabase=$databaseName;isolatedDatabaseCreated=$isolatedDatabaseCreated;databaseRetention='保留本卡唯一新库及Flyway结构供复核；未删除既有库或预置业务记录';
   publicFixture=[pscustomobject]@{spaceId=$fixture.payload.spaceId;releaseId=$fixture.payload.releaseId;platformTenant=$fixture.payload.platformTenant;environment='ex02';appCode=$runId};
   scope='正式当前JAR启动/JMX白名单配置；真实Spring注入客户端到OR；配置限流器。不是平台发布J9或完整EX07';
   cleanup='仅停止本卡由生产脚本登记且归属匹配的PID；PG/Redis/MinIO与OR保活未停止'}
  $record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $run 'run.json') -Encoding UTF8
  $current=[Environment]::GetEnvironmentVariables('Process')
  foreach($name in $current.Keys){if(-not $original.ContainsKey($name)){[Environment]::SetEnvironmentVariable($name,$null,'Process')}}
  foreach($name in $original.Keys){[Environment]::SetEnvironmentVariable($name,$original[$name],'Process')}
  Write-Output ('EX01_LIVE_RECORD='+$run+'; complete='+$complete+'; secretFound='+$secretFound)
 }
}
if(-not $complete){throw '实际验证或清理/秘密日志/配置指纹检查未通过，详见本卡脱敏记录'}
