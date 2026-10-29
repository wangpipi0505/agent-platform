param(
 [Parameter(Mandatory=$true)][ValidateSet('Adjacent','Full')][string]$Mode,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ApprovedManifestSha256,
 [switch]$ExecuteApproved,[switch]$ExclusiveRuntimeWindowApproved
)
# Static candidate: sealing requires the ACTUAL Green receipt and approved fixture fingerprints.
# No build/clean/install, collector DDL, queue cleanup, process start/stop, or reason normalization.
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1){throw 'PS51_REQUIRED'}
if(-not $ExecuteApproved -or -not $ExclusiveRuntimeWindowApproved){throw 'EXPLICIT_RUNTIME_WINDOW_REQUIRED'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..')).TrimEnd([char]92,[char]47)
$manifestPath=Join-Path $PSScriptRoot 'regression-manifest.json'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash}
function Read-Json([string]$Path){$value=[IO.File]::ReadAllText($Path)|ConvertFrom-Json;return $value}
function Pin([string]$Path,[string]$Sha){if($Sha -cnotmatch '^[A-F0-9]{64}$' -or -not(Test-Path -LiteralPath $Path -PathType Leaf) -or (Hash $Path) -cne $Sha){throw 'PIN_CHANGED'}}
Pin $manifestPath $ApprovedManifestSha256.ToUpperInvariant()
$m=Read-Json $manifestPath
if($m.sealed -ne $true -or $m.green.receipt.sha256 -cnotmatch '^[A-F0-9]{64}$'){throw 'ACTUAL_GREEN_INPUTS_NOT_SEALED'}
if($Mode -ceq 'Full' -and ($m.fullFixtureContext.sealed -ne $true -or @($m.fullFixtureContext.files).Count -eq 0)){throw 'FULL_FIXTURE_CONTEXT_NOT_SEALED'}
$run=Join-Path $root ('.local/ex27-v222-recovery/'+$RunId)
if(Test-Path -LiteralPath $run){throw 'RUN_ALREADY_EXISTS'}
$phase='ADMISSION';$exit=2;$native=$null;$prior=@{};$location=Get-Location;$storageBefore=$null;$storageAfter=$null;$invoked=$false
function Fact([string]$Name,$Value){[IO.File]::WriteAllText((Join-Path $run $Name),(ConvertTo-Json -InputObject $Value -Depth 32),(New-Object Text.UTF8Encoding($false)))}
function Safe-Path([string]$Path){
 $full=[IO.Path]::GetFullPath($Path).TrimEnd([char]92,[char]47)
 if(-not $full.Equals($root,[StringComparison]::OrdinalIgnoreCase) -and -not $full.StartsWith($root+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'PATH_OUTSIDE_AP'}
 $cursor=$full
 while($cursor.Length -ge $root.Length){if(Test-Path -LiteralPath $cursor){if(((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'REPARSE_PATH'}};if($cursor.Equals($root,[StringComparison]::OrdinalIgnoreCase)){break};$cursor=[IO.Path]::GetDirectoryName($cursor)}
 return $full
}
function Private-Directory([string]$Path){
 [void](Safe-Path $Path);[IO.Directory]::CreateDirectory($Path)|Out-Null
 $acl=New-Object Security.AccessControl.DirectorySecurity;$acl.SetAccessRuleProtection($true,$false)
 $current=[Security.Principal.WindowsIdentity]::GetCurrent().User
 foreach($sid in @($current,(New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))){
  $rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow');$acl.AddAccessRule($rule)
 }
 Set-Acl -LiteralPath $Path -AclObject $acl
}
function Sources {
 $files=@(Get-Item -LiteralPath (Join-Path $root 'pom.xml'))
 foreach($module in @('admin-common','engine-runtime')){$files+=Get-Item -LiteralPath (Join-Path $root ($module+'/pom.xml'));$files+=@(Get-ChildItem -LiteralPath (Join-Path $root ($module+'/src')) -File -Recurse -Force)}
 @($files|Sort-Object FullName|ForEach-Object{[void](Safe-Path $_.FullName);[pscustomobject]@{path=$_.FullName.Substring($root.Length+1).Replace([char]92,[char]47);sha256=(Hash $_.FullName)}})
}
function Same($Before,$After){
 $map=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::Ordinal)
 foreach($row in @($Before)){if($map.ContainsKey($row.path)){return $false};$map.Add($row.path,$row.sha256)}
 if($map.Count -ne @($After).Count){return $false};$seen=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
 foreach($row in @($After)){if(-not $seen.Add($row.path) -or -not $map.ContainsKey($row.path) -or $map[$row.path] -cne $row.sha256){return $false}};return $true
}
function Equal-Json($A,$B){return (ConvertTo-Json -InputObject $A -Depth 30 -Compress) -ceq (ConvertTo-Json -InputObject $B -Depth 30 -Compress)}
function Stable {
 $head=((& git -C $root rev-parse HEAD)-join '')
 if($LASTEXITCODE -ne 0 -or $head -cne $m.expectedHead -or (Hash $manifestPath) -cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'HEAD_OR_MANIFEST_CHANGED'}
 foreach($pin in $m.pins){Pin (Join-Path $root $pin.path) $pin.sha256}
 foreach($tool in $m.tools){Pin $tool.path $tool.sha256}
 foreach($path in $m.mustBeAbsent){if(Test-Path -LiteralPath $path){throw 'UNAPPROVED_CONFIG_PRESENT'}}
}
function Installed {@($green.installedArtifacts|ForEach-Object{[pscustomobject]@{path=$_.path;sha256=(Hash (Join-Path $root $_.path))}})}
function Check-Green {
 Pin (Join-Path $root $m.green.receipt.path) $m.green.receipt.sha256
 foreach($pin in $m.green.pins){Pin (Join-Path $root $pin.path) $pin.sha256}
 $script:green=Read-Json (Join-Path $root $m.green.receipt.path)
 if($green.schema -cne 'EX27-V222-GREEN-dependency-receipt-v1' -or $green.runId -cne $m.green.runId -or $green.head -cne $m.expectedHead -or $green.redRunId -cne $m.red.runId -or $green.records -ne 4 -or $green.passed -ne 4 -or $green.buildNativeExit -ne 0 -or $green.testNativeExit -ne 0 -or $green.collectorGatePassed -ne $true -or $green.sourceCount -ne 306 -or $green.sqlResourceCount -ne 24 -or $green.v222Sha256 -cne $m.restoredSql.sha256){throw 'GREEN_RECEIPT_NOT_ACTUAL_COMPLETE_GREEN'}
 $dir=Join-Path $root $m.green.directory
 foreach($pair in @(@('approved-manifest.json','manifestSha256'),@('collector.ps1','runnerSha256'),@('source-after.json','sourceAfterSha256'),@('installed-after-test.json','installedAfterSha256'),@('after-test-built-resources.json','builtResourcesSha256'),@('after-test-history.json','historyAfterSha256'),@('summary.json','summarySha256'))){Pin (Join-Path $dir $pair[0]) $green.($pair[1])}
 Pin (Join-Path $root $m.historyApproval.path) $green.approvedHistoryProofSha256
 $redManifest=Read-Json (Join-Path $root $m.red.manifestPath)
 $redSources=Read-Json (Join-Path $root $m.red.sourcesPath)
 if(@($redSources).Count -ne 305 -or -not(Same $redManifest.sources $redSources) -or -not(Same (@($redSources)+@($m.restoredSql)) $m.sources) -or -not(Same (Read-Json (Join-Path $dir 'source-after.json')) $m.sources)){throw 'SOURCE_NOT_RED_305_PLUS_ONE_SQL'}
 $paths=@($green.installedArtifacts|ForEach-Object{[pscustomobject]@{path=$_.path;sha256='path'}})
 $required=@($m.installedArtifactPaths|ForEach-Object{[pscustomobject]@{path=$_;sha256='path'}})
 if(@($green.installedArtifacts).Count -ne 6 -or -not(Same $required $paths) -or -not(Same $green.installedArtifacts (Read-Json (Join-Path $dir 'installed-after-test.json'))) -or -not(Same $green.installedArtifacts (Installed))){throw 'GREEN_DEPENDENCIES_CHANGED'}
 if($green.engineJarPath -cne 'engine-runtime/target/engine-runtime-0.1.0-SNAPSHOT.jar'){throw 'GREEN_JAR_SCOPE_CHANGED'}
 Pin (Join-Path $root $green.engineJarPath) $green.engineJarSha256
}
function Fixture-Context {
 # Only the already used app fixture classpath. No rebuilding missing fixture classes.
 Pin (Join-Path $root $m.fullFixtureContext.classpath.path) $m.fullFixtureContext.classpath.sha256
 $cp=[IO.File]::ReadAllText((Join-Path $root $m.fullFixtureContext.classpath.path)).Trim()
 $files=New-Object 'Collections.Generic.List[object]'
 foreach($entry in ($cp -split ';')){
  $full=Safe-Path $entry;$relative=$full.Substring($root.Length+1).Replace([char]92,[char]47)
  if($relative -cnotmatch '^(\.tools/m2/|app-management/target/(classes|test-classes)(/|$)|admin-common/target/classes(/|$))'){throw 'FIXTURE_CLASSPATH_OUTSIDE_SCOPE'}
  $item=Get-Item -LiteralPath $full -Force
  if($item.PSIsContainer){$items=@(Get-ChildItem -LiteralPath $full -Recurse -File -Force)}else{$items=@($item)}
  foreach($file in $items){[void](Safe-Path $file.FullName);$files.Add([pscustomobject]@{path=$file.FullName.Substring($root.Length+1).Replace([char]92,[char]47);sha256=(Hash $file.FullName)})}
 }
 if(-not(Same $m.fullFixtureContext.files $files.ToArray())){throw 'FIXTURE_CLASSPATH_BYTES_OR_FILE_SET_CHANGED'}
 return $files.ToArray()
}
function Sql-Checksum([string]$Path){
 $utf8=New-Object Text.UTF8Encoding($false,$true);$reader=New-Object IO.StringReader($utf8.GetString([IO.File]::ReadAllBytes($Path)))
 [uint32]$crc=[uint32]::MaxValue;$first=$true
 try{while($null -ne ($line=$reader.ReadLine())){if($first){if($line.Length -gt 0 -and $line[0] -eq [char]0xFEFF){$line=$line.Substring(1)};$first=$false};foreach($byte in $utf8.GetBytes($line)){$crc=$crc -bxor [uint32]$byte;for($bit=0;$bit -lt 8;$bit++){if(($crc -band 1) -ne 0){$crc=($crc -shr 1) -bxor [uint32]3988292384}else{$crc=$crc -shr 1}}}}}finally{$reader.Dispose()}
 return [BitConverter]::ToInt32([BitConverter]::GetBytes([uint32]($crc -bxor [uint32]::MaxValue)),0)
}
function Read-Pg([string]$Database,[string]$Sql){
 $raw=& (Join-Path $root '.tools/postgresql-16.15/pgsql/bin/psql.exe') -X -w -h 127.0.0.1 -p 25432 -U agent_v12 -d $Database -At -v ON_ERROR_STOP=1 -c $Sql 2>> (Join-Path $run 'pg.raw.log')
 if($LASTEXITCODE -ne 0){throw 'READONLY_PG_QUERY_FAILED'};$value=($raw -join [Environment]::NewLine)|ConvertFrom-Json;return $value
}
function Check-Storage([string]$Label){
 $listeners=@(Get-NetTCPConnection -LocalPort 25432 -State Listen -ErrorAction Stop)
 if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -cne '127.0.0.1' -or $listeners[0].OwningProcess -ne $m.storage.pgPid){throw 'PG_LISTENER_CHANGED'}
 $pg=Get-CimInstance Win32_Process -Filter ('ProcessId='+$m.storage.pgPid) -ErrorAction Stop
 if(-not $pg -or $pg.CreationDate.ToString('o') -cne $m.storage.pgCreatedAt -or $pg.ExecutablePath.Replace([char]92,[char]47) -cne $m.storage.pgExecutable -or -not $pg.CommandLine.Replace([char]92,[char]47).Contains($m.storage.pgDataDirectory)){throw 'PG_PROCESS_IDENTITY_CHANGED'}
 foreach($sql in $m.sqlCatalog){Pin (Join-Path $root $sql.path) $sql.sha256;if((Sql-Checksum (Join-Path $root $sql.path)) -ne $sql.checksum){throw 'SQL_CHECKSUM_CHANGED'}}
 $old=Read-Json (Join-Path $root $m.storage.originalAdmission.path)
 if($old.passed -ne $true -or @($old.databaseProofs).Count -ne 14){throw 'ORIGINAL_ADMISSION_INVALID'}
 $facts=New-Object 'Collections.Generic.List[object]'
 foreach($db in @($m.storage.fixedDatabases)+@('agent_platform_dev_v12')){
  if($db -cnotmatch '^agent_platform_[a-z0-9_]+$'){throw 'PG_DATABASE_SCOPE_INVALID'}
  $meta=Read-Pg $db "select json_build_object('database',current_database(),'role',current_user,'owner',(select pg_get_userbyid(datdba) from pg_database where datname=current_database()),'dataDirectory',current_setting('data_directory'),'readOnly',current_setting('transaction_read_only'),'timeout',current_setting('statement_timeout'),'otherClients',(select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and backend_type='client backend'))"
  if($meta.database -cne $db -or $meta.role -cne 'agent_v12' -or $meta.owner -cne 'agent_v12' -or $meta.dataDirectory.Replace([char]92,[char]47).TrimEnd('/') -cne $m.storage.pgDataDirectory -or $meta.readOnly -cne 'on' -or $meta.timeout -cne '5s' -or $meta.otherClients -ne 0){throw 'PG_OWNER_OR_SESSION_CHANGED'}
  $expected=@($old.databaseProofs|Where-Object database -ceq $db);$history=@();$schema=$null
  if($db -ceq 'agent_platform_test_input_test'){
   $absent=Read-Pg $db "select json_build_object('schemaPresent',to_regnamespace('engine') is not null,'historyPresent',to_regclass('engine.flyway_schema_history') is not null)"
   if($absent.schemaPresent -ne $false -or $absent.historyPresent -ne $false){throw 'ORIGINAL_TEST_INPUT_EMPTY_STATE_CHANGED'}
  }elseif($expected.Count -eq 1 -and $expected[0].schema){
   $schema=$expected[0].schema;if($schema -cnotin @('engine','app')){throw 'PG_SCHEMA_SCOPE_INVALID'}
   $owners=Read-Pg $db ("select json_build_object('schemaOwner',(select pg_get_userbyid(nspowner) from pg_namespace where nspname='"+$schema+"'),'historyOwner',(select pg_get_userbyid(relowner) from pg_class where oid=to_regclass('"+$schema+".flyway_schema_history')))" )
   if($owners.schemaOwner -cne 'agent_v12' -or $owners.historyOwner -cne 'agent_v12'){throw 'PG_SCHEMA_OWNER_CHANGED'}
   $history=@(Read-Pg $db ("select coalesce(json_agg(json_build_object('rank',installed_rank,'version',version,'type',type,'script',script,'checksum',checksum,'installedBy',installed_by,'success',success) order by installed_rank),'[]'::json) from "+$schema+'.flyway_schema_history'))
   if(-not(Equal-Json $history $expected[0].history)){throw 'ORIGINAL_SQL_HISTORY_CHANGED'}
  }elseif($db -cnotin @('agent_platform_v12','agent_platform_dev_v12')){throw 'UNKNOWN_DATABASE_ADMISSION'}
  $facts.Add([pscustomobject]@{metadata=$meta;schema=$schema;history=$history})
 }
 $dynamic=@(Read-Pg 'postgres' "select coalesce(json_agg(json_build_object('database',datname,'owner',pg_get_userbyid(datdba)) order by datname),'[]'::json) from pg_database where datname ~ '^timeout_fix_[0-9a-f]{32}$'")
 if(@($dynamic|Where-Object owner -cne 'agent_v12').Count -ne 0){throw 'DYNAMIC_PREFIX_OWNER_CHANGED'}
 # Existing verify-cache without -Stop: AUTH / INFO / CONFIG GET and owned WSL exe only.
 $cache=& (Join-Path $root 'scripts/verify-cache.ps1')
 if($cache.version -cne '7.2.16' -or $cache.port -ne 26379 -or $cache.authenticated -ne $true -or $cache.distribution -cne 'AgentPlatformV12'){throw 'REDIS_CONTEXT_CHANGED'}
 $minio=Read-Json (Join-Path $root '.local/minio-process.json')
 if($minio.pid -ne 7860 -or $minio.port -ne 29000 -or -not([IO.Path]::GetFullPath($minio.executable)).Equals((Join-Path $root '.tools/bin/minio.exe'),[StringComparison]::OrdinalIgnoreCase) -or -not([IO.Path]::GetFullPath($minio.data)).Equals((Join-Path $root '.local/minio'),[StringComparison]::OrdinalIgnoreCase)){throw 'MINIO_RECORD_CHANGED'}
 $process=Get-CimInstance Win32_Process -Filter 'ProcessId=7860' -ErrorAction Stop
 $ml=@(Get-NetTCPConnection -LocalPort 29000 -State Listen -ErrorAction Stop)
 if(-not $process -or -not $process.ExecutablePath.Equals($minio.executable,[StringComparison]::OrdinalIgnoreCase) -or -not $process.CommandLine.Contains($minio.data) -or @($ml|Where-Object OwningProcess -ne 7860).Count -gt 0 -or $ml.Count -eq 0){throw 'MINIO_PROCESS_CHANGED'}
 $health=Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:29000/minio/health/live' -TimeoutSec 5 -MaximumRedirection 0
 if($health.StatusCode -ne 200){throw 'MINIO_NOT_HEALTHY'}
 $value=[pscustomobject]@{databases=$facts.ToArray();timeoutDatabases=$dynamic;redis=$cache;minio=[pscustomobject]@{pid=7860;createdAt=$process.CreationDate.ToString('o');health=200};readOnlyCollector=$true;collectorDDL=$false;unknownCleanup=$false}
 Fact ($Label+'-storage.json') $value;return $value
}
function Compare-Methods($Expected,$Actual){
 $map=New-Object 'Collections.Generic.Dictionary[string,object]' ([StringComparer]::Ordinal)
 foreach($row in @($Expected)){$key=Get-Ex04Identity $row;if($map.ContainsKey($key)){throw 'DUPLICATE_EXPECTED_IDENTITY'};$map.Add($key,$row)}
 $delta=New-Object 'Collections.Generic.List[object]';$reasons=New-Object 'Collections.Generic.List[object]';$seen=New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
 foreach($row in @($Actual)){
  $key=Get-Ex04Identity $row;if(-not $seen.Add($key)){throw 'DUPLICATE_ACTUAL_IDENTITY'}
  if(-not $map.ContainsKey($key)){$delta.Add([pscustomobject]@{identity=$key;kind='EXTRA'});continue};$before=$map[$key]
  if($before.status -cne $row.status -or $before.record_kind -cne $row.record_kind){$delta.Add([pscustomobject]@{identity=$key;kind='STATUS_OR_RECORD_KIND';before=$before.status;after=$row.status})}
  if($before.status -cne 'passed'){
   foreach($field in @('exception_type','cause_types','first_project_frame','message_sha256','detail_sha256')){if($before.$field -cne $row.$field){$reasons.Add([pscustomobject]@{identity=$key;field=$field;before=$before.$field;after=$row.$field})}}
  }
 }
 foreach($key in $map.Keys){if(-not $seen.Contains($key)){$delta.Add([pscustomobject]@{identity=$key;kind='MISSING'})}}
 return [pscustomobject]@{methodGate=($delta.Count -eq 0);reasonGate=($reasons.Count -eq 0);methodDelta=$delta.ToArray();reasonDelta=$reasons.ToArray()}
}
try{
 Set-Location -LiteralPath $root;Private-Directory $run;Stable
 Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $run 'approved-manifest.json');Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $run 'collector.ps1')
 Check-Green;$sourceBefore=Sources;Fact 'source-before.json' $sourceBefore
 if(@($m.sources).Count -ne 306 -or -not(Same $m.sources $sourceBefore)){throw 'SOURCE_INVENTORY_CHANGED'}
 Fact 'installed-before.json' (Installed)
 if($Mode -ceq 'Full'){Fact 'fixture-context-before.json' (Fixture-Context)}
 . (Join-Path $root $m.reportReader)
 $baseDir=Join-Path $root $m.baseline.directory;$baseline=@(Import-Csv -LiteralPath (Join-Path $baseDir 'methods.csv'))
 if($baseline.Count -ne 932 -or @($baseline|Where-Object record_kind -ceq 'CLASS_EVENT').Count -ne 2 -or @($baseline|Where-Object status -cne 'passed').Count -ne 115){throw 'ORIGINAL_932_BASELINE_REQUIRED'}
 $xmlPins=Read-Json (Join-Path $baseDir 'xml-manifest.json')
 if(@($xmlPins).Count -ne 93){throw 'ORIGINAL_93_XML_REQUIRED'}
 foreach($pin in $xmlPins){Pin (Join-Path $baseDir ('xml/'+$pin.file)) $pin.sha256}
 $baselineRaw=Read-Ex04FullReports (Join-Path $baseDir 'xml');$baseComparison=Compare-Methods $baseline $baselineRaw.rows
 if(-not $baseComparison.methodGate -or -not $baseComparison.reasonGate -or @($baselineRaw.issues).Count -ne 0){throw 'BASELINE_CSV_NOT_RAW_XML'}
 $expected=@($baseline)+@($m.addedMethod)
 if($Mode -ceq 'Adjacent'){$expected=@($expected|Where-Object{$m.adjacent.classes -ccontains $_.classname})}
 $expectedCount=if($Mode -ceq 'Full'){933}else{24};$expectedXml=if($Mode -ceq 'Full'){93}else{5}
 if($expected.Count -ne $expectedCount){throw 'MODE_METHOD_SCOPE_CHANGED'}
 foreach($key in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_ARGS','MAVEN_DEBUG_OPTS','JVM_CONFIG_MAVEN_PROPS','CLASSWORLDS_LAUNCHER')){if(-not[string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key,'Process'))){throw 'HIDDEN_JVM_OR_MAVEN_OPTIONS'}}
 if($env:MAVEN_OPTS -and $env:MAVEN_OPTS.Trim() -cne '-Xmx512m'){throw 'UNAPPROVED_MAVEN_OPTS'}
 foreach($key in @('JAVA_HOME','PATH','MAVEN_OPTS','MAVEN_SKIP_RC','MAVEN_BASEDIR','MAVEN_BATCH_ECHO','MAVEN_BATCH_PAUSE','MAVEN_TERMINATE_CMD','PGPASSWORD','PGCONNECT_TIMEOUT','PGOPTIONS','ENGINE_PG_TEST','WSL_UTF8')){$prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
 foreach($line in [IO.File]::ReadAllLines((Join-Path $root '.env'))){if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){$key=$Matches[1];if(-not $prior.ContainsKey($key)){$prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')};[Environment]::SetEnvironmentVariable($key,$Matches[2],'Process')}}
 if($env:AGENT_DB_URL -cne 'jdbc:postgresql://127.0.0.1:25432/agent_platform_dev_v12' -or $env:AGENT_DB_USER -cne 'agent_v12' -or [string]::IsNullOrEmpty($env:AGENT_DB_PASSWORD) -or $env:AGENT_DEV_AUTH -cne 'true' -or -not[string]::IsNullOrEmpty($env:AGENT_SECURITY_MODE)){throw 'PINNED_ENV_FLAGS_CHANGED'}
 $env:JAVA_HOME=$m.jdk;$env:PATH=$m.jdk+'/bin;'+$env:PATH;$env:MAVEN_OPTS='-Xmx512m';$env:MAVEN_SKIP_RC='1';$env:MAVEN_BASEDIR=$root;$env:MAVEN_BATCH_ECHO='off';$env:MAVEN_BATCH_PAUSE='off';$env:MAVEN_TERMINATE_CMD='off';$env:ENGINE_PG_TEST='true'
 $env:PGPASSWORD=$env:AGENT_DB_PASSWORD;$env:PGCONNECT_TIMEOUT='5';$env:PGOPTIONS='-c statement_timeout=5000 -c default_transaction_read_only=on'
 if($Mode -ceq 'Full'){$phase='STORAGE_BEFORE';$storageBefore=Check-Storage 'before'}
 Stable;Check-Green;if(-not(Same $m.sources (Sources))){throw 'SOURCE_CHANGED_BEFORE_TEST'}
 $args=@('-B','-ntp','-pl','engine-runtime','test',('-Dmaven.repo.local='+$m.repository),'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true')
 if($Mode -ceq 'Full'){$args+='-Dmaven.test.failure.ignore=true'}else{$args+=('-Dtest='+($m.adjacent.classes -join ','));$args+='-Dmaven.test.failure.ignore=false'}
 $start=[DateTime]::UtcNow;$phase='MAVEN_TEST';$invoked=$true
 Fact 'command.json' ([ordered]@{command=$m.maven;arguments=$args;startedAtUtc=$start.ToString('o');failureIgnore=($Mode -ceq 'Full');defaultForkReuseOrder=$true;registryPropertiesAdded=$false})
 try{$ErrorActionPreference='Continue';& $m.maven @args *> (Join-Path $run 'raw.log');$native=$LASTEXITCODE}finally{$ErrorActionPreference='Stop';Fact 'native-exit.json' ([ordered]@{nativeExit=$native;startedAtUtc=$start.ToString('o');endedAtUtc=[DateTime]::UtcNow.ToString('o')})}
 $phase='COLLECT_FRESH_REPORTS';$xmlDir=Join-Path $run 'xml';[IO.Directory]::CreateDirectory($xmlDir)|Out-Null
 $fresh=@(Get-ChildItem -LiteralPath (Join-Path $root 'engine-runtime/target/surefire-reports') -File -Filter 'TEST-*.xml'|Where-Object{$_.LastWriteTimeUtc -ge $start})
 $xmlProof=@(foreach($file in $fresh){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $xmlDir $file.Name);[pscustomobject]@{file=$file.Name;bytes=$file.Length;mtimeUtc=$file.LastWriteTimeUtc.ToString('o');sha256=(Hash (Join-Path $xmlDir $file.Name))}})
 Fact 'xml-manifest.json' $xmlProof
 $read=Read-Ex04FullReports $xmlDir;$actual=@($read.rows);$actual|Export-Csv -LiteralPath (Join-Path $run 'methods.csv') -NoTypeInformation -Encoding UTF8
 Fact 'xml-issues.json' $read.issues;$comparison=Compare-Methods $expected $actual
 Fact 'method-status-delta.json' $comparison.methodDelta;Fact 'strict-reason-delta.json' $comparison.reasonDelta
 $events=@($actual|Where-Object record_kind -ceq 'CLASS_EVENT');Fact 'class-level-events.json' $events
 $unexecuted=Read-Json (Join-Path $baseDir 'known-unexecuted-source.json');$missingGate=$true
 if($Mode -ceq 'Full'){if(@($unexecuted).Count -ne 14){throw 'ORIGINAL_14_UNEXECUTED_SCOPE_CHANGED'};foreach($entry in $unexecuted){if(@($actual|Where-Object{$_.classname -ceq $entry.classname -and $_.method -ceq $entry.method}).Count -ne 0){$missingGate=$false}};Fact 'known-unexecuted-source.json' $unexecuted}
 $phase='AFTER_ADMISSION';$after=Sources;Fact 'source-after.json' $after;Stable;Check-Green;Fact 'installed-after.json' (Installed)
 $sourceStable=Same $m.sources $after;$storageGate=$true
 if($Mode -ceq 'Full'){
  Fact 'fixture-context-after.json' (Fixture-Context);$storageAfter=Check-Storage 'after'
  $oldNames=New-Object 'Collections.Generic.Dictionary[string,string]' ([StringComparer]::Ordinal);foreach($db in $storageBefore.timeoutDatabases){$oldNames.Add($db.database,$db.owner)}
  $new=@($storageAfter.timeoutDatabases|Where-Object{-not $oldNames.ContainsKey($_.database)})
  $existing=@($storageAfter.timeoutDatabases|Where-Object{$oldNames.ContainsKey($_.database)})
  $storageGate=(Equal-Json $storageBefore.databases $storageAfter.databases) -and (Equal-Json $storageBefore.redis $storageAfter.redis) -and (Equal-Json $storageBefore.minio $storageAfter.minio) -and $new.Count -eq 1 -and $existing.Count -eq $oldNames.Count -and @($existing|Where-Object{$_.owner -cne $oldNames[$_.database]}).Count -eq 0
  Fact 'dynamic-database-delta.json' ([ordered]@{beforeCount=$oldNames.Count;afterCount=@($storageAfter.timeoutDatabases).Count;added=$new;exactOneOriginalFixtureCreate=$storageGate;deletedByCollector=$false})
 }
 $skip=@($actual|Where-Object status -ceq 'skipped').Count;$gate=$native -eq 0 -and $fresh.Count -eq $expectedXml -and $actual.Count -eq $expectedCount -and @($read.issues).Count -eq 0 -and $comparison.methodGate -and $comparison.reasonGate -and $missingGate -and $skip -eq 0 -and $sourceStable -and $storageGate
 $summary=[ordered]@{mode=$Mode;nativeExit=$native;xml=$fresh.Count;records=$actual.Count;passed=@($actual|Where-Object status -ceq 'passed').Count;failures=@($actual|Where-Object status -ceq 'failure').Count;errors=@($actual|Where-Object status -ceq 'error').Count;skipped=$skip;named=@($actual|Where-Object record_kind -ceq 'NAMED_CASE').Count;classEvents=$events.Count;methodIdentityAndStatusGate=$comparison.methodGate;strictOriginalReasonGate=$comparison.reasonGate;knownUnexecutedScopeGate=$missingGate;sourceStable=$sourceStable;contextStable=$true;greenDependenciesStable=$true;storageGate=$storageGate;collectorGatePassed=$gate;allTestsGreen=($Mode -ceq 'Adjacent' -and $gate);baseline='EX04 full-engine-20261029-1124';wholeCardClosed=$false;rawReasonDifferencesNormalized=$false}
 Fact 'summary.json' $summary;$exit=if($gate){0}else{1};$summary|ConvertTo-Json -Depth 5
}catch{
 $reason=if($_.Exception.Message -cmatch '^[A-Z_0-9]+$'){$_.Exception.Message}else{'UNEXPECTED_COLLECTOR_ERROR'}
 if(Test-Path -LiteralPath $run){Fact 'collector-error.json' ([ordered]@{phase=$phase;reason=$reason;line=$_.InvocationInfo.ScriptLineNumber;exceptionType=$_.Exception.GetType().FullName;nativeExit=$native;notAutomaticallyTestRedOrGreen=$true});($_|Out-String)|Set-Content -LiteralPath (Join-Path $run 'collector-error.raw.txt') -Encoding UTF8}
 Write-Output ('V222_REGRESSION_STOPPED phase='+$phase+' reason='+$reason)
}finally{
 if(Test-Path -LiteralPath $run){Fact 'collector-exit.json' ([ordered]@{collectorExit=$exit;nativeExit=$native;testInvoked=$invoked;automaticProcessOrDatabaseCleanup=$false});if(-not(Test-Path -LiteralPath (Join-Path $run 'source-after.json'))){Fact 'source-after.json' (Sources)}}
 foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')};Set-Location -LiteralPath $location
}
exit $exit
