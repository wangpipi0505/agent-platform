param([Parameter(Mandatory=$true)][string]$CommonPath,
    [ValidateSet('All','DraftsResume','Refusal','UnknownAndReadback')][string]$Case='All')
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
function Invoke-JointHttp {
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
function Run-Drafts {Invoke-JointPlatformAppDrafts -Plan $script:plan -State $script:state -Runtime $script:runtime -BearerToken 'unit-verified-context' -Save {Save-Protocol}}
$results=New-Object 'System.Collections.Generic.List[object]';$exitCode=0
foreach($test in @('DraftsResume','Refusal','UnknownAndReadback')){
    if($Case -ne 'All' -and $Case -ne $test){continue}
    try{
        Reset-Protocol
        switch($test){
            'DraftsResume' {
                $receipt=Run-Drafts
                Check ($receipt.status -ceq 'DRAFT' -and $receipt.evaluationStatus -ceq 'NOT_EVALUATED' -and
                    $receipt.complete -eq $false -and $script:state.complete -eq $false -and $script:state.stage -ceq 'PLATFORM_APP_DRAFTS') 'drafts never claim evaluated published or All complete'
                Check (@($receipt.applications).Count -eq 3 -and @($receipt.applications|Select-Object -ExpandProperty id -Unique).Count -eq 3 -and
                    @($receipt.applications|Where-Object{$_.draftRev -ne 2 -or $_.candidateSha256 -cne ('d'*64)}).Count -eq 0) 'three distinct confirmed draft identities'
                Check ($receipt.appCode -ceq ($script:prefix+'_resolve') -and $receipt.explicitAppCode -ceq ($script:prefix+'_explicit') -and $receipt.pinAppCode -ceq ($script:prefix+'_pin')) 'three stable session codes'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 3 -and @($script:requests|Where-Object method -eq 'PUT').Count -eq 3) 'exactly three creates and three config writes'
                $before=$script:requests.Count;Run-Drafts|Out-Null
                Check (@($script:requests|Select-Object -Skip $before|Where-Object method -ne 'GET').Count -eq 0) 'confirmed resume only reads'
                Check ($script:state.platformSkill.status -ceq 'DRAFT' -and $script:state.platformPublishedSkill.status -ceq 'PUBLISHED') 'old draft and published facts retained'
                Check (-not (($script:saved -join "`n").Contains('unit-verified-context'))) 'no bearer persisted'
            }
            'Refusal' {
                $script:state.platformPublishedSkill=$null;Reject {Run-Drafts} 'JOINT_SKILL_PUBLICATION_STAGE_REQUIRED'
                Check ($script:requests.Count -eq 0) 'missing publish evidence stops before HTTP'
                Reset-Protocol;$script:state.actions.A_first_commit.spaceId='44444444-4444-4444-8444-444444444444';Reject {Run-Drafts} 'JOINT_APP_ONTOLOGY_IDENTITY_INVALID'
                Check ($script:requests.Count -eq 0) 'mismatched release ownership stops before HTTP'
                Reset-Protocol;$script:fault='model-disabled';Reject {Run-Drafts} 'JOINT_MODEL_ENTRY_NOT_READY'
                Check ($script:apps.Count -eq 0) 'disabled model creates zero apps'
                Reset-Protocol;$script:fault='skill-drift';Reject {Run-Drafts} 'JOINT_SKILL_DRAFT_CHANGED'
                Check ($script:apps.Count -eq 0) 'changed skill creates zero apps'
                Reset-Protocol;$script:fault='collision';Reject {Run-Drafts} 'JOINT_APP_CODE_ALREADY_EXISTS'
                Check ($script:apps.Count -eq 0) 'third code collision detected before any create'
                Reset-Protocol;Run-Drafts|Out-Null
                $script:apps[$script:prefix+'_resolve'].config.systemPrompt='user edit'
                $script:apps[$script:prefix+'_resolve'].candidateSha256='f'*64
                $before=$script:requests.Count;Reject {Run-Drafts} 'JOINT_APP_DRAFT_CHANGED'
                Check (@($script:requests|Select-Object -Skip $before|Where-Object method -ne 'GET').Count -eq 0) 'resume does not overwrite user edits'
            }
            'UnknownAndReadback' {
                $script:fault='lost-create';Reject {Run-Drafts} 'JOINT_HTTP_TRANSPORT_FAILED'
                Check ($script:state.pending.action -ceq 'platform_app_resolve_create' -and $script:apps.Count -eq 1) 'unknown create preserved without delete'
                $before=$script:requests.Count;Reject {Run-Drafts} 'JOINT_PENDING_ACTION_NEEDS_RECONCILIATION'
                Check ($script:requests.Count -eq $before) 'unknown create never repeated'
                Reset-Protocol;$script:fault='lost-config';Reject {Run-Drafts} 'JOINT_HTTP_TRANSPORT_FAILED'
                Check ($script:state.pending.action -ceq 'platform_app_resolve_config' -and $script:apps[$script:prefix+'_resolve'].draftRev -eq 2) 'unknown config write remains pending'
                $before=$script:requests.Count;Reject {Run-Drafts} 'JOINT_PENDING_ACTION_NEEDS_RECONCILIATION'
                Check ($script:requests.Count -eq $before) 'unknown config never repeated'
                Reset-Protocol;$script:fault='readback-drift';Reject {Run-Drafts} 'JOINT_APP_DRAFT_CHANGED'
                Check ($null -eq $script:state.pending -and $script:state.actions.platform_app_resolve_config -and
                    $null -eq $script:state.platformAppDrafts) 'bad final readback is not successful stage'
                $script:fault=$null;$before=$script:requests.Count;Run-Drafts|Out-Null
                $resolvePath='/admin/apps/'+$script:prefix+'_resolve/config'
                Check (@($script:requests|Select-Object -Skip $before|Where-Object{$_.method -ceq 'PUT' -and $_.path -ceq $resolvePath}).Count -eq 0) 'readback recovery does not rewrite confirmed config'
                Check ($script:apps.Count -eq 3 -and $script:state.stage -ceq 'PLATFORM_APP_DRAFTS') 'confirmed partial resumes remaining applications'
            }
        }
        $results.Add([pscustomobject]@{name=$test;passed=$true})
    }catch{$exitCode=1;$results.Add([pscustomobject]@{name=$test;passed=$false;error=$_.Exception.Message})}
}
[pscustomobject]@{scope='HTTP protocol substitutes; no real model/PG/apps/joint acceptance';cases=$results;assertionsPassed=$checks.Count;passed=($exitCode -eq 0)}|ConvertTo-Json -Depth 5
exit $exitCode
