param(
 [Parameter(Mandatory=$true)][ValidatePattern('^ex15a-live-[0-9]{14}-[0-9a-f]{8}$')][string]$RunId,
 [Parameter(Mandatory=$true)][string]$GatewayGreenEvidence,
 [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$GatewayGreenSha256,
 [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$GatewaySourceSha256,
 [switch]$ExecuteApproved
)
$ErrorActionPreference='Stop'
if(-not $ExecuteApproved){throw 'Static candidate only. Require gateway green review and the exclusive runtime window before -ExecuteApproved.'}
$repo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$private=Join-Path $repo ('.local/ex15a-live/'+$RunId)
$run=Join-Path $PSScriptRoot ('runs/'+$RunId)
$database='agent_platform_'+$RunId.Replace('-','_')
$utf8=New-Object Text.UTF8Encoding($false)
$javaHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$java=Join-Path $javaHome 'bin/java.exe'
$gatewaySource=Join-Path $repo 'platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java'
$installedCommon=Join-Path $repo '.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar'
$pgBin=Join-Path $repo '.tools/postgresql-16.15/pgsql/bin'
$start=Join-Path $repo 'scripts/start-backend.ps1'
$stop=Join-Path $repo 'scripts/stop-backend.ps1'
$owned=@{}; $buildOwned=@{}; $facts=New-Object 'System.Collections.Generic.List[object]'
$original=@{}; foreach($e in [Environment]::GetEnvironmentVariables('Process').GetEnumerator()){$original[$e.Key]=$e.Value}
$complete=$false; $failureCode=$null; $initialHead=$null; $before=$null; $packagePins=$null; $databaseCreated=$false
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Save([string]$Path,$Value){[IO.File]::WriteAllText($Path,($Value | ConvertTo-Json -Depth 12),$utf8)}
function PrivateAcl([string]$Path){
 $directory=(Get-Item -LiteralPath $Path).PSIsContainer
 $inherit=[Security.AccessControl.InheritanceFlags]::None
 if($directory){$inherit=[Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit}
 $sids=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18') | Select-Object -Unique
 $currentAcl=Get-Acl -LiteralPath $Path
 $rules=@($currentAcl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
 $matchesDacl=$currentAcl.AreAccessRulesProtected -and $rules.Count -eq @($sids).Count
 foreach($sid in $sids){
  $ownRules=@($rules | Where-Object {$_.IdentityReference.Value -eq $sid})
  if($ownRules.Count -ne 1){$matchesDacl=$false; continue}
  $rule=$ownRules[0]
  if($rule.IsInherited -or $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
     $rule.FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl -or
     $rule.InheritanceFlags -ne $inherit -or $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None){$matchesDacl=$false}
 }
 if($matchesDacl){return}
 # Persist only a newly built DACL; never round-trip owner/group/audit state from Get-Acl.
 $acl=if($directory){New-Object Security.AccessControl.DirectorySecurity}else{New-Object Security.AccessControl.FileSecurity}
 $acl.SetAccessRuleProtection($true,$false)
 foreach($sid in $sids){
  $identity=New-Object Security.Principal.SecurityIdentifier($sid)
  $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl',$inherit,'None','Allow')))
 }
 if($directory){[IO.Directory]::SetAccessControl($Path,$acl)}else{[IO.File]::SetAccessControl($Path,$acl)}
}
function OwnPath([string]$Path){
 $full=[IO.Path]::GetFullPath($Path); $boundary=[IO.Path]::GetFullPath($private).TrimEnd('\')+'\'
 if(-not $full.StartsWith($boundary,[StringComparison]::OrdinalIgnoreCase)){throw 'OWN_PATH_BOUNDARY'}
 $item=Get-Item -LiteralPath $full -ErrorAction SilentlyContinue
 if($item){while($item -and $item.FullName -ne $repo){if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'OWN_PATH_REPARSE'}; $item=if($item.PSIsContainer){$item.Parent}else{$item.Directory}}}
 return $full
}
function Pins {
 $paths=New-Object 'System.Collections.Generic.List[string]'
 foreach($module in @('admin-common','platform-gateway','skill-registry')){
  $paths.Add((Join-Path $repo "$module/pom.xml"))
  foreach($file in Get-ChildItem -LiteralPath (Join-Path $repo "$module/src") -File -Recurse){
   if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'SOURCE_REPARSE'}
   $paths.Add($file.FullName)
  }
 }
 foreach($path in @('pom.xml','.env','scripts/start-backend.ps1','scripts/stop-backend.ps1','scripts/start-database.ps1')){$paths.Add((Join-Path $repo $path))}
 foreach($path in @($installedCommon,$java,(Join-Path $javaHome 'bin/javac.exe'),(Join-Path $javaHome 'release'),$script:maven,(Join-Path $PSScriptRoot 'run-live.ps1'),(Join-Path $PSScriptRoot 'ArtifactLiveProbe.java'),(Join-Path $PSScriptRoot 'README.md'),$GatewayGreenEvidence,(Join-Path $script:gatewayGreenDirectory 'source-before.json'),(Join-Path $script:gatewayGreenDirectory 'source-after.json'),(Join-Path $script:gatewayGreenDirectory 'methods.xml'),(Join-Path $script:gatewayGreenDirectory 'native-exit.json'))){$paths.Add($path)}
 @($paths | Sort-Object -Unique | ForEach-Object {[pscustomobject]@{path=$_;bytes=(Get-Item -LiteralPath $_).Length;sha256=(Hash $_)}})
}
function SamePins($Left,$Right){
 if(($Left | ConvertTo-Json -Depth 5 -Compress) -cne ($Right | ConvertTo-Json -Depth 5 -Compress)){throw 'INPUT_OR_PACKAGE_CHANGED'}
}
function CheckPorts([string[]]$Services){foreach($service in $Services){if(Get-NetTCPConnection -LocalPort $ports[$service] -State Listen -ErrorAction SilentlyContinue){throw 'SERVICE_PORT_ALREADY_OWNED'}}}
function Identity($Record){
 $p=Get-CimInstance Win32_Process -Filter "ProcessId=$($Record.pid)" -ErrorAction SilentlyContinue
 if(-not $p){return $null}
 if($p.CreationDate.ToString('o') -cne $Record.creation -or $p.Name -ne 'java.exe' -or
    -not $p.CommandLine.Contains($Record.jar) -or [IO.Path]::GetFullPath($p.ExecutablePath) -ine [IO.Path]::GetFullPath($java)){throw 'REGISTERED_PROCESS_IDENTITY_CHANGED'}
 return $p
}
function ArchiveLogs([string]$Service,[string]$Generation){
 foreach($kind in @('out','err')){
  $source=Join-Path $repo ".local/logs/$Service.$kind.log"
  if(Test-Path -LiteralPath $source){
   $target=OwnPath (Join-Path $private "$Generation-$Service.$kind.raw.log")
   if(Test-Path -LiteralPath $target){throw 'LOG_ARCHIVE_EXISTS'}
   Copy-Item -LiteralPath $source -Destination $target
   $facts.Add([pscustomobject]@{stage='log-archive';service=$Service;generation=$Generation;kind=$kind;sha256=(Hash $target);bytes=(Get-Item -LiteralPath $target).Length})
  }
 }
}
function StartServices([string[]]$Services,[string]$Generation){
 CheckPorts $Services
 # The official script overwrites only these exact four logs. Keep prior bytes first.
 foreach($service in $Services){
  ArchiveLogs $service ('before-'+$Generation)
  foreach($kind in @('out','err')){
   $log=Join-Path $repo ".local/logs/$service.$kind.log"
   if(-not (Test-Path -LiteralPath $log)){[IO.File]::WriteAllText($log,'',$utf8)}
   PrivateAcl $log
  }
 }
 $startFailure=$null
 try {& $start -Services $Services -SkipBuild -ServiceEnvironmentFiles $serviceFiles *> (Join-Path $private "$Generation-start.raw.log")}
 catch {$startFailure=$_; $_ | Out-String | Set-Content -LiteralPath (Join-Path $private "$Generation-start-error.raw.log") -Encoding UTF8}
 # Even a partial startup must register before a later failure can enter finally.
 $manifestPath=Join-Path $repo '.local/services.json'
 if(Test-Path -LiteralPath $manifestPath){
  $manifest=Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
  foreach($service in $Services){
   $entries=@($manifest | Where-Object {$_.service -eq $service})
   if($entries.Count -ne 1){if($startFailure){continue}; throw 'MANIFEST_NOT_UNIQUE'}
   $entry=$entries[0]; $p=Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.pid)" -ErrorAction SilentlyContinue
   if(-not $p){continue}
   $jar=[IO.Path]::GetFullPath((Join-Path $repo "$service/target/$service-0.1.0-SNAPSHOT.jar"))
   if($p.CreationDate -lt $script:startTime){continue}
   if($p.Name -ne 'java.exe' -or -not $p.CommandLine.Contains($jar)){throw 'START_PROCESS_NOT_OWNED'}
   $record=[pscustomobject]@{service=$service;pid=[int]$p.ProcessId;creation=$p.CreationDate.ToString('o');jar=$jar;jarSha256=(Hash $jar);generation=$Generation}
   $owned[$service]=$record; [void](Identity $record)
   $facts.Add([pscustomobject]@{stage='registered';process=$record})
  }
 }
 # On a failure before services.json is written, only trust the exact PID emitted by
 # this invocation of the official script, then verify creation/JAR/JDK before private registration.
 if($startFailure){
  $startOutput=[IO.File]::ReadAllText((Join-Path $private "$Generation-start.raw.log"))
  foreach($service in $Services){
   if($owned.ContainsKey($service)){continue}
   $pattern='(?m)^'+[regex]::Escape($service)+' 已启动，PID=([0-9]+)，端口='+$ports[$service]+'\s*$'
   $matches=[regex]::Matches($startOutput,$pattern)
   if($matches.Count -ne 1){continue}
   $p=Get-CimInstance Win32_Process -Filter "ProcessId=$($matches[0].Groups[1].Value)" -ErrorAction SilentlyContinue
   $jar=[IO.Path]::GetFullPath((Join-Path $repo "$service/target/$service-0.1.0-SNAPSHOT.jar"))
   if(-not $p -or $p.CreationDate -lt $script:startTime){continue}
   $record=[pscustomobject]@{service=$service;pid=[int]$p.ProcessId;creation=$p.CreationDate.ToString('o');jar=$jar;jarSha256=(Hash $jar);generation=$Generation;partialStart=$true}
   [void](Identity $record); $owned[$service]=$record
   $facts.Add([pscustomobject]@{stage='partial-start-private-registration';process=$record})
  }
 }
 if($startFailure){throw 'OFFICIAL_START_FAILED'}
 foreach($service in $Services){if(-not $owned.ContainsKey($service)){throw 'START_MISSING_REGISTRATION'}}
}
function StopService([string]$Service){
 if(-not $owned.ContainsKey($Service)){return}
 $record=$owned[$Service]; $p=Identity $record
 if($p){
  if($record.PSObject.Properties['partialStart'] -and $record.partialStart){
   # Official start failed before manifest persistence. Stop only the privately registered PID,
   # whose creation time, executable and exact JAR have just been rechecked; never edit the manifest.
   Stop-Process -Id $record.pid
  } else {
  $entries=@((Get-Content -LiteralPath (Join-Path $repo '.local/services.json') -Raw -Encoding UTF8 | ConvertFrom-Json) | Where-Object {$_.service -eq $Service})
  if($entries.Count -ne 1 -or [int]$entries[0].pid -ne $record.pid -or [IO.Path]::GetFullPath($entries[0].jar) -ine $record.jar){throw 'STOP_MANIFEST_CHANGED'}
  & $stop -Services @($Service) *> (Join-Path $private ($record.generation+'-'+$Service+'-stop.raw.log'))
  }
  $deadline=(Get-Date).AddSeconds(20)
  while((Identity $record) -and (Get-Date) -lt $deadline){Start-Sleep -Milliseconds 100}
  if(Identity $record){throw 'OLD_PROCESS_DID_NOT_EXIT'}
 }
 if(Get-NetTCPConnection -LocalPort $ports[$Service] -State Listen -ErrorAction SilentlyContinue){throw 'STOP_PORT_NOT_EMPTY'}
 ArchiveLogs $Service ('after-'+$record.generation)
 $facts.Add([pscustomobject]@{stage='exited';service=$Service;pid=$record.pid;creation=$record.creation})
 $owned.Remove($Service)
}
function RegisterBuildChildren {
 if(-not $script:buildRecord){return}
 foreach($p in Get-CimInstance Win32_Process -Filter "ParentProcessId=$($script:buildRecord.pid)" -ErrorAction SilentlyContinue){
  if($p.CreationDate -lt $script:buildStarted -or $p.Name -ne 'java.exe' -or
     [IO.Path]::GetFullPath($p.ExecutablePath) -ine [IO.Path]::GetFullPath($java) -or
     -not $p.CommandLine.Contains('org.codehaus.plexus.classworlds.launcher.Launcher') -or
     -not $p.CommandLine.Contains((Split-Path -Parent (Split-Path -Parent $maven)))){continue}
  if(-not $buildOwned.ContainsKey([int]$p.ProcessId)){
   $buildOwned[[int]$p.ProcessId]=[pscustomobject]@{pid=[int]$p.ProcessId;creation=$p.CreationDate.ToString('o');command=$p.CommandLine;exe=$p.ExecutablePath}
  }
 }
 Save (Join-Path $private 'package-children.private.json') @($buildOwned.Values)
}
function StopBuildOwned {
 foreach($record in @($buildOwned.Values)+@($script:buildRecord)){
  if(-not $record){continue}
  $p=Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)" -ErrorAction SilentlyContinue
  if(-not $p){continue}
  if($p.CreationDate.ToString('o') -cne $record.creation -or $p.CommandLine -cne $record.command -or $p.ExecutablePath -ine $record.exe){throw 'PACKAGE_PID_IDENTITY_CHANGED'}
  Stop-Process -Id $record.pid
  $deadline=(Get-Date).AddSeconds(15)
  while((Get-Process -Id $record.pid -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline){Start-Sleep -Milliseconds 100}
  if(Get-Process -Id $record.pid -ErrorAction SilentlyContinue){throw 'PACKAGE_OWNED_PROCESS_DID_NOT_EXIT'}
 }
}
function Ready([string]$Service){
 $client=New-Object Net.Http.HttpClient; $client.Timeout=[TimeSpan]::FromSeconds(2)
 try {
  $deadline=(Get-Date).AddSeconds(100)
  while((Get-Date) -lt $deadline){
   if(-not (Identity $owned[$Service])){throw 'SERVICE_EXITED_BEFORE_READY'}
   try {$response=$client.GetAsync("http://127.0.0.1:$($ports[$Service])/actuator/health").GetAwaiter().GetResult(); $ok=[int]$response.StatusCode -eq 200; $response.Dispose()} catch {$ok=$false}
   if($ok){
    $listeners=@(Get-NetTCPConnection -LocalPort $ports[$Service] -State Listen -ErrorAction Stop)
    if(@($listeners | Where-Object {$_.OwningProcess -ne $owned[$Service].pid}).Count -gt 0){throw 'LISTENER_IDENTITY_CHANGED'}
    $facts.Add([pscustomobject]@{stage='ready';service=$Service;pid=$owned[$Service].pid;port=$ports[$Service];http=200}); return
   }
   Start-Sleep -Milliseconds 500
  }
  throw 'SERVICE_READY_TIMEOUT'
 } finally {$client.Dispose()}
}
function Probe([string]$Phase){
 $arguments=@('-Xmx128m',('-Djava.io.tmpdir="'+$private+'"'),'-Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-cp',('"'+$classpath+'"'),('"'+(Join-Path $PSScriptRoot 'ArtifactLiveProbe.java')+'"'),$Phase,('"'+$private+'"'))
 $p=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $private "$Phase-probe.out.raw.log") -RedirectStandardError (Join-Path $private "$Phase-probe.err.raw.log")
 [void]$p.Handle
 $cim=Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)" -ErrorAction Stop
 $record=[pscustomobject]@{pid=$p.Id;creation=$cim.CreationDate.ToString('o');jar=(Join-Path $PSScriptRoot 'ArtifactLiveProbe.java')}
 Save (Join-Path $private "$Phase-probe-process.json") $record
 $deadline=(Get-Date).AddSeconds(240)
 while(-not $p.WaitForExit(1000) -and (Get-Date) -lt $deadline){}
 if(-not $p.HasExited){if(Identity $record){Stop-Process -Id $p.Id}; throw 'PROBE_TIMEOUT'}
 $p.WaitForExit(); $exitCode=$p.ExitCode
 $facts.Add([pscustomobject]@{stage='probe-exit';phase=$Phase;nativeExit=$exitCode;pid=$p.Id})
 if($exitCode -ne 0){throw 'PROBE_FAILED_PRIVATE_RAW_PRESERVED'}
 $safePath=Join-Path $private "$Phase.safe.json"
 $safe=Get-Content -LiteralPath $safePath -Raw -Encoding UTF8 | ConvertFrom-Json
 if($safe.phase -ne $Phase -or $safe.passed -ne $true -or $safe.bytes -ne 67108864){throw 'PROBE_SAFE_RESULT_INVALID'}
 Copy-Item -LiteralPath $safePath -Destination (Join-Path $run "$Phase.json")
}
function Pg([string]$Sql,[string]$Db='postgres'){
 $output=& (Join-Path $pgBin 'psql.exe') -X -h 127.0.0.1 -p 25432 -U agent_v12 -d $Db -At -v ON_ERROR_STOP=1 -c $Sql 2>> (Join-Path $private 'psql.err.raw.log')
 if($LASTEXITCODE -ne 0){throw 'PG_COMMAND_FAILED'}
 return ($output -join "`n").Trim()
}
function PackagePins {
 $result=New-Object 'System.Collections.Generic.List[object]'
 foreach($module in @('admin-common','platform-gateway','skill-registry')){
  $jar=Join-Path $repo "$module/target/$module-0.1.0-SNAPSHOT.jar"
  $result.Add([pscustomobject]@{path=$jar;bytes=(Get-Item -LiteralPath $jar).Length;sha256=(Hash $jar)})
 }
 return $result.ToArray()
}
try {
 if((Test-Path -LiteralPath $private) -or (Test-Path -LiteralPath $run)){throw 'RUN_ALREADY_EXISTS'}
 if((Hash $GatewayGreenEvidence) -ine $GatewayGreenSha256 -or (Hash $gatewaySource) -ine $GatewaySourceSha256){throw 'GATEWAY_GREEN_SOURCE_OR_PROOF_CHANGED'}
 $green=Get-Content -LiteralPath $GatewayGreenEvidence -Raw -Encoding UTF8 | ConvertFrom-Json
 if($green.phase -cne 'green' -or $green.nativeExit -ne 0 -or $green.tests -ne 11 -or $green.passed -ne 11 -or
    $green.failures -ne 0 -or $green.errors -ne 0 -or $green.skipped -ne 0 -or $green.identityComplete -ne $true -or
    $green.sourceStable -ne $true -or $green.contextStable -ne $true -or $green.phaseExpectationMet -ne $true){throw 'GATEWAY_GREEN_NOT_COMPLETE'}
 $script:gatewayGreenDirectory=Split-Path -Parent ([IO.Path]::GetFullPath($GatewayGreenEvidence))
 if((Hash (Join-Path $script:gatewayGreenDirectory 'methods.xml')) -ine $green.xmlSha256 -or
    (Get-Content -LiteralPath (Join-Path $script:gatewayGreenDirectory 'native-exit.json') -Raw -Encoding UTF8 | ConvertFrom-Json).nativeExit -ne 0){throw 'GATEWAY_GREEN_RAW_MISMATCH'}
 foreach($stage in @('before','after')){
  $sourcePins=Get-Content -LiteralPath (Join-Path $script:gatewayGreenDirectory "source-$stage.json") -Raw -Encoding UTF8 | ConvertFrom-Json
  $gatewayPin=@($sourcePins | Where-Object {$_.path.Replace('\','/') -ceq 'platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java'})
  if($gatewayPin.Count -ne 1 -or $gatewayPin[0].sha256 -ine $GatewaySourceSha256){throw 'GATEWAY_GREEN_SOURCE_NOT_THIS_BUILD'}
 }
 New-Item -ItemType Directory -Path $private -Force | Out-Null; PrivateAcl $private
 New-Item -ItemType Directory -Path $run | Out-Null
 [void](OwnPath (Join-Path $private 'payload.bin'))
 & git -C $repo check-ignore --no-index --quiet -- $private
 if($LASTEXITCODE -ne 0){throw 'PRIVATE_DIRECTORY_NOT_IGNORED'}
 $maven=(Get-Command mvn.cmd -ErrorAction Stop).Source
 $ports=@{'platform-gateway'=18080;'skill-registry'=18083}
 CheckPorts @('platform-gateway','skill-registry')
 $global=@{}
 foreach($line in Get-Content -LiteralPath (Join-Path $repo '.env') -Encoding UTF8){
  if($line -match '^\s*(#|$)'){continue}; if($line -notmatch '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){throw 'ENV_FORMAT'}
  if($global.ContainsKey($Matches[1])){throw 'ENV_DUPLICATE'}; $global[$Matches[1]]=$Matches[2]
 }
 foreach($key in @($original.Keys)+@($global.Keys)){
  if($key -match '^(SPRING_(DATASOURCE|FLYWAY|CONFIG|APPLICATION_JSON|PROFILES)|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|JAVA_OPTS|MAVEN_OPTS|SERVER_PORT)'){
   $value=if($global.ContainsKey($key)){$global[$key]}else{$original[$key]}; if($value){throw 'UNAPPROVED_HIGH_PRIORITY_OVERRIDE'}
  }
 }
 foreach($key in $global.Keys){[Environment]::SetEnvironmentVariable($key,$global[$key],'Process')}
 if($env:AGENT_DB_USER -ne 'agent_v12' -or -not $env:AGENT_DB_PASSWORD){throw 'PG_CREDENTIAL_CONFIG'}
 $minio=[uri]$env:AGENT_MINIO_ENDPOINT
 if($minio.Scheme -ne 'http' -or $minio.Host -ne '127.0.0.1' -or $minio.Port -ne 29000 -or $minio.UserInfo -or
    -not $env:AGENT_MINIO_ACCESS_KEY -or -not $env:AGENT_MINIO_SECRET_KEY -or -not $env:AGENT_MINIO_BUCKET){throw 'MINIO_CONFIG_NOT_AP'}
 $env:JAVA_HOME=$javaHome; $env:PATH="$javaHome/bin;$env:PATH"; $env:MAVEN_OPTS='-Xmx1g'
 $env:PGPASSWORD=$env:AGENT_DB_PASSWORD
 $before=Pins; Save (Join-Path $run 'inputs-before.json') $before
 $initialHead=(& git -C $repo rev-parse HEAD).Trim()
 # Actual listener + executable + data directory, never a stale hard-coded PID.
 $listeners=@(Get-NetTCPConnection -LocalPort 25432 -State Listen -ErrorAction Stop)
 $pgPids=@($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
 if($pgPids.Count -ne 1){throw 'PG_LISTENER_NOT_UNIQUE'}
 $pgProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($pgPids[0])" -ErrorAction Stop
 if($pgProcess.Name -ne 'postgres.exe' -or $pgProcess.ExecutablePath.Replace('\','/') -ine 'V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe' -or
    -not $pgProcess.CommandLine.Replace('\','/').Contains('V:/.local/pgdata')){throw 'PG_INSTANCE_NOT_AP'}
 if((Pg "select current_user || '|' || current_setting('data_directory') || '|' || current_setting('port')") -ine 'agent_v12|V:/.local/pgdata|25432'){throw 'PG_DATA_DIRECTORY_MISMATCH'}
 # start-database.ps1 must only observe its two old development DBs, never create either implicitly.
 if((Pg "select count(*) from pg_database where datname in ('agent_platform_v12','agent_platform_dev_v12') and pg_get_userbyid(datdba)='agent_v12'") -ne '2'){throw 'START_DATABASE_PRECONDITIONS'}
 if((Pg "select count(*) from pg_database where datname='$database'") -ne '0'){throw 'LIVE_DATABASE_ALREADY_EXISTS'}
 Save (Join-Path $run 'admission.json') @{head=$initialHead;gatewayGreenSha256=$GatewayGreenSha256;gatewaySourceSha256=$GatewaySourceSha256;gatewayGreen=$green;postgresPid=$pgProcess.ProcessId;postgresCreation=$pgProcess.CreationDate.ToString('o');database=$database;portsInitiallyEmpty=$true}
 # Exact reactor package. No clean, install, engine-runtime, target deletion or migration repair.
 $buildArgs='/d /s /c ""'+$maven+'" -B -ntp -pl platform-gateway,skill-registry -am package -DskipTests "-Dmaven.repo.local='+$repo+'/.tools/m2""'
 $script:buildStarted=Get-Date
 $build=Start-Process -FilePath $env:ComSpec -ArgumentList $buildArgs -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $private 'package.out.raw.log') -RedirectStandardError (Join-Path $private 'package.err.raw.log')
 [void]$build.Handle
 $buildCim=Get-CimInstance Win32_Process -Filter "ProcessId=$($build.Id)" -ErrorAction Stop
 $script:buildRecord=[pscustomobject]@{pid=$build.Id;creation=$buildCim.CreationDate.ToString('o');command=$buildCim.CommandLine;exe=$buildCim.ExecutablePath}
 Save (Join-Path $private 'package-process.json') $script:buildRecord
 $buildDeadline=(Get-Date).AddSeconds(900)
 while(-not $build.WaitForExit(1000) -and (Get-Date) -lt $buildDeadline){RegisterBuildChildren}
 if(-not $build.HasExited){RegisterBuildChildren; StopBuildOwned; throw 'PACKAGE_TIMEOUT_OWNED_PROCESSES_STOPPED'}
 $build.WaitForExit(); $facts.Add([pscustomobject]@{stage='package';nativeExit=$build.ExitCode})
 if($build.ExitCode -ne 0){throw 'PACKAGE_FAILED'}
 SamePins $before (Pins)
 $packagePins=PackagePins; Save (Join-Path $run 'jars-before.json') $packagePins
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $classPins=New-Object 'System.Collections.Generic.List[object]'
 foreach($module in @('admin-common','platform-gateway','skill-registry')){
  $jarPath=Join-Path $repo "$module/target/$module-0.1.0-SNAPSHOT.jar"
  $zip=[IO.Compression.ZipFile]::OpenRead($jarPath)
  try {foreach($entry in $zip.Entries | Where-Object {$_.FullName -like '*.class' -or $_.FullName -like 'BOOT-INF/lib/admin-common-*.jar'}){
   $stream=$entry.Open(); $sha=[Security.Cryptography.SHA256]::Create()
   try {$hash=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','').ToLowerInvariant()} finally {$stream.Dispose();$sha.Dispose()}
   $classPins.Add([pscustomobject]@{module=$module;entry=$entry.FullName;bytes=$entry.Length;sha256=$hash})
  }} finally {$zip.Dispose()}
 }
 Save (Join-Path $run 'packaged-classes.json') $classPins.ToArray()
 $extracted=OwnPath (Join-Path $private 'skill-jar')
 [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $repo 'skill-registry/target/skill-registry-0.1.0-SNAPSHOT.jar'),$extracted)
 $classpath=(Join-Path $extracted 'BOOT-INF/classes')+';'+(Join-Path $extracted 'BOOT-INF/lib/*')
 # Fail if either fixed service acquired a listener while packaging.
 CheckPorts @('platform-gateway','skill-registry')
 & (Join-Path $pgBin 'createdb.exe') -h 127.0.0.1 -p 25432 -U agent_v12 -O agent_v12 -T template0 -E UTF8 $database *> (Join-Path $private 'createdb.raw.log')
 if($LASTEXITCODE -ne 0){throw 'CREATE_OWN_DATABASE_FAILED'}; $databaseCreated=$true
 if((Pg "select pg_get_userbyid(datdba) from pg_database where datname=current_database()" $database) -ne 'agent_v12' -or
    (Pg "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname not in ('pg_catalog','information_schema') and n.nspname not like 'pg_toast%' and c.relkind in ('r','p')" $database) -ne '0'){throw 'NEW_DATABASE_NOT_EMPTY_OWNED'}
 $env:EX15_PRIVATE=$private; $env:EX15_DATABASE=$database; $env:EX15_RUN=$RunId
 $env:AGENT_DB_URL='jdbc:postgresql://127.0.0.1:25432/'+$database
 $serviceFiles=@{}
 New-Item -ItemType Directory -Path (Join-Path $private 'multipart-temp') | Out-Null
 New-Item -ItemType Directory -Path (Join-Path $repo '.local/logs') -Force | Out-Null
 foreach($service in @('platform-gateway','skill-registry')){
  $lines=@(('AGENT_DB_URL='+$env:AGENT_DB_URL),'AGENT_SECURITY_MODE=dev-hmac','AGENT_BIND_ADDRESS=127.0.0.1','AGENT_DEV_AUTH=true','AGENT_DEMO_MODE=false','AGENT_PROCESS_ENABLED=false',('SERVER_PORT='+$ports[$service]),'AGENT_SKILL_URL=http://127.0.0.1:18083',('SPRING_SERVLET_MULTIPART_LOCATION='+(Join-Path $private 'multipart-temp')))
  $path=Join-Path $private ($service+'.env'); [IO.File]::WriteAllLines($path,$lines,(New-Object Text.UTF8Encoding($true))); $serviceFiles[$service]=$path
 }
 Add-Type -AssemblyName System.Net.Http
 $script:startTime=Get-Date
 StartServices @('platform-gateway','skill-registry') 'initial'
 Ready 'platform-gateway'; Ready 'skill-registry'
 $gatewayHistory=Pg "select coalesce(to_regclass('gateway.flyway_schema_history')::text,'ABSENT')" $database
 Save (Join-Path $run 'gateway-database-observation.json') @{gatewayHistoryRegclass=$gatewayHistory;gatewayHistoryPresent=($gatewayHistory -ne 'ABSENT');gatewayRequiresHistory=$false;reason='Gateway and admin-common have no JDBC/Flyway dependency or gateway migration resources; YAML does not initialize a schema.'}
 $histories=Pg "select 'skill|'||coalesce(version,'<null>')||'|'||script||'|'||coalesce(checksum::text,'<null>')||'|'||success from skill.flyway_schema_history order by installed_rank" $database
 if([string]::IsNullOrWhiteSpace($histories) -or (Pg "select count(*) from skill.flyway_schema_history where not success" $database) -ne '0'){throw 'SKILL_HISTORY_NOT_SUCCESSFUL'}
 [IO.File]::WriteAllText((Join-Path $run 'new-database-history.txt'),$histories,$utf8)
 Probe 'upload'
 $old=$owned['skill-registry']; $gatewayBefore=$owned['platform-gateway']
 StopService 'skill-registry'
 SamePins $packagePins (PackagePins)
 $script:startTime=Get-Date
 StartServices @('skill-registry') 'restarted'; Ready 'skill-registry'
 if($owned['skill-registry'].pid -eq $old.pid -or $owned['skill-registry'].creation -eq $old.creation){throw 'RESTART_IDENTITY_NOT_NEW'}
 if(-not (Identity $gatewayBefore)){throw 'GATEWAY_DID_NOT_SURVIVE_SKILL_RESTART'}
 $facts.Add([pscustomobject]@{stage='real-jvm-restart';oldPid=$old.pid;oldCreation=$old.creation;newPid=$owned['skill-registry'].pid;newCreation=$owned['skill-registry'].creation;gatewayPidUnchanged=$true})
 Probe 'restart'
 Probe 'cleanup'
 $complete=$true
} catch {
 $failureCode='EX15_LIVE_FAILED'
 if(Test-Path -LiteralPath $private){$_ | Out-String | Set-Content -LiteralPath (Join-Path $private 'failure.raw.log') -Encoding UTF8}
} finally {
 if(Test-Path -LiteralPath $private){
  try {StopBuildOwned} catch {$complete=$false;$failureCode='EX15_PACKAGE_STOP_REVIEW_REQUIRED'; $_ | Out-String | Set-Content -LiteralPath (Join-Path $private 'package-finally.raw.log') -Encoding UTF8}
  foreach($service in @('skill-registry','platform-gateway')){
   try {StopService $service} catch {$complete=$false;$failureCode='EX15_OWNED_STOP_REVIEW_REQUIRED'; $_ | Out-String | Set-Content -LiteralPath (Join-Path $private ($service+'-finally.raw.log')) -Encoding UTF8}
  }
  # Only the exact generated fixture is deleted. DB, objects, logs and all failure facts remain.
  try {$payload=OwnPath (Join-Path $private 'payload.bin'); if(Test-Path -LiteralPath $payload){Remove-Item -LiteralPath $payload}} catch {$complete=$false;$failureCode='EX15_FIXTURE_CLEANUP_REVIEW_REQUIRED'}
  try {
   if($before){$after=Pins; Save (Join-Path $run 'inputs-after.json') $after; SamePins $before $after}
   if($packagePins){$afterJars=PackagePins; Save (Join-Path $run 'jars-after.json') $afterJars; SamePins $packagePins $afterJars}
   if($initialHead -and ((& git -C $repo rev-parse HEAD).Trim() -ne $initialHead)){throw 'HEAD_CHANGED'}
  } catch {$complete=$false;$failureCode='EX15_SOURCE_OR_ARTIFACT_CHANGED'}
  if(Test-Path -LiteralPath $run){
   $raw=@(Get-ChildItem -LiteralPath $private -File | Where-Object {$_.Name -ne 'payload.bin'} | ForEach-Object {[pscustomobject]@{name=$_.Name;bytes=$_.Length;sha256=(Hash $_.FullName)}})
   Save (Join-Path $run 'private-evidence-fingerprints.json') $raw
   Save (Join-Path $run 'run.json') @{run=$RunId;complete=$complete;failureCode=$failureCode;database=$database;databaseCreated=$databaseCreated;databasePreserved=$true;head=$initialHead;facts=$facts.ToArray();publicContainsCredentials=$false;rawEvidenceDirectory=$private;noCleanInstall=$true}
  }
 }
 foreach($key in @([Environment]::GetEnvironmentVariables('Process').Keys)){if(-not $original.ContainsKey($key)){[Environment]::SetEnvironmentVariable($key,$null,'Process')}}
 foreach($key in $original.Keys){[Environment]::SetEnvironmentVariable($key,$original[$key],'Process')}
}
if(-not $complete){throw 'EX15_LIVE_FAILED: private raw evidence preserved; no database/object blanket cleanup was attempted.'}
Write-Output ('EX15_LIVE_PASS '+$RunId)
