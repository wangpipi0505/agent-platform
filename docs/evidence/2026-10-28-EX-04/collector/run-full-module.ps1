param(
 [Parameter(Mandatory=$true)][ValidateSet('skill-registry','engine-runtime')][string]$Module,
 [Parameter(Mandatory=$true)][string]$FreezePath,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId
)
$ErrorActionPreference='Stop';$PSNativeCommandUseErrorActionPreference=$false
$taskRoot='G:/207/项目/算法重组工具/代码/agent-platform'
$taskRun=$null;$taskPrevious=@{};$taskLocation=Get-Location
$taskPhase='FREEZE_ADMISSION';$taskReason='ROOT_FULL_FREEZE_NOT_APPROVED';$taskCollectorExit=2
$skill=($Module -ceq 'skill-registry');$phase=if($skill){'FULL_SKILL'}else{'FULL_ENGINE'}
$baseDirectory=if($skill){'docs/evidence/2026-10-28-EX-27/runs/outbox-fix-20261029-085643-361/full'}else{'docs/evidence/2026-10-28-EX-36/runs/after-module-20261028-230322-842'}
$baseCsv=if($skill){Join-Path $baseDirectory 'methods.csv'}else{Join-Path $baseDirectory 'method-summary/methods.csv'}
function Write-TaskJson([string]$Name,$Value){[IO.File]::WriteAllText((Join-Path $taskRun $Name),(ConvertTo-Json -InputObject $Value -Depth 12),(New-Object Text.UTF8Encoding($false)))}
function Identity($Row){$Row.classname+'|'+$Row.method+'|'+$Row.display_name+'|'+$Row.suite_path}
function Load-PinnedFile([string]$Path,[string]$Sha){
 if($Sha -cnotmatch '^[A-Fa-f0-9]{64}$' -or -not (Test-Path -LiteralPath $Path -PathType Leaf) -or (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -cne $Sha.ToUpperInvariant()){throw 'FROZEN_INPUT_MISMATCH'}
 [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path).Path,[Text.Encoding]::UTF8)
}
function Current-Sources{
 @(Get-ChildItem -LiteralPath (Join-Path $taskRoot ($Module+'/src')) -Recurse -File|Sort-Object FullName|ForEach-Object{[pscustomobject]@{path=$_.FullName.Substring($taskRoot.Length+1).Replace([char]92,[char]47);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}})
}
function Assert-Sources($Expected,$Actual){
 $map=@{};foreach($row in $Expected){if($row.path -cnotmatch ('^'+[regex]::Escape($Module)+'/src/') -or $row.path -match '(^|/)\.\.(/|$)' -or $row.sha256 -cnotmatch '^[A-Fa-f0-9]{64}$' -or $map.ContainsKey($row.path)){throw 'INVALID_SOURCE_MANIFEST'};$map[$row.path]=$row.sha256.ToUpperInvariant()}
 if($map.Count -eq 0 -or $map.Count -ne @($Actual).Count -or @($Actual|Where-Object{-not $map.ContainsKey($_.path) -or $map[$_.path] -cne $_.sha256}).Count -ne 0){throw 'FROZEN_SOURCE_DRIFT'}
}
try{
 Set-Location -LiteralPath $taskRoot
 $freeze=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $FreezePath).Path,[Text.Encoding]::UTF8)|ConvertFrom-Json
 if(-not $freeze.approvedForExclusiveActualWindow -or $freeze.phase -cne $phase -or $freeze.module -cne $Module -or $freeze.head -cnotmatch '^[a-fA-F0-9]{40}$' -or $freeze.envSha256 -cnotmatch '^[a-fA-F0-9]{64}$' -or $freeze.adminCommonJarSha256 -cnotmatch '^[a-fA-F0-9]{64}$' -or -not $freeze.candidateReview -or $freeze.candidateReview -like 'PENDING*' -or -not $freeze.rootGrant -or $freeze.rootGrant -like 'PENDING*' -or -not $freeze.exclusiveWindowId){throw 'ROOT_FULL_FREEZE_NOT_APPROVED'}
 $source=Load-PinnedFile $freeze.moduleSourceManifest $freeze.moduleSourceManifestSha256|ConvertFrom-Json
 [void](Load-PinnedFile $freeze.expectedMethodsCsv $freeze.expectedMethodsCsvSha256)
 [void](Load-PinnedFile $freeze.expectedNewMethodsCsv $freeze.expectedNewMethodsCsvSha256)
 [void](Load-PinnedFile $freeze.directedMethodsCsv $freeze.directedMethodsCsvSha256)
 [void](Load-PinnedFile (Join-Path $PSScriptRoot 'full-report-records.ps1') $freeze.reportReaderSha256)
 if($skill){[void](Load-PinnedFile (Join-Path $PSScriptRoot 'extract-projection-evidence.ps1') $freeze.evidenceExtractorSha256)}
 $expected=@(Import-Csv -LiteralPath $freeze.expectedMethodsCsv);$new=@(Import-Csv -LiteralPath $freeze.expectedNewMethodsCsv);$base=@(Import-Csv -LiteralPath $baseCsv);$directed=@(Import-Csv -LiteralPath $freeze.directedMethodsCsv)
 $baseMap=@{};foreach($row in $base){$key=Identity $row;if($baseMap.ContainsKey($key)){throw 'DUPLICATE_BASELINE_IDENTITY'};$baseMap[$key]=$row}
 $union=@{};foreach($key in $baseMap.Keys){$union[$key]=$baseMap[$key]}
 foreach($row in $new){$key=Identity $row;if(-not $row.method -or $union.ContainsKey($key)){throw 'NEW_IDENTITY_RENAMES_OR_DUPLICATES_BASELINE'};$union[$key]=$row}
 $expectedMap=@{};foreach($row in $expected){$key=Identity $row;if($expectedMap.ContainsKey($key) -or -not $union.ContainsKey($key)){throw 'INVALID_FROZEN_FULL_IDENTITIES'};$expectedMap[$key]=$row}
 if($new.Count -eq 0 -or $expectedMap.Count -ne $union.Count -or @($union.Keys|Where-Object{-not $expectedMap.ContainsKey($_)}).Count -ne 0 -or $directed.Count -eq 0 -or @($directed|Where-Object{-not $expectedMap.ContainsKey((Identity $_))}).Count -ne 0){throw 'FULL_EXPECTED_SET_NOT_BASELINE_PLUS_APPROVED_NEW'}
 $classes=@($expected|Select-Object -ExpandProperty classname -Unique)
 $before=Current-Sources;Assert-Sources $source $before
 if(((& git rev-parse HEAD)-join '') -cne $freeze.head){throw 'FROZEN_HEAD_MISMATCH'}
 $greenDirectory=(Resolve-Path -LiteralPath $freeze.greenPrerequisiteRunDirectory).Path
 $ownedRuns=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runs'))+[IO.Path]::DirectorySeparatorChar
 if(-not $greenDirectory.StartsWith($ownedRuns,[StringComparison]::OrdinalIgnoreCase)){throw 'GREEN_PREREQUISITE_SCOPE_MISMATCH'}
 $green=Load-PinnedFile (Join-Path $greenDirectory 'summary.json') $freeze.greenPrerequisiteSummarySha256|ConvertFrom-Json
 $greenNative=[IO.File]::ReadAllText((Join-Path $greenDirectory 'native-exit.json'))|ConvertFrom-Json
 $greenEnd=[IO.File]::ReadAllText((Join-Path $greenDirectory 'command-end.json'))|ConvertFrom-Json
 $greenSources=[IO.File]::ReadAllText((Join-Path $greenDirectory 'source-after.json'))|ConvertFrom-Json
 Assert-Sources $source $greenSources
 if(-not $green.regressionGatePassed -or -not $green.allGreen -or -not $green.sourceFrozen -or $green.nativeExit -ne 0 -or $green.failureIgnore -or $greenNative.nativeExit -ne 0 -or $greenNative.failureIgnore -or $green.actualReports -ne $directed.Count -or $green.passed -ne $directed.Count -or $green.skipped -ne 0 -or $greenEnd.head -cne $freeze.head -or $greenEnd.envSha256 -cne $freeze.envSha256.ToUpperInvariant() -or $greenEnd.jarSha256 -cne $freeze.adminCommonJarSha256.ToUpperInvariant()){throw 'DIRECTED_GREEN_OR_SAME_FROZEN_STACK_MISSING'}
 $greenRows=@(Import-Csv -LiteralPath (Join-Path $greenDirectory 'methods.csv'));$greenKeys=@($greenRows|ForEach-Object{Identity $_})
 if($greenRows.Count -ne $directed.Count -or @($directed|Where-Object{$greenKeys -cnotcontains (Identity $_)}).Count -ne 0 -or @($greenRows|Where-Object{$_.status -cne 'passed'}).Count -ne 0){throw 'DIRECTED_GREEN_IDENTITY_MISMATCH'}
 $admission=Load-PinnedFile $freeze.pgAdmissionFile $freeze.pgAdmissionSha256|ConvertFrom-Json
 $age=([DateTimeOffset]::Now-[DateTimeOffset]::Parse($admission.checkedAt)).TotalMinutes
 if(-not $admission.passed -or $admission.evidenceKind -cne 'ACTUAL_PG_READONLY_PREFLIGHT' -or $admission.module -cne $Module -or $admission.head -cne $freeze.head -or $admission.envSha256 -cne $freeze.envSha256.ToUpperInvariant() -or $admission.moduleSourceManifestSha256 -cne $freeze.moduleSourceManifestSha256.ToUpperInvariant() -or $admission.exclusiveWindowId -cne $freeze.exclusiveWindowId -or $admission.pid -ne 15204 -or $admission.port -ne 25432 -or -not $admission.knownDataDirectory -or -not $admission.roleAgentV12 -or -not $admission.knownScopeOnly -or $admission.unknownDatabaseCount -ne 0 -or -not $admission.noCreateResetOrQueueCleanup -or -not $admission.readOnly -or -not $admission.originalReplayWhitelistReviewed -or $age -lt 0 -or $age -gt 15){throw 'SAME_WINDOW_STRICT_PG_ADMISSION_MISSING'}
 $proofs=@($admission.databaseProofs)
 if($proofs.Count -eq 0 -or @($proofs|Where-Object{$_.database -cnotmatch '^agent_platform_[a-z0-9_]+$' -or ($_.existed -and ($_.owner -cne 'agent_v12' -or -not $_.ownershipVerified)) -or (-not $_.existed -and (-not $_.knownAbsentBaseline -or -not $_.originalBehaviorDoesNotCreate))}).Count -ne 0){throw 'OWNED_PRIVATE_DATABASE_SCOPE_NOT_PROVEN'}
 if($skill){
  foreach($name in @('agent_platform_v12','agent_platform_skill_algorithm_test','agent_platform_skill_algorithm_migration_test','agent_platform_skill_digest_test','agent_platform_ontology_import_test')){if(@($proofs|Where-Object{$_.database -ceq $name -and $_.existed -and $_.ownershipVerified}).Count -ne 1){throw 'ORIGINAL_SKILL_DATABASE_SCOPE_INCOMPLETE'}}
  foreach($name in @('agent_platform_skill_algorithm_migration_test','agent_platform_skill_digest_test')){if(@($admission.approvedReplayDatabases|Where-Object{$_ -ceq $name}).Count -ne 1){throw 'ORIGINAL_REPLAY_AUTHORIZATION_MISSING'}}
 }
 $taskRun=Join-Path $PSScriptRoot ('runs/'+$RunId);if(Test-Path -LiteralPath $taskRun){throw 'RUN_ID_ALREADY_EXISTS'};New-Item -ItemType Directory -Path $taskRun|Out-Null
 Write-TaskJson 'freeze-admission.json' ([ordered]@{phase=$phase;module=$Module;head=$freeze.head;freezeSha256=(Get-FileHash -LiteralPath $FreezePath -Algorithm SHA256).Hash;sourceManifestSha256=$freeze.moduleSourceManifestSha256;expectedMethodsSha256=$freeze.expectedMethodsCsvSha256;newMethodsSha256=$freeze.expectedNewMethodsCsvSha256;baseline=$baseDirectory;baselineRecords=$base.Count;approvedNewRecords=$new.Count;expectedRecords=$expected.Count;expectedClasses=$classes.Count;directedGate=$true;sameFrozenStack=$true;pgAdmissionSha256=$freeze.pgAdmissionSha256;pgAdmissionAgeMinutes=$age;collectorDdl=$false;originalReplayScope=$admission.approvedReplayDatabases})
 Write-TaskJson 'source-before.json' $before
 $taskPhase='ENVIRONMENT';$taskReason='FROZEN_RUNTIME_CONTEXT_MISMATCH'
 if((Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash -cne $freeze.envSha256.ToUpperInvariant()){throw 'FROZEN_RUNTIME_CONTEXT_MISMATCH'}
 $values=@{};foreach($line in [IO.File]::ReadAllLines((Join-Path $taskRoot '.env'),[Text.Encoding]::UTF8)){if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
 foreach($n in (@($values.Keys)+@('JAVA_HOME','PATH','ENGINE_PG_TEST')|Select-Object -Unique)){$taskPrevious[$n]=[Environment]::GetEnvironmentVariable($n,'Process')}
 foreach($n in $values.Keys){[Environment]::SetEnvironmentVariable($n,$values[$n],'Process')}
 $env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot';$env:PATH="$env:JAVA_HOME/bin;$env:PATH";$env:ENGINE_PG_TEST='true'
 $jar=Join-Path $taskRoot '.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar'
 if(-not $env:AGENT_DB_PASSWORD -or $env:AGENT_DEV_AUTH -cne 'true' -or -not [string]::IsNullOrEmpty($env:AGENT_SECURITY_MODE) -or (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -cne $freeze.adminCommonJarSha256.ToUpperInvariant()){throw 'FROZEN_RUNTIME_CONTEXT_MISMATCH'}
 $taskPhase='CURRENT_PG_INSTANCE';$taskReason='SAME_WINDOW_PG_LISTENER_CHANGED'
 $listen=@(Get-NetTCPConnection -LocalPort 25432 -State Listen);if($listen.Count -ne 1 -or $listen[0].LocalAddress -cne '127.0.0.1' -or $listen[0].OwningProcess -ne 15204){throw 'SAME_WINDOW_PG_LISTENER_CHANGED'}
 $process=Get-CimInstance Win32_Process -Filter 'ProcessId=15204';if($process.ExecutablePath.Replace([char]92,[char]47) -cne 'V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe' -or $process.CommandLine -notlike '*V:/.local/pgdata*'){throw 'SAME_WINDOW_PG_LISTENER_CHANGED'}
 Write-TaskJson 'environment.json' ([ordered]@{envSha256=$freeze.envSha256;jarSha256=$freeze.adminCommonJarSha256;jdk=$env:JAVA_HOME;developmentFlag=$true;postgresFlag=$true;diagnosticFlagsPassed=$false;defaultForkReuseOrder=$true;secretValuesPrinted=$false})
 $taskPhase='MAVEN_FULL';$taskReason='NATIVE_OR_COMPILE_RESULT_REQUIRES_REVIEW'
 $taskArgs=@('-B','-ntp','-pl',$Module,'test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=true')
 $started=Get-Date;$native=$null;Write-TaskJson 'command.json' ([ordered]@{command='mvn.cmd';arguments=$taskArgs;startedAt=$started.ToString('o');state='STARTED';failureIgnore=$true})
 try{$priorPreference=$ErrorActionPreference;$ErrorActionPreference='Continue';& mvn.cmd @taskArgs *> (Join-Path $taskRun 'raw.log');$native=$LASTEXITCODE}
 finally{$ErrorActionPreference=$priorPreference;$ended=Get-Date;Write-TaskJson 'native-exit.json' ([ordered]@{nativeExit=$native;startedAt=$started.ToString('o');endedAt=$ended.ToString('o');failureIgnore=$true;collectorExitSeparate=$true})}
 Write-TaskJson 'command.json' ([ordered]@{command='mvn.cmd';arguments=$taskArgs;startedAt=$started.ToString('o');endedAt=$ended.ToString('o');nativeExit=$native;state='COMMAND_RETURNED';failureIgnore=$true})
 Write-TaskJson 'command-end.json' ([ordered]@{head=((& git rev-parse HEAD)-join '');envSha256=(Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash;jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash;nativeAlreadyPersisted=$true;beforeXml=$true})
 $after=Current-Sources;Write-TaskJson 'source-after.json' $after;Assert-Sources $source $after
 if(((& git rev-parse HEAD)-join '') -cne $freeze.head -or (Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash -cne $freeze.envSha256.ToUpperInvariant() -or (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -cne $freeze.adminCommonJarSha256.ToUpperInvariant()){throw 'POST_NATIVE_FROZEN_CONTEXT_DRIFT'}
 $taskPhase='XML_COLLECTION';$taskReason='FRESH_XML_OR_IDENTITY_MISMATCH'
 $xml=Join-Path $taskRun 'xml';New-Item -ItemType Directory -Path $xml|Out-Null
 $fresh=@(Get-ChildItem -LiteralPath (Join-Path $taskRoot ($Module+'/target/surefire-reports')) -File -Filter 'TEST-*.xml'|Where-Object{$_.LastWriteTime -ge $started});foreach($file in $fresh){Copy-Item -LiteralPath $file.FullName -Destination $xml}
 Write-TaskJson 'xml-manifest.json' @($fresh|ForEach-Object{[pscustomobject]@{file=$_.Name;bytes=$_.Length;mtime=$_.LastWriteTime.ToString('o');sha256=(Get-FileHash -LiteralPath (Join-Path $xml $_.Name) -Algorithm SHA256).Hash}})
 [void](Load-PinnedFile (Join-Path $PSScriptRoot 'full-report-records.ps1') $freeze.reportReaderSha256);. (Join-Path $PSScriptRoot 'full-report-records.ps1')
 $report=Read-Ex04FullReports $xml;$rows=@($report.rows);$actualMap=@{};foreach($row in $rows){$actualMap[(Identity $row)]=$row}
 $rows|Export-Csv -LiteralPath (Join-Path $taskRun 'methods.csv') -NoTypeInformation -Encoding UTF8
 Write-TaskJson 'xml-issues.json' @($report.issues)
 $missing=@($expectedMap.Keys|Where-Object{-not $actualMap.ContainsKey($_)});$unexpected=@($actualMap.Keys|Where-Object{-not $expectedMap.ContainsKey($_)})
 $changed=@(foreach($key in $baseMap.Keys){if($actualMap.ContainsKey($key) -and $actualMap[$key].status -cne $baseMap[$key].status){[pscustomobject]@{identity=$key;before=$baseMap[$key].status;after=$actualMap[$key].status}}})
 $newNonpass=@($rows|Where-Object{$_.status -cne 'passed' -and (-not $baseMap.ContainsKey((Identity $_)) -or $baseMap[(Identity $_)].status -cne $_.status)})
 $classEvents=@($rows|Where-Object{-not $_.method});$baselineEvents=@($base|Where-Object{-not $_.method})
 $sourceUnexecuted=@();if(-not $skill){$known=@(Import-Csv -LiteralPath (Join-Path $PSScriptRoot 'engine-baseline-unexecuted-source.csv'));$sourceUnexecuted=@($known|Where-Object{$knownRow=$_;@($rows|Where-Object{$_.classname -ceq $knownRow.classname -and ($_.method -ceq $knownRow.method -or $_.method.StartsWith($knownRow.method+'('))}).Count -eq 0});if($sourceUnexecuted.Count -ne $known.Count){throw 'ORIGINAL_CLASS_EVENT_SOURCE_EXECUTION_CHANGED'}}
 Write-TaskJson 'class-level-events.json' $classEvents;Write-TaskJson 'known-unexecuted-source.json' $sourceUnexecuted
 Write-TaskJson 'method-status-delta.json' $changed;Write-TaskJson 'new-nonpass.json' $newNonpass
 $reported=@($report.classes);$complete=$rows.Count -eq $expected.Count -and $fresh.Count -eq $classes.Count -and @($classes|Where-Object{$reported -cnotcontains $_}).Count -eq 0 -and @($reported|Where-Object{$classes -cnotcontains $_}).Count -eq 0 -and $missing.Count -eq 0 -and $unexpected.Count -eq 0 -and @($report.issues).Count -eq 0
 $sdkOkay=$true
 if($skill){
  $sdkClass='cn.agentplatform.skill.infra.AlgorithmOffloadInterruptionTest';$sdk=@($rows|Where-Object{$_.classname -ceq $sdkClass})
  $sdkNames=@('interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[1]','interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[2]','interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[3]','unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit','originalGetBodyHandoffPreservesConfirmedOrExplicitlyUnconfirmedStopSafety')
  $keys=@($report.outcomes|ForEach-Object{$_.stage+'|'+$_.handoff});$wanted=@('PUT|False','GET|False','GET_BODY|False','GET_BODY|True')
  $sdkOkay=$sdk.Count -eq 5 -and @($sdk|Where-Object{$_.status -cne 'passed' -or $sdkNames -cnotcontains $_.method}).Count -eq 0 -and @($sdkNames|Where-Object{$n=$_;@($sdk|Where-Object{$_.method -ceq $n}).Count -ne 1}).Count -eq 0 -and @($report.outcomes).Count -eq 4 -and @($wanted|Where-Object{$n=$_;@($keys|Where-Object{$_ -ceq $n}).Count -ne 1}).Count -eq 0 -and @($report.outcomes|Where-Object{-not $_.interrupted -or $_.inputClosed -lt 1 -or (-not $_.handoff -and -not $_.firstQuiescent) -or ($_.handoff -and -not $_.explicitUnconfirmed -and -not $_.firstQuiescent)}).Count -eq 0 -and $report.diagnosticCount -eq 0
  Write-TaskJson 'sdk-default-outcomes.json' @($report.outcomes)
 }
 $statusGate=$complete -and $native -eq 0 -and $changed.Count -eq 0 -and $newNonpass.Count -eq 0 -and $classEvents.Count -eq $baselineEvents.Count -and $sdkOkay
 $projection=$true;if($skill -and $statusGate){$taskPhase='PROJECTION_EVIDENCE';$taskReason='ACTUAL_XML_PROJECTION_OR_BATCH_PREFIX_MISMATCH';[void](Load-PinnedFile (Join-Path $PSScriptRoot 'extract-projection-evidence.ps1') $freeze.evidenceExtractorSha256);. (Join-Path $PSScriptRoot 'extract-projection-evidence.ps1');$projection=[bool](Export-Ex04ProjectionEvidence -RunDirectory $taskRun).passed}elseif($skill){$projection=$false}
 $gate=$statusGate -and $projection;$allGreen=$complete -and $native -eq 0 -and @($rows|Where-Object{$_.status -cne 'passed'}).Count -eq 0
 Write-TaskJson 'summary.json' ([ordered]@{phase=$phase;module=$Module;nativeExit=$native;failureIgnore=$true;baselineRecords=$base.Count;approvedNewRecords=$new.Count;expectedRecords=$expected.Count;records=$rows.Count;namedRecords=@($rows|Where-Object{$_.method}).Count;classLevelEvents=$classEvents.Count;knownUnexecutedSourceTests=$sourceUnexecuted.Count;reportedXml=$fresh.Count;expectedClasses=$classes.Count;passed=@($rows|Where-Object{$_.status -ceq 'passed'}).Count;failure=@($rows|Where-Object{$_.status -ceq 'failure'}).Count;error=@($rows|Where-Object{$_.status -ceq 'error'}).Count;skipped=@($rows|Where-Object{$_.status -ceq 'skipped'}).Count;missing=$missing;unexpected=$unexpected;newNonpass=$newNonpass.Count;statusChanges=$changed.Count;reportsCollected=$complete;allSourceMethodsExecuted=($complete -and $sourceUnexecuted.Count -eq 0 -and @($rows|Where-Object{$_.status -ceq 'skipped'}).Count -eq 0);sdkDefaultProtocolPassed=$sdkOkay;actualProjectionEvidencePassed=$projection;sourceFrozen=$true;methodRegressionGatePassed=$gate;reasonComparisonPending=$true;allGreen=$allGreen;ignoreNative0IsNotAllGreen=$true;collectorDdl=$false})
 $taskCollectorExit=if($gate){0}else{1};Write-Output ('EX04_FULL_ARCHIVE='+$taskRun+' module='+$Module+' native='+$native+' collector='+$taskCollectorExit+' records='+$rows.Count+' method_gate='+$gate+' reason_pending=True all_green='+$allGreen)
}catch{
 if($taskRun -and (Test-Path -LiteralPath $taskRun)){Write-TaskJson 'collector-error.json' ([ordered]@{phase=$taskPhase;reason=$taskReason;scriptLineNumber=$_.InvocationInfo.ScriptLineNumber;exceptionClass=$_.Exception.GetType().FullName;messagePrinted=$false;nativePersisted=(Test-Path -LiteralPath (Join-Path $taskRun 'native-exit.json'))})}
 Write-Output ('EX04_FULL_STOP phase='+$taskPhase+' reason='+$taskReason+' line='+$_.InvocationInfo.ScriptLineNumber)
}finally{foreach($n in $taskPrevious.Keys){[Environment]::SetEnvironmentVariable($n,$taskPrevious[$n],'Process')};Set-Location -LiteralPath $taskLocation}
exit $taskCollectorExit
