# PhaseA Adjacent88 / Full706. Current-source actual GREEN30 is mandatory; no automatic next mode.
param(
 [Parameter(Mandatory=$true)][ValidateSet('Adjacent','Full')][string]$Mode,
 [Parameter(Mandatory=$true)][ValidatePattern('\A[a-z0-9-]+\z')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('\A[A-Fa-f0-9]{64}\z')][string]$ApprovedManifestSha256,
 [switch]$ExecuteApproved
)
$ErrorActionPreference='Stop'
if(-not $ExecuteApproved){throw 'EXCLUSIVE_ACTUAL_WINDOW_NOT_GRANTED'}
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$root=$taskRoot
$manifestPath=Join-Path $PSScriptRoot 'regression-manifest-v2.json'
if((Get-FileHash -LiteralPath $manifestPath).Hash -cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'APPROVED_MANIFEST_CHANGED'}
$manifest=[IO.File]::ReadAllText($manifestPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
if(-not $manifest.greenPrerequisite.sealed){throw 'ACTUAL_GREEN_PREREQUISITE_UNSEALED'}
if(-not $manifest.modeSealed.$Mode){throw 'MODE_NOT_SEALED_FOR_ACTUAL_EXECUTION'}
$taskRun=Join-Path $PSScriptRoot ('runs/'+$RunId)
if(Test-Path -LiteralPath $taskRun){throw 'RUN_DIRECTORY_ALREADY_EXISTS'}
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
 return $map.Count -eq 152 -and @($Actual).Count -eq 152 -and @($Actual|Where-Object{-not $map.ContainsKey($_.path) -or $map[$_.path] -cne $_.sha256}).Count -eq 0
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
function Read-Classpaths([string]$Directory){
 $expected=[IO.Path]::GetFullPath((Join-Path $root $manifest.commonContext.mainJar)).Replace([char]92,[char]47)
 @(foreach($file in Get-ChildItem -LiteralPath $Directory -File -Filter 'TEST-*.xml'){
  $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load($file.FullName)
  $property=$doc.SelectSingleNode("//property[@name='java.class.path']")
  $cp=if($property){$property.GetAttribute('value')}else{''}
  $common=@();$badPath=0;$tests=0
  foreach($part in ($cp -split [regex]::Escape([string][IO.Path]::PathSeparator))){
   if([string]::IsNullOrWhiteSpace($part)){continue}
   if(-not [IO.Path]::IsPathRooted($part) -or $part -match '^[A-Za-z]:(?![\\/])'){$badPath++;continue}
   try{$full=[IO.Path]::GetFullPath($part).Replace([char]92,[char]47);$name=[IO.Path]::GetFileName($full)}
   catch{$badPath++;continue}
   if($name -imatch '^admin-common.*\.jar$'){$common+=$full}
   if($name -imatch '^admin-common.*-tests\.jar$'){$tests++}
  }
  $exact=@($common|Where-Object{[string]::Equals($_,$expected,[StringComparison]::OrdinalIgnoreCase)}).Count
  [pscustomobject]@{xml=$file.Name;present=($null -ne $property);valueSha256=(Get-Ex04TextSha $cp);commonJarCandidates=$common.Count;exactApMainJarMatches=$exact;otherCommonJarCount=($common.Count-$exact);testJarEntries=$tests;unparseableClasspathEntries=$badPath;mainJarSha256=(Hash (Join-Path $root $manifest.commonContext.mainJar));expectedMainJarRelativePath=$manifest.commonContext.mainJar;fullClasspathNotPublished=$true}
 })
}
function Check-Context {
 $facts=[ordered]@{head=((& git -C $taskRoot rev-parse HEAD)-join '');envSha256=(Hash (Join-Path $taskRoot '.env'));files=@(foreach($p in $manifest.contextPins){$full=if([IO.Path]::IsPathRooted($p.path)){$p.path}else{Join-Path $taskRoot $p.path};[pscustomobject]@{path=$p.path;sha256=(Hash $full)}})}
 if($facts.head -cne $manifest.expectedHead -or $facts.envSha256 -cne $manifest.envSha256 -or @($facts.files|Where-Object{$a=$_;@($manifest.contextPins|Where-Object{$_.path -ceq $a.path -and $_.sha256 -ceq $a.sha256}).Count -ne 1}).Count){throw 'CONTEXT_HEAD_ENV_OR_DEPENDENCY_CHANGED'}
 return $facts
}
function Exact-Classpaths($Rows,[int]$Count){
 @($Rows).Count -eq $Count -and @($Rows|Where-Object{-not $_.present -or $_.valueSha256 -cne $manifest.expectedClasspathValueSha256 -or $_.commonJarCandidates -ne 1 -or $_.exactApMainJarMatches -ne 1 -or $_.otherCommonJarCount -ne 0 -or $_.testJarEntries -ne 0 -or $_.unparseableClasspathEntries -ne 0 -or $_.mainJarSha256 -cne $manifest.commonContext.preparedMainJarSha256}).Count -eq 0
}
function Read-PinnedReport([string]$RelativeDirectory,[int]$Count,[int]$XmlCount){
 $directory=Join-Path $taskRoot $RelativeDirectory
 $xmlPins=[IO.File]::ReadAllText((Join-Path $directory 'xml-manifest.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 if(@($xmlPins).Count -ne $XmlCount){throw 'PINNED_XML_COUNT_CHANGED'}
 foreach($p in $xmlPins){Assert-Pin ($RelativeDirectory+'/xml/'+$p.file) $p.sha256}
 $report=Read-Ex04FullReports (Join-Path $directory 'xml')
 $map=Identity-Map @($report.rows);$csv=Identity-Map @(Import-Csv -LiteralPath (Join-Path $directory 'methods.csv'))
 if($map.Count -ne $Count -or $csv.Count -ne $Count -or @($report.classes).Count -ne $XmlCount -or @($report.issues).Count){throw 'BASELINE_OR_PREREQUISITE_REPORT_INCOMPLETE'}
 foreach($key in $map.Keys){
  if(-not $csv.ContainsKey($key)){throw 'BASELINE_CSV_XML_IDENTITY_DIFFERS'}
  foreach($field in @('record_kind','status','exception_type','cause_types','first_project_frame','message_sha256','detail_sha256')){if($map[$key].$field -cne $csv[$key].$field){throw 'BASELINE_CSV_XML_FACT_DIFFERS'}}
 }
 return $report
}
function Assert-Green30 {
 $g=$manifest.greenPrerequisite
 foreach($p in $g.pins){Assert-Pin $p.path $p.sha256}
 $dir=Join-Path $taskRoot $g.directory
 $summary=[IO.File]::ReadAllText((Join-Path $dir 'summary.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $admission=[IO.File]::ReadAllText((Join-Path $dir 'admission.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $nativeRecord=[IO.File]::ReadAllText((Join-Path $dir 'native-exit.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $exitRecord=[IO.File]::ReadAllText((Join-Path $dir 'collector-exit.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 if($summary.mode -cne 'GREEN_ONLY' -or $summary.records -ne 30 -or $summary.passed -ne 30 -or $summary.originalMethodsPassed -ne 25 -or $summary.newMethodsPassed -ne 5 -or $summary.recoveredExactRedFailures -ne 3 -or $summary.failures -ne 0 -or $summary.errors -ne 0 -or $summary.skipped -ne 0 -or $summary.failureIgnore -or -not $summary.gatePassed -or -not $summary.allGreen -or -not $summary.sourceStable -or -not $summary.contextStable -or -not $summary.classpathPassed -or $nativeRecord.nativeExit -ne 0 -or $nativeRecord.failureIgnore -or $exitRecord.collectorExit -ne 0 -or $admission.head -cne $manifest.expectedHead -or $admission.manifestSha256 -cne $g.manifestSha256 -or $admission.collectorSha256 -cne $g.runnerSha256){throw 'ACTUAL_GREEN30_NOT_QUALIFIED'}
 foreach($name in @('source-before.json','source-after.json')){$s=[IO.File]::ReadAllText((Join-Path $dir $name),[Text.Encoding]::UTF8)|ConvertFrom-Json;if(-not(Same-Sources $manifest.sources $s)){throw 'GREEN30_SOURCE_DIFFERS'}}
 $report=Read-PinnedReport $g.directory 30 3
 $greenMap=Identity-Map $report.rows
 $original=@($baseReport.rows|Where-Object{$manifest.targetClasses -ccontains $_.classname})
 if($original.Count -ne 25 -or @($report.rows|Where-Object{$_.status -cne 'passed'}).Count){throw 'GREEN30_ORIGINAL_OR_STATUS_DIFFERS'}
 $wanted=@($original)+@($manifest.newMethods)
 foreach($row in $wanted){if(-not $greenMap.ContainsKey((Get-Ex04Identity $row))){throw 'GREEN30_EXACT_IDENTITY_DIFFERS'}}
 $cp=Read-Classpaths (Join-Path $dir 'xml')
 if(-not(Exact-Classpaths $cp 3)){throw 'GREEN30_EXACT_CLASSPATH_CHANGED'}
 Write-Fact 'dependency-green30.json' ([ordered]@{directory=$g.directory;summarySha256=(Hash (Join-Path $dir 'summary.json'));manifestSha256=$g.manifestSha256;runnerSha256=$g.runnerSha256;sourceAfterSha256=(Hash (Join-Path $dir 'source-after.json'));actualRecords=30;actualPassed=30;nativeExit=0;collectorExit=0;same152Source=$true;exactClasspathPassed=$true})
}
function Assert-Adjacent88 {
 $a=$manifest.adjacentPrerequisite
 if(-not $a.sealed -or [string]::IsNullOrWhiteSpace($a.directory)){throw 'ACTUAL_ADJACENT88_UNSEALED'}
 foreach($p in $a.pins){Assert-Pin $p.path $p.sha256}
 $dir=Join-Path $taskRoot $a.directory
 $summary=[IO.File]::ReadAllText((Join-Path $dir 'summary.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $admission=[IO.File]::ReadAllText((Join-Path $dir 'admission.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $exitRecord=[IO.File]::ReadAllText((Join-Path $dir 'collector-exit.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 if($summary.mode -cne 'Adjacent' -or $summary.nativeExit -ne 0 -or $summary.failureIgnore -or $summary.records -ne 88 -or $summary.passed -ne 88 -or $summary.skipped -ne 0 -or -not $summary.regressionGatePassed -or -not $summary.allGreen -or $exitRecord.collectorExit -ne 0 -or $admission.manifestSha256 -cne $a.manifestSha256 -or $admission.collectorSha256 -cne (Hash $PSCommandPath) -or $admission.head -cne $head){throw 'ADJACENT88_NOT_GREEN_ON_SAME_STACK'}
 foreach($name in @('source-before.json','source-after.json')){$s=[IO.File]::ReadAllText((Join-Path $dir $name),[Text.Encoding]::UTF8)|ConvertFrom-Json;if(-not(Same-Sources $manifest.sources $s)){throw 'ADJACENT_SOURCE_DIFFERS'}}
 $report=Read-PinnedReport $a.directory 88 10;$map=Identity-Map $report.rows
 $wanted=@($expected.Values|Where-Object{$manifest.adjacentClasses -ccontains $_.classname})
 if($wanted.Count -ne 88 -or @($report.rows|Where-Object{$_.status -cne 'passed'}).Count -or @($wanted|Where-Object{-not $map.ContainsKey((Get-Ex04Identity $_))}).Count -or -not(Exact-Classpaths (Read-Classpaths (Join-Path $dir 'xml')) 10)){throw 'ADJACENT_EXACT_REPORT_OR_CLASSPATH_DIFFERS'}
 Write-Fact 'dependency-adjacent88.json' ([ordered]@{directory=$a.directory;summarySha256=(Hash (Join-Path $dir 'summary.json'));manifestSha256=$a.manifestSha256;runnerSha256=(Hash $PSCommandPath);sourceAfterSha256=(Hash (Join-Path $dir 'source-after.json'));records=88;nativeZeroNoIgnore=$true;same152Source=$true})
}
try{
 Set-Location -LiteralPath $taskRoot
 foreach($p in $manifest.pins){Assert-Pin $p.path $p.sha256}
 Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $taskRun 'approved-manifest.json')
 Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $taskRun 'collector.ps1')
 . (Join-Path $taskRoot $manifest.reportReader)
 $contextBefore=Check-Context;Write-Fact 'context-before.json' $contextBefore;$head=$contextBefore.head
 $baseline=Join-Path $taskRoot $manifest.baselineDirectory
 $baseReport=Read-PinnedReport $manifest.baselineDirectory 701 73
 $baseMap=Identity-Map $baseReport.rows
 $baseSummary=[IO.File]::ReadAllText((Join-Path $baseline 'summary.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 if(-not $baseSummary.regressionGatePassed -or $baseSummary.allGreen -or $baseSummary.records -ne 701 -or $baseSummary.passed -ne 585 -or $baseSummary.failures -ne 6 -or $baseSummary.errors -ne 106 -or $baseSummary.skipped -ne 4){throw 'BASELINE_FULL701_RESULT_CHANGED'}
 foreach($pair in @(@('passed',585),@('failure',6),@('error',106),@('skipped',4))){if(@($baseReport.rows|Where-Object{$_.status -ceq $pair[0]}).Count -ne $pair[1]){throw 'BASELINE_FULL701_XML_STATUS_CHANGED'}}
 if(@($baseReport.rows|Where-Object record_kind -ceq 'CLASS_EVENT').Count -ne 0){throw 'BASELINE_UNEXPECTED_CLASS_EVENT'}
 $adjacentBaseline=Read-PinnedReport $manifest.adjacentBaselineDirectory 83 10
 $oldAdjacentMap=Identity-Map $adjacentBaseline.rows
 if(@($adjacentBaseline.rows|Where-Object{$_.status -cne 'passed' -or $manifest.adjacentClasses -cnotcontains $_.classname}).Count){throw 'BASELINE_ADJACENT83_STATUS_OR_CLASS_CHANGED'}
 foreach($key in $oldAdjacentMap.Keys){if(-not $baseMap.ContainsKey($key) -or $baseMap[$key].status -cne 'passed'){throw 'BASELINE_ADJACENT_NOT_SUBSET_OF_FULL'}}
 $before=Sources;Write-Fact 'source-before.json' $before
 if(-not(Same-Sources $manifest.sources $before)){throw 'FROZEN_152_SOURCE_DRIFT'}
 $baseSources=[IO.File]::ReadAllText((Join-Path $baseline 'source-after.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $baseSourceMap=@{};foreach($s in $baseSources){if($baseSourceMap.ContainsKey($s.path)){throw 'DUPLICATE_BASELINE_SOURCE'};$baseSourceMap[$s.path]=$s.sha256}
 $delta=@($before|Where-Object{-not $baseSourceMap.ContainsKey($_.path) -or $baseSourceMap[$_.path] -cne $_.sha256}|Select-Object -ExpandProperty path)
 if($baseSourceMap.Count -ne 152 -or $delta.Count -ne 5 -or @($delta|Where-Object{$manifest.allowedSourceDelta -cnotcontains $_}).Count -ne 0 -or @($baseSourceMap.Keys|Where-Object{$before.path -cnotcontains $_}).Count){throw 'SOURCE_DELTA_NOT_EXACT_TWO_PRODUCT_THREE_TEST'}
 $expected=@{};foreach($key in $baseMap.Keys){$expected[$key]=$baseMap[$key]}
 foreach($row in $manifest.newMethods){$key=Get-Ex04Identity $row;if($expected.ContainsKey($key) -or $row.status -cne 'passed'){throw 'NEW_METHOD_NOT_UNIQUE_PASS'};$expected[$key]=$row}
 if($expected.Count -ne 706 -or @($manifest.newMethods).Count -ne 5){throw 'WRONG_FULL706_IDENTITY_SET'}
 Assert-Green30
 if($Mode -ceq 'Full'){Assert-Adjacent88}
 if($Mode -ceq 'Adjacent'){$subset=@{};foreach($key in $expected.Keys){if($manifest.adjacentClasses -ccontains $expected[$key].classname){$subset[$key]=$expected[$key]}};$expected=$subset}
 $expectedCount=if($Mode -ceq 'Adjacent'){88}else{706}
 $expectedXml=if($Mode -ceq 'Adjacent'){10}else{73}
 $expectedClasses=@($expected.Values|Select-Object -ExpandProperty classname -Unique)
 if($expected.Count -ne $expectedCount -or $expectedClasses.Count -ne $expectedXml){throw 'MODE_IDENTITY_OR_CLASS_SET_MISMATCH'}
 $notSelected=@($baseMap.Values|Where-Object{$expected.Keys -cnotcontains (Get-Ex04Identity $_)}|Select-Object classname,method,display_name,suite_path,record_kind,status)
 Write-Fact 'original-baseline-outside-selected-mode.json' $notSelected
 Write-Fact 'known-unexecuted-source.json' @()
 Write-Fact 'admission.json' ([ordered]@{mode=$Mode;head=$head;manifestSha256=(Hash $manifestPath);collectorSha256=(Hash $PSCommandPath);baseline=$manifest.baselineDirectory;baselineRecords=701;expectedRecords=$expectedCount;expectedXml=$expectedXml;sourceCount=152;sourceDelta=$delta;baselineClassEvents=0;knownUnexecutedFromClassEvents=0;allSourceMethodsDiscoveredClaimed=$false;exclusiveWindowExplicitlyGranted=$true;originalReplayDatabasesSelected=if($Mode -ceq 'Full'){@('agent_platform_skill_algorithm_migration_test','agent_platform_skill_digest_test')}else{@()};collectorDdl=$false;originalFull701RawReasonGatePreserved=$true;automaticNextMode=$false;rawReasonChangesAutomaticallyAccepted=$false})
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
 if(-not(Test-Path -LiteralPath $manifest.maven -PathType Leaf)){throw 'APPROVED_MAVEN_MISSING'}
 $pg=Join-Path $taskRoot '.tools/postgresql-16.15/pgsql/bin/psql.exe'
 Check-Storage 'before'
 $arguments=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true')
 $ignore=$Mode -ceq 'Full'
 if($ignore){$arguments+='-Dmaven.test.failure.ignore=true'}
 
 else{$arguments+='-Dmaven.test.failure.ignore=false';$arguments+=('-Dtest='+(@($manifest.adjacentClasses|ForEach-Object{($_ -split '\.')[-1]}) -join ','))}
 $phase='MAVEN';$started=[DateTime]::UtcNow
 Write-Fact 'command.json' ([ordered]@{command=$manifest.maven;arguments=$arguments;startedAt=$started.ToString('o');head=$head;envSha256=(Hash '.env');adminJarSha256=(Hash $jar);jdk=$env:JAVA_HOME;failureIgnore=$ignore;defaultForkReuseAndOrder=$true})
 try{$ErrorActionPreference='Continue';& $manifest.maven @arguments *> (Join-Path $taskRun 'raw.log');$native=$LASTEXITCODE}
 finally{$ErrorActionPreference='Stop';Write-Fact 'native-exit.json' ([ordered]@{nativeExit=$native;startedAt=$started.ToString('o');endedAt=[DateTime]::UtcNow.ToString('o');failureIgnore=$ignore;nativeZeroIsNotFullGreen=$ignore})}
 $phase='ARCHIVE_AND_COMPARE'
 $after=Sources;Write-Fact 'source-after.json' $after;$sourceStable=Same-Sources $manifest.sources $after
 $contextAfter=Check-Context;Write-Fact 'context-after.json' $contextAfter;$endHead=$contextAfter.head;$contextStable=$true
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
 $classpathRows=Read-Classpaths $xml;Write-Fact 'classpath-facts.json' $classpathRows;$classpathOkay=Exact-Classpaths $classpathRows $expectedXml
 $classEvents=@($rows|Where-Object record_kind -ceq 'CLASS_EVENT');Write-Fact 'class-level-events.json' $classEvents;$eventsPreserved=$classEvents.Count -eq 0
 $missing=@($expected.Keys|Where-Object{$actual.Keys -cnotcontains $_});$unexpected=@($actual.Keys|Where-Object{$expected.Keys -cnotcontains $_})
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
  [pscustomobject]@{identity=$key;beforeStatus=$old.status;afterStatus=if($current){$current.status}else{'MISSING'};structureSame=$structural;messageSame=$messageSame;detailSame=$detailSame;beforeMessageSha=$old.message_sha256;afterMessageSha=if($current){$current.message_sha256}else{''};beforeDetailSha=$old.detail_sha256;afterDetailSha=if($current){$current.detail_sha256}else{''};boundedVolatileExplanation=$bounded;reasonPreserved=($structural -and $messageSame -and $detailSame)}
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
 $ownershipLines=New-Object 'System.Collections.Generic.List[string]'
 if($Mode -ceq 'Full'){
  $ownershipFile=Join-Path $xml 'TEST-cn.agentplatform.skill.infra.AlgorithmOffloadResponseOwnershipTest.xml'
  if(Test-Path -LiteralPath $ownershipFile){
   $ownershipDoc=New-Object Xml.XmlDocument;$ownershipDoc.XmlResolver=$null;$ownershipDoc.Load($ownershipFile)
   foreach($output in $ownershipDoc.SelectNodes("//*[local-name()='system-out']")){
    foreach($line in ($output.InnerText -split '\r?\n')){if($line.StartsWith('ALGORITHM_RESPONSE_OWNERSHIP')){$ownershipLines.Add($line)}}
   }
  }
 }
 Write-Fact 'ownership-witness.json' ($ownershipLines.ToArray())
 $wantedWitness='ALGORITHM_RESPONSE_OWNERSHIP awaitInterrupted=true originalFutureSuccess=true sameResponse=true stoppingBeforeCallerClose=false stages=0 calls=0 retainedOriginalCall=false'
 $ownershipOkay=if($Mode -ceq 'Adjacent'){$ownershipLines.Count -eq 0}else{$ownershipLines.Count -eq 1 -and $ownershipLines[0] -ceq $wantedWitness}
 $sdkOkay=$sdkOkay -and $report.diagnosticCount -eq 0
 $phase='POST_STORAGE';Check-Storage 'after'
 $oldNonpass=if($Mode -ceq 'Full'){116}else{0}
 $reasonGate=$reasons.Count -eq $oldNonpass -and @($reasons|Where-Object{-not $_.reasonPreserved}).Count -eq 0
 $rawReasonGate=$reasons.Count -eq $oldNonpass -and @($reasons|Where-Object{-not $_.structureSame -or -not $_.messageSame -or -not $_.detailSame}).Count -eq 0
 $classSetSame=@($expectedClasses|Where-Object{$report.classes -cnotcontains $_}).Count -eq 0 -and @($report.classes|Where-Object{$expectedClasses -cnotcontains $_}).Count -eq 0
 $complete=$rows.Count -eq $expectedCount -and $fresh.Count -eq $expectedXml -and @($report.classes).Count -eq $expectedXml -and $classSetSame -and $missing.Count -eq 0 -and $unexpected.Count -eq 0 -and @($report.issues).Count -eq 0
 $newSkipped=@($manifest.newMethods|Where-Object{$actual.ContainsKey((Get-Ex04Identity $_)) -and $actual[(Get-Ex04Identity $_)].status -ceq 'skipped'}).Count
 $expectedSkips=if($Mode -ceq 'Full'){4}else{0};$skipGate=@($rows|Where-Object{$_.status -ceq 'skipped'}).Count -eq $expectedSkips -and $newSkipped -eq 0
 $gate=$native -eq 0 -and $complete -and $statusChanges.Count -eq 0 -and $reasonGate -and $sourceStable -and $contextStable -and $sdkOkay -and $ownershipOkay -and $skipGate -and $classpathOkay -and $eventsPreserved
 $summary=[ordered]@{mode=$Mode;nativeExit=$native;failureIgnore=$ignore;baselineRecords=701;expectedRecords=$expectedCount;records=$rows.Count;expectedXml=$expectedXml;actualXml=$fresh.Count;passed=@($rows|Where-Object{$_.status -ceq 'passed'}).Count;failures=@($rows|Where-Object{$_.status -ceq 'failure'}).Count;errors=@($rows|Where-Object{$_.status -ceq 'error'}).Count;skipped=@($rows|Where-Object{$_.status -ceq 'skipped'}).Count;missing=$missing;unexpected=$unexpected;statusChanges=$statusChanges.Count;baselineNonpass=$oldNonpass;baselineSkipped=$expectedSkips;newSkipped=$newSkipped;skipGatePassed=$skipGate;unexplainedReasons=@($reasons|Where-Object{-not $_.reasonPreserved}).Count;rawReasonComparisonPassed=$rawReasonGate;boundedVolatileAccepted=0;boundedVolatileExplanationCandidates=@($reasons|Where-Object{$_.boundedVolatileExplanation}).Count;rawReasonChangesAutomaticallyAccepted=$false;reasonComparisonPassed=$reasonGate;sdkDefaultPassed=$sdkOkay;ownershipWitnessPassed=$ownershipOkay;diagnosticCount=$report.diagnosticCount;sourceCount=152;originalFull701RawGatePreserved=$true;classpathPassed=$classpathOkay;classEventsPreserved=$eventsPreserved;knownUnexecutedFromClassEvents=0;allSourceMethodsDiscoveredClaimed=$false;sourceStable=$sourceStable;contextStable=$contextStable;reportsComplete=$complete;regressionGatePassed=$gate;allGreen=($gate -and @($rows|Where-Object{$_.status -cne 'passed'}).Count -eq 0);nativeZeroIsNotFullGreen=$ignore;collectorDdl=$false;cardClosed=$false}
 Write-Fact 'summary.json' $summary;$summary|ConvertTo-Json -Depth 5
 $collectorExit=if($gate){0}else{1}
}catch{
 $safeReason=if($_.Exception.Message -cmatch '^[A-Z_0-9]+$'){$_.Exception.Message}else{'UNEXPECTED_COLLECTOR_ERROR'}
 Write-Fact 'collector-error.json' ([ordered]@{phase=$phase;reason=$safeReason;exceptionClass=$_.Exception.GetType().FullName;scriptLine=$_.InvocationInfo.ScriptLineNumber;nativeExit=$native;collectorExit=2;rawEvidencePreserved=$true})
 Write-Output ('EX15B_PHASE_A_REGRESSION_STOPPED phase='+$phase+' line='+$_.InvocationInfo.ScriptLineNumber)
}finally{
 foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')}
 Set-Location -LiteralPath $location
 Write-Fact 'collector-exit.json' ([ordered]@{collectorExit=$collectorExit;nativeExit=$native;sourceAndIndexMutated=$false;rawEvidencePreserved=$true})
}
exit $collectorExit
