param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ApprovedManifestSha256,
 [switch]$ExecuteApproved,
 [switch]$ExclusiveBuildWindowApproved,
 [switch]$FormalServiceStopVerified
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1){throw 'PS51_REQUIRED'}
if(-not $ExecuteApproved -or -not $ExclusiveBuildWindowApproved -or -not $FormalServiceStopVerified){throw 'EXPLICIT_RED_BUILD_WINDOW_REQUIRED'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..')).TrimEnd([char]92,[char]47)
$manifestPath=Join-Path $PSScriptRoot 'manifest.json'
if((Get-FileHash -LiteralPath $manifestPath).Hash -cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'MANIFEST_CHANGED'}
$m=[IO.File]::ReadAllText($manifestPath)|ConvertFrom-Json
$run=Join-Path $root ('.local/ex27-v222-recovery/'+$RunId)
if(Test-Path -LiteralPath $run){throw 'RUN_ALREADY_EXISTS'}
$phase='ADMISSION';$exit=2;$buildNative=$null;$testNative=$null;$prior=@{};$location=Get-Location
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash}
function Fact([string]$Name,$Value){[IO.File]::WriteAllText((Join-Path $run $Name),(ConvertTo-Json -InputObject $Value -Depth 16),(New-Object Text.UTF8Encoding($false)))}
function Pin([string]$Path,[string]$Sha){if(-not(Test-Path -LiteralPath $Path -PathType Leaf) -or (Hash $Path) -cne $Sha){throw 'PIN_CHANGED'}}
function Safe-Directory([string]$Path){
 $full=[IO.Path]::GetFullPath($Path).TrimEnd([char]92,[char]47)
 if(-not $full.Equals($root,[StringComparison]::OrdinalIgnoreCase) -and -not $full.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'PATH_OUTSIDE_AP'}
 $cursor=$full
 while($cursor.Length -ge $root.Length){
  if(Test-Path -LiteralPath $cursor){if(((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'REPARSE_PATH'}}
  if($cursor.Equals($root,[StringComparison]::OrdinalIgnoreCase)){break};$cursor=[IO.Path]::GetDirectoryName($cursor)
 }
 return $full
}
function Clean-Tree-Safe([string]$Path){
 [void](Safe-Directory $Path)
 if(-not(Test-Path -LiteralPath $Path)){return}
 $queue=New-Object 'Collections.Generic.Stack[string]';$queue.Push($Path)
 while($queue.Count -gt 0){foreach($item in Get-ChildItem -LiteralPath $queue.Pop() -Force){if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'TARGET_CONTAINS_REPARSE'};if($item.PSIsContainer){$queue.Push($item.FullName)}}}
}
function Sources {
 $files=@(Get-Item -LiteralPath (Join-Path $root 'pom.xml'))
 foreach($module in @('admin-common','engine-runtime')){$files+=Get-Item -LiteralPath (Join-Path $root ($module+'/pom.xml'));$files+=@(Get-ChildItem -LiteralPath (Join-Path $root ($module+'/src')) -File -Recurse -Force)}
 @($files|Sort-Object FullName|ForEach-Object{[pscustomobject]@{path=$_.FullName.Substring($root.Length+1).Replace([char]92,[char]47);sha256=(Hash $_.FullName)}})
}
function Same($Before,$After){
 $map=@{};foreach($row in $Before){if($map.ContainsKey($row.path)){return $false};$map[$row.path]=$row.sha256}
 return $map.Count -eq @($After).Count -and @($After|Where-Object{$map.Keys -cnotcontains $_.path -or $map[$_.path] -cne $_.sha256}).Count -eq 0
}
function Stable {
 $head=((& git -C $root rev-parse HEAD)-join '')
 if($LASTEXITCODE -ne 0 -or $head -cne $m.expectedHead -or (Hash $manifestPath) -cne $ApprovedManifestSha256.ToUpperInvariant()){return $false}
 foreach($pin in $m.pins){if((Hash (Join-Path $root $pin.path)) -cne $pin.sha256){return $false}}
 foreach($tool in $m.tools){if((Hash $tool.path) -cne $tool.sha256){return $false}}
 foreach($path in $m.mustBeAbsent){if(Test-Path -LiteralPath $path){return $false}}
 $help=Join-Path $m.repository 'org/apache/maven/plugins/maven-help-plugin/3.5.1/maven-help-plugin-3.5.1.jar'
 if((Test-Path -LiteralPath $help) -and (Hash $help) -cne $m.helpPluginSha256){return $false}
 if($m.storageApprovalProof.sha256 -cnotmatch '^[A-F0-9]{64}$' -or -not(Test-Path -LiteralPath (Join-Path $root $m.storageApprovalProof.path))){return $false}
 [void](Safe-Directory ([IO.Path]::GetDirectoryName((Join-Path $root $m.storageApprovalProof.path))))
 if((Hash (Join-Path $root $m.storageApprovalProof.path)) -cne $m.storageApprovalProof.sha256){return $false}
 return $true
}
function Read-Pg([string]$Sql){
 $psql=Join-Path $root '.tools/postgresql-16.15/pgsql/bin/psql.exe'
 $raw=& $psql -X -w -h 127.0.0.1 -p 25432 -U agent_v12 -d agent_platform_dev_v12 -At -v ON_ERROR_STOP=1 -c $Sql 2>> (Join-Path $run 'pg.raw.log')
 if($LASTEXITCODE -ne 0){throw 'READONLY_HISTORY_QUERY_FAILED'}
 return (($raw -join [Environment]::NewLine)|ConvertFrom-Json)
}
function Check-HistoricalStorage([string]$Label){
 $listeners=@(Get-NetTCPConnection -LocalPort 25432 -State Listen -ErrorAction Stop)
 if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -cne '127.0.0.1' -or $listeners[0].OwningProcess -ne $m.storage.expectedPid){throw 'AP_PG_LISTENER_CHANGED'}
 $pg=Get-CimInstance Win32_Process -Filter ('ProcessId='+$m.storage.expectedPid) -ErrorAction Stop
 if(-not $pg -or $pg.ExecutablePath.Replace([char]92,[char]47) -cne $m.storage.executable -or -not $pg.CommandLine.Replace([char]92,[char]47).Contains($m.storage.dataDirectory)){throw 'AP_PG_INSTANCE_CHANGED'}
 $facts=Read-Pg "select json_build_object('database',current_database(),'role',current_user,'dataDirectory',current_setting('data_directory'),'readOnly',current_setting('transaction_read_only'),'databaseOwner',(select pg_get_userbyid(datdba) from pg_database where datname=current_database()),'schemaOwner',(select pg_get_userbyid(nspowner) from pg_namespace where nspname='engine'),'historyOwner',(select pg_get_userbyid(relowner) from pg_class where oid=to_regclass('engine.flyway_schema_history')),'otherSessions',(select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and backend_type='client backend'))"
 if($facts.database -cne 'agent_platform_dev_v12' -or $facts.role -cne 'agent_v12' -or $facts.databaseOwner -cne 'agent_v12' -or $facts.schemaOwner -cne 'agent_v12' -or $facts.historyOwner -cne 'agent_v12' -or $facts.readOnly -cne 'on' -or $facts.dataDirectory.Replace([char]92,[char]47).TrimEnd('/') -cne 'V:/.local/pgdata' -or $facts.otherSessions -ne 0){throw 'HISTORICAL_DATABASE_OWNERSHIP_OR_SESSION_CHANGED'}
 $history=@(Read-Pg "select coalesce(json_agg(json_build_object('rank',installed_rank,'version',version,'type',type,'script',script,'checksum',checksum,'installedBy',installed_by,'success',success) order by installed_rank),'[]'::json) from engine.flyway_schema_history")
 if(@($history|Where-Object{$_.success -ne $true -or $_.installedBy -cne 'agent_v12' -or $_.type -cnotin @('SQL','SCHEMA')}).Count -ne 0){throw 'FAILED_OR_UNKNOWN_HISTORY'}
 $sql=@($history|Where-Object type -ceq 'SQL');$markers=@($history|Where-Object type -ceq 'SCHEMA')
 if($sql.Count -ne 24 -or @($sql|Group-Object version|Where-Object Count -ne 1).Count -ne 0 -or @($sql|Where-Object{$_.rank -le 0}).Count -ne 0 -or @($sql|Group-Object rank|Where-Object Count -ne 1).Count -ne 0){throw 'EXACT_24_SQL_HISTORY_REQUIRED'}
 if($markers.Count -gt 1 -or @($markers|Where-Object{$_.rank -ne 0 -or $null -ne $_.version -or $null -ne $_.checksum -or $_.script -cne '"engine"'}).Count -ne 0){throw 'SCHEMA_MARKER_NOT_ORIGINAL'}
 if(@($m.knownSqlCatalog).Count -ne 24 -or (@($m.knownSqlCatalog.version|Sort-Object) -join '|') -cne ((200..223|ForEach-Object{[string]$_}) -join '|')){throw 'FIXED_SOURCE_CATALOG_INVALID'}
 foreach($expected in $m.knownSqlCatalog){$row=@($sql|Where-Object version -ceq $expected.version);if($row.Count -ne 1 -or $row[0].script -cne $expected.script -or [string]$row[0].checksum -cne [string]$expected.checksum){throw 'SOURCE_CHECKSUM_OR_SCRIPT_MISMATCH'}}
 $columns=@(Read-Pg "select coalesce(json_agg(json_build_object('udtSchema',udt_schema,'udtName',udt_name,'nullable',is_nullable,'default',column_default)),'[]'::json) from information_schema.columns where table_schema='engine' and table_name='run' and column_name='context_pack'")
 if($columns.Count -ne 1 -or $columns[0].udtSchema -cne 'pg_catalog' -or $columns[0].udtName -cne 'jsonb' -or $columns[0].nullable -cne 'YES' -or $null -ne $columns[0].default){throw 'CONTEXT_PACK_COLUMN_SHAPE_CHANGED'}
 $value=[ordered]@{facts=$facts;sqlHistory=$sql;schemaMarkers=$markers;column=$columns[0];pgPid=$pg.ProcessId;pgCreatedAt=$pg.CreationDate.ToString('o');sqlRows=24;totalRows=$history.Count;readOnly=$true;businessValuesRead=$false;ddl=$false}
 Fact ($Label+'-history.json') $value
 return $value
}
function Check-NoFormalServices([string]$Label){
 $records=@([IO.File]::ReadAllText((Join-Path $root '.local/services.json'))|ConvertFrom-Json)
 $observed=@()
 foreach($record in $records){
  if($m.formalServices -cnotcontains $record.service -or [int]$record.pid -le 0){throw 'SERVICE_RECORD_SHAPE_CHANGED'}
  $expected=[IO.Path]::GetFullPath((Join-Path $root ($record.service+'/target/'+$record.service+'-0.1.0-SNAPSHOT.jar')))
  if(-not([IO.Path]::GetFullPath($record.jar)).Equals($expected,[StringComparison]::OrdinalIgnoreCase)){throw 'SERVICE_RECORD_PATH_CHANGED'}
  $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$record.pid) -ErrorAction Stop
  if($process){throw 'REGISTERED_PID_STILL_EXISTS_OR_REUSED'}
  $observed+=[ordered]@{service=$record.service;registeredPid=[int]$record.pid;absent=$true}
 }
 # 仅询问命令行含本仓正式 JAR 绝对路径的 java.exe，不枚举或记录其他工程进程。
 foreach($service in $m.formalServices){foreach($base in @($root,'V:')){
  foreach($slash in @([char]92,[char]47)){
   $jar=($base+'/'+$service+'/target/'+$service+'-0.1.0-SNAPSHOT.jar').Replace([char]92,[char]47).Replace([char]47,$slash)
   $wql=$jar.Replace('\','\\').Replace("'","''")
   $query="SELECT ProcessId FROM Win32_Process WHERE Name='java.exe' AND CommandLine LIKE '%"+$wql+"%'"
   if(@(Get-CimInstance -Query $query -ErrorAction Stop).Count -ne 0){throw 'FORMAL_JAR_STILL_IN_USE'}
  }
 }}
 Fact ($Label+'-services.json') ([ordered]@{registered=$observed;formalJarCommandLineMatches=0;otherProjectsEnumerated=$false;processesStoppedOrStarted=$false})
}
function Archive-Sql([string]$Jar,[string]$Entry){
 $zip=[IO.Compression.ZipFile]::OpenRead($Jar)
 try{
  $entries=@($zip.Entries|Where-Object FullName -ceq $Entry)
  if($entries.Count -ne 1 -or $entries[0].Length -ne 286){throw 'ARCHIVE_SQL_ENTRY_CHANGED'}
  $stream=$entries[0].Open();$sha=[Security.Cryptography.SHA256]::Create()
  try{$value=([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-','');if($value -cne $m.v222Sha256){throw 'ARCHIVE_SQL_BYTES_CHANGED'}}finally{$stream.Dispose();$sha.Dispose()}
 }finally{$zip.Dispose()}
}
function Maven([string]$Label,[string[]]$Arguments){
 $start=[DateTime]::UtcNow;$native=$null
 Fact ($Label+'-command.json') ([ordered]@{command=$m.maven;arguments=$Arguments;startedAtUtc=$start.ToString('o');failureIgnore=$false})
 try{$ErrorActionPreference='Continue';& $m.maven @Arguments *> (Join-Path $run ($Label+'.raw.log'));$native=$LASTEXITCODE}
 finally{$ErrorActionPreference='Stop';Fact ($Label+'-native.json') ([ordered]@{nativeExit=$native;startedAtUtc=$start.ToString('o');endedAtUtc=[DateTime]::UtcNow.ToString('o')})}
 return [pscustomobject]@{nativeExit=$native;startedAt=$start}
}
function Installed {
 @($m.installedArtifactPaths|ForEach-Object{[pscustomobject]@{path=$_;sha256=(Hash (Join-Path $root $_))}})
}
function Require-RedResources([string]$Label) {
 $src=Join-Path $root 'engine-runtime/src/main/resources/db/migration'
 $target=Join-Path $root 'engine-runtime/target/classes/db/migration'
 $actual=@(Get-ChildItem -LiteralPath $target -File -Filter '*.sql'|ForEach-Object{[pscustomobject]@{path=$_.Name;sha256=(Hash $_.FullName)}})
 $expected=@(Get-ChildItem -LiteralPath $src -File -Filter '*.sql'|ForEach-Object{[pscustomobject]@{path=$_.Name;sha256=(Hash $_.FullName)}})
 if($expected.Count -ne 23 -or -not(Same $expected $actual) -or (Test-Path -LiteralPath (Join-Path $target 'V222__run_context_pack.sql'))){throw 'RED_RESOURCE_OUTPUT_NOT_SOURCE_ONLY'}
 $jar=Join-Path $root 'engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar'
 $zip=[IO.Compression.ZipFile]::OpenRead($jar)
 try{
  $sql=@($zip.Entries|Where-Object{$_.FullName -cmatch '^BOOT-INF/classes/db/migration/V[^/]+\.sql$'})
  if($sql.Count -ne 23 -or @($sql|Where-Object Name -ceq 'V222__run_context_pack.sql').Count -ne 0){throw 'RED_JAR_RESOURCES_CHANGED'}
  foreach($entry in $sql){$s=$entry.Open();$sha=[Security.Cryptography.SHA256]::Create();try{$value=([BitConverter]::ToString($sha.ComputeHash($s))).Replace('-','');$match=@($expected|Where-Object path -ceq $entry.Name);if($match.Count -ne 1 -or $match[0].sha256 -cne $value){throw 'RED_JAR_SQL_NOT_CURRENT_SOURCE'}}finally{$s.Dispose();$sha.Dispose()}}
 }finally{$zip.Dispose()}
 Fact ($Label+'-built-resources.json') ([ordered]@{migrations=$actual;count=23;v222Absent=$true;jarSha256=(Hash $jar);oldEngineJarAddedByCollector=$false})
}
try{
 Set-Location -LiteralPath $root
 [void](Safe-Directory $run);[IO.Directory]::CreateDirectory($run)|Out-Null
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 if(-not([IO.Path]::GetFullPath($m.repository)).Equals([IO.Path]::GetFullPath((Join-Path $root '.tools/m2')),[StringComparison]::OrdinalIgnoreCase)){throw 'AP_OWN_REPOSITORY_REQUIRED'}
 if($m.storageApprovalProof.sha256 -cnotmatch '^[A-F0-9]{64}$'){throw 'ACTUAL_HISTORY_PROOF_NOT_FROZEN'}
 if(-not(Stable)){throw 'IMMUTABLE_CONTEXT_CHANGED'}
 $before=Sources;Fact 'source-before.json' $before
 if(-not(Same $m.sources $before)){throw 'SOURCE_INVENTORY_CHANGED'}
 Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $run 'approved-manifest.json')
 Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $run 'collector.ps1')
 foreach($pin in $m.preCleanPins){Pin (Join-Path $root $pin.path) $pin.sha256}
 Archive-Sql (Join-Path $root ($m.archiveDirectory+'/engine-before.jar')) 'BOOT-INF/classes/db/migration/V222__run_context_pack.sql'
 Archive-Sql (Join-Path $root ($m.archiveDirectory+'/engine-before.jar.original')) 'db/migration/V222__run_context_pack.sql'
 if((Get-Item -LiteralPath (Join-Path $root ($m.archiveDirectory+'/V222__run_context_pack.sql'))).Length -ne 286){throw 'ARCHIVE_SQL_LENGTH_CHANGED'}
 $dependencyBackup=Join-Path $run 'installed-common-before';[IO.Directory]::CreateDirectory($dependencyBackup)|Out-Null
 foreach($pin in $m.commonBefore){$path=Join-Path $root $pin.path;Pin $path $pin.sha256;$copy=Join-Path $dependencyBackup ([IO.Path]::GetFileName($path));Copy-Item -LiteralPath $path -Destination $copy;Pin $copy $pin.sha256}
 Fact 'dependency-backup.json' ([ordered]@{oldInstalledCommon=$m.commonBefore;backupDirectory=$dependencyBackup;immutableArchiveDirectory=$m.archiveDirectory;automaticDependencyRestore=$false})
 foreach($key in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_ARGS','MAVEN_DEBUG_OPTS','JVM_CONFIG_MAVEN_PROPS','CLASSWORLDS_LAUNCHER')){if(-not[string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key,'Process'))){throw 'HIDDEN_JVM_OR_MAVEN_OPTIONS'}}
 if($env:MAVEN_OPTS -and $env:MAVEN_OPTS.Trim() -cne '-Xmx512m'){throw 'UNAPPROVED_MAVEN_OPTS'}
 foreach($key in @('JAVA_HOME','PATH','MAVEN_OPTS','MAVEN_SKIP_RC','MAVEN_BASEDIR','MAVEN_BATCH_ECHO','MAVEN_BATCH_PAUSE','MAVEN_TERMINATE_CMD','PGPASSWORD','PGCONNECT_TIMEOUT','PGOPTIONS')){$prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
 $env:JAVA_HOME=$m.jdk;$env:PATH=$m.jdk+'/bin;'+$env:PATH;$env:MAVEN_OPTS='-Xmx512m';$env:MAVEN_SKIP_RC='1';$env:MAVEN_BASEDIR=$root;$env:MAVEN_BATCH_ECHO='off';$env:MAVEN_BATCH_PAUSE='off';$env:MAVEN_TERMINATE_CMD='off'
 $values=@{};foreach($line in [IO.File]::ReadAllLines((Join-Path $root '.env'))){if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
 if($values.AGENT_DB_URL -cne $m.storage.jdbcUrl -or $values.AGENT_DB_USER -cne 'agent_v12' -or [string]::IsNullOrEmpty($values.AGENT_DB_PASSWORD)){throw 'PINNED_DB_ENVIRONMENT_INVALID'}
 $env:PGPASSWORD=$values.AGENT_DB_PASSWORD;$env:PGCONNECT_TIMEOUT='5';$env:PGOPTIONS='-c statement_timeout=5000 -c default_transaction_read_only=on'
 $phase='READONLY_HISTORY_BEFORE_CLEAN';$historyBefore=Check-HistoricalStorage 'before-clean'
 Copy-Item -LiteralPath (Join-Path $root $m.storageApprovalProof.path) -Destination (Join-Path $run 'approved-history-proof.json')
 $phase='SERVICE_AND_TARGET_ADMISSION';Check-NoFormalServices 'before-resolve'
 $resolved=@()
 foreach($project in $m.projects){
  $pom=Join-Path $root $project.pom;[xml]$xml=[IO.File]::ReadAllText($pom)
  if([string]$xml.project.artifactId -cne $project.artifactId){throw 'PROJECT_COORDINATE_CHANGED'}
  $result=Maven ('resolve-'+$project.artifactId) @('-B','-ntp','-N','-f',$pom,'org.apache.maven.plugins:maven-help-plugin:3.5.1:evaluate','-Dexpression=project.build.directory','-DforceStdout=false','-Dstyle.color=never',('-Dmaven.repo.local='+$m.repository))
  if($result.nativeExit -ne 0){throw 'PROJECT_TARGET_RESOLUTION_FAILED'}
  Pin (Join-Path $m.repository 'org/apache/maven/plugins/maven-help-plugin/3.5.1/maven-help-plugin-3.5.1.jar') $m.helpPluginSha256
  $values=@([IO.File]::ReadAllLines((Join-Path $run ('resolve-'+$project.artifactId+'.raw.log')))|ForEach-Object{$_.Trim()}|Where-Object{$_ -cmatch '^[A-Za-z]:[\\/]'}|ForEach-Object{Safe-Directory $_})
  $expected=Safe-Directory (Join-Path $root $project.target)
  if($values.Count -ne 1 -or $values[0] -cne $expected){throw 'EXACT_PROJECT_TARGET_NOT_RESOLVED'}
  Clean-Tree-Safe $values[0];$resolved+=$values[0]
 }
 if($resolved.Count -ne 3 -or @($resolved|Select-Object -Unique).Count -ne 3){throw 'THREE_EXACT_TARGETS_REQUIRED'}
 Fact 'resolved-targets.json' ([ordered]@{targets=$resolved;independentPomEvaluation=$true;reparseRejected=$true})
 if(-not(Stable) -or -not(Same $m.sources (Sources))){throw 'CONTEXT_CHANGED_BEFORE_CLEAN'}
 Check-NoFormalServices 'before-clean';foreach($target in $resolved){Clean-Tree-Safe $target}
 $phase='CURRENT_SOURCE_CLEAN_INSTALL'
 $build=Maven 'build' @('-B','-ntp','-pl','engine-runtime','-am','clean','install','-DskipTests','-Dmaven.test.failure.ignore=false',('-Dmaven.repo.local='+$m.repository))
 $buildNative=$build.nativeExit;if($buildNative -ne 0){throw 'BUILD_FAILED_NOT_RESOURCE_RED'}
 $installed=@(Installed);Fact 'installed-before-test.json' $installed
 Require-RedResources 'before-test'
 if(-not(Stable) -or -not(Same $m.sources (Sources))){throw 'CONTEXT_CHANGED_DURING_BUILD'}
 $phase='FOUR_RESOURCE_TESTS'
 $test=Maven 'test' @('-B','-ntp','-pl','engine-runtime','test',('-Dmaven.repo.local='+$m.repository),'-Dtest=EngineMigrationsTest','-Dmaven.test.failure.ignore=false','-Dsurefire.failIfNoSpecifiedTests=true','-Dsurefire.timeout=900','-DargLine=-Xmx1024m')
 $testNative=$test.nativeExit
 $phase='COMPARE_RED'
 $fresh=@(Get-ChildItem -LiteralPath (Join-Path $root 'engine-runtime/target/surefire-reports') -File -Filter 'TEST-*.xml'|Where-Object{$_.LastWriteTimeUtc -ge $test.startedAt})
 if($fresh.Count -ne 1 -or $fresh[0].Name -cne 'TEST-cn.agentplatform.engine.infra.EngineMigrationsTest.xml'){throw 'FOUR_METHOD_XML_MISSING'}
 Copy-Item -LiteralPath $fresh[0].FullName -Destination (Join-Path $run 'methods.xml')
 $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load((Join-Path $run 'methods.xml'))
 $methods=@(foreach($case in $doc.SelectNodes('//testcase')){$failure=$case.SelectSingleNode('./failure');$errorNode=$case.SelectSingleNode('./error');$skip=$case.SelectSingleNode('./skipped');[pscustomobject]@{class=$case.GetAttribute('classname');method=$case.GetAttribute('name');status=if($errorNode){'ERROR'}elseif($failure){'FAIL'}elseif($skip){'SKIP'}else{'PASS'};exactResourceMissing=[bool]($failure -and $failure.GetAttribute('type') -ceq 'org.opentest4j.AssertionFailedError' -and $failure.GetAttribute('message') -ceq 'V222_RESOURCE_REQUIRED ==> expected: not <null>' -and $failure.InnerText.Contains('EngineMigrationsTest.preservesExecutedV222MigrationResourceBytes'))}})
 Fact 'methods.json' $methods
 $identity=$methods.Count -eq 4 -and @($methods.method|Select-Object -Unique).Count -eq 4 -and @($methods|Where-Object{$_.class -cne 'cn.agentplatform.engine.infra.EngineMigrationsTest' -or $m.methods -cnotcontains $_.method}).Count -eq 0
 $oldOkay=@($methods|Where-Object{$_.method -cne 'preservesExecutedV222MigrationResourceBytes' -and $_.status -ceq 'PASS'}).Count -eq 3
 $newRed=@($methods|Where-Object{$_.method -ceq 'preservesExecutedV222MigrationResourceBytes' -and $_.status -ceq 'FAIL' -and $_.exactResourceMissing}).Count -eq 1
 $after=Sources;Fact 'source-after.json' $after;$sourceStable=Same $m.sources $after;$contextStable=Stable
 $installedAfter=@(Installed);Fact 'installed-after-test.json' $installedAfter;$dependencyStable=Same $installed $installedAfter
 Require-RedResources 'after-test';Check-NoFormalServices 'after-test';$historyAfter=Check-HistoricalStorage 'after-test'
 $historyStable=(ConvertTo-Json -InputObject $historyBefore -Depth 15 -Compress) -ceq (ConvertTo-Json -InputObject $historyAfter -Depth 15 -Compress)
 $gate=$buildNative -eq 0 -and $testNative -eq 1 -and $identity -and $oldOkay -and $newRed -and $sourceStable -and $contextStable -and $dependencyStable -and $historyStable
 $summary=[ordered]@{phase='RED';buildNativeExit=$buildNative;testNativeExit=$testNative;records=$methods.Count;oldThreePassed=$oldOkay;newResourceMissingFailure=$newRed;exactIdentities=$identity;sourceStable=$sourceStable;contextStable=$contextStable;newInstalledDependenciesStable=$dependencyStable;historicalStorageUnchanged=$historyStable;collectorGatePassed=$gate;sqlSourceRestored=$false;pgMetadataReadOnly=$true;businessValuesRead=$false;httpCalled=$false;formalServicesMutated=$false;wholeCardClosed=$false;xmlSha256=(Hash (Join-Path $run 'methods.xml'))}
 Fact 'summary.json' $summary;$exit=if($gate){0}else{1};$summary|ConvertTo-Json -Depth 4
}catch{
 $reason=if($_.Exception.Message -cmatch '^[A-Z_0-9]+$'){$_.Exception.Message}else{'UNEXPECTED_COLLECTOR_ERROR'}
 if(Test-Path -LiteralPath $run){Fact 'collector-error.json' ([ordered]@{phase=$phase;reason=$reason;line=$_.InvocationInfo.ScriptLineNumber;exceptionType=$_.Exception.GetType().FullName;buildNativeExit=$buildNative;testNativeExit=$testNative;notAutomaticallyResourceRed=$true})}
 Write-Output ('V222_RED_STOPPED phase='+$phase+' reason='+$reason)
}finally{
 if(Test-Path -LiteralPath $run){Fact 'collector-exit.json' ([ordered]@{collectorExit=$exit;buildNativeExit=$buildNative;testNativeExit=$testNative;automaticProcessOrDependencyRestore=$false});if(-not(Test-Path -LiteralPath (Join-Path $run 'source-after.json'))){Fact 'source-after.json' (Sources)}}
 foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')};Set-Location -LiteralPath $location
}
exit $exit
