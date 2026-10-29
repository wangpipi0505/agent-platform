param([Parameter(Mandatory=$true)][ValidateSet('Class','Full')][string]$Mode)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$taskRoot='G:/207/项目/算法重组工具/代码/agent-platform'
$taskRun=Join-Path $PSScriptRoot ($Mode.ToLowerInvariant())
New-Item -ItemType Directory -Path $taskRun -Force|Out-Null
$taskPhase='BOOT';$taskReason='UNEXPECTED_COLLECTOR_ERROR';$taskCollectorExit=2
$taskOriginalLocation=Get-Location
$taskPrevious=@{}
$taskNames=@('PGPASSWORD','JAVA_HOME','PATH','ENGINE_PG_TEST')
$taskFrozen=@{
 'skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java'='43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D'
 'skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java'='9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D'
 'skill-registry/src/main/java/cn/agentplatform/skill/application/OntologyManifestImports.java'='41450FAAC9141E0E3393D7A9ED45B5E5F67E7D09DA787F448DDBE86565F24CA5'
 'skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java'='7C98CAE7587DA4F64BF70E93E9540A16F097BFF885DE7EA158E147FE8D491B03'
 'skill-registry/src/test/java/cn/agentplatform/skill/application/OntologyManifestImportsTest.java'='97D0A6B1B48ECD833568C9E3F584303FEEF6D2A094BDAE6979AEC36353432051'
}
function Write-TaskJson($Name,$Value) {ConvertTo-Json -InputObject $Value -Depth 10 | Set-Content -LiteralPath (Join-Path $taskRun $Name) -Encoding utf8}
function Get-TaskIdentity($Row) {return ($Row.classname+'|'+$Row.method+'|'+$Row.display_name+'|'+$Row.suite_path)}
try {
 $taskPhase='ENVIRONMENT_BACKUP';$taskReason='AP_ENVIRONMENT_LOAD_FAILED'
Get-Content -LiteralPath "$taskRoot/.env" | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$taskNames+=$Matches[1]}}
foreach($taskName in ($taskNames|Select-Object -Unique)){$taskPrevious[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}

 Set-Location -LiteralPath $taskRoot
 $taskPhase='SOURCE_ENVIRONMENT';$taskReason='FROZEN_SOURCE_HEAD_CONFIG_MISMATCH'
 if(((& git rev-parse HEAD)-join '') -cne '83726d50a6cce116251c03c5c193080d8ed5aad2'){throw 'FROZEN_SOURCE_HEAD_CONFIG_MISMATCH'}
 if((Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash -cne 'AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A'){throw 'FROZEN_SOURCE_HEAD_CONFIG_MISMATCH'}
 $taskSource=@(foreach($taskPath in $taskFrozen.Keys){
  $taskHash=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskRoot $taskPath)).Hash
  if($taskHash -ne $taskFrozen[$taskPath]){throw ('冻结源码指纹改变，先重新登记候选：'+$taskPath)}
  [pscustomobject]@{path=$taskPath;sha256=$taskHash}
 })
 Write-TaskJson 'source-sha256.json' $taskSource
 $taskAllSource=@(Get-ChildItem -LiteralPath "$taskRoot/skill-registry/src" -Recurse -File | Sort-Object FullName | ForEach-Object {
  [pscustomobject]@{path=$_.FullName.Substring($taskRoot.Length+1).Replace('\','/');sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash}
 })
 if($taskAllSource.Count -ne 149){throw 'SOURCE_FILE_SET_MISMATCH'}
 $taskPreviousSource=[IO.File]::ReadAllText("$taskRoot/docs/evidence/2026-10-28-EX-27/runs/algorithm-diagnostic-20261029-full-010605-669/module-source-sha256.json")|ConvertFrom-Json
 $taskPreviousMap=@{};foreach($taskEntry in $taskPreviousSource){$taskPreviousMap[$taskEntry.path]=$taskEntry.sha256}
 $taskUnexpectedSource=@($taskAllSource|Where-Object{$_.path -cne 'skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java' -and (-not $taskPreviousMap.ContainsKey($_.path) -or $taskPreviousMap[$_.path] -cne $_.sha256)})
 if(@($taskPreviousSource).Count -ne 149 -or $taskUnexpectedSource.Count -ne 0){throw 'OTHER_148_SOURCE_FROZEN_MISMATCH'}
 Write-TaskJson 'source-context.json' ([pscustomobject]@{total149=$true;other148SameAsPreviousFull=$true;onlyApprovedTestCandidateChanged=$true})
 Write-TaskJson 'module-source-sha256.json' $taskAllSource
 $taskClasses=@(Get-ChildItem -LiteralPath "$taskRoot/skill-registry/src/test/java" -Recurse -File -Filter '*Test.java' | ForEach-Object {
  $taskText=Get-Content -LiteralPath $_.FullName -Raw -Encoding utf8
  if($taskText -notmatch '(?m)^\s*package\s+([\w.]+)\s*;'){throw '测试源码缺package，拒绝空选择'}
  $Matches[1]+'.'+$_.BaseName
 } | Sort-Object -Unique)
 if($taskClasses.Count -ne 70){throw '当前Skill源类清单不再是已核70类，先核定范围'}
 $taskClasses | Set-Content -LiteralPath "$taskRun/source-test-classes.txt" -Encoding utf8
 $taskTarget=@('cn.agentplatform.skill.infra.AlgorithmOffloadInterruptionTest')
 $taskExpected=if($Mode -eq 'Class'){$taskTarget}else{$taskClasses}
 if(@($taskExpected | Where-Object {$taskClasses -notcontains $_}).Count -gt 0){throw '选择器包含不存在的源类'}
 $taskExpected | Set-Content -LiteralPath "$taskRun/selected-classes.txt" -Encoding utf8
 Get-Content -LiteralPath "$taskRoot/.env" | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
 if(-not $env:AGENT_DB_PASSWORD){throw '缺本仓PG测试前提，拒绝把新增PG测试skip当通过'}
 $env:JAVA_HOME=if($env:AGENT_JAVA_HOME){$env:AGENT_JAVA_HOME}else{'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'}
 $env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 $env:ENGINE_PG_TEST='true'
 $env:PGPASSWORD=$env:AGENT_DB_PASSWORD
 $taskFlags=@{}
 foreach($taskFlag in @('AGENT_DEV_AUTH','ENGINE_PG_TEST','AGENT_SECURITY_MODE')){
  $taskValue=[Environment]::GetEnvironmentVariable($taskFlag,'Process')
  $taskFlags[$taskFlag]=if([string]::IsNullOrEmpty($taskValue)){'not explicitly set'}else{$taskValue}
 }
 $taskJar=Join-Path $taskRoot '.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar'
 if(-not (Test-Path -LiteralPath $taskJar)){throw '缺本轮已安装依赖，不允许-am中止代替目标执行'}
 if($env:JAVA_HOME.Replace([char]92,[char]47) -cne 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot' -or $env:AGENT_DEV_AUTH -cne 'true' -or $env:ENGINE_PG_TEST -cne 'true' -or -not [string]::IsNullOrEmpty($env:AGENT_SECURITY_MODE) -or (Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash -cne '1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87'){throw 'BASELINE_RUNTIME_CONTEXT_MISMATCH'}
 Write-TaskJson 'environment.json' ([pscustomobject]@{head=(& git rev-parse HEAD);powershell=$PSVersionTable.PSVersion.ToString();jdk=$env:JAVA_HOME;envSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath "$taskRoot/.env").Hash;nonSecretFlags=$taskFlags;securitySystemProperty='not set by collector';adminCommonJarSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $taskJar).Hash;sourceClasses=$taskClasses.Count;mode=$Mode;selectedClasses=$taskExpected.Count;valuesPrinted=$false})
 $taskPhase='CURRENT_LISTENER';$taskReason='OWNED_PG_PROCESS_MISMATCH'
 $taskListen=@(Get-NetTCPConnection -LocalPort 25432 -State Listen)
 if($taskListen.Count -ne 1 -or $taskListen[0].LocalAddress -ne '127.0.0.1' -or $taskListen[0].OwningProcess -ne 15204){throw '本仓PG环回监听不符'}
 $taskProcess=Get-CimInstance Win32_Process -Filter ('ProcessId='+$taskListen[0].OwningProcess)
 if($taskProcess.ExecutablePath.Replace('\','/') -ne 'V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe' -or $taskProcess.CommandLine -notlike '*V:/.local/pgdata*'){throw 'PG进程/数据目录不属于本仓'}
 $taskPhase='STRICT_ADMISSION_LINK';$taskReason='OWNED_EXISTING_DATABASE_ADMISSION_MISMATCH'
 $taskAdmissionFile=Join-Path $PSScriptRoot 'admission.json'
 $taskAdmissionHash=(Get-FileHash -LiteralPath $taskAdmissionFile -Algorithm SHA256).Hash
 $taskAdmission=[IO.File]::ReadAllText($taskAdmissionFile)|ConvertFrom-Json
 $taskAge=([DateTimeOffset]::Now-[DateTimeOffset]::Parse($taskAdmission.checkedAt)).TotalMinutes
 if(-not $taskAdmission.passed -or $taskAdmission.head -cne '83726d50a6cce116251c03c5c193080d8ed5aad2' -or $taskAdmission.envSha256 -cne 'AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A' -or -not $taskAdmission.exactCatalogIdentity -or -not $taskAdmission.exactClassifiedHistory -or $taskAdmission.pid -ne 15204 -or $taskAge -lt 0 -or $taskAge -gt 15){throw 'OWNED_EXISTING_DATABASE_ADMISSION_MISMATCH'}
 foreach($taskDb in @('agent_platform_skill_algorithm_migration_test','agent_platform_skill_digest_test')){
  $taskProof=[IO.File]::ReadAllText((Join-Path $PSScriptRoot ($taskDb+'-precondition.json')))|ConvertFrom-Json
  if(-not $taskProof.existed -or $taskProof.owner -cne 'agent_v12' -or $taskProof.schemaOwner -cne 'agent_v12' -or $taskProof.historyOwner -cne 'agent_v12' -or $taskProof.badRows -ne 0 -or -not $taskProof.originalTestSkillSchemaReplayAuthorizedByRoot){throw 'OWNED_EXISTING_DATABASE_ADMISSION_MISMATCH'}
 }
 if((Test-Path -LiteralPath "$taskRun/native-exit.json") -or (Test-Path -LiteralPath "$taskRun/raw.log")){throw 'PHASE_ALREADY_ATTEMPTED'}
 Write-TaskJson 'admission-link.json' ([pscustomobject]@{sha256=$taskAdmissionHash;ageMinutes=$taskAge;strictSameExclusiveWindow=$true;currentListenerPid=15204;collectorDdl=$false;sqlReadRepeated=$false;originalTwoOwnedReplayTestsPreserved=$true})
 $taskArgs=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true')
 if($Mode -eq 'Class'){$taskArgs+='-Dtest=AlgorithmOffloadInterruptionTest'}else{$taskArgs+='-Dmaven.test.failure.ignore=true'}
 $taskOriginalCommand=[IO.File]::ReadAllText("$taskRoot/docs/evidence/2026-10-28-EX-03/after/full-20261028-234917-584/command.json")|ConvertFrom-Json
 $taskExpectedArgs=@($taskOriginalCommand.arguments|Where-Object{$_ -cne '-Dmaven.test.failure.ignore=true'})
 if($Mode -eq 'Full'){$taskExpectedArgs+='-Dmaven.test.failure.ignore=true'}else{$taskExpectedArgs+='-Dtest=AlgorithmOffloadInterruptionTest'}
 if(($taskArgs -join "`0") -cne ($taskExpectedArgs -join "`0")){throw 'DEFAULT_COMMAND_CONTEXT_MISMATCH'}
 Write-TaskJson 'command-context.json' ([pscustomobject]@{defaultNoDiagnosticFlags=$true;defaultForkReuseAndOrderUnchanged=$true;selectedPhase=$Mode;classNoIgnore=($Mode -eq 'Class');fullOriginalIgnoreOnly=($Mode -eq 'Full');originalCollector='EX03/run-verify.ps1';noExtraMavenCommands=$true})
 if($Mode -eq 'Full'){
  $taskPhase='CLASS_GREEN_DEPENDENCY';$taskReason='CLASS_FIVE_REPORTS_OR_FROZEN_CONTEXT_NOT_GREEN'
  $taskClass=Join-Path $PSScriptRoot 'class'
  $taskClassSummary=[IO.File]::ReadAllText((Join-Path $taskClass 'summary.json'))|ConvertFrom-Json
  $taskClassNative=[IO.File]::ReadAllText((Join-Path $taskClass 'native-exit.json'))|ConvertFrom-Json
  $taskClassEnd=[IO.File]::ReadAllText((Join-Path $taskClass 'command-end-fingerprints.json'))|ConvertFrom-Json
  if(-not $taskClassSummary.regressionGatePassed -or -not $taskClassSummary.allTestsPassed -or $taskClassSummary.testcases -ne 5 -or $taskClassSummary.passed -ne 5 -or $taskClassSummary.nativeExit -ne 0 -or $taskClassNative.nativeExit -ne 0 -or $taskClassNative.failureIgnore -or -not $taskClassSummary.fiveReportIdentitiesPassed -or -not $taskClassSummary.defaultOutcomeProtocolPassed -or $taskClassSummary.sourceDrift -ne 0 -or $taskClassEnd.head -cne '83726d50a6cce116251c03c5c193080d8ed5aad2' -or $taskClassEnd.testSha256 -cne '43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D' -or $taskClassEnd.artifactStoreSha256 -cne '9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D' -or $taskClassEnd.envSha256 -cne 'AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A' -or $taskClassEnd.adminCommonJarSha256 -cne '1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87'){throw 'CLASS_FIVE_REPORTS_OR_FROZEN_CONTEXT_NOT_GREEN'}
  $taskClassSources=[IO.File]::ReadAllText((Join-Path $taskClass 'module-source-sha256.json'))|ConvertFrom-Json
  $taskClassMap=@{};foreach($item in $taskClassSources){$taskClassMap[$item.path]=$item.sha256}
  if(@($taskClassSources).Count -ne 149 -or @($taskAllSource|Where-Object{-not $taskClassMap.ContainsKey($_.path) -or $taskClassMap[$_.path] -cne $_.sha256}).Count -ne 0){throw 'CLASS_FIVE_REPORTS_OR_FROZEN_CONTEXT_NOT_GREEN'}
  Write-TaskJson 'class-dependency.json' ([pscustomobject]@{fivePassedNative0NoIgnore=$true;same149Sources=$true;sameHeadEnvJar=$true;noDiagnosticFlags=$true;classSummarySha256=(Get-FileHash -LiteralPath (Join-Path $taskClass 'summary.json') -Algorithm SHA256).Hash;noRetestOrSampling=$true})
 }
 $taskPhase='FULL_COMMAND';$taskReason='MAVEN_OR_COMPILATION_FAILURE'
 $taskStarted=Get-Date
 Write-TaskJson 'command.json' ([pscustomobject]@{command='mvn.cmd';arguments=$taskArgs;startedAt=$taskStarted.ToString('o');state='STARTED';nativeExit=$null})
 $taskExit=$null
 $taskNativePreference=$ErrorActionPreference
 try {
  $ErrorActionPreference='Continue'
  & mvn.cmd @taskArgs *> "$taskRun/raw.log"
  $taskExit=$LASTEXITCODE
 } finally {
  $ErrorActionPreference=$taskNativePreference
  $taskEnded=Get-Date
  # 原生命令返回后先持久化exit；后续归档/UTF-8解析失败不能抹掉真实运行结果。
  Write-TaskJson 'native-exit.json' ([pscustomobject]@{command='mvn.cmd';nativeExit=$taskExit;startedAt=$taskStarted.ToString('o');endedAt=$taskEnded.ToString('o');failureIgnore=($Mode -eq 'Full');collectorExitIsNotNativeExit=$true})
  Write-TaskJson 'command.json' ([pscustomobject]@{command='mvn.cmd';arguments=$taskArgs;startedAt=$taskStarted.ToString('o');endedAt=$taskEnded.ToString('o');nativeExit=$taskExit;state='COMMAND_RETURNED'})
 }
 Write-TaskJson 'command-end-fingerprints.json' ([pscustomobject]@{observedAt=[DateTimeOffset]::Now.ToString('o');head=((& git rev-parse HEAD)-join '');envSha256=(Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash;adminCommonJarSha256=(Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash;testSha256=(Get-FileHash -LiteralPath 'skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java' -Algorithm SHA256).Hash;artifactStoreSha256=(Get-FileHash -LiteralPath 'skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java' -Algorithm SHA256).Hash;nativeExitAlreadyPersisted=$true;beforeXmlCollection=$true})
 $taskPhase='XML_COLLECTION';$taskReason='XML_OR_SAFE_FACT_COLLECTION_FAILED'
 $taskXml=Join-Path $taskRun 'xml'
 New-Item -ItemType Directory -Path $taskXml -Force | Out-Null
 $taskFresh=@(Get-ChildItem -LiteralPath "$taskRoot/skill-registry/target/surefire-reports" -Filter 'TEST-*.xml' | Where-Object {$_.LastWriteTime -ge $taskStarted})
 $taskFresh | Copy-Item -Destination $taskXml
 Write-TaskJson 'xml-manifest.json' @($taskFresh | ForEach-Object {[pscustomobject]@{file=$_.Name;mtime=$_.LastWriteTime.ToString('o');length=$_.Length;sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskXml $_.Name)).Hash}})
 if($taskFresh.Count -eq 0){throw '本轮没有新鲜XML，记未执行，拒绝空通过'}
 $taskDiagnosticLines=0
 $taskOutcomes=New-Object 'System.Collections.Generic.List[object]'
 $taskDiagnostics=New-Object 'System.Collections.Generic.List[object]'
 $taskRows=New-Object 'System.Collections.Generic.List[object]'
 $taskReported=New-Object 'System.Collections.Generic.List[string]'
 $taskXmlIssues=New-Object 'System.Collections.Generic.List[object]'
 foreach($taskFile in Get-ChildItem -LiteralPath $taskXml -Filter '*.xml' | Sort-Object Name){
  $taskDocument=New-Object System.Xml.XmlDocument
  $taskDocument.XmlResolver=$null
  $taskDocument.Load($taskFile.FullName)
  foreach($taskOutput in $taskDocument.SelectNodes("//*[local-name()='system-out']")){
   foreach($taskLine in ($taskOutput.InnerText -split "`r?`n")){
    if($taskLine -match '^ALGORITHM_INTERRUPT_DIAGNOSTIC (\{.*\})$'){$taskDiagnostics.Add(($Matches[1]|ConvertFrom-Json))}
    if($taskLine -match '^ALGORITHM_INTERRUPT_DIAGNOSTIC'){$taskDiagnosticLines++}
    if($taskLine -match '^ALGORITHM_INTERRUPT stage=(PUT|GET|GET_BODY) returnMs=([0-9]+) flag=(true|false) inputClosed=([0-9]+) handoff=(true|false) explicitUnconfirmed=(true|false) firstQuiescent=(true|false)$'){
     $taskOutcomes.Add([pscustomobject]@{stage=$Matches[1];returnMs=[long]$Matches[2];interrupted=[bool]::Parse($Matches[3]);inputClosed=[int]$Matches[4];handoff=[bool]::Parse($Matches[5]);explicitUnconfirmed=[bool]::Parse($Matches[6]);firstQuiescent=[bool]::Parse($Matches[7])})
    }
   }
  }
  $taskReported.Add($taskDocument.DocumentElement.GetAttribute('name'))
  $taskCaseIndex=0
  foreach($taskCase in $taskDocument.SelectNodes("//*[local-name()='testcase']")){
   $taskCaseIndex++
   $taskState='passed'
   foreach($taskStatus in @('error','failure','skipped')){if($taskCase.SelectSingleNode("./*[local-name()='$taskStatus']")){$taskState=$taskStatus;break}}
   $taskAncestors=New-Object 'System.Collections.Generic.List[string]'
   $taskParent=$taskCase.ParentNode
   while($taskParent -and $taskParent -is [System.Xml.XmlElement]){if($taskParent.LocalName -eq 'testsuite'){$taskAncestors.Insert(0,$taskParent.GetAttribute('name'))};$taskParent=$taskParent.ParentNode}
   $taskRows.Add([pscustomobject]@{module='skill-registry';database='AP';attempt=(Split-Path -Leaf $taskRun);classname=$taskCase.GetAttribute('classname');method=$taskCase.GetAttribute('name');status=$taskState;display_name=$taskCase.GetAttribute('displayName');suite_path=($taskAncestors -join ' > ');case_index=$taskCaseIndex;seconds=$taskCase.GetAttribute('time');xml=('xml/'+$taskFile.Name)})
  }
  foreach($taskSuite in $taskDocument.SelectNodes("//*[local-name()='testsuite']")){
   $taskCases=@($taskSuite.SelectNodes(".//*[local-name()='testcase']"))
   $taskDeclared=@{tests=$taskCases.Count;errors=@($taskCases|Where-Object {$_.SelectSingleNode("./*[local-name()='error']")}).Count;failures=@($taskCases|Where-Object {$_.SelectSingleNode("./*[local-name()='failure']")}).Count;skipped=@($taskCases|Where-Object {$_.SelectSingleNode("./*[local-name()='skipped']")}).Count}
   foreach($taskField in $taskDeclared.Keys){if($taskSuite.HasAttribute($taskField) -and [int]$taskSuite.GetAttribute($taskField) -ne $taskDeclared[$taskField]){$taskXmlIssues.Add([pscustomobject]@{file=$taskFile.Name;suite=$taskSuite.GetAttribute('name');field=$taskField;declared=$taskSuite.GetAttribute($taskField);observed=$taskDeclared[$taskField]})}}
  }
 }
 $taskRows | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath "$taskRun/methods.csv"
 $taskCoverage=@(foreach($taskClass in $taskExpected){[pscustomobject]@{classname=$taskClass;status=if($taskReported -contains $taskClass){'REPORTED'}else{'NOT_REPORTED'}}})
 $taskCoverage | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath "$taskRun/coverage.csv"
 Write-TaskJson 'diagnostic-facts.json' ($taskDiagnostics.ToArray())
 $taskPhase='METHOD_COMPARISON';$taskReason='METHOD_COLLECTION_OR_REGRESSION_GATE_MISMATCH'
 $taskBaseline=@(Import-Csv -LiteralPath "$taskRoot/docs/evidence/2026-10-28-EX-00/baseline/20261028-164553-272/method-summary/methods.csv" | Where-Object {$_.module -eq 'skill-registry' -and ($Mode -eq 'Full' -or $taskTarget -contains $_.classname)})
 $taskBefore=@{}
 foreach($taskRow in $taskBaseline){$taskKey=Get-TaskIdentity $taskRow;if($taskBefore.ContainsKey($taskKey)){throw '基线方法身份重复，需人工保留多重身份后再比'};$taskBefore[$taskKey]=$taskRow}
 $taskAfter=@{}
 foreach($taskRow in $taskRows){$taskKey=Get-TaskIdentity $taskRow;if($taskAfter.ContainsKey($taskKey)){throw '本轮方法身份重复，拒绝覆盖'};$taskAfter[$taskKey]=$taskRow}
 $taskMissing=@($taskBaseline | Where-Object {-not $taskAfter.ContainsKey((Get-TaskIdentity $_))})
 $taskNew=@($taskRows | Where-Object {-not $taskBefore.ContainsKey((Get-TaskIdentity $_))})
 $taskChanged=@(foreach($taskKey in $taskBefore.Keys){if($taskAfter.ContainsKey($taskKey) -and $taskBefore[$taskKey].status -ne $taskAfter[$taskKey].status){[pscustomobject]@{classname=$taskBefore[$taskKey].classname;method=$taskBefore[$taskKey].method;before=$taskBefore[$taskKey].status;after=$taskAfter[$taskKey].status}}})
 $taskNewNonpass=@($taskRows | Where-Object {$_.status -ne 'passed' -and (-not $taskBefore.ContainsKey((Get-TaskIdentity $_)) -or $taskBefore[(Get-TaskIdentity $_)].status -ne $_.status)})
 $taskRetained=@($taskRows | Where-Object {$_.status -ne 'passed' -and $taskBefore.ContainsKey((Get-TaskIdentity $_)) -and $taskBefore[(Get-TaskIdentity $_)].status -eq $_.status})
 $taskMissing | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath "$taskRun/missing-baseline-methods.csv"
 $taskNew | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath "$taskRun/new-methods.csv"
 $taskChanged | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath "$taskRun/status-changes.csv"
 $taskNewNonpass | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath "$taskRun/new-nonpass.csv"
 $taskRetained | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath "$taskRun/retained-baseline-nonpass.csv"
 $taskRequiredNew=@(if($Mode -eq 'Full'){Import-Csv -LiteralPath "$taskRoot/docs/evidence/2026-10-28-EX-03/red/20261028-230240-211/method-status.csv" | Where-Object {$_.origin -eq 'new'}})
 $taskMissingNew=@($taskRequiredNew | Where-Object {$taskMethod=$_.method;@($taskRows|Where-Object {$_.classname -eq 'cn.agentplatform.skill.application.OntologyManifestImportsTest' -and $_.method -eq $taskMethod}).Count -ne 1})
 $taskMissingNew | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath "$taskRun/missing-required-new-methods.csv"
 Write-TaskJson 'xml-issues.json' ($taskXmlIssues.ToArray())
 $taskDrift=@($taskAllSource | Where-Object {(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskRoot $_.path)).Hash -ne $_.sha256})
 $taskUnselected=@($taskReported | Where-Object {$taskExpected -notcontains $_})
 $taskSdkClass='cn.agentplatform.skill.infra.AlgorithmOffloadInterruptionTest'
 $taskHandoff='originalGetBodyHandoffPreservesConfirmedOrExplicitlyUnconfirmedStopSafety'
 $taskSourceMethod='unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit'
 $taskFive=@('interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[1]','interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[2]','interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[3]',$taskSourceMethod,$taskHandoff)
 $taskSdkRows=@($taskRows|Where-Object{$_.classname -ceq $taskSdkClass})
 $taskFiveOkay=$taskSdkRows.Count -eq 5 -and @($taskSdkRows|Where-Object{$_.status -cne 'passed' -or $taskFive -cnotcontains $_.method}).Count -eq 0 -and @($taskFive|Where-Object{$name=$_;@($taskSdkRows|Where-Object{$_.method -ceq $name}).Count -ne 1}).Count -eq 0
 $taskOutcomeKeys=@($taskOutcomes|ForEach-Object{$_.stage+'|'+$_.handoff})
 $taskExpectedOutcomeKeys=@('PUT|False','GET|False','GET_BODY|False','GET_BODY|True')
 $taskOutcomeOkay=$taskOutcomes.Count -eq 4 -and @($taskExpectedOutcomeKeys|Where-Object{$key=$_;@($taskOutcomeKeys|Where-Object{$_ -ceq $key}).Count -ne 1}).Count -eq 0 -and @($taskOutcomes|Where-Object{-not $_.interrupted -or $_.inputClosed -lt 1 -or (-not $_.handoff -and -not $_.firstQuiescent) -or ($_.handoff -and -not $_.explicitUnconfirmed -and -not $_.firstQuiescent)}).Count -eq 0
 $taskSourcePassed=@($taskSdkRows|Where-Object{$_.method -ceq $taskSourceMethod -and $_.status -ceq 'passed'}).Count -eq 1
 $taskProtocolOkay=$taskFiveOkay -and $taskOutcomeOkay -and $taskSourcePassed -and $taskDiagnostics.Count -eq 0 -and $taskDiagnosticLines -eq 0
 $taskRequiredNewCount=if($Mode -eq 'Full'){9}else{0}
 $taskExpectedRecords=if($Mode -eq 'Full'){674}else{5}
 $taskExpectedNewMethods=if($Mode -eq 'Full'){10}else{1}
 Write-TaskJson 'network-outcomes.json' ($taskOutcomes.ToArray())
 Write-TaskJson 'default-protocol.json' ([pscustomobject]@{passed=$taskProtocolOkay;fiveIdentitiesPassed=$taskFiveOkay;networkOutcomesPassed=$taskOutcomeOkay;sourceCasePassed=$taskSourcePassed;networkOutcomeCount=$taskOutcomes.Count;diagnosticJsonCount=$taskDiagnostics.Count;diagnosticOutputLines=$taskDiagnosticLines;normalBodyPremiseReachedByPassing43ECAssertions=$taskFiveOkay;handoffUnconfirmedRequiredEveryRun=$false;sourceUnconfirmedCompletePostconditionsReachedByPassing43ECAssertions=$taskSourcePassed;oldSceneReconstructedByCurrentQ=$false})
 $taskEndProof=[IO.File]::ReadAllText((Join-Path $taskRun 'command-end-fingerprints.json'))|ConvertFrom-Json
 $taskEndOkay=$taskEndProof.head -ceq '83726d50a6cce116251c03c5c193080d8ed5aad2' -and $taskEndProof.testSha256 -ceq '43EC56726C4DD04F3B4E4553590B80316D87BD2B619230C6F728A52D9376BD9D' -and $taskEndProof.artifactStoreSha256 -ceq '9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D' -and $taskEndProof.envSha256 -ceq 'AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A' -and $taskEndProof.adminCommonJarSha256 -ceq '1B0D7B40FC3AE02B207294D3B6BE7EA7F7BB9DCD35AF8EA62A372F916FA14B87'
 $taskComplete=$taskEndOkay -and $taskProtocolOkay -and $taskRows.Count -eq $taskExpectedRecords -and $taskNew.Count -eq $taskExpectedNewMethods -and @($taskCoverage | Where-Object {$_.status -eq 'NOT_REPORTED'}).Count -eq 0 -and $taskMissing.Count -eq 0 -and $taskMissingNew.Count -eq 0 -and $taskRequiredNew.Count -eq $taskRequiredNewCount -and $taskXmlIssues.Count -eq 0 -and $taskUnselected.Count -eq 0 -and $taskDrift.Count -eq 0
 $taskAllPass=@($taskRows | Where-Object {$_.status -ne 'passed'}).Count -eq 0
 $taskGate=$taskComplete -and $taskNewNonpass.Count -eq 0 -and $taskExit -eq 0 -and (($Mode -eq 'Full') -or ($taskAllPass -and $taskRows.Count -eq 5))
 $taskSummary=[pscustomobject]@{fiveReportIdentitiesPassed=$taskFiveOkay;defaultOutcomeProtocolPassed=$taskProtocolOkay;networkOutcomeCount=$taskOutcomes.Count;sourceCasePassed=$taskSourcePassed;defaultNoDiagnostics=($taskDiagnostics.Count -eq 0 -and $taskDiagnosticLines -eq 0);endFrozenContextPassed=$taskEndOkay;reasonComparisonPending=($Mode -eq 'Full');diagnosticStages=@($taskDiagnostics|ForEach-Object{$_.stage});diagnosticCount=$taskDiagnostics.Count;mode=$Mode;nativeExit=$taskExit;failureIgnore=($Mode -eq 'Full');baselineMethods=$taskBaseline.Count;reportedClasses=$taskReported.Count;expectedClasses=$taskExpected.Count;testcases=$taskRows.Count;passed=@($taskRows|Where-Object {$_.status -eq 'passed'}).Count;failure=@($taskRows|Where-Object {$_.status -eq 'failure'}).Count;error=@($taskRows|Where-Object {$_.status -eq 'error'}).Count;skipped=@($taskRows|Where-Object {$_.status -eq 'skipped'}).Count;newMethods=$taskNew.Count;missingBaselineMethods=$taskMissing.Count;missingRequiredNewMethods=$taskMissingNew.Count;newNonpass=$taskNewNonpass.Count;retainedBaselineNonpass=$taskRetained.Count;statusChanges=$taskChanged.Count;sourceDrift=$taskDrift.Count;xmlIssues=$taskXmlIssues.Count;unselectedClasses=$taskUnselected;collectionComplete=$taskComplete;allTestsPassed=$taskAllPass;regressionGatePassed=$taskGate;gateNote='Full permits only exact retained EX-00 nonpass; failure.ignore native0 is not all-green'}
 $taskCollectorExit=if($taskGate){0}else{1}
 Write-TaskJson 'summary.json' $taskSummary
 Write-Output ('EX27_ALGORITHM_FIX_ARCHIVE='+$taskRun)
 $taskSummary | ConvertTo-Json -Depth 4
 if(-not $taskGate){throw '本轮定向/比较门未满足，保留真实结果，不放行'}
} catch {
 Write-TaskJson 'collector-error.json' ([pscustomobject]@{phase=$taskPhase;reason=$taskReason;scriptLineNumber=$_.InvocationInfo.ScriptLineNumber;type=$_.Exception.GetType().FullName;state='COLLECTION_OR_GATE_FAILED';nativeExitFileExists=(Test-Path -LiteralPath "$taskRun/native-exit.json")})
 Write-Output ('EX27_ALGORITHM_FULL_STOP phase='+$taskPhase+' reason='+$taskReason+' line='+$_.InvocationInfo.ScriptLineNumber)
} finally {
 foreach($taskName in $taskPrevious.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskPrevious[$taskName],'Process')}
 Set-Location -LiteralPath $taskOriginalLocation
}
exit $taskCollectorExit