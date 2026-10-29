param(
 [Parameter(Mandatory=$true)][ValidateSet('Full','Adjacent')][string]$Mode,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ApprovedManifestSha256,
 [switch]$ExecuteApproved
)
$ErrorActionPreference='Stop'
if(-not $ExecuteApproved){throw 'EXCLUSIVE_ACTUAL_WINDOW_NOT_GRANTED'}
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$taskRun=Join-Path $PSScriptRoot ('runs/'+$RunId)
$manifestPath=Join-Path $PSScriptRoot 'manifest.json'
if((Get-FileHash -LiteralPath $manifestPath).Hash -cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'APPROVED_MANIFEST_CHANGED'}
if(Test-Path -LiteralPath $taskRun){throw 'RUN_DIRECTORY_ALREADY_EXISTS'}
$manifest=[IO.File]::ReadAllText($manifestPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
$prior=@{};$location=Get-Location;$phase='INPUT_ADMISSION';$native=$null;$collectorExit=2
New-Item -ItemType Directory -Path $taskRun|Out-Null
function Write-Fact([string]$Name,$Value){
 [IO.File]::WriteAllText((Join-Path $taskRun $Name),(ConvertTo-Json -InputObject $Value -Depth 14),(New-Object Text.UTF8Encoding($false)))
}
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash}
function Assert-Pin([string]$Path,[string]$Sha){
 $full=[IO.Path]::GetFullPath((Join-Path $taskRoot $Path))
 if(-not $full.StartsWith($taskRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $Sha -cnotmatch '^[a-fA-F0-9]{64}$' -or -not(Test-Path -LiteralPath $full -PathType Leaf) -or (Hash $full) -cne $Sha.ToUpperInvariant()){throw 'PINNED_INPUT_CHANGED'}
}
function Sources {
 @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'skill-registry/src') -Recurse -File|Sort-Object FullName|ForEach-Object{
  [pscustomobject]@{path=$_.FullName.Substring($taskRoot.Length+1).Replace([char]92,[char]47);sha256=(Hash $_.FullName)}
 })
}
function Same-Sources($Expected,$Actual){
 $map=@{};foreach($r in $Expected){$p=$r.path.Replace([char]92,[char]47);if($map.ContainsKey($p)){return $false};$map[$p]=$r.sha256.ToUpperInvariant()}
 return $map.Count -eq 150 -and @($Actual).Count -eq 150 -and @($Actual|Where-Object{-not $map.ContainsKey($_.path) -or $map[$_.path] -cne $_.sha256}).Count -eq 0
}
function Identity-Map($Rows){
 $map=@{};foreach($r in $Rows){$key=Get-Ex04Identity $r;if($map.ContainsKey($key)){throw 'DUPLICATE_METHOD_IDENTITY'};$map[$key]=$r};return $map
}
function Read-Pg([string]$Database,[string]$Sql){
 $out=& $pg -X -w -h 127.0.0.1 -p 25432 -U agent_v12 -d $Database -At -v ON_ERROR_STOP=1 -c $Sql 2>> (Join-Path $taskRun 'pg.raw.log')
 if($LASTEXITCODE -ne 0){throw 'READONLY_PG_QUERY_FAILED'}
 (($out -join "`n")|ConvertFrom-Json)
}
function Check-Storage([string]$Label){
 $saved=[Environment]::GetEnvironmentVariable('PGOPTIONS','Process')
 try{
  $env:PGOPTIONS='-c statement_timeout=5000 -c default_transaction_read_only=on'
  $listeners=@(Get-NetTCPConnection -LocalPort 25432 -State Listen)
  if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -cne '127.0.0.1' -or $listeners[0].OwningProcess -ne 15204){throw 'PG_LISTENER_CHANGED'}
  $process=Get-CimInstance Win32_Process -Filter 'ProcessId=15204'
  if($process.ExecutablePath.Replace([char]92,[char]47) -cne 'V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe' -or $process.CommandLine -notlike '*V:/.local/pgdata*'){throw 'PG_INSTANCE_CHANGED'}
  $instance=Read-Pg 'postgres' "select json_build_object('directory',current_setting('data_directory'),'role',current_user)"
  if($instance.directory.Replace([char]92,[char]47).TrimEnd('/') -cne 'V:/.local/pgdata' -or $instance.role -cne 'agent_v12'){throw 'PG_DATA_OR_ROLE_CHANGED'}
  $known=@{};foreach($r in $manifest.knownSqlHistory){$known[$r.script]=$r}
  $proofs=@(foreach($database in @('agent_platform_v12','agent_platform_skill_algorithm_test','agent_platform_skill_algorithm_migration_test','agent_platform_skill_digest_test','agent_platform_ontology_import_test')){
   $owner=Read-Pg 'postgres' ("select json_build_object('database',datname,'owner',pg_get_userbyid(datdba),'marker',coalesce(shobj_description(oid,'pg_database'),'')) from pg_database where datname='"+$database+"'")
   if(-not $owner -or $owner.database -cne $database -or $owner.owner -cne 'agent_v12'){throw 'EXISTING_DATABASE_OWNER_MISMATCH'}
   if($database -ceq 'agent_platform_ontology_import_test' -and $owner.marker -cne 'agent-platform EX-03 2026-10-28 ontology import isolated test database'){throw 'ONTOLOGY_DATABASE_MARKER_MISMATCH'}
   $schema=Read-Pg $database "select json_build_object('schemaOwner',pg_get_userbyid(n.nspowner),'historySchema',h.nspname,'historyName',c.relname,'historyOwner',pg_get_userbyid(c.relowner)) from pg_namespace n left join pg_class c on c.oid=to_regclass('skill.flyway_schema_history') left join pg_namespace h on h.oid=c.relnamespace where n.nspname='skill'"
   if(-not $schema -or $schema.schemaOwner -cne 'agent_v12' -or $schema.historySchema -cne 'skill' -or $schema.historyName -cne 'flyway_schema_history' -or $schema.historyOwner -cne 'agent_v12'){throw 'OWNED_SKILL_HISTORY_REQUIRED'}
   $history=@(Read-Pg $database "select coalesce(json_agg(json_build_object('rank',installed_rank,'version',version,'type',type,'script',script,'checksum',checksum,'installedBy',installed_by,'success',success) order by installed_rank),'[]'::json) from skill.flyway_schema_history")
   $seen=@{};$schemaRows=0;$sqlRows=0
   foreach($r in $history){
    if(-not $r.success -or $r.installedBy -cne 'agent_v12'){throw 'UNTRUSTED_FLYWAY_HISTORY'}
    if($r.type -ceq 'SCHEMA'){
     $schemaRows++;if($r.rank -ne 0 -or $null -ne $r.version -or $r.script -cne '"skill"' -or $null -ne $r.checksum){throw 'UNKNOWN_FLYWAY_SCHEMA_ROW'}
    }elseif($r.type -ceq 'SQL'){
     if($r.rank -lt 1 -or -not $known.ContainsKey($r.script) -or $seen.ContainsKey($r.script) -or $r.version -cne $known[$r.script].version -or $r.checksum -ne $known[$r.script].checksum){throw 'UNKNOWN_OR_CHANGED_FLYWAY_SQL'}
     $seen[$r.script]=$true;$sqlRows++
    }else{throw 'UNKNOWN_FLYWAY_TYPE'}
   }
   if($sqlRows -eq 0 -or $schemaRows -gt 1 -or ($database -ceq 'agent_platform_v12' -and $sqlRows -ne $known.Count)){throw 'FLYWAY_HISTORY_INCOMPLETE'}
   [pscustomobject]@{database=$database;owner='agent_v12';schemaOwner='agent_v12';historyOwner='agent_v12';history=$history;knownHistoryOnly=$true;collectorDdl=$false;originalReplay=($database -cin @('agent_platform_skill_algorithm_migration_test','agent_platform_skill_digest_test'))}
  })
  $endpoint=[Uri]$env:AGENT_MINIO_ENDPOINT
  if($endpoint.Scheme -cne 'http' -or $endpoint.Host -cne '127.0.0.1' -or $endpoint.Port -ne 29000 -or $endpoint.UserInfo -or $endpoint.Query -or $endpoint.Fragment){throw 'MINIO_ENDPOINT_OUTSIDE_APPROVED_SCOPE'}
  $health=Invoke-WebRequest -UseBasicParsing -Uri ($endpoint.GetLeftPart([UriPartial]::Authority)+'/minio/health/live') -TimeoutSec 10
  if([int]$health.StatusCode -ne 200){throw 'MINIO_HEALTH_FAILED'}
  Write-Fact ($Label+'-storage.json') ([ordered]@{checkedAt=[DateTimeOffset]::Now.ToString('o');pgPid=15204;pgPort=25432;knownDataDirectory=$true;databaseProofs=$proofs;minioPort=29000;minioHealthy=$true;collectorReadOnly=$true;collectorDdl=$false;unknownDatabaseCreation=$false;queueCleanup=$false})
 }finally{[Environment]::SetEnvironmentVariable('PGOPTIONS',$saved,'Process')}
}
# Same exact-case locator as EX04/compare-full-reasons.ps1; its old baseline/entry is not executed.
function Read-ExactReasonText([string]$Directory,$Record){
 $name=$Record.xml.Substring(4);if($name -notmatch '^TEST-[A-Za-z0-9_.$-]+\.xml$'){throw 'REASON_XML_SCOPE_MISMATCH'}
 $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load((Join-Path $Directory $name))
 $found=@(foreach($case in $doc.SelectNodes("//*[local-name()='testcase']")){
  $suites=New-Object 'System.Collections.Generic.List[string]';$parent=$case.ParentNode
  while($parent -and $parent -is [Xml.XmlElement]){if($parent.LocalName -eq 'testsuite'){$suites.Insert(0,$parent.GetAttribute('name'))};$parent=$parent.ParentNode}
  $candidate=[pscustomobject]@{classname=$case.GetAttribute('classname');method=$case.GetAttribute('name');display_name=$case.GetAttribute('displayName');suite_path=($suites -join ' > ')}
  if((Get-Ex04Identity $candidate) -ceq (Get-Ex04Identity $Record)){$case}
 })
 if($found.Count -ne 1){throw 'EXACT_REASON_CASE_NOT_UNIQUE'}
 $event=$found[0].SelectSingleNode('./'+$Record.status);if(-not $event){throw 'EXACT_REASON_EVENT_MISSING'}
 [pscustomobject]@{message=$event.GetAttribute('message');detail=$event.InnerText.Replace("`r`n","`n")}
}
try{
 Set-Location -LiteralPath $taskRoot
 foreach($pin in $manifest.pins){Assert-Pin $pin.path $pin.sha256}
 Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $taskRun 'approved-manifest.json')
 Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $taskRun 'collector.ps1')
 . (Join-Path $taskRoot $manifest.reportReader)
 $baseline=Join-Path $taskRoot $manifest.baselineDirectory
 $baseXmlManifest=[IO.File]::ReadAllText((Join-Path $baseline 'xml-manifest.json'))|ConvertFrom-Json
 foreach($pin in $baseXmlManifest){Assert-Pin ($manifest.baselineDirectory+'/xml/'+$pin.file) $pin.sha256}
 $baseReport=Read-Ex04FullReports (Join-Path $baseline 'xml')
 $baseCsv=@(Import-Csv -LiteralPath (Join-Path $baseline 'methods.csv'))
 $baseMap=Identity-Map $baseReport.rows;$csvMap=Identity-Map $baseCsv
 if($baseMap.Count -ne 689 -or $csvMap.Count -ne 689 -or @($baseReport.classes).Count -ne 71 -or @($baseReport.issues).Count -ne 0){throw 'EX04_689_BASELINE_REQUIRED'}
 foreach($key in $baseMap.Keys){if(-not $csvMap.ContainsKey($key) -or $csvMap[$key].status -cne $baseMap[$key].status -or $csvMap[$key].message_sha256 -cne $baseMap[$key].message_sha256 -or $csvMap[$key].detail_sha256 -cne $baseMap[$key].detail_sha256){throw 'BASELINE_CSV_XML_MISMATCH'}}
 $before=Sources;Write-Fact 'source-before.json' $before
 if(-not(Same-Sources $manifest.sources $before)){throw 'CANDIDATE_150_SOURCE_DRIFT'}
 $baseSources=[IO.File]::ReadAllText((Join-Path $baseline 'source-after.json'))|ConvertFrom-Json
 $baseSourceMap=@{};foreach($s in $baseSources){$baseSourceMap[$s.path]=$s.sha256}
 $delta=@($before|Where-Object{-not $baseSourceMap.ContainsKey($_.path) -or $baseSourceMap[$_.path] -cne $_.sha256}|Select-Object -ExpandProperty path)
 if($baseSourceMap.Count -ne 150 -or $delta.Count -ne 5 -or @($delta|Where-Object{$manifest.allowedSourceDelta -cnotcontains $_}).Count -ne 0){throw 'SOURCE_DELTA_NOT_EXACT_APPROVED_FIVE'}
 foreach($green in $manifest.directedGreen){
  $summary=[IO.File]::ReadAllText((Join-Path $taskRoot ($green.directory+'/summary.json')))|ConvertFrom-Json
  $greenSources=[IO.File]::ReadAllText((Join-Path $taskRoot ($green.directory+'/source-after.json')))|ConvertFrom-Json
  if($summary.nativeExit -ne 0 -or $summary.tests -ne $green.tests -or $summary.passed -ne $green.tests -or $summary.skipped -ne 0 -or -not $summary.sourceStable -or -not(Same-Sources $greenSources $before)){throw 'SAME_SOURCE_DIRECTED_GREEN_MISSING'}
 }
 $head=((& git rev-parse HEAD)-join '');if($LASTEXITCODE -ne 0 -or $head -cnotmatch '^[a-f0-9]{40}$'){throw 'HEAD_UNAVAILABLE'}
 $expected=@{};foreach($key in $baseMap.Keys){if($Mode -ceq 'Full' -or $manifest.adjacentClasses -ccontains $baseMap[$key].classname){$expected[$key]=$baseMap[$key]}}
 if($Mode -ceq 'Full'){foreach($row in $manifest.newMethods){$key=Get-Ex04Identity $row;if($expected.ContainsKey($key)){throw 'NEW_METHOD_DUPLICATES_BASELINE'};$expected[$key]=$row}}
 $expectedCount=if($Mode -ceq 'Full'){698}else{32};$expectedXml=if($Mode -ceq 'Full'){71}else{5}
 $expectedClasses=@($expected.Values|Select-Object -ExpandProperty classname -Unique)
 if($expected.Count -ne $expectedCount -or @($manifest.newMethods).Count -ne 9){throw 'WRONG_APPROVED_METHOD_SET'}
 Write-Fact 'admission.json' ([ordered]@{mode=$Mode;head=$head;manifestSha256=(Hash $manifestPath);collectorSha256=(Hash $PSCommandPath);baseline=$manifest.baselineDirectory;baselineRecords=689;expectedRecords=$expectedCount;expectedXml=$expectedXml;sourceCount=150;sourceDelta=$delta;exclusiveWindowExplicitlyGranted=$true;originalReplayDatabases=@('agent_platform_skill_algorithm_migration_test','agent_platform_skill_digest_test');collectorDdl=$false})
 $phase='ENVIRONMENT_AND_READONLY_STORAGE'
 Assert-Pin '.env' $manifest.envSha256
 $values=@{};foreach($line in [IO.File]::ReadAllLines((Join-Path $taskRoot '.env'),[Text.Encoding]::UTF8)){if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
 foreach($key in (@($values.Keys)+@('JAVA_HOME','PATH','ENGINE_PG_TEST','PGPASSWORD','PGCONNECT_TIMEOUT','PGOPTIONS')|Select-Object -Unique)){$prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
 foreach($key in $values.Keys){[Environment]::SetEnvironmentVariable($key,$values[$key],'Process')}
 $env:JAVA_HOME=$manifest.jdk;$env:PATH="$env:JAVA_HOME/bin;$env:PATH";$env:ENGINE_PG_TEST='true';$env:PGPASSWORD=$env:AGENT_DB_PASSWORD;$env:PGCONNECT_TIMEOUT='5'
 if(-not $env:AGENT_DB_PASSWORD -or $env:AGENT_DEV_AUTH -cne 'true' -or -not [string]::IsNullOrEmpty($env:AGENT_SECURITY_MODE)){throw 'ORIGINAL_ENVIRONMENT_REQUIRED'}
 foreach($key in @('MAVEN_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')){if(-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key,'Process'))){throw 'UNAPPROVED_JVM_OPTION_ENVIRONMENT'}}
 $jar=Join-Path $taskRoot $manifest.adminJar;Assert-Pin $manifest.adminJar $manifest.adminJarSha256
 if(-not(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe') -PathType Leaf)){throw 'APPROVED_JDK_MISSING'}
 [void](Get-Command mvn.cmd -CommandType Application -ErrorAction Stop)
 $pg=Join-Path $taskRoot '.tools/postgresql-16.15/pgsql/bin/psql.exe'
 Check-Storage 'before'
 $arguments=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true')
 $ignore=$Mode -ceq 'Full'
 if($ignore){$arguments+='-Dmaven.test.failure.ignore=true'}else{$arguments+=('-Dtest='+($manifest.adjacentClasses -join ','))}
 $phase='MAVEN';$started=[DateTime]::UtcNow
 Write-Fact 'command.json' ([ordered]@{command='mvn.cmd';arguments=$arguments;startedAt=$started.ToString('o');head=$head;envSha256=(Hash '.env');adminJarSha256=(Hash $jar);jdk=$env:JAVA_HOME;failureIgnore=$ignore;defaultForkReuseAndOrder=$true})
 try{$ErrorActionPreference='Continue';& mvn.cmd @arguments *> (Join-Path $taskRun 'raw.log');$native=$LASTEXITCODE}
 finally{$ErrorActionPreference='Stop';Write-Fact 'native-exit.json' ([ordered]@{nativeExit=$native;startedAt=$started.ToString('o');endedAt=[DateTime]::UtcNow.ToString('o');failureIgnore=$ignore;nativeZeroIsNotFullGreen=$ignore})}
 $phase='ARCHIVE_AND_COMPARE'
 $after=Sources;Write-Fact 'source-after.json' $after;$sourceStable=Same-Sources $manifest.sources $after
 $endHead=((& git rev-parse HEAD)-join '');$contextStable=$endHead -ceq $head -and (Hash '.env') -ceq $manifest.envSha256 -and (Hash $jar) -ceq $manifest.adminJarSha256
 Write-Fact 'command-end.json' ([ordered]@{head=$endHead;envSha256=(Hash '.env');adminJarSha256=(Hash $jar);sourceStable=$sourceStable;contextStable=$contextStable})
 $xml=Join-Path $taskRun 'xml';New-Item -ItemType Directory -Path $xml|Out-Null
 $fresh=@(Get-ChildItem -LiteralPath 'skill-registry/target/surefire-reports' -File -Filter 'TEST-*.xml'|Where-Object{$_.LastWriteTimeUtc -ge $started})
 foreach($f in $fresh){Copy-Item -LiteralPath $f.FullName -Destination $xml}
 Write-Fact 'xml-manifest.json' @($fresh|ForEach-Object{[pscustomobject]@{file=$_.Name;bytes=$_.Length;mtimeUtc=$_.LastWriteTimeUtc.ToString('o');sha256=(Hash (Join-Path $xml $_.Name))}})
 Write-Fact 'raw-log-manifest.json' ([ordered]@{path='raw.log';bytes=(Get-Item -LiteralPath (Join-Path $taskRun 'raw.log')).Length;sha256=(Hash (Join-Path $taskRun 'raw.log'));unmodified=$true})
 Assert-Pin $manifest.reportReader $manifest.reportReaderSha256
 $report=Read-Ex04FullReports $xml;$rows=@($report.rows);$actual=Identity-Map $rows
 $rows|Export-Csv -LiteralPath (Join-Path $taskRun 'methods.csv') -NoTypeInformation -Encoding UTF8
 Write-Fact 'xml-issues.json' @($report.issues)
 $missing=@($expected.Keys|Where-Object{-not $actual.ContainsKey($_)});$unexpected=@($actual.Keys|Where-Object{-not $expected.ContainsKey($_)})
 $statusChanges=@(foreach($key in $expected.Keys){if($actual.ContainsKey($key) -and $actual[$key].status -cne $expected[$key].status){[pscustomobject]@{identity=$key;before=$expected[$key].status;after=$actual[$key].status}}})
 Write-Fact 'method-status-changes.json' $statusChanges
 $uuid='[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
 $stamp='\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}(?::?\d{2})?)?'
 $volatileProof=New-Object 'System.Collections.Generic.List[object]'
 $reasons=@(foreach($key in $expected.Keys|Sort-Object){
  $old=$expected[$key];if($old.status -ceq 'passed'){continue};$current=$actual[$key]
  $structural=$null -ne $current -and $old.status -ceq $current.status -and $old.exception_type -ceq $current.exception_type -and $old.cause_types -ceq $current.cause_types -and $old.first_project_frame -ceq $current.first_project_frame
  $messageSame=$null -ne $current -and $old.message_sha256 -ceq $current.message_sha256
  $detailSame=$null -ne $current -and $old.detail_sha256 -ceq $current.detail_sha256;$bounded=$false
  if($structural -and (-not $messageSame -or -not $detailSame) -and $old.classname -ceq 'cn.agentplatform.skill.infra.AlgorithmMigrationPostgresTest' -and $old.method -ceq 'v310UpgradePreservesLegacyHttpProofAndAlgorithmRowsAndAddsScopedUniqueIdentity' -and $old.status -ceq 'failure' -and $old.exception_type -ceq 'org.opentest4j.AssertionFailedError'){
   $a=Read-ExactReasonText (Join-Path $baseline 'xml') $old;$b=Read-ExactReasonText $xml $current
   $counts=@([regex]::Matches($a.message,$uuid).Count,[regex]::Matches($b.message,$uuid).Count,[regex]::Matches($a.message,$stamp).Count,[regex]::Matches($b.message,$stamp).Count,[regex]::Matches($a.detail,$uuid).Count,[regex]::Matches($b.detail,$uuid).Count,[regex]::Matches($a.detail,$stamp).Count,[regex]::Matches($b.detail,$stamp).Count)
   $sameMessage=[regex]::Replace([regex]::Replace($a.message,$uuid,'<UUID>'),$stamp,'<TIMESTAMP>') -ceq [regex]::Replace([regex]::Replace($b.message,$uuid,'<UUID>'),$stamp,'<TIMESTAMP>')
   $sameDetail=[regex]::Replace([regex]::Replace($a.detail,$uuid,'<UUID>'),$stamp,'<TIMESTAMP>') -ceq [regex]::Replace([regex]::Replace($b.detail,$uuid,'<UUID>'),$stamp,'<TIMESTAMP>')
   $bounded=@($counts|Where-Object{$_ -ne 2}).Count -eq 0 -and $sameMessage -and $sameDetail
   $volatileProof.Add([pscustomobject]@{identity=$key;scope='EXACT_ONE_MIGRATION_TWO_UUID_TWO_TIMESTAMPS';counts=$counts;normalizedMessageSame=$sameMessage;normalizedDetailSame=$sameDetail;passed=$bounded;rawXmlUnmodified=$true})
  }
  [pscustomobject]@{identity=$key;beforeStatus=$old.status;afterStatus=if($current){$current.status}else{'MISSING'};structureSame=$structural;messageSame=$messageSame;detailSame=$detailSame;beforeMessageSha=$old.message_sha256;afterMessageSha=if($current){$current.message_sha256}else{''};beforeDetailSha=$old.detail_sha256;afterDetailSha=if($current){$current.detail_sha256}else{''};boundedVolatileExplanation=$bounded;reasonPreserved=($structural -and (($messageSame -and $detailSame) -or $bounded))}
 })
 $reasons|Export-Csv -LiteralPath (Join-Path $taskRun 'nonpass-reasons.csv') -NoTypeInformation -Encoding UTF8
 Write-Fact 'bounded-volatile-proof.json' ($volatileProof.ToArray())
 $sdkOkay=$true
 if($Mode -ceq 'Full'){
  $sdk=@($rows|Where-Object{$_.classname -ceq 'cn.agentplatform.skill.infra.AlgorithmOffloadInterruptionTest'})
  $keys=@($report.outcomes|ForEach-Object{$_.stage+'|'+$_.handoff});$wanted=@('PUT|False','GET|False','GET_BODY|False','GET_BODY|True')
  $sdkOkay=$sdk.Count -eq 5 -and @($sdk|Where-Object{$_.status -cne 'passed'}).Count -eq 0 -and @($report.outcomes).Count -eq 4 -and @($wanted|Where-Object{$w=$_;@($keys|Where-Object{$_ -ceq $w}).Count -ne 1}).Count -eq 0 -and @($report.outcomes|Where-Object{-not $_.interrupted -or $_.inputClosed -lt 1 -or (-not $_.handoff -and -not $_.firstQuiescent) -or ($_.handoff -and -not $_.explicitUnconfirmed -and -not $_.firstQuiescent)}).Count -eq 0 -and $report.diagnosticCount -eq 0
  Write-Fact 'sdk-default-outcomes.json' @($report.outcomes)
 }
 $phase='POST_STORAGE';Check-Storage 'after'
 $oldNonpass=if($Mode -ceq 'Full'){116}else{0}
 $reasonGate=$reasons.Count -eq $oldNonpass -and @($reasons|Where-Object{-not $_.reasonPreserved}).Count -eq 0
 $classSetSame=@($expectedClasses|Where-Object{$report.classes -cnotcontains $_}).Count -eq 0 -and @($report.classes|Where-Object{$expectedClasses -cnotcontains $_}).Count -eq 0
 $complete=$rows.Count -eq $expectedCount -and $fresh.Count -eq $expectedXml -and @($report.classes).Count -eq $expectedXml -and $classSetSame -and $missing.Count -eq 0 -and $unexpected.Count -eq 0 -and @($report.issues).Count -eq 0
 $gate=$native -eq 0 -and $complete -and $statusChanges.Count -eq 0 -and $reasonGate -and $sourceStable -and $contextStable -and $sdkOkay
 $summary=[ordered]@{mode=$Mode;nativeExit=$native;failureIgnore=$ignore;baselineRecords=689;expectedRecords=$expectedCount;records=$rows.Count;expectedXml=$expectedXml;actualXml=$fresh.Count;passed=@($rows|Where-Object{$_.status -ceq 'passed'}).Count;failures=@($rows|Where-Object{$_.status -ceq 'failure'}).Count;errors=@($rows|Where-Object{$_.status -ceq 'error'}).Count;skipped=@($rows|Where-Object{$_.status -ceq 'skipped'}).Count;missing=$missing;unexpected=$unexpected;statusChanges=$statusChanges.Count;baselineNonpass=$oldNonpass;unexplainedReasons=@($reasons|Where-Object{-not $_.reasonPreserved}).Count;reasonComparisonPassed=$reasonGate;sdkDefaultPassed=$sdkOkay;sourceStable=$sourceStable;contextStable=$contextStable;reportsComplete=$complete;regressionGatePassed=$gate;allGreen=($gate -and @($rows|Where-Object{$_.status -cne 'passed'}).Count -eq 0);nativeZeroIsNotFullGreen=$ignore;collectorDdl=$false;cardClosed=$false}
 Write-Fact 'summary.json' $summary;$summary|ConvertTo-Json -Depth 5
 $collectorExit=if($gate){0}else{1}
}catch{
 $safeReason=if($_.Exception.Message -cmatch '^[A-Z_0-9]+$'){$_.Exception.Message}else{'UNEXPECTED_COLLECTOR_ERROR'}
 Write-Fact 'collector-error.json' ([ordered]@{phase=$phase;reason=$safeReason;exceptionClass=$_.Exception.GetType().FullName;scriptLine=$_.InvocationInfo.ScriptLineNumber;nativeExit=$native;collectorExit=2;rawEvidencePreserved=$true})
 Write-Output ('EX15A_COLLECTOR_STOPPED phase='+$phase+' line='+$_.InvocationInfo.ScriptLineNumber)
}finally{
 foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')}
 Set-Location -LiteralPath $location
 Write-Fact 'collector-exit.json' ([ordered]@{collectorExit=$collectorExit;nativeExit=$native;sourceAndIndexMutated=$false;rawEvidencePreserved=$true})
}
exit $collectorExit
