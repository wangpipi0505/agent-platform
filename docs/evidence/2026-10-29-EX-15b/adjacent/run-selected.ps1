param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ApprovedManifestSha256,
 [switch]$ExecuteApproved
)
$ErrorActionPreference='Stop'
if(-not $ExecuteApproved){throw 'EXCLUSIVE_ACTUAL_WINDOW_NOT_GRANTED'}
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$taskRun=Join-Path $PSScriptRoot ('runs/'+$RunId)
$manifestPath=Join-Path $PSScriptRoot 'manifest.json'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash}
function Read-Json([string]$Path){$value=[IO.File]::ReadAllText($Path,[Text.Encoding]::UTF8)|ConvertFrom-Json;return $value}
if((Hash $manifestPath) -cne $ApprovedManifestSha256.ToUpperInvariant()){throw 'APPROVED_MANIFEST_CHANGED'}
if(Test-Path -LiteralPath $taskRun){throw 'RUN_DIRECTORY_ALREADY_EXISTS'}
$manifest=Read-Json $manifestPath
if((Hash $PSCommandPath) -cne $manifest.collectorSha256){throw 'APPROVED_COLLECTOR_CHANGED'}
function Write-Fact([string]$Name,$Value){
 [IO.File]::WriteAllText((Join-Path $taskRun $Name),(ConvertTo-Json -InputObject $Value -Depth 14),(New-Object Text.UTF8Encoding($false)))
}
function Sources {
 $files=@(foreach($path in @('admin-common/src','skill-registry/src')){Get-ChildItem -LiteralPath (Join-Path $taskRoot $path) -Recurse -File})
 $files+=@(foreach($path in @('pom.xml','admin-common/pom.xml','skill-registry/pom.xml')){Get-Item -LiteralPath (Join-Path $taskRoot $path)})
 @($files|Sort-Object FullName|ForEach-Object{[pscustomobject]@{path=$_.FullName.Substring($taskRoot.Length+1).Replace([char]92,[char]47);sha256=(Hash $_.FullName)}})
}
function Same-Pins($Left,$Right){
 $map=@{};foreach($pin in $Left){if($map.ContainsKey($pin.path)){return $false};$map[$pin.path]=$pin.sha256}
 return @($Left).Count -eq @($Right).Count -and @($Right|Where-Object{-not $map.ContainsKey($_.path) -or $map[$_.path] -cne $_.sha256}).Count -eq 0
}
function Head {
 $value=((& git -C $taskRoot rev-parse HEAD)-join '')
 if($LASTEXITCODE -ne 0 -or $value -cnotmatch '^[a-f0-9]{40}$'){throw 'HEAD_UNAVAILABLE'}
 return $value
}
function Context {
 $pins=@(foreach($pin in $manifest.contextPins){
  $full=if([IO.Path]::IsPathRooted($pin.path)){$pin.path}else{Join-Path $taskRoot $pin.path}
  [pscustomobject]@{path=$pin.path;sha256=(Hash $full)}
 })
 [pscustomobject]@{head=(Head);pins=$pins}
}
function Private-NewDirectory([string]$Path){
 [IO.Directory]::CreateDirectory($Path)|Out-Null
 $acl=New-Object Security.AccessControl.DirectorySecurity
 $acl.SetAccessRuleProtection($true,$false)
 $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
 $system=New-Object Security.Principal.SecurityIdentifier('S-1-5-18')
 $acl.SetOwner($sid)
 foreach($identity in @($sid,$system)){
  $rule=New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
  [void]$acl.AddAccessRule($rule)
 }
 Set-Acl -LiteralPath $Path -AclObject $acl
}
$prior=@{};$location=Get-Location;$phase='ADMISSION';$native=$null;$collector=2;$before=$null;$contextBefore=$null
Private-NewDirectory $taskRun
try {
 Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $taskRun 'approved-manifest.json')
 Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $taskRun 'collector.ps1')
 foreach($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS')){
  if(-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name,'Process'))){throw 'UNAPPROVED_JVM_OPTION_ENVIRONMENT'}
 }
 $before=Sources;Write-Fact 'source-before.json' $before
 if(-not(Same-Pins $manifest.sources $before)){throw 'SOURCE_CANDIDATE_DRIFT'}
 if(@($before|Where-Object path -like 'skill-registry/src/*').Count -ne 151){throw 'EXPECTED_151_SKILL_SOURCES'}
 $contextBefore=Context;Write-Fact 'context-before.json' $contextBefore
 if(-not(Same-Pins $manifest.contextPins $contextBefore.pins)){throw 'CONTEXT_PIN_DRIFT'}
 $baselinePath=Join-Path $taskRoot $manifest.baselineCsv
 if((Hash $baselinePath) -cne $manifest.baselineCsvSha256){throw 'BASELINE_CSV_CHANGED'}
 $baseline=@(Import-Csv -LiteralPath $baselinePath|Where-Object{$manifest.originalClasses -ccontains $_.classname})
 if($baseline.Count -ne 81 -or @($baseline|Where-Object{$_.status -cne 'passed' -or $_.record_kind -cne 'NAMED_CASE' -or $_.display_name -or $_.suite_path -cne $_.classname}).Count -ne 0){throw 'ORIGINAL_81_IDENTITIES_REQUIRED'}
 $expected=@($manifest.expectedMethods|ForEach-Object{$_.classname+'#'+$_.method})
 $original=@($baseline|ForEach-Object{$_.classname+'#'+$_.method})
 $new=@('cn.agentplatform.skill.infra.ProcessFilesTest#acceptsValidEnvelopeWithoutOutputFiles','cn.agentplatform.skill.infra.ProcessFilesTest#rejectsParentRelativeOutputPathBeforeReturningSuccess')
 if($expected.Count -ne 83 -or @($expected|Select-Object -Unique).Count -ne 83 -or @(Compare-Object ($original+$new) $expected -CaseSensitive).Count -ne 0 -or @($manifest.classes).Count -ne 10){throw 'EXPECTED_83_METHOD_SET_CHANGED'}
 if(@($manifest.classes|Where-Object{$_ -cnotmatch '^cn\.agentplatform\.skill\.(infra|application|domain)\.[A-Za-z]+Test$'}).Count -ne 0 -or @(Compare-Object (@($manifest.expectedMethods|Select-Object -ExpandProperty classname -Unique)) @($manifest.classes) -CaseSensitive).Count -ne 0){throw 'CLASS_SELECTION_CHANGED'}
 foreach($name in @('JAVA_HOME','PATH')){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
 $env:JAVA_HOME=$manifest.jdk;$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 $maven=(Get-Command mvn.cmd -CommandType Application -ErrorAction Stop).Source
 if($maven -cne $manifest.mavenPath -or (Hash $maven) -cne $manifest.mavenSha256){throw 'MAVEN_LAUNCHER_CHANGED'}
 Set-Location -LiteralPath $taskRoot
 $arguments=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=false',('-Dtest='+($manifest.classes -join ',')))
 $started=[DateTime]::UtcNow
 Write-Fact 'command.json' ([ordered]@{head=$contextBefore.head;arguments=$arguments;startedAtUtc=$started.ToString('o');powershell=$PSVersionTable.PSVersion.ToString();environmentImported=$false;databaseAccess=$false;minioAccess=$false;serviceStartup=$false;failureIgnore=$false;expectedXml=10;expectedMethods=83;deferredPgMethods=3;defaultForkAndOrder=$true})
 $phase='MAVEN'
 $ErrorActionPreference='Continue'
 & $maven @arguments > (Join-Path $taskRun 'maven.raw.log') 2>&1
 $native=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 Write-Fact 'native-exit.json' ([ordered]@{nativeExit=$native;endedAtUtc=[DateTime]::UtcNow.ToString('o')})
 $phase='XML_COLLECTION'
 $reportRoot=Join-Path $taskRoot 'skill-registry/target/surefire-reports'
 $xmlRoot=Join-Path $taskRun 'xml';[IO.Directory]::CreateDirectory($xmlRoot)|Out-Null
 $fresh=@(Get-ChildItem -LiteralPath $reportRoot -Filter 'TEST-*.xml' -File|Where-Object LastWriteTimeUtc -ge $started)
 $expectedFiles=@($manifest.classes|ForEach-Object{'TEST-'+$_+'.xml'})
 $freshSet=$fresh.Count -eq 10 -and @(Compare-Object $expectedFiles @($fresh|Select-Object -ExpandProperty Name) -CaseSensitive).Count -eq 0
 $rows=New-Object 'System.Collections.Generic.List[object]';$xmlPins=New-Object 'System.Collections.Generic.List[object]'
 $suiteGate=$true
 foreach($class in $manifest.classes){
  $file='TEST-'+$class+'.xml';$source=Join-Path $reportRoot $file
  if(-not(Test-Path -LiteralPath $source -PathType Leaf) -or (Get-Item -LiteralPath $source).LastWriteTimeUtc -lt $started){$suiteGate=$false;continue}
  $copy=Join-Path $xmlRoot $file;Copy-Item -LiteralPath $source -Destination $copy
  $xmlPins.Add([pscustomobject]@{file=('xml/'+$file);bytes=(Get-Item -LiteralPath $copy).Length;sha256=(Hash $copy)})
  $doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load($copy)
  $suites=@($doc.SelectNodes('/testsuite'));$cases=@($doc.SelectNodes('/testsuite/testcase'))
  $classCount=@($manifest.expectedMethods|Where-Object classname -ceq $class).Count
  if($suites.Count -ne 1 -or $suites[0].GetAttribute('name') -cne $class -or $cases.Count -ne $classCount -or [int]$suites[0].GetAttribute('tests') -ne $classCount -or [int]$suites[0].GetAttribute('failures') -ne 0 -or [int]$suites[0].GetAttribute('errors') -ne 0 -or [int]$suites[0].GetAttribute('skipped') -ne 0){$suiteGate=$false}
  foreach($case in $cases){
   $failure=$case.SelectSingleNode('./failure');$errorNode=$case.SelectSingleNode('./error');$skip=$case.SelectSingleNode('./skipped')
   $status=if($failure){'FAIL'}elseif($errorNode){'ERROR'}elseif($skip){'SKIP'}else{'PASS'}
   if($case.GetAttribute('classname') -cne $class -or $case.GetAttribute('displayName')){$suiteGate=$false}
   $rows.Add([pscustomobject]@{classname=$case.GetAttribute('classname');method=$case.GetAttribute('name');status=$status;xml=('xml/'+$file)})
  }
 }
 $methods=$rows.ToArray();Write-Fact 'methods.json' $methods;Write-Fact 'xml-manifest.json' ($xmlPins.ToArray())
 $actual=@($methods|ForEach-Object{$_.classname+'#'+$_.method})
 $identityGate=$actual.Count -eq 83 -and @($actual|Select-Object -Unique).Count -eq 83 -and @(Compare-Object $expected $actual -CaseSensitive).Count -eq 0
 $pass=@($methods|Where-Object status -ceq 'PASS').Count;$fail=@($methods|Where-Object status -ceq 'FAIL').Count
 $errors=@($methods|Where-Object status -ceq 'ERROR').Count;$skipped=@($methods|Where-Object status -ceq 'SKIP').Count
 $after=Sources;Write-Fact 'source-after.json' $after
 $contextAfter=Context;Write-Fact 'context-after.json' $contextAfter
 $sourceStable=Same-Pins $before $after
 $contextStable=$contextBefore.head -ceq $contextAfter.head -and (Same-Pins $contextBefore.pins $contextAfter.pins)
 $passed=$native -eq 0 -and $freshSet -and $suiteGate -and $identityGate -and $pass -eq 83 -and $fail -eq 0 -and $errors -eq 0 -and $skipped -eq 0 -and $sourceStable -and $contextStable
 Write-Fact 'summary.json' ([ordered]@{nativeExit=$native;tests=$methods.Count;passed=$pass;failures=$fail;errors=$errors;skipped=$skipped;freshXml=$fresh.Count;freshXmlSet=$freshSet;suiteGate=$suiteGate;identityGate=$identityGate;sourceStable=$sourceStable;contextStable=$contextStable;regressionGate=$passed;originalMethods=81;newMethods=2;deferredPgMethods=3;wholeCardComplete=$false;mavenLogSha256=(Hash (Join-Path $taskRun 'maven.raw.log'))})
 $phase='COMPLETE';if($passed){$collector=0}
 Get-Content -LiteralPath (Join-Path $taskRun 'summary.json') -Encoding UTF8
} catch {
 # Raw collector exception remains in this new private run; public receipt has only phase/native state.
 [IO.File]::WriteAllText((Join-Path $taskRun 'collector-error.raw.txt'),($_|Out-String),(New-Object Text.UTF8Encoding($false)))
 Write-Fact 'collection-error.json' ([ordered]@{phase=$phase;nativeMavenExit=$native;notAutomaticallyProductFailure=$true;error='COLLECTION_FAILED_SEE_PRIVATE_RAW'})
} finally {
 try {
  if(-not(Test-Path -LiteralPath (Join-Path $taskRun 'source-after.json'))){Write-Fact 'source-after.json' (Sources)}
  if(-not(Test-Path -LiteralPath (Join-Path $taskRun 'context-after.json'))){Write-Fact 'context-after.json' (Context)}
 } catch {$collector=2}
 foreach($name in $prior.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}
 Set-Location -LiteralPath $location
 Write-Fact 'collector-exit.json' ([ordered]@{collectorExit=$collector;nativeMavenExit=$native;phase=$phase})
}
exit $collector
