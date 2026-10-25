param([switch]$WithObjectStore,[switch]$WithCache)
$ErrorActionPreference='Stop'
$env:WSL_UTF8='1'
$agentRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $agentRoot
New-Item -ItemType Directory -Path '.tools' -Force|Out-Null
if(-not(Test-Path -LiteralPath '.tools/postgresql-16.15/pgsql/bin/postgres.exe')){
 $agentArchive='.tools/postgresql-16.15-windows-x64.zip'
 Invoke-WebRequest -Uri 'https://get.enterprisedb.com/postgresql/postgresql-16.15-1-windows-x64-binaries.zip' -OutFile $agentArchive -TimeoutSec 180
 Expand-Archive -LiteralPath $agentArchive -DestinationPath '.tools/postgresql-16.15'
}
& (Join-Path $PSScriptRoot 'start-database.ps1')
if($WithObjectStore){
 if(-not(Test-Path -LiteralPath '.tools/bin/minio.exe')){
  $agentGoArchive='.tools/go1.27.2.windows-amd64.zip'
  if(-not(Test-Path -LiteralPath '.tools/go-1.27.2/go/bin/go.exe')){
   Invoke-WebRequest -Uri 'https://go.dev/dl/go1.27.2.windows-amd64.zip' -OutFile $agentGoArchive -TimeoutSec 180
   if((Get-FileHash -LiteralPath $agentGoArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne '1314008898bd40df77af4b014f777f08873dbdfbcd3d92308728ee03304fe04f'){throw 'Go官方SHA256不匹配'}
   Expand-Archive -LiteralPath $agentGoArchive -DestinationPath '.tools/go-1.27.2'
  }
  $env:GOROOT=(Join-Path $agentRoot '.tools/go-1.27.2/go');$env:GOPATH="$agentRoot/.tools/gopath";$env:GOMODCACHE="$agentRoot/.tools/gomodcache";$env:GOCACHE="$agentRoot/.tools/gobuildcache";$env:GOBIN="$agentRoot/.tools/bin";$env:GOTOOLCHAIN='local';$env:CGO_ENABLED='0';$env:GOFLAGS='-p=4';$env:GOTMPDIR='V:/.local/tmp'
  New-Item -ItemType Directory -Path '.tools/bin','.local/tmp' -Force|Out-Null
  & "$env:GOROOT/bin/go.exe" install github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z
  if($LASTEXITCODE -ne 0){throw '固定官方源码MinIO编译失败'}
 }
 & (Join-Path $PSScriptRoot 'start-object-store.ps1')
 & (Join-Path $PSScriptRoot 'initialize-object-store.ps1')
}
if($WithCache){
 $agentWslRoot=Join-Path $agentRoot '.local/wsl'
 if(-not(Test-Path -LiteralPath (Join-Path $agentWslRoot 'ext4.vhdx'))){
  $agentDistroNames=((& wsl.exe --list --quiet)|Out-String).Replace([string][char]0,'')
  if($agentDistroNames -match '(?m)^AgentPlatformV12\s*$'){throw '同名WSL已存在但不属于本工程，拒绝复用'}
  $agentAlpineArchive='.tools/alpine-minirootfs-3.24.1-x86_64.tar.gz'
  Invoke-WebRequest -Uri 'https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.1-x86_64.tar.gz' -OutFile $agentAlpineArchive -TimeoutSec 60
  $agentChecksum=Invoke-RestMethod -Uri 'https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/x86_64/alpine-minirootfs-3.24.1-x86_64.tar.gz.sha512' -TimeoutSec 20
  if((Get-FileHash -LiteralPath $agentAlpineArchive -Algorithm SHA512).Hash.ToLowerInvariant() -ne $agentChecksum.Split(' ')[0].Trim()){throw 'Alpine官方SHA512不匹配'}
  New-Item -ItemType Directory -Path $agentWslRoot -Force|Out-Null
  & wsl.exe --import AgentPlatformV12 $agentWslRoot (Join-Path $agentRoot $agentAlpineArchive) --version 2
  if($LASTEXITCODE -ne 0){throw '专用WSL导入失败；不启动或操作其他发行版'}
 }
 & (Join-Path $PSScriptRoot 'install-cache.ps1')
 & (Join-Path $PSScriptRoot 'start-cache.ps1')
}
Write-Output '新工程基础设施准备结束；未修改全局JDK/Node/旧项目或已有容器'
