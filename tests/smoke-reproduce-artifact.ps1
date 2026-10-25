$ErrorActionPreference='Stop'
$agentBase='http://127.0.0.1:18080'
$agentLogin=Invoke-RestMethod -Method Post -Uri "$agentBase/auth/dev-token"
$agentHeaders=@{Authorization="Bearer $($agentLogin.data.accessToken)"}
function Invoke-AgentApi([string]$Method,[string]$Path,$Body=$null){
 $agentParams=@{Method=$Method;Uri="$agentBase$Path";Headers=$agentHeaders;TimeoutSec=90}
 if($null -ne $Body){$agentParams.ContentType='application/json';$agentParams.Body=ConvertTo-Json -InputObject $Body -Depth 30 -Compress}
 $agentEnvelope=Invoke-RestMethod @agentParams
 if($agentEnvelope.code -ne 'OK'){throw "API未成功：$Path，$($agentEnvelope.code)"};return $agentEnvelope.data
}
$agentPrior=Get-Content -LiteralPath '.local/large-artifact-smoke-result.json' -Raw|ConvertFrom-Json
$agentSource=Invoke-AgentApi GET "/admin/runs/$($agentPrior.formalRunId)/replay"
$agentCallsBefore=(Invoke-RestMethod 'http://127.0.0.1:28412/stats').calls
$agentRun=Invoke-AgentApi POST "/admin/runs/$($agentPrior.formalRunId)/reproduce" @{requestId=[Guid]::NewGuid().ToString();sourceDigest=$agentSource.sourceDigest}
for($agentPoll=0;$agentPoll -lt 100;$agentPoll++){
 $agentStatus=Invoke-AgentApi GET "/admin/runs/$($agentRun.runId)"
 if($agentStatus.status -eq 'SUCCEEDED'){break}
 if($agentStatus.status -in @('FAILED','CANCELLED')){$agentStatus.nodes|ConvertTo-Json -Depth 10;throw '受控附件复现失败'}
 Start-Sleep -Milliseconds 300
}
if($agentStatus.status -ne 'SUCCEEDED'){throw '受控附件复现未完成'}
$agentRef=@($agentStatus.fileRefs)[0]
if(-not $agentRef.artifactId -or $agentRef.artifactId -eq $agentPrior.artifact.artifactId){throw '未生成新run附件归属'}
if($agentRef.externalDownloadUrl -ne "/v1/runs/$($agentRun.runId)/artifacts/$($agentRef.artifactId)/download"){throw '附件外部路径不属于当前run'}
$agentDownload=Join-Path (Get-Location) '.local/reproduced-artifact-downloaded.json'
Invoke-WebRequest -Uri "$agentBase$($agentRef.downloadUrl)" -Headers $agentHeaders -OutFile $agentDownload -TimeoutSec 60
$agentHash=(Get-FileHash -LiteralPath $agentDownload -Algorithm SHA256).Hash.ToLowerInvariant()
if($agentHash -ne $agentPrior.artifact.sha256 -or $agentHash -ne $agentRef.sha256){throw '受控复现附件摘要不一致'}
$agentCallsAfter=(Invoke-RestMethod 'http://127.0.0.1:28412/stats').calls
if($agentCallsBefore -ne $agentCallsAfter){throw '受控复现不得重做业务查询'}
$agentEvidence=[ordered]@{fixtureOnly=$true;sourceRunId=$agentPrior.formalRunId;runId=$agentRun.runId;status=$agentStatus.status;artifact=$agentRef;businessCallsAdded=$agentCallsAfter-$agentCallsBefore;downloadSha256Verified=$true}
$agentEvidence|ConvertTo-Json -Depth 10|Set-Content -LiteralPath '.local/reproduce-artifact-smoke-result.json' -Encoding utf8
$agentEvidence|ConvertTo-Json -Depth 10
exit 0
