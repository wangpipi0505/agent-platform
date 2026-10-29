param()
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
$taskRoot='G:/207/项目/算法重组工具/代码/agent-platform'
$taskRun=Join-Path $PSScriptRoot ('red/'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
$taskPrevious=@{}
$taskNames=@('PGPASSWORD','JAVA_HOME','PATH','ENGINE_PG_TEST')
Get-Content -LiteralPath "$taskRoot/.env" | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$taskNames+=$Matches[1]}}
foreach($taskName in ($taskNames|Select-Object -Unique)){$taskPrevious[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try {
 Get-Content -LiteralPath "$taskRoot/.env" | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
 $env:JAVA_HOME=if($env:AGENT_JAVA_HOME){$env:AGENT_JAVA_HOME}else{'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'}
 $env:PATH="$env:JAVA_HOME/bin;$env:PATH"
 $env:ENGINE_PG_TEST='true'
 $env:PGPASSWORD=$env:AGENT_DB_PASSWORD
 $taskPg='V:/.tools/postgresql-16.15/pgsql/bin/psql.exe'
 $taskListen=Get-NetTCPConnection -LocalPort 25432 -State Listen
 if($taskListen.OwningProcess -ne 15204){throw '本仓PG监听归属已改变，停止核实'}
 $taskProcess=Get-CimInstance Win32_Process -Filter 'ProcessId=15204'
 if($taskProcess.CommandLine -notlike '*V:/.local/pgdata*'){throw 'PG数据目录归属不符'}
 $taskData=& $taskPg -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -v ON_ERROR_STOP=1 -c 'show data_directory'
 if($LASTEXITCODE -ne 0 -or $taskData.Replace('\','/').TrimEnd('/') -ne 'V:/.local/pgdata'){throw 'PG权威数据目录不符'}
 $taskDatabase='agent_platform_ontology_import_test'
 $taskMark='agent-platform EX-03 2026-10-28 ontology import isolated test database'
 $taskExisting=& $taskPg -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -F '|' -v ON_ERROR_STOP=1 -c "select pg_get_userbyid(datdba),coalesce(shobj_description(oid,'pg_database'),'') from pg_database where datname='$taskDatabase'"
 if($LASTEXITCODE -ne 0){throw '查询测试库失败'}
 $taskCreated=$false
 if(-not $taskExisting){
  $taskCreate=& $taskPg -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -v ON_ERROR_STOP=1 -c "create database $taskDatabase"
  if($LASTEXITCODE -ne 0){throw '创建本卡测试库失败'}
  $taskComment=& $taskPg -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -v ON_ERROR_STOP=1 -c "comment on database $taskDatabase is '$taskMark'"
  if($LASTEXITCODE -ne 0){throw '写本卡归属标记失败'}
  $taskCreated=$true
 } elseif($taskExisting -ne "agent_v12|$taskMark"){throw '已有测试库缺本卡owner/标记证据，停止'}
 [pscustomobject]@{pgPort=25432;pid=15204;executable=$taskProcess.ExecutablePath;commandLine=$taskProcess.CommandLine;dataDirectory=$taskData;database=$taskDatabase;originallyExisted=(-not $taskCreated);createdByThisRun=$taskCreated;owner='agent_v12';comment=$taskMark;envSha256=(Get-FileHash -Algorithm SHA256 -LiteralPath "$taskRoot/.env").Hash;secretValuesPrinted=$false} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$taskRun/database-precondition.json" -Encoding utf8
 $taskPaths=@('skill-registry/src/test/java/cn/agentplatform/skill/application/OntologyManifestImportsTest.java','skill-registry/src/main/java/cn/agentplatform/skill/application/OntologyManifestImports.java','skill-registry/src/main/java/cn/agentplatform/skill/application/SkillCatalog.java')
 $taskPaths | ForEach-Object {[pscustomobject]@{path=$_;sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $taskRoot $_)).Hash}} | ConvertTo-Json | Set-Content -LiteralPath "$taskRun/source-sha256.json" -Encoding utf8
 $taskArgs=@('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dtest=OntologyManifestImportsTest')
 $taskStarted=Get-Date
 Set-Location -LiteralPath $taskRoot
 & mvn.cmd @taskArgs *> "$taskRun/maven.raw.txt"
 $taskExit=$LASTEXITCODE
 $taskEnded=Get-Date
 $taskXml=Join-Path $taskRun 'xml'
 New-Item -ItemType Directory -Path $taskXml -Force | Out-Null
 Get-ChildItem -LiteralPath "$taskRoot/skill-registry/target/surefire-reports" -Filter 'TEST-*OntologyManifestImportsTest.xml' | Where-Object {$_.LastWriteTime -ge $taskStarted} | Copy-Item -Destination $taskXml
 [pscustomobject]@{head=(& git rev-parse HEAD);command='mvn.cmd';arguments=$taskArgs;startedAt=$taskStarted.ToString('o');endedAt=$taskEnded.ToString('o');exitCode=$taskExit;freshXml=@(Get-ChildItem -LiteralPath $taskXml -Filter '*.xml').Count;phase='RED_ONLY';productModified=$false} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$taskRun/command.json" -Encoding utf8
 Write-Output ("EX03_RED_ARCHIVED="+$taskRun)
 Write-Output ("MAVEN_EXIT="+$taskExit)
 Get-Content -LiteralPath "$taskRun/maven.raw.txt" -Tail 24
} finally {
 foreach($taskName in $taskPrevious.Keys){[Environment]::SetEnvironmentVariable($taskName,$taskPrevious[$taskName],'Process')}
}
