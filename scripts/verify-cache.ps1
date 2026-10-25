param([switch]$Stop)
$ErrorActionPreference='Stop'
$env:WSL_UTF8='1'
$agentRoot=Split-Path -Parent $PSScriptRoot
Get-Content -LiteralPath (Join-Path $agentRoot '.env')|ForEach-Object {if($_ -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}
$agentRecord=Get-Content -LiteralPath (Join-Path $agentRoot '.local/redis-process.json') -Raw|ConvertFrom-Json
if($agentRecord.distribution -ne 'AgentPlatformV12' -or [IO.Path]::GetFullPath($agentRecord.root) -ne [IO.Path]::GetFullPath((Join-Path $agentRoot '.local/wsl'))){throw '缓存登记不属于本工程'}
$agentSocket=[Net.Sockets.TcpClient]::new('127.0.0.1',[int]$env:AGENT_REDIS_PORT)
$agentSocket.ReceiveTimeout=5000;$agentSocket.SendTimeout=5000
$agentStream=$agentSocket.GetStream()
function Read-AgentRedisLine {
 $agentLineBytes=[Collections.Generic.List[byte]]::new()
 while($true){$agentByte=$agentStream.ReadByte();if($agentByte -lt 0){throw '缓存连接提前结束'};if($agentByte -eq 10){break};if($agentLineBytes.Count -ge 1024){throw '缓存响应头超限'};$agentLineBytes.Add([byte]$agentByte)}
 return [Text.Encoding]::ASCII.GetString($agentLineBytes.ToArray()).TrimEnd("`r")
}
function Read-AgentRedisReply {
 $agentLine=Read-AgentRedisLine
 if($null -eq $agentLine){throw '缓存连接提前结束'}
 switch($agentLine.Substring(0,1)){
  '+' {return $agentLine.Substring(1)}
  '-' {throw '缓存拒绝认证或命令'}
  ':' {return [long]$agentLine.Substring(1)}
  '$' {$agentLength=[int]$agentLine.Substring(1);if($agentLength -lt 0){return $null};if($agentLength -gt 65536){throw '缓存诊断响应超限'};$agentBuffer=[byte[]]::new($agentLength);$agentOffset=0;while($agentOffset -lt $agentLength){$agentRead=$agentStream.Read($agentBuffer,$agentOffset,$agentLength-$agentOffset);if($agentRead -le 0){throw '缓存响应不完整'};$agentOffset+=$agentRead};if((Read-AgentRedisLine) -ne ''){throw '缓存响应结束符错误'};return [Text.Encoding]::UTF8.GetString($agentBuffer)}
  '*' {$agentValues=@();for($agentIndex=0;$agentIndex -lt [int]$agentLine.Substring(1);$agentIndex++){$agentValues+=,(Read-AgentRedisReply)};return ,$agentValues}
  default {throw '缓存协议不正确'}
 }
}
function Send-AgentRedisCommand([string[]]$Parts){
 $agentCommand='*'+$Parts.Count+"`r`n"
 foreach($agentPart in $Parts){$agentCommand+='$'+[Text.Encoding]::UTF8.GetByteCount($agentPart)+"`r`n"+$agentPart+"`r`n"}
 $agentBytes=[Text.Encoding]::UTF8.GetBytes($agentCommand);$agentStream.Write($agentBytes,0,$agentBytes.Length);$agentStream.Flush()
 return Read-AgentRedisReply
}
try{
 if((Send-AgentRedisCommand @('AUTH',$env:AGENT_REDIS_PASSWORD)) -ne 'OK'){throw '缓存认证失败'}
 $agentInfo=Send-AgentRedisCommand @('INFO','server')
 $agentVersion=[regex]::Match($agentInfo,'(?m)^redis_version:([^\r\n]+)').Groups[1].Value
 $agentPidValue=[regex]::Match($agentInfo,'(?m)^process_id:([0-9]+)').Groups[1].Value
 if(-not $agentVersion -or -not $agentPidValue){throw '缓存身份不完整'}
 $agentDirectory=Send-AgentRedisCommand @('CONFIG','GET','dir')
 if($agentDirectory[1] -notin @('/var/lib/redis','/var/lib/agent-platform-redis7')){throw '缓存数据目录不属于新工程专用布局'}
 $agentExecutable=((& wsl.exe -d AgentPlatformV12 --exec /usr/bin/readlink "/proc/$agentPidValue/exe")|Out-String).Trim()
 if($LASTEXITCODE -ne 0 -or $agentExecutable -notin @('/usr/bin/redis-server','/opt/agent-platform/redis-7.2.16/src/redis-server')){throw '缓存进程不属于登记的专用WSL二进制'}
 if($Stop){try{[void](Send-AgentRedisCommand @('SHUTDOWN','SAVE'))}catch{if($agentSocket.Client.Poll(1000,[Net.Sockets.SelectMode]::SelectRead) -and $agentSocket.Available -eq 0){}else{throw}};Write-Output '已保存并停止本工程专用缓存，未操作其他发行版或进程'}
 else{[pscustomobject]@{version=$agentVersion;port=[int]$env:AGENT_REDIS_PORT;authenticated=$true;directory=$agentDirectory[1];distribution='AgentPlatformV12'}}
}finally{$agentStream.Dispose();$agentSocket.Dispose()}
