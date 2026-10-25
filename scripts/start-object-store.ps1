$ErrorActionPreference='Stop'
$agentRoot=Split-Path -Parent $PSScriptRoot
$agentEnv=Join-Path $agentRoot '.env'
if(-not(Test-Path -LiteralPath $agentEnv)){throw '先运行新工程start-database.ps1生成独立配置'}
$agentValues=@{}
Get-Content -LiteralPath $agentEnv | ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$agentValues[$Matches[1]]=$Matches[2]}}
$agentDefaults=@{AGENT_MINIO_ENDPOINT='http://127.0.0.1:29000';AGENT_MINIO_BUCKET='agent-platform'}
if(-not $agentValues.ContainsKey('AGENT_MINIO_ACCESS_KEY')){$agentDefaults.AGENT_MINIO_ACCESS_KEY='agent_'+[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(12)).ToLowerInvariant()}
if(-not $agentValues.ContainsKey('AGENT_MINIO_SECRET_KEY')){$agentDefaults.AGENT_MINIO_SECRET_KEY=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(36))}
foreach($agentKey in $agentDefaults.Keys){if(-not $agentValues.ContainsKey($agentKey)){$agentValues[$agentKey]=$agentDefaults[$agentKey];Add-Content -LiteralPath $agentEnv -Value "$agentKey=$($agentDefaults[$agentKey])" -Encoding utf8}}
foreach($agentKey in $agentValues.Keys){[Environment]::SetEnvironmentVariable($agentKey,$agentValues[$agentKey],'Process')}
$agentExecutable=Join-Path $agentRoot '.tools/bin/minio.exe'
if(-not(Test-Path -LiteralPath $agentExecutable)){throw '缺少本工程按官方固定源码构建的MinIO，不复用其他实例'}
$agentManifest=Join-Path $agentRoot '.local/minio-process.json'
if(Get-NetTCPConnection -LocalPort 29000 -State Listen -ErrorAction SilentlyContinue){
 if(Test-Path -LiteralPath $agentManifest){
  $agentRegistered=Get-Content -LiteralPath $agentManifest -Raw|ConvertFrom-Json
  $agentProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$($agentRegistered.pid)" -ErrorAction SilentlyContinue
  if($agentProcess -and $agentProcess.ExecutablePath -eq $agentExecutable){Write-Output '本工程MinIO已运行：127.0.0.1:29000';return}
 }
 throw '29000端口被占用且无法证明为本工程进程，拒绝接管'
}
if(Get-NetTCPConnection -LocalPort 29001 -State Listen -ErrorAction SilentlyContinue){throw '29001端口被占用，拒绝接管'}
$agentData=Join-Path $agentRoot '.local/minio'
$agentLogs=Join-Path $agentRoot '.local/logs'
New-Item -ItemType Directory -Path $agentData,$agentLogs -Force|Out-Null
$env:MINIO_ROOT_USER=$env:AGENT_MINIO_ACCESS_KEY
$env:MINIO_ROOT_PASSWORD=$env:AGENT_MINIO_SECRET_KEY
$agentProcess=Start-Process -FilePath $agentExecutable -ArgumentList @('server',('"'+$agentData+'"'),'--address','127.0.0.1:29000','--console-address','127.0.0.1:29001','--quiet') -WorkingDirectory $agentRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $agentLogs 'minio.out.log') -RedirectStandardError (Join-Path $agentLogs 'minio.err.log') -PassThru
[pscustomobject]@{pid=$agentProcess.Id;executable=$agentExecutable;data=$agentData;port=29000}|ConvertTo-Json|Set-Content -LiteralPath $agentManifest -Encoding utf8
Remove-Item Env:MINIO_ROOT_USER,Env:MINIO_ROOT_PASSWORD
Write-Output "本工程MinIO已启动，PID=$($agentProcess.Id)，仅本机29000/29001；凭据仅在本工程.env"
