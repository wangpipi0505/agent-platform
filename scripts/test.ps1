param([string[]]$Modules=@(),[switch]$Frontend)
$ErrorActionPreference='Stop'
$agentRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $agentRoot
& node (Join-Path $PSScriptRoot 'verify-contracts.mjs')
if($LASTEXITCODE -ne 0){throw '契约或服务边界漂移'}
& (Join-Path $PSScriptRoot 'start-database.ps1')
Get-Content -LiteralPath (Join-Path $agentRoot '.env')|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$env:JAVA_HOME=if($env:AGENT_JAVA_HOME){$env:AGENT_JAVA_HOME}else{'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'}
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:ENGINE_PG_TEST='true'
$env:PGPASSWORD=$env:AGENT_DB_PASSWORD
$agentPg=Join-Path $agentRoot '.tools/postgresql-16.15/pgsql/bin'
# 所有名字为显式白名单；不连接/清理运行开发库或任何既有数据库。
foreach($agentTestDb in @('agent_platform_model_test','agent_platform_engine_test')){
 $agentFound=& (Join-Path $agentPg 'psql.exe') -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -c "select 1 from pg_database where datname='$agentTestDb'"
 if($agentFound -ne '1'){& (Join-Path $agentPg 'createdb.exe') -h 127.0.0.1 -p 25432 -U agent_v12 $agentTestDb;if($LASTEXITCODE -ne 0){throw "创建专用测试库失败：$agentTestDb"}}
}
Remove-Item Env:PGPASSWORD
$agentArgs=@('-B','-ntp','test',"-Dmaven.repo.local=$agentRoot/.tools/m2",'-Djdk.net.unixdomain.tmpdir=V:/.local/tmp')
if($Modules.Count -gt 0){$agentArgs+=@('-pl',($Modules -join ','),'-am')}
& mvn.cmd @agentArgs
if($LASTEXITCODE -ne 0){throw '后端测试失败；不得计作通过'}
if($Frontend){
 Push-Location -LiteralPath (Join-Path $agentRoot 'web')
 try{
  & npm.cmd test;if($LASTEXITCODE -ne 0){throw '前端测试失败'}
  & npm.cmd run build;if($LASTEXITCODE -ne 0){throw '前端构建失败'}
 }finally{Pop-Location}
}
