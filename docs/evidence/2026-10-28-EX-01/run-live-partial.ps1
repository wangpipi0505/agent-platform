param([Parameter(Mandatory=$true)][string]$FromRun,[Parameter(Mandatory=$true)][string]$PrivateFixture)
$ErrorActionPreference='Stop'
$evidenceRoot=$PSScriptRoot
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$fromPath=[IO.Path]::GetFullPath($FromRun)
$runBoundary=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runs'))+[IO.Path]::DirectorySeparatorChar
if(-not $fromPath.StartsWith($runBoundary,[StringComparison]::OrdinalIgnoreCase)){throw '只允许继续本卡既有真实验证记录'}
$prior=Get-Content -Raw -Encoding UTF8 -LiteralPath $fromPath | ConvertFrom-Json
if(-not $prior.ownedServicesCleanupSucceeded -or -not $prior.isolatedDatabaseCreated){throw '前轮清理或独立库归属没有成功记录'}
$legacy=@($prior.facts | Where-Object {$_.stage -eq 'jmx-skill-legacy' -and $_.exitCode -eq 0})
$processNegative=@($prior.facts | Where-Object {$_.stage -eq 'formal-process-empty-whitelist' -and $_.exitCode -eq 1 -and $_.exactConstructorRejection})
if($legacy.Count -ne 1 -or $processNegative.Count -ne 1){throw '已绿前置不足，不能跳过或伪造legacy/PROCESS验证'}
$private=[IO.Path]::GetFullPath($prior.privateDirectory)
$privateBoundary=[IO.Path]::GetFullPath((Join-Path $repo '.local/ex01-private'))+[IO.Path]::DirectorySeparatorChar
if(-not $private.StartsWith($privateBoundary,[StringComparison]::OrdinalIgnoreCase) -or -not (Get-Acl -LiteralPath $private).AreAccessRulesProtected){throw '前轮受限凭据目录归属或ACL不符'}
if($prior.isolatedDatabase -notmatch '^agent_platform_ex01_[0-9_]+$'){throw '前轮独立库名称不符合本卡命名'}
$package=Get-Content -Raw -Encoding UTF8 -LiteralPath $prior.packageRecord | ConvertFrom-Json
foreach($entry in $package.sourceSha256.PSObject.Properties){if((Get-FileHash -LiteralPath (Join-Path $repo $entry.Name) -Algorithm SHA256).Hash -ne $entry.Value){throw '冻结生产源码已变，不能用旧JAR验证'}}
foreach($jar in $package.jars){if((Get-FileHash -LiteralPath $jar.path -Algorithm SHA256).Hash -ne $jar.sha256){throw '冻结JAR已变，不能混用原产物'}}
try{$fixture=Get-Content -Raw -Encoding UTF8 -LiteralPath $PrivateFixture | ConvertFrom-Json -ErrorAction Stop}catch{throw '授权凭据文件无法解析，不显示内容'}
if($fixture.payload.platformTenant -ne $prior.publicFixture.platformTenant -or $fixture.payload.spaceId -ne $prior.publicFixture.spaceId -or $fixture.payload.releaseId -ne $prior.publicFixture.releaseId){throw '继续验证必须使用同一授权公开夹具'}
$run=Join-Path $PSScriptRoot ('runs/live-partial-'+(Get-Date -Format yyyyMMdd-HHmmss-fff))
New-Item -ItemType Directory -Path $run | Out-Null
$source=Join-Path $repo 'scripts/start-backend.ps1'
foreach($group in @(
 @{Path=$source;Names=@('Read-AgentEnvironmentFile','Get-AgentServiceEnvironmentPlan','Start-AgentServiceProcess')},
 @{Path=(Join-Path $PSScriptRoot 'run-live.ps1');Names=@('Start-RegisteredServices','Stop-OwnedServices','Wait-Ready','Invoke-PrivateProbe','Invoke-ConfigurationProbe','Get-PackagedClasspath')})){
 $tokens=$null;$errors=$null
 $ast=[Management.Automation.Language.Parser]::ParseFile($group.Path,[ref]$tokens,[ref]$errors)
 if($errors.Count){throw '共享生产/实际采集函数语法错误'}
 foreach($name in $group.Names){
  $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
  if(-not $definition){throw '共享实际采集函数不存在'}
  . ([scriptblock]::Create($definition.Extent.Text))
 }
}
$original=@{}
foreach($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()){$original[$entry.Key]=$entry.Value}
$global=Read-AgentEnvironmentFile (Join-Path $repo '.env')
if($global.ContainsKey('SPRING_DATASOURCE_URL') -or $global.ContainsKey('SPRING_APPLICATION_JSON')){throw '全局配置存在会掩盖服务文件的标准覆盖'}
foreach($name in $global.Keys){[Environment]::SetEnvironmentVariable($name,$global[$name],'Process')}
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
$serviceIds=@{'app-management'='app';'engine-runtime'='engine';'skill-registry'='skill';'platform-gateway'='gateway'}
$ports=@{'app-management'=18081;'engine-runtime'=18082;'skill-registry'=18083;'platform-gateway'=18080}
$owned=@{}
$facts=New-Object 'System.Collections.Generic.List[object]'
$facts.Add([pscustomobject]@{stage='reused-green-prerequisites';sourceRun=$fromPath;legacyPassed=$true;formalProcessRejected=$true;reExecuted=$false})
$files=@{'app-management'=(Join-Path $private 'app.env');'engine-runtime'=(Join-Path $private 'engine.env');'skill-registry'=(Join-Path $private 'skill.env');'platform-gateway'=(Join-Path $private 'gateway.env')}
$environmentSha=(Get-FileHash -LiteralPath (Join-Path $repo '.env') -Algorithm SHA256).Hash
$head=(& git -C $repo rev-parse HEAD)
$runnerHashes=@{}
foreach($name in @('run-live-partial.ps1','run-live.ps1','ServiceConfigurationProbe.java','ConfiguredOntologyClientProbe.java','ConfiguredRateLimitProbe.java')){$runnerHashes[$name]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $name) -Algorithm SHA256).Hash}
Add-Type -AssemblyName System.Net.Http
$complete=$false
try {
 [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_URL',$null,'Process')
 [Environment]::SetEnvironmentVariable('SPRING_APPLICATION_JSON',$null,'Process')
 $env:EX01_EXPECTED_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:25432/'+$prior.isolatedDatabase
 $env:AGENT_SECRET_ONTO_APP_BINDER_KEY=$fixture.binder.key
 $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY=$fixture.readonly.key
 $env:AGENT_SECRET_BINDINGS='{"AGENT_SECRET_ONTO_APP_BINDER_KEY":"local-v12","AGENT_SECRET_ONTO_ENGINE_READ_KEY":"local-v12"}'
 $env:AGENT_ONTOLOGY_API_KEY_REF='env:AGENT_SECRET_ONTO_ENGINE_READ_KEY'
 $active=@('app-management','skill-registry','platform-gateway')
 Start-RegisteredServices $active $files
 foreach($service in $active){Wait-Ready $service}
 $plan=Get-AgentServiceEnvironmentPlan $repo $active $files $serviceIds $global
 foreach($service in $active){Invoke-ConfigurationProbe $service $plan}
 $parentRestored=($env:AGENT_SECRET_ONTO_APP_BINDER_KEY -eq $fixture.binder.key -and $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY -eq $fixture.readonly.key)
 $facts.Add([pscustomobject]@{stage='actual-launch-parent-restored';bothOriginalKeysPreserved=$parentRestored})
 if(-not $parentRestored){throw '实际启动后父环境密钥未恢复'}
 # 只读写负例仅验证配置的SecretVault凭据权限，不绕过/冒称已成功装配engine客户端Bean。
 $appPackaged=Get-PackagedClasspath 'app-management'
 $clientSource=Join-Path $PSScriptRoot 'ConfiguredOntologyClientProbe.java'
 foreach($stage in @('readonly-negative','app-pin')){
  $service=if($stage -eq 'app-pin'){'app-management'}else{'engine-runtime'}
  $arguments=@('-Xmx192m','-Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-cp',('"'+$appPackaged.classpath+'"'),('"'+$clientSource+'"'),$stage,('"'+$appPackaged.yaml+'"'))
  Invoke-PrivateProbe $stage $service $plan $arguments 'EX01_CLIENT_PASS'
 }
 $packaged=Get-PackagedClasspath 'platform-gateway'
 $rateArguments=@('-Xmx192m','-Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-cp',('"'+$packaged.classpath+'"'),('"'+(Join-Path $PSScriptRoot 'ConfiguredRateLimitProbe.java')+'"'),('"'+$packaged.yaml+'"'),'bounded')
 Invoke-PrivateProbe 'configured-rate-bounded' 'platform-gateway' $plan $rateArguments 'EX01_RATE_PASS'
 Stop-OwnedServices @('platform-gateway')
 $gateway=$files['platform-gateway']
 [IO.File]::WriteAllText($gateway,[IO.File]::ReadAllText($gateway).Replace('MAX_REQUESTS=2','MAX_REQUESTS=0'),(New-Object Text.UTF8Encoding($true)))
 Start-RegisteredServices @('platform-gateway') $files
 Wait-Ready 'platform-gateway'
 $zeroPlan=Get-AgentServiceEnvironmentPlan $repo @('platform-gateway') $files $serviceIds $global
 Invoke-ConfigurationProbe 'platform-gateway' $zeroPlan 'unlimited'
 $rateArguments[$rateArguments.Count-1]='unlimited'
 Invoke-PrivateProbe 'configured-rate-unlimited' 'platform-gateway' $zeroPlan $rateArguments 'EX01_RATE_PASS'
 $complete=$true
} catch {$facts.Add([pscustomobject]@{stage='safe-failure-location';exceptionType=$_.Exception.GetType().FullName;scriptStackTrace=$_.ScriptStackTrace});throw}
finally {
 $cleanup=$false
 try{Stop-OwnedServices @('platform-gateway','app-management','skill-registry');$cleanup=$true}finally{
  $scanPaths=@()
  foreach($service in $serviceIds.Keys){foreach($suffix in @('out','err')){$scanPaths+=Join-Path $repo ('.local/logs/'+$service+'.'+$suffix+'.log')}}
  $scanPaths+=@(Get-ChildItem -LiteralPath $private -File | Where-Object {$_.Name -match '\.(out|err)\.log$'} | ForEach-Object {$_.FullName})
  $scanPaths+=@(Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse | Where-Object {$_.Extension -in @('.json','.md','.csv','.java','.ps1','.txt')} | ForEach-Object {$_.FullName})
  $secretFound=$false
  foreach($path in $scanPaths){if(Test-Path -LiteralPath $path){$text=[IO.File]::ReadAllText($path);if($text.Contains($fixture.binder.key) -or $text.Contains($fixture.readonly.key)){$secretFound=$true}}}
  $after=(Get-FileHash -LiteralPath (Join-Path $repo '.env') -Algorithm SHA256).Hash
  $complete=$complete -and $cleanup -and -not $secretFound -and $environmentSha -eq $after
  [pscustomobject]@{status=$(if($complete){'PARTIAL_PASS_ENGINE_BLOCKED'}else{'INCOMPLETE'});wholeCardPassed=$false;sourceRun=$fromPath;
   headAtStart=$head;headAtEnd=(& git -C $repo rev-parse HEAD);packageRecord=$prior.packageRecord;runnerSha256AtStart=$runnerHashes;
   packageSourceAndJarHashesMatchedBeforeStart=$true;facts=$facts.ToArray();publicFixture=$prior.publicFixture;privateDirectory=$private;
   isolatedDatabase=$prior.isolatedDatabase;databaseCreatedThisContinuation=$false;ownedServicesCleanupSucceeded=$cleanup;
   environmentSha256Before=$environmentSha;environmentSha256After=$after;issuedKeyFoundInLogsOrVersionableText=$secretFound;scannedFiles=$scanPaths.Count;
   engineStartupVerified=$false;engineBlocker='原run/engine-startup-blocker.json；EX36独立修复后仅补engine及必要关联';
   gatewayOriginalBaselineGatePassed=$false;knownGatewayNewNonPass='EX35/EX27 forcedRelease SSE；未豁免/未promote基线';
   scope='app/skill/gateway真实当前JAR配置、父进程双密钥隔离、真实Spring装配app binder客户端；只读凭据403与配置限流器；不含engine Bean绕过或J9'} |
   ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $run 'run.json') -Encoding UTF8
  $current=[Environment]::GetEnvironmentVariables('Process')
  foreach($name in $current.Keys){if(-not $original.ContainsKey($name)){[Environment]::SetEnvironmentVariable($name,$null,'Process')}}
  foreach($name in $original.Keys){[Environment]::SetEnvironmentVariable($name,$original[$name],'Process')}
  Write-Output ('EX01_PARTIAL_RECORD='+$run+'; partialComplete='+$complete+'; wholeCardPassed=false; secretFound='+$secretFound)
 }
}
if(-not $complete){throw '已授权局部阶段未全部完成，保留本次真实失败记录'}
