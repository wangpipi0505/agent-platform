param([Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$run=Join-Path $PSScriptRoot $RunId
if(Test-Path -LiteralPath $run){throw 'Run already exists'}
$test='skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessFilesTest.java'
$adapter='skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessAdapter.java'
if((Get-FileHash (Join-Path $root $test)).Hash -ne '7B363256F026266C5A65D4C85E2BEB79D4AF3DADDC497AC1831E30834868A7D7'){throw 'Test candidate drift'}
if((Get-FileHash (Join-Path $root $adapter)).Hash -ne '5A0C87FD88EECE89C2B5A2F85C95B599C59124468830E5A500C734A935118E80'){throw 'Fixed adapter drift'}
foreach($name in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS')){
 if(-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name,'Process'))){throw "Inherited JVM option: $name"}
}
[IO.Directory]::CreateDirectory($run)|Out-Null
function Fact([string]$Name,[object]$Value){
 [IO.File]::WriteAllText((Join-Path $run $Name),(ConvertTo-Json -InputObject $Value -Depth 12),(New-Object Text.UTF8Encoding($false)))
}
function Sources {
 $files=@(foreach($p in @('admin-common/src','skill-registry/src')){Get-ChildItem -LiteralPath (Join-Path $root $p) -Recurse -File})
 $files+=@(foreach($p in @('pom.xml','admin-common/pom.xml','skill-registry/pom.xml')){Get-Item -LiteralPath (Join-Path $root $p)})
 @($files|Sort-Object FullName|ForEach-Object{[pscustomobject]@{path=$_.FullName.Substring($root.Length+1).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName).Hash}})
}
$before=Sources
Fact 'source-before.json' $before
$prior=@{};$location=Get-Location;$native=2;$collector=2
$common=Join-Path $root '.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar'
$headBefore=((& git -C $root rev-parse HEAD)-join '')
$commonBefore=(Get-FileHash $common).Hash
$envBefore=(Get-FileHash (Join-Path $root '.env')).Hash
try {
 foreach($name in @('JAVA_HOME','PATH')){$prior[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
 $env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
 $env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 Set-Location -LiteralPath $root
 # Pure process protocol cases: no .env import, no PG/MinIO/HTTP and no service startup.
 $arguments=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$root/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=false','-Dtest=ProcessFilesTest')
 $started=[DateTime]::UtcNow
 Fact 'command.json' ([ordered]@{head=$headBefore;arguments=$arguments;startedAtUtc=$started.ToString('o');powershellVersion=$PSVersionTable.PSVersion.ToString();collectorSha256=(Get-FileHash $PSCommandPath).Hash;javaHome=$env:JAVA_HOME;javaSha256=(Get-FileHash "$env:JAVA_HOME/bin/java.exe").Hash;commonJarSha256=$commonBefore;envSha256=$envBefore;environmentImported=$false;failureIgnore=$false;resourceScope='Two pure JDK workers, own per-case Chinese/space temp roots. Hostile path is JSON only; no PG, MinIO, HTTP, external files or service processes.'})
 $ErrorActionPreference='Continue'
 & mvn.cmd @arguments > (Join-Path $run 'maven.log') 2>&1
 $native=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 Fact 'native-exit.json' ([ordered]@{nativeExit=$native;endedAtUtc=[DateTime]::UtcNow.ToString('o')})
 $report=Join-Path $root 'skill-registry/target/surefire-reports/TEST-cn.agentplatform.skill.infra.ProcessFilesTest.xml'
 if(-not(Test-Path $report) -or (Get-Item $report).LastWriteTimeUtc -lt $started){throw 'Fresh XML missing; not a verified green'}
 Copy-Item -LiteralPath $report -Destination (Join-Path $run 'methods.xml')
 $xml=New-Object Xml.XmlDocument;$xml.XmlResolver=$null;$xml.Load($report)
 $methods=@($xml.SelectNodes('//testcase')|ForEach-Object{
  $f=$_.SelectSingleNode('failure');$e=$_.SelectSingleNode('error');$s=$_.SelectSingleNode('skipped')
  [pscustomobject]@{class=$_.classname;method=$_.name;status=$(if($f){'FAIL'}elseif($e){'ERROR'}elseif($s){'SKIP'}else{'PASS'});type=$(if($f){$f.type}elseif($e){$e.type}else{''});message=$(if($f){$f.message}elseif($e){$e.message}else{''})}
 })
 Fact 'methods.json' $methods
 $expected=@('acceptsValidEnvelopeWithoutOutputFiles','rejectsParentRelativeOutputPathBeforeReturningSuccess')
 $identities=($methods.Count -eq 2 -and @($methods|Where-Object class -ne 'cn.agentplatform.skill.infra.ProcessFilesTest').Count -eq 0 -and @(Compare-Object $expected $methods.method).Count -eq 0)
 $pass=@($methods|Where-Object status -eq PASS).Count;$fail=@($methods|Where-Object status -eq FAIL).Count
 $errorCount=@($methods|Where-Object status -eq ERROR).Count;$skip=@($methods|Where-Object status -eq SKIP).Count
 $behaviorGreen=$identities -and $native -eq 0 -and $pass -eq 2 -and $fail -eq 0 -and $errorCount -eq 0 -and $skip -eq 0
 $after=Sources;Fact 'source-after.json' $after
 $sourceStable=(ConvertTo-Json -InputObject $before -Compress) -ceq (ConvertTo-Json -InputObject $after -Compress)
 $contextStable=$headBefore -ceq ((& git rev-parse HEAD)-join '') -and $commonBefore -eq (Get-FileHash $common).Hash -and $envBefore -eq (Get-FileHash '.env').Hash
 Fact 'summary.json' ([ordered]@{nativeExit=$native;tests=$methods.Count;passed=$pass;failures=$fail;errors=$errorCount;skipped=$skip;identityComplete=$identities;actualBehaviorGreen=$behaviorGreen;sourceStable=$sourceStable;contextStable=$contextStable;xmlSha256=(Get-FileHash (Join-Path $run 'methods.xml')).Hash;minimalParentSegmentFix=$true})
 if(-not $sourceStable -or -not $contextStable){throw 'Execution context drift'}
 if($behaviorGreen){$collector=0}
 Get-Content -LiteralPath (Join-Path $run 'summary.json') -Encoding UTF8
} catch {
 Fact 'collection-error.json' ([ordered]@{message=$_.Exception.Message;nativeExit=$native;notAutomaticallyProductRed=$true})
 throw
} finally {
 foreach($name in $prior.Keys){[Environment]::SetEnvironmentVariable($name,$prior[$name],'Process')}
 Set-Location -LiteralPath $location
 if(-not(Test-Path (Join-Path $run 'source-after.json'))){Fact 'source-after.json' (Sources)}
 Fact 'collector-exit.json' ([ordered]@{collectorExit=$collector;nativeMavenExit=$native})
}
exit $collector
