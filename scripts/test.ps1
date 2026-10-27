param([string[]]$Modules=@(),[switch]$Frontend,[switch]$WithCache,[int]$ForkTimeoutSeconds=900)
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
# model-gateway 与部分引擎用例要连本工程专用 Redis（26379）。默认不启动，避免测试顺带改变本机状态；
# 需要时显式 -WithCache，脚本只认 .env 里的 AGENT_REDIS_*，端口被别的进程占用会直接失败而不是接管。
if($WithCache){& (Join-Path $PSScriptRoot 'start-cache.ps1')}
# 已知坑 Y-2：默认堆=物理内存 25%，七模块顺序跑会在 fork 里 native OOM（G1 virtual space 映射失败），
# 且 surefire 默认不设 fork 超时，HTTP 夹具挂起会把整轮拖成"无限等待"。两者都在此显式钉死。
$agentArgLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp"
$agentArgs=@('-B','-ntp','test',"-Dmaven.repo.local=$agentRoot/.tools/m2","-DargLine=$agentArgLine","-Dsurefire.timeout=$ForkTimeoutSeconds")
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
