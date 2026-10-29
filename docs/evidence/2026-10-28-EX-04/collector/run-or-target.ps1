param([Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9-]+$')][string]$RunId)
$ErrorActionPreference='Stop'
$taskRoot='G:/207/项目/算法重组工具/代码/ontorepo'
$taskRun=Join-Path $PSScriptRoot ('runs/'+$RunId)
$taskPrior=@{};$taskLocation=Get-Location;$taskPhase='ADMISSION';$taskNative=$null;$taskExit=2
function Write-TaskJson([string]$Name,$Value){[IO.File]::WriteAllText((Join-Path $taskRun $Name),(ConvertTo-Json -InputObject $Value -Depth 8),(New-Object Text.UTF8Encoding($false)))}
function Current-TaskSources {
 @(Get-ChildItem -LiteralPath (Join-Path $taskRoot 'onto-contract/src') -Recurse -File | Sort-Object FullName | ForEach-Object{[pscustomobject]@{path=$_.FullName.Substring($taskRoot.Length+1).Replace([char]92,[char]47);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}})
}
try {
 Set-Location -LiteralPath $taskRoot
 if(((& git rev-parse HEAD)-join '') -cne 'da3345a123752c3fda06c5a7a8a1dc9d614b8918'){throw 'OR_HEAD_NOT_FROZEN'}
 if(((& git diff --name-only -- onto-contract/src)-join '')){throw 'OR_SOURCE_MODIFIED'}
 if(Test-Path -LiteralPath $taskRun){throw 'RUN_ID_EXISTS'}
 New-Item -ItemType Directory -Path $taskRun|Out-Null
 $before=Current-TaskSources;Write-TaskJson 'source-before.json' $before
 $configPath=Join-Path $taskRoot '.runtime/local-env.json'
 $config=[IO.File]::ReadAllText($configPath,[Text.Encoding]::UTF8)|ConvertFrom-Json
 foreach($name in (@($config.PSObject.Properties.Name)+@('JAVA_HOME','PATH','MAVEN_OPTS','NODE_OPTIONS','ONTO_TEST_DB_URL')|Select-Object -Unique)){$taskPrior[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
 . (Join-Path $taskRoot 'deploy/Environment.ps1')
 [void](Import-OntoEnvironment)
 Set-OntoJava
 Set-OntoBuildLimits
 $asciiRoot=Get-OntoAsciiRoot $config.ONTO_LOCAL_DRIVE
 $temporary=Join-Path $asciiRoot '.runtime/tmp';New-Item -ItemType Directory -Path $temporary -Force|Out-Null
 # This pure contract class does not use PG/HTTP. Keep its own BASE setting, never point at AP or P4.
 if(-not $config.PSObject.Properties['ONTO_TEST_DB_URL']){throw 'OWN_OR_BASE_TEST_URL_MISSING'}
 $env:ONTO_TEST_DB_URL=([string]$config.ONTO_TEST_DB_URL -replace '/onto_test(?:_p[34])?(?=\?|$)', '/onto_test')
 if($env:ONTO_TEST_DB_URL -notmatch '^jdbc:postgresql://127\.0\.0\.1:[0-9]+/onto_test(?:\?|$)'){throw 'OWN_OR_BASE_TEST_URL_MISMATCH'}
 $argsForTest=@('-B','-ntp','-pl','onto-contract','test',"-DargLine=-Xmx512m -Djava.io.tmpdir=$temporary -Djdk.net.unixdomain.tmpdir=$temporary",'-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dtest=ToolBindingExposureTest')
 Write-TaskJson 'admission.json' ([ordered]@{head='da3345a123752c3fda06c5a7a8a1dc9d614b8918';sourceCount=$before.Count;configurationSha256=(Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash;ownAsciiRoot=$asciiRoot;ownEnvironmentOnly=$true;secretValuesPrinted=$false;pureContractTest=$true;pgHttpStarted=$false;databaseMode='BASE';failureIgnore=$false;am=$false;rootGrant='ROOT sole actual window OR_TOOL_BINDING_9 once after directed engine window returned'})
 $taskPhase='MAVEN';$started=Get-Date
 Write-TaskJson 'command.json' ([ordered]@{command='mvn.cmd';arguments=$argsForTest;startedAt=$started.ToString('o');state='STARTED'})
 try{$priorPreference=$ErrorActionPreference;$ErrorActionPreference='Continue';& mvn.cmd @argsForTest *> (Join-Path $taskRun 'raw.log');$taskNative=$LASTEXITCODE}
 finally{$ErrorActionPreference=$priorPreference;$ended=Get-Date;Write-TaskJson 'native-exit.json' ([ordered]@{nativeExit=$taskNative;startedAt=$started.ToString('o');endedAt=$ended.ToString('o');failureIgnore=$false;collectorExitSeparate=$true})}
 Write-TaskJson 'command.json' ([ordered]@{command='mvn.cmd';arguments=$argsForTest;startedAt=$started.ToString('o');endedAt=$ended.ToString('o');nativeExit=$taskNative;state='COMMAND_RETURNED'})
 $after=Current-TaskSources;Write-TaskJson 'source-after.json' $after
 $map=@{};foreach($row in $before){$map[$row.path]=$row.sha256}
 if($before.Count -ne $after.Count -or @($after|Where-Object{-not $map.ContainsKey($_.path) -or $map[$_.path] -cne $_.sha256}).Count -ne 0 -or ((& git rev-parse HEAD)-join '') -cne 'da3345a123752c3fda06c5a7a8a1dc9d614b8918'){throw 'OR_FROZEN_SOURCE_DRIFT'}
 $taskPhase='XML';$xml=Join-Path $taskRun 'xml';New-Item -ItemType Directory -Path $xml|Out-Null
 $fresh=@(Get-ChildItem -LiteralPath 'onto-contract/target/surefire-reports' -Filter 'TEST-*.xml' -File|Where-Object{$_.LastWriteTime -ge $started})
 if($fresh.Count -ne 1 -or $fresh[0].Name -cne 'TEST-cn.ontorepo.contract.ToolBindingExposureTest.xml'){throw 'OR_FRESH_CLASS_MISMATCH'}
 Copy-Item -LiteralPath $fresh[0].FullName -Destination $xml
 $file=Join-Path $xml $fresh[0].Name;$doc=New-Object Xml.XmlDocument;$doc.XmlResolver=$null;$doc.Load($file)
 $rows=@(foreach($case in $doc.SelectNodes("//*[local-name()='testcase']")){$status='passed';foreach($kind in @('failure','error','skipped')){if($case.SelectSingleNode("./*[local-name()='$kind']")){$status=$kind;break}};[pscustomobject]@{classname=$case.GetAttribute('classname');method=$case.GetAttribute('name');status=$status}})
 $expected=@('mcpDeclarationNoLongerRequiresHttpUrlOrPost','algorithmBindingWithSubmitQueryKindIsDescribedNotExecuted','undeclaredBindingKeepsLegacyHttpSyncManifest','nonHttpManifestCarryingHttpEntryIsRejected','exposureDeclarationFlowsIntoManifestAndValidates','mutationExposureCannotPretendReadOnly','invalidExposureDeclarationsFailWithTypedErrors','malformedBindingDeclarationFailsWithTypedErrors','formalizePreservesExposureDeclaration')
 $identity=$rows.Count -eq 9 -and @($rows|Where-Object{$_.classname -cne 'cn.ontorepo.contract.ToolBindingExposureTest' -or $expected -cnotcontains $_.method}).Count -eq 0 -and @($rows.method|Select-Object -Unique).Count -eq 9
 $green=$identity -and $taskNative -eq 0 -and @($rows|Where-Object{$_.status -cne 'passed'}).Count -eq 0
 $rows|Export-Csv -LiteralPath (Join-Path $taskRun 'methods.csv') -Encoding UTF8 -NoTypeInformation
 Write-TaskJson 'xml-manifest.json' ([ordered]@{file=$fresh[0].Name;bytes=$fresh[0].Length;mtime=$fresh[0].LastWriteTime.ToString('o');sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash})
 Write-TaskJson 'summary.json' ([ordered]@{nativeExit=$taskNative;failureIgnore=$false;reports=$rows.Count;passed=@($rows|Where-Object{$_.status -ceq 'passed'}).Count;exact9Identities=$identity;regressionGatePassed=$green;allGreen=$green;sourceDrift=0;productModified=$false;pgHttpStarted=$false;businessPaginationOrJ4Proven=$false})
 $taskExit=if($green){0}else{1};Write-Output ('EX04_OR_TARGET_ARCHIVE='+$taskRun+' native='+$taskNative+' collector='+$taskExit+' reports='+$rows.Count+' green='+$green)
}catch{if(Test-Path -LiteralPath $taskRun){Write-TaskJson 'collector-error.json' ([ordered]@{phase=$taskPhase;scriptLineNumber=$_.InvocationInfo.ScriptLineNumber;type=$_.Exception.GetType().FullName;messagePrinted=$false;nativeExit=$taskNative})};Write-Output ('EX04_OR_TARGET_STOP phase='+$taskPhase+' line='+$_.InvocationInfo.ScriptLineNumber)}
finally{foreach($name in $taskPrior.Keys){[Environment]::SetEnvironmentVariable($name,$taskPrior[$name],'Process')};Set-Location -LiteralPath $taskLocation}
exit $taskExit
