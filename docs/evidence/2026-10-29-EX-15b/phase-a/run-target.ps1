# RED only. Preparation does not grant a Maven/runtime window.
param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ApprovedManifestSha256,
 [switch]$ExecuteApproved
)
$ErrorActionPreference='Stop'
if(-not $ExecuteApproved){throw 'EXCLUSIVE_ACTUAL_WINDOW_NOT_GRANTED'}
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$manifestPath=Join-Path $PSScriptRoot 'manifest.json'
if((Get-FileHash -LiteralPath $manifestPath).Hash -cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'APPROVED_MANIFEST_CHANGED'}
$manifest=[IO.File]::ReadAllText($manifestPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
$run=Join-Path $PSScriptRoot ('runs/'+$RunId)
if(Test-Path -LiteralPath $run){throw 'RUN_ALREADY_EXISTS'}
New-Item -ItemType Directory -Path $run|Out-Null
$phase='INPUT_ADMISSION';$native=$null;$collector=2;$prior=@{};$location=Get-Location
function Save([string]$name,$value){[IO.File]::WriteAllText((Join-Path $run $name),(ConvertTo-Json -InputObject $value -Depth 14),(New-Object Text.UTF8Encoding($false)))}
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
function Assert-Pin([string]$path,[string]$sha){
 $full=if([IO.Path]::IsPathRooted($path)){$path}else{Join-Path $root $path}
 if($sha -cnotmatch '^[A-Fa-f0-9]{64}$' -or (Hash $full) -cne $sha.ToUpperInvariant()){throw 'FROZEN_INPUT_OR_DEPENDENCY_CHANGED'}
}
function Sources {
 @(Get-ChildItem -LiteralPath (Join-Path $root 'skill-registry/src') -Recurse -File|Sort-Object FullName|ForEach-Object{
  [pscustomobject]@{path=$_.FullName.Substring($root.Length+1).Replace([char]92,[char]47);sha256=(Hash $_.FullName)}
 })
}
function Same-Sources($rows){
 $map=@{};foreach($s in $manifest.sources){if($map.ContainsKey($s.path)){return $false};$map[$s.path]=$s.sha256}
 $map.Count -eq 152 -and @($rows).Count -eq 152 -and @($rows|Where-Object{-not $map.ContainsKey($_.path) -or $map[$_.path] -cne $_.sha256}).Count -eq 0
}
function Identity-Map($rows){
 $map=@{};foreach($row in $rows){$key=Get-Ex04Identity $row;if($map.ContainsKey($key)){throw 'DUPLICATE_METHOD_IDENTITY'};$map[$key]=$row};$map
}
function Context-Facts {
 [ordered]@{head=((& git -C $root rev-parse HEAD)-join '');envSha256=(Hash (Join-Path $root '.env'));files=@(foreach($pin in $manifest.contextPins){$full=if([IO.Path]::IsPathRooted($pin.path)){$pin.path}else{Join-Path $root $pin.path};[pscustomobject]@{path=$pin.path;sha256=(Hash $full)}});envFileImported=$false;pgMinioHttpInvoked=$false}
}
function Context-Matches($facts){
 $facts.head -ceq $manifest.expectedHead -and $facts.envSha256 -ceq $manifest.envSha256 -and @($facts.files|Where-Object{$actual=$_;@($manifest.contextPins|Where-Object{$_.path -ceq $actual.path -and $_.sha256 -ceq $actual.sha256}).Count -ne 1}).Count -eq 0 -and @($facts.files).Count -eq @($manifest.contextPins).Count
}
try{
 Set-Location -LiteralPath $root
 foreach($pin in $manifest.pins){Assert-Pin $pin.path $pin.sha256}
 foreach($pin in $manifest.contextPins){Assert-Pin $pin.path $pin.sha256}
 Assert-Pin '.env' $manifest.envSha256
 Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $run 'approved-manifest.json')
 Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $run 'collector.ps1')
 . (Join-Path $root $manifest.reportReader)
 $before=Sources;Save 'source-before.json' $before
 if(-not(Same-Sources $before)){throw 'FROZEN_152_SKILL_SOURCES_CHANGED'}
 $originalSources=[IO.File]::ReadAllText((Join-Path $root $manifest.originalSourceFile),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $oldMap=@{};foreach($source in $originalSources){$oldMap[$source.path]=$source.sha256}
 $delta=@($before|Where-Object{-not $oldMap.ContainsKey($_.path) -or $oldMap[$_.path] -cne $_.sha256}|Select-Object -ExpandProperty path)
 if($oldMap.Count -ne 152 -or $delta.Count -ne 3 -or @($delta|Where-Object{$manifest.allowedTestDelta -cnotcontains $_}).Count -ne 0){throw 'ONLY_THREE_TEST_DELTAS_ALLOWED'}
 $contextBefore=Context-Facts;Save 'context-before.json' $contextBefore
 if(-not(Context-Matches $contextBefore)){throw 'HEAD_ENV_OR_DEPENDENCY_CHANGED_REFREEZE_REQUIRED'}
 $baselineCsv=@(Import-Csv -LiteralPath (Join-Path $root $manifest.originalMethodsFile)|Where-Object{$manifest.classes -ccontains $_.classname})
 $baselineXml=Join-Path $run 'baseline-xml';New-Item -ItemType Directory -Path $baselineXml|Out-Null
 foreach($pin in $manifest.originalXmlPins){Assert-Pin $pin.path $pin.sha256;Copy-Item -LiteralPath (Join-Path $root $pin.path) -Destination (Join-Path $baselineXml ([IO.Path]::GetFileName($pin.path)))}
 $originalReport=Read-Ex04FullReports $baselineXml
 $baseMap=Identity-Map @($originalReport.rows);$csvMap=Identity-Map $baselineCsv
 if($baseMap.Count -ne 25 -or $csvMap.Count -ne 25 -or @($originalReport.classes).Count -ne 3 -or @($originalReport.issues).Count -ne 0){throw 'ORIGINAL_25_METHOD_BASELINE_INCOMPLETE'}
 foreach($key in $baseMap.Keys){if(-not $csvMap.ContainsKey($key) -or $baseMap[$key].status -cne 'passed' -or $csvMap[$key].status -cne 'passed' -or $baseMap[$key].message_sha256 -cne $csvMap[$key].message_sha256 -or $baseMap[$key].detail_sha256 -cne $csvMap[$key].detail_sha256){throw 'ORIGINAL_BASELINE_CSV_XML_MISMATCH'}}
 $expected=@{};foreach($key in $baseMap.Keys){$expected[$key]=$baseMap[$key]}
 foreach($row in $manifest.newMethods){$key=Get-Ex04Identity $row;if($expected.ContainsKey($key)){throw 'NEW_IDENTITY_DUPLICATES_ORIGINAL'};$expected[$key]=$row}
 if($expected.Count -ne 30 -or @($manifest.newMethods).Count -ne 5 -or @($manifest.newMethods|Where-Object status -ceq 'failure').Count -ne 3){throw 'EXACT_PHASE_A_EXPECTATION_REQUIRED'}
 Save 'admission.json' ([ordered]@{phase='RED_ONLY';head=$contextBefore.head;manifestSha256=(Hash $manifestPath);collectorSha256=(Hash $PSCommandPath);sourceCount=152;onlyThreeTestDeltas=$delta;originalMethods=25;newControls=2;expectedBehaviorFailures=3;expectedMethods=30;envFileImported=$false;pgMinioHttpInvoked=$false;commonRebuildNeedsNewApprovedManifest=$true;explicitActualWindowGranted=$true})
 foreach($name in @('JAVA_HOME','PATH')){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
 foreach($name in @('MAVEN_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')){if(-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name,'Process'))){throw 'UNAPPROVED_JVM_ENVIRONMENT'}}
 $env:JAVA_HOME=$manifest.jdk;$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 $arguments=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$root/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=false','-Dtest=ProcessContractTest,SkillProcessRegistrationTest,ProcessFilesTest')
 $phase='MAVEN';$started=[DateTime]::UtcNow
 Save 'command.json' ([ordered]@{command=$manifest.maven;arguments=$arguments;startedAt=$started.ToString('o');head=$contextBefore.head;envSha256=$contextBefore.envSha256;jdk=$env:JAVA_HOME;failureIgnore=$false;defaultForkReuseAndOrder=$true;envFileImported=$false;pgMinioHttpInvoked=$false;cleanBuildInstallOrAm=$false})
 try{$ErrorActionPreference='Continue';& $manifest.maven @arguments *> (Join-Path $run 'raw.log');$native=$LASTEXITCODE}
 finally{$ErrorActionPreference='Stop';Save 'native-exit.json' ([ordered]@{nativeExit=$native;startedAt=$started.ToString('o');endedAt=[DateTime]::UtcNow.ToString('o');failureIgnore=$false;nativeRecordedBeforeReportParsing=$true})}
 $phase='ARCHIVE'
 $after=Sources;Save 'source-after.json' $after;$sourceStable=Same-Sources $after
 $contextAfter=Context-Facts;Save 'context-after.json' $contextAfter;$contextStable=Context-Matches $contextAfter
 $xml=Join-Path $run 'xml';New-Item -ItemType Directory -Path $xml|Out-Null
 $fresh=@(Get-ChildItem -LiteralPath 'skill-registry/target/surefire-reports' -File -Filter 'TEST-*.xml'|Where-Object{$_.LastWriteTimeUtc -ge $started})
 foreach($file in $fresh){Copy-Item -LiteralPath $file.FullName -Destination $xml}
 Save 'xml-manifest.json' @($fresh|ForEach-Object{[pscustomobject]@{file=$_.Name;bytes=$_.Length;mtimeUtc=$_.LastWriteTimeUtc.ToString('o');sha256=(Hash (Join-Path $xml $_.Name))}})
 Save 'raw-log-manifest.json' ([ordered]@{path='raw.log';bytes=(Get-Item -LiteralPath (Join-Path $run 'raw.log')).Length;sha256=(Hash (Join-Path $run 'raw.log'));unmodified=$true})
 Assert-Pin $manifest.reportReader $manifest.reportReaderSha256
 $report=Read-Ex04FullReports $xml;$rows=@($report.rows);$actual=Identity-Map $rows
 $rows|Export-Csv -LiteralPath (Join-Path $run 'methods.csv') -NoTypeInformation -Encoding UTF8
 Save 'xml-issues.json' @($report.issues)
 $missing=@($expected.Keys|Where-Object{$actual.Keys -cnotcontains $_});$extra=@($actual.Keys|Where-Object{$expected.Keys -cnotcontains $_})
 $changes=@(foreach($key in $expected.Keys){if($actual.ContainsKey($key) -and $actual[$key].status -cne $expected[$key].status){[pscustomobject]@{identity=$key;expected=$expected[$key].status;actual=$actual[$key].status}}})
 Save 'method-status-changes.json' $changes
 $reasonFacts=@(foreach($row in $manifest.newMethods|Where-Object status -ceq 'failure'){
  $key=Get-Ex04Identity $row;$observed=$actual[$key]
  $okay=$null -ne $observed -and $observed.status -ceq 'failure' -and $observed.exception_type -ceq 'org.opentest4j.AssertionFailedError' -and $observed.message_sha256 -ceq $manifest.expectedNothingThrownMessageSha256
  [pscustomobject]@{identity=$key;behavior=$row.behavior;matched=$okay;exceptionType=if($observed){$observed.exception_type}else{'MISSING'};messageSha256=if($observed){$observed.message_sha256}else{''};detailSha256=if($observed){$observed.detail_sha256}else{''};firstProjectFrame=if($observed){$observed.first_project_frame}else{''};reason=if($okay){'EXPECTED_PLATFORM_EXCEPTION_NOT_THROWN'}else{'UNEXPECTED_FAILURE_OR_MISSING'}}
 })
 Save 'expected-red-reasons.json' $reasonFacts
 $classPaths=@(foreach($file in $fresh){
  $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load((Join-Path $xml $file.Name))
  $property=$doc.SelectSingleNode("//property[@name='java.class.path']")
  $cp=if($property){$property.GetAttribute('value')}else{''}
  [pscustomobject]@{xml=$file.Name;present=($null -ne $property);valueSha256=(Get-Ex04TextSha $cp);mainJarPresent=$cp.Contains('admin-common-0.1.0-SNAPSHOT.jar');testJarPresent=$cp.Contains('admin-common-0.1.0-SNAPSHOT-tests.jar');fullValueNotPublished=$true}
 })
 Save 'classpath-facts.json' $classPaths
 $classSet=@($report.classes)
 $complete=$rows.Count -eq 30 -and $fresh.Count -eq 3 -and $classSet.Count -eq 3 -and @($manifest.classes|Where-Object{$classSet -cnotcontains $_}).Count -eq 0 -and @($classSet|Where-Object{$manifest.classes -cnotcontains $_}).Count -eq 0 -and $missing.Count -eq 0 -and $extra.Count -eq 0 -and @($report.issues).Count -eq 0
 $oldPassed=@($baseMap.Keys|Where-Object{$actual.ContainsKey($_) -and $actual[$_].status -ceq 'passed'}).Count
 $controlsPassed=@($manifest.newMethods|Where-Object{$_.status -ceq 'passed' -and $actual.ContainsKey((Get-Ex04Identity $_)) -and $actual[(Get-Ex04Identity $_)].status -ceq 'passed'}).Count
 $errors=@($rows|Where-Object status -ceq 'error').Count;$skips=@($rows|Where-Object status -ceq 'skipped').Count
 $classpathOkay=$classPaths.Count -eq 3 -and @($classPaths|Where-Object{-not $_.present -or -not $_.mainJarPresent -or $_.testJarPresent}).Count -eq 0
 $behaviorRed=$native -eq 1 -and $complete -and $oldPassed -eq 25 -and $controlsPassed -eq 2 -and $reasonFacts.Count -eq 3 -and @($reasonFacts|Where-Object{-not $_.matched}).Count -eq 0 -and $changes.Count -eq 0 -and $errors -eq 0 -and $skips -eq 0
 $gate=$behaviorRed -and $sourceStable -and $contextStable -and $classpathOkay
 Save 'summary.json' ([ordered]@{mode='RED_ONLY';nativeExit=$native;failureIgnore=$false;records=$rows.Count;freshXml=$fresh.Count;originalMethodsPassed=$oldPassed;newControlsPassed=$controlsPassed;passed=@($rows|Where-Object status -ceq 'passed').Count;failures=@($rows|Where-Object status -ceq 'failure').Count;errors=$errors;skipped=$skips;missing=$missing;extra=$extra;unexpectedStatusChanges=$changes.Count;exactIdentityComplete=$complete;expectedThreeReasonsPassed=($reasonFacts.Count -eq 3 -and @($reasonFacts|Where-Object{-not $_.matched}).Count -eq 0);expectedBehaviorRed=$behaviorRed;classpathPassed=$classpathOkay;sourceStable=$sourceStable;contextStable=$contextStable;gatePassed=$gate;allGreen=($native -eq 0 -and $complete -and @($rows|Where-Object status -cne 'passed').Count -eq 0);collectorZeroMeansExpectedRedOnly=$true;envFileImported=$false;pgMinioHttpInvoked=$false;wholeEx15bComplete=$false})
 $collector=if($gate){0}else{1}
 Write-Output ('EX15B_PHASE_A native='+$native+' records='+$rows.Count+' expectedRed='+$behaviorRed+' gate='+$gate)
}catch{
 $reason=if($_.Exception.Message -cmatch '^[A-Z_0-9]+$'){$_.Exception.Message}else{'UNEXPECTED_COLLECTOR_ERROR'}
 Save 'collector-error.json' ([ordered]@{phase=$phase;reason=$reason;scriptLine=$_.InvocationInfo.ScriptLineNumber;exceptionClass=$_.Exception.GetType().FullName;nativeExit=$native;unexpectedCollectorFailureNotProductRed=$true})
 Write-Output ('EX15B_PHASE_A_STOPPED phase='+$phase+' line='+$_.InvocationInfo.ScriptLineNumber)
}finally{
 foreach($name in $prior.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}
 Set-Location -LiteralPath $location
 Save 'collector-exit.json' ([ordered]@{nativeExit=$native;collectorExit=$collector;rawEvidencePreserved=$true;noAutomaticRerunOrFix=$true})
}
exit $collector
