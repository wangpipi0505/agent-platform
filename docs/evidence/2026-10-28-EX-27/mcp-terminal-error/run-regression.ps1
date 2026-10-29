# Actual RED is immutable. Green1 precedes Class39; each mode needs an explicitly sealed input.
param(
 [Parameter(Mandatory=$true)][ValidatePattern('\A[a-z0-9-]+\z')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('\A[A-Fa-f0-9]{64}\z')][string]$ApprovedManifestSha256,
 [ValidateSet('Green','Class')][string]$Mode='Green',
 [switch]$ExecuteApproved
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -ne 5){throw 'EXPLICIT_WINDOWS_POWERSHELL_5_REQUIRED'}
if(-not $ExecuteApproved){throw 'EXCLUSIVE_ACTUAL_WINDOW_NOT_GRANTED'}
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$manifestPath=Join-Path $PSScriptRoot 'regression-manifest.json'
if((Get-FileHash -LiteralPath $manifestPath).Hash -cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'APPROVED_MANIFEST_CHANGED'}
$manifest=[IO.File]::ReadAllText($manifestPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
if(-not $manifest.modes.$Mode.sealed -or -not $manifest.executionHeadSealed -or $manifest.expectedExecutionHead -cnotmatch '\A[a-f0-9]{40}\z'){throw 'MODE_OR_EXECUTION_HEAD_NOT_SEALED'}
if($Mode -ceq 'Class' -and (-not $manifest.greenPrerequisite.sealed -or [string]::IsNullOrWhiteSpace($manifest.greenPrerequisite.directory))){throw 'ACTUAL_GREEN_PREREQUISITE_NOT_SEALED'}
if((Get-FileHash -LiteralPath $PSCommandPath).Hash -cne $manifest.runnerSha256){throw 'APPROVED_RUNNER_CHANGED'}
$privateBase=Join-Path $root '.local/ex27-mcp-terminal'
$run=Join-Path $privateBase $RunId
foreach($directory in @($root,(Join-Path $root '.local'),$privateBase,$run)){
 if((Test-Path -LiteralPath $directory) -and ((Get-Item -LiteralPath $directory -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'PRIVATE_RUN_REPARSE_POINT'}
}
if(Test-Path -LiteralPath $run){throw 'RUN_ALREADY_EXISTS'}
New-Item -ItemType Directory -Path $run|Out-Null
$phase='INPUT_ADMISSION';$native=$null;$collector=2;$prior=@{};$location=Get-Location
function Save([string]$name,$value){[IO.File]::WriteAllText((Join-Path $run $name),(ConvertTo-Json -InputObject $value -Depth 16),(New-Object Text.UTF8Encoding($false)))}
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
function Full-Path([string]$path){if([IO.Path]::IsPathRooted($path)){$path}else{Join-Path $root $path}}
function Assert-Pin($pin){if($pin.sha256 -cnotmatch '\A[A-F0-9]{64}\z' -or (Hash (Full-Path $pin.path)) -cne $pin.sha256){throw 'FROZEN_INPUT_OR_DEPENDENCY_CHANGED'}}
function Sources {
 @(Get-ChildItem -LiteralPath (Join-Path $root 'skill-registry/src') -Recurse -File|Sort-Object FullName|ForEach-Object{[pscustomobject]@{path=$_.FullName.Substring($root.Length+1).Replace([char]92,[char]47);sha256=(Hash $_.FullName)}})
}
function Same-Sources($rows){
 $map=@{};foreach($s in $manifest.sources){if($map.ContainsKey($s.path)){return $false};$map[$s.path]=$s.sha256}
 $map.Count -eq 152 -and @($rows).Count -eq 152 -and @($rows|Where-Object{-not $map.ContainsKey($_.path) -or $map[$_.path] -cne $_.sha256}).Count -eq 0
}
function Context-Facts {
 $head=((& git -C $root rev-parse HEAD)-join '');if($LASTEXITCODE -ne 0){throw 'HEAD_READ_FAILED'}
 $delta=@(& git -C $root diff --name-only $manifest.baselineHead $head -- skill-registry admin-common pom.xml);if($LASTEXITCODE -ne 0){throw 'HEAD_SCOPE_COMPARISON_FAILED'}
 [ordered]@{head=$head;committedScopeDelta=$delta;envSha256=(Hash (Join-Path $root '.env'));files=@(foreach($pin in $manifest.contextPins){[pscustomobject]@{path=$pin.path;sha256=(Hash (Full-Path $pin.path))}});classpathJars=@(foreach($pin in $manifest.classpathJarPins){[pscustomobject]@{path=$pin.path;sha256=(Hash (Full-Path $pin.path))}});envFileImported=$false;pgMinioOrExternalHttpInvoked=$false}
}
function Pin-Rows-Match($actual,$expected){
 $map=@{};foreach($p in $expected){if($map.ContainsKey($p.path)){return $false};$map[$p.path]=$p.sha256}
 @($actual).Count -eq $map.Count -and @($actual|Where-Object{-not $map.ContainsKey($_.path) -or $map[$_.path] -cne $_.sha256}).Count -eq 0
}
function Context-Matches($facts){$facts.head -ceq $manifest.expectedExecutionHead -and @($facts.committedScopeDelta).Count -eq 0 -and $facts.envSha256 -ceq $manifest.envSha256 -and (Pin-Rows-Match $facts.files $manifest.contextPins) -and (Pin-Rows-Match $facts.classpathJars $manifest.classpathJarPins)}
function Identity-Map($rows){$map=@{};foreach($row in $rows){$key=Get-Ex04Identity $row;if($map.ContainsKey($key)){throw 'DUPLICATE_METHOD_IDENTITY'};$map[$key]=$row};$map}
function Read-Classpath([string]$file){
 $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load($file)
 $property=$doc.SelectSingleNode("//property[@name='java.class.path']");$cp=if($property){$property.GetAttribute('value')}else{''}
 $expected=[IO.Path]::GetFullPath((Join-Path $root $manifest.commonContext.mainJar)).Replace([char]92,[char]47)
 $common=@();$bad=0;$tests=0;$jarCount=0;$directoryCount=0
 foreach($part in ($cp -split ';')){
  if($part -ceq ''){continue}
  if([string]::IsNullOrWhiteSpace($part) -or -not [IO.Path]::IsPathRooted($part) -or $part -match '^[A-Za-z]:(?![\\/])'){$bad++;continue}
  try{$full=[IO.Path]::GetFullPath($part).Replace([char]92,[char]47);$name=[IO.Path]::GetFileName($full)}catch{$bad++;continue}
  if($name -imatch '\.jar$'){$jarCount++}else{$directoryCount++}
  if($name -imatch '^admin-common.*\.jar$'){$common+=$full}
  if($name -imatch '^admin-common.*-tests\.jar$'){$tests++}
 }
 $runtime=@(foreach($expect in $manifest.expectedRuntimeProperties){$p=$doc.SelectSingleNode("//property[@name='$($expect.name)']");[pscustomobject]@{name=$expect.name;matched=($null -ne $p -and $p.GetAttribute('value') -ceq $expect.value)}})
 $exact=@($common|Where-Object{[string]::Equals($_,$expected,[StringComparison]::OrdinalIgnoreCase)}).Count
 [pscustomobject]@{valueSha256=(Get-Ex04TextSha $cp);present=($null -ne $property);entryCount=($cp -split ';').Count;jarCount=$jarCount;directoryCount=$directoryCount;commonCandidates=$common.Count;exactMainMatches=$exact;testsJarEntries=$tests;badEntries=$bad;runtimeProperties=$runtime;runtimePropertiesPassed=(@($runtime|Where-Object{-not $_.matched}).Count -eq 0);passed=($null -ne $property -and (Get-Ex04TextSha $cp) -ceq $manifest.expectedClasspathValueSha256 -and $jarCount -eq 179 -and $directoryCount -eq 2 -and $common.Count -eq 1 -and $exact -eq 1 -and $tests -eq 0 -and $bad -eq 0 -and @($runtime|Where-Object{-not $_.matched}).Count -eq 0);fullValuePrivateInRawXml=$true}
}
function Read-Json([string]$path){[IO.File]::ReadAllText($path,[Text.Encoding]::UTF8)|ConvertFrom-Json}
function Verify-ActualRun($proof,[string]$expectedMode){
 if(-not $proof.sealed -or [string]::IsNullOrWhiteSpace($proof.directory)){throw 'ACTUAL_PREREQUISITE_NOT_SEALED'}
 $directory=Full-Path $proof.directory
 $required=@('approved-manifest.json','collector.ps1','summary.json','native-exit.json','collector-exit.json','admission.json','source-before.json','source-after.json','context-before.json','context-after.json','command.json','classpath-facts.json','methods.csv','xml-manifest.json','xml/TEST-cn.agentplatform.skill.infra.McpAdapterTest.xml')
 foreach($name in $required){$path=$proof.directory+'/'+$name;if(@($proof.pins|Where-Object path -ceq $path).Count -ne 1){throw 'PREREQUISITE_PIN_SET_INCOMPLETE'}}
 foreach($pin in $proof.pins){Assert-Pin $pin}
 $priorManifest=Read-Json (Join-Path $directory 'approved-manifest.json')
 $summary=Read-Json (Join-Path $directory 'summary.json');$exit=Read-Json (Join-Path $directory 'collector-exit.json');$nativeReceipt=Read-Json (Join-Path $directory 'native-exit.json');$admission=Read-Json (Join-Path $directory 'admission.json')
 $expectedNative=if($expectedMode -ceq 'Red'){1}else{0};$expectedStatus=if($expectedMode -ceq 'Red'){'failure'}else{'passed'}
 if($summary.mode -cne $expectedMode -or $summary.records -ne 1 -or $summary.freshXml -ne 1 -or $summary.nativeExit -ne $expectedNative -or $nativeReceipt.nativeExit -ne $expectedNative -or $exit.nativeExit -ne $expectedNative -or $exit.collectorExit -ne 0 -or $summary.failureIgnore -or $nativeReceipt.failureIgnore -or -not $summary.gatePassed -or -not $summary.sourceStable -or -not $summary.contextStable -or -not $summary.classpathPassed -or -not $summary.inputsStable -or -not $summary.exactIdentityComplete -or $summary.errors -ne 0 -or $summary.skipped -ne 0){throw 'ACTUAL_PREREQUISITE_RECEIPT_NOT_QUALIFIED'}
 if($admission.mode -cne $expectedMode -or $admission.head -cne $manifest.expectedExecutionHead -or $admission.manifestSha256 -cne (Hash (Join-Path $directory 'approved-manifest.json')) -or $admission.runnerSha256 -cne (Hash (Join-Path $directory 'collector.ps1'))){throw 'ACTUAL_PREREQUISITE_ADMISSION_DIFFERS'}
 $priorBefore=Read-Json (Join-Path $directory 'source-before.json');$priorAfter=Read-Json (Join-Path $directory 'source-after.json')
 if(@($priorBefore).Count -ne 152 -or -not(Pin-Rows-Match $priorAfter $priorBefore) -or -not(Pin-Rows-Match $priorManifest.sources $priorBefore)){throw 'ACTUAL_PREREQUISITE_SOURCE_DIFFERS'}
 foreach($name in @('context-before.json','context-after.json')){if(-not(Context-Matches (Read-Json (Join-Path $directory $name)))){throw 'ACTUAL_PREREQUISITE_CONTEXT_DIFFERS'}}
 $priorReport=Read-Ex04FullReports (Join-Path $directory 'xml');$priorRows=@($priorReport.rows)
 $csv=@(Import-Csv -LiteralPath (Join-Path $directory 'methods.csv'))
 if($priorRows.Count -ne 1 -or $csv.Count -ne 1 -or @($priorReport.classes).Count -ne 1 -or @($priorReport.issues).Count -ne 0 -or @(Get-ChildItem -LiteralPath (Join-Path $directory 'xml') -File -Filter 'TEST-*.xml').Count -ne 1){throw 'ACTUAL_PREREQUISITE_XML_INCOMPLETE'}
 $row=$priorRows[0];$priorCsv=$csv[0]
 if((Get-Ex04Identity $row) -cne (Get-Ex04Identity $manifest.expectedMethod) -or (Get-Ex04Identity $row) -cne (Get-Ex04Identity $priorCsv) -or $row.status -cne $expectedStatus -or $row.status -cne $priorCsv.status -or $row.exception_type -cne $priorCsv.exception_type -or $row.message_sha256 -cne $priorCsv.message_sha256 -or $row.detail_sha256 -cne $priorCsv.detail_sha256){throw 'ACTUAL_PREREQUISITE_METHOD_DIFFERS'}
 $xml=Join-Path $directory 'xml/TEST-cn.agentplatform.skill.infra.McpAdapterTest.xml';$priorCp=Read-Classpath $xml
 if(-not $priorCp.passed){throw 'ACTUAL_PREREQUISITE_CLASSPATH_DIFFERS'}
 if($expectedMode -ceq 'Red'){
  $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load($xml);$event=$doc.SelectSingleNode('//testcase/failure')
  if($summary.passed -ne 0 -or $summary.failures -ne 1 -or -not $summary.exactTerminalLateErrorFailure -or -not $summary.expectedBehaviorRed -or $row.exception_type -cne 'org.opentest4j.AssertionFailedError' -or $row.message_sha256 -cne $manifest.expectedMessageSha256 -or $row.first_project_frame -cne $manifest.expectedFirstProjectFrame -or $null -eq $event -or $event.GetAttribute('message') -cne $manifest.expectedMessage -or $event.InnerText.Contains('Suppressed:')){throw 'ACTUAL_RED_NOT_EXACT_TERMINAL_ACK_FAILURE'}
 }elseif($summary.passed -ne 1 -or $summary.failures -ne 0 -or -not $summary.allGreen -or -not(Same-Sources $priorBefore)){throw 'ACTUAL_GREEN_NOT_ONE_PASS_ON_CURRENT_SOURCE'}
 [pscustomobject]@{mode=$expectedMode;directory=$proof.directory;sources=@($priorBefore);manifestSha256=(Hash (Join-Path $directory 'approved-manifest.json'));runnerSha256=(Hash (Join-Path $directory 'collector.ps1'));xmlSha256=(Hash $xml);qualified=$true}
}

try{
 Set-Location -LiteralPath $root
 Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $run 'approved-manifest.json')
 Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $run 'collector.ps1')
 foreach($pin in $manifest.pins){Assert-Pin $pin}
 foreach($pin in $manifest.contextPins){Assert-Pin $pin}
 foreach($pin in $manifest.classpathJarPins){Assert-Pin $pin}
 if(-not(Test-Path -LiteralPath $manifest.maven -PathType Leaf)){throw 'APPROVED_MAVEN_MISSING'}
 foreach($path in $manifest.absentMavenInputs){if(Test-Path -LiteralPath (Full-Path $path)){throw 'UNAPPROVED_MAVEN_INPUT_APPEARED'}}
 . (Join-Path $root $manifest.reportReader)
 $before=Sources;Save 'source-before.json' $before
 if(-not(Same-Sources $before)){throw 'FROZEN_152_SKILL_SOURCES_CHANGED'}
 $red=Verify-ActualRun $manifest.redPrerequisite 'Red'
 Save 'actual-red-prerequisite.json' ([ordered]@{directory=$red.directory;manifestSha256=$red.manifestSha256;runnerSha256=$red.runnerSha256;xmlSha256=$red.xmlSha256;qualified=$true;originalRedUnmodified=$true})
 $oldMap=@{};foreach($source in $red.sources){$oldMap[$source.path]=$source.sha256}
 $delta=@($before|Where-Object{-not $oldMap.ContainsKey($_.path) -or $oldMap[$_.path] -cne $_.sha256}|Select-Object -ExpandProperty path)
 if($oldMap.Count -ne 152 -or $delta.Count -ne 1 -or $delta[0] -cne $manifest.onlyAllowedSourceDelta){throw 'ONLY_TERMINAL_CATCH_PRODUCT_DELTA_ALLOWED'}
 if($Mode -ceq 'Class'){
  $green=Verify-ActualRun $manifest.greenPrerequisite 'Green'
  Save 'actual-green-prerequisite.json' ([ordered]@{directory=$green.directory;manifestSha256=$green.manifestSha256;runnerSha256=$green.runnerSha256;xmlSha256=$green.xmlSha256;qualified=$true;originalGreenUnmodified=$true})
 }
 $baselineXml=Join-Path $run 'baseline-xml';New-Item -ItemType Directory -Path $baselineXml|Out-Null
 Copy-Item -LiteralPath (Join-Path $root $manifest.baselineMcpXml) -Destination $baselineXml
 $old=Read-Ex04FullReports $baselineXml;$oldIdentities=Identity-Map @($old.rows);$declared=Identity-Map @($manifest.originalMethods)
 if($oldIdentities.Count -ne 38 -or $declared.Count -ne 38 -or @($old.issues).Count -ne 0){throw 'ORIGINAL_38_IDENTITIES_INCOMPLETE'}
 foreach($key in $oldIdentities.Keys){if(-not $declared.ContainsKey($key) -or $oldIdentities[$key].status -cne $declared[$key].status){throw 'ORIGINAL_METHOD_TABLE_DIFFERS'}}
 Save 'original-methods.json' @($manifest.originalMethods)
 $expected=@{};$newKey=Get-Ex04Identity $manifest.expectedMethod
 if($oldIdentities.ContainsKey($newKey)){throw 'NEW_METHOD_COLLIDES_WITH_ORIGINAL'}
 if($Mode -ceq 'Class'){foreach($key in $oldIdentities.Keys){$expected[$key]='passed'}}
 $expected[$newKey]='passed';$expectedCount=if($Mode -ceq 'Green'){1}else{39}
 if($expected.Count -ne $expectedCount){throw 'EXPECTED_METHOD_COUNT_DIFFERS'}
 $contextBefore=Context-Facts;Save 'context-before.json' $contextBefore
 if(-not(Context-Matches $contextBefore)){throw 'HEAD_OR_CONTEXT_CHANGED_REFREEZE_REQUIRED'}
 foreach($name in @('MAVEN_OPTS','MAVEN_ARGS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')){if(-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name,'Process'))){throw 'UNAPPROVED_JVM_OR_MAVEN_ENVIRONMENT'}}
 foreach($name in @('JAVA_HOME','PATH','MAVEN_SKIP_RC')){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
 $env:JAVA_HOME=$manifest.jdk;$env:PATH="$env:JAVA_HOME/bin;$env:PATH";$env:MAVEN_SKIP_RC='1'
 $arguments=@('-o','-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$root/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=false',("-Dtest="+$(if($Mode -ceq 'Green'){'McpAdapterTest#closedSessionDoesNotDeliverLateAckFailureToCancelledRpc'}else{'McpAdapterTest'})))
 Save 'admission.json' ([ordered]@{mode=$Mode;head=$contextBefore.head;baselineHead=$manifest.baselineHead;manifestSha256=(Hash $manifestPath);runnerSha256=(Hash $PSCommandPath);sourceCount=152;onlySourceDelta=$delta;oldIdentitiesPreserved=38;selectedNewMethodCount=1;expectedRecords=$expectedCount;originalFull706RegressionGateStillFalse=$true;envFileImported=$false;pgMinioOrExternalHttpInvoked=$false;testUsesOwnedLoopbackPeer=$true;explicitActualWindowGranted=$true})
 $phase='MAVEN';$started=[DateTime]::UtcNow
 Save 'command.json' ([ordered]@{command=$manifest.maven;arguments=$arguments;startedAt=$started.ToString('o');head=$contextBefore.head;failureIgnore=$false;offline=$true;defaultForkReuseAndOrder=$true;skipUserMavenRc=$true;cleanBuildInstallOrAm=$false;testBodyDeadlineMs=800;testIncludingFinallyDeadlineMs=1800;unchangedAfterEachDeadlineMs=2000})
 try{$ErrorActionPreference='Continue';& $manifest.maven @arguments *> (Join-Path $run 'raw.log');$native=$LASTEXITCODE}
 finally{$ErrorActionPreference='Stop';Save 'native-exit.json' ([ordered]@{nativeExit=$native;startedAt=$started.ToString('o');endedAt=[DateTime]::UtcNow.ToString('o');failureIgnore=$false;nativeRecordedBeforeReportParsing=$true})}
 $phase='ARCHIVE'
 $after=Sources;Save 'source-after.json' $after;$sourceStable=Same-Sources $after
 $contextAfter=Context-Facts;Save 'context-after.json' $contextAfter;$contextStable=Context-Matches $contextAfter
 $xml=Join-Path $run 'xml';New-Item -ItemType Directory -Path $xml|Out-Null
 $fresh=@(Get-ChildItem -LiteralPath (Join-Path $root 'skill-registry/target/surefire-reports') -File -Filter 'TEST-*.xml'|Where-Object{$_.LastWriteTimeUtc -ge $started})
 foreach($file in $fresh){Copy-Item -LiteralPath $file.FullName -Destination $xml}
 Save 'xml-manifest.json' @($fresh|ForEach-Object{[pscustomobject]@{file=$_.Name;bytes=$_.Length;mtimeUtc=$_.LastWriteTimeUtc.ToString('o');sha256=(Hash (Join-Path $xml $_.Name))}})
 Save 'raw-log-manifest.json' ([ordered]@{path='raw.log';bytes=(Get-Item -LiteralPath (Join-Path $run 'raw.log')).Length;sha256=(Hash (Join-Path $run 'raw.log'));unmodified=$true})
 $report=Read-Ex04FullReports $xml;$rows=@($report.rows)
 $rows|Export-Csv -LiteralPath (Join-Path $run 'methods.csv') -NoTypeInformation -Encoding UTF8
 Save 'xml-issues.json' @($report.issues)
 $actual=Identity-Map $rows
 $missing=@($expected.Keys|Where-Object{-not $actual.ContainsKey($_)});$extra=@($actual.Keys|Where-Object{-not $expected.ContainsKey($_)})
 $complete=$fresh.Count -eq 1 -and $rows.Count -eq $expectedCount -and @($report.classes).Count -eq 1 -and $report.classes[0] -ceq $manifest.expectedMethod.classname -and @($report.issues).Count -eq 0 -and $missing.Count -eq 0 -and $extra.Count -eq 0
 $classpathOkay=$false
 if($fresh.Count -eq 1){$cp=Read-Classpath (Join-Path $xml $fresh[0].Name);Save 'classpath-facts.json' $cp;$classpathOkay=$cp.passed}
 $transitions=@(foreach($key in $expected.Keys){$a=$actual[$key];[pscustomobject]@{identity=$key;expected='passed';actual=if($a){$a.status}else{'MISSING'};passed=($null -ne $a -and $a.status -ceq 'passed')}})
 Save 'method-transitions.json' $transitions
 $inputsStable=(Hash $manifestPath) -ceq $ApprovedManifestSha256.ToUpperInvariant() -and (Hash $PSCommandPath) -ceq $manifest.runnerSha256
 foreach($pin in $manifest.pins){Assert-Pin $pin};foreach($pin in $manifest.redPrerequisite.pins){Assert-Pin $pin}
 if($Mode -ceq 'Class'){foreach($pin in $manifest.greenPrerequisite.pins){Assert-Pin $pin}}
 $failures=@($rows|Where-Object status -ceq 'failure').Count;$errors=@($rows|Where-Object status -ceq 'error').Count;$skipped=@($rows|Where-Object status -ceq 'skipped').Count
 $allGreen=$native -eq 0 -and $complete -and @($transitions|Where-Object{-not $_.passed}).Count -eq 0 -and $failures -eq 0 -and $errors -eq 0 -and $skipped -eq 0
 $gate=$allGreen -and $sourceStable -and $contextStable -and $classpathOkay -and $inputsStable
 Save 'summary.json' ([ordered]@{mode=$Mode;nativeExit=$native;records=$rows.Count;freshXml=$fresh.Count;expectedRecords=$expectedCount;passed=@($rows|Where-Object status -ceq 'passed').Count;failures=$failures;errors=$errors;skipped=$skipped;missing=$missing;extra=$extra;exactIdentityComplete=$complete;originalMethodsPassed=@($oldIdentities.Keys|Where-Object{$actual.ContainsKey($_) -and $actual[$_].status -ceq 'passed'}).Count;newMethodPassed=($actual.ContainsKey($newKey) -and $actual[$newKey].status -ceq 'passed');actualRedVerified=$true;actualGreenVerified=($Mode -ceq 'Class');classpathPassed=$classpathOkay;sourceStable=$sourceStable;contextStable=$contextStable;inputsStable=$inputsStable;gatePassed=$gate;failureIgnore=$false;old38NotRunInThisTarget=($Mode -ceq 'Green');originalFull706RegressionGateStillFalse=$true;allGreen=$allGreen;allGreenAppliesOnlyToSelectedMode=$true;cardClosed=$false})
 $collector=if($gate){0}else{1}
 Write-Output ('EX27_MCP_TERMINAL mode='+$Mode+' native='+$native+' records='+$rows.Count+' allGreen='+$allGreen+' gate='+$gate)
}catch{
 $reason=if($_.Exception.Message -cmatch '\A[A-Z_0-9]+\z'){$_.Exception.Message}else{'UNEXPECTED_COLLECTOR_ERROR'}
 Save 'collector-error.json' ([ordered]@{phase=$phase;reason=$reason;scriptLine=$_.InvocationInfo.ScriptLineNumber;exceptionClass=$_.Exception.GetType().FullName;nativeExit=$native;unexpectedCollectorFailureNotTestVerdict=$true})
 Write-Output ('EX27_MCP_TERMINAL_STOPPED phase='+$phase+' line='+$_.InvocationInfo.ScriptLineNumber)
}finally{
 foreach($name in $prior.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}
 Set-Location -LiteralPath $location
 Save 'collector-exit.json' ([ordered]@{nativeExit=$native;collectorExit=$collector;rawEvidencePreserved=$true;noAutomaticRerunOrFix=$true})
}
exit $collector
