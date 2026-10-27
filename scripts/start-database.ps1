$ErrorActionPreference='Stop'
$agentRoot=Split-Path -Parent $PSScriptRoot
$agentLocal=Join-Path $agentRoot '.local'
New-Item -ItemType Directory -Path $agentLocal -Force | Out-Null
$agentEnv=Join-Path $agentRoot '.env'
if(-not(Test-Path -LiteralPath $agentEnv)){
  $agentDbPassword=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
  $agentJwtSecret=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48))
  $agentMasterKey=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
  @("AGENT_DB_USER=agent_v12","AGENT_DB_PASSWORD=$agentDbPassword","AGENT_DB_URL=jdbc:postgresql://127.0.0.1:25432/agent_platform_dev_v12","AGENT_JWT_SECRET=$agentJwtSecret","AGENT_MASTER_KEY=$agentMasterKey","AGENT_SECRET_DIR=$agentRoot/.local/secrets","AGENT_DEV_AUTH=true","AGENT_BIND_ADDRESS=127.0.0.1") | Set-Content -LiteralPath $agentEnv -Encoding utf8
}
Get-Content -LiteralPath $agentEnv | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$agentRuntimeRoot=$agentRoot
# Windows版initdb将数据路径写入bootstrap SQL；当前ANSI代码页936会使中文路径破坏UTF8。
# 仅给本新工程创建专属空闲V盘别名，文件实际仍位于本工程；绝不覆盖已用盘符。
if($agentRoot -match '[^\x00-\x7F]'){
  $agentAlias='V:'
  if(Test-Path "$agentAlias\"){
    if(-not('AgentPlatform.DosDevice' -as [type])){
      Add-Type 'using System; using System.Text; using System.Runtime.InteropServices; namespace AgentPlatform { public static class DosDevice { [DllImport("kernel32.dll", CharSet=CharSet.Unicode)] public static extern uint QueryDosDevice(string name, StringBuilder target, int size); }}'
    }
    $agentDeviceTarget=[Text.StringBuilder]::new(4096)
    [void][AgentPlatform.DosDevice]::QueryDosDevice($agentAlias,$agentDeviceTarget,$agentDeviceTarget.Capacity)
    if(-not($agentDeviceTarget.ToString().EndsWith($agentRoot.Replace('/','\'),[StringComparison]::OrdinalIgnoreCase))){throw 'V盘已使用且不属于本工程；不覆盖其他路径'}
  } else {
    & subst.exe $agentAlias $agentRoot
    if($LASTEXITCODE -ne 0){throw '新工程原生工具路径别名创建失败'}
  }
  $agentRuntimeRoot="$agentAlias\"
  $agentLocal=Join-Path $agentRuntimeRoot '.local'
}
$agentPg=Join-Path $agentRuntimeRoot '.tools/postgresql-16.15/pgsql/bin'
if(-not(Test-Path -LiteralPath (Join-Path $agentPg 'pg_ctl.exe'))){throw '先下载并解压项目专用PostgreSQL16.15到.tools；不使用全局或既有实例。'}
$agentPgData=Join-Path $agentLocal 'pgdata'
if(-not(Test-Path -LiteralPath (Join-Path $agentPgData 'PG_VERSION'))){
  $agentPasswordFile=Join-Path $agentLocal 'db-init-password'
  [IO.File]::WriteAllText($agentPasswordFile,$env:AGENT_DB_PASSWORD,[Text.UTF8Encoding]::new($false))
  & (Join-Path $agentPg 'initdb.exe') -D $agentPgData -U agent_v12 --pwfile=$agentPasswordFile --auth-host=scram-sha-256 --auth-local=scram-sha-256 --encoding=UTF8 --locale=C
  if($LASTEXITCODE -ne 0){throw '独立数据库初始化失败'}
  Remove-Item -LiteralPath $agentPasswordFile
}
& (Join-Path $agentPg 'pg_ctl.exe') status -D $agentPgData *> $null
if($LASTEXITCODE -ne 0){
  if(Get-NetTCPConnection -LocalPort 25432 -State Listen -ErrorAction SilentlyContinue){throw '专用25432端口被占用；禁止复用或关闭其他进程'}
  & (Join-Path $agentPg 'pg_ctl.exe') -D $agentPgData -l (Join-Path $agentLocal 'postgres.log') -o '-h 127.0.0.1 -p 25432' -w start
  if($LASTEXITCODE -ne 0){throw '独立数据库启动失败'}
}
$env:PGPASSWORD=$env:AGENT_DB_PASSWORD
foreach($agentDbName in @('agent_platform_v12','agent_platform_dev_v12')){
  $agentDbExists=& (Join-Path $agentPg 'psql.exe') -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -c "SELECT 1 FROM pg_database WHERE datname='$agentDbName'"
  if($agentDbExists -ne '1'){
    & (Join-Path $agentPg 'createdb.exe') -h 127.0.0.1 -p 25432 -U agent_v12 $agentDbName
    if($LASTEXITCODE -ne 0){throw "无法创建新工程数据库：$agentDbName"}
  }
}
Remove-Item Env:PGPASSWORD
Write-Output '独立PostgreSQL就绪：127.0.0.1:25432；开发库dev_v12，集成测试库v12，密钥仅在新工程.env。'
