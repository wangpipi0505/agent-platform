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
 for($agentPoll=0;$agentPoll -lt 100;$agentPoll++){
  $agentState=Invoke-AgentApi GET "/admin/debug-runs/$Id/status"
  if($agentState.status -eq 'SUCCEEDED'){return $agentState}
  if($agentState.status -in @('FAILED','CANCELLED')){throw "运行失败：$Id，$($agentState.error|ConvertTo-Json -Depth 8 -Compress)"}
  Start-Sleep -Milliseconds 300
 };throw "运行未完成：$Id，$($agentState.status)"
}
$agentSuffix=[Guid]::NewGuid().ToString('N').Substring(0,10)
$agentSkillCode="smoke_large_$agentSuffix"
$agentSkill=Invoke-AgentApi POST '/admin/skills' @{code=$agentSkillCode;name='隔离5MiB结果查询';description='仅联合测试大结果转存、受控下载；不是真实业务服务';type='HTTP';endpoint='http://127.0.0.1:28412/result';httpMethod='POST';inputSchema=@{type='object';required=@('message','region');properties=@{message=@{type='string'};region=@{type='string'}}};outputSchema=@{type='object';required=@('data');properties=@{data=@{type='string'};message=@{type='string'}}};constraints=@{outputMaxBytes=4096};execution=@{readonly=$true;sideEffect=$false;idempotent=$true;cancellable=$false};guard=@{roles=@('EDITOR');attributes=@{region=@('LOCAL')}};timeoutMs=30000}
$agentConnection=Invoke-AgentApi POST "/admin/skills/$agentSkillCode/drafts/1/connection-test" @{inputs=@{message='large-readonly';region='LOCAL'};requestId=[Guid]::NewGuid().ToString()}
if(-not $agentConnection.runId){throw '只读连接必须创建受控DEBUG运行'}
$agentControlled=Wait-AgentRun $agentConnection.runId
$agentAccepted=Invoke-AgentApi POST "/admin/skills/$agentSkillCode/drafts/1/connection-test" @{controlledCallId=$agentControlled.controlledCallId}
if(-not $agentAccepted.ok){throw '大结果受控DEBUG连接未通过'}
$agentModel=Get-Content -LiteralPath '.local/write-skill-smoke-result.json' -Raw | ConvertFrom-Json
$agentSelection=Invoke-AgentApi POST "/admin/skills/$agentSkillCode/drafts/1/acceptance" @{modelEntryId=$agentModel.modelEntryId;cases=@(@{question='查询受控大结果';expectSelected=$agentSkillCode})}
if(-not $agentSelection.pass){throw '大结果技能选型管道失败'}
$agentPublishedSkill=Invoke-AgentApi POST "/admin/skills/$agentSkillCode/versions/1/publish"
$agentCode="smoke_artifact_$agentSuffix"
$agentApp=Invoke-AgentApi POST '/admin/apps' @{code=$agentCode;name='隔离大结果下载验收';type='FLOW';description='真实MinIO转存与浏览器下载验证，外部数据为fixture'}
$agentDefinition=@{definitionVersion='1.0';trigger='TASK';entry='start';inputs=@(@{name='message';type='STRING';required=$true});outputs=@(@{name='message';from='${input.message}'});nodes=@(@{id='start';type='START'},@{id='query';type='SKILL';skillRef=@{code=$agentSkillCode;version=1};args=@{message='${input.message}';region='LOCAL'}},@{id='end';type='END'});edges=@(@{from='start';to='query'},@{from='query';to='end'});joins=@();executionRules=@{parallel=$false;maxParallel=1;cyclesForbidden=$true}}
$agentApp=Invoke-AgentApi PUT "/admin/flows/$agentCode" @{draftRev=$agentApp.draftRev;definition=$agentDefinition;layout=@{}}
$agentApp=Invoke-AgentApi PUT "/admin/apps/$agentCode/config" @{draftRev=$agentApp.draftRev;config=@{guard=@{roles=@('EDITOR');attributes=@{region=@('LOCAL')}}}}
$agentReport=Invoke-AgentApi POST '/admin/evaluations' @{appCode=$agentCode;draftRev=$agentApp.draftRev;candidateSha256=$agentApp.candidateSha256;datasetVersion='large_fixture_v1';cases=@(@{id='large_result';inputs=@{message='large-formal'};expected=@{message='large-formal'};safety=$true})}
if($agentReport.status -ne 'ACCEPTED'){throw "大结果评测未通过：$($agentReport.status)"}
$agentCheck=Invoke-AgentApi POST "/admin/apps/$agentCode/publish/precheck"
if(-not $agentCheck.passed){throw "预检未通过：$($agentCheck|ConvertTo-Json -Depth 12 -Compress)"}
$agentPublished=Invoke-AgentApi POST "/admin/apps/$agentCode/publish/commit" @{precheckId=$agentCheck.precheckId;draftRev=$agentApp.draftRev;candidateSha256=$agentApp.candidateSha256;idempotencyRequestNo=[Guid]::NewGuid().ToString()}
$agentFormal=Invoke-AgentApi POST "/admin/apps/$agentCode/runs" @{inputs=@{message='large-formal'};requestId=[Guid]::NewGuid().ToString()}
$agentDone=Wait-AgentRun $agentFormal.runId
$agentRef=@($agentDone.fileRefs)[0]
if(-not $agentRef.artifactId -or $agentRef.bytes -lt 5*1024*1024){throw '运行状态未提供受控大结果引用'}
$agentDownload=Join-Path (Get-Location) '.local/large-artifact-downloaded.json'
Invoke-WebRequest -Uri "$agentBase$($agentRef.downloadUrl)" -Headers $agentHeaders -OutFile $agentDownload -TimeoutSec 60
if((Get-FileHash -LiteralPath $agentDownload -Algorithm SHA256).Hash.ToLowerInvariant() -ne $agentRef.sha256){throw '下载摘要不符'}
$agentEvidence=[ordered]@{fixtureOnly=$true;endpoint='http://127.0.0.1:28412/result';skillCode=$agentSkillCode;skillVersionId=$agentPublishedSkill.skillVersionId;appCode=$agentCode;versionNo=$agentPublished.versionNo;controlledRunId=$agentControlled.runId;formalRunId=$agentFormal.runId;artifact=$agentRef;downloadSha256Verified=$true}
$agentEvidence|ConvertTo-Json -Depth 10|Set-Content -LiteralPath '.local/large-artifact-smoke-result.json' -Encoding utf8
$agentEvidence|ConvertTo-Json -Depth 10
exit 0
