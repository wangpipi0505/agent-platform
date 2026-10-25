$ErrorActionPreference='Stop'
$agentBase='http://127.0.0.1:18080'
$agentLogin=Invoke-RestMethod -Method Post -Uri "$agentBase/auth/dev-token"
$agentHeaders=@{Authorization="Bearer $($agentLogin.data.accessToken)"}
function Invoke-AgentApi([string]$Method,[string]$Path,$Body=$null){
 $agentParams=@{Method=$Method;Uri="$agentBase$Path";Headers=$agentHeaders;TimeoutSec=90}
 if($null -ne $Body){$agentParams.ContentType='application/json';$agentParams.Body=ConvertTo-Json -InputObject $Body -Depth 40 -Compress}
 $agentEnvelope=Invoke-RestMethod @agentParams
 if($agentEnvelope.code -ne 'OK'){throw "API未成功：$Path，$($agentEnvelope.code)"};return $agentEnvelope.data
}
function Wait-AgentRun([string]$Id){
 for($agentPoll=0;$agentPoll -lt 80;$agentPoll++){
  $agentState=Invoke-AgentApi GET "/v1/runs/$Id"
  if($agentState.status -eq 'SUCCEEDED'){return $agentState}
  if($agentState.status -in @('FAILED','CANCELLED')){throw "会话失败：$Id，$($agentState.error|ConvertTo-Json -Depth 8 -Compress)"}
  Start-Sleep -Milliseconds 250
 };throw "会话未结束：$Id"
}
$agentFixture=Get-Content -LiteralPath '.local/write-skill-smoke-result.json' -Raw|ConvertFrom-Json
$agentEntry=@(Invoke-AgentApi GET '/admin/model-entries'|Where-Object {$_.id -eq $agentFixture.modelEntryId})[0]
$agentProvider=@(Invoke-AgentApi GET '/admin/model-providers'|Where-Object {$_.id -eq $agentEntry.providerId})[0]
$agentCode='smoke_chat_'+[Guid]::NewGuid().ToString('N').Substring(0,10)
$agentApp=Invoke-AgentApi POST '/admin/apps' @{code=$agentCode;name='隔离多轮会话验收';type='CHAT';description='显式HTTP模型fixture，只验证会话和发布管道'}
$agentApp=Invoke-AgentApi PUT "/admin/apps/$agentCode/config" @{draftRev=$agentApp.draftRev;config=@{modelBinding=@{modelEntryId=$agentEntry.id};inputs=@(@{name='message';type='STRING';required=$true});systemPrompt='仅用于独立工程协议测试';tools=@();output=@{maxTokens=256};params=@{temperature=0};guard=@{roles=@('EDITOR');attributes=@{}}}}
$agentExpected=@{text='受控测试完成';usage=@{promptTokens=8;completionTokens=4;totalTokens=12};actualModel=@{modelEntryId=$agentEntry.id;modelName=$agentEntry.modelName;providerId=$agentProvider.id;endpoint=$agentProvider.baseUrl}}
$agentReport=Invoke-AgentApi POST '/admin/evaluations' @{appCode=$agentCode;draftRev=$agentApp.draftRev;candidateSha256=$agentApp.candidateSha256;datasetVersion='chat_fixture_v1';cases=@(@{id='chat_plain';inputs=@{message='会话协议测试'};expected=$agentExpected;safety=$true})}
if($agentReport.status -ne 'ACCEPTED'){$agentReport|ConvertTo-Json -Depth 10;throw '会话候选未通过'}
$agentCheck=Invoke-AgentApi POST "/admin/apps/$agentCode/publish/precheck"
if(-not $agentCheck.passed){$agentCheck|ConvertTo-Json -Depth 12;throw 'CHAT预检未通过'}
$agentPublished=Invoke-AgentApi POST "/admin/apps/$agentCode/publish/commit" @{precheckId=$agentCheck.precheckId;draftRev=$agentApp.draftRev;candidateSha256=$agentApp.candidateSha256;idempotencyRequestNo=[Guid]::NewGuid().ToString()}
$agentSchema=Invoke-AgentApi GET "/v1/apps/$agentCode/input-schema"
if($agentSchema.type -ne 'CHAT' -or $agentSchema.versionNo -ne 1){throw '冻结CHAT公开输入契约不正确'}
$agentSession=Invoke-AgentApi POST "/v1/apps/$agentCode/sessions"
$agentRuns=@()
foreach($agentQuestion in @('第一轮：你好','第二轮：继续')){
 $agentRequest=@{requestId=[Guid]::NewGuid().ToString();inputs=@{message=$agentQuestion}}
 $agentRun=Invoke-AgentApi POST "/v1/apps/$agentCode/sessions/$($agentSession.sessionId)/turns" $agentRequest
 $agentDone=Wait-AgentRun $agentRun.runId
 if($agentDone.inputs.message -ne $agentQuestion -or $agentDone.output.text -ne '受控测试完成'){throw '会话输入或最终快照不一致'}
 $agentDuplicate=Invoke-AgentApi POST "/v1/apps/$agentCode/sessions/$($agentSession.sessionId)/turns" $agentRequest
 if($agentDuplicate.runId -ne $agentRun.runId){throw '同轮请求没有幂等复用'}
 $agentRuns+=$agentRun.runId
}
$agentRestored=Invoke-AgentApi GET "/v1/apps/$agentCode/sessions/$($agentSession.sessionId)"
if(@($agentRestored.turns).Count -ne 2){throw '会话刷新未读到两轮持久记录'}
$agentEvidence=[ordered]@{fixtureOnly=$true;appCode=$agentCode;versionNo=$agentPublished.versionNo;modelEntryId=$agentEntry.id;sessionId=$agentSession.sessionId;runIds=$agentRuns;turns=2;idempotent=$true;inputsRestored=$true}
$agentEvidence|ConvertTo-Json -Depth 8|Set-Content -LiteralPath '.local/chat-session-smoke-result.json' -Encoding utf8
$agentEvidence|ConvertTo-Json -Depth 8
exit 0
