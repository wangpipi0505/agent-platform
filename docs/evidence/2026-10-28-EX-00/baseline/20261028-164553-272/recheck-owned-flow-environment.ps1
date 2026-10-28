$ErrorActionPreference='Stop'
$root='G:/207/项目/算法重组工具/代码/agent-platform'
$orRoot='G:/207/项目/算法重组工具/代码/ontorepo'
$primary=Join-Path $root 'docs/evidence/2026-10-28-EX-00/baseline/20261028-164553-272'
if(-not(Test-Path -LiteralPath "$orRoot/docs/evidence/2026-10-28-EX-00/baseline/20261028-164553-272/web-build-run.json")){throw 'Wait for OR/backend/frontend pipeline to finish first'}
$head=(& git -C $root rev-parse HEAD).Trim()
if($head -ne 'ebbfd62ca1d19efc4d172804efb5ac79453ceb64'){throw 'Source HEAD changed since baseline'}
$runId=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$evidence=Join-Path $root "docs/evidence/2026-10-28-EX-00/environment-recheck/$runId"
if(Test-Path -LiteralPath $evidence){throw 'Recheck attempt already exists'}
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
$head|Set-Content -LiteralPath "$evidence/HEAD.txt" -Encoding utf8
Get-Content -LiteralPath "$root/.env"|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$env:ENGINE_PG_TEST='true'
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:MAVEN_OPTS='-Xmx512m'
$pg=Join-Path $root '.tools/postgresql-16.15/pgsql/bin'
$previousPassword=[Environment]::GetEnvironmentVariable('PGPASSWORD','Process')
$database='agent_platform_iam_owned_flow_v12'
try{
 # Existing project script verifies V: ownership and project PG data directory.
 & "$root/scripts/start-database.ps1" *> "$evidence/project-pg-verification.raw.txt"
 $env:PGPASSWORD=$env:AGENT_DB_PASSWORD
 $dataDir=(& "$pg/psql.exe" -w -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -Atc 'show data_directory').Trim()
 if($LASTEXITCODE -ne 0){throw 'Project PG authentication failed'}
 $allowed=@('V:/.local/pgdata',"$root/.local/pgdata")
 if($dataDir.Replace('\','/') -notin $allowed){throw 'PG data directory not in this project allowlist'}
 $exists=& "$pg/psql.exe" -w -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -Atc "select 1 from pg_database where datname='$database'"
 if($LASTEXITCODE -ne 0){throw 'Cannot inspect test database presence'}
 if($exists -eq '1'){
  [pscustomobject]@{kind='environment-recheck';sourceHead=$head;database=$database;port=25432;dataDirectory=$dataDir;existedBefore=$true;created=$false;status='NOT_EXECUTED';reason='Existing database is not a newly created empty test database; tests truncate engine.run and are prohibited on this unverified preexisting database'}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath "$evidence/environment-precondition.json" -Encoding utf8
  [pscustomobject]@{name='engine-runtime';kind='environment-recheck';selectedClasses=@('OwnedFlowSchedulerPostgresTest','OwnedFlowMaterialsPostgresTest');status='NOT_EXECUTED';exitCode=$null;reason='Safety gate: preexisting database';mergeIntoPrimaryBaseline=$false}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath "$evidence/engine-runtime-run.json" -Encoding utf8
  [pscustomobject]@{runId=$runId;path=$evidence;primaryRunId='20261028-164553-272';kind='environment-recheck';mergeIntoPrimaryBaseline=$false}|ConvertTo-Json|Set-Content -LiteralPath "$primary/owned-flow-environment-recheck-link.json" -Encoding utf8
  throw 'Existing OwnedFlow database: no test or truncate executed'
 }
 $created=$false
 if($exists -ne '1'){
  & "$pg/createdb.exe" -w -h 127.0.0.1 -p 25432 -U agent_v12 $database *> "$evidence/create-test-database.raw.txt"
  if($LASTEXITCODE -ne 0){throw 'Dedicated test database creation failed'}
  $created=$true
 }
 $dbIdentity=& "$pg/psql.exe" -w -h 127.0.0.1 -p 25432 -U agent_v12 -d $database -Atc 'select current_database(), current_user'
 if($LASTEXITCODE -ne 0){throw 'Dedicated test database authentication failed'}
 [pscustomobject]@{kind='environment-recheck';sourceHead=$head;primaryRunId='20261028-164553-272';database=$database;port=25432;dataDirectory=$dataDir;existedBefore=($exists -eq '1');created=$created;mutation=if($created){'CREATE DATABASE for this explicitly named dedicated test database only'}else{'None; dedicated database already present'};identity=$dbIdentity;environmentSource='.env current process only';configurationEdited=$false;productCodeEdited=$false;originalBaselinePreserved=$true}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath "$evidence/environment-precondition.json" -Encoding utf8
}finally{[Environment]::SetEnvironmentVariable('PGPASSWORD',$previousPassword,'Process')}
$arguments=@('-B','-ntp','-pl','engine-runtime','test',"-Dmaven.repo.local=$root/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dtest=OwnedFlowSchedulerPostgresTest,OwnedFlowMaterialsPostgresTest')
$started=Get-Date
$code=$null;$state='COLLECTED'
Push-Location -LiteralPath $root
$previous=$ErrorActionPreference
try{
 $ErrorActionPreference='Continue'
 & mvn.cmd @arguments *> "$evidence/engine-runtime.raw.txt"
 $code=$LASTEXITCODE
 if($code -ne 0){$state='COMMAND_FAILED'}
}catch{
 $state='COMMAND_FAILED'
 $_|Out-String|Add-Content -LiteralPath "$evidence/engine-runtime.raw.txt"
}finally{
 $ErrorActionPreference=$previous
 Pop-Location
 $xml=Join-Path $evidence 'engine-runtime-xml'
 New-Item -ItemType Directory -Path $xml -Force|Out-Null
 Get-ChildItem -LiteralPath "$root/engine-runtime/target/surefire-reports" -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue|Where-Object {$_.LastWriteTime -ge $started}|Copy-Item -Destination $xml
 [pscustomobject]@{name='engine-runtime';kind='environment-recheck';root=$root;executable='mvn.cmd';arguments=$arguments;selectedClasses=@('OwnedFlowSchedulerPostgresTest','OwnedFlowMaterialsPostgresTest');startedAt=$started.ToString('o');endedAt=(Get-Date).ToString('o');exitCode=$code;status=$state;sourceHead=$head;primaryRunId='20261028-164553-272';mergeIntoPrimaryBaseline=$false}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath "$evidence/engine-runtime-run.json" -Encoding utf8
 [pscustomobject]@{runId=$runId;path=$evidence;primaryRunId='20261028-164553-272';kind='environment-recheck';mergeIntoPrimaryBaseline=$false}|ConvertTo-Json|Set-Content -LiteralPath "$primary/owned-flow-environment-recheck-link.json" -Encoding utf8
}
Write-Host "OwnedFlow environment recheck path=$evidence exit=$code status=$state"
