param(
 [Parameter(Mandatory=$true)][ValidateSet('Red','Green')][string]$Mode,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ApprovedTestSha,
 [Parameter(Mandatory=$true)][ValidatePattern('^[A-Fa-f0-9]{64}$')][string]$ApprovedStoreSha,
 [switch]$ExecuteApproved
)
$ErrorActionPreference='Stop'
if(-not $ExecuteApproved){throw 'ACTUAL_WINDOW_NOT_GRANTED'}
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../../..')).Path
$run=Join-Path $PSScriptRoot ('runs/'+$RunId)
if(Test-Path -LiteralPath $run){throw 'RUN_ALREADY_EXISTS'}
$manifestPath=Join-Path $root 'docs/evidence/2026-10-29-EX-15b/collector/manifest.json'
if((Get-FileHash -LiteralPath $manifestPath).Hash -cne '833EE0F815DAB47FDD22F6F672E23B7EE31E295389A8BE761055CA4C50511C9F'){throw 'BASE_MANIFEST_CHANGED'}
$manifest=[IO.File]::ReadAllText($manifestPath)|ConvertFrom-Json
$testPath='skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadResponseOwnershipTest.java'
$storePath='skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java'
$expected=@{}
foreach($s in $manifest.sources){$expected[$s.path.Replace([char]92,[char]47)]=$s.sha256.ToUpperInvariant()}
if($expected.Count -ne 151 -or $expected.ContainsKey($testPath)){throw 'BASE_SOURCE_SET_CHANGED'}
$expected[$testPath]=$ApprovedTestSha.ToUpperInvariant()
$expected[$storePath]=$ApprovedStoreSha.ToUpperInvariant()
if($Mode -ceq 'Red' -and $expected[$storePath] -cne '967849596AE6179A2B0858FB39D50A365C65F2370D4E2AD0658DFEE44A0569B1'){throw 'RED_REQUIRES_ORIGINAL_PRODUCT'}
New-Item -ItemType Directory -Path $run|Out-Null
$native=$null;$collector=2;$phase='ADMISSION';$prior=@{};$location=Get-Location
function Save([string]$name,$value){[IO.File]::WriteAllText((Join-Path $run $name),(ConvertTo-Json -InputObject $value -Depth 10),(New-Object Text.UTF8Encoding($false)))}
function Hash([string]$path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash}
function Sources {
 @(Get-ChildItem -LiteralPath (Join-Path $root 'skill-registry/src') -Recurse -File|Sort-Object FullName|ForEach-Object{
  [pscustomobject]@{path=$_.FullName.Substring($root.Length+1).Replace([char]92,[char]47);sha256=(Hash $_.FullName)}
 })
}
function Matches($rows){$rows.Count -eq 152 -and @($rows|Where-Object{-not $expected.ContainsKey($_.path) -or $expected[$_.path] -cne $_.sha256}).Count -eq 0}
try{
 Set-Location -LiteralPath $root
 $before=Sources;Save 'source-before.json' $before
 if(-not(Matches $before)){throw 'SOURCE_ADMISSION_DRIFT'}
 $head=((& git rev-parse HEAD)-join '')
 if($LASTEXITCODE -ne 0 -or $head -cne $manifest.expectedHead){throw 'HEAD_CHANGED'}
 $envSha=Hash '.env';$jarSha=Hash $manifest.adminJar
 if($envSha -cne $manifest.envSha256 -or $jarSha -cne $manifest.adminJarSha256){throw 'CONTEXT_CHANGED'}
 if((Hash $manifest.reportReader) -cne $manifest.reportReaderSha256){throw 'REPORT_READER_CHANGED'}
 Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $run 'collector.ps1')
 Copy-Item -LiteralPath $testPath -Destination (Join-Path $run 'test-source.java')
 Copy-Item -LiteralPath $storePath -Destination (Join-Path $run 'product-source.java')
 Save 'admission.json' ([ordered]@{mode=$Mode;head=$head;testSha256=$expected[$testPath];storeSha256=$expected[$storePath];sourceCount=152;baseManifestSha256=(Hash $manifestPath);collectorSha256=(Hash $PSCommandPath);envSha256=$envSha;adminJarSha256=$jarSha;envFileImported=$false;pgUsed=$false;realSdkAndOwnedLoopbackHttp=$true;originalFiveTestsChanged=$false;actualWindowGranted=$true})
 foreach($key in @('JAVA_HOME','PATH')){$prior[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
 foreach($key in @('MAVEN_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')){if(-not[string]::IsNullOrEmpty([Environment]::GetEnvironmentVariable($key,'Process'))){throw 'UNAPPROVED_JVM_ENVIRONMENT'}}
 $env:JAVA_HOME=$manifest.jdk;$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 $arguments=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$root/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=false','-Dtest=AlgorithmOffloadResponseOwnershipTest')
 $started=[DateTime]::UtcNow;$phase='MAVEN'
 Save 'command.json' ([ordered]@{command='mvn.cmd';arguments=$arguments;startedAt=$started.ToString('o');failureIgnore=$false;selectedMethods=1;envFileImported=$false;pgUsed=$false})
 try{$ErrorActionPreference='Continue';& mvn.cmd @arguments *> (Join-Path $run 'raw.log');$native=$LASTEXITCODE}
 finally{$ErrorActionPreference='Stop';Save 'native-exit.json' ([ordered]@{nativeExit=$native;startedAt=$started.ToString('o');endedAt=[DateTime]::UtcNow.ToString('o');nativeRecordedBeforeReportParsing=$true})}
 $phase='ARCHIVE'
 $after=Sources;Save 'source-after.json' $after
 $stable=Matches $after
 $context=((& git rev-parse HEAD)-join '') -ceq $head -and (Hash '.env') -ceq $envSha -and (Hash $manifest.adminJar) -ceq $jarSha
 $xml=Join-Path $run 'xml';New-Item -ItemType Directory -Path $xml|Out-Null
 $fresh=@(Get-ChildItem -LiteralPath 'skill-registry/target/surefire-reports' -File -Filter 'TEST-*.xml'|Where-Object{$_.LastWriteTimeUtc -ge $started})
 foreach($f in $fresh){Copy-Item -LiteralPath $f.FullName -Destination $xml}
 Save 'xml-manifest.json' @($fresh|ForEach-Object{[pscustomobject]@{file=$_.Name;bytes=$_.Length;sha256=(Hash (Join-Path $xml $_.Name))}})
 Save 'raw-log-manifest.json' ([ordered]@{path='raw.log';sha256=(Hash (Join-Path $run 'raw.log'));bytes=(Get-Item (Join-Path $run 'raw.log')).Length;unmodified=$true})
 . (Join-Path $root $manifest.reportReader)
 $report=Read-Ex04FullReports $xml;$rows=@($report.rows)
 $rows|Export-Csv -LiteralPath (Join-Path $run 'methods.csv') -NoTypeInformation -Encoding UTF8
 Save 'xml-issues.json' @($report.issues)
 $identity=$fresh.Count -eq 1 -and $rows.Count -eq 1 -and $rows[0].classname -ceq 'cn.agentplatform.skill.infra.AlgorithmOffloadResponseOwnershipTest' -and $rows[0].method -ceq 'interruptedAwaitOwnsResponseCompletedBeforeCallerStartsStopping' -and @($report.issues).Count -eq 0
 $witness=@()
 foreach($f in $fresh){$doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load((Join-Path $xml $f.Name));foreach($o in $doc.SelectNodes("//*[local-name()='system-out']")){$witness+=@($o.InnerText -split '\r?\n'|Where-Object{$_ -clike 'ALGORITHM_RESPONSE_OWNERSHIP *'})}}
 Save 'ownership-witness.json' $witness
 $expectedWitness='ALGORITHM_RESPONSE_OWNERSHIP awaitInterrupted=true originalFutureSuccess=true sameResponse=true stoppingBeforeCallerClose=false stages=0 calls='
 $witnessOkay=$witness.Count -eq 1 -and $witness[0] -ceq ($expectedWitness+$(if($Mode -ceq 'Red'){'1 retainedOriginalCall=true'}else{'0 retainedOriginalCall=false'}))
 $red=$identity -and $native -ne 0 -and $rows[0].status -ceq 'failure' -and $rows[0].exception_type -ceq 'org.opentest4j.AssertionFailedError' -and $rows[0].message_sha256 -ceq (Get-Ex04TextSha 'original SDK stages/Calls and original owned executor really exited ==> expected: <true> but was: <false>')
 $green=$identity -and $native -eq 0 -and $rows[0].status -ceq 'passed'
 $gate=$stable -and $context -and $witnessOkay -and $(if($Mode -ceq 'Red'){$red}else{$green})
 Save 'summary.json' ([ordered]@{mode=$Mode;nativeExit=$native;records=$rows.Count;freshXml=$fresh.Count;exactIdentity=$identity;sourceStable=$stable;contextStable=$context;originalResponseOwnershipWitness=$witnessOkay;expectedBehaviorRed=$red;green=$green;gatePassed=$gate;pgUsed=$false;originalFullFailurePreserved=$true;wholeCardComplete=$false})
 $collector=if($gate){0}else{1}
 Write-Output ('EX27_RESPONSE_OWNERSHIP mode='+$Mode+' native='+$native+' records='+$rows.Count+' gate='+$gate)
}catch{
 Save 'collector-error.json' ([ordered]@{phase=$phase;nativeExit=$native;reason=$(if($_.Exception.Message -cmatch '^[A-Z_0-9]+$'){$_.Exception.Message}else{'UNEXPECTED_COLLECTOR_ERROR'});line=$_.InvocationInfo.ScriptLineNumber;exceptionClass=$_.Exception.GetType().FullName})
 Write-Output ('EX27_RESPONSE_COLLECTOR_STOPPED phase='+$phase+' line='+$_.InvocationInfo.ScriptLineNumber)
}finally{
 foreach($key in $prior.Keys){[Environment]::SetEnvironmentVariable($key,$prior[$key],'Process')}
 Set-Location -LiteralPath $location
 Save 'collector-exit.json' ([ordered]@{nativeExit=$native;collectorExit=$collector;rawEvidencePreserved=$true})
}
exit $collector
