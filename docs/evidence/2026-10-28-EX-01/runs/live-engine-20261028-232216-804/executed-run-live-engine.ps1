param([Parameter(Mandatory=$true)][string]$PackageRecord,[Parameter(Mandatory=$true)][string]$PrivateFixture)
$ErrorActionPreference='Stop'
$evidenceRoot=$PSScriptRoot
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$context=Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot 'engine-continuation-context.json') | ConvertFrom-Json
$prior=Get-Content -Raw -Encoding UTF8 -LiteralPath $context.previousFullRun | ConvertFrom-Json
$partial=Get-Content -Raw -Encoding UTF8 -LiteralPath $context.partialRun | ConvertFrom-Json
if(-not $prior.ownedServicesCleanupSucceeded -or -not $prior.isolatedDatabaseCreated -or $partial.status -ne 'PARTIAL_PASS_ENGINE_BLOCKED' -or -not $partial.ownedServicesCleanupSucceeded){throw '既有独立库归属、清理或已绿局部记录不足'}
$private=[IO.Path]::GetFullPath($prior.privateDirectory)
$privateBoundary=[IO.Path]::GetFullPath((Join-Path $repo '.local/ex01-private'))+[IO.Path]::DirectorySeparatorChar
if(-not $private.StartsWith($privateBoundary,[StringComparison]::OrdinalIgnoreCase) -or -not (Get-Acl -LiteralPath $private).AreAccessRulesProtected){throw '受限凭据目录归属或ACL不符'}
if($prior.isolatedDatabase -notmatch '^agent_platform_ex01_[0-9_]+$'){throw '独立库名称不符合本卡命名'}
$packagePath=[IO.Path]::GetFullPath($PackageRecord)
$package=Get-Content -Raw -Encoding UTF8 -LiteralPath $packagePath | ConvertFrom-Json
if($package.exitCode -ne 0 -or -not $package.sourceUnchangedDuringBuild -or @($package.jars).Count -ne 1 -or $package.jars[0].module -ne 'engine-runtime' -or -not $package.jars[0].fresh){throw '只接受新鲜的engine-only成功包记录'}
$actualSources=@(Get-ChildItem -LiteralPath (Join-Path $repo 'engine-runtime/src/main'),(Join-Path $repo 'admin-common/src/main') -Recurse -File | ForEach-Object {$_.FullName.Substring($repo.Length+1).Replace('\','/')})
$recordedSources=@($package.sourceSha256.PSObject.Properties.Name)
if(@(Compare-Object $actualSources $recordedSources).Count){throw 'engine/common源码集合与新包记录不符'}
foreach($entry in $package.sourceSha256.PSObject.Properties){if((Get-FileHash -LiteralPath (Join-Path $repo $entry.Name) -Algorithm SHA256).Hash -ne $entry.Value){throw 'engine/common源码摘要与新包记录不符'}}
$jar=$package.jars[0]
$jarPath=Join-Path $repo $jar.path
if((Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash -ne $jar.sha256){throw 'engine正式JAR摘要与新包记录不符'}
try{$fixture=Get-Content -Raw -Encoding UTF8 -LiteralPath $PrivateFixture | ConvertFrom-Json -ErrorAction Stop}catch{throw '授权凭据文件无法解析，不显示内容'}
if($fixture.payload.platformTenant -ne $prior.publicFixture.platformTenant -or $fixture.payload.spaceId -ne $prior.publicFixture.spaceId -or $fixture.payload.releaseId -ne $prior.publicFixture.releaseId){throw '必须使用同一授权公开夹具'}
$source=Join-Path $repo 'scripts/start-backend.ps1'
foreach($group in @(
 @{Path=$source;Names=@('Read-AgentEnvironmentFile','Get-AgentServiceEnvironmentPlan','Start-AgentServiceProcess')},
 @{Path=(Join-Path $PSScriptRoot 'run-live.ps1');Names=@('Start-RegisteredServices','Stop-OwnedServices','Wait-Ready','Invoke-PrivateProbe','Invoke-ConfigurationProbe','Get-PackagedClasspath')})){
 $tokens=$null;$errors=$null
 $ast=[Management.Automation.Language.Parser]::ParseFile($group.Path,[ref]$tokens,[ref]$errors)
 if($errors.Count){throw '共享生产或采集函数语法错误'}
 foreach($name in $group.Names){
  $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
  if(-not $definition){throw '共享生产或采集函数不存在'}
  . ([scriptblock]::Create($definition.Extent.Text))
 }
}
$files=@{}
foreach($entry in $context.privateEnvironmentFiles.PSObject.Properties){$files[$entry.Name]=$entry.Value}
$engineLines=Read-AgentEnvironmentFile $files['engine-runtime'] -Strict
if($engineLines['AGENT_DB_URL'] -ne ('jdbc:postgresql://127.0.0.1:25432/'+$prior.isolatedDatabase)){throw 'engine服务文件没有指向本卡独立库'}
$run=Join-Path $PSScriptRoot ('runs/live-engine-'+(Get-Date -Format yyyyMMdd-HHmmss-fff))
New-Item -ItemType Directory -Path $run | Out-Null
$original=@{}
foreach($entry in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()){$original[$entry.Key]=$entry.Value}
$global=Read-AgentEnvironmentFile (Join-Path $repo '.env')
if($global.ContainsKey('SPRING_DATASOURCE_URL') -or $global.ContainsKey('SPRING_APPLICATION_JSON')){throw '全局存在会掩盖服务文件的标准覆盖'}
foreach($name in $global.Keys){[Environment]::SetEnvironmentVariable($name,$global[$name],'Process')}
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
$serviceIds=@{'app-management'='app';'engine-runtime'='engine';'skill-registry'='skill';'platform-gateway'='gateway'}
$ports=@{'engine-runtime'=18082}
$owned=@{}
$facts=New-Object 'System.Collections.Generic.List[object]'
$facts.Add([pscustomobject]@{stage='historical-green-reference';sourceRun=$context.partialRun;reExecuted=$false;scope='legacy/PROCESS/app/skill/gateway/binder/rate-limit已绿阶段仅引用历史轮'})
$environmentSha=(Get-FileHash -LiteralPath (Join-Path $repo '.env') -Algorithm SHA256).Hash
$head=(& git -C $repo rev-parse HEAD)
$runnerHashes=@{}
foreach($name in @('run-live-engine.ps1','run-live.ps1','ServiceConfigurationProbe.java','ConfiguredOntologyClientProbe.java')){$runnerHashes[$name]=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $name) -Algorithm SHA256).Hash}
Add-Type -AssemblyName System.Net.Http
$complete=$false
try {
 [Environment]::SetEnvironmentVariable('SPRING_DATASOURCE_URL',$null,'Process')
 [Environment]::SetEnvironmentVariable('SPRING_APPLICATION_JSON',$null,'Process')
 $env:EX01_EXPECTED_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:25432/'+$prior.isolatedDatabase
 # 父进程预存双凭据，再由同一生产隔离算法只给engine自己的引用、值与绑定。
 $env:AGENT_SECRET_ONTO_APP_BINDER_KEY=$fixture.binder.key
 $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY=$fixture.readonly.key
 $env:AGENT_SECRET_BINDINGS='{"AGENT_SECRET_ONTO_APP_BINDER_KEY":"local-v12","AGENT_SECRET_ONTO_ENGINE_READ_KEY":"local-v12"}'
 $env:AGENT_ONTOLOGY_API_KEY_REF='env:AGENT_SECRET_ONTO_APP_BINDER_KEY'
 Start-RegisteredServices @('engine-runtime') $files
 Wait-Ready 'engine-runtime'
 $plan=Get-AgentServiceEnvironmentPlan $repo @('engine-runtime') $files $serviceIds $global
 Invoke-ConfigurationProbe 'engine-runtime' $plan
 $parentRestored=($env:AGENT_SECRET_ONTO_APP_BINDER_KEY -eq $fixture.binder.key -and $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY -eq $fixture.readonly.key -and $env:AGENT_ONTOLOGY_API_KEY_REF -eq 'env:AGENT_SECRET_ONTO_APP_BINDER_KEY')
 $facts.Add([pscustomobject]@{stage='engine-launch-parent-restored';bothOriginalKeysAndReferencePreserved=$parentRestored})
 if(-not $parentRestored){throw '实际engine启动后父凭据或引用未恢复'}
 $packaged=Get-PackagedClasspath 'engine-runtime'
 $arguments=@('-Xmx192m','-Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-cp',('"'+$packaged.classpath+'"'),('"'+(Join-Path $PSScriptRoot 'ConfiguredOntologyClientProbe.java')+'"'),'engine-read',('"'+$packaged.yaml+'"'))
 Invoke-PrivateProbe 'engine-read' 'engine-runtime' $plan $arguments 'EX01_CLIENT_PASS'
 Stop-OwnedServices @('engine-runtime')
 $engine=$files['engine-runtime']
 $text=[IO.File]::ReadAllText($engine)
 foreach($pair in @(@('TENANT_TOKENS=301','TENANT_TOKENS=0'),@('APP_TOKENS=211','APP_TOKENS=0'),@('CREDENTIAL_TOKENS=101','CREDENTIAL_TOKENS=0'))){if(-not $text.Contains($pair[0])){throw '非零配额配置缺失，禁止猜测改写'};$text=$text.Replace($pair[0],$pair[1])}
 [IO.File]::WriteAllText($engine,$text,(New-Object Text.UTF8Encoding($true)))
 Start-RegisteredServices @('engine-runtime') $files
 Wait-Ready 'engine-runtime'
 $zeroPlan=Get-AgentServiceEnvironmentPlan $repo @('engine-runtime') $files $serviceIds $global
 Invoke-ConfigurationProbe 'engine-runtime' $zeroPlan 'unlimited'
 $facts.Add([pscustomobject]@{stage='zero-semantics-existing-proof';realConfigurationVerified=$true;quotaEnabled=$true;allThreeLimits=0;semanticTest='cn.agentplatform.engine.infra.RunQuotaPostgresTest#zeroLimitMeansUnlimitedAndUnknownModelSkipsCredentialLevel';targetedRun='runs/engine-runtime-targeted-20261028-213039-849';newEngineModuleRun='EX36完整engine模块907方法；原方法PASS保留';meaning='正式JAR的true/0配置与既有真实PG不限语义关联，不冒充新业务运行'})
 $complete=$true
} catch {$facts.Add([pscustomobject]@{stage='safe-failure-location';exceptionType=$_.Exception.GetType().FullName;scriptStackTrace=$_.ScriptStackTrace});throw}
finally {
 $cleanup=$false
 try{Stop-OwnedServices @('engine-runtime');$cleanup=$true}finally{
  $scanPaths=@()
  foreach($suffix in @('out','err')){$scanPaths+=Join-Path $repo (' .local/logs/engine-runtime.'+$suffix+'.log').TrimStart()}
  $scanPaths+=@(Get-ChildItem -LiteralPath $private -File | Where-Object {$_.Name -match '\.(out|err)\.log$'} | ForEach-Object {$_.FullName})
  $scanPaths+=@(Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse | Where-Object {$_.Extension -in @('.json','.md','.csv','.java','.ps1','.txt')} | ForEach-Object {$_.FullName})
  $secretFound=$false
  foreach($path in $scanPaths){if(Test-Path -LiteralPath $path){$text=[IO.File]::ReadAllText($path);if($text.Contains($fixture.binder.key) -or $text.Contains($fixture.readonly.key)){$secretFound=$true}}}
  $after=(Get-FileHash -LiteralPath (Join-Path $repo '.env') -Algorithm SHA256).Hash
  $sourceAfter=$true
  foreach($entry in $package.sourceSha256.PSObject.Properties){if((Get-FileHash -LiteralPath (Join-Path $repo $entry.Name) -Algorithm SHA256).Hash -ne $entry.Value){$sourceAfter=$false}}
  $jarAfter=(Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash
  $complete=$complete -and $cleanup -and -not $secretFound -and $environmentSha -eq $after -and $sourceAfter -and $jarAfter -eq $jar.sha256
  [pscustomobject]@{status=$(if($complete){'ENGINE_SUPPLEMENT_PASS'}else{'INCOMPLETE'});wholeCardPassed=$false;sourceRun=$context.previousFullRun;historicalPartialRun=$context.partialRun;
   headAtStart=$head;headAtEnd=(& git -C $repo rev-parse HEAD);packageRecord=$packagePath;runnerSha256AtStart=$runnerHashes;
   packageSourceAndJarHashesMatchedBeforeStart=$true;engineCommonSourceCount=$recordedSources.Count;engineCommonSourceMatchedAfter=$sourceAfter;engineJarSha256Before=$jar.sha256;engineJarSha256After=$jarAfter;
   facts=$facts.ToArray();publicFixture=$prior.publicFixture;privateDirectory=$private;isolatedDatabase=$prior.isolatedDatabase;databaseCreatedThisContinuation=$false;
   ownedServicesCleanupSucceeded=$cleanup;environmentSha256Before=$environmentSha;environmentSha256After=$after;issuedKeyFoundInLogsOrVersionableText=$secretFound;scannedFiles=$scanPaths.Count;
   engineStartupVerified=$complete;gatewayOriginalBaselineGatePassed=$false;knownGatewayNewNonPass='EX35/EX27 forcedRelease SSE，未豁免/未promote基线';
   scope='仅EX36新engine/common源码与engine正式JAR；真实生产启动/JMX、双凭据隔离、Spring客户端真实pin/context-pack读取、true/0配置；其余服务引用历史已绿轮，不冒同轮全绿'} |
   ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $run 'run.json') -Encoding UTF8
  $current=[Environment]::GetEnvironmentVariables('Process')
  foreach($name in $current.Keys){if(-not $original.ContainsKey($name)){[Environment]::SetEnvironmentVariable($name,$null,'Process')}}
  foreach($name in $original.Keys){[Environment]::SetEnvironmentVariable($name,$original[$name],'Process')}
  Write-Output ('EX01_ENGINE_RECORD='+$run+'; engineComplete='+$complete+'; wholeCardPassed=false; secretFound='+$secretFound)
 }
}
if(-not $complete){throw 'engine补验未全部通过，保留本次真实失败记录'}
