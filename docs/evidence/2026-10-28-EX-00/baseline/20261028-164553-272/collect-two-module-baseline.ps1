$ErrorActionPreference='Stop'
$root='G:/207/项目/算法重组工具/代码/agent-platform'
$primary=Join-Path $root 'docs/evidence/2026-10-28-EX-00/baseline/20261028-164553-272'
$head=(& git -C $root rev-parse HEAD).Trim()
if($head -ne 'ebbfd62ca1d19efc4d172804efb5ac79453ceb64'){throw 'AP source HEAD changed since original baseline'}
$scopedChanges=@(& git -C $root diff --name-only -- admin-common app-management engine-runtime pom.xml)
if($scopedChanges.Count -gt 0){throw 'AP tested source/dependencies differ from original baseline'}
$preconditions=@(
 (Get-Content -LiteralPath "$root/docs/evidence/2026-10-28-EX-00/environment-recheck/20261028-181751-069/environment-precondition.json" -Raw|ConvertFrom-Json),
 (Get-Content -LiteralPath "$root/docs/evidence/2026-10-28-EX-00/environment-recheck/20261028-181800-266/environment-precondition.json" -Raw|ConvertFrom-Json)
)
foreach($proof in $preconditions){
 if(-not $proof.created -or $proof.existedBefore){throw 'Database was not confirmed newly created by this EX-00 run'}
}
$runId=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$evidence=Join-Path $root "docs/evidence/2026-10-28-EX-00/baseline/$runId"
if(Test-Path -LiteralPath $evidence){throw 'Module recollection RunId exists'}
New-Item -ItemType Directory -Path $evidence -Force|Out-Null
$head|Set-Content -LiteralPath "$evidence/HEAD.txt" -Encoding utf8
git -C $root branch --show-current|Set-Content -LiteralPath "$evidence/branch.txt" -Encoding utf8
Get-Content -LiteralPath "$root/.env"|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$env:ENGINE_PG_TEST='true'
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:MAVEN_OPTS='-Xmx512m'
$env:NODE_OPTIONS='--max-old-space-size=1024'
$previousPassword=[Environment]::GetEnvironmentVariable('PGPASSWORD','Process')
$env:PGPASSWORD=$env:AGENT_DB_PASSWORD
try{
 foreach($database in @('agent_platform_app_copy_test','agent_platform_iam_owned_flow_v12')){
  $found=& "$root/.tools/postgresql-16.15/pgsql/bin/psql.exe" -w -h 127.0.0.1 -p 25432 -U agent_v12 -d $database -Atc 'select current_database()'
  if($LASTEXITCODE -ne 0 -or $found -ne $database){throw 'Owned newly-created test database readiness failed'}
 }
}finally{[Environment]::SetEnvironmentVariable('PGPASSWORD',$previousPassword,'Process')}
[pscustomobject]@{kind='baseline-module-recollection';modules=@('app-management','engine-runtime');sourceHead=$head;primaryRunId='20261028-164553-272';mergeIntoOriginalBaseline=$false;onlyDeclaredAPPreconditionChanges=@('agent_platform_app_copy_test newly created in environment-recheck/20261028-181751-069','agent_platform_iam_owned_flow_v12 newly created in environment-recheck/20261028-181800-266');scopedTrackedChanges=$scopedChanges;environmentSource='.env current process as original AP run';AGENT_DEV_AUTH=($env:AGENT_DEV_AUTH -eq 'true');ENGINE_PG_TEST=($env:ENGINE_PG_TEST -eq 'true');AGENT_SECURITY_MODE_PRESENT=[bool]$env:AGENT_SECURITY_MODE;argLine='-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp';buildDependencyInstall='Original current-source install succeeded; source/dependencies unchanged; no -am'}|ConvertTo-Json -Depth 7|Set-Content -LiteralPath "$evidence/module-scope.json" -Encoding utf8
foreach($module in @('app-management','engine-runtime')){
 $started=Get-Date
 $testArguments=@('-B','-ntp','-pl',$module,'test',"-Dmaven.repo.local=$root/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.timeout=900','-Dmaven.test.failure.ignore=true')
 $testExit=$null;$state='COLLECTED';$previous=$ErrorActionPreference
 Push-Location -LiteralPath $root
 try{
  $ErrorActionPreference='Continue'
  & mvn.cmd @testArguments *> "$evidence/$module.raw.txt"
  $testExit=$LASTEXITCODE
  if($testExit -ne 0){$state='COMMAND_FAILED'}
 }catch{
  $state='COMMAND_FAILED'
  $_|Out-String|Add-Content -LiteralPath "$evidence/$module.raw.txt"
 }finally{
  $ErrorActionPreference=$previous
  Pop-Location
  $xml=Join-Path $evidence "$module-xml"
  New-Item -ItemType Directory -Path $xml -Force|Out-Null
  Get-ChildItem -LiteralPath "$root/$module/target/surefire-reports" -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue|Where-Object {$_.LastWriteTime -ge $started}|Copy-Item -Destination $xml
  [pscustomobject]@{name=$module;root=$root;executable='mvn.cmd';arguments=$testArguments;startedAt=$started.ToString('o');endedAt=(Get-Date).ToString('o');exitCode=$testExit;status=$state;kind='baseline-module-recollection';primaryRunId='20261028-164553-272';mergeIntoOriginalBaseline=$false}|ConvertTo-Json -Depth 7|Set-Content -LiteralPath "$evidence/$module-run.json" -Encoding utf8
 }
 Write-Host "$module recollection exit=$testExit status=$state"
}
[pscustomobject]@{runId=$runId;path=$evidence;kind='baseline-module-recollection';modules=@('app-management','engine-runtime');mergeIntoOriginalBaseline=$false}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath "$primary/two-module-baseline-link.json" -Encoding utf8
Write-Host "Two-module recollection path=$evidence"
