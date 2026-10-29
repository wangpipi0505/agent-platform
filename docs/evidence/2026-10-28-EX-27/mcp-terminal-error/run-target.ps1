# Targeted RED only. A sealed manifest and the owner's actual runtime window are both required.
param(
 [Parameter(Mandatory=$true)][ValidatePattern('\A[a-z0-9-]+\z')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('\A[A-Fa-f0-9]{64}\z')][string]$ApprovedManifestSha256,
 [ValidateSet('Red','Green','Class')][string]$Mode='Red',
 [switch]$ExecuteApproved
)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -ne 5){throw 'EXPLICIT_WINDOWS_POWERSHELL_5_REQUIRED'}
if(-not $ExecuteApproved){throw 'EXCLUSIVE_ACTUAL_WINDOW_NOT_GRANTED'}
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$manifestPath=Join-Path $PSScriptRoot 'manifest.json'
if((Get-FileHash -LiteralPath $manifestPath).Hash -cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'APPROVED_MANIFEST_CHANGED'}
$manifest=[IO.File]::ReadAllText($manifestPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
if($Mode -cne 'Red' -or -not $manifest.modes.Red.sealed -or -not $manifest.executionHeadSealed -or $manifest.expectedExecutionHead -cnotmatch '\A[a-f0-9]{40}\z'){throw 'MODE_OR_EXECUTION_HEAD_NOT_SEALED'}
if($manifest.modes.Green.sealed -or $manifest.modes.Class.sealed){throw 'THIS_COLLECTOR_HAS_NO_GREEN_OR_CLASS_IMPLEMENTATION'}
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
 $baseline=[IO.File]::ReadAllText((Join-Path $root $manifest.baselineSourceBefore),[Text.Encoding]::UTF8)|ConvertFrom-Json
 $baselineAfter=[IO.File]::ReadAllText((Join-Path $root $manifest.baselineSourceAfter),[Text.Encoding]::UTF8)|ConvertFrom-Json
 if(-not(Pin-Rows-Match $baselineAfter $baseline) -or @($baseline).Count -ne 152){throw 'FULL706_SOURCE_BASELINE_INCOMPLETE'}
 $oldMap=@{};foreach($s in $baseline){$oldMap[$s.path]=$s.sha256}
 $delta=@($before|Where-Object{-not $oldMap.ContainsKey($_.path) -or $oldMap[$_.path] -cne $_.sha256}|Select-Object -ExpandProperty path)
 if($delta.Count -ne 1 -or $delta[0] -cne $manifest.onlyAllowedSourceDelta){throw 'ONLY_NEW_MCP_TEST_DELTA_ALLOWED'}
 $baselineXml=Join-Path $run 'baseline-xml';New-Item -ItemType Directory -Path $baselineXml|Out-Null
 Copy-Item -LiteralPath (Join-Path $root $manifest.baselineMcpXml) -Destination $baselineXml
 $old=Read-Ex04FullReports $baselineXml;$oldIdentities=Identity-Map @($old.rows);$declared=Identity-Map @($manifest.originalMethods)
 if($oldIdentities.Count -ne 38 -or $declared.Count -ne 38 -or @($old.issues).Count -ne 0){throw 'ORIGINAL_38_IDENTITIES_INCOMPLETE'}
 foreach($key in $oldIdentities.Keys){if(-not $declared.ContainsKey($key) -or $oldIdentities[$key].status -cne $declared[$key].status){throw 'ORIGINAL_METHOD_TABLE_DIFFERS'}}
 Save 'original-methods.json' @($manifest.originalMethods)
 $contextBefore=Context-Facts;Save 'context-before.json' $contextBefore
 if(-not(Context-Matches $contextBefore)){throw 'HEAD_OR_CONTEXT_CHANGED_REFREEZE_REQUIRED'}
 foreach($name in @('MAVEN_OPTS','MAVEN_ARGS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH')){if(-not [string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($name,'Process'))){throw 'UNAPPROVED_JVM_OR_MAVEN_ENVIRONMENT'}}
 foreach($name in @('JAVA_HOME','PATH','MAVEN_SKIP_RC')){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
 $env:JAVA_HOME=$manifest.jdk;$env:PATH="$env:JAVA_HOME/bin;$env:PATH";$env:MAVEN_SKIP_RC='1'
 $arguments=@('-o','-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$root/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=false','-Dtest=McpAdapterTest#closedSessionDoesNotDeliverLateAckFailureToCancelledRpc')
 Save 'admission.json' ([ordered]@{mode='Red';head=$contextBefore.head;baselineHead=$manifest.baselineHead;manifestSha256=(Hash $manifestPath);runnerSha256=(Hash $PSCommandPath);sourceCount=152;onlySourceDelta=$delta;oldIdentitiesPreserved=38;selectedNewMethodCount=1;originalFull706RegressionGateStillFalse=$true;envFileImported=$false;pgMinioOrExternalHttpInvoked=$false;testUsesOwnedLoopbackPeer=$true;explicitActualWindowGranted=$true})
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
 $complete=$fresh.Count -eq 1 -and $rows.Count -eq 1 -and @($report.classes).Count -eq 1 -and $report.classes[0] -ceq $manifest.expectedMethod.classname -and @($report.issues).Count -eq 0
 $reasonOkay=$false;$classpathOkay=$false;$identityOkay=$false
 if($complete){
  $row=$rows[0];$identityOkay=(Get-Ex04Identity $row) -ceq (Get-Ex04Identity $manifest.expectedMethod)
  $file=Join-Path $xml $fresh[0].Name;$cp=Read-Classpath $file;Save 'classpath-facts.json' $cp;$classpathOkay=$cp.passed
  $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load($file)
  $event=$doc.SelectSingleNode('//testcase/failure')
  $reasonOkay=$identityOkay -and $row.status -ceq 'failure' -and $row.exception_type -ceq 'org.opentest4j.AssertionFailedError' -and $row.message_sha256 -ceq $manifest.expectedMessageSha256 -and $row.first_project_frame -ceq $manifest.expectedFirstProjectFrame -and $null -ne $event -and $event.GetAttribute('message') -ceq $manifest.expectedMessage -and -not $event.InnerText.Contains('Suppressed:')
 }
 Save 'expected-red-reason.json' ([ordered]@{token=$manifest.expectedToken;expected=0;actualRequired=1;exactAssertionMatched=$reasonOkay;fixtureDeadlineCleanupCompileOrClasspathFailureIsNotProductRed=$true;onErrorDroppedNotRequiredForRed=$true})
 $inputsStable=(Hash $manifestPath) -ceq $ApprovedManifestSha256.ToUpperInvariant() -and (Hash $PSCommandPath) -ceq $manifest.runnerSha256
 foreach($pin in $manifest.pins){Assert-Pin $pin}
 $behaviorRed=$native -eq 1 -and $complete -and $identityOkay -and $reasonOkay -and @($rows|Where-Object status -ceq 'error').Count -eq 0 -and @($rows|Where-Object status -ceq 'skipped').Count -eq 0
 $gate=$behaviorRed -and $sourceStable -and $contextStable -and $classpathOkay -and $inputsStable
 Save 'summary.json' ([ordered]@{mode='Red';nativeExit=$native;records=$rows.Count;freshXml=$fresh.Count;passed=@($rows|Where-Object status -ceq 'passed').Count;failures=@($rows|Where-Object status -ceq 'failure').Count;errors=@($rows|Where-Object status -ceq 'error').Count;skipped=@($rows|Where-Object status -ceq 'skipped').Count;exactIdentityComplete=($complete -and $identityOkay);exactTerminalLateErrorFailure=$reasonOkay;expectedBehaviorRed=$behaviorRed;classpathPassed=$classpathOkay;sourceStable=$sourceStable;contextStable=$contextStable;inputsStable=$inputsStable;gatePassed=$gate;failureIgnore=$false;collectorZeroMeansExpectedRedOnly=$true;old38NotRunInThisTarget=$true;originalFull706RegressionGateStillFalse=$true;allGreen=$false;cardClosed=$false})
 $collector=if($gate){0}else{1}
 Write-Output ('EX27_MCP_TERMINAL_RED native='+$native+' records='+$rows.Count+' expectedRed='+$behaviorRed+' gate='+$gate)
}catch{
 $reason=if($_.Exception.Message -cmatch '\A[A-Z_0-9]+\z'){$_.Exception.Message}else{'UNEXPECTED_COLLECTOR_ERROR'}
 Save 'collector-error.json' ([ordered]@{phase=$phase;reason=$reason;scriptLine=$_.InvocationInfo.ScriptLineNumber;exceptionClass=$_.Exception.GetType().FullName;nativeExit=$native;unexpectedCollectorFailureNotProductRed=$true})
 Write-Output ('EX27_MCP_TERMINAL_STOPPED phase='+$phase+' line='+$_.InvocationInfo.ScriptLineNumber)
}finally{
 foreach($name in $prior.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}
 Set-Location -LiteralPath $location
 Save 'collector-exit.json' ([ordered]@{nativeExit=$native;collectorExit=$collector;rawEvidencePreserved=$true;noAutomaticRerunOrFix=$true})
}
exit $collector
