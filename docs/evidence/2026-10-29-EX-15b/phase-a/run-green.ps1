# GREEN only. The immutable actual RED is a prerequisite; this file never runs automatically.
param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ApprovedManifestSha256,
 [switch]$ExecuteApproved
)
$ErrorActionPreference='Stop'
if(-not $ExecuteApproved){throw 'EXCLUSIVE_ACTUAL_WINDOW_NOT_GRANTED'}
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$manifestPath=Join-Path $PSScriptRoot 'green-manifest.json'
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
function Exact-Classpaths($rows){
 @($rows).Count -eq 3 -and @($rows|Where-Object{-not $_.present -or $_.valueSha256 -cne $manifest.expectedClasspathValueSha256 -or $_.commonJarCandidates -ne 1 -or $_.exactApMainJarMatches -ne 1 -or $_.otherCommonJarCount -ne 0 -or $_.testJarEntries -ne 0 -or $_.unparseableClasspathEntries -ne 0 -or $_.mainJarSha256 -cne $manifest.commonContext.preparedMainJarSha256}).Count -eq 0
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
 $redDirectory=Join-Path $root $manifest.redRunDirectory
 $redManifest=[IO.File]::ReadAllText((Join-Path $redDirectory 'approved-manifest.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $redSummary=[IO.File]::ReadAllText((Join-Path $redDirectory 'summary.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $redNative=[IO.File]::ReadAllText((Join-Path $redDirectory 'native-exit.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $redExit=[IO.File]::ReadAllText((Join-Path $redDirectory 'collector-exit.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $redAdmission=[IO.File]::ReadAllText((Join-Path $redDirectory 'admission.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 if($redSummary.mode -cne 'RED_ONLY' -or $redSummary.records -ne 30 -or $redSummary.passed -ne 27 -or $redSummary.failures -ne 3 -or $redSummary.errors -ne 0 -or $redSummary.skipped -ne 0 -or -not $redSummary.gatePassed -or -not $redSummary.sourceStable -or -not $redSummary.contextStable -or -not $redSummary.expectedThreeReasonsPassed -or $redSummary.failureIgnore -or $redNative.nativeExit -ne 1 -or $redExit.collectorExit -ne 0 -or $redAdmission.manifestSha256 -cne $manifest.expectedRedManifestSha256 -or $redAdmission.collectorSha256 -cne $manifest.expectedRedRunnerSha256){throw 'ACTUAL_RED_NOT_QUALIFIED'}
 $redSources=[IO.File]::ReadAllText((Join-Path $redDirectory 'source-before.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $redAfter=[IO.File]::ReadAllText((Join-Path $redDirectory 'source-after.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $oldMap=@{};foreach($s in $redSources){if($oldMap.ContainsKey($s.path)){throw 'DUPLICATE_RED_SOURCE_PATH'};$oldMap[$s.path]=$s.sha256}
 foreach($sourceRows in @(@($redManifest.sources),@($redAfter))){
  if(@($sourceRows).Count -ne 152 -or @($sourceRows|Where-Object{-not $oldMap.ContainsKey($_.path) -or $oldMap[$_.path] -cne $_.sha256}).Count -ne 0){throw 'ACTUAL_RED_SOURCE_BEFORE_AFTER_OR_MANIFEST_DIFFERS'}
 }
 $delta=@($before|Where-Object{-not $oldMap.ContainsKey($_.path) -or $oldMap[$_.path] -cne $_.sha256}|Select-Object -ExpandProperty path)
 if($oldMap.Count -ne 152 -or $delta.Count -ne 2 -or @($delta|Where-Object{$manifest.allowedProductDelta -cnotcontains $_}).Count -ne 0){throw 'ONLY_TWO_PRODUCT_DELTAS_ALLOWED'}
 $contextBefore=Context-Facts;Save 'context-before.json' $contextBefore
 $redContextBefore=[IO.File]::ReadAllText((Join-Path $redDirectory 'context-before.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $redContextAfter=[IO.File]::ReadAllText((Join-Path $redDirectory 'context-after.json'),[Text.Encoding]::UTF8)|ConvertFrom-Json
 if(-not(Context-Matches $contextBefore) -or -not(Context-Matches $redContextBefore) -or -not(Context-Matches $redContextAfter)){throw 'RED_GREEN_HEAD_ENV_OR_DEPENDENCY_DIFFERS'}
 $redReport=Read-Ex04FullReports (Join-Path $redDirectory 'xml');$redMap=Identity-Map @($redReport.rows)
 $redCsv=@(Import-Csv -LiteralPath (Join-Path $redDirectory 'methods.csv'));$redCsvMap=Identity-Map $redCsv
 if($redMap.Count -ne 30 -or $redCsvMap.Count -ne 30 -or @($redReport.classes).Count -ne 3 -or @($redReport.issues).Count -ne 0){throw 'ACTUAL_RED_REPORT_INCOMPLETE'}
 foreach($key in $redMap.Keys){$a=$redMap[$key];$b=$redCsvMap[$key];if($null -eq $b -or $a.status -cne $b.status -or $a.exception_type -cne $b.exception_type -or $a.message_sha256 -cne $b.message_sha256 -or $a.detail_sha256 -cne $b.detail_sha256){throw 'ACTUAL_RED_CSV_XML_DIFFERS'}}
 foreach($row in $manifest.redExpectedNewMethods){
  $key=Get-Ex04Identity $row;$actualRed=$redMap[$key]
  if($null -eq $actualRed -or $actualRed.status -cne $row.status){throw 'ACTUAL_RED_NEW_METHOD_STATUS_DIFFERS'}
  if($row.status -ceq 'failure' -and ($actualRed.exception_type -cne 'org.opentest4j.AssertionFailedError' -or $actualRed.message_sha256 -cne $manifest.expectedNothingThrownMessageSha256)){throw 'ACTUAL_RED_NOT_EXACT_NOTHING_THROWN'}
 }
 $original=@(Import-Csv -LiteralPath (Join-Path $root $manifest.originalMethodsFile)|Where-Object{$manifest.classes -ccontains $_.classname})
 $baseMap=Identity-Map $original
 if($baseMap.Count -ne 25 -or @($baseMap.Keys|Where-Object{-not $redMap.ContainsKey($_) -or $redMap[$_].status -cne 'passed'}).Count -ne 0){throw 'ACTUAL_RED_ORIGINAL_25_NOT_PASSED'}
 $expected=@{};foreach($key in $redMap.Keys){$row=$redMap[$key];$expected[$key]=[pscustomobject]@{classname=$row.classname;method=$row.method;display_name=$row.display_name;suite_path=$row.suite_path;status='passed'}}
 $redCp=Read-Classpaths (Join-Path $redDirectory 'xml');Save 'red-classpath-verification.json' $redCp
 if(-not(Exact-Classpaths $redCp)){throw 'ACTUAL_RED_CLASSPATH_NOT_EXACT_AP_MAIN_ONLY'}
 Save 'red-prerequisite.json' ([ordered]@{directory=$manifest.redRunDirectory;summarySha256=(Hash (Join-Path $redDirectory 'summary.json'));manifestSha256=(Hash (Join-Path $redDirectory 'approved-manifest.json'));runnerSha256=(Hash (Join-Path $redDirectory 'collector.ps1'));sourceBeforeSha256=(Hash (Join-Path $redDirectory 'source-before.json'));actualThirty=30;passed=27;exactBehaviorFailures=3;nativeExit=1;collectorExit=0;originalRedUnmodified=$true})
 Save 'admission.json' ([ordered]@{phase='GREEN_ONLY';head=$contextBefore.head;manifestSha256=(Hash $manifestPath);collectorSha256=(Hash $PSCommandPath);sourceCount=152;onlyTwoProductDeltas=$delta;originalMethods=25;newMethods=5;expectedMethods=30;allExpectedPassed=$true;envFileImported=$false;pgMinioHttpInvoked=$false;explicitActualWindowGranted=$true})
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
 $transitions=@(foreach($key in $expected.Keys){$a=$actual[$key];[pscustomobject]@{identity=$key;redStatus=$redMap[$key].status;expected='passed';greenStatus=if($a){$a.status}else{'MISSING'};passed=($null -ne $a -and $a.status -ceq 'passed')}})
 Save 'red-to-green-transitions.json' $transitions
 $classPaths=Read-Classpaths $xml;Save 'classpath-facts.json' $classPaths;$classpathOkay=Exact-Classpaths $classPaths
 $classSet=@($report.classes)
 $complete=$rows.Count -eq 30 -and $fresh.Count -eq 3 -and $classSet.Count -eq 3 -and @($manifest.classes|Where-Object{$classSet -cnotcontains $_}).Count -eq 0 -and @($classSet|Where-Object{$manifest.classes -cnotcontains $_}).Count -eq 0 -and $missing.Count -eq 0 -and $extra.Count -eq 0 -and @($report.issues).Count -eq 0
 $oldPassed=@($baseMap.Keys|Where-Object{$actual.ContainsKey($_) -and $actual[$_].status -ceq 'passed'}).Count
 $newPassed=@($manifest.newMethods|Where-Object{$actual.ContainsKey((Get-Ex04Identity $_)) -and $actual[(Get-Ex04Identity $_)].status -ceq 'passed'}).Count
 $failures=@($rows|Where-Object status -ceq 'failure').Count;$errors=@($rows|Where-Object status -ceq 'error').Count;$skips=@($rows|Where-Object status -ceq 'skipped').Count
 $allGreen=$native -eq 0 -and $complete -and $oldPassed -eq 25 -and $newPassed -eq 5 -and $failures -eq 0 -and $errors -eq 0 -and $skips -eq 0
 $gate=$allGreen -and $sourceStable -and $contextStable -and $classpathOkay
 Save 'summary.json' ([ordered]@{mode='GREEN_ONLY';nativeExit=$native;failureIgnore=$false;records=$rows.Count;freshXml=$fresh.Count;originalMethodsPassed=$oldPassed;newMethodsPassed=$newPassed;passed=@($rows|Where-Object status -ceq 'passed').Count;failures=$failures;errors=$errors;skipped=$skips;missing=$missing;extra=$extra;exactIdentityComplete=$complete;recoveredExactRedFailures=@($transitions|Where-Object{$_.redStatus -ceq 'failure' -and $_.passed}).Count;classpathPassed=$classpathOkay;sourceStable=$sourceStable;contextStable=$contextStable;gatePassed=$gate;allGreen=$allGreen;immutableActualRedPreserved=$true;envFileImported=$false;pgMinioHttpInvoked=$false;wholeEx15bComplete=$false})
 $collector=if($gate){0}else{1}
 Write-Output ('EX15B_PHASE_A_GREEN native='+$native+' records='+$rows.Count+' allGreen='+$allGreen+' gate='+$gate)
}catch{
 $reason=if($_.Exception.Message -cmatch '^[A-Z_0-9]+$'){$_.Exception.Message}else{'UNEXPECTED_COLLECTOR_ERROR'}
 Save 'collector-error.json' ([ordered]@{phase=$phase;reason=$reason;scriptLine=$_.InvocationInfo.ScriptLineNumber;exceptionClass=$_.Exception.GetType().FullName;nativeExit=$native;unexpectedCollectorFailureNotTestVerdict=$true})
 Write-Output ('EX15B_PHASE_A_GREEN_STOPPED phase='+$phase+' line='+$_.InvocationInfo.ScriptLineNumber)
}finally{
 foreach($name in $prior.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}
 Set-Location -LiteralPath $location
 Save 'collector-exit.json' ([ordered]@{nativeExit=$native;collectorExit=$collector;rawEvidencePreserved=$true;noAutomaticRerunOrFix=$true})
}
exit $collector
