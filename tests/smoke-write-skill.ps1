$ErrorActionPreference='Stop'
$agentBase='http://127.0.0.1:18080'
$agentLogin=Invoke-RestMethod -Method Post -Uri "$agentBase/auth/dev-token"
$agentHeaders=@{Authorization="Bearer $($agentLogin.data.accessToken)"}
function Invoke-AgentApi([string]$Method,[string]$Path,$Body=$null){
 $agentParams=@{Method=$Method;Uri="$agentBase$Path";Headers=$agentHeaders;TimeoutSec=60}
 if($null -ne $Body){$agentParams.ContentType='application/json';$agentParams.Body=ConvertTo-Json -InputObject $Body -Depth 40 -Compress}
 $agentEnvelope=Invoke-RestMethod @agentParams
 if($agentEnvelope.code -ne 'OK'){throw "API未成功：$Path，$($agentEnvelope.code)"}
 return $agentEnvelope.data
}
function Wait-AgentRun([string]$Id,[string[]]$States){
 for($agentPoll=0;$agentPoll -lt 40;$agentPoll++){
  $agentState=Invoke-AgentApi GET "/admin/debug-runs/$Id/status"
  if($agentState.status -in $States){return $agentState}
  if($agentState.status -eq 'FAILED'){throw "受控运行失败：$Id，$($agentState.error|ConvertTo-Json -Depth 8 -Compress)"}
  Start-Sleep -Milliseconds 300
 }
 throw "受控运行未达预期：$Id，$($agentState.status)"
}
$agentSuffix=[Guid]::NewGuid().ToString('N').Substring(0,10)
$agentProvider=Invoke-AgentApi POST '/admin/model-providers' @{code="smoke_model_$agentSuffix";name='隔离联合测试模型';baseUrl='http://127.0.0.1:28410/v1';trustLabels=@('LOCAL')}
$agentProbe=Invoke-AgentApi POST "/admin/model-providers/$($agentProvider.id)/test"
if(-not $agentProbe.ok){throw '受控模型连接测试未通过'}
$agentEntry=Invoke-AgentApi POST '/admin/model-entries' @{providerId=$agentProvider.id;modelName='gpt-4';displayName='隔离联合测试FC';contextWindow=8192;maxOutputTokens=512;capabilities=@('chat','tools')}
$agentSkillCode="smoke_write_$agentSuffix"
$agentInputSchema=@{type='object';properties=@{message=@{type='string'}};required=@('message');additionalProperties=$false}
$agentOutputSchema=@{type='object';properties=@{ok=@{type='boolean'};value=@{type='string'}};required=@('ok','value');additionalProperties=$false}
$agentSkill=Invoke-AgentApi POST '/admin/skills' @{code=$agentSkillCode;name='隔离测试写技能';description='联合测试计数器写入，必须确认，不是业务服务';type='HTTP';endpoint='http://127.0.0.1:28411/write';httpMethod='POST';inputSchema=$agentInputSchema;outputSchema=$agentOutputSchema;execution=@{readonly=$false;sideEffect=$true;idempotent=$true;cancellable=$false;confirmRoles=@('APPROVER')};guard=@{roles=@('EDITOR');attributes=@{}};timeoutMs=10000}
$agentBefore=(Invoke-RestMethod -Uri 'http://127.0.0.1:28411/stats').writes
$agentConnection=Invoke-AgentApi POST "/admin/skills/$agentSkillCode/drafts/1/connection-test" @{inputs=@{message='smoke-write'};requestId=[Guid]::NewGuid().ToString()}
if(-not $agentConnection.runId){throw '写技能未产生受控DEBUG运行'}
$agentRun=Wait-AgentRun $agentConnection.runId @('WAITING_APPROVAL')
$agentBeforeApproval=(Invoke-RestMethod -Uri 'http://127.0.0.1:28411/stats').writes
if($agentBeforeApproval -ne $agentBefore){throw '批准前已发生外部写动作'}
$agentApprovals=Invoke-AgentApi GET '/admin/approvals'
$agentApproval=@($agentApprovals|Where-Object {$_.runId -eq $agentRun.runId})[0]
if(-not $agentApproval){throw '确认中心未返回有权限的待办'}
Invoke-AgentApi POST "/admin/approvals/$($agentRun.runId)/$($agentApproval.nodeInstanceId)" @{requestId=[Guid]::NewGuid().ToString();approved=$true;snapshotDigest=$agentApproval.snapshotDigest}|Out-Null
$agentDone=Wait-AgentRun $agentRun.runId @('SUCCEEDED')
$agentAfter=(Invoke-RestMethod -Uri 'http://127.0.0.1:28411/stats').writes
if($agentAfter -ne $agentBefore+1){throw '受控写操作不是恰好一次'}
$agentConnectionDone=Invoke-AgentApi POST "/admin/skills/$agentSkillCode/drafts/1/connection-test" @{controlledCallId=$agentDone.controlledCallId}
if(-not $agentConnectionDone.ok){throw '同摘要真实DEBUG事实未获连接验收'}
$agentAcceptance=Invoke-AgentApi POST "/admin/skills/$agentSkillCode/drafts/1/acceptance" @{modelEntryId=$agentEntry.id;cases=@(@{question='执行受控测试写入';expectSelected=$agentSkillCode})}
if(-not $agentAcceptance.pass){throw '受控模型FC选择管道未通过'}
$agentPublished=Invoke-AgentApi POST "/admin/skills/$agentSkillCode/versions/1/publish"
$agentEvidence=[ordered]@{fixtureOnly=$true;skillCode=$agentSkillCode;skillVersionId=$agentPublished.skillVersionId;modelEntryId=$agentEntry.id;runId=$agentRun.runId;callId=$agentDone.controlledCallId;beforeApprovalWrites=$agentBeforeApproval-$agentBefore;afterApprovalWrites=$agentAfter-$agentBefore;connection=$agentConnectionDone.ok;selectionPipeline=$agentAcceptance.pass;published=$agentPublished.status}
$agentEvidence|ConvertTo-Json|Set-Content -LiteralPath '.local/write-skill-smoke-result.json' -Encoding utf8
$agentEvidence|ConvertTo-Json
