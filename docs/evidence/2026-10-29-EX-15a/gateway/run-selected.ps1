param(
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId,
 [ValidateSet('red','green')][string]$Phase='red'
)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$taskRun=Join-Path $PSScriptRoot $RunId
if(Test-Path -LiteralPath $taskRun){throw 'Run directory already exists'}
foreach($key in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','MAVEN_OPTS')){
 if(-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($key,'Process'))){
  throw "Unreviewed inherited JVM options are present in $key"
 }
}
$controller='platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java'
$test='platform-gateway/src/test/java/cn/agentplatform/gateway/ArtifactUploadGatewayTest.java'
$oldControllerSha='D920EA867FF02F0433AA65BC33540B7CA17A9799F2CB910BE95134EDD756AE2B'
if($Phase -eq 'red' -and (Get-FileHash -LiteralPath (Join-Path $taskRoot $controller)).Hash -cne $oldControllerSha){
 throw 'Red collection requires the original 2 MiB GatewayController fingerprint'
}
New-Item -ItemType Directory -Path $taskRun | Out-Null
function Write-Fact($Name,$Value){
 [IO.File]::WriteAllText((Join-Path $taskRun $Name),(ConvertTo-Json -InputObject $Value -Depth 8),(New-Object Text.UTF8Encoding($false)))
}
function Source-Facts {
 $files=@(foreach($part in @('admin-common/src','platform-gateway/src')){
  Get-ChildItem -LiteralPath (Join-Path $taskRoot $part) -Recurse -File
 })
 $files+=@(foreach($part in @('pom.xml','admin-common/pom.xml','platform-gateway/pom.xml')){
  Get-Item -LiteralPath (Join-Path $taskRoot $part)
 })
 @($files | Sort-Object FullName | ForEach-Object {
  [pscustomobject]@{path=$_.FullName.Substring($taskRoot.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
 })
}
$before=Source-Facts
Write-Fact 'source-before.json' $before
$expected=@([regex]::Matches([IO.File]::ReadAllText((Join-Path $taskRoot $test)),'@Test\s+void\s+(\w+)\s*\(') | ForEach-Object {$_.Groups[1].Value} | Sort-Object)
if($expected.Count -ne 11){throw 'Reviewed test method inventory changed'}
Write-Fact 'expected-methods.json' $expected
$redMethods=@('forwards64MiBMultipartWithKnownLengthAndVerifiedInternalIdentity','forwards64MiBMultipartWithoutContentLength','acceptsExactly65MiBTotalWireWithKnownLength','acceptsExactly65MiBTotalWireWithoutContentLength') | Sort-Object
$prior=@{};$location=Get-Location;$native=2;$headAtStart=$null
$adminJar=Join-Path $taskRoot '.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar'
try {
 foreach($key in @('JAVA_HOME','PATH','AGENT_JWT_SECRET')){
  $prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')
 }
 # This fixture has no datasource or MinIO client. Do not load .env or use a live-service signing key.
 $env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
 $env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 $env:AGENT_JWT_SECRET='ex15a-public-test-key-only-01234567890123456789'
 Set-Location -LiteralPath $taskRoot
 $mavenArgs=@('-B','-ntp','-pl','platform-gateway','test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx256m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=180','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=false','-DreuseForks=false','-DforkCount=1','-Dtest=ArtifactUploadGatewayTest')
 $started=[DateTime]::UtcNow
 $headAtStart=((& git rev-parse HEAD)-join '')
 if($LASTEXITCODE -ne 0 -or $headAtStart -notmatch '^[0-9a-f]{40}$'){throw 'Could not capture start HEAD'}
 $jarAtStart=(Get-FileHash -LiteralPath $adminJar).Hash
 Write-Fact 'context.json' ([ordered]@{phase=$Phase;head=$headAtStart;arguments=$mavenArgs;startedAt=$started.ToString('o');collectorSha256=(Get-FileHash -LiteralPath $PSCommandPath).Hash;adminJarSha256=$jarAtStart;failureIgnore=$false;inheritedJvmOptionsAbsent=$true;scope='Generated Servlet input to real GatewayController and loopback HTTP peer; authenticated context premise, no inbound FilterChain/JVM restart/PG/MinIO proof';resources='Per-call 127.0.0.1 random listener, owned worker and deadline; no fixed ports, external services, DB rows or objects'})
 $ErrorActionPreference='Continue'
 & mvn.cmd @mavenArgs > (Join-Path $taskRun 'maven.log') 2>&1
 $native=$LASTEXITCODE
 $ErrorActionPreference='Stop'
 Write-Fact 'native-exit.json' ([ordered]@{nativeExit=$native;endedAt=[DateTime]::UtcNow.ToString('o')})
 $report=Join-Path $taskRoot 'platform-gateway/target/surefire-reports/TEST-cn.agentplatform.gateway.ArtifactUploadGatewayTest.xml'
 if(-not(Test-Path -LiteralPath $report) -or (Get-Item -LiteralPath $report).LastWriteTimeUtc -lt $started){throw 'Fresh selected XML missing; raw native exit remains recorded'}
 Copy-Item -LiteralPath $report -Destination (Join-Path $taskRun 'methods.xml')
 $xml=New-Object Xml.XmlDocument
 $xml.XmlResolver=$null
 $xml.Load($report)
 $methods=@($xml.SelectNodes('//testcase') | ForEach-Object {
  $failure=$_.SelectSingleNode('failure');$errorNode=$_.SelectSingleNode('error');$skipped=$_.SelectSingleNode('skipped')
  [pscustomobject]@{class=$_.classname;method=$_.name;status=$(if($failure){'failure'}elseif($errorNode){'error'}elseif($skipped){'skipped'}else{'passed'});message=$(if($failure){$failure.message}elseif($errorNode){$errorNode.message}else{''})}
 })
 Write-Fact 'methods.json' $methods
 $identityComplete=($methods.Count -eq $expected.Count) -and (@($methods | Where-Object class -ne 'cn.agentplatform.gateway.ArtifactUploadGatewayTest').Count -eq 0) -and ((@($methods.method | Sort-Object) -join '|') -ceq ($expected -join '|'))
 $passed=@($methods | Where-Object status -eq 'passed').Count
 $failed=@($methods | Where-Object status -eq 'failure').Count
 $errors=@($methods | Where-Object status -eq 'error').Count
 $skipped=@($methods | Where-Object status -eq 'skipped').Count
 $redMatched=($native -eq 1 -and $passed -eq 7 -and $failed -eq 4 -and $errors -eq 0 -and $skipped -eq 0 -and ((@($methods | Where-Object status -eq 'failure' | ForEach-Object method | Sort-Object) -join '|') -ceq ($redMethods -join '|')))
 $greenMatched=($native -eq 0 -and $passed -eq 11 -and $failed -eq 0 -and $errors -eq 0 -and $skipped -eq 0)
 $after=Source-Facts
 Write-Fact 'source-after.json' $after
 $stable=(ConvertTo-Json -InputObject $before -Compress) -ceq (ConvertTo-Json -InputObject $after -Compress)
 $headAtEnd=((& git rev-parse HEAD)-join '')
 $jarAtEnd=(Get-FileHash -LiteralPath $adminJar).Hash
 $contextStable=($headAtStart -ceq $headAtEnd -and $jarAtStart -ceq $jarAtEnd)
 Write-Fact 'context-after.json' ([ordered]@{head=$headAtEnd;adminJarSha256=$jarAtEnd;contextStable=$contextStable})
 Write-Fact 'summary.json' ([ordered]@{phase=$Phase;nativeExit=$native;tests=$methods.Count;passed=$passed;failures=$failed;errors=$errors;skipped=$skipped;identityComplete=$identityComplete;sourceStable=$stable;contextStable=$contextStable;phaseExpectationMet=($identityComplete -and $stable -and $contextStable -and $(if($Phase -eq 'red'){$redMatched}else{$greenMatched}));xmlSha256=(Get-FileHash -LiteralPath (Join-Path $taskRun 'methods.xml')).Hash})
 if(-not $stable){throw 'Source changed during execution'}
 if(-not $contextStable){throw 'HEAD or installed admin-common JAR changed during execution'}
 Get-Content -LiteralPath (Join-Path $taskRun 'summary.json') -Encoding UTF8
} finally {
 foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')}
 Set-Location -LiteralPath $location
 if(-not(Test-Path -LiteralPath (Join-Path $taskRun 'source-after.json'))){Write-Fact 'source-after.json' (Source-Facts)}
 if($headAtStart -and -not(Test-Path -LiteralPath (Join-Path $taskRun 'context-after.json'))){
  $headAtEnd=((& git -C $taskRoot rev-parse HEAD)-join '')
  $jarAtEnd=(Get-FileHash -LiteralPath $adminJar).Hash
  Write-Fact 'context-after.json' ([ordered]@{head=$headAtEnd;adminJarSha256=$jarAtEnd;contextStable=($headAtStart -ceq $headAtEnd -and $jarAtStart -ceq $jarAtEnd)})
 }
}
exit $native
