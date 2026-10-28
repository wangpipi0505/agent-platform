param([ValidateSet('Prepare','Install','APTests','ORTests','Frontend')][string]$Phase)
$ErrorActionPreference = 'Stop'
$ApRoot = 'G:/207/项目/算法重组工具/代码/agent-platform'
$OntoRoot = 'G:/207/项目/算法重组工具/代码/ontorepo'
$RunId = '20261028-164553-272'
$ApEvidence = Join-Path $ApRoot "docs/evidence/2026-10-28-EX-00/baseline/$RunId"
$OrEvidence = Join-Path $OntoRoot "docs/evidence/2026-10-28-EX-00/baseline/$RunId"
$JdkRoot = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:JAVA_HOME = $JdkRoot
$env:PATH = "$JdkRoot/bin;$env:PATH"
$env:MAVEN_OPTS = '-Xmx512m'
$env:NODE_OPTIONS = '--max-old-space-size=1024'
$OntoP4OnlyTests = @('JdbcOutboxStoreTest','OutboxRelayTest','OutboxLeaseWaitAuditTest','OutboxClockAuditTest','ReleaseEventServiceTest','TraceAuditServiceTest','ReleaseRestoreTest')
function Import-AP {
 Get-Content -LiteralPath "$ApRoot/.env" | ForEach-Object { if ($_ -match '^([^#=]+)=(.*)$') {[Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')} }
 $env:ENGINE_PG_TEST = 'true'
}
function Import-OR {
 . "$OntoRoot/deploy/Environment.ps1"
 $script:OntoConfig = Import-OntoEnvironment
 Set-OntoJava -JavaHome $JdkRoot
 Set-OntoBuildLimits
 $script:OntoPgPort = 0
 if (-not [int]::TryParse([string]$OntoConfig.ONTO_PG_PORT,[ref]$script:OntoPgPort) -or $OntoPgPort -lt 1 -or $OntoPgPort -gt 65535) {throw 'Invalid project PG port'}
 $url = [string]$OntoConfig.ONTO_TEST_DB_URL
 $pattern = '^jdbc:postgresql://127\.0\.0\.1:{0}/onto_test(?:_p[34])?(?:\?[^#]*)?$' -f $OntoPgPort
 if ($url -notmatch $pattern) {throw 'Test database URL is outside the dedicated project allowlist'}
 $script:OntoBaseTestUrl = $url -replace '/onto_test(?:_p[34])?(?=\?|$)','/onto_test'
 $script:OntoP4TestUrl = $url -replace '/onto_test(?:_p[34])?(?=\?|$)','/onto_test_p4'
 $env:ONTO_TEST_DB_URL = $OntoBaseTestUrl
}
function Run-Command([string]$Root,[string]$Evidence,[string]$Name,[string]$Executable,[string[]]$Arguments) {
 $started = Get-Date
 $code = $null
 $state = 'COLLECTED'
 Push-Location -LiteralPath $Root
 $previous = $ErrorActionPreference
 try {
  $ErrorActionPreference = 'Continue'
  & $Executable @Arguments *> (Join-Path $Evidence "$Name.raw.txt")
  $code = $LASTEXITCODE
  if ($code -ne 0) {$state = 'COMMAND_FAILED'}
 } catch {
  $state = 'COMMAND_FAILED'
  $_ | Out-String | Add-Content -LiteralPath (Join-Path $Evidence "$Name.raw.txt")
 } finally {
  $ErrorActionPreference = $previous
  Pop-Location
 }
 $result=[pscustomobject]@{name=$Name;root=$Root;executable=$Executable;arguments=$Arguments;startedAt=$started.ToString('o');endedAt=(Get-Date).ToString('o');exitCode=$code;status=$state}
 $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $Evidence "$Name-run.json") -Encoding utf8
 Write-Host "$Name exit=$code status=$state"
 return $result
}
function Archive-Xml([string]$Root,[string]$Module,[string]$Dest,[datetime]$Started) {
 New-Item -ItemType Directory -Path $Dest -Force | Out-Null
 Get-ChildItem -LiteralPath "$Root/$Module/target/surefire-reports" -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
  Where-Object {$_.LastWriteTime -ge $Started} | Copy-Item -Destination $Dest
}
function Get-ORClasses([string]$Module,[string]$Database) {
 $testRoot=Join-Path $OntoRoot "$Module/src/test/java"
 if (-not(Test-Path -LiteralPath $testRoot)) {return}
 $files=@(Get-ChildItem -LiteralPath $testRoot -Filter '*Test.java' -Recurse -File)
 if (@($files|Group-Object BaseName|Where-Object {$_.Count -gt 1}).Count -gt 0) {throw "Duplicate test class names: $Module"}
 foreach($file in @($files|Sort-Object BaseName)){
  $isP4=$file.BaseName -in $OntoP4OnlyTests
  if (($Database -eq 'P4' -and $isP4) -or ($Database -eq 'BASE' -and -not $isP4)) {$file.BaseName}
 }
}
if($Phase -eq 'Prepare'){
 Import-AP
 $prepareAttempt=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
 $prep=Join-Path $ApEvidence "prepare-$prepareAttempt.raw.txt"
 $orPrep=Join-Path $OrEvidence "prepare-$prepareAttempt.raw.txt"
 & "$ApRoot/scripts/stop-backend.ps1" *> $prep
 & "$ApRoot/scripts/start-database.ps1" *>> $prep
 & "$ApRoot/scripts/start-cache.ps1" *>> $prep
 & "$ApRoot/scripts/start-object-store.ps1" *>> $prep
 $cache=& "$ApRoot/scripts/verify-cache.ps1"
 $cache|ConvertTo-Json|Set-Content -LiteralPath "$ApEvidence/cache-readiness.json" -Encoding utf8
 $previousPgPassword=[Environment]::GetEnvironmentVariable('PGPASSWORD','Process')
 $env:PGPASSWORD=$env:AGENT_DB_PASSWORD
 try {
  $pg=Join-Path $ApRoot '.tools/postgresql-16.15/pgsql/bin'
  $dbs=& "$pg/psql.exe" -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -Atc "select datname from pg_database where datname like 'agent_platform%' order by datname"
  if($LASTEXITCODE -ne 0){throw 'AP PG list failed'}
  $dbs|Set-Content -LiteralPath "$ApEvidence/database-names.txt" -Encoding utf8
  foreach($testDb in @('agent_platform_model_test','agent_platform_engine_test','agent_platform_statistics_test')){
   if($testDb -notin $dbs){throw "Required existing AP test database missing: $testDb; needs coordinated preparation"}
  }
  & "$pg/psql.exe" -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -Atc "show data_directory; select version();" *> "$ApEvidence/pg-readiness.txt"
  if($LASTEXITCODE -ne 0){throw 'AP PG authentication failed'}
 } finally {[Environment]::SetEnvironmentVariable('PGPASSWORD',$previousPgPassword,'Process')}
 $null=Run-Command $ApRoot $ApEvidence 'verify-contracts' 'node' @('scripts/verify-contracts.mjs')
 $null=Run-Command $ApRoot $ApEvidence 'verify-capability-package' 'node' @('scripts/verify-capability-package.mjs')
 . "$OntoRoot/deploy/Environment.ps1"
 Import-OR
 & "$OntoRoot/deploy/Stop-Local.ps1" *> $orPrep
 $ascii=Get-OntoAsciiRoot $OntoConfig.ONTO_LOCAL_DRIVE
 $pgData=Join-Path $ascii '.runtime/pgsql'
 & (Join-Path $OntoConfig.ONTO_PG_BIN 'pg_ctl.exe') -D $pgData status *> "$OrEvidence/pg-status.txt"
 if($LASTEXITCODE -ne 0){ & "$OntoRoot/deploy/Initialize-Local.ps1" -PostgresBin $OntoConfig.ONTO_PG_BIN -Drive $OntoConfig.ONTO_LOCAL_DRIVE -JavaHome $JdkRoot *>> $orPrep; Import-OR }
 $previousPgPassword=[Environment]::GetEnvironmentVariable('PGPASSWORD','Process')
 $env:PGPASSWORD=$OntoConfig.ONTO_DB_PASSWORD
 try {
  $dbs=& (Join-Path $OntoConfig.ONTO_PG_BIN 'psql.exe') -h 127.0.0.1 -p $OntoPgPort -U $OntoConfig.ONTO_DB_USER -d postgres -Atc "select datname from pg_database where datname like 'onto_%' order by datname"
  if($LASTEXITCODE -ne 0){throw 'OR PG list failed'}
  $dbs|Set-Content -LiteralPath "$OrEvidence/database-names.txt" -Encoding utf8
  if('onto_test' -notin $dbs -or 'onto_test_p4' -notin $dbs){throw 'Required existing OR test database missing'}
  & (Join-Path $OntoConfig.ONTO_PG_BIN 'psql.exe') -h 127.0.0.1 -p $OntoPgPort -U $OntoConfig.ONTO_DB_USER -d postgres -Atc "show data_directory; select version();" *> "$OrEvidence/pg-readiness.txt"
  if($LASTEXITCODE -ne 0){throw 'OR PG authentication failed'}
 } finally {[Environment]::SetEnvironmentVariable('PGPASSWORD',$previousPgPassword,'Process')}
 $storage=$null
 if(Test-Path -LiteralPath "$OntoRoot/.runtime/storage-process.json"){
  $storageRecord=Get-Content -LiteralPath "$OntoRoot/.runtime/storage-process.json" -Raw | ConvertFrom-Json
  $storage=Get-CimInstance Win32_Process -Filter "ProcessId=$($storageRecord.pid)" -ErrorAction SilentlyContinue
 }
 if($storage){
  if($storage.ExecutablePath -ne $storageRecord.executable -or -not $storage.CommandLine.Contains($storageRecord.marker)){throw 'OR storage ownership mismatch'}
 }else{
  Add-Type -AssemblyName System.Net.Http
  & "$OntoRoot/deploy/Start-Storage.ps1" *>> $orPrep
 }
 foreach($pair in @(@($ApEvidence,'http://127.0.0.1:29000/minio/health/ready'),@($OrEvidence,'http://127.0.0.1:19100/minio/health/ready'))){
  $response=Invoke-WebRequest -Uri $pair[1] -UseBasicParsing -TimeoutSec 5
  "status=$($response.StatusCode)" | Set-Content -LiteralPath (Join-Path $pair[0] 'minio-readiness.txt') -Encoding utf8
 }
 foreach($pair in @(@($ApRoot,$ApEvidence),@($OntoRoot,$OrEvidence))){
  $repo=$pair[0];$evidence=$pair[1]
  git -C $repo rev-parse HEAD | Set-Content -LiteralPath "$evidence/HEAD.txt" -Encoding utf8
  git -C $repo branch --show-current | Set-Content -LiteralPath "$evidence/branch.txt" -Encoding utf8
  git -C $repo status --short | Set-Content -LiteralPath "$evidence/git-status-before.txt" -Encoding utf8
  $reactor=[xml](Get-Content -LiteralPath "$repo/pom.xml" -Raw)
  @(foreach($mod in @($reactor.project.modules.module)) { if(Test-Path -LiteralPath "$repo/$mod/src/test") {rg --files -g '*Test*.java' "$repo/$mod/src/test"} }) | Set-Content -LiteralPath "$evidence/source-test-files.txt" -Encoding utf8
  $migrationFiles=@(foreach($mod in @($reactor.project.modules.module)) { $migrationPath="$repo/$mod/src/main/resources/db/migration"; if(Test-Path -LiteralPath $migrationPath) {Get-ChildItem -LiteralPath $migrationPath -Filter '*.sql' -File} })
  $migrationFiles | ForEach-Object {
   [pscustomobject]@{path=$_.FullName.Substring($repo.Length+1);sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
  } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$evidence/migration-inventory.json" -Encoding utf8
 }
 [pscustomobject]@{powershell=$PSVersionTable.PSVersion.ToString();jdk=$JdkRoot;apPgPort=25432;apPgData='V:/.local/pgdata';apRedisPort=26379;apMinioPort=29000;orPgPort=$OntoPgPort;orPgData=$pgData;orMinioPort=19100;orBaseDatabase='onto_test';orP4Database='onto_test_p4';apConfig='.env';orConfig='.runtime/local-env.json';apiState='Stopped own registered API processes; PG Redis MinIO retained'} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath "$ApEvidence/environment.json" -Encoding utf8
 Copy-Item -LiteralPath "$ApEvidence/environment.json" -Destination "$OrEvidence/environment.json"
 Write-Output 'Prepare COMPLETE'
}
if($Phase -eq 'Install'){
 Import-AP
 $ap=Run-Command $ApRoot $ApEvidence 'install' 'mvn.cmd' @('-B','-ntp','install','-DskipTests',"-Dmaven.repo.local=$ApRoot/.tools/m2")
 . "$OntoRoot/deploy/Environment.ps1"
 Import-OR
 $or=Run-Command $OntoRoot $OrEvidence 'install' 'mvn.cmd' @('-B','-ntp','install','-DskipTests')
 if($ap.exitCode -ne 0 -or $or.exitCode -ne 0){throw 'Install failure; dependent tests prohibited'}
}
if($Phase -eq 'APTests'){
 if(Test-Path -LiteralPath "$ApEvidence/APTests-started.json"){throw 'APTests already started for this RunId; use a fresh RunId'}
 @{phase=$Phase;startedAt=(Get-Date).ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath "$ApEvidence/APTests-started.json" -Encoding utf8
 Import-AP
 $install=Get-Content -LiteralPath "$ApEvidence/install-run.json" -Raw|ConvertFrom-Json
 if($install.exitCode -ne 0){throw 'AP current-source install not successful'}
 foreach($module in @('admin-common','platform-gateway','app-management','engine-runtime','skill-registry','model-gateway','monitor')){
  $started=Get-Date
  $null=Run-Command $ApRoot $ApEvidence $module 'mvn.cmd' @('-B','-ntp','-pl',$module,'test',"-Dmaven.repo.local=$ApRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dmaven.test.failure.ignore=true')
  Archive-Xml $ApRoot $module (Join-Path $ApEvidence "$module-xml") $started
 }
}
if($Phase -eq 'ORTests'){
 if(Test-Path -LiteralPath "$OrEvidence/ORTests-started.json"){throw 'ORTests already started for this RunId; use a fresh RunId'}
 @{phase=$Phase;startedAt=(Get-Date).ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath "$OrEvidence/ORTests-started.json" -Encoding utf8
 . "$OntoRoot/deploy/Environment.ps1"
 Import-OR
 $install=Get-Content -LiteralPath "$OrEvidence/install-run.json" -Raw|ConvertFrom-Json
 if($install.exitCode -ne 0){throw 'OR current-source install not successful'}
 $pom=[xml](Get-Content -LiteralPath "$OntoRoot/pom.xml" -Raw)
 $ascii=Get-OntoAsciiRoot $OntoConfig.ONTO_LOCAL_DRIVE
 $tmp=Join-Path $ascii '.runtime/tmp'
 New-Item -ItemType Directory -Path $tmp -Force|Out-Null
 foreach($module in @($pom.project.modules.module)){
  $count=0
  foreach($database in @('BASE','P4')){
   $classes=@(Get-ORClasses $module $database);$count+=$classes.Count
   if($classes.Count -eq 0){continue}
   $dir=Join-Path $OrEvidence "$module-$database"
   New-Item -ItemType Directory -Path $dir -Force|Out-Null
   $started=Get-Date
   $previousTestUrl=[Environment]::GetEnvironmentVariable('ONTO_TEST_DB_URL','Process')
   $status='COLLECTED';$exitCode=$null
   try {
    if($database -eq 'P4' -and $OntoP4TestUrl -notmatch '^jdbc:postgresql://127\.0\.0\.1:15472/onto_test_p4(?:\?[^#]*)?$'){throw 'P4 not executed: fixture requires 15472'}
    $env:ONTO_TEST_DB_URL=if($database -eq 'P4'){$OntoP4TestUrl}else{$OntoBaseTestUrl}
    $result=Run-Command $OntoRoot $dir 'test' 'mvn.cmd' @('-B','-ntp','-pl',$module,'test',"-DargLine=-Xmx512m -Djava.io.tmpdir=$tmp -Djdk.net.unixdomain.tmpdir=$tmp",'-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dmaven.test.failure.ignore=true',"-Dtest=$($classes -join ',')")
    $status=$result.status;$exitCode=$result.exitCode
   }catch{
    $status='COMMAND_FAILED'
    $_|Out-String|Add-Content -LiteralPath "$dir/test.raw.txt"
   }finally{
    [Environment]::SetEnvironmentVariable('ONTO_TEST_DB_URL',$previousTestUrl,'Process')
    Archive-Xml $OntoRoot $module "$dir/xml" $started
    [pscustomobject]@{module=$module;database=$database;classes=$classes;startedAt=$started.ToString('o');endedAt=(Get-Date).ToString('o');exitCode=$exitCode;status=$status}|ConvertTo-Json -Depth 6|Set-Content -LiteralPath "$dir/run.json" -Encoding utf8
   }
  }
  if($count -eq 0){"No *Test.java; no test pass claimed" | Set-Content -LiteralPath "$OrEvidence/$module-no-tests.txt" -Encoding utf8}
 }
}
if($Phase -eq 'Frontend'){
 if(Test-Path -LiteralPath "$ApEvidence/Frontend-started.json"){throw 'Frontend already started for this RunId; use a fresh RunId'}
 @{phase=$Phase;startedAt=(Get-Date).ToString('o')}|ConvertTo-Json|Set-Content -LiteralPath "$ApEvidence/Frontend-started.json" -Encoding utf8
 Import-AP
 $null=Run-Command "$ApRoot/web" $ApEvidence 'web-test' 'npm.cmd' @('test')
 $null=Run-Command "$ApRoot/web" $ApEvidence 'web-build' 'npm.cmd' @('run','build')
 . "$OntoRoot/deploy/Environment.ps1"
 Import-OR
 $dependencyInstall=Run-Command "$OntoRoot/web" $OrEvidence 'web-install' 'pnpm.cmd' @('install','--frozen-lockfile')
 if($dependencyInstall.exitCode -ne 0){
  foreach($task in @('test','typecheck','format:check','build')){
   $name='web-'+($task -replace ':','-')
   [pscustomobject]@{name=$name;status='NOT_EXECUTED';reason='pnpm install --frozen-lockfile failed; existing dependencies are not accepted as current-source evidence';exitCode=$null}|ConvertTo-Json|Set-Content -LiteralPath "$OrEvidence/$name-run.json" -Encoding utf8
  }
  Write-Host 'OR frontend NOT_EXECUTED: current dependency install failed'
  return
 }
 foreach($task in @('test','typecheck','format:check','build')){
  $name='web-'+($task -replace ':','-')
  $null=Run-Command "$OntoRoot/web" $OrEvidence $name 'pnpm.cmd' @($task)
 }
}
