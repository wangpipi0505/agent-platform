param([Parameter(Mandatory=$true)][string]$CommonPath,
    [ValidateSet('All','PublishResume','Refusal','UnknownAndReadback')][string]$Case='All')
$ErrorActionPreference='Stop'
. $CommonPath
$checks=New-Object 'System.Collections.Generic.List[string]'
function Check([bool]$Value,[string]$Name){if(-not $Value){throw ('ASSERTION_FAILED:'+ $Name)};$checks.Add($Name)}
function Reject([scriptblock]$Action,[string]$Code){
    $caught=$null;try{& $Action|Out-Null}catch{$caught=$_.Exception.Message}
    Check ($caught -ceq $Code) $Code
}
function Reset-Protocol {
    $script:plan=[pscustomobject]@{SessionId='joint-publish-unit';PlatformTenant='local-v12';PlatformBaseUrl='http://127.0.0.1:18080';ModelMode='TestFixture';ModelEntryId=$null}
    $script:runtime=[pscustomobject][ordered]@{sessionId='joint-publish-unit';platformTenant='local-v12';platformBaseUrl='http://127.0.0.1:18080';databaseName='agent_platform_joint_publish_unit';redisHost='127.0.0.1';redisPort=29379;isolationConfirmed=$true;modelDemoMode=$true;engineDemoMode=$true;fixtureBaseUrl='http://127.0.0.1:29380'}
    $script:skill=[pscustomobject][ordered]@{skillId=81;code='joint_unit_op';skillVersionId=82;versionNo=1;sha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;status='DRAFT';authRef='env:AGENT_SECRET_JOINT_AABBCCDDEEFF_CONSUMER';complete=$false}
    $script:connection=[pscustomobject]@{ok=$true;skillVersionId=82;candidateSha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;environment='DEMO';demoMode=$true}
    $script:model=[pscustomobject]@{modelEntryId=72;providerId=61;modelName='ready.model';healthStatus='HEALTHY';modelMode='TestFixture';complete=$false}
    $script:state=[pscustomobject]@{actions=[pscustomobject]@{platform_skill_import=$script:skill;platform_skill_connection_recheck=$script:connection};platformSkill=$script:skill;platformConnection=$script:connection;platformModel=$script:model;pending=$null;complete=$false;stage='PLATFORM_SKILL_CONNECTED';platformRuntime=$script:runtime}
    $script:definition=[pscustomobject]@{type='HTTP';invocationKind='SYNC';authScheme='API_KEY';authConfigured=$true;riskLevel='READ_ONLY';execution=[pscustomobject]@{readonly=$true;sideEffect=$false};guard=[pscustomobject]@{attributes=[pscustomobject]@{region=@()}}}
    $sha=[Security.Cryptography.SHA256]::Create()
    try{$script:providerCode='fixture_joint_'+([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($script:plan.SessionId))).Replace('-','').ToLowerInvariant()).Substring(0,24)}finally{$sha.Dispose()}
    $script:requests=New-Object 'System.Collections.Generic.List[object]'
    $script:saved=New-Object 'System.Collections.Generic.List[string]'
    $script:fault=$null;$script:status='DRAFT';$script:accepted=$false
}
function Save-Protocol {
    Assert-JointSafeMetadata $script:state
    $script:saved.Add((ConvertTo-Json -InputObject $script:state -Depth 20 -Compress))
}
function Version-Protocol {
    return [pscustomobject]@{code='joint_unit_op';skillVersionId=82;versionNo=1;status=$script:status;sha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;definition=$script:definition}
}
# 只替换HTTP边界。真实模型选型与发布门由产品API执行，协议替身不充当PG/双仓验收。
function Invoke-JointHttp {
    param($BaseUrl,$Path,$Method='GET',$Body,$BearerToken,$TimeoutSeconds)
    Check ($BaseUrl -ceq $script:plan.PlatformBaseUrl -and $BearerToken -ceq 'unit-verified-context') 'fixed AP origin and verified bearer'
    Check ($TimeoutSeconds -ge 1 -and $TimeoutSeconds -le 30) 'bounded request'
    $script:requests.Add([pscustomobject]@{method=$Method;path=$Path})
    if($Method -ceq 'POST'){
        Check ($script:state.pending.path -ceq $Path -and ($script:saved[$script:saved.Count-1]|ConvertFrom-Json).pending.path -ceq $Path) 'pending saved before writes'
    }
    if($Path -ceq '/admin/skills/joint_unit_op/versions' -and $Method -ceq 'GET'){
        $version=Version-Protocol
        if($script:fault -ceq 'draft-changed'){$version.sha256='e'*64}
        $data=@($version)
    }elseif($Path -ceq '/admin/model-entries' -and $Method -ceq 'GET'){
        $data=@([pscustomobject]@{id=72;providerId=61;modelName='ready.model';status='ENABLED';capabilities=@('chat','tools');provider=[pscustomobject]@{id=61;code=$script:providerCode;status='ENABLED';trustLabels=@('LOCAL')}})
        if($script:fault -ceq 'model-disabled'){$data[0].status='DISABLED'}
    }elseif($Path -ceq '/admin/model-providers' -and $Method -ceq 'GET'){
        $data=@([pscustomobject]@{id=61;code=$script:providerCode;status='ENABLED';protocol='OPENAI_COMPATIBLE';baseUrl=$script:runtime.fixtureBaseUrl})
    }elseif($Path -ceq '/admin/skills/joint_unit_op/drafts/1/acceptance' -and $Method -ceq 'POST'){
        Check ($Body.modelEntryId -eq 72 -and @($Body.cases).Count -eq 1 -and $Body.cases[0].question -ceq 'EX06_TOOL:joint_unit_op' -and
            $Body.cases[0].expectSelected -ceq 'joint_unit_op' -and $Body.cases[0].expectArgs.Count -eq 0) 'actual fixture tool and empty arguments case'
        if($script:fault -ceq 'lost-acceptance'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
        $data=[pscustomobject]@{id='sa_11111111-1111-4111-8111-111111111111';pass=$true;hitRate=1;gate=[pscustomobject]@{minHitRate=0.8};candidateSha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;cases=@([pscustomobject]@{question='EX06_TOOL:joint_unit_op';selected='joint_unit_op';argsOk=$true;pass=$true;actualArgs=[pscustomobject]@{}})}
        if($script:fault -ceq 'wrong-selection'){$data.cases[0].selected='other_tool'}
        if($script:fault -ceq 'wrong-args'){$data.cases[0].actualArgs=[pscustomobject]@{unexpected=1}}
        $script:accepted=$true
    }elseif($Path -ceq '/admin/skills/joint_unit_op/drafts/1/publish' -and $Method -ceq 'POST'){
        Check ($script:accepted -and $null -eq $Body) 'publish only after acceptance with no bypass body'
        if($script:fault -ceq 'publish-gate') {throw 'JOINT_HTTP_UNEXPECTED_STATUS'}
        $script:status='PUBLISHED'
        if($script:fault -ceq 'lost-publish'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
        $data=Version-Protocol
    }elseif($Path -ceq '/admin/skills/joint_unit_op' -and $Method -ceq 'GET'){
        $data=[pscustomobject]@{id=81;code='joint_unit_op';status=$script:status;currentVersionId=82;currentVersion=1}
        if($script:fault -ceq 'readback-drift'){$data.currentVersionId=83}
    }else{throw 'PROTOCOL_ROUTE_UNEXPECTED'}
    return [pscustomobject]@{Data=$data}
}
function Run-Publish { Invoke-JointPlatformSkillPublish -Plan $script:plan -State $script:state -Runtime $script:runtime -BearerToken 'unit-verified-context' -Save {Save-Protocol} }
$results=New-Object 'System.Collections.Generic.List[object]';$exitCode=0
foreach($test in @('PublishResume','Refusal','UnknownAndReadback')){
    if($Case -ne 'All' -and $Case -ne $test){continue}
    try{
        Reset-Protocol
        switch($test){
            'PublishResume' {
                $receipt=Run-Publish
                Check ($receipt.status -ceq 'PUBLISHED' -and $receipt.skillVersionId -eq 82 -and $receipt.acceptanceId -ceq 'sa_11111111-1111-4111-8111-111111111111') 'published receipt uses actual acceptance and version'
                Check ($script:state.stage -ceq 'PLATFORM_SKILL_PUBLISHED' -and $script:state.complete -eq $false) 'skill published does not complete All'
                Check ($script:state.platformSkill.status -ceq 'DRAFT' -and $script:state.platformConnection.ok) 'historical draft and connection records remain unchanged'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 2) 'only acceptance and publish writes'
                $before=$script:requests.Count;Run-Publish|Out-Null
                Check (@($script:requests|Select-Object -Skip $before|Where-Object method -eq 'POST').Count -eq 0) 'confirmed publish resume is readonly'
                Check (-not (($script:saved -join "`n").Contains('unit-verified-context'))) 'no bearer persisted'
            }
            'Refusal' {
                $script:fault='draft-changed';Reject {Run-Publish} 'JOINT_SKILL_DRAFT_CHANGED'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 0) 'changed draft causes zero writes'
                Reset-Protocol;$script:fault='model-disabled';Reject {Run-Publish} 'JOINT_MODEL_ENTRY_NOT_READY'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 0) 'disabled model never evaluates'
                Reset-Protocol;$script:fault='wrong-selection';Reject {Run-Publish} 'JOINT_SKILL_ACCEPTANCE_NOT_PASSED'
                Check ($script:status -ceq 'DRAFT') 'incorrect selected tool cannot publish'
                Reset-Protocol;$script:fault='wrong-args';Reject {Run-Publish} 'JOINT_SKILL_ACCEPTANCE_NOT_PASSED'
                Reset-Protocol;$script:definition.guard.attributes=[pscustomobject]@{};Reject {Run-Publish} 'JOINT_SKILL_PUBLISH_GUARD_INVALID'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 0) 'empty query guard refuses before writes'
            }
            'UnknownAndReadback' {
                $script:fault='lost-acceptance';Reject {Run-Publish} 'JOINT_HTTP_TRANSPORT_FAILED'
                Check ($script:state.pending.action -ceq 'platform_skill_acceptance') 'unknown acceptance remains pending'
                $before=$script:requests.Count;Reject {Run-Publish} 'JOINT_PENDING_ACTION_NEEDS_RECONCILIATION'
                Check ($script:requests.Count -eq $before) 'unknown writes are not repeated'
                Reset-Protocol;$script:fault='publish-gate';Reject {Run-Publish} 'JOINT_HTTP_UNEXPECTED_STATUS'
                Check ($script:status -ceq 'DRAFT') 'server publish gate is not bypassed'
                Reset-Protocol;$script:fault='lost-publish';Reject {Run-Publish} 'JOINT_HTTP_TRANSPORT_FAILED'
                Check ($script:state.pending.action -ceq 'platform_skill_publish' -and $null -eq $script:state.platformPublishedSkill) 'lost publish reply never becomes success'
                Reset-Protocol;$script:fault='readback-drift';Reject {Run-Publish} 'JOINT_SKILL_PUBLICATION_READBACK_MISMATCH'
                Check ($null -eq $script:state.pending -and $script:state.stage -cne 'PLATFORM_SKILL_PUBLISHED') 'confirmed mutation with bad readback remains incomplete'
                $script:fault=$null;$before=$script:requests.Count;Run-Publish|Out-Null
                Check (@($script:requests|Select-Object -Skip $before|Where-Object method -eq 'POST').Count -eq 0) 'readback recovery does not republish'
            }
        }
        $results.Add([pscustomobject]@{name=$test;passed=$true})
    }catch{$exitCode=1;$results.Add([pscustomobject]@{name=$test;passed=$false;error=$_.Exception.Message})}
}
[pscustomobject]@{scope='HTTP protocol substitutes; no real model/PG/publish';cases=$results;assertionsPassed=$checks.Count;passed=($exitCode -eq 0)}|ConvertTo-Json -Depth 5
exit $exitCode
