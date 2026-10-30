param([Parameter(Mandatory=$true)][string]$CommonPath,
    [ValidateSet('All','ReadyResume','Refusal','UnknownAndChanged')][string]$Case='All')
$ErrorActionPreference='Stop'
. $CommonPath
$checks=New-Object 'System.Collections.Generic.List[string]'
function Check([bool]$Value,[string]$Name){if(-not $Value){throw ('ASSERTION_FAILED:'+ $Name)};$checks.Add($Name)}
function Reject([scriptblock]$Action,[string]$Code){
    $caught=$null;try{& $Action|Out-Null}catch{$caught=$_.Exception.Message}
    Check ($caught -ceq $Code) $Code
}
function Reset-Protocol {
    $script:plan=[pscustomobject]@{SessionId='joint-skill-unit';PlatformTenant='local-v12';PlatformBaseUrl='http://127.0.0.1:18080';OntologyBaseUrl='http://127.0.0.1:18182';ModelMode='TestFixture';ModelEntryId=$null}
    $script:runtime=[pscustomobject][ordered]@{sessionId='joint-skill-unit';platformTenant='local-v12';platformBaseUrl='http://127.0.0.1:18080';databaseName='agent_platform_joint_skill_unit';redisHost='127.0.0.1';redisPort=29379;isolationConfirmed=$true;modelDemoMode=$true;engineDemoMode=$true;fixtureBaseUrl='http://127.0.0.1:29380'}
    $script:releaseId='11111111-1111-4111-8111-111111111111';$script:spaceId='22222222-2222-4222-8222-222222222222'
    $script:consumerRef='env:AGENT_SECRET_JOINT_AABBCCDDEEFF_CONSUMER'
    $script:readRef='env:AGENT_SECRET_JOINT_AABBCCDDEEFF_READ'
    $script:readKey='ontosa_'+('x'*43)
    $script:resources=[pscustomobject]@{spaceId=$script:spaceId;spaceCode='joint_unit_a';releaseId=$script:releaseId;opCode='joint_unit_op';readCredentialId='read-unit';consumerCredentialId='consumer-unit';readKeyRef=$script:readRef;consumerKeyRef=$script:consumerRef}
    $actions=[pscustomobject]@{A_first_commit=[pscustomobject]@{id=$script:releaseId;spaceId=$script:spaceId;status='READY';bundleSha256=('a'*64)};key_READ=[pscustomobject]@{id='read-unit';spaceId=$script:spaceId;credentialKind='SERVICE_ACCOUNT';keyRef=$script:readRef};key_CONSUMER=[pscustomobject]@{id='consumer-unit';spaceId=$script:spaceId;credentialKind='PLATFORM_ACTOR';keyRef=$script:consumerRef}}
    $script:state=[pscustomobject]@{resources=$script:resources;actions=$actions;pending=$null;complete=$false;stage='PLATFORM_MODEL_READY';platformModel=[pscustomobject]@{modelEntryId=72;modelMode='TestFixture';healthStatus='HEALTHY'};platformRuntime=$script:runtime}
    $script:manifest=[pscustomobject][ordered]@{tool_code='joint_unit_op';category='ontology_service';adapter_type='HTTP';invocation_kind='SYNC';http_method='POST';endpoint=('http://127.0.0.1:18182/onto/r/joint_unit_a/releases/'+$script:releaseId+'/ops/joint_unit_op');exec_attrs=[pscustomobject]@{readonly_flag=$true;side_effect=$false};ontology_meta=[pscustomobject]@{spaceCode='joint_unit_a';releaseId=$script:releaseId;version='v1';contentDigest=('a'*64)};name='Frozen model query';description='Protocol fixture only';input_schema=[pscustomobject]@{type='object'};output_schema=[pscustomobject]@{type='object'}}
    $script:manifestBefore=ConvertTo-Json -InputObject $script:manifest -Depth 20 -Compress
    $script:requests=New-Object 'System.Collections.Generic.List[object]'
    $script:saved=New-Object 'System.Collections.Generic.List[string]'
    $script:fault=$null;$script:importVersion=$null
}
function Save-Protocol {
    Assert-JointSafeMetadata $script:state
    $script:saved.Add((ConvertTo-Json -InputObject $script:state -Depth 20 -Compress))
}
# HTTP传输协议替身；没有PG/OR/API服务和真实凭据，不能作为双仓验收。
function Invoke-JointHttp {
    param($BaseUrl,$Path,$Method='GET',$Body,$BearerToken,$ApiKey,$TimeoutSeconds)
    $script:requests.Add([pscustomobject]@{method=$Method;path=$Path})
    Check ($TimeoutSeconds -ge 1 -and $TimeoutSeconds -le 30) 'bounded request timeout'
    $data=$null
    if($Path -ceq ('/onto/releases/'+$script:releaseId+'/manifest') -and $Method -ceq 'GET'){
        Check ($BaseUrl -ceq $script:plan.OntologyBaseUrl -and $ApiKey -ceq $script:readKey -and -not $BearerToken) 'OR read uses only existing readonly key'
        $data=[pscustomobject]@{manifests=@($script:manifest)}
    }else{
        Check ($BaseUrl -ceq $script:plan.PlatformBaseUrl -and $BearerToken -ceq 'unit-verified-context' -and -not $ApiKey) 'AP uses verified bearer and never OR key'
        if($Path -ceq '/admin/skills' -and $Method -ceq 'GET'){
            $data=@();if($script:fault -ceq 'collision'){$data=@([pscustomobject]@{code='joint_unit_op'})}
        }elseif($Path -ceq '/admin/skills/import-ontology-manifests' -and $Method -ceq 'POST'){
            Check ($script:state.pending.action -ceq 'platform_skill_import') 'pending exists before import'
            Check (($script:saved[$script:saved.Count-1]|ConvertFrom-Json).pending.action -ceq 'platform_skill_import') 'pending is saved before import'
            Check ($Body.authScheme -ceq 'API_KEY' -and $Body.authRef -ceq $script:consumerRef) 'import passes consumer secret_ref only'
            Check (@($Body.manifests).Count -eq 1 -and (ConvertTo-Json -InputObject $Body.manifests[0] -Depth 20 -Compress) -ceq $script:manifestBefore) 'formal manifest is not rewritten'
            if($script:fault -ceq 'lost-import'){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
            $source=[pscustomobject]@{kind='ONTOLOGY';toolCode='joint_unit_op';spaceCode='joint_unit_a';releaseId=$script:releaseId;version='v1';contentDigest=('a'*64)}
            $definition=[pscustomobject]@{type='HTTP';invocationKind='SYNC';httpMethod='POST';endpoint=$script:manifest.endpoint;authScheme='API_KEY';authRef=$script:consumerRef;importSource=$source}
            $script:importVersion=[pscustomobject]@{id=82;skillVersionId=82;code='joint_unit_op';versionNo=1;status='DRAFT';sha256=('b'*64);semanticDigest=('c'*64);digestVersion=1;definition=$definition}
            $data=[pscustomobject]@{drafts=@([pscustomobject]@{id=81;code='joint_unit_op';status='DRAFT';currentVersion=$null;versions=@($script:importVersion)})}
            if($script:fault -ceq 'bad-reply'){$data.drafts=@()}
        }elseif($Path -ceq '/admin/skills/joint_unit_op/versions' -and $Method -ceq 'GET'){
            $version=$script:importVersion|ConvertTo-Json -Depth 20|ConvertFrom-Json
            $version.definition.PSObject.Properties.Remove('authRef')
            $version.definition|Add-Member -NotePropertyName authConfigured -NotePropertyValue $true
            if($script:fault -ceq 'changed'){$version.sha256='d'*64}
            $data=@($version)
        }else{throw 'PROTOCOL_ROUTE_UNEXPECTED'}
    }
    return [pscustomobject]@{Data=$data}
}
function Run-Draft { Invoke-JointPlatformSkillDraft -Plan $script:plan -State $script:state -Runtime $script:runtime -BearerToken 'unit-verified-context' -ReadKey $script:readKey -Save {Save-Protocol} }
$results=New-Object 'System.Collections.Generic.List[object]';$exitCode=0
foreach($test in @('ReadyResume','Refusal','UnknownAndChanged')){
    if($Case -ne 'All' -and $Case -ne $test){continue}
    try{
        Reset-Protocol
        switch($test){
            'ReadyResume' {
                $ready=Run-Draft
                Check ($ready.skillVersionId -eq 82 -and $ready.status -ceq 'DRAFT' -and $script:state.complete -eq $false) 'actual imported draft remains unpublished partial'
                Check ($script:state.stage -ceq 'PLATFORM_SKILL_DRAFT_READY' -and $null -eq $script:state.pending) 'ready only after matching readback'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 1) 'one import only'
                $before=$script:requests.Count;Run-Draft|Out-Null
                Check (@($script:requests|Select-Object -Skip $before|Where-Object method -eq 'POST').Count -eq 0) 'resume reads confirmed draft without import'
                Check (-not (($script:saved -join "`n").Contains($script:readKey)) -and -not (($script:saved -join "`n").Contains('unit-verified-context'))) 'journal contains no key or bearer'
            }
            'Refusal' {
                $script:state.platformModel.healthStatus='UNHEALTHY'
                Reject {Run-Draft} 'JOINT_PLATFORM_MODEL_STAGE_REQUIRED'
                Check ($script:requests.Count -eq 0) 'model prerequisite refuses before HTTP'
                Reset-Protocol;$script:manifest.ontology_meta.contentDigest='d'*64
                Reject {Run-Draft} 'JOINT_FORMAL_MANIFEST_IDENTITY_MISMATCH'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 0) 'wrong release digest never imports'
                Reset-Protocol;$script:fault='collision'
                Reject {Run-Draft} 'JOINT_SKILL_CODE_ALREADY_EXISTS'
                Check (@($script:requests|Where-Object method -eq 'POST').Count -eq 0) 'preexisting code is never overwritten'
            }
            'UnknownAndChanged' {
                $script:fault='lost-import'
                Reject {Run-Draft} 'JOINT_HTTP_TRANSPORT_FAILED'
                Check ($script:state.pending.action -ceq 'platform_skill_import') 'unknown import preserves pending'
                $before=$script:requests.Count
                Reject {Run-Draft} 'JOINT_PENDING_ACTION_NEEDS_RECONCILIATION'
                Check ($script:requests.Count -eq $before) 'unknown import is not retried'
                Reset-Protocol;$script:fault='bad-reply'
                Reject {Run-Draft} 'JOINT_SKILL_IMPORT_REPLY_INVALID'
                Check ($script:state.pending.action -ceq 'platform_skill_import') 'invalid successful response remains unknown'
                Reset-Protocol;$script:fault='changed'
                Reject {Run-Draft} 'JOINT_SKILL_DRAFT_CHANGED'
                Check ($script:state.stage -cne 'PLATFORM_SKILL_DRAFT_READY' -and $null -eq $script:state.pending) 'readback drift does not become ready or recreate'
            }
        }
        $results.Add([pscustomobject]@{name=$test;passed=$true})
    }catch{$exitCode=1;$results.Add([pscustomobject]@{name=$test;passed=$false;error=$_.Exception.Message})}
}
[pscustomobject]@{scope='script orchestration with HTTP protocol substitutes; no AP/OR/PG';cases=$results;assertionsPassed=$checks.Count;passed=($exitCode -eq 0)}|ConvertTo-Json -Depth 5
exit $exitCode
