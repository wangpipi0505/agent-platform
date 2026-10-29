param([Parameter(Mandatory=$true)][ValidateSet('Target','Full')][string]$Mode)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$taskRoot='G:/207/项目/算法重组工具/代码/agent-platform'
$taskRun=Join-Path $PSScriptRoot ('after/'+$Mode.ToLowerInvariant()+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
$taskOriginalLocation=Get-Location
$taskPrevious=@{}
$taskNames=@('PGPASSWORD','JAVA_HOME','PATH','ENGINE_PG_TEST')
$taskFrozen=@{
 'skill-registry/src/main/java/cn/agentplatform/skill/application/OntologyManifestImports.java'='41450FAAC9141E0E3393D7A9ED45B5E5F67E7D09DA787F448DDBE86565F24CA5'
 'skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java'='7C98CAE7587DA4F64BF70E93E9540A16F097BFF885DE7EA158E147FE8D491B03'
 'skill-registry/src/test/java/cn/agentplatform/skill/application/OntologyManifestImportsTest.java'='97D0A6B1B48ECD833568C9E3F584303FEEF6D2A094BDAE6979AEC36353432051'
}
function Write-TaskJson($Name,$Value) {ConvertTo-Json -InputObject $Value -Depth 10 | Set-Content -LiteralPath (Join-Path $taskRun $Name) -Encoding utf8}
function Get-TaskIdentity($Row) {return ($Row.classname+'|'+$Row.method+'|'+$Row.display_name+'|'+$Row.suite_path)}
Get-Content -LiteralPath "$taskRoot/.env" | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$taskNames+=$Matches[1]}}
foreach($taskName in ($taskNames|Select-Object -Unique)){$taskPrevious[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
 Set-Location -LiteralPath $taskRoot
 $taskSource=@(foreach($taskPath in $taskFrozen.Keys){
  $taskHash=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskRoot $taskPath)).Hash
  if($taskHash -ne $taskFrozen[$taskPath]){throw ('冻结源码指纹改变，先重新登记候选：'+$taskPath)}
  [pscustomobject]@{path=$taskPath;sha256=$taskHash}
 })
 Write-TaskJson 'source-sha256.json' $taskSource
 $taskAllSource=@(Get-ChildItem -LiteralPath "$taskRoot/skill-registry/src" -Recurse -File | Sort-Object FullName | ForEach-Object {
  [pscustomobject]@{path=$_.FullName.Substring($taskRoot.Length+1).Replace('\','/');sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash}
 })
 Write-TaskJson 'module-source-sha256.json' $taskAllSource
 $taskClasses=@(Get-ChildItem -LiteralPath "$taskRoot/skill-registry/src/test/java" -Recurse -File -Filter '*Test.java' | ForEach-Object {
  $taskText=Get-Content -LiteralPath $_.FullName -Raw -Encoding utf8
  if($taskText -notmatch '(?m)^\s*package\s+([\w.]+)\s*;'){throw '测试源码缺package，拒绝空选择'}
  $Matches[1]+'.'+$_.BaseName
 } | Sort-Object -Unique)
 if($taskClasses.Count -ne 70){throw '当前Skill源类清单不再是已核70类，先核定范围'}
 $taskClasses | Set-Content -LiteralPath "$taskRun/source-test-classes.txt" -Encoding utf8
 $taskTarget=@('cn.agentplatform.skill.application.OntologyManifestImportsTest','cn.agentplatform.skill.application.SkillAuthSchemeRegistrationTest','cn.agentplatform.skill.infra.AuthSchemeOutboundTest')
 $taskExpected=if($Mode -eq 'Target'){$taskTarget}else{$taskClasses}
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
 Write-TaskJson 'environment.json' ([pscustomobject]@{head=(& git rev-parse HEAD);powershell=$PSVersionTable.PSVersion.ToString();jdk=$env:JAVA_HOME;envSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath "$taskRoot/.env").Hash;nonSecretFlags=$taskFlags;securitySystemProperty='not set by collector';adminCommonJarSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $taskJar).Hash;sourceClasses=$taskClasses.Count;mode=$Mode;selectedClasses=$taskExpected.Count;valuesPrinted=$false})
 $taskListen=@(Get-NetTCPConnection -LocalPort 25432 -State Listen)
 if($taskListen.Count -ne 1 -or $taskListen[0].LocalAddress -ne '127.0.0.1'){throw '本仓PG环回监听不符'}
 $taskProcess=Get-CimInstance Win32_Process -Filter ('ProcessId='+$taskListen[0].OwningProcess)
 if($taskProcess.ExecutablePath.Replace('\','/') -ne 'V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe' -or $taskProcess.CommandLine -notlike '*V:/.local/pgdata*'){throw 'PG进程/数据目录不属于本仓'}
 $taskPg='V:/.tools/postgresql-16.15/pgsql/bin/psql.exe'
 $taskData=& $taskPg -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -v ON_ERROR_STOP=1 -c 'show data_directory' 2> "$taskRun/pg-precondition.raw.txt"
 if($LASTEXITCODE -ne 0 -or $taskData.Replace('\','/').TrimEnd('/') -ne 'V:/.local/pgdata'){throw 'PG实际权威目录不符'}
 $taskDatabase='agent_platform_ontology_import_test'
 $taskMarker='agent-platform EX-03 2026-10-28 ontology import isolated test database'
 $taskOwner=& $taskPg -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -F '|' -v ON_ERROR_STOP=1 -c "select pg_get_userbyid(datdba),coalesce(shobj_description(oid,'pg_database'),'') from pg_database where datname='$taskDatabase'" 2>> "$taskRun/pg-precondition.raw.txt"
 if($LASTEXITCODE -ne 0 -or $taskOwner -ne "agent_v12|$taskMarker"){throw '专库缺失或owner/本卡标记不符；不创建/清理/接管'}
 Write-TaskJson 'database-precondition.json' ([pscustomobject]@{pid=$taskProcess.ProcessId;port=25432;executable=$taskProcess.ExecutablePath;commandLine=$taskProcess.CommandLine;dataDirectory=$taskData;database=$taskDatabase;owner='agent_v12';marker=$taskMarker;createdByThisRun=$false})
 $taskArgs=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true')
 if($Mode -eq 'Target'){$taskArgs+='-Dtest=OntologyManifestImportsTest,SkillAuthSchemeRegistrationTest,AuthSchemeOutboundTest'}else{$taskArgs+='-Dmaven.test.failure.ignore=true'}
 $taskStarted=Get-Date
 Write-TaskJson 'command.json' ([pscustomobject]@{command='mvn.cmd';arguments=$taskArgs;startedAt=$taskStarted.ToString('o');state='STARTED';nativeExit=$null})
 $taskExit=$null
 $taskNativePreference=$ErrorActionPreference
 try {
  $ErrorActionPreference='Continue'
  & mvn.cmd @taskArgs *> "$taskRun/maven.raw.txt"
  $taskExit=$LASTEXITCODE
 } finally {
  $ErrorActionPreference=$taskNativePreference
  $taskEnded=Get-Date
  # 原生命令返回后先持久化exit；后续归档/UTF-8解析失败不能抹掉真实运行结果。
  Write-TaskJson 'native-exit.json' ([pscustomobject]@{command='mvn.cmd';nativeExit=$taskExit;startedAt=$taskStarted.ToString('o');endedAt=$taskEnded.ToString('o');failureIgnore=($Mode -eq 'Full');collectorExitIsNotNativeExit=$true})
  Write-TaskJson 'command.json' ([pscustomobject]@{command='mvn.cmd';arguments=$taskArgs;startedAt=$taskStarted.ToString('o');endedAt=$taskEnded.ToString('o');nativeExit=$taskExit;state='COMMAND_RETURNED'})
 }
 $taskXml=Join-Path $taskRun 'skill-registry-xml'
 New-Item -ItemType Directory -Path $taskXml -Force | Out-Null
 $taskFresh=@(Get-ChildItem -LiteralPath "$taskRoot/skill-registry/target/surefire-reports" -Filter 'TEST-*.xml' | Where-Object {$_.LastWriteTime -ge $taskStarted})
 $taskFresh | Copy-Item -Destination $taskXml
 Write-TaskJson 'xml-manifest.json' @($taskFresh | ForEach-Object {[pscustomobject]@{file=$_.Name;mtime=$_.LastWriteTime.ToString('o');length=$_.Length;sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskXml $_.Name)).Hash}})
 if($taskFresh.Count -eq 0){throw '本轮没有新鲜XML，记未执行，拒绝空通过'}
 $taskRows=New-Object 'System.Collections.Generic.List[object]'
 $taskReported=New-Object 'System.Collections.Generic.List[string]'
 $taskXmlIssues=New-Object 'System.Collections.Generic.List[object]'
 foreach($taskFile in Get-ChildItem -LiteralPath $taskXml -Filter '*.xml' | Sort-Object Name){
  $taskDocument=New-Object System.Xml.XmlDocument
  $taskDocument.XmlResolver=$null
  $taskDocument.Load($taskFile.FullName)
  $taskReported.Add($taskDocument.DocumentElement.GetAttribute('name'))
  $taskCaseIndex=0
  foreach($taskCase in $taskDocument.SelectNodes("//*[local-name()='testcase']")){
   $taskCaseIndex++
   $taskState='passed'
   foreach($taskStatus in @('error','failure','skipped')){if($taskCase.SelectSingleNode("./*[local-name()='$taskStatus']")){$taskState=$taskStatus;break}}
   $taskAncestors=New-Object 'System.Collections.Generic.List[string]'
   $taskParent=$taskCase.ParentNode
   while($taskParent -and $taskParent -is [System.Xml.XmlElement]){if($taskParent.LocalName -eq 'testsuite'){$taskAncestors.Insert(0,$taskParent.GetAttribute('name'))};$taskParent=$taskParent.ParentNode}
   $taskRows.Add([pscustomobject]@{module='skill-registry';database='AP';attempt=(Split-Path -Leaf $taskRun);classname=$taskCase.GetAttribute('classname');method=$taskCase.GetAttribute('name');status=$taskState;display_name=$taskCase.GetAttribute('displayName');suite_path=($taskAncestors -join ' > ');case_index=$taskCaseIndex;seconds=$taskCase.GetAttribute('time');xml=('skill-registry-xml/'+$taskFile.Name)})
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
 $taskRequiredNew=@(Import-Csv -LiteralPath "$PSScriptRoot/red/20261028-230240-211/method-status.csv" | Where-Object {$_.origin -eq 'new'})
 $taskMissingNew=@($taskRequiredNew | Where-Object {$taskMethod=$_.method;@($taskRows|Where-Object {$_.classname -eq 'cn.agentplatform.skill.application.OntologyManifestImportsTest' -and $_.method -eq $taskMethod}).Count -ne 1})
 $taskMissingNew | Export-Csv -NoTypeInformation -Encoding utf8 -LiteralPath "$taskRun/missing-required-new-methods.csv"
 Write-TaskJson 'xml-issues.json' ($taskXmlIssues.ToArray())
 $taskDrift=@($taskAllSource | Where-Object {(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskRoot $_.path)).Hash -ne $_.sha256})
 $taskUnselected=@($taskReported | Where-Object {$taskExpected -notcontains $_})
 $taskComplete=$taskRows.Count -gt 0 -and @($taskCoverage | Where-Object {$_.status -eq 'NOT_REPORTED'}).Count -eq 0 -and $taskMissing.Count -eq 0 -and $taskMissingNew.Count -eq 0 -and $taskRequiredNew.Count -eq 9 -and $taskXmlIssues.Count -eq 0 -and $taskUnselected.Count -eq 0 -and $taskDrift.Count -eq 0
 $taskAllPass=@($taskRows | Where-Object {$_.status -ne 'passed'}).Count -eq 0
 $taskGate=$taskComplete -and $taskNewNonpass.Count -eq 0 -and $taskExit -eq 0 -and (($Mode -eq 'Full') -or ($taskAllPass -and $taskRows.Count -eq 30))
 $taskSummary=[pscustomobject]@{mode=$Mode;nativeExit=$taskExit;failureIgnore=($Mode -eq 'Full');baselineMethods=$taskBaseline.Count;reportedClasses=$taskReported.Count;expectedClasses=$taskExpected.Count;testcases=$taskRows.Count;passed=@($taskRows|Where-Object {$_.status -eq 'passed'}).Count;failure=@($taskRows|Where-Object {$_.status -eq 'failure'}).Count;error=@($taskRows|Where-Object {$_.status -eq 'error'}).Count;skipped=@($taskRows|Where-Object {$_.status -eq 'skipped'}).Count;newMethods=$taskNew.Count;missingBaselineMethods=$taskMissing.Count;missingRequiredNewMethods=$taskMissingNew.Count;newNonpass=$taskNewNonpass.Count;retainedBaselineNonpass=$taskRetained.Count;statusChanges=$taskChanged.Count;sourceDrift=$taskDrift.Count;xmlIssues=$taskXmlIssues.Count;unselectedClasses=$taskUnselected;collectionComplete=$taskComplete;allTestsPassed=$taskAllPass;regressionGatePassed=$taskGate;gateNote='Full permits only exact retained EX-00 nonpass; failure.ignore native0 is not all-green'}
 Write-TaskJson 'summary.json' $taskSummary
 Write-Output ('EX03_ARCHIVE='+$taskRun)
 $taskSummary | ConvertTo-Json -Depth 4
 if(-not $taskGate){throw '本轮定向/比较门未满足，保留真实结果，不放行'}
} catch {
 Write-TaskJson 'collector-error.json' ([pscustomobject]@{type=$_.Exception.GetType().FullName;state='COLLECTION_OR_GATE_FAILED';nativeExitFileExists=(Test-Path -LiteralPath "$taskRun/native-exit.json")})
 throw
} finally {
 foreach($taskName in $taskPrevious.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskPrevious[$taskName],'Process')}
 Set-Location -LiteralPath $taskOriginalLocation
}
