param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]{1,80}$')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ApprovedManifestSha256,
 [switch]$ExecuteApproved,[switch]$ExclusiveDatabaseWindowApproved,[switch]$FormalServicesStoppedVerified
)
$ErrorActionPreference='Stop'
if(-not $ExecuteApproved -or -not $ExclusiveDatabaseWindowApproved -or -not $FormalServicesStoppedVerified){throw 'EXCLUSIVE_HISTORY_VALIDATE_WINDOW_REQUIRED'}
if($PSVersionTable.PSVersion.Major -ne 5 -or $PSVersionTable.PSVersion.Minor -ne 1){throw 'WINDOWS_PS51_REQUIRED'}
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../..'))
$manifestPath=Join-Path $PSScriptRoot 'history-validate-manifest.json'
if((Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash -cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'APPROVED_MANIFEST_CHANGED'}
$m=[IO.File]::ReadAllText($manifestPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
$run=Join-Path $root ('.local/ex27-v222-recovery/'+$RunId)
if(Test-Path -LiteralPath $run){throw 'RUN_ALREADY_EXISTS'}
$phase='ADMISSION';$exit=2;$prior=@{};$location=Get-Location;$compileNative=$null;$validateNative=$null

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
 $raw=& $psql -X -w -h 127.0.0.1 -p 25432 -U agent_v12 -d agent_platform_engine_test -At -v ON_ERROR_STOP=1 -c $Sql 2>> (Join-Path $run 'pg.raw.log')
 if($LASTEXITCODE -ne 0){throw 'READONLY_HISTORY_QUERY_FAILED'}
 return (($raw -join [Environment]::NewLine)|ConvertFrom-Json)
}

function Check-HistoricalStorage([string]$Label){
 $listeners=@(Get-NetTCPConnection -LocalPort 25432 -State Listen -ErrorAction Stop)
 if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -cne '127.0.0.1' -or $listeners[0].OwningProcess -ne $m.storage.expectedPid){throw 'AP_PG_LISTENER_CHANGED'}
 $pg=Get-CimInstance Win32_Process -Filter ('ProcessId='+$m.storage.expectedPid) -ErrorAction Stop
 if(-not $pg -or $pg.ExecutablePath.Replace([char]92,[char]47) -cne $m.storage.executable -or -not $pg.CommandLine.Replace([char]92,[char]47).Contains($m.storage.dataDirectory)){throw 'AP_PG_INSTANCE_CHANGED'}
 $facts=Read-Pg "select json_build_object('database',current_database(),'role',current_user,'dataDirectory',current_setting('data_directory'),'readOnly',current_setting('transaction_read_only'),'statementTimeout',current_setting('statement_timeout'),'databaseOwner',(select pg_get_userbyid(datdba) from pg_database where datname=current_database()),'schemaOwner',(select pg_get_userbyid(nspowner) from pg_namespace where nspname='engine'),'historyOwner',(select pg_get_userbyid(relowner) from pg_class where oid=to_regclass('engine.flyway_schema_history')),'otherSessions',(select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and backend_type='client backend'))"
 if($facts.database -cne 'agent_platform_engine_test' -or $facts.role -cne 'agent_v12' -or $facts.databaseOwner -cne 'agent_v12' -or $facts.schemaOwner -cne 'agent_v12' -or $facts.historyOwner -cne 'agent_v12' -or $facts.readOnly -cne 'on' -or $facts.statementTimeout -cne '5s' -or $facts.dataDirectory.Replace([char]92,[char]47).TrimEnd('/') -cne 'V:/.local/pgdata' -or $facts.otherSessions -ne 0){throw 'HISTORICAL_DATABASE_OWNERSHIP_OR_SESSION_CHANGED'}
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
 foreach($field in @('database','role','dataDirectory','readOnly','statementTimeout','databaseOwner','schemaOwner','historyOwner','otherSessions')){
  if([string]$facts.$field -cne [string]$approvedHistory.facts.$field){throw 'APPROVED_HISTORY_FACTS_CHANGED'}
 }
 if($pg.ProcessId -ne $approvedHistory.pgPid -or $value.pgCreatedAt -cne $approvedHistory.pgCreatedAt){throw 'APPROVED_PG_PROCESS_IDENTITY_CHANGED'}
 if((ConvertTo-Json -InputObject $sql -Depth 8 -Compress) -cne (ConvertTo-Json -InputObject $approvedHistory.sqlHistory -Depth 8 -Compress) -or (ConvertTo-Json -InputObject $markers -Depth 8 -Compress) -cne (ConvertTo-Json -InputObject $approvedHistory.schemaMarkers -Depth 8 -Compress) -or (ConvertTo-Json -InputObject $columns[0] -Depth 4 -Compress) -cne (ConvertTo-Json -InputObject $approvedHistory.column -Depth 4 -Compress)){throw 'APPROVED_HISTORY_OR_COLUMN_CHANGED'}
 Fact ($Label+'-history.json') $value
 return $value
}

function Check-NoFormalServices([string]$Label){
 $records=[IO.File]::ReadAllText((Join-Path $root '.local/services.json'))|ConvertFrom-Json
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

function Sql-Checksum([string]$Path){
 $utf8=New-Object Text.UTF8Encoding($false,$true)
 $reader=New-Object IO.StringReader($utf8.GetString([IO.File]::ReadAllBytes($Path)))
 [uint32]$crc=[uint32]::MaxValue;$first=$true
 try{
  while($null -ne ($line=$reader.ReadLine())){
   if($first){if($line.Length -gt 0 -and $line[0] -eq [char]0xFEFF){$line=$line.Substring(1)};$first=$false}
   foreach($byte in $utf8.GetBytes($line)){
    $crc=$crc -bxor [uint32]$byte
    for($bit=0;$bit -lt 8;$bit++){if(($crc -band 1) -ne 0){$crc=($crc -shr 1) -bxor [uint32]3988292384}else{$crc=$crc -shr 1}}
   }
  }
 }finally{$reader.Dispose()}
 return [BitConverter]::ToInt32([BitConverter]::GetBytes([uint32]($crc -bxor [uint32]::MaxValue)),0)
}

function Private-Directory {
 [void](Safe-Directory $run);[IO.Directory]::CreateDirectory($run)|Out-Null
 $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
 $acl=New-Object Security.AccessControl.DirectorySecurity
 $acl.SetOwner($sid);$acl.SetAccessRuleProtection($true,$false)
 $rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,[Security.AccessControl.FileSystemRights]::FullControl,([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit),[Security.AccessControl.PropagationFlags]::None,[Security.AccessControl.AccessControlType]::Allow)
 [void]$acl.AddAccessRule($rule);[IO.Directory]::SetAccessControl($run,$acl)
 $actual=Get-Acl -LiteralPath $run
 if(-not $actual.AreAccessRulesProtected -or @($actual.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])|Where-Object{$_.IdentityReference.Value -cne $sid.Value -or $_.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow}).Count){throw 'PRIVATE_ACL_NOT_EXACT_OWNER'}
 Fact 'private-directory.json' ([ordered]@{protected=$true;ownerOnlyRules=$true;secretFileCreated=$false})
}
function Check-Resources {
 $classes=Safe-Directory (Join-Path $root $m.classesDirectory)
 $src=Join-Path $root 'engine-runtime/src/main/resources/db/migration';$target=Join-Path $classes 'db/migration'
 [void](Safe-Directory $target)
 if(Test-Path -LiteralPath (Join-Path $classes 'db/callback')){throw 'TARGET_CALLBACK_RESOURCES_REJECTED'}
 $files=@(Get-ChildItem -LiteralPath $target -File -Recurse -Force)
 if($files.Count -ne 24 -or @($m.knownSqlCatalog).Count -ne 24 -or @($files|Where-Object{($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0}).Count){throw 'EXACT_24_TARGET_RESOURCES_REQUIRED'}
 foreach($item in $m.knownSqlCatalog){
  $source=Join-Path $src $item.script;$output=Join-Path $target $item.script
  if(@($files|Where-Object FullName -ceq $output).Count -ne 1 -or (Hash $source) -cne $item.sha256 -or (Hash $output) -cne $item.sha256 -or (Sql-Checksum $source) -ne $item.checksum){throw 'SOURCE_TARGET_CATALOG_MISMATCH'}
 }
 $v222=Join-Path $target 'V222__run_context_pack.sql'
 if((Get-Item -LiteralPath $v222).Length -ne 286 -or (Hash $v222) -cne $m.v222Sha256){throw 'V222_BYTES_CHANGED'}
 $jar=Join-Path $root $m.engineJar.path;Pin $jar $m.engineJar.sha256
 $zip=[IO.Compression.ZipFile]::OpenRead($jar)
 try{
  $sql=@($zip.Entries|Where-Object FullName -cmatch '^BOOT-INF/classes/db/migration/[^/]+\.sql$')
  if($sql.Count -ne 24 -or @($sql|Group-Object FullName|Where-Object Count -ne 1).Count){throw 'JAR_RESOURCE_SET_CHANGED'}
  foreach($entry in $sql){$name=$entry.FullName.Substring('BOOT-INF/classes/db/migration/'.Length);$expected=@($m.knownSqlCatalog|Where-Object script -ceq $name);$stream=$entry.Open();$algorithm=[Security.Cryptography.SHA256]::Create();try{$value=([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-','');if($expected.Count -ne 1 -or $value -cne $expected[0].sha256){throw 'JAR_SQL_CATALOG_CHANGED'}}finally{$stream.Dispose();$algorithm.Dispose()}}
 }finally{$zip.Dispose()}
 return [ordered]@{count=24;sourceTargetAndJarMatch=$true;catalogCrcMatch=$true;v222Bytes=286;v222Sha256=$m.v222Sha256;engineJarSha256=$m.engineJar.sha256}
}
function Check-Classpath {
 $xmlPath=Join-Path $root $m.classpathSource.path;Pin $xmlPath $m.classpathSource.sha256
 $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load($xmlPath)
 $property=@($doc.SelectNodes('/testsuite/properties/property')|Where-Object{$_.GetAttribute('name') -ceq 'java.class.path'})
 if($property.Count -ne 1){throw 'FRESH_CLASSPATH_PROPERTY_NOT_UNIQUE'}
 $original=@($property[0].GetAttribute('value').Split(';'))
 $classes=Safe-Directory (Join-Path $root $m.classesDirectory)
 if(@($original|Where-Object{$_ -ceq $classes}).Count -ne 1 -or $original.Count -ne $m.classpathSource.entries){throw 'FRESH_CLASSES_ROOT_CHANGED'}
 $paths=@();$seen=@{}
 foreach($lib in $m.classpathLibraries){
  $path=[IO.Path]::GetFullPath((Join-Path $root $lib.path));[void](Safe-Directory ([IO.Path]::GetDirectoryName($path)))
  $repository=[IO.Path]::GetFullPath((Join-Path $root '.tools/m2'))
  if($seen.ContainsKey($path) -or -not $path.StartsWith($repository+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or @($original|Where-Object{$_ -ceq $path}).Count -ne 1 -or $path -cmatch '(?:-tests\.jar|test-classes|engine-runtime-[^/\\]+\.jar)$' -or ((Get-Item -LiteralPath $path -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'CLASSPATH_ORIGIN_REJECTED'}
  $seen[$path]=$true;Pin $path $lib.sha256
  $zip=[IO.Compression.ZipFile]::OpenRead($path)
  try{if(@($zip.Entries|Where-Object{$_.FullName -cmatch '^(?:db/(?:migration|callback)(?:/|$)|META-INF/services/org\.flywaydb\.core\.api\.callback\.Callback$)'}).Count){throw 'EXTRA_MIGRATION_OR_CALLBACK_CLASSPATH_ROOT'}}finally{$zip.Dispose()}
  $paths+=$path
 }
 if($paths.Count -ne 94){throw 'EXACT_RUNTIME_LIBRARY_SET_REQUIRED'}
 return @($classes)+@($paths)
}
function Quote-Argument([string]$Value){
 if($Value.IndexOf([char]0) -ge 0 -or $Value.Contains('"') -or $Value.Contains("`r") -or $Value.Contains("`n") -or $Value.EndsWith('\')){throw 'PROCESS_ARGUMENT_SHAPE_REJECTED'}
 return '"'+$Value+'"'
}
function Hash-Bytes([byte[]]$Value){$algorithm=[Security.Cryptography.SHA256]::Create();try{return ([BitConverter]::ToString($algorithm.ComputeHash($Value))).Replace('-','')}finally{$algorithm.Dispose()}}
function Owned-Process([string]$Label,[string]$Executable,[string[]]$Arguments,[int]$Seconds,$InputText){
 $process=New-Object Diagnostics.Process;$process.StartInfo=New-Object Diagnostics.ProcessStartInfo
 $process.StartInfo.FileName=$Executable;$process.StartInfo.Arguments=(@($Arguments|ForEach-Object{Quote-Argument $_}) -join ' ')
 if($process.StartInfo.Arguments.Length -gt 30000){throw 'COMMAND_LINE_TOO_LONG'}
 $process.StartInfo.WorkingDirectory=$root;$process.StartInfo.UseShellExecute=$false;$process.StartInfo.CreateNoWindow=$true
 $process.StartInfo.RedirectStandardOutput=$true;$process.StartInfo.RedirectStandardError=$true;$process.StartInfo.RedirectStandardInput=($null -ne $InputText)
 $process.StartInfo.EnvironmentVariables.Remove('PGPASSWORD');$process.StartInfo.EnvironmentVariables.Remove('AGENT_DB_PASSWORD')
 $process.StartInfo.StandardOutputEncoding=New-Object Text.UTF8Encoding($false,$true);$process.StartInfo.StandardErrorEncoding=New-Object Text.UTF8Encoding($false,$true)
 $started=[DateTime]::UtcNow;$timedOut=$false;$native=$null;$stdout='';$pidOwned=$null;$outBytes=[byte[]]@();$errBytes=[byte[]]@();$compileLogsSaved=$false
 $outBuffer=New-Object IO.MemoryStream;$errBuffer=New-Object IO.MemoryStream
 Fact ($Label+'-command.json') ([ordered]@{executable=$Executable;arguments=$Arguments;startedAtUtc=$started.ToString('o');budgetSeconds=$Seconds;stdinProtocolProvided=($null -ne $InputText);stdinPersisted=$false;secretInArguments=$false})
 try{
  if(-not $process.Start()){throw 'OWNED_PROCESS_START_FAILED'}
  $retainedHandle=$process.Handle;$pidOwned=$process.Id
  $outTask=$process.StandardOutput.BaseStream.CopyToAsync($outBuffer);$errTask=$process.StandardError.BaseStream.CopyToAsync($errBuffer)
  if($null -ne $InputText){$process.StandardInput.NewLine="`n";$process.StandardInput.Write($InputText);$process.StandardInput.Close()}
  if(-not $process.WaitForExit($Seconds*1000)){$timedOut=$true;$process.Kill();if(-not $process.WaitForExit(5000)){throw 'OWNED_PROCESS_DID_NOT_EXIT'}}
  if(-not $outTask.Wait(5000) -or -not $errTask.Wait(5000)){throw 'OWNED_PROCESS_PIPE_TIMEOUT'}
  $outTask.GetAwaiter().GetResult();$errTask.GetAwaiter().GetResult();$outBytes=$outBuffer.ToArray();$errBytes=$errBuffer.ToArray();$native=$process.ExitCode
  if($Label -ceq 'compile'){[IO.File]::WriteAllBytes((Join-Path $run 'compile.stdout.raw.log'),$outBytes);[IO.File]::WriteAllBytes((Join-Path $run 'compile.stderr.raw.log'),$errBytes);$compileLogsSaved=$true}
  if($null -eq $native){throw 'OWNED_PROCESS_EXIT_CODE_MISSING'}
  if($outBytes.Length -gt 65536 -or $errBytes.Length -gt 65536){throw 'OWNED_PROCESS_OUTPUT_TOO_LARGE'}
  if($Label -ceq 'validate'){$stdout=(New-Object Text.UTF8Encoding($false,$true)).GetString($outBytes)}
 }finally{
  if($pidOwned -and -not $process.HasExited){$process.Kill();[void]$process.WaitForExit(5000)}
  Fact ($Label+'-native.json') ([ordered]@{nativeExit=$native;timedOut=$timedOut;startedAtUtc=$started.ToString('o');endedAtUtc=[DateTime]::UtcNow.ToString('o');budgetSeconds=$Seconds;stdoutBytes=$outBytes.Length;stderrBytes=$errBytes.Length;stdoutSha256=(Hash-Bytes $outBytes);stderrSha256=(Hash-Bytes $errBytes);rawStreamsPersisted=$compileLogsSaved;secretInArguments=$false;compilerReceivesCredentials=$false})
  $process.Dispose();$outBuffer.Dispose();$errBuffer.Dispose()
 }
 return [pscustomobject]@{nativeExit=$native;timedOut=$timedOut;stdout=$stdout;stderrBytes=$errBytes.Length}
}
function Safe-ValidatorResult([string]$Text){
 if($Text -cnotmatch '\A\{[^\r\n]*\}\r?\n?\z'){throw 'VALIDATOR_OUTPUT_NOT_SINGLE_JSON'}
 $value=$Text|ConvertFrom-Json
 $keys=@('schema','passed','safeCode','validationSuccessful','validateCount','invalidMigrationCount','errorDetailsPresent','errorCodes','openedConnections','verifiedBeforeConnections','verifiedAfterConnections','closedConnections','connectionGuardsPassed','resourcePinned','ddlInvoked','applicationStarted','wholeCardClosed')
 if(@($value.psobject.Properties).Count -ne $keys.Count -or @($value.psobject.Properties|Where-Object{$keys -cnotcontains $_.Name}).Count -or $value.schema -cne 'EX27-V222-history-validator-v1' -or $value.safeCode -cnotmatch '^[A-Z0-9_]{1,80}$'){throw 'VALIDATOR_OUTPUT_SCHEMA_REJECTED'}
 foreach($key in @('passed','validationSuccessful','errorDetailsPresent','connectionGuardsPassed','resourcePinned','ddlInvoked','applicationStarted','wholeCardClosed')){if($value.$key -isnot [bool]){throw 'VALIDATOR_BOOLEAN_TYPE_REJECTED'}}
 foreach($key in @('validateCount','invalidMigrationCount','openedConnections','verifiedBeforeConnections','verifiedAfterConnections','closedConnections')){if($value.$key -isnot [int] -or $value.$key -lt -1 -or $value.$key -gt 10000){throw 'VALIDATOR_COUNT_TYPE_REJECTED'}}
 if($value.errorCodes -isnot [Array] -or @($value.errorCodes|Where-Object{$_ -isnot [string] -or $_ -cnotmatch '^[A-Z0-9_]{1,80}$'}).Count -or @($value.errorCodes).Count -gt 100){throw 'VALIDATOR_ERROR_CODE_REJECTED'}
 if($value.ddlInvoked -or $value.applicationStarted -or $value.wholeCardClosed){throw 'VALIDATOR_SCOPE_REJECTED'}
 return $value
}
try{
 Set-Location -LiteralPath $root
 if($m.schema -cne 'EX27-V222-history-validate-candidate-v1' -or $m.sourceCount -ne 306 -or -not(Stable)){throw 'FROZEN_CONTEXT_REJECTED'}
 if($m.storage.database -cne 'agent_platform_engine_test' -or $m.storage.schema -cne 'engine' -or $m.storage.port -ne 25432 -or $m.storage.host -cne '127.0.0.1' -or $m.storage.role -cne 'agent_v12'){throw 'EXACT_ENGINE_TEST_SCOPE_REQUIRED'}
 foreach($key in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH','JAVA_OPTS')){if(-not[string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key,'Process'))){throw 'HIDDEN_JVM_OPTIONS'}}
 if(@([Environment]::GetEnvironmentVariables('Process').Keys|Where-Object{$_ -cmatch '^FLYWAY_'}).Count){throw 'UNAPPROVED_FLYWAY_ENVIRONMENT'}
 $phase='PRIVATE_DIRECTORY';Private-Directory
 Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $run 'approved-manifest.json')
 Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $run 'collector.ps1')
 Copy-Item -LiteralPath (Join-Path $root $m.helper.path) -Destination (Join-Path $run 'validator-source.java')
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $approvedHistory=[IO.File]::ReadAllText((Join-Path $root $m.storageApprovalProof.path))|ConvertFrom-Json
 if($approvedHistory.passed -ne $true -or $approvedHistory.targetDatabase -cne 'agent_platform_engine_test' -or $approvedHistory.sqlRows -ne 24 -or $approvedHistory.totalRows -ne 25){throw 'ORIGINAL_HISTORY_APPROVAL_REJECTED'}
 foreach($pin in $m.green.pins){Pin (Join-Path $root $pin.path) $pin.sha256};Pin (Join-Path $root $m.green.receipt.path) $m.green.receipt.sha256
 $green=[IO.File]::ReadAllText((Join-Path $root $m.green.receipt.path))|ConvertFrom-Json
 if(-not $green.collectorGatePassed -or $green.sourceCount -ne 306 -or $green.records -ne 4 -or $green.passed -ne 4 -or $green.engineJarSha256 -cne $m.engineJar.sha256){throw 'GREEN_RESOURCE_RECEIPT_REJECTED'}
 $before=Sources;Fact 'source-before.json' $before;if(-not(Same $m.sources $before)){throw 'SOURCE_306_CHANGED'}
 $installedBefore=@($m.installedArtifacts|ForEach-Object{Pin (Join-Path $root $_.path) $_.sha256;$_});Fact 'installed-before.json' $installedBefore
 Fact 'resources-before.json' (Check-Resources);$classpath=@(Check-Classpath)
 foreach($key in @('PGPASSWORD','PGCONNECT_TIMEOUT','PGOPTIONS')){$prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
 $values=@{};foreach($line in [IO.File]::ReadAllLines((Join-Path $root '.env'))){if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
 if($values.AGENT_DB_URL -cne $m.storage.credentialJdbcUrl -or $values.AGENT_DB_USER -cne 'agent_v12' -or [string]::IsNullOrEmpty($values.AGENT_DB_PASSWORD)){throw 'PINNED_CREDENTIAL_CONFIGURATION_REJECTED'}
 $env:PGPASSWORD=$values.AGENT_DB_PASSWORD;$env:PGCONNECT_TIMEOUT='5';$env:PGOPTIONS='-c statement_timeout=5000 -c default_transaction_read_only=on'
 $phase='READONLY_HISTORY_BEFORE';$historyBefore=Check-HistoricalStorage 'before';Check-NoFormalServices 'before'
 $classes=Join-Path $run 'classes';[void](Safe-Directory $classes);[IO.Directory]::CreateDirectory($classes)|Out-Null
 $cp=$classpath -join ';'
 $phase='COMPILE_EVIDENCE_MAIN'
 $compile=Owned-Process 'compile' (Join-Path $m.jdk 'bin/javac.exe') @('-J-Xmx256m','-encoding','UTF-8','--release','21','-proc:none','-implicit:none','-cp',$cp,'-d',$classes,(Join-Path $root $m.helper.path)) 60 $null
 $compileNative=$compile.nativeExit
 if($compile.timedOut -or $compileNative -ne 0){throw 'EVIDENCE_MAIN_COMPILE_FAILED'}
 $outputs=@(Get-ChildItem -LiteralPath $classes -File -Recurse -Force);$expected=@('V222HistoryValidate.class','V222HistoryValidate$Rejected.class','V222HistoryValidate$ReadOnlyDataSource.class')
 if($outputs.Count -ne 3 -or @($outputs|Where-Object{$expected -cnotcontains $_.Name -or $_.DirectoryName -cne $classes}).Count){throw 'EVIDENCE_CLASS_OUTPUT_SET_REJECTED'}
 Fact 'compiled-evidence.json' @($outputs|ForEach-Object{[ordered]@{file=$_.Name;sha256=(Hash $_.FullName)}})
 if(-not(Stable) -or -not(Same $m.sources (Sources))){throw 'CONTEXT_CHANGED_BEFORE_VALIDATION'}
 $phase='HISTORY_VALIDATE_ONLY'
 $passwordBytes=[Text.Encoding]::UTF8.GetBytes($values.AGENT_DB_PASSWORD)
 try{$inputText="V222-HISTORY-VALIDATE/1`n"+[Convert]::ToBase64String($passwordBytes)+"`n";$validation=Owned-Process 'validate' (Join-Path $m.jdk 'bin/java.exe') @('-Xmx256m','-Dfile.encoding=UTF-8','-cp',($classes+';'+$cp),'V222HistoryValidate',$RunId,(Join-Path $root $m.classesDirectory)) 60 $inputText}finally{if($passwordBytes){[Array]::Clear($passwordBytes,0,$passwordBytes.Length)};$inputText=$null;$values.AGENT_DB_PASSWORD=$null}
 $validateNative=$validation.nativeExit
 if($validation.timedOut){throw 'VALIDATION_PROCESS_TIMEOUT'}
 $result=Safe-ValidatorResult $validation.stdout;Fact 'validator-result.json' $result
 $phase='READONLY_HISTORY_AFTER';$historyAfter=Check-HistoricalStorage 'after';Check-NoFormalServices 'after'
 $after=Sources;Fact 'source-after.json' $after
 $installedAfter=@($m.installedArtifacts|ForEach-Object{[pscustomobject]@{path=$_.path;sha256=(Hash (Join-Path $root $_.path))}});Fact 'installed-after.json' $installedAfter
 Fact 'resources-after.json' (Check-Resources);[void](Check-Classpath)
 $sourceStable=Same $m.sources $after;$dependenciesStable=Same $installedBefore $installedAfter;$contextStable=Stable
 $historyStable=(ConvertTo-Json -InputObject $historyBefore -Depth 16 -Compress) -ceq (ConvertTo-Json -InputObject $historyAfter -Depth 16 -Compress)
 $connections=$result.openedConnections -gt 0 -and $result.openedConnections -eq $result.verifiedBeforeConnections -and $result.openedConnections -eq $result.verifiedAfterConnections -and $result.openedConnections -eq $result.closedConnections
 $gate=$validateNative -eq 0 -and $validation.stderrBytes -eq 0 -and $result.passed -and $result.safeCode -ceq 'VALIDATED' -and $result.validationSuccessful -and $result.invalidMigrationCount -eq 0 -and -not $result.errorDetailsPresent -and @($result.errorCodes).Count -eq 0 -and $result.connectionGuardsPassed -and $connections -and $historyStable -and $sourceStable -and $dependenciesStable -and $contextStable
 $summary=[ordered]@{mode='HistoricalValidateOnly';compileNativeExit=$compileNative;validateNativeExit=$validateNative;validationSuccessful=$result.validationSuccessful;invalidMigrationCount=$result.invalidMigrationCount;connectionGuardsPassed=($connections -and $result.connectionGuardsPassed);sourceStable=$sourceStable;greenDependenciesStable=$dependenciesStable;contextStable=$contextStable;historyAndColumnUnchanged=$historyStable;sourceCount=306;sqlResources=24;collectorGatePassed=$gate;newDatabaseMigrated=$false;adjacentOrFullExecuted=$false;ddlInvoked=$false;servicesStartedOrStopped=$false;wholeCardClosed=$false;validationExceptionStringsPersisted=$false;compilerDiagnosticsPrivateOnly=$true;credentialsPersisted=$false}
 Fact 'summary.json' $summary;$exit=if($gate){0}else{1};$summary|ConvertTo-Json -Depth 6
}catch{
 $reason=if($m.safeErrorCodes -ccontains $_.Exception.Message){$_.Exception.Message}else{'UNEXPECTED_COLLECTOR_ERROR'}
 if(Test-Path -LiteralPath $run){Fact 'collector-error.json' ([ordered]@{phase=$phase;safeReason=$reason;exceptionType=$_.Exception.GetType().FullName;line=$_.InvocationInfo.ScriptLineNumber;compileNativeExit=$compileNative;validateNativeExit=$validateNative;notBusinessRed=$true;notSuccessfulValidation=$true;rawExceptionPersisted=$false})}
 Write-Output ('V222_HISTORY_VALIDATE_REJECTED phase='+$phase+' reason='+$reason)
}finally{
 if(Test-Path -LiteralPath $run){Fact 'collector-exit.json' ([ordered]@{collectorExit=$exit;compileNativeExit=$compileNative;validateNativeExit=$validateNative;automaticProcessOrDatabaseRestore=$false;wholeCardClosed=$false})}
 foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')};Set-Location -LiteralPath $location
}
exit $exit
