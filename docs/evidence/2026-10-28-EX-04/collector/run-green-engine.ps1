param(
 [Parameter(Mandatory=$true)][string]$FreezePath,
 [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$taskRoot='G:/207/项目/算法重组工具/代码/agent-platform'
$taskRun=$null
$taskPhase='FREEZE_ADMISSION';$taskReason='ROOT_FREEZE_NOT_APPROVED';$taskCollectorExit=2
$taskPrevious=@{};$taskLocation=Get-Location
$taskClasses=@('cn.agentplatform.engine.application.ToolOutputPolicyTest','cn.agentplatform.engine.application.RunToolProfileTest')
function Write-TaskJson([string]$Name,$Value){
 [IO.File]::WriteAllText((Join-Path $taskRun $Name),(ConvertTo-Json -InputObject $Value -Depth 10),(New-Object Text.UTF8Encoding($false)))
}
function Identity($Row){$Row.classname+'|'+$Row.method+'|'+$Row.display_name+'|'+$Row.suite_path}
function Load-PinnedFile([string]$Path,[string]$Sha){
 if($Sha -cnotmatch '^[A-Fa-f0-9]{64}$' -or -not (Test-Path -LiteralPath $Path -PathType Leaf) -or (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -cne $Sha.ToUpperInvariant()){throw 'FROZEN_INPUT_MISMATCH'}
 [IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path).Path,[Text.Encoding]::UTF8)
}
function Current-Sources{
 @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'engine-runtime/src') -Recurse -File|Sort-Object FullName|ForEach-Object{
  [pscustomobject]@{path=$_.FullName.Substring($taskRoot.Length+1).Replace([char]92,[char]47);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
 })
}
function Assert-Sources($Expected,$Actual){
 $map=@{};foreach($row in $Expected){
  if($row.path -cnotmatch '^engine-runtime/src/' -or $row.path -match '(^|/)\.\.(/|$)' -or $row.sha256 -cnotmatch '^[A-Fa-f0-9]{64}$' -or $map.ContainsKey($row.path)){throw 'INVALID_SOURCE_MANIFEST'}
  $map[$row.path]=$row.sha256.ToUpperInvariant()
 }
 if($map.Count -eq 0 -or $map.Count -ne @($Actual).Count -or @($Actual|Where-Object{-not $map.ContainsKey($_.path) -or $map[$_.path] -cne $_.sha256}).Count -ne 0){throw 'FROZEN_SOURCE_DRIFT'}
}
try{
 Set-Location -LiteralPath $taskRoot
 $freeze=[IO.File]::ReadAllText((Resolve-Path -LiteralPath $FreezePath).Path,[Text.Encoding]::UTF8)|ConvertFrom-Json
 if(-not $freeze.pureTwoClassNoPg -or -not $freeze.approvedForExclusiveActualWindow -or $freeze.phase -cne 'GREEN_ENGINE' -or $freeze.head -cnotmatch '^[a-fA-F0-9]{40}$' -or $freeze.envSha256 -cnotmatch '^[a-fA-F0-9]{64}$' -or $freeze.adminCommonJarSha256 -cnotmatch '^[a-fA-F0-9]{64}$' -or -not $freeze.testReview -or -not $freeze.rootGrant -or $freeze.testReview -like 'PENDING*' -or $freeze.rootGrant -like 'PENDING*'){throw 'ROOT_FREEZE_NOT_APPROVED'}
 $source=Load-PinnedFile $freeze.moduleSourceManifest $freeze.moduleSourceManifestSha256|ConvertFrom-Json
 [void](Load-PinnedFile $freeze.expectedMethodsCsv $freeze.expectedMethodsCsvSha256)
 $expected=@(Import-Csv -LiteralPath $freeze.expectedMethodsCsv)
 $expectedMap=@{};foreach($row in $expected){$key=Identity $row;if(-not $row.method -or $taskClasses -cnotcontains $row.classname -or $expectedMap.ContainsKey($key)){throw 'INVALID_EXPECTED_IDENTITIES'};$expectedMap[$key]=$row}
 if(@($taskClasses|Where-Object{$class=$_;@($expected|Where-Object{$_.classname -ceq $class}).Count -eq 0}).Count -ne 0){throw 'EXPECTED_TWO_CLASSES_MISSING'}
 $before=Current-Sources;Assert-Sources $source $before
 $bridge=$freeze.projectionFixtureProvenance
 $consumerPaths=@('engine-runtime/src/test/java/cn/agentplatform/engine/application/ToolOutputPolicyTest.java','engine-runtime/src/test/java/cn/agentplatform/engine/application/RunToolProfileTest.java')
 if(-not $bridge.rootVerified -or -not $bridge.actualPgTwoKeySource -or @($bridge.consumerSourcePins).Count -ne 2 -or @($consumerPaths|Where-Object{$p=$_;@($bridge.consumerSourcePins|Where-Object{$_.path -ceq $p}).Count -ne 1}).Count -ne 0){throw 'ROOT_FROZEN_FIXTURE_PROVENANCE_MISSING'}
 foreach($pin in $bridge.consumerSourcePins){if($pin.sha256 -cnotmatch '^[A-Fa-f0-9]{64}$' -or (Get-FileHash -LiteralPath $pin.path -Algorithm SHA256).Hash -cne $pin.sha256.ToUpperInvariant()){throw 'CONSUMER_FIXTURE_SOURCE_DRIFT'}}
 $producer=(Resolve-Path -LiteralPath $bridge.producerRunDirectory).Path
 $ownedProducerRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'runs'))+[IO.Path]::DirectorySeparatorChar
 if(-not $producer.StartsWith($ownedProducerRoot,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $producer) -cnotmatch '^green-skill-[a-z0-9-]+$'){throw 'FIXTURE_PRODUCER_SCOPE_MISMATCH'}
 $projectionFile=Join-Path $producer 'ex04-persisted-projection.json'
 $producerXml=Join-Path $producer 'xml/TEST-cn.agentplatform.skill.application.OntologyProjectionImportTest.xml'
 $producerProofFile=Join-Path $producer 'ex04-output-provenance.json'
 [void](Load-PinnedFile $projectionFile $bridge.projectionJsonSha256)
 [void](Load-PinnedFile $producerXml $bridge.producerXmlSha256)
 $producerProof=Load-PinnedFile $producerProofFile $bridge.producerProofSha256|ConvertFrom-Json
 if(-not $producerProof.passed -or $producerProof.source -cne 'ACTUAL_FRESH_PASSING_PROJECTION_XML_ONLY' -or $producerProof.projectionPrefixCount -ne 1 -or $producerProof.batchPrefixCount -ne 4 -or -not $producerProof.projectionJsonSavedAsExactEmittedUtf8Bytes -or $producerProof.constructedBeforeActualRun -or $producerProof.projectionJsonSha256 -cne $bridge.projectionJsonSha256.ToUpperInvariant() -or $producerProof.xmlSha256 -cne $bridge.producerXmlSha256.ToUpperInvariant()){throw 'ACTUAL_PG_PROJECTION_PROVENANCE_MISMATCH'}
 if(((& git rev-parse HEAD)-join '') -cne $freeze.head){throw 'FROZEN_HEAD_MISMATCH'}
 foreach($class in $taskClasses){$p='engine-runtime/src/test/java/'+$class.Replace('.','/')+'.java';if(-not (Test-Path -LiteralPath $p -PathType Leaf)){throw 'SELECTED_TEST_SOURCE_MISSING'}}
 $taskRun=Join-Path $PSScriptRoot ('runs/'+$RunId)
 if(Test-Path -LiteralPath $taskRun){throw 'RUN_ID_ALREADY_EXISTS'}
 New-Item -ItemType Directory -Path $taskRun|Out-Null
 Write-TaskJson 'freeze-admission.json' ([ordered]@{phase='GREEN_ENGINE';head=$freeze.head;freezeSha256=(Get-FileHash -LiteralPath $FreezePath -Algorithm SHA256).Hash;sourceManifestSha256=$freeze.moduleSourceManifestSha256;expectedMethodsSha256=$freeze.expectedMethodsCsvSha256;expectedReports=$expected.Count;candidateSourceFrozen=$true;testReview=$freeze.testReview;rootGrant=$freeze.rootGrant;templateExecuted=$false})
 Write-TaskJson 'source-before.json' $before
 Write-TaskJson 'fixture-provenance-admission.json' ([ordered]@{rootVerified=$true;actualPgTwoKeySource=$true;producerRunDirectory=$bridge.producerRunDirectory;projectionJsonSha256=$bridge.projectionJsonSha256;producerXmlSha256=$bridge.producerXmlSha256;producerProofSha256=$bridge.producerProofSha256;consumerSourcePins=$bridge.consumerSourcePins;fixedFixtureRatherThanLivePgInEngine=$true;enginePgOrHttpAdmission=$false})
 $taskPhase='ENVIRONMENT';$taskReason='FROZEN_RUNTIME_CONTEXT_MISMATCH'
 if((Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash -cne $freeze.envSha256.ToUpperInvariant()){throw 'FROZEN_RUNTIME_CONTEXT_MISMATCH'}
 $values=@{};foreach($line in [IO.File]::ReadAllLines((Join-Path $taskRoot '.env'),[Text.Encoding]::UTF8)){if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
 foreach($n in (@($values.Keys)+@('PGPASSWORD','PGCONNECT_TIMEOUT','PGOPTIONS','JAVA_HOME','PATH','ENGINE_PG_TEST')|Select-Object -Unique)){$taskPrevious[$n]=[Environment]::GetEnvironmentVariable($n,'Process')}
 foreach($n in $values.Keys){[Environment]::SetEnvironmentVariable($n,$values[$n],'Process')}
 $env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot';$env:PATH="$env:JAVA_HOME/bin;$env:PATH";$env:ENGINE_PG_TEST='true'
 $jar=Join-Path $taskRoot '.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar'
 if($env:AGENT_DEV_AUTH -cne 'true' -or -not [string]::IsNullOrEmpty($env:AGENT_SECURITY_MODE) -or (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -cne $freeze.adminCommonJarSha256.ToUpperInvariant()){throw 'FROZEN_RUNTIME_CONTEXT_MISMATCH'}
 Write-TaskJson 'environment.json' ([ordered]@{envSha256=$freeze.envSha256;jarSha256=$freeze.adminCommonJarSha256;jdk=$env:JAVA_HOME;developmentFlag=$true;postgresFlag=$true;securityModeSet=$false;diagnosticFlagsPassed=$false;secretValuesPrinted=$false})
 Write-TaskJson 'database-precondition.json' ([ordered]@{phase='GREEN_ENGINE';requiredForFrozenPureTwoClassScope=$false;pgConnected=$false;collectorDdl=$false;rootSourceReviewRequired=$true})
 $taskPhase='MAVEN_GREEN';$taskReason='NATIVE_OR_COMPILE_RESULT_REQUIRES_REVIEW'
 $taskArgs=@('-B','-ntp','-pl','engine-runtime','test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dtest=ToolOutputPolicyTest,RunToolProfileTest')
 $started=Get-Date;$native=$null
 Write-TaskJson 'command.json' ([ordered]@{command='mvn.cmd';arguments=$taskArgs;startedAt=$started.ToString('o');state='STARTED';failureIgnore=$false})
 try{$priorPreference=$ErrorActionPreference;$ErrorActionPreference='Continue';& mvn.cmd @taskArgs *> (Join-Path $taskRun 'raw.log');$native=$LASTEXITCODE}
 finally{$ErrorActionPreference=$priorPreference;$ended=Get-Date;Write-TaskJson 'native-exit.json' ([ordered]@{nativeExit=$native;startedAt=$started.ToString('o');endedAt=$ended.ToString('o');failureIgnore=$false;collectorExitSeparate=$true})}
 Write-TaskJson 'command.json' ([ordered]@{command='mvn.cmd';arguments=$taskArgs;startedAt=$started.ToString('o');endedAt=$ended.ToString('o');nativeExit=$native;state='COMMAND_RETURNED';failureIgnore=$false})
 Write-TaskJson 'command-end.json' ([ordered]@{head=((& git rev-parse HEAD)-join '');envSha256=(Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash;jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash;nativeAlreadyPersisted=$true;beforeXml=$true})
 $after=Current-Sources;Write-TaskJson 'source-after.json' $after;Assert-Sources $source $after
 if(((& git rev-parse HEAD)-join '') -cne $freeze.head -or (Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash -cne $freeze.envSha256.ToUpperInvariant() -or (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash -cne $freeze.adminCommonJarSha256.ToUpperInvariant()){throw 'POST_NATIVE_FROZEN_CONTEXT_DRIFT'}
 $taskPhase='XML_COLLECTION';$taskReason='FRESH_XML_OR_IDENTITY_MISMATCH'
 $xml=Join-Path $taskRun 'xml';New-Item -ItemType Directory -Path $xml|Out-Null
 $fresh=@(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'engine-runtime/target/surefire-reports') -File -Filter 'TEST-*.xml'|Where-Object{$_.LastWriteTime -ge $started})
 $rows=@(foreach($file in $fresh){
  Copy-Item -LiteralPath $file.FullName -Destination $xml
  $d=New-Object Xml.XmlDocument;$d.XmlResolver=$null;$d.Load((Join-Path $xml $file.Name))
  foreach($case in $d.SelectNodes("//*[local-name()='testcase']")){
   $status='passed';$type='';foreach($s in @('error','failure','skipped')){$event=$case.SelectSingleNode("./*[local-name()='$s']");if($event){$status=$s;$type=$event.GetAttribute('type');break}}
   $suites=New-Object 'System.Collections.Generic.List[string]';$parent=$case.ParentNode
   while($parent -and $parent -is [Xml.XmlElement]){if($parent.LocalName -eq 'testsuite'){$suites.Insert(0,$parent.GetAttribute('name'))};$parent=$parent.ParentNode}
   [pscustomobject]@{classname=$case.GetAttribute('classname');method=$case.GetAttribute('name');display_name=$case.GetAttribute('displayName');suite_path=($suites -join ' > ');status=$status;exception_type=$type;xml=('xml/'+$file.Name)}
  }
 })
 $rows|Export-Csv -LiteralPath (Join-Path $taskRun 'methods.csv') -NoTypeInformation -Encoding UTF8
 Write-TaskJson 'xml-manifest.json' @($fresh|ForEach-Object{[pscustomobject]@{file=$_.Name;bytes=$_.Length;mtime=$_.LastWriteTime.ToString('o');sha256=(Get-FileHash -LiteralPath (Join-Path $xml $_.Name) -Algorithm SHA256).Hash}})
 $actualMap=@{};foreach($row in $rows){$key=Identity $row;if($actualMap.ContainsKey($key)){throw 'DUPLICATE_REPORT_IDENTITY'};$actualMap[$key]=$row}
 $missing=@($expectedMap.Keys|Where-Object{-not $actualMap.ContainsKey($_)})
 $unexpected=@($actualMap.Keys|Where-Object{-not $expectedMap.ContainsKey($_)})
 $bad=@($rows|Where-Object{$_.status -cne 'passed'}|ForEach-Object{Identity $_})
 $complete=$fresh.Count -eq 2 -and $rows.Count -eq $expected.Count -and $missing.Count -eq 0 -and $unexpected.Count -eq 0 -and @($rows|Where-Object{-not $_.method -or $_.status -ceq 'skipped'}).Count -eq 0
 $green=$complete -and $native -eq 0 -and $null -ne $native -and $bad.Count -eq 0
 Write-TaskJson 'summary.json' ([ordered]@{phase='GREEN_ENGINE';nativeExit=$native;failureIgnore=$false;expectedReports=$expected.Count;actualReports=$rows.Count;passed=@($rows|Where-Object{$_.status -ceq 'passed'}).Count;failure=@($rows|Where-Object{$_.status -ceq 'failure'}).Count;error=@($rows|Where-Object{$_.status -ceq 'error'}).Count;skipped=@($rows|Where-Object{$_.status -ceq 'skipped'}).Count;missing=$missing;unexpected=$unexpected;nonpass=$bad;reportsCollected=$complete;testGreenPassed=$green;regressionGatePassed=$green;allGreen=$green;sourceFrozen=$true;fixedActualPgFixtureProvenancePassed=$true;pgConnectedByCollector=$false;httpConnectedByCollector=$false;rootRuntimeReviewStillRequired=$true})
 $taskCollectorExit=if($green){0}else{1}
 Write-Output ('EX04_GREEN_ENGINE_ARCHIVE='+$taskRun+' native='+$native+' collector='+$taskCollectorExit+' reports='+$rows.Count+' green='+$green)
}catch{
 if($taskRun -and (Test-Path -LiteralPath $taskRun)){Write-TaskJson 'collector-error.json' ([ordered]@{phase=$taskPhase;reason=$taskReason;scriptLineNumber=$_.InvocationInfo.ScriptLineNumber;exceptionClass=$_.Exception.GetType().FullName;messagePrinted=$false;nativePersisted=(Test-Path -LiteralPath (Join-Path $taskRun 'native-exit.json'))})}
 Write-Output ('EX04_GREEN_ENGINE_STOP phase='+$taskPhase+' reason='+$taskReason+' line='+$_.InvocationInfo.ScriptLineNumber)
}finally{
 foreach($n in $taskPrevious.Keys){[Environment]::SetEnvironmentVariable($n,$taskPrevious[$n],'Process')}
 Set-Location -LiteralPath $taskLocation
}
exit $taskCollectorExit
