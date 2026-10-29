<#
EX-15a gateway module: READ-ONLY PostgreSQL admission. PS5.1 / UTF8 BOM.
Before: writes a NEW preparation/<RunId> with module-{manifest,fingerprints,pg-admission}.json.
After: same RunId, preserves Before and writes separate after history/fingerprints/receipt.
Only -ExecuteApproved on Before can set manifest.approved=true; never invokes run-module.
Example after live has stopped and root has granted the exclusive window:
  .\prepare-module.ps1 -Mode Before -RunId gateway-module-20261029-01 -ExecuteApproved
  .\run-module.ps1 -RunId gateway-module-20261029-01 -CandidatePath <printed manifest> -RuntimeWindowGranted
  .\prepare-module.ps1 -Mode After -RunId gateway-module-20261029-01
No CREATE/DROP/repair/clean/TRUNCATE/queue cleanup, Java, Maven, HTTP or service process action.
#>
param(
 [Parameter(Mandatory=$true)][ValidateSet('Before','After')][string]$Mode,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9-]{0,95}$')][string]$RunId,
 [switch]$ExecuteApproved,
 [string]$ApRoot=(Join-Path $PSScriptRoot '../../../..')
)
$ErrorActionPreference='Stop'
$Mode=(Get-Culture).TextInfo.ToTitleCase($Mode.ToLowerInvariant())
$ApRoot=(Resolve-Path -LiteralPath $ApRoot).Path.TrimEnd('\','/')
$runner=Join-Path $PSScriptRoot 'run-module.ps1'
$output=Join-Path $PSScriptRoot ('preparation/'+$RunId)
$private=Join-Path $ApRoot ('.local/ex15a-gateway-preparation/'+$RunId+'/'+$Mode.ToLowerInvariant())
$manifestPath=Join-Path $output 'module-manifest.json'
$oldEnvironment=@{}; $passed=$false; $phase='static-inputs'; $databaseName=$null
$runnerSha='9a7ba2911997276e61c7ea22dd2a3a010019043f0d60400cee6b861d34ebf208'
$legacySha='419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90'
$legacyPath='engine-runtime/target/classes/db/migration/V222__run_context_pack.sql'
$proofs=@(); $catalog=@(); $classpathFacts=@(); $fingerprints=$null
function Save-New([string]$Path,$Value){
 if(Test-Path -LiteralPath $Path){throw 'EVIDENCE_EXISTS_NO_OVERWRITE'}
 [IO.File]::WriteAllText($Path,(ConvertTo-Json -InputObject $Value -Depth 18),(New-Object Text.UTF8Encoding($false)))
}
function Private-Acl([string]$Path){
 $acl=Get-Acl -LiteralPath $Path; $acl.SetAccessRuleProtection($true,$false)
 foreach($rule in @($acl.Access)){[void]$acl.RemoveAccessRuleSpecific($rule)}
 foreach($sid in @([Security.Principal.WindowsIdentity]::GetCurrent().User,(New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))){
  $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))
 }
 Set-Acl -LiteralPath $Path -AclObject $acl
}
function Relative([string]$Path){
 $full=Own-Path $Path
 $full.Substring($ApRoot.Length+1).Replace('\','/')
}
function Classpath-Path([string]$Path){
 if($Path -match '^[vV]:[\\/]'){
  if(-not('EX15Gateway.ApAlias' -as [type])){
   Add-Type -TypeDefinition 'using System; using System.Text; using System.Runtime.InteropServices; namespace EX15Gateway { public static class ApAlias { [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] public static extern uint QueryDosDevice(string name,StringBuilder target,int count); } }'
  }
  $mapped=New-Object Text.StringBuilder(4096)
  if([EX15Gateway.ApAlias]::QueryDosDevice('V:',$mapped,$mapped.Capacity) -eq 0){throw 'AP_ALIAS_UNRESOLVED'}
  $target=$mapped.ToString()
  if(-not $target.StartsWith('\??\',[StringComparison]::Ordinal)){throw 'AP_ALIAS_NOT_SUBST'}
  $actual=[IO.Path]::GetFullPath($target.Substring(4)).TrimEnd('\','/')
  if($actual -ine $ApRoot){throw 'AP_ALIAS_POINTS_OUTSIDE_REPOSITORY'}
  $Path=Join-Path $ApRoot $Path.Substring(3)
 }
 return (Own-Path $Path)
}
function Read-Pg([string]$Database,[string]$Sql){
 # Every call is one SELECT, with enforced read-only transactions and bounded timeouts.
 if($Sql -notmatch '^select ' -or $Sql.Contains(';')){throw 'ONLY_SINGLE_READ_QUERY_ALLOWED'}
 $raw=& (Join-Path $ApRoot '.tools/postgresql-16.15/pgsql/bin/psql.exe') -X -w -h 127.0.0.1 -p 25432 -U agent_v12 -d $Database -At -v ON_ERROR_STOP=1 -c $Sql 2>> (Join-Path $private 'psql.err.raw.log')
 if($LASTEXITCODE -ne 0){throw 'READONLY_PG_FAILED'}
 $json=($raw -join "`n").Trim()
 if(-not $json){throw 'READONLY_PG_NO_ROWS'}
 return ($json | ConvertFrom-Json)
}
function Initialize-Crc {
 if('EX15Gateway.FlywayCrc' -as [type]){return}
 Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
namespace EX15Gateway {
 public static class FlywayCrc {
  public static int File(string path) { using(var stream=System.IO.File.OpenRead(path)) { return Stream(stream); } }
  public static int Stream(Stream stream) {
   uint crc=0xffffffffU; bool first=true;
   using(var reader=new StreamReader(stream,new UTF8Encoding(false,true),false,4096,true)) {
    string line;
    while((line=reader.ReadLine())!=null) {
     if(first && line.Length>0 && line[0]=='\ufeff')line=line.Substring(1);
     first=false;
     foreach(byte b in Encoding.UTF8.GetBytes(line)) {
      crc^=b; for(int n=0;n<8;n++)crc=(crc&1U)!=0 ? (crc>>1)^0xedb88320U : crc>>1;
     }
    }
   }
   return unchecked((int)(crc^0xffffffffU));
  }
 }
}
'@
}
function Migration-Catalog([string]$Module){
 $sourceDir=Join-Path $ApRoot ($Module+'/src/main/resources/db/migration')
 $targetDir=Join-Path $ApRoot ($Module+'/target/classes/db/migration')
 $rows=@(); $known=@{}
 foreach($file in Get-ChildItem -LiteralPath $sourceDir -File -Filter '*.sql' | Sort-Object Name){
  if($file.Name -notmatch '^V([0-9]+)__.+\.sql$'){throw 'UNKNOWN_MIGRATION_SOURCE_NAME'}
  $version=$Matches[1]; $target=Join-Path $targetDir $file.Name
  if(-not(Test-Path -LiteralPath $target -PathType Leaf) -or (Sha $target) -cne (Sha $file.FullName)){throw 'RUNTIME_SQL_DIFFERS_FROM_SOURCE'}
  $rows+=[pscustomobject]@{module=$Module;version=$version;script=$file.Name;source=(Relative $file.FullName);runtime=(Relative $target);sha256=(Sha $file.FullName);checksum=[EX15Gateway.FlywayCrc]::File($file.FullName);legacy=$false}
  $known[$file.Name]=$true
 }
 if($Module -ceq 'engine-runtime'){
  if(Test-Path -LiteralPath (Join-Path $sourceDir 'V222__run_context_pack.sql')){throw 'V222_SOURCE_UNEXPECTED'}
  $legacy=Own-Path $legacyPath
  if((Sha $legacy) -cne $legacySha -or [EX15Gateway.FlywayCrc]::File($legacy) -ne 701700386){throw 'KNOWN_V222_RUNTIME_DRIFT'}
  $rows+=[pscustomobject]@{module=$Module;version='222';script='V222__run_context_pack.sql';source=$null;runtime=$legacyPath;sha256=$legacySha;checksum=701700386;legacy=$true}
  $known['V222__run_context_pack.sql']=$true
 }
 foreach($file in Get-ChildItem -LiteralPath $targetDir -File){if(-not $known.ContainsKey($file.Name)){throw 'UNKNOWN_RUNTIME_MIGRATION'}}
 if(@($rows | Group-Object version | Where-Object Count -ne 1).Count){throw 'DUPLICATE_MIGRATION_VERSION'}
 return @($rows | Sort-Object {[int]$_.version})
}
function Classpath-Inputs {
 $extra=New-Object 'System.Collections.Generic.List[string]'
 $texts=@(); $facts=@()
 foreach($relative in @('.local/iam-n4b2-gateway-published-chain/app-classpath.txt','.local/iam-n4b2-gateway-published-chain/engine-classpath.txt','.local/iam-o1c-gateway-status/engine-classpath.txt','.local/iam-o2b-f2c2b2-joined-chain/engine-classpath.txt')){
  $extra.Add($relative); $path=Own-Path $relative
  $exists=Test-Path -LiteralPath $path -PathType Leaf
  $facts+=[pscustomobject]@{file=$relative;exists=$exists}
  if($exists){$texts+=([IO.File]::ReadAllText($path).Trim())}
 }
 $smoke='docs/evidence/iam-stage-4-d3a0/smoke-classpaths.json'; $extra.Add($smoke)
 if(Test-Path -LiteralPath (Own-Path $smoke)){
  $p=Read-Json (Own-Path $smoke)
  foreach($key in @('app','gateway')){if(-not [string]::IsNullOrWhiteSpace([string]$p.$key)){$texts+=[string]$p.$key}}
 }
 $allowedDirectories=@('app-management/target/classes','app-management/target/test-classes','engine-runtime/target/classes','engine-runtime/target/test-classes','platform-gateway/target/classes','platform-gateway/target/test-classes','admin-common/target/classes','admin-common/target/test-classes')
 $entries=@(foreach($text in $texts){$text -split ';'}) | Sort-Object -Unique
 foreach($entry in $entries){
  if([string]::IsNullOrWhiteSpace($entry)){
   # Existing trailing ; means the child working directory. Do not rewrite classpaths.
   if(Test-Path -LiteralPath (Join-Path $ApRoot 'db/migration')){throw 'IMPLICIT_WORKING_DIRECTORY_MIGRATIONS'}
   $facts+=[pscustomobject]@{emptyClasspathEntry=$true;workingDirectoryMigrationAbsent=$true};continue
  }
  if($entry.Contains('*')){throw 'CLASSPATH_WILDCARD_REQUIRES_SEPARATE_REVIEW'}
  $full=Classpath-Path $entry; $relative=Relative $full
  if($relative -in $allowedDirectories){
   # Pin actual existing class/resource bytes, and separately pin the exact expected mains below.
   if(Test-Path -LiteralPath $full -PathType Container){
    $directoryFiles=@(Get-ChildItem -LiteralPath $full -File -Recurse)
    foreach($file in $directoryFiles){$extra.Add((Relative $file.FullName))}
    $facts+=[pscustomobject]@{directory=$relative;exists=$true;fileCount=$directoryFiles.Count;classCount=@($directoryFiles | Where-Object Extension -ceq '.class').Count;resourceCount=@($directoryFiles | Where-Object Extension -cne '.class').Count}
   }
   else {$extra.Add($relative)}
  } elseif($relative -like '.tools/m2/*.jar' -or $relative -ceq 'admin-common/target/admin-common-0.1.0-SNAPSHOT-tests.jar'){
   $extra.Add($relative)
   if(Test-Path -LiteralPath $full -PathType Leaf){
    $zip=[IO.Compression.ZipFile]::OpenRead($full)
    try {if(@($zip.Entries | Where-Object {$_.FullName -match '^db/migration/'}).Count){throw 'DEPENDENCY_CONTAINS_UNREVIEWED_MIGRATION'}} finally {$zip.Dispose()}
   }
  } else {throw 'CLASSPATH_OUTSIDE_EXACT_MODULE_OR_DEPENDENCY_SCOPE'}
 }
 # Missing fixture classes remain explicit exists=false; never compile/install to alter the old failures.
 foreach($path in @(
  'app-management/target/test-classes/cn/agentplatform/common/authority/PublishedAdmissionFixtureMain.class',
  'app-management/target/test-classes/cn/agentplatform/common/authority/PublishedMetadataFixtureMain.class',
  'engine-runtime/target/test-classes/cn/agentplatform/common/authority/GatewayPublishedEngineFixtureMain.class',
  'engine-runtime/target/test-classes/cn/agentplatform/common/authority/GatewayRunStatusEngineFixtureMain.class',
  'engine-runtime/target/test-classes/cn/agentplatform/engine/infra/EngineMigrations.class',
  'platform-gateway/target/test-classes/cn/agentplatform/common/authority/PublishedMetadataSmokeMain.class',
  'app-management/src/test/java/cn/agentplatform/common/authority/PublishedAdmissionFixtureMain.java',
  'app-management/src/test/java/cn/agentplatform/common/authority/PublishedMetadataFixtureMain.java',
  'engine-runtime/src/test/java/cn/agentplatform/common/authority/GatewayPublishedEngineFixtureMain.java',
  'engine-runtime/src/test/java/cn/agentplatform/common/authority/GatewayRunStatusEngineFixtureMain.java',
  'engine-runtime/src/test/java/cn/agentplatform/engine/infra/EngineMigrations.java',
  'docs/evidence/2026-10-28-EX-04/collector/inputs/full-engine-candidate/legacy-runtime-proof.json',
  'docs/evidence/2026-10-29-EX-15a/gateway/run-module.ps1',
  'docs/evidence/2026-10-29-EX-15a/gateway/module-candidate.md',
  'docs/evidence/2026-10-29-EX-15a/gateway/prepare-module.ps1')){$extra.Add($path)}
 foreach($row in $catalog){if($row.source){$extra.Add($row.source)};$extra.Add($row.runtime)}
 return [pscustomobject]@{paths=@($extra.ToArray() | Sort-Object -Unique);observations=$facts}
}
try {
 # Extract only these pure functions from the frozen runner AST, never its top-level body.
 if((Get-FileHash -LiteralPath $runner -Algorithm SHA256).Hash.ToLowerInvariant() -cne $runnerSha){throw 'FROZEN_MODULE_RUNNER_CHANGED'}
 $tokens=$null;$parseErrors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($runner,[ref]$tokens,[ref]$parseErrors)
 if($parseErrors.Count){throw 'MODULE_RUNNER_PARSE_ERROR'}
 foreach($name in @('Own-Path','Read-Json','Sha','Fingerprints')){
  $definitions=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true))
  if($definitions.Count -ne 1){throw 'RUNNER_FUNCTION_NOT_UNIQUE'}
  . ([scriptblock]::Create($definitions[0].Extent.Text))
 }
 if(Test-Path -LiteralPath $private){throw 'PRIVATE_RUN_EXISTS'}
 if($Mode -ceq 'Before'){
  if(Test-Path -LiteralPath $output){throw 'PREPARATION_EXISTS'}
  New-Item -ItemType Directory -Path $output -Force | Out-Null
 } elseif(-not(Test-Path -LiteralPath $manifestPath)){throw 'BEFORE_MANIFEST_REQUIRED'}
 if(Test-Path -LiteralPath (Join-Path $output ('pg-history-'+$Mode.ToLowerInvariant()+'.json'))){throw 'HISTORY_EXISTS_NO_OVERWRITE'}
 New-Item -ItemType Directory -Path $private -Force | Out-Null; Private-Acl $private
 & git -C $ApRoot check-ignore --no-index --quiet -- $private
 if($LASTEXITCODE -ne 0){throw 'PRIVATE_DIRECTORY_NOT_IGNORED'}
 Save-New (Join-Path $output ('executed-prepare-'+$Mode.ToLowerInvariant()+'.json')) @{scriptSha256=(Sha $PSCommandPath);runnerSha256=$runnerSha;mode=$Mode;executeApproved=[bool]$ExecuteApproved;head=(((& git -C $ApRoot rev-parse HEAD)-join '').Trim());timeUtc=[DateTime]::UtcNow.ToString('o')}
 Initialize-Crc
 # Controls copied from the already-proven EX04 Flyway algorithm; language-only, no Java execution.
 if([EX15Gateway.FlywayCrc]::File((Join-Path $ApRoot 'engine-runtime/src/main/resources/db/migration/V200__engine.sql')) -ne -1129590471 -or
    [EX15Gateway.FlywayCrc]::File((Join-Path $ApRoot 'engine-runtime/src/main/resources/db/migration/V201__engine_tool_approval.sql')) -ne 580067347){throw 'FLYWAY_CRC_CONTROL_FAILED'}
 $catalog=@(Migration-Catalog 'app-management')+@(Migration-Catalog 'engine-runtime')
 Add-Type -AssemblyName System.IO.Compression.FileSystem
 $cp=Classpath-Inputs; $classpathFacts=$cp.observations
 if($Mode -ceq 'Before'){
  $candidate=[pscustomobject][ordered]@{approved=$false;baselineDirectory='docs/evidence/2026-10-28-EX-27/runs/safety-module-20261028-233458-012';greenXml='docs/evidence/2026-10-29-EX-15a/gateway/gateway-green-20261029-ex15a-01/methods.xml';greenSummary='docs/evidence/2026-10-29-EX-15a/gateway/gateway-green-20261029-ex15a-01/summary.json';greenSources='docs/evidence/2026-10-29-EX-15a/gateway/gateway-green-20261029-ex15a-01/source-before.json';expectedFingerprints=(Relative (Join-Path $output 'module-fingerprints.json'));pgAdmission=(Relative (Join-Path $output 'module-pg-admission.json'));addedClass='cn.agentplatform.gateway.ArtifactUploadGatewayTest';addedSource='platform-gateway/src/test/java/cn/agentplatform/gateway/ArtifactUploadGatewayTest.java';javaHome='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot';unixTmpDir='V:/.local/tmp';extraFingerprintPaths=$cp.paths}
 } else {
  $candidate=Read-Json $manifestPath
  if((ConvertTo-Json -InputObject @($candidate.extraFingerprintPaths) -Compress) -cne (ConvertTo-Json -InputObject @($cp.paths) -Compress)){throw 'CLASSPATH_RESOURCE_SET_CHANGED'}
 }
 $fingerprints=Fingerprints
 if($Mode -ceq 'After'){
  $frozen=Read-Json (Own-Path $candidate.expectedFingerprints)
  if((ConvertTo-Json -InputObject $frozen -Compress) -cne (ConvertTo-Json -InputObject $fingerprints -Compress)){throw 'MODULE_SOURCE_OR_CLASSPATH_DRIFT'}
 }
 Save-New (Join-Path $output ('migration-catalog-'+$Mode.ToLowerInvariant()+'.json')) $catalog
 Save-New (Join-Path $output ('classpath-observation-'+$Mode.ToLowerInvariant()+'.json')) $classpathFacts
 Save-New (Join-Path $output ('module-fingerprints'+$(if($Mode -ceq 'After'){'-after'}else{''})+'.json')) $fingerprints
 $phase='read-only-pg';$configurationSha=Sha (Join-Path $ApRoot '.env')
 $values=@{};foreach($line in [IO.File]::ReadAllLines((Join-Path $ApRoot '.env'),[Text.Encoding]::UTF8)){if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
 if($values['AGENT_DB_USER'] -cne 'agent_v12' -or -not $values['AGENT_DB_PASSWORD']){throw 'AP_CREDENTIAL_CONFIG_REQUIRED'}
 foreach($name in @('PGPASSWORD','PGCONNECT_TIMEOUT','PGOPTIONS')){$oldEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
 $env:PGPASSWORD=$values['AGENT_DB_PASSWORD'];$env:PGCONNECT_TIMEOUT='5';$env:PGOPTIONS='-c statement_timeout=5000 -c default_transaction_read_only=on'
 $listeners=@(Get-NetTCPConnection -LocalPort 25432 -State Listen -ErrorAction Stop)
 if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -cne '127.0.0.1'){throw 'AP_LISTENER_REQUIRED'}
 $pgProcess=Get-CimInstance Win32_Process -Filter ('ProcessId='+$listeners[0].OwningProcess)
 if($pgProcess.ExecutablePath.Replace('\','/') -cne 'V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe' -or -not $pgProcess.CommandLine.Replace('\','/').Contains('V:/.local/pgdata')){throw 'AP_POSTGRES_IDENTITY_REQUIRED'}
 $instance=Read-Pg 'postgres' "select json_build_object('directory',current_setting('data_directory'),'role',current_user,'readOnly',current_setting('transaction_read_only'))"
 if($instance.directory.Replace('\','/').TrimEnd('/') -cne 'V:/.local/pgdata' -or $instance.role -cne 'agent_v12' -or $instance.readOnly -cne 'on'){throw 'AP_READONLY_INSTANCE_REQUIRED'}
 $scopes=@(
  @{name='agent_platform_iam_admission_app_v12';schema='app';module='app-management';target=104;metadataOnly=$false},
  @{name='agent_platform_iam_admission_engine_v12';schema='engine';module='engine-runtime';target=223;metadataOnly=$false},
  @{name='agent_platform_iam_o1c_gateway_status_v12';schema='engine';module='engine-runtime';target=223;metadataOnly=$false},
  @{name='agent_platform_iam_metadata_v12';schema='app';module='app-management';target=104;metadataOnly=$true})
 foreach($scope in $scopes){
  $databaseName=$scope.name;$schema=$scope.schema
  $owner=Read-Pg 'postgres' ("select json_build_object('rows',coalesce(json_agg(json_build_object('name',datname,'owner',pg_get_userbyid(datdba))),'[]'::json)) from pg_database where datname='"+$databaseName+"'")
  if(@($owner.rows).Count -ne 1 -or $owner.rows[0].name -cne $databaseName -or $owner.rows[0].owner -cne 'agent_v12'){throw 'EXISTING_OWN_DATABASE_REQUIRED'}
  $state=Read-Pg $databaseName ("select json_build_object('database',current_database(),'schemaOwner',pg_get_userbyid(n.nspowner),'historyOwner',pg_get_userbyid(c.relowner)) from pg_namespace n left join pg_class c on c.oid=to_regclass('"+$schema+".flyway_schema_history') where n.nspname='"+$schema+"'")
  if($state.database -cne $databaseName -or $state.schemaOwner -cne 'agent_v12' -or $state.historyOwner -cne 'agent_v12'){throw 'OWN_SCHEMA_HISTORY_REQUIRED'}
  $data=Read-Pg $databaseName ("select json_build_object('rows',coalesce(json_agg(json_build_object('rank',installed_rank,'version',version,'type',type,'script',script,'checksum',checksum,'installedBy',installed_by,'success',success) order by installed_rank),'[]'::json)) from "+$schema+".flyway_schema_history")
  $history=@($data.rows);$sql=@($history | Where-Object type -ceq 'SQL');$allowed=@($catalog | Where-Object module -ceq $scope.module)
  if($scope.metadataOnly){$allowed=@($allowed | Where-Object {[int]$_.version -le 104})}
  if(@($history | Where-Object {$_.success -ne $true -or $_.installedBy -cne 'agent_v12' -or $_.type -cnotin @('SQL','SCHEMA')}).Count -or @($sql | Group-Object version | Where-Object Count -ne 1).Count){throw 'FAILED_UNKNOWN_OR_DUPLICATE_HISTORY'}
  $markers=@($history | Where-Object type -ceq 'SCHEMA')
  if($markers.Count -gt 1 -or @($markers | Where-Object {$_.rank -ne 0 -or $null -ne $_.version -or $null -ne $_.checksum -or $_.script -cne ('"'+$schema+'"')}).Count){throw 'SCHEMA_MARKER_NOT_ORIGINAL'}
  foreach($row in $sql){
   $match=@($allowed | Where-Object {$_.version -ceq $row.version -and $_.script -ceq $row.script})
   if($match.Count -ne 1 -or $null -eq $row.checksum -or [int]$row.checksum -ne [int]$match[0].checksum){throw 'SQL_HISTORY_NOT_EXACT_SOURCE_OR_V222'}
  }
  $required=@($allowed | Where-Object {[int]$_.version -le $scope.target})
  foreach($migration in $required){if(@($sql | Where-Object {$_.version -ceq $migration.version}).Count -ne 1){throw 'MIGRATE_WOULD_APPLY_UNADMITTED_SQL'}}
  if($schema -ceq 'engine' -and (@($allowed).Count -ne 24 -or ($allowed | Measure-Object -Property version -Maximum).Maximum -ne 223)){throw 'ENGINE_RUNTIME_VERSION_SET_CHANGED'}
  $proofs+=[pscustomobject][ordered]@{name=$databaseName;existed=$true;owner=$owner.rows[0].owner;projectOwnershipVerified=$true;historyVerified=$true;schema=$schema;schemaOwner=$state.schemaOwner;historyOwner=$state.historyOwner;fixtureTarget=$scope.target;metadataOnly=$scope.metadataOnly;pendingRequiredMigrations=0;actualSqlRows=$sql.Count;history=$history}
 }
 Save-New (Join-Path $output ('pg-history-'+$Mode.ToLowerInvariant()+'.json')) $proofs
 if($Mode -ceq 'After'){
  $previous=Read-Json (Join-Path $output 'pg-history-before.json')
  if((ConvertTo-Json -InputObject $previous -Depth 18 -Compress) -cne (ConvertTo-Json -InputObject $proofs -Depth 18 -Compress)){throw 'PG_HISTORY_CHANGED_DURING_MODULE'}
 }
 $current=Get-CimInstance Win32_Process -Filter ('ProcessId='+$pgProcess.ProcessId)
 if(-not $current -or $current.CreationDate -ne $pgProcess.CreationDate -or (Sha (Join-Path $ApRoot '.env')) -cne $configurationSha){throw 'INSTANCE_OR_ENV_CHANGED_DURING_ADMISSION'}
 $latest=Fingerprints
 if((ConvertTo-Json -InputObject $latest -Compress) -cne (ConvertTo-Json -InputObject $fingerprints -Compress)){throw 'FINGERPRINTS_CHANGED_DURING_ADMISSION'}
 $receipt=[ordered]@{passed=$true;checkedAtUtc=[DateTime]::UtcNow.ToString('o');mode=$Mode;configurationSha256=$configurationSha;host='127.0.0.1';port=25432;pid=$pgProcess.ProcessId;creationDate=$pgProcess.CreationDate.ToString('o');dataDirectory=$instance.directory;readOnly=$true;databases=$proofs;knownLegacyRuntime=@{path=$legacyPath;sha256=$legacySha;checksum=701700386;sourceAbsent=$true;cleanBuildVerified=$false};noDdlOrQueueCleanup=$true;historyUnchanged=$(if($Mode -ceq 'After'){$true}else{$null});fingerprintsSha256=(Sha (Join-Path $output ('module-fingerprints'+$(if($Mode -ceq 'After'){'-after'}else{''})+'.json')))}
 Save-New (Join-Path $output ('module-pg-admission'+$(if($Mode -ceq 'After'){'-after'}else{''})+'.json')) $receipt
 if($Mode -ceq 'Before'){$candidate.approved=[bool]$ExecuteApproved;Save-New $manifestPath $candidate}
 $passed=$true
 Write-Output ('EX15_GATEWAY_PG_'+$Mode.ToUpperInvariant()+'_PASS fourExistingDatabases=true manifest='+(Relative $manifestPath))
} catch {
 if(Test-Path -LiteralPath $private){
  [IO.File]::WriteAllText((Join-Path $private 'failure.raw.log'),($_ | Out-String),(New-Object Text.UTF8Encoding($false)))
  Save-New (Join-Path $private 'observed-history.private.json') @{completed=$proofs;currentDatabase=$databaseName;currentHistory=$history}
 }
 if(Test-Path -LiteralPath $output){
  $errorPath=Join-Path $output ('prepare-'+$Mode.ToLowerInvariant()+'-error.json')
  if(-not(Test-Path -LiteralPath $errorPath)){Save-New $errorPath @{passed=$false;phase=$phase;database=$databaseName;scriptLine=$_.InvocationInfo.ScriptLineNumber;type=$_.Exception.GetType().FullName;reason='READONLY_ADMISSION_FAILED';rawMessagePrivate=$true;observedCompletedDatabases=$proofs.Count;noDdl=$true}}
 }
 Write-Output ('EX15_GATEWAY_PG_'+$Mode.ToUpperInvariant()+'_FAILED phase='+$phase)
} finally {foreach($name in $oldEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$oldEnvironment[$name],'Process')}}
if(-not $passed){exit 2}
