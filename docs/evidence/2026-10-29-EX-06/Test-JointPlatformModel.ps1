param([Parameter(Mandatory=$true)][string]$CommonPath,
    [ValidateSet('All','ReadyResume','UnknownStop','RefusalAndReal')][string]$Case='All')
$ErrorActionPreference='Stop'
. $CommonPath
$checks=New-Object 'System.Collections.Generic.List[string]'
function Check([bool]$Value,[string]$Name){if(-not $Value){throw ('ASSERTION_FAILED:'+ $Name)};$checks.Add($Name)}
function Reject([scriptblock]$Action,[string]$Code){
    $caught=$null;try{& $Action|Out-Null}catch{$caught=$_.Exception.Message}
    Check ($caught -ceq $Code) $Code
}
function Reset-Protocol {
    $script:plan=[pscustomobject]@{SessionId='joint-model-unit';PlatformTenant='local-v12';PlatformBaseUrl='http://127.0.0.1:18080';ModelMode='TestFixture';ModelEntryId=$null}
    $script:runtime=[pscustomobject]@{sessionId='joint-model-unit';platformTenant='local-v12';platformBaseUrl='http://127.0.0.1:18080';databaseName='agent_platform_joint_model_unit';redisHost='127.0.0.1';redisPort=29379;isolationConfirmed=$true;modelDemoMode=$true;engineDemoMode=$true;fixtureBaseUrl='http://127.0.0.1:29380'}
    $script:state=[pscustomobject]@{resources=[pscustomobject]@{spaceId='space-unit';releaseId='release-unit'};actions=[pscustomobject]@{};pending=$null;complete=$false;stage='ONTOLOGY_READY'}
    $script:requests=New-Object 'System.Collections.Generic.List[object]'
    $script:saved=New-Object 'System.Collections.Generic.List[string]'
    $script:provider=$null;$script:entry=$null;$script:fault=$null
}
function Save-Protocol {
    Assert-JointSafeMetadata $script:state
    $script:saved.Add((ConvertTo-Json -InputObject $script:state -Depth 20 -Compress))
}
# 仅替换HTTP传输边界；调用候选真实编排函数。此处不冒充AP/PG或模型实际成功。
function Invoke-JointHttp {
    param($BaseUrl,$Path,$Method='GET',$Body,$BearerToken,$TimeoutSeconds)
    if($BaseUrl -cne $script:plan.PlatformBaseUrl -or $BearerToken -cne 'unit-verified-context' -or $TimeoutSeconds -lt 1 -or $TimeoutSeconds -gt 30){throw 'PROTOCOL_REQUEST_INVALID'}
    $script:requests.Add([pscustomobject]@{method=$Method;path=$Path})
    if($Method -ceq 'POST' -and $Path -cne '/admin/model-entries/72/test'){
        Check ($script:state.pending.path -ceq $Path) 'write has pending before transport'
        Check (($script:saved[$script:saved.Count-1]|ConvertFrom-Json).pending.path -ceq $Path) 'pending saved before transport'
    }
    $data=$null
    if($Path -ceq '/admin/model-providers' -and $Method -ceq 'POST'){
        Check ($Body.code -cmatch '^fixture_joint_[a-f0-9]{24}$') 'fixture provider has deterministic session name'
        Check ($Body.baseUrl -ceq $script:runtime.fixtureBaseUrl) 'provider uses explicit fixture origin'
        if($script:fault -ceq 'lost-create'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
        $script:provider=[pscustomobject]@{id=61;code=$Body.code;protocol=$Body.protocol;baseUrl=$Body.baseUrl;trustLabels=@('LOCAL');status='DISABLED'}
        $data=[pscustomobject]@{id=61;code=$Body.code;status='DISABLED'}
    }elseif($Path -ceq '/admin/model-providers' -and $Method -ceq 'GET'){
        $data=@($script:provider)
    }elseif($Path -ceq '/admin/model-providers/61/test' -and $Method -ceq 'POST'){
        $script:provider.status='ENABLED'
        $data=[pscustomobject]@{ok=$true;stage='OK';discoveredModels=@('ready.model')}
        if($script:fault -ceq 'provider-failed'){$data.ok=$false}
    }elseif($Path -ceq '/admin/model-entries' -and $Method -ceq 'POST'){
        Check ($Body.providerId -eq 61 -and $Body.modelName -ceq 'ready.model') 'entry is bound to confirmed provider'
        $script:entry=[pscustomobject]@{id=72;providerId=61;modelName='ready.model';status='ENABLED';capabilities=@('chat','tools');provider=$script:provider}
        $data=$script:entry
    }elseif($Path -ceq '/admin/model-entries' -and $Method -ceq 'GET'){
        $data=@($script:entry)
    }elseif($Path -ceq '/admin/model-entries/72/test' -and $Method -ceq 'POST'){
        $data=[pscustomobject]@{entryId=72;providerId=61;model=$script:entry.modelName;ok=$true;status='HEALTHY';checks=@('config','endpoint','auth','model','tools'|ForEach-Object{[pscustomobject]@{key=$_;status='PASS'}})}
        if($script:fault -ceq 'tools-not-observed'){$data.checks[-1].status='WARN'}
    }else{throw 'PROTOCOL_ROUTE_UNEXPECTED'}
    return [pscustomobject]@{Data=$data}
}
function Run-Model { Invoke-JointPlatformModel -Plan $script:plan -State $script:state -Runtime $script:runtime -BearerToken 'unit-verified-context' -Save {Save-Protocol} }
$results=New-Object 'System.Collections.Generic.List[object]'
$exitCode=0
foreach($test in @('ReadyResume','UnknownStop','RefusalAndReal')){
    if($Case -ne 'All' -and $Case -ne $test){continue}
    try{
        Reset-Protocol
        switch($test){
            'ReadyResume' {
                $ready=Run-Model
                Check ($ready.modelEntryId -eq 72 -and $ready.modelMode -ceq 'TestFixture') 'ready references actual returned entry'
                Check ($script:state.stage -ceq 'PLATFORM_MODEL_READY' -and $script:state.complete -eq $false) 'partial model readiness does not complete All'
                Check ($null -eq $script:state.pending) 'successful writes clear pending'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 4) 'one create per resource and real probes'
                $before=$script:requests.Count
                Run-Model|Out-Null
                $later=@($script:requests|Select-Object -Skip $before)
                Check (@($later|Where-Object{$_.method -ceq 'POST' -and $_.path -cne '/admin/model-entries/72/test'}).Count -eq 0) 'resume never recreates confirmed resources'
                Check (@($later|Where-Object path -eq '/admin/model-entries/72/test').Count -eq 1) 'resume rechecks actual health'
                Check (-not (($script:saved -join "`n").Contains('unit-verified-context'))) 'journal never contains bearer token'
            }
            'UnknownStop' {
                $script:fault='lost-create'
                Reject {Run-Model} 'JOINT_HTTP_TRANSPORT_FAILED'
                Check ($script:state.pending.action -ceq 'model_provider' -and $script:requests.Count -eq 1) 'unknown provider write stops before downstream writes'
                Reject {Run-Model} 'JOINT_PENDING_ACTION_NEEDS_RECONCILIATION'
                Check ($script:requests.Count -eq 1) 'resume does not retry unknown create'
                Reset-Protocol;$script:fault='provider-failed'
                Reject {Run-Model} 'JOINT_MODEL_PROVIDER_TEST_FAILED'
                Check ($null -eq $script:entry -and $script:state.pending.action -ceq 'model_provider_test') 'failed test cannot manufacture enabled entry'
                Reset-Protocol;$script:fault='tools-not-observed'
                Reject {Run-Model} 'JOINT_MODEL_HEALTH_FAILED'
                Check ($script:state.stage -cne 'PLATFORM_MODEL_READY') 'missing tools observation cannot mark ready'
            }
            'RefusalAndReal' {
                $script:runtime.modelDemoMode=$false
                Reject {Run-Model} 'JOINT_PLATFORM_DEMO_REQUIRED'
                Check ($script:requests.Count -eq 0) 'DEMO mismatch refuses before HTTP'
                Reset-Protocol;$script:runtime.platformTenant='other-tenant'
                Reject {Run-Model} 'JOINT_PLATFORM_RUNTIME_CONTEXT_MISMATCH'
                Check ($script:requests.Count -eq 0) 'tenant mismatch refuses before HTTP'
                Reset-Protocol;$script:runtime.redisPort=26379
                Reject {Run-Model} 'JOINT_PLATFORM_ISOLATION_REQUIRED'
                Check ($script:requests.Count -eq 0) 'shared Redis default cannot be admitted'
                Reset-Protocol;$script:plan.ModelMode='Real';$script:plan.ModelEntryId='72'
                $script:runtime.modelDemoMode=$false;$script:runtime.engineDemoMode=$false;$script:runtime.fixtureBaseUrl=$null
                $script:provider=[pscustomobject]@{id=61;code='operator_real';status='ENABLED';trustLabels=@('LOCAL')}
                $script:entry=[pscustomobject]@{id=72;providerId=61;modelName='operator.model';status='ENABLED';capabilities=@('chat','tools');provider=$script:provider}
                $real=Run-Model
                Check ($real.modelEntryId -eq 72 -and $real.modelMode -ceq 'Real') 'Real uses exactly explicit entry'
                Check (@($script:requests|Where-Object{$_.method -ceq 'POST' -and $_.path -cne '/admin/model-entries/72/test'}).Count -eq 0) 'Real never creates provider or entry'
                $script:provider.code='fixture_operator';$before=$script:requests.Count
                Reject {Run-Model} 'JOINT_REAL_MODEL_NOT_READY'
                Check (@($script:requests|Select-Object -Skip $before|Where-Object method -eq 'POST').Count -eq 0) 'Real rejects fixture before probe'
            }
        }
        $results.Add([pscustomobject]@{name=$test;passed=$true})
    }catch{$exitCode=1;$results.Add([pscustomobject]@{name=$test;passed=$false;error=$_.Exception.Message})}
}
[pscustomobject]@{scope='script orchestration protocol; no AP/PG/external model';cases=$results;assertionsPassed=$checks.Count;passed=($exitCode -eq 0)}|ConvertTo-Json -Depth 5
exit $exitCode
