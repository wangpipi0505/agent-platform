$ErrorActionPreference='Stop'
$env:WSL_UTF8='1'
$agentRoot=Split-Path -Parent $PSScriptRoot
if(-not(Test-Path -LiteralPath (Join-Path $agentRoot '.local/wsl/ext4.vhdx'))){throw '缺少本工程新建WSL；不使用其他发行版'}
$agentArchive=Join-Path $agentRoot '.tools/redis-7.2.16.tar.gz'
if(-not(Test-Path -LiteralPath $agentArchive)){Invoke-WebRequest -Uri 'https://download.redis.io/releases/redis-7.2.16.tar.gz' -OutFile $agentArchive -TimeoutSec 60}
# redis/redis-hashes官方README的固定版本SHA256，不能静默改为当前latest。
if((Get-FileHash -LiteralPath $agentArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne '960a8ec15e34ff40e57ff16837b26b33bd81f2da6d24497bb63de532a323a18e'){throw 'Redis官方源码摘要不符'}
& wsl.exe -d AgentPlatformV12 --exec /sbin/apk add --no-cache build-base linux-headers
if($LASTEXITCODE -ne 0){throw '专用WSL编译依赖失败'}
$agentLinuxArchive=((& wsl.exe -d AgentPlatformV12 -- wslpath -a -u $agentArchive.Replace('\','/'))|Out-String).Trim()
if($LASTEXITCODE -ne 0 -or -not $agentLinuxArchive){throw '源码路径转换失败'}
$agentLinuxScript=((& wsl.exe -d AgentPlatformV12 -- wslpath -a -u (Join-Path $PSScriptRoot 'build-cache.sh').Replace('\','/'))|Out-String).Trim()
if($LASTEXITCODE -ne 0 -or -not $agentLinuxScript){throw '编译脚本路径转换失败'}
& wsl.exe -d AgentPlatformV12 --exec /bin/sh $agentLinuxScript $agentLinuxArchive
if($LASTEXITCODE -ne 0){throw '固定Redis7.2.16编译失败'}
