param([Parameter(Mandatory=$true)][string]$CommonPath,
    [ValidateSet('All','ReadyResume','UnknownStop','IdentityAndRecovery')][string]$Case='All')
$ErrorActionPreference='Stop'
. $CommonPath
$checks=New-Object 'System.Collections.Generic.List[string]'
function Check([bool]$Value,[string]$Name){if(-not $Value){throw ('ASSERTION_FAILED:'+ $Name)};$checks.Add($Name)}
function Reject([scriptblock]$Action,[string]$Code){
    $caught=$null;try{& $Action|Out-Null}catch{$caught=$_.Exception.Message}
    Check ($caught -ceq $Code) $Code
}
function Reset-Protocol {
    $script:plan=[pscustomobject]@{SessionId='joint-connect-unit';PlatformTenant='local-v12';PlatformBaseUrl='http://127.0.0.1:18080';ModelMode='TestFixture';ModelEntryId=$null}
    $script:runtime=[pscustomobject][ordered]@{sessionId='joint-connect-unit';platformTenant='local-v12';platformBaseUrl='http://127.0.0.1:18080';databaseName='agent_platform_joint_connect_unit';redisHost='127.0.0.1';redisPort=29379;isolationConfirmed=$true;modelDemoMode=$true;engineDemoMode=$true;fixtureBaseUrl='http://127.0.0.1:29380'}
    $script:skill=[pscustomobject][ordered]@{skillId=81;code='joint_unit_op';skillVersionId=82;versionNo=1;sha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;status='DRAFT';authRef='env:AGENT_SECRET_JOINT_AABBCCDDEEFF_CONSUMER';complete=$false}
    $script:state=[pscustomobject]@{actions=[pscustomobject]@{platform_skill_import=$script:skill};platformSkill=$script:skill;pending=$null;complete=$false;stage='PLATFORM_SKILL_DRAFT_READY';platformRuntime=$script:runtime}
    $script:definition=[pscustomobject]@{type='HTTP';invocationKind='SYNC';authScheme='API_KEY';authConfigured=$true;riskLevel='READ_ONLY';execution=[pscustomobject]@{readonly=$true;sideEffect=$false}}
    $script:runId='r_'+('d'*32)
    $script:requests=New-Object 'System.Collections.Generic.List[object]'
    $script:saved=New-Object 'System.Collections.Generic.List[string]'
    $script:fault=$null;$script:polls=0
}
function Save-Protocol {
    Assert-JointSafeMetadata $script:state
    $script:saved.Add((ConvertTo-Json -InputObject $script:state -Depth 20 -Compress))
}
# 只替换HTTP协议边界；真实DEBUG、DB事实和外部OR调用必须在后续实际环境取得。
function Invoke-JointHttp {
    param($BaseUrl,$Path,$Method='GET',$Body,$BearerToken,$TimeoutSeconds)
    Check ($BaseUrl -ceq $script:plan.PlatformBaseUrl -and $BearerToken -ceq 'unit-verified-context') 'only AP origin and verified bearer'
    Check ($TimeoutSeconds -ge 1 -and $TimeoutSeconds -le 30) 'bounded HTTP request'
    $script:requests.Add([pscustomobject]@{method=$Method;path=$Path;recheck=($null -ne $Body -and $Body.ContainsKey('controlledCallId'))})
    if($Path -ceq '/admin/skills/joint_unit_op/versions' -and $Method -ceq 'GET'){
        $data=@([pscustomobject]@{skillVersionId=82;versionNo=1;status='DRAFT';sha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;definition=$script:definition})
        if($script:fault -ceq 'draft-changed'){$data[0].sha256='e'*64}
    }elseif($Path -ceq '/admin/skills/joint_unit_op/drafts/1/connection-test' -and $Method -ceq 'POST'){
        Check ($script:state.pending.path -ceq $Path -and ($script:saved[$script:saved.Count-1]|ConvertFrom-Json).pending.path -ceq $Path) 'pending saved before connection write'
        if($Body.ContainsKey('controlledCallId')){
            Check ($Body.Count -eq 1 -and $Body.controlledCallId -eq 91) 'recheck sends only actual controlled call'
            if($script:fault -ceq 'lost-recheck'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
            $data=[pscustomobject]@{ok=$true;candidateSha256=('b'*64);semanticDigest=('c'*64);digestVersion=1}
            if($script:fault -ceq 'no-fact'){$data.ok=$false}
        }else{
            Check ($Body.requestId -cmatch '^ex06-connection-[a-f0-9]{32}$' -and $Body.inputs.Count -eq 0 -and $Body.Count -eq 2) 'stable request and original empty inputs'
            if($script:fault -ceq 'lost-start'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
            $data=[pscustomobject]@{ok=$false;runId=$script:runId;status='PENDING';action='WAIT_AND_RECHECK';candidateSha256=('b'*64);semanticDigest=('c'*64);digestVersion=1}
            if($script:fault -ceq 'wrong-start-digest'){$data.candidateSha256='e'*64}
        }
    }elseif($Path -ceq ('/admin/debug-runs/'+$script:runId+'/status') -and $Method -ceq 'GET'){
        $script:polls++
        if($script:fault -ceq 'read-failure'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
        $data=[pscustomobject]@{runId=$script:runId;appCode='skill_test_82';controlledSkillVersionId=82;controlledSkillStatus='DRAFT';controlledCallId=91;status='SUCCEEDED';environment='DEMO';demoMode=$true}
        if($script:fault -ceq 'first-pending' -and $script:polls -eq 1){$data.status='PENDING';$data.controlledCallId=$null}
        if($script:fault -ceq 'wrong-run'){$data.controlledSkillVersionId=83}
        if($script:fault -ceq 'wrong-environment'){$data.environment='REAL';$data.demoMode=$false}
        if($script:fault -ceq 'run-failed'){$data.status='FAILED'}
    }else{throw 'PROTOCOL_ROUTE_UNEXPECTED'}
    return [pscustomobject]@{Data=$data}
}
function Run-Connect { Invoke-JointPlatformSkillConnect -Plan $script:plan -State $script:state -Runtime $script:runtime -BearerToken 'unit-verified-context' -Save {Save-Protocol} }
$results=New-Object 'System.Collections.Generic.List[object]';$exitCode=0
foreach($test in @('ReadyResume','UnknownStop','IdentityAndRecovery')){
    if($Case -ne 'All' -and $Case -ne $test){continue}
    try{
        Reset-Protocol
        switch($test){
            'ReadyResume' {
                $script:fault='first-pending';$receipt=Run-Connect
                Check ($receipt.ok -eq $true -and $receipt.controlledCallId -eq 91 -and $script:polls -eq 2) 'initial false waits for controlled run then real fact recheck'
                Check ($script:state.stage -ceq 'PLATFORM_SKILL_CONNECTED' -and $script:state.complete -eq $false) 'connection readiness is not publication or All'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 2) 'only one start and one recheck'
                $before=$script:requests.Count;Run-Connect|Out-Null
                Check (@($script:requests|Select-Object -Skip $before|Where-Object method -eq 'POST').Count -eq 0) 'resume does not start or rewrite confirmed fact'
                Check (-not (($script:saved -join "`n").Contains('unit-verified-context'))) 'no bearer in journal'
                Check (-not (Get-JointNames $receipt).Contains('reportId')) 'does not invent reportId'
            }
            'UnknownStop' {
                $script:fault='lost-start';Reject {Run-Connect} 'JOINT_HTTP_TRANSPORT_FAILED'
                Check ($script:state.pending.action -ceq 'platform_skill_connection_start') 'unknown start kept pending'
                $before=$script:requests.Count;Reject {Run-Connect} 'JOINT_PENDING_ACTION_NEEDS_RECONCILIATION'
                Check ($script:requests.Count -eq $before) 'unknown start never retried'
                Reset-Protocol;$script:fault='lost-recheck';Reject {Run-Connect} 'JOINT_HTTP_TRANSPORT_FAILED'
                Check ($script:state.pending.action -ceq 'platform_skill_connection_recheck') 'unknown report write kept pending'
                Reset-Protocol;$script:fault='no-fact';Reject {Run-Connect} 'JOINT_SKILL_CONNECTION_FACT_NOT_PASSED'
                Check ($script:state.stage -cne 'PLATFORM_SKILL_CONNECTED') 'run succeeded alone cannot replace nonmock fact'
            }
            'IdentityAndRecovery' {
                $script:fault='draft-changed';Reject {Run-Connect} 'JOINT_SKILL_DRAFT_CHANGED'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 0) 'changed draft never starts'
                Reset-Protocol;$script:definition.execution.sideEffect=$true;Reject {Run-Connect} 'JOINT_SKILL_CONNECTION_NOT_READONLY'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 0) 'side effect cannot bypass approval'
                Reset-Protocol;$script:fault='wrong-environment';Reject {Run-Connect} 'JOINT_SKILL_CONNECTION_ENVIRONMENT_MISMATCH'
                Check (@($script:requests|Where-Object recheck -eq $true).Count -eq 0) 'wrong actual environment cannot recheck'
                Reset-Protocol;$script:fault='wrong-run';Reject {Run-Connect} 'JOINT_SKILL_CONNECTION_RUN_MISMATCH'
                Reset-Protocol;$script:fault='run-failed';Reject {Run-Connect} 'JOINT_SKILL_CONNECTION_RUN_NOT_SUCCEEDED'
                Reset-Protocol;$script:fault='read-failure';Reject {Run-Connect} 'JOINT_HTTP_TRANSPORT_FAILED'
                Check ($null -eq $script:state.pending -and $null -ne $script:state.actions.platform_skill_connection_start) 'confirmed start survives read failure'
                $script:fault=$null;$before=$script:requests.Count;Run-Connect|Out-Null
                $writes=@($script:requests|Select-Object -Skip $before|Where-Object method -eq 'POST')
                Check ($writes.Count -eq 1 -and $writes[0].recheck) 'recovery reuses exact run and only records final fact'
            }
        }
        $results.Add([pscustomobject]@{name=$test;passed=$true})
    }catch{$exitCode=1;$results.Add([pscustomobject]@{name=$test;passed=$false;error=$_.Exception.Message})}
}
[pscustomobject]@{scope='script orchestration with HTTP substitutes; no real DEBUG/PG/OR';cases=$results;assertionsPassed=$checks.Count;passed=($exitCode -eq 0)}|ConvertTo-Json -Depth 5
exit $exitCode
