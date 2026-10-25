param([string[]]$Services=@('platform-gateway','app-management','engine-runtime','skill-registry','model-gateway','monitor'))
$ErrorActionPreference='Stop'
$agentRoot=Split-Path -Parent $PSScriptRoot
$agentManifest=Join-Path $agentRoot '.local/services.json'
if(-not(Test-Path -LiteralPath $agentManifest)){Write-Output '没有本工程登记的服务进程';return}
$agentRegistered=@(Get-Content -LiteralPath $agentManifest -Raw | ConvertFrom-Json)
foreach($agentEntry in $agentRegistered){
 if($agentEntry.service -notin $Services){continue}
 $agentTargetPid=[int]$agentEntry.pid
 $agentProcess=Get-CimInstance Win32_Process -Filter "ProcessId=$agentTargetPid" -ErrorAction SilentlyContinue
 if($null -eq $agentProcess){continue}
 $agentExpectedJar=[IO.Path]::GetFullPath($agentEntry.jar)
 $agentBoundary=[IO.Path]::GetFullPath($agentRoot).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
 if(-not $agentExpectedJar.StartsWith($agentBoundary,[StringComparison]::OrdinalIgnoreCase)){throw '进程记录越出本工程，拒绝停止'}
 if($agentProcess.Name -ne 'java.exe' -or -not $agentProcess.CommandLine.Contains($agentExpectedJar)){Write-Output "PID ${agentTargetPid}与本工程命令不符，跳过";continue}
 Stop-Process -Id $agentTargetPid
 Write-Output "已停止本工程 $($agentEntry.service)，PID=$agentTargetPid"
}
