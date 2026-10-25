$ErrorActionPreference='Stop'
$agentBase='http://127.0.0.1:18080'
$agentLogin=Invoke-RestMethod -Method Post -Uri "$agentBase/auth/dev-token"
$agentHeaders=@{Authorization="Bearer $($agentLogin.data.accessToken)"}
function Invoke-AgentApi([string]$Method,[string]$Path,$Body=$null){
 $agentParams=@{Method=$Method;Uri="$agentBase$Path";Headers=$agentHeaders;TimeoutSec=60}
 if($null -ne $Body){$agentParams.ContentType='application/json';$agentParams.Body=ConvertTo-Json -InputObject $Body -Depth 40 -Compress}
 $agentEnvelope=Invoke-RestMethod @agentParams
 if($agentEnvelope.code -ne 'OK'){throw "API未成功：$Path，$($agentEnvelope.code)"};return $agentEnvelope.data
}
function Wait-AgentRun([string]$Id,[string[]]$States){
 for($agentPoll=0;$agentPoll -lt 50;$agentPoll++){
  $agentState=Invoke-AgentApi GET "/admin/debug-runs/$Id/status"
  if($agentState.status -in $States){return $agentState}
  if($agentState.status -eq 'FAILED'){throw "运行失败：$Id，$($agentState.error|ConvertTo-Json -Depth 8 -Compress)"}
  Start-Sleep -Milliseconds 300
 };throw "运行未达预期：$Id，$($agentState.status)"
}
function Approve-AgentRun([string]$Id){
 $agentWaiting=Wait-AgentRun $Id @('WAITING_APPROVAL')
 $agentApprovals=Invoke-AgentApi GET '/admin/approvals'
 $agentApproval=@($agentApprovals|Where-Object {$_.runId -eq $Id})[0]
 if(-not $agentApproval){throw '确认中心缺少本次待办'}
 Invoke-AgentApi POST "/admin/approvals/$Id/$($agentApproval.nodeInstanceId)" @{requestId=[Guid]::NewGuid().ToString();approved=$true;snapshotDigest=$agentApproval.snapshotDigest}|Out-Null
 return Wait-AgentRun $Id @('SUCCEEDED')
}
$agentPrior=Get-Content -LiteralPath '.local/write-skill-smoke-result.json' -Raw|ConvertFrom-Json
$agentCode='smoke_flow_'+[Guid]::NewGuid().ToString('N').Substring(0,10)
$agentApp=Invoke-AgentApi POST '/admin/apps' @{code=$agentCode;name='隔离写技能全流程验收';type='FLOW';description='受控HTTP依赖，仅证明跨服务管道'}
$agentDefinition=@{definitionVersion='1.0';trigger='TASK';entry='start';inputs=@(@{name='message';type='STRING';required=$true});outputs=@(@{name='value';from='${write.output.value}'});nodes=@(@{id='start';type='START'},@{id='write';type='SKILL';skillRef=@{code=$agentPrior.skillCode;version=1};args=@{message='${input.message}'}},@{id='end';type='END'});edges=@(@{from='start';to='write'},@{from='write';to='end'});joins=@();executionRules=@{parallel=$false;maxParallel=1;cyclesForbidden=$true}}
$agentApp=Invoke-AgentApi PUT "/admin/flows/$agentCode" @{draftRev=$agentApp.draftRev;definition=$agentDefinition;layout=@{}}
$agentApp=Invoke-AgentApi PUT "/admin/apps/$agentCode/config" @{draftRev=$agentApp.draftRev;config=@{guard=@{roles=@('EDITOR');attributes=@{}}}}
$agentBefore=(Invoke-RestMethod -Uri 'http://127.0.0.1:28411/stats').writes
$agentReport=Invoke-AgentApi POST '/admin/evaluations' @{appCode=$agentCode;draftRev=$agentApp.draftRev;candidateSha256=$agentApp.candidateSha256;datasetVersion='smoke_write_v1';cases=@(@{id='controlled_write';inputs=@{message='formal-smoke'};expected=@{value='formal-smoke'};safety=$true})}
if($agentReport.status -ne 'RUNNING'){throw "写评测应等待确认：$($agentReport|ConvertTo-Json -Depth 12 -Compress)"}
$agentEvalRun=$agentReport.results[0].runId
$agentEvalDone=Approve-AgentRun $agentEvalRun
$agentReport=Invoke-AgentApi POST "/admin/evaluations/$($agentReport.id)/resume"
if($agentReport.status -ne 'ACCEPTED'){throw "候选报告未通过：$($agentReport|ConvertTo-Json -Depth 12 -Compress)"}
$agentCheck=Invoke-AgentApi POST "/admin/apps/$agentCode/publish/precheck"
if(-not $agentCheck.passed){throw "预检未通过：$($agentCheck|ConvertTo-Json -Depth 12 -Compress)"}
$agentPublished=Invoke-AgentApi POST "/admin/apps/$agentCode/publish/commit" @{precheckId=$agentCheck.precheckId;draftRev=$agentApp.draftRev;candidateSha256=$agentApp.candidateSha256;idempotencyRequestNo=[Guid]::NewGuid().ToString()}
$agentFormal=Invoke-AgentApi POST "/admin/apps/$agentCode/runs" @{inputs=@{message='formal-smoke'};requestId=[Guid]::NewGuid().ToString();mocks=@(@{skillCode=$agentPrior.skillCode;mode='FIXED';fixedResponse=@{ok=$true;value='forged-mock'}})}
$agentFormalDone=Approve-AgentRun $agentFormal.runId
if($agentFormalDone.output.value -ne 'formal-smoke'){throw '正式输出或后继变量映射不正确'}
$agentAfter=(Invoke-RestMethod -Uri 'http://127.0.0.1:28411/stats').writes
if($agentAfter -ne $agentBefore+2){throw '评测和正式运行各一次，Mock不得替代正式调用'}
$agentStats=Invoke-AgentApi GET "/admin/skills/$($agentPrior.skillCode)/stats"
$agentFormalStats=@($agentStats|Where-Object {$_.callKind -eq 'FORMAL' -and $_.status -eq 'SUCCEEDED' -and -not $_.isMock})
if($agentFormalStats.Count -lt 1){throw '正式CHAT/TASK用途没有归一到FORMAL真实统计'}
$agentEvidence=[ordered]@{fixtureOnly=$true;appCode=$agentCode;skillCode=$agentPrior.skillCode;evalReportId=$agentReport.id;evaluationRunId=$agentEvalRun;publishedVersion=$agentPublished.versionNo;formalRunId=$agentFormal.runId;formalStatus=$agentFormalDone.status;output=$agentFormalDone.output;actualWrites=$agentAfter-$agentBefore;formalMockIgnored=$true;formalStatistics=$agentFormalStats}
$agentEvidence|ConvertTo-Json -Depth 10|Set-Content -LiteralPath '.local/formal-skill-smoke-result.json' -Encoding utf8
$agentEvidence|ConvertTo-Json -Depth 10
