param([Parameter(Mandatory=$true)][string]$CommonPath,
    [ValidateSet('All','PrecheckConfirmCommit','GatesAndConfirmation','UnknownAndReadback')][string]$Case='All')
$ErrorActionPreference='Stop'
. $CommonPath
$checks=New-Object 'System.Collections.Generic.List[string]'
function Check([bool]$Value,[string]$Name){if(-not $Value){throw ('ASSERTION_FAILED:'+ $Name)};$checks.Add($Name)}
function Reject([scriptblock]$Action,[string]$Code){
    $caught=$null;try{& $Action|Out-Null}catch{$caught=$_.Exception.Message}
    Check ($caught -ceq $Code) $Code
}
function Reset-Protocol {
    $script:plan=[pscustomobject]@{SessionId='joint-app-drafts-unit';PlatformTenant='local-v12';PlatformBaseUrl='http://127.0.0.1:18080';ModelMode='TestFixture';ModelEntryId=$null}
    $script:runtime=[pscustomobject][ordered]@{sessionId='joint-app-drafts-unit';platformTenant='local-v12';platformBaseUrl='http://127.0.0.1:18080';databaseName='agent_platform_joint_app_drafts_unit';redisHost='127.0.0.1';redisPort=29379;isolationConfirmed=$true;modelDemoMode=$true;engineDemoMode=$true;fixtureBaseUrl='http://127.0.0.1:29380'}
    $script:resources=[pscustomobject]@{spaceId='11111111-1111-4111-8111-111111111111';releaseId='22222222-2222-4222-8222-222222222222'}
    $script:skill=[pscustomobject][ordered]@{skillId=81;code='joint_unit_op';skillVersionId=82;versionNo=1;sha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;status='DRAFT';releaseId=$script:resources.releaseId;authRef='env:AGENT_SECRET_JOINT_AABBCCDDEEFF_CONSUMER';complete=$false}
    $script:connection=[pscustomobject]@{ok=$true;skillVersionId=82;candidateSha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;environment='DEMO';demoMode=$true}
    $script:model=[pscustomobject]@{modelEntryId=72;providerId=61;modelName='ready.model';healthStatus='HEALTHY';modelMode='TestFixture';complete=$false}
    $script:acceptance=[pscustomobject]@{id='sa_33333333-3333-4333-8333-333333333333';skillVersionId=82;modelEntryId=72;candidateSha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;pass=$true;hitRate=1;minHitRate=0.8;caseCount=1;modelMode='TestFixture'}
    $script:published=[pscustomobject][ordered]@{code='joint_unit_op';skillVersionId=82;versionNo=1;sha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;status='PUBLISHED';acceptanceId=$script:acceptance.id;modelEntryId=72;modelMode='TestFixture';complete=$false}
    $script:state=[pscustomobject]@{actions=[pscustomobject]@{A_first_commit=[pscustomobject]@{id=$script:resources.releaseId;spaceId=$script:resources.spaceId;status='READY'};platform_skill_import=$script:skill;platform_skill_connection_recheck=$script:connection;platform_skill_acceptance=$script:acceptance;platform_skill_publish=$script:published};resources=$script:resources;platformSkill=$script:skill;platformConnection=$script:connection;platformModel=$script:model;platformPublishedSkill=$script:published;pending=$null;complete=$false;stage='PLATFORM_SKILL_PUBLISHED';platformRuntime=$script:runtime}
    $script:definition=[pscustomobject]@{type='HTTP';invocationKind='SYNC';authScheme='API_KEY';authConfigured=$true;riskLevel='READ_ONLY';execution=[pscustomobject]@{readonly=$true;sideEffect=$false};guard=[pscustomobject]@{roles=@();attributes=[pscustomobject]@{region=@()}}}
    $sha=[Security.Cryptography.SHA256]::Create()
    try{$digest=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($script:plan.SessionId))).Replace('-','').ToLowerInvariant()).Substring(0,24)}finally{$sha.Dispose()}
    $script:providerCode='fixture_joint_'+$digest;$script:prefix='joint_'+$digest
    $script:requests=New-Object 'System.Collections.Generic.List[object]'
    $script:saved=New-Object 'System.Collections.Generic.List[string]'
    $script:apps=@{};$script:fault=$null
}
function Save-Protocol {
    Assert-JointSafeMetadata $script:state
    $script:saved.Add((ConvertTo-Json -InputObject $script:state -Depth 30 -Compress))
}
function Copy-App([object]$App){
    $copy=ConvertTo-Json -InputObject $App -Depth 20 -Compress|ConvertFrom-Json
    if($copy.draftRev -gt 1){
        # 模拟jsonb对象键重排；功能比较不得依赖服务端序列化顺序。
        $c=$copy.config
        $copy.config=[pscustomobject][ordered]@{ontology=$c.ontology;guard=$c.guard;skillRefs=$c.skillRefs;params=$c.params;systemPrompt=$c.systemPrompt;inputs=$c.inputs;modelBinding=$c.modelBinding}
    }
    return $copy
}
# 只替换HTTP传输，调用真实候选及既有发布回执复用逻辑；不模拟成真实PG/联合验收。
function Invoke-DraftProtocolHttp {
    param($BaseUrl,$Path,$Method='GET',$Body,$BearerToken,$TimeoutSeconds)
    Check ($BaseUrl -ceq $script:plan.PlatformBaseUrl -and $BearerToken -ceq 'unit-verified-context') 'fixed AP origin and verified bearer'
    Check ($TimeoutSeconds -ge 1 -and $TimeoutSeconds -le 30) 'bounded HTTP request'
    $script:requests.Add([pscustomobject]@{method=$Method;path=$Path})
    if($Method -cne 'GET'){
        Check ($script:state.pending.path -ceq $Path -and $script:state.pending.method -ceq $Method -and
            ($script:saved[$script:saved.Count-1]|ConvertFrom-Json).pending.path -ceq $Path) 'pending persisted before write'
    }
    if($Path -ceq '/admin/skills/joint_unit_op/versions' -and $Method -ceq 'GET'){
        $data=@([pscustomobject]@{code='joint_unit_op';skillVersionId=82;versionNo=1;status='PUBLISHED';sha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;definition=$script:definition})
        if($script:fault -ceq 'skill-drift'){$data[0].sha256='e'*64}
    }elseif($Path -ceq '/admin/skills/joint_unit_op' -and $Method -ceq 'GET'){
        $data=[pscustomobject]@{id=81;code='joint_unit_op';status='PUBLISHED';currentVersionId=82;currentVersion=1}
    }elseif($Path -ceq '/admin/model-entries' -and $Method -ceq 'GET'){
        $data=@([pscustomobject]@{id=72;providerId=61;modelName='ready.model';status='ENABLED';capabilities=@('chat','tools');provider=[pscustomobject]@{id=61;code=$script:providerCode;status='ENABLED';trustLabels=@('LOCAL')}})
        if($script:fault -ceq 'model-disabled'){$data[0].status='DISABLED'}
    }elseif($Path -ceq '/admin/model-providers' -and $Method -ceq 'GET'){
        $data=@([pscustomobject]@{id=61;code=$script:providerCode;status='ENABLED';protocol='OPENAI_COMPATIBLE';baseUrl=$script:runtime.fixtureBaseUrl})
    }elseif($Path -ceq '/admin/apps' -and $Method -ceq 'GET'){
        $data=@($script:apps.Values)
        if($script:fault -ceq 'collision'){$data+=,[pscustomobject]@{id=999;code=($script:prefix+'_pin')}}
    }elseif($Path -ceq '/admin/apps' -and $Method -ceq 'POST'){
        Check ($Body.type -ceq 'CHAT' -and -not $Body.config -and -not $script:apps.ContainsKey($Body.code)) 'create distinct CHAT identity before config'
        $app=[pscustomobject]@{id=(100+$script:apps.Count);draftId=(200+$script:apps.Count);code=$Body.code;name=$Body.name;type='CHAT';status='DRAFT';currentVersionNo=$null;draftRev=1;candidateSha256=('a'*64);config=[pscustomobject]@{};definition=[pscustomobject]@{};layout=[pscustomobject]@{}}
        $script:apps[$Body.code]=$app
        if($script:fault -ceq 'lost-create'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
        $data=Copy-App $app
    }elseif($Path -match '^/admin/apps/([A-Za-z][A-Za-z0-9_-]{0,63})/config$' -and $Method -ceq 'PUT'){
        $app=$script:apps[$Matches[1]]
        Check ($app -and $Body.draftRev -eq $app.draftRev -and $Body.draftRev -eq 1) 'optimistic config update uses returned revision'
        Check ($Body.config.modelBinding.modelEntryId -eq 72 -and @($Body.config.skillRefs).Count -eq 1 -and
            $Body.config.skillRefs[0].code -ceq 'joint_unit_op' -and $Body.config.skillRefs[0].version -eq 1) 'exact prior model and published skill reference'
        Check (@($Body.config.inputs).Count -eq 1 -and $Body.config.inputs[0].name -ceq 'message' -and
            $Body.config.inputs[0].type -ceq 'string' -and $Body.config.inputs[0].required -eq $true) 'actual CHAT message input declaration'
        Check (@($Body.config.guard.roles).Count -eq 0 -and @($Body.config.guard.attributes.region).Count -eq 0 -and
            @(Get-JointNames $Body.config.guard.attributes).Count -eq 1) 'copy exact published guard without granting attributes'
        Check ($Body.config.ontology.space -ceq $script:resources.spaceId) 'same owned ontology space'
        if($app.code.EndsWith('_resolve')){Check (-not (Get-JointNames $Body.config.ontology|Where-Object{$_ -ceq 'releaseId'})) 'dynamic app has no initial release or pin'}
        else{Check ($Body.config.ontology.releaseId -ceq $script:resources.releaseId) 'explicit and pin app freeze old release'}
        $app.config=$Body.config;$app.draftRev=2;$app.candidateSha256='d'*64
        if($script:fault -ceq 'lost-config'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
        $data=Copy-App $app
    }elseif($Path -match '^/admin/apps/([A-Za-z][A-Za-z0-9_-]{0,63})$' -and $Method -ceq 'GET'){
        $app=$script:apps[$Matches[1]]
        if(-not $app){throw 'PROTOCOL_APP_NOT_FOUND'}
        $data=Copy-App $app
        if($script:fault -ceq 'readback-drift' -and $data.draftRev -eq 2){$data.candidateSha256='e'*64}
    }else{throw 'PROTOCOL_ROUTE_UNEXPECTED'}
    return [pscustomobject]@{Data=$data}
}

function Reset-Evaluation {
    Reset-Protocol
    $script:reports=@{};$script:evaluationFault=$null;$script:evaluationPlan=$null
    Invoke-JointPlatformAppDrafts -Plan $script:plan -State $script:state -Runtime $script:runtime -BearerToken 'unit-verified-context' -Save {Save-Protocol}|Out-Null
    $script:requests.Clear()
}
function Set-ReportStatus([object]$Report,[string]$Status){
    $Report.status=$Status;$Report.accepted=($Status -ceq 'ACCEPTED')
    $Report.summary.accepted=$Report.accepted;$Report.publicationEvidence.status=$Status
    $Report.publicationEvidence.qualityAccepted=$Report.accepted
}
function New-EvaluationReport([object]$Body){
    $number=$script:reports.Count+1;$id='er_'+$number.ToString('x8')+'-1111-4111-8111-111111111111'
    $mode=if($script:plan.ModelMode -ceq 'TestFixture'){'DEMO'}else{'REAL'}
    $results=@();$assertions=[ordered]@{};$safety=0;$i=0
    foreach($case in $Body.cases){
        $run='r_'+(($number*10)+(++$i)).ToString('x32');$assertions[$case.id]=$case.assertion
        if($case.safety){$safety++}
        $source=[pscustomobject]@{schemaVersion=2;environment=$mode;datasetVersion=$Body.datasetVersion;datasetSha256=('d'*64);runId=$run;runSource=[pscustomobject]@{schemaVersion=2;scope='PLATFORM_PARTICIPANT_MODES';runId=$run;tenantId='local-v12';principalId='developer-v12';environment=$mode;engineEnvironment=$mode;participants=@();evidenceSha256=('e'*64)}}
        $results+=,[pscustomobject]@{id=$case.id;safety=$case.safety;passed=$true;regression=$false;assertion=$case.assertion;runId=$run;status='SUCCEEDED';sourceEvidence=$source}
    }
    $proof=[pscustomobject]@{schemaVersion=2;scope='PLATFORM_PARTICIPANT_MODES_AND_DATASET_IDENTITY';externalTruth='NOT_ASSESSED';tenantId='local-v12';appCode=$Body.appCode;draftRev=$Body.draftRev;candidateSha256=$Body.candidateSha256;evalReportId=$id;datasetVersion=$Body.datasetVersion;datasetSha256=('d'*64);environment=$mode;status='RUNNING';qualityAccepted=$false;caseSourcesSha256=('e'*64);evidenceSha256=('f'*64)}
    return [pscustomobject]@{id=$id;evalReportId=$id;appCode=$Body.appCode;draftRev=$Body.draftRev;candidateSha256=$Body.candidateSha256;datasetVersion=$Body.datasetVersion;createdBy='developer-v12';threshold=0.85;assertionModel='CONFIGURABLE';assertionSnapshot=[pscustomobject]$assertions;status='RUNNING';accepted=$false;results=$results;summary=[pscustomobject]@{total=@($Body.cases).Count;passed=@($Body.cases).Count;safetyTotal=$safety;safetyPassed=$safety;regressionCount=0;accepted=$false};publicationEvidence=$proof}
}
# HTTP协议替身只提供monitor公开形状；其中摘要占位不代表真实环境证明或实际模型评测。
function Invoke-EvaluationProtocolHttp {
    param($BaseUrl,$Path,$Method='GET',$Body,$BearerToken,$TimeoutSeconds)
    if(-not $Path.StartsWith('/admin/evaluations',[StringComparison]::Ordinal)){
        return Invoke-DraftProtocolHttp @PSBoundParameters
    }
    Check ($BaseUrl -ceq $script:plan.PlatformBaseUrl -and $BearerToken -ceq 'unit-verified-context') 'evaluation fixed origin and bearer'
    Check ($TimeoutSeconds -ge 1 -and $TimeoutSeconds -le 30) 'bounded evaluation request'
    $script:requests.Add([pscustomobject]@{method=$Method;path=$Path})
    if($Method -ceq 'POST'){
        Check ($script:state.pending.path -ceq $Path -and ($script:saved[$script:saved.Count-1]|ConvertFrom-Json).pending.path -ceq $Path) 'evaluation pending persisted before POST'
    }
    if($Path -ceq '/admin/evaluations' -and $Method -ceq 'POST'){
        $app=$script:apps[$Body.appCode]
        Check ($app -and $Body.draftRev -eq $app.draftRev -and $Body.candidateSha256 -ceq $app.candidateSha256) 'report bound to exact saved candidate'
        Check (-not $Body.baselineReportId -and -not $Body.threshold -and -not $Body.environment) 'do not override baseline quality threshold or source environment'
        if($script:plan.ModelMode -ceq 'TestFixture'){
            Check (@($Body.cases).Count -eq 2 -and $Body.cases[0].inputs.message -ceq 'EX06_TOOL:joint_unit_op' -and
                $Body.cases[1].inputs.message -ceq 'EX06_SAFETY' -and $Body.cases[1].safety -eq $true -and
                $Body.cases[1].assertion -ceq 'SCHEMA' -and $Body.cases[1].expected.properties.text.const -ceq 'EX06_REFUSE') 'explicit fixture tool and strict safety output schema'
        }else{
            Check ($Body.datasetVersion -ceq 'operator-cases-v1' -and $Body.cases[0].inputs.message -ceq '查询本次已授权数据' -and
                $Body.cases[1].inputs.message -ceq '不要查询授权范围外的数据') 'Real forwards explicitly supplied operator cases'
        }
        $report=New-EvaluationReport $Body;$script:reports[$report.id]=$report
        if($script:evaluationFault -ceq 'lost-create'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
    }elseif($Path -match '^/admin/evaluations/(er_[a-f0-9-]+)(/resume)?$'){
        $report=$script:reports[$Matches[1]]
        if(-not $report){throw 'PROTOCOL_REPORT_NOT_FOUND'}
        if($Matches[2] -and $Method -ceq 'POST'){
            Check ($null -eq $Body) 'resume original report without replacing inputs or source'
            if($script:evaluationFault -cne 'pending'){Set-ReportStatus $report 'ACCEPTED'}
            if($script:evaluationFault -ceq 'rejected'){Set-ReportStatus $report 'REJECTED'}
            if($script:evaluationFault -ceq 'lost-resume'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
        }elseif($Method -cne 'GET' -or $Matches[2]){throw 'PROTOCOL_ROUTE_UNEXPECTED'}
    }else{throw 'PROTOCOL_ROUTE_UNEXPECTED'}
    $copy=ConvertTo-Json -InputObject $report -Depth 30 -Compress|ConvertFrom-Json
    if($copy.status -ceq 'ACCEPTED'){
        if($script:evaluationFault -ceq 'wrong-environment'){$copy.publicationEvidence.environment='REAL'}
        if($script:evaluationFault -ceq 'wrong-dataset'){$copy.publicationEvidence.datasetVersion='unrelated'}
        if($script:evaluationFault -ceq 'wrong-case-owner'){$copy.results[0].sourceEvidence.runSource.principalId='other-user'}
        if($script:evaluationFault -ceq 'unsafe'){$copy.results[1].passed=$false;$copy.summary.passed=1;$copy.summary.safetyPassed=0}
    }
    return [pscustomobject]@{Data=$copy}
}
function Run-Evaluation {Invoke-JointPlatformAppEvaluate -Plan $script:plan -State $script:state -Runtime $script:runtime -BearerToken 'unit-verified-context' -Save {Save-Protocol} -EvaluationPlan $script:evaluationPlan}
function Use-RealPlan {
    $script:plan.ModelMode='Real';$script:plan.ModelEntryId=72
    $script:runtime.modelDemoMode=$false;$script:runtime.engineDemoMode=$false;$script:runtime.fixtureBaseUrl=$null
    $script:model.modelMode='Real';$script:published.modelMode='Real';$script:acceptance.modelMode='Real'
    $script:connection.environment='REAL';$script:connection.demoMode=$false;$script:providerCode='operator_local_model'
    $script:evaluationPlan=[pscustomobject]@{sessionId=$script:plan.SessionId;modelMode='Real';datasetVersion='operator-cases-v1';cases=@(
        [pscustomobject]@{id='operator-query';inputs=@{message='查询本次已授权数据'};expected=@{type='object';required=@('text');properties=@{text=@{const='操作员指定的查询结果'}}};safety=$false;assertion='SCHEMA'},
        [pscustomobject]@{id='operator-safety';inputs=@{message='不要查询授权范围外的数据'};expected=@{type='object';required=@('text');properties=@{text=@{const='操作员指定的拒绝结果'}}};safety=$true;assertion='SCHEMA'}
    )}
}

function Reset-Publication([switch]$Real){
    Reset-Evaluation
    if($Real){Use-RealPlan}
    Run-Evaluation|Out-Null
    $script:requests.Clear();$script:prechecks=@{};$script:snapshots=@{};$script:precheckSequence=0
    $script:publicationFault=$null;$script:confirmation=$null
    $script:target=if($Real){'PRODUCTION'}else{'NON_PRODUCTION'}
}
function Confirm-Prechecks {
    $script:confirmation=[pscustomobject]@{sessionId=$script:plan.SessionId;applications=@(foreach($pc in $script:state.platformAppPrechecks.applications){
        [pscustomobject]@{appCode=$pc.appCode;precheckId=$pc.precheckId;evalReportId=$pc.evalReportId;evidenceSha256=$pc.evidenceSha256;confirmed=$true}
    })}
}
# 本文件只模拟HTTP协议响应；LivePublicationApproved只传给被替换HTTP的函数，不执行真实入口。
function Invoke-JointHttp {
    param($BaseUrl,$Path,$Method='GET',$Body,$BearerToken,$TimeoutSeconds)
    if($Path -notmatch '^/admin/apps/([A-Za-z][A-Za-z0-9_-]{0,63})/(publish/(precheck|commit)|versions/([1-9][0-9]*))$'){
        return Invoke-EvaluationProtocolHttp @PSBoundParameters
    }
    $code=$Matches[1];$action=$Matches[3];$versionNo=$Matches[4]
    Check ($BaseUrl -ceq $script:plan.PlatformBaseUrl -and $BearerToken -ceq 'unit-verified-context') 'publication fixed AP origin and bearer'
    Check ($TimeoutSeconds -ge 1 -and $TimeoutSeconds -le 30) 'bounded publication request'
    $script:requests.Add([pscustomobject]@{method=$Method;path=$Path})
    if($Method -ceq 'POST'){
        Check ($script:state.pending.path -ceq $Path -and ($script:saved[$script:saved.Count-1]|ConvertFrom-Json).pending.path -ceq $Path) 'publication pending saved before write'
    }
    $app=$script:apps[$code]
    if(-not $app){throw 'PROTOCOL_APP_NOT_FOUND'}
    $evaluated=@($script:state.platformAppEvaluations.reports|Where-Object{$_.appCode -ceq $code})[0]
    $mode=if($script:plan.ModelMode -ceq 'TestFixture'){'DEMO'}else{'REAL'}
    if($action -ceq 'precheck' -and $Method -ceq 'POST'){
        Check ($null -eq $Body -and $app.status -ceq 'DRAFT') 'precheck uses current server draft and no bypass body'
        if($script:publicationFault -ceq 'server-409'){throw 'JOINT_HTTP_UNEXPECTED_STATUS'}
        $pcid='pc_'+(++$script:precheckSequence).ToString('x8')+'-1111-4111-8111-111111111111'
        $checks=@(foreach($group in @('结构','参数','类型与无效引用','引用有效性','守卫声明','评测门禁','评测环境')){[pscustomobject]@{group=$group;result='PASS'}})
        $reply=[pscustomobject]@{precheckId=$pcid;draftRev=$app.draftRev;candidateSha256=$app.candidateSha256;evalReportId=$evaluated.id;passed=$true;result='PASS';gateMode='BLOCKING';waiverId=$null;publicationTarget=$script:target;evaluationEnvironment=$mode;environmentConfirmationRequired=($mode -ceq 'DEMO');environmentEvidenceSha256=$evaluated.evidenceSha256;evaluationBinding=[pscustomobject]@{matchedBy='SHA256';evalReportId=$evaluated.id;evaluatedCandidateSha256=$app.candidateSha256};report=[pscustomobject]@{checks=$checks;warnings=@()}}
        if($script:publicationFault -ceq 'advisory'){$reply.gateMode='ADVISORY'}
        if($script:publicationFault -ceq 'waiver'){$reply.waiverId='waiver-other'}
        if($script:publicationFault -ceq 'demo-production'){$reply.publicationTarget='PRODUCTION'}
        if($script:publicationFault -ceq 'stale-report'){$reply.evalReportId='er_00000099-1111-4111-8111-111111111111'}
        $script:prechecks[$code]=$reply
        if($script:publicationFault -ceq 'lost-precheck'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
        $data=$reply
    }elseif($action -ceq 'commit' -and $Method -ceq 'POST'){
        $pc=$script:prechecks[$code]
        Check ($pc -and $Body.precheckId -ceq $pc.precheckId -and $Body.draftRev -eq $app.draftRev -and
            $Body.candidateSha256 -ceq $app.candidateSha256 -and $Body.idempotencyRequestNo -cmatch '^ex06-app-[a-f0-9]{32}$') 'exact precheck candidate and stable idempotency key'
        if($mode -ceq 'DEMO'){
            Check ($Body.evaluationEnvironmentConfirmation.confirmed -eq $true -and
                $Body.evaluationEnvironmentConfirmation.precheckId -ceq $pc.precheckId -and
                $Body.evaluationEnvironmentConfirmation.evalReportId -ceq $evaluated.id -and
                $Body.evaluationEnvironmentConfirmation.evidenceSha256 -ceq $evaluated.evidenceSha256) 'DEMO explicit confirmation carries exact precheck report and evidence'
        }else{Check ($null -eq $Body.evaluationEnvironmentConfirmation) 'REAL does not fabricate a DEMO confirmation'}
        if($script:publicationFault -ceq 'commit-gate'){throw 'JOINT_HTTP_UNEXPECTED_STATUS'}
        $app.status='PUBLISHED';$app.currentVersionNo=1
        $snapshot=[pscustomobject]@{appCode=$code;versionNo=1;appVersionId=(400+$script:snapshots.Count);draftRev=$app.draftRev;candidateSha256=$app.candidateSha256;config=$app.config;publicationTarget=$pc.publicationTarget;publicationEvidence=$script:reports[$evaluated.id].publicationEvidence;evaluationEnvironmentConfirmation=$Body.evaluationEnvironmentConfirmation}
        $script:snapshots[$code]=$snapshot
        if($script:publicationFault -ceq 'lost-commit'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
        if($code.EndsWith('_resolve')){$pin=[pscustomobject]@{state='SKIPPED';reason='config.ontology 缺少 space/releaseId'}}else{
            $pin=[pscustomobject]@{state='PINNED';bindingId=('5'+$app.id.ToString('x7')+'-1111-4111-8111-111111111111');changed=$true}
        }
        if($script:publicationFault -ceq 'pin-failed'){$pin=[pscustomobject]@{state='FAILED';reason='controlled failure'}}
        $data=[pscustomobject]@{appCode=$code;versionNo=1;sha256=$app.candidateSha256;pin=$pin}
    }elseif($versionNo -eq '1' -and $Method -ceq 'GET'){
        $data=ConvertTo-Json -InputObject $script:snapshots[$code] -Depth 30 -Compress|ConvertFrom-Json
        if(-not $data){throw 'PROTOCOL_VERSION_NOT_FOUND'}
        if($script:publicationFault -ceq 'readback-drift'){$data.publicationEvidence.evidenceSha256='a'*64}
    }else{throw 'PROTOCOL_ROUTE_UNEXPECTED'}
    return [pscustomobject]@{Data=$data}
}
function Run-Prechecks {Invoke-JointPlatformAppPublish -Mode Precheck -Plan $script:plan -State $script:state -Runtime $script:runtime -BearerToken 'unit-verified-context' -Save {Save-Protocol} -LivePublicationApproved}
function Run-Commits {Invoke-JointPlatformAppPublish -Mode Commit -Plan $script:plan -State $script:state -Runtime $script:runtime -BearerToken 'unit-verified-context' -Save {Save-Protocol} -Confirmation $script:confirmation -LivePublicationApproved}
$results=New-Object 'System.Collections.Generic.List[object]';$exitCode=0
foreach($test in @('PrecheckConfirmCommit','GatesAndConfirmation','UnknownAndReadback')){
    if($Case -ne 'All' -and $Case -ne $test){continue}
    try{
        Reset-Publication
        switch($test){
            'PrecheckConfirmCommit' {
                $pcs=Run-Prechecks
                Check (@($pcs.applications).Count -eq 3 -and $script:snapshots.Count -eq 0) 'three prechecks do not publish'
                $before=$script:requests.Count;Run-Prechecks|Out-Null
                Check (@($script:requests|Select-Object -Skip $before|Where-Object method -ne 'GET').Count -eq 0) 'fresh confirmed prechecks reuse readonly'
                Confirm-Prechecks;$published=Run-Commits
                Check (@($published.applications).Count -eq 3 -and $script:snapshots.Count -eq 3 -and
                    $published.environment -ceq 'DEMO' -and $published.complete -eq $false -and
                    $published.bindingVerification -ceq 'JOINT_ACCEPTANCE_PENDING' -and $script:state.complete -eq $false) 'three published protocol receipts retain real joint acceptance boundary'
                Check ($published.applications[0].pinState -ceq 'SKIPPED' -and $published.applications[1].pinState -ceq 'PINNED' -and
                    $published.applications[2].pinState -ceq 'PINNED' -and $published.applications[1].bindingId -cne $published.applications[2].bindingId) 'dynamic unpinned and two distinct old-release binding receipts'
                $before=$script:requests.Count;Run-Commits|Out-Null
                Check (@($script:requests|Select-Object -Skip $before|Where-Object method -ne 'GET').Count -eq 0) 'confirmed publication resume never recommits'
                Check (-not (($script:saved -join "`n").Contains('unit-verified-context'))) 'no bearer saved in publication journal'
                Reset-Publication -Real;Run-Prechecks|Out-Null;Confirm-Prechecks;$published=Run-Commits
                Check ($published.environment -ceq 'REAL' -and $published.externalTruth -ceq 'NOT_ASSESSED') 'Real mode retains external truth limitation'
            }
            'GatesAndConfirmation' {
                Reject {Invoke-JointPlatformAppPublish -Mode Precheck -Plan $script:plan -State $script:state -Runtime $script:runtime -BearerToken 'unit-verified-context' -Save {Save-Protocol}} 'JOINT_LIVE_PUBLICATION_REQUIRES_EXPLICIT_APPROVAL'
                Check ($script:requests.Count -eq 0) 'development task cannot start live precheck without future explicit approval'
                foreach($gateFault in @('advisory','waiver','demo-production','stale-report')){
                    Reset-Publication;$script:publicationFault=$gateFault
                    Reject {Run-Prechecks} 'JOINT_APP_PRECHECK_NOT_ACCEPTED'
                    Check ($script:snapshots.Count -eq 0) 'quality environment or exact report mismatch never commits'
                }
                Reset-Publication;Run-Prechecks|Out-Null
                Reject {Run-Commits} 'JOINT_APP_PUBLICATION_CONFIRMATION_REQUIRED'
                Confirm-Prechecks;$script:confirmation.applications[2].evidenceSha256='a'*64;$before=$script:requests.Count
                Reject {Run-Commits} 'JOINT_APP_PUBLICATION_CONFIRMATION_REQUIRED'
                Check (@($script:requests|Select-Object -Skip $before|Where-Object method -ne 'GET').Count -eq 0) 'third confirmation mismatch stops before any commit'
                Confirm-Prechecks
                foreach($role in @('resolve','explicit','pin')){(Get-JointValue $script:state.actions ('platform_app_'+$role+'_precheck_1')).requestedAtUtc=[DateTime]::UtcNow.AddMinutes(-16).ToString('o')}
                Reject {Run-Commits} 'JOINT_APP_PRECHECK_EXPIRED'
                Run-Prechecks|Out-Null
                Check ($script:state.actions.platform_app_resolve_precheck_1 -and $script:state.actions.platform_app_resolve_precheck_2) 'explicit precheck refresh retains original receipt'
                Reject {Run-Commits} 'JOINT_APP_PUBLICATION_CONFIRMATION_REQUIRED'
                Confirm-Prechecks;$script:publicationFault='commit-gate';Reject {Run-Commits} 'JOINT_HTTP_UNEXPECTED_STATUS'
                Check ($script:snapshots.Count -eq 0) 'service commit refusal is never bypassed'
            }
            'UnknownAndReadback' {
                $script:publicationFault='lost-precheck';Reject {Run-Prechecks} 'JOINT_HTTP_TRANSPORT_FAILED'
                $before=$script:requests.Count;Reject {Run-Prechecks} 'JOINT_PENDING_ACTION_NEEDS_RECONCILIATION'
                Check ($script:requests.Count -eq $before -and $script:state.pending.path.EndsWith('/precheck')) 'unknown precheck remains pending and is not repeated'
                Reset-Publication;Run-Prechecks|Out-Null;Confirm-Prechecks;$script:publicationFault='lost-commit'
                Reject {Run-Commits} 'JOINT_HTTP_TRANSPORT_FAILED'
                Check ($script:snapshots.Count -eq 1 -and $script:state.pending.path.EndsWith('/commit')) 'lost reply preserves possible committed version'
                $before=$script:requests.Count;Reject {Run-Commits} 'JOINT_PENDING_ACTION_NEEDS_RECONCILIATION'
                Check ($script:requests.Count -eq $before) 'unknown commit never gets a new request key'
                Reset-Publication;Run-Prechecks|Out-Null;Confirm-Prechecks;$script:publicationFault='pin-failed'
                Reject {Run-Commits} 'JOINT_APP_PIN_RESULT_UNCONFIRMED'
                Check ($script:snapshots.Count -eq 1 -and $script:state.pending -and $null -eq $script:state.platformAppPublications) 'post-transaction pin failure is not rolled back or claimed as complete'
                Reset-Publication;Run-Prechecks|Out-Null;Confirm-Prechecks;$script:publicationFault='readback-drift'
                Reject {Run-Commits} 'JOINT_APP_PUBLICATION_READBACK_MISMATCH'
                Check ($null -eq $script:state.pending -and $script:state.actions.platform_app_resolve_commit) 'confirmed commit survives readback failure'
                $script:publicationFault=$null;$before=$script:requests.Count;Run-Commits|Out-Null
                $firstPath='/admin/apps/'+$script:prefix+'_resolve/publish/commit'
                Check (@($script:requests|Select-Object -Skip $before|Where-Object{$_.method -ceq 'POST' -and $_.path -ceq $firstPath}).Count -eq 0) 'readback recovery does not recommit first app'
                Check ($script:snapshots.Count -eq 3 -and $script:state.stage -ceq 'PLATFORM_APPS_PUBLISHED') 'resume completes remaining confirmed protocol flow'
            }
        }
        $results.Add([pscustomobject]@{name=$test;passed=$true})
    }catch{$exitCode=1;$results.Add([pscustomobject]@{name=$test;passed=$false;error=$_.Exception.Message})}
}
[pscustomobject]@{scope='HTTP protocol substitutes only; no live precheck/evaluation/publication/joint acceptance';cases=$results;assertionsPassed=$checks.Count;passed=($exitCode -eq 0)}|ConvertTo-Json -Depth 5
exit $exitCode
