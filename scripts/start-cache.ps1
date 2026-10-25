$ErrorActionPreference='Stop'
$env:WSL_UTF8='1'
$agentRoot=Split-Path -Parent $PSScriptRoot
$agentEnv=Join-Path $agentRoot '.env'
if(-not(Test-Path -LiteralPath $agentEnv)){throw '先生成新工程独立.env'}
$agentKeys=@{}
Get-Content -LiteralPath $agentEnv|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){$agentKeys[$Matches[1]]=$Matches[2]}}
if(-not $agentKeys.ContainsKey('AGENT_REDIS_PASSWORD')){
 $agentKeys.AGENT_REDIS_PASSWORD=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
 Add-Content -LiteralPath $agentEnv -Value "AGENT_REDIS_PASSWORD=$($agentKeys.AGENT_REDIS_PASSWORD)" -Encoding utf8
}
foreach($agentPair in @(@('AGENT_REDIS_HOST','127.0.0.1'),@('AGENT_REDIS_PORT','26379'))){if(-not $agentKeys.ContainsKey($agentPair[0])){$agentKeys[$agentPair[0]]=$agentPair[1];Add-Content -LiteralPath $agentEnv -Value ($agentPair -join '=') -Encoding utf8}}
foreach($agentKey in $agentKeys.Keys){[Environment]::SetEnvironmentVariable($agentKey,$agentKeys[$agentKey],'Process')}
$agentDistro='AgentPlatformV12'
$agentWslRoot=Join-Path $agentRoot '.local/wsl'
if(-not(Test-Path -LiteralPath (Join-Path $agentWslRoot 'ext4.vhdx'))){throw '缺少新工程专用WSL rootfs；不使用已有Docker发行版/缓存'}
if(Get-NetTCPConnection -LocalPort 26379 -State Listen -ErrorAction SilentlyContinue){
 if(Test-Path -LiteralPath (Join-Path $agentRoot '.local/redis-process.json')){
  $agentCurrent=& (Join-Path $PSScriptRoot 'verify-cache.ps1')
  if($agentCurrent.version -ne '7.2.16'){throw '已监听的本工程实验缓存不符合固定Redis7.2.16；先核对并独立保存停止，启动脚本不自动覆盖数据'}
  Write-Output 'Redis7.2.16已认证并核对本工程数据目录';return
 }
 throw '26379被占用，禁止接管其他进程'
}
$agentDirectory=Join-Path $agentRoot '.local/redis'
New-Item -ItemType Directory -Path $agentDirectory -Force|Out-Null
$agentConfig=Join-Path $agentDirectory 'redis.conf'
$agentLines=@('bind 127.0.0.1','port 26379','protected-mode yes',"requirepass $($agentKeys.AGENT_REDIS_PASSWORD)",'daemonize yes','dir /var/lib/agent-platform-redis7','pidfile /var/lib/agent-platform-redis7/agent-platform.pid','logfile /var/lib/agent-platform-redis7/agent-platform.log','appendonly yes','maxmemory 128mb','maxmemory-policy noeviction')
[IO.File]::WriteAllLines($agentConfig,$agentLines,[Text.UTF8Encoding]::new($false))
$agentLinuxConfig=((& wsl.exe -d $agentDistro -- wslpath -a -u $agentConfig.Replace('\','/'))|Out-String).Trim()
if($LASTEXITCODE -ne 0){throw '专用配置路径转换失败'}
& wsl.exe -d $agentDistro --user redis --exec /opt/agent-platform/redis-7.2.16/src/redis-server $agentLinuxConfig
if($LASTEXITCODE -ne 0){throw '专用Redis启动失败'}
[pscustomobject]@{distribution=$agentDistro;root=$agentWslRoot;port=26379;user='redis'}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $agentRoot '.local/redis-process.json') -Encoding utf8
Write-Output 'Redis已在专用WSL启动：127.0.0.1:26379；数据仅在本工程.local/wsl，随机凭据在本工程.env'
