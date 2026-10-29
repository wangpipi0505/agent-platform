param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9][a-zA-Z0-9-]{0,95}$')][string]$RunId,
 [Parameter(Mandatory=$true)][string]$CandidatePath,
 [switch]$RuntimeWindowGranted,
 [string]$ApRoot=(Join-Path $PSScriptRoot '../../../..')
)
$ErrorActionPreference='Stop'
if(-not $RuntimeWindowGranted){throw 'EX15A_GATEWAY_RUNTIME_WINDOW_REQUIRED'}
$ApRoot=(Resolve-Path -LiteralPath $ApRoot).Path.TrimEnd('\','/')
function Own-Path([string]$Path){
 if(-not [IO.Path]::IsPathRooted($Path)){$Path=Join-Path $ApRoot $Path}
 $full=[IO.Path]::GetFullPath($Path)
 if(-not $full.StartsWith($ApRoot+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'PATH_OUTSIDE_AP'}
 return $full
}
function Read-Json([string]$Path){Get-Content -LiteralPath $Path -Raw -Encoding UTF8|ConvertFrom-Json}
function Sha([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Text-Sha([string]$Value){
 $hash=[Security.Cryptography.SHA256]::Create()
 try{[BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value))).Replace('-','').ToLowerInvariant()}finally{$hash.Dispose()}
}
$candidateFile=Own-Path $CandidatePath
$candidate=Read-Json $candidateFile
if($candidate.approved -ne $true){throw 'CANDIDATE_NOT_APPROVED'}
foreach($key in @('baselineDirectory','greenXml','greenSummary','greenSources','expectedFingerprints','pgAdmission','addedClass','addedSource','javaHome','unixTmpDir')){
 if([string]::IsNullOrWhiteSpace([string]$candidate.$key)){throw "CANDIDATE_FIELD_REQUIRED_$key"}
}
$runDir=Join-Path $PSScriptRoot ('runs/'+$RunId)
$rawDir=Own-Path ('.local/ex15a-gateway-module/'+$RunId)
if((Test-Path -LiteralPath $runDir)-or(Test-Path -LiteralPath $rawDir)){throw 'RUN_DIRECTORY_EXISTS'}
New-Item -ItemType Directory -Path $runDir,$rawDir|Out-Null
function Fact([string]$Name,$Value){[IO.File]::WriteAllText((Join-Path $runDir $Name),(ConvertTo-Json -InputObject $Value -Depth 18),(New-Object Text.UTF8Encoding($false)))}
function Raw-Fact([string]$Name,$Value){[IO.File]::WriteAllText((Join-Path $rawDir $Name),(ConvertTo-Json -InputObject $Value -Depth 18),(New-Object Text.UTF8Encoding($false)))}
function Fingerprints {
 $paths=@(foreach($part in @('admin-common/src','platform-gateway/src')){
  Get-ChildItem -LiteralPath (Join-Path $ApRoot $part) -Recurse -File|ForEach-Object{$_.FullName.Substring($ApRoot.Length+1).Replace('\','/')}
 })
 $paths+=@('pom.xml','admin-common/pom.xml','platform-gateway/pom.xml','.env',
  '.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar',
  '.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT-tests.jar')
 $paths+=@($candidate.extraFingerprintPaths|Where-Object{$_})
 @(foreach($path in @($paths|Sort-Object -Unique)){
  $full=Own-Path $path;$exists=Test-Path -LiteralPath $full -PathType Leaf
  [pscustomobject]@{path=$path.Replace('\','/');exists=$exists;sha256=$(if($exists){Sha $full}else{$null})}
 })
}
function Inventory([string[]]$Files){
 $rows=@();$issues=@();$suites=@()
 foreach($file in $Files){
  $settings=New-Object Xml.XmlReaderSettings;$settings.DtdProcessing=[Xml.DtdProcessing]::Prohibit;$settings.XmlResolver=$null
  $reader=[Xml.XmlReader]::Create($file,$settings)
  try{$doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load($reader)}finally{$reader.Dispose()}
  foreach($suite in @($doc.SelectNodes('//testsuite'))){
   $cases=@($suite.SelectNodes('testcase'));$suites+=[string]$suite.name
   if($suite.HasAttribute('tests') -and [int]$suite.tests -ne $cases.Count){$issues+='SUITE_CASE_COUNT_'+[string]$suite.name}
   foreach($case in $cases){
    $status='PASS';$fault=$null
    if($case.SelectSingleNode('failure')){$status='FAIL';$fault=$case.SelectSingleNode('failure')}
    elseif($case.SelectSingleNode('error')){$status='ERROR';$fault=$case.SelectSingleNode('error')}
    elseif($case.SelectSingleNode('skipped')){$status='SKIP';$fault=$case.SelectSingleNode('skipped')}
    $class=[string]$case.classname;if(-not $class){$class=[string]$suite.name}
    $message='';$detail='';$type='';$causes=@();$first=''
    if($fault){
     $message=[string]$fault.message;$detail=[string]$fault.InnerText;$type=[string]$fault.type
     foreach($line in ($detail -split '\r?\n')){
      if($line -match '^\s*Caused by:\s*([A-Za-z_][A-Za-z0-9_.$]*(?:Exception|Error))(?::|$)'){$causes+=$Matches[1]}
      if(-not $first -and $line -match '^\s*at cn\.agentplatform\.[^\r\n]+'){$first=$line.Trim()}
     }
    }
    $rows+=[pscustomobject]@{key=$class+'#'+[string]$case.name;classname=$class;method=[string]$case.name;status=$status;
     type=$type;causes=$causes;firstProjectFrame=$first;message=$message;detail=$detail;messageSha256=(Text-Sha $message);detailSha256=(Text-Sha $detail);
     classEvent=([string]$case.name -match '^(initializationError|executionError|classError|<.*>)$');xml=[IO.Path]::GetFileName($file)}
   }
   if($cases.Count -eq 0 -and ($suite.SelectSingleNode('error') -or $suite.SelectSingleNode('failure'))){$issues+='UNREPRESENTED_CLASS_EVENT_'+[string]$suite.name}
  }
 }
 $duplicates=@($rows|Group-Object key|Where-Object Count -ne 1)
 if($duplicates.Count){$issues+='DUPLICATE_IDENTITIES'}
 [pscustomobject]@{rows=$rows;issues=$issues;suites=$suites}
}
function Public-Rows($Rows){@($Rows|Select-Object key,classname,method,status,type,causes,firstProjectFrame,messageSha256,detailSha256,classEvent,xml)}
function Props([string]$Path){
 $value=@{};if(Test-Path -LiteralPath $Path){foreach($line in Get-Content -LiteralPath $Path -Encoding UTF8){if($line -match '^([A-Za-z][A-Za-z0-9]*)=(.*)$'){$value[$Matches[1]]=$Matches[2]}}};return $value
}
function Scenario([string]$Name){$Name -replace '-[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$',''}
$phase='admission';$native=$null;$started=$null;$before=$null;$after=$null;$headBefore=$null;$headAfter=$null
$collectorErrors=@();$prior=@{};$location=Get-Location;$collectorExit=2
try{
 Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $runDir 'executed-run-module.ps1')
 $baseDir=Own-Path $candidate.baselineDirectory
 $greenXml=Own-Path $candidate.greenXml;$greenSummary=Read-Json (Own-Path $candidate.greenSummary)
 if($greenSummary.nativeExit -ne 0 -or $greenSummary.phase -ne 'green' -or $greenSummary.phaseExpectationMet -ne $true -or $greenSummary.sourceStable -ne $true -or $greenSummary.identityComplete -ne $true -or $greenSummary.contextStable -ne $true){throw 'DIRECTED_GREEN_RECEIPT_REQUIRED'}
 $baseFiles=@(Get-ChildItem -LiteralPath (Join-Path $baseDir 'xml') -Filter 'TEST-*.xml'|ForEach-Object FullName)
 $base=Inventory $baseFiles;$green=Inventory @($greenXml)
 $baseRun=Read-Json (Join-Path $baseDir 'run.json');$baseChildren=@(Read-Json (Join-Path $baseDir 'native-children.json'))
 if($baseFiles.Count -ne 27 -or $base.rows.Count -ne 288 -or @($base.rows|Where-Object status -eq 'PASS').Count -ne 273 -or @($base.rows|Where-Object status -eq 'FAIL').Count -ne 7 -or @($base.rows|Where-Object status -eq 'ERROR').Count -ne 8 -or $base.issues.Count -or $baseChildren.Count -ne 49){throw 'UNEXPECTED_EX27_BASELINE'}
 if($green.issues.Count -or $green.rows.Count -lt 1 -or @($green.rows|Where-Object{ $_.classname -cne $candidate.addedClass -or $_.status -ne 'PASS' -or $_.classEvent }).Count){throw 'ADDED_GREEN_IDENTITY_REQUIRED'}
 $expected=@($base.rows)+@($green.rows)
 if(@($expected|Group-Object key|Where-Object Count -ne 1).Count){throw 'EXPECTED_IDENTITY_OVERLAP'}
 $before=Fingerprints;Fact 'fingerprints-before.json' $before
 if(@($before|Where-Object{($_.path -eq '.env' -or $_.path -like '.tools/m2/*') -and -not $_.exists}).Count){throw 'REQUIRED_ENVIRONMENT_OR_INSTALLED_JAR_MISSING'}
 $approved=@(Read-Json (Own-Path $candidate.expectedFingerprints))
 if((ConvertTo-Json -InputObject $before -Compress)-cne(ConvertTo-Json -InputObject $approved -Compress)){throw 'FROZEN_SOURCE_OR_ENVIRONMENT_DRIFT'}
 $greenSources=@(Read-Json (Own-Path $candidate.greenSources));$byPath=@{};foreach($row in $before){$byPath[$row.path.Replace('\','/')]=$row}
 foreach($row in $greenSources){$p=$row.path.Replace('\','/');if(-not $byPath.ContainsKey($p) -or $byPath[$p].sha256 -ine $row.sha256){throw 'DIRECTED_GREEN_SOURCE_DRIFT'}}
 if(-not $byPath.ContainsKey($candidate.addedSource.Replace('\','/'))){throw 'ADDED_SOURCE_NOT_FROZEN'}
 $pg=Read-Json (Own-Path $candidate.pgAdmission)
 $age=([DateTime]::UtcNow-[DateTime]::Parse($pg.checkedAtUtc).ToUniversalTime()).TotalMinutes
 if($pg.passed -ne $true -or $pg.configurationSha256 -ine $byPath['.env'].sha256 -or $age -lt -0.1 -or $age -gt 15){throw 'CURRENT_PG_ADMISSION_REQUIRED'}
 $dbNames=@('agent_platform_iam_admission_app_v12','agent_platform_iam_admission_engine_v12','agent_platform_iam_o1c_gateway_status_v12','agent_platform_iam_metadata_v12')
 foreach($name in $dbNames){$db=@($pg.databases|Where-Object name -ceq $name);if($db.Count -ne 1 -or $db[0].existed -ne $true -or $db[0].owner -cne 'agent_v12' -or $db[0].projectOwnershipVerified -ne $true -or $db[0].historyVerified -ne $true){throw 'KNOWN_OWNED_PG_DATABASE_REQUIRED'}}
 if($pg.host -cne '127.0.0.1' -or [int]$pg.port -ne 25432 -or [string]::IsNullOrWhiteSpace([string]$pg.dataDirectory) -or -not(Get-Process -Id ([int]$pg.pid) -ErrorAction SilentlyContinue)){throw 'PG_INSTANCE_RECEIPT_REQUIRED'}
 $pgProcess=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$pg.pid)
 if(-not $pgProcess -or [IO.Path]::GetFileName($pgProcess.ExecutablePath) -ine 'postgres.exe' -or $pgProcess.CommandLine.Replace('/','\').IndexOf(([string]$pg.dataDirectory).Replace('/','\'),[StringComparison]::OrdinalIgnoreCase) -lt 0){throw 'PG_PROCESS_DATA_DIRECTORY_CHANGED'}
 $sourceClasses=@(Get-ChildItem -LiteralPath (Join-Path $ApRoot 'platform-gateway/src/test/java') -Filter '*Test.java' -Recurse|ForEach-Object BaseName|Sort-Object)
 $wantedClasses=@(@($baseRun.sourceTestClasses)+@(($candidate.addedClass -split '\.')[-1])|Sort-Object)
 if(($sourceClasses -join '|')-cne($wantedClasses -join '|')){throw 'SOURCE_CLASS_SET_CHANGED'}
 $sseSource=Join-Path $ApRoot 'platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java'
 $peerMatch=[regex]::Match([IO.File]::ReadAllText($sseSource),'\.resolve\("(\.local/[^"\r\n]+/peers)"\)')
 if(-not $peerMatch.Success){throw 'PEER_ROOT_SOURCE_UNRESOLVED'}
 $peerRoot=Own-Path $peerMatch.Groups[1].Value
 $priorPeers=@();if(Test-Path -LiteralPath $peerRoot){$priorPeers=@(Get-ChildItem -LiteralPath $peerRoot -Directory|ForEach-Object Name)}
 $headBefore=((& git -C $ApRoot rev-parse HEAD)-join '').Trim()
 Fact 'admission.json' ([ordered]@{head=$headBefore;candidateSha256=(Sha $candidateFile);baselineReports=27;baselineRecords=288;baselineNonpass=15;addedRecords=$green.rows.Count;expectedRecords=$expected.Count;greenXmlSha256=(Sha $greenXml);pgAdmissionSha256=(Sha (Own-Path $candidate.pgAdmission));collectorSha256=(Sha $PSCommandPath);noDdlByCollector=$true})
 foreach($key in @('JAVA_HOME','PATH','ENGINE_PG_TEST')){$prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
 foreach($line in Get-Content -LiteralPath (Join-Path $ApRoot '.env') -Encoding UTF8){
  if($line -match '^([^#=]+)=(.*)$'){$key=$Matches[1];if(-not $prior.ContainsKey($key)){$prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')};[Environment]::SetEnvironmentVariable($key,$Matches[2],'Process')}
 }
 $env:JAVA_HOME=[string]$candidate.javaHome;$env:PATH="$env:JAVA_HOME/bin;$env:PATH";$env:ENGINE_PG_TEST='true'
 if(-not(Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe')) -or -not(Test-Path -LiteralPath $candidate.unixTmpDir -PathType Container)){throw 'JDK_OR_EXISTING_TEMP_DIRECTORY_REQUIRED'}
 $mavenArgs=@('-B','-ntp','-pl','platform-gateway','test',"-Dmaven.repo.local=$ApRoot/.tools/m2",("-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir="+[string]$candidate.unixTmpDir),'-DreuseForks=false','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true')
 $phase='maven';Set-Location -LiteralPath $ApRoot;$started=[DateTime]::UtcNow
 Fact 'context.json' ([ordered]@{headAtStart=$headBefore;startedAtUtc=$started.ToString('o');arguments=$mavenArgs;javaHome=$env:JAVA_HOME;failureIgnore=$false;reuseForks=$false;developmentFlagPresent=($env:AGENT_DEV_AUTH -eq 'true');postgresTestsEnabled=($env:ENGINE_PG_TEST -eq 'true');rawDirectory=$rawDir;rawLogEncoding='Windows PowerShell native redirection; retain original bytes';serviceTokensReset=$false})
 $ErrorActionPreference='Continue'
 & mvn.cmd @mavenArgs *> (Join-Path $rawDir 'raw.log')
 $native=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 Fact 'native-exit.json' ([ordered]@{nativeMavenExit=$native;endedAtUtc=[DateTime]::UtcNow.ToString('o')})
 $phase='collection';$xmlDir=Join-Path $rawDir 'xml';New-Item -ItemType Directory -Path $xmlDir|Out-Null
 $reports=@(Get-ChildItem -LiteralPath (Join-Path $ApRoot 'platform-gateway/target/surefire-reports') -Filter 'TEST-*.xml'|Where-Object LastWriteTimeUtc -ge $started)
 foreach($report in $reports){Copy-Item -LiteralPath $report.FullName -Destination (Join-Path $xmlDir $report.Name)}
 $actual=Inventory @($reports|ForEach-Object FullName)
 Fact 'methods.json' (Public-Rows $actual.rows);Raw-Fact 'raw-reasons.json' @($actual.rows|Where-Object status -ne 'PASS')
 Fact 'xml-manifest.json' @($reports|ForEach-Object{[pscustomobject]@{file=$_.Name;sha256=(Sha $_.FullName);lastWriteTimeUtc=$_.LastWriteTimeUtc.ToString('o')}})
 $a=@{};foreach($row in $actual.rows){$a[$row.key]=$row};$e=@{};foreach($row in $expected){$e[$row.key]=$row}
 $differences=@(foreach($key in @(@($e.Keys)+@($a.Keys)|Sort-Object -Unique)){
  $old=if($e.ContainsKey($key)){$e[$key].status}else{'UNEXPECTED'};$now=if($a.ContainsKey($key)){$a[$key].status}else{'MISSING'}
  if($old -cne $now){[pscustomobject]@{key=$key;expected=$old;actual=$now}}
 })
 Fact 'identity-differences.json' $differences
 $reasons=@(foreach($old in @($base.rows|Where-Object status -ne 'PASS')){
  $now=$a[$old.key]
  [pscustomobject]@{key=$old.key;present=($null-ne$now);statusSame=($now.status-ceq$old.status);typeBefore=$old.type;typeAfter=$now.type;typeSame=($old.type-ceq$now.type);
   causesBefore=$old.causes;causesAfter=$now.causes;causesSame=(($old.causes-join'|')-ceq($now.causes-join'|'));firstFrameBefore=$old.firstProjectFrame;firstFrameAfter=$now.firstProjectFrame;firstFrameSame=($old.firstProjectFrame-ceq$now.firstProjectFrame);
   messageBeforeSha256=$old.messageSha256;messageAfterSha256=$now.messageSha256;detailBeforeSha256=$old.detailSha256;detailAfterSha256=$now.detailSha256;rawSame=($old.message-ceq$now.message -and $old.detail-ceq$now.detail)}
 })
 Fact 'nonpass-reason-comparison.json' $reasons;Raw-Fact 'baseline-nonpass-reasons.json' @($base.rows|Where-Object status -ne 'PASS')
 $children=@();$safety=@();$peers=@();if(Test-Path -LiteralPath $peerRoot){$peers=@(Get-ChildItem -LiteralPath $peerRoot -Directory|Where-Object{$_.Name-notin$priorPeers})}
 foreach($peer in $peers){
  $dest=Join-Path $rawDir ('peers/'+$peer.Name);New-Item -ItemType Directory -Path $dest -Force|Out-Null
  $props=Props (Join-Path $peer.FullName 'exit.properties')
  $children+=[pscustomobject]@{directory=$peer.Name;scenario=(Scenario $peer.Name);exit=$props['exit'];alive=$props['alive'];collectorAlive=$props['collectorAlive'];pid=$props['pid']}
  foreach($name in @('exit.properties','associated-close-facts.json','ex27-release-diagnostic.json','forced-release.properties','off-lock-release-safety.properties','protocol.properties')){if(Test-Path -LiteralPath (Join-Path $peer.FullName $name)){Copy-Item -LiteralPath (Join-Path $peer.FullName $name) -Destination (Join-Path $dest $name)}}
  foreach($name in @('forced-release.properties','off-lock-release-safety.properties')){
   $file=Join-Path $peer.FullName $name;if(-not(Test-Path -LiteralPath $file)){continue}
   $reference=@(Get-ChildItem -LiteralPath (Join-Path $baseDir 'peers') -Directory|Where-Object{(Scenario $_.Name)-ceq(Scenario $peer.Name)})
   if($reference.Count -ne 1){$collectorErrors+='SAFETY_REFERENCE_NOT_UNIQUE';continue}
   $old=Props (Join-Path $reference[0].FullName $name);$now=Props $file
   $changed=@($old.Keys|Where-Object{$_-ne'recycled'-and$old[$_]-cne$now[$_]})
   $safety+=[pscustomobject]@{scenario=(Scenario $peer.Name);file=$name;before=$old;after=$now;fixedInvariantChanges=$changed;recycledIsObservation=$true}
  }
  $phases=@(foreach($trace in @(Get-ChildItem -LiteralPath $peer.FullName -Filter 'forced-trace-*.txt')){
   $names=@(Get-Content -LiteralPath $trace.FullName -Encoding UTF8|ForEach-Object{if($_-match'^([a-z][a-z0-9-]{0,64})=-?[0-9]+$'){$Matches[1]}})
   [pscustomobject]@{file=$trace.Name;phases=$names}
  });if($phases.Count){Raw-Fact ('phases-'+$peer.Name+'.json') $phases}
 }
 Fact 'native-children.json' $children;Fact 'sse-safety.json' $safety
 $oldScenarios=@($baseChildren|ForEach-Object{Scenario $_.directory}|Sort-Object);$newScenarios=@($children.scenario|Sort-Object)
 $childGate=($children.Count-eq$baseChildren.Count -and ($oldScenarios-join'|')-ceq($newScenarios-join'|') -and @($children|Where-Object{$_.exit-ne'0'-or$_.alive-ne'false'-or$_.collectorAlive-ne'false'}).Count-eq0)
 $sseClass='cn.agentplatform.gateway.infra.PortalRunEventsSseLifecycleTest'
 $safetyGate=($childGate-and$safety.Count-eq2-and@($safety|Where-Object{$_.fixedInvariantChanges.Count}).Count-eq0-and@($actual.rows|Where-Object{$_.classname-ceq$sseClass-and$_.status-ne'PASS'}).Count-eq0)
 $methodGate=($actual.issues.Count-eq0-and$reports.Count-eq($baseFiles.Count+1)-and$actual.rows.Count-eq$expected.Count-and$differences.Count-eq0)
 $reasonGate=($reasons.Count-eq15-and@($reasons|Where-Object{-not($_.present-and$_.statusSame-and$_.typeSame-and$_.causesSame-and$_.firstFrameSame-and$_.rawSame)}).Count-eq0)
 $after=Fingerprints;$headAfter=((& git -C $ApRoot rev-parse HEAD)-join '').Trim();Fact 'fingerprints-after.json' $after
 $stable=((ConvertTo-Json -InputObject $before -Compress)-ceq(ConvertTo-Json -InputObject $after -Compress))
 $gate=($methodGate-and$reasonGate-and$safetyGate-and$stable-and$headBefore-ceq$headAfter-and$native-eq1-and$collectorErrors.Count-eq0)
 $collectorExit=if($gate){0}else{1}
 Fact 'summary.json' ([ordered]@{nativeMavenExit=$native;collectorExit=$collectorExit;headAtStart=$headBefore;headAtEnd=$headAfter;sourceStable=$stable;reports=$reports.Count;expectedRecords=$expected.Count;records=$actual.rows.Count;addedExpected=$green.rows.Count;
  statuses=@($actual.rows|Group-Object status|ForEach-Object{[pscustomobject]@{status=$_.Name;count=$_.Count}});classEvents=@($actual.rows|Where-Object classEvent).Count;identityGate=$methodGate;original15RawReasonGate=$reasonGate;sseAnd49ChildGate=$safetyGate;regressionGate=$gate;allTestsPassed=(@($actual.rows|Where-Object status -ne 'PASS').Count-eq0-and$methodGate);collectionIssues=$actual.issues;collectorErrors=$collectorErrors;rawReasonsNormalized=$false;noFailureIgnore=$true;baselineNativeOneIsExpected=$true})
 $phase='completed'
}catch{
 $collectorErrors+=$_.Exception.GetType().FullName
 Fact 'collector-error.json' ([ordered]@{phase=$phase;scriptLine=$_.InvocationInfo.ScriptLineNumber;reason='COLLECTOR_OR_ADMISSION_FAILED';type=$_.Exception.GetType().FullName;nativeMavenExit=$native;targetTestFailureInferred=$false})
}finally{
 if($before -and -not $after){try{Fact 'fingerprints-after.json' (Fingerprints)}catch{Fact 'fingerprint-error.json' @{reason='FINAL_FINGERPRINT_FAILED';type=$_.Exception.GetType().FullName}}}
 foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')}
 Set-Location -LiteralPath $location
 Fact 'collector-exit.json' ([ordered]@{collectorExit=$collectorExit;phase=$phase;nativeMavenExit=$native;endedAtUtc=[DateTime]::UtcNow.ToString('o');executionFinished=$true})
}
if(Test-Path -LiteralPath (Join-Path $runDir 'summary.json')){Get-Content -LiteralPath (Join-Path $runDir 'summary.json') -Encoding UTF8}
exit $collectorExit
