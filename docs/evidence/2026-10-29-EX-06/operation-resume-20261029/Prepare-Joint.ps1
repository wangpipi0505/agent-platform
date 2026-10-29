param(
    [Parameter(Mandatory=$true)][string]$SessionId,[string]$OntologyBaseUrl='http://127.0.0.1:18182',
    [string]$PlatformBaseUrl='http://127.0.0.1:18080',[string]$PlatformTenant='local-v12',[string]$Environment='production',
    [string]$OutputDirectory,[string]$ReuseManifestPath,[switch]$Resume,
    [ValidateSet('TestFixture','Real')][string]$ModelMode='TestFixture',[Alias('RealModelEntryId')][string]$ModelEntryId,
    [ValidateSet('All','Ontology')][string]$Stage='All',[pscredential]$OntologyCredential
)
$ErrorActionPreference='Stop'
$repositoryRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'Joint.Common.ps1')
$script:jointState=$null;$script:jointToken=$null;$plan=$null;$private=$null;$exitCode=1
$script:jointClock=[Diagnostics.Stopwatch]::StartNew()
function Save-JointStage {
    Write-JointPrivateJson $repositoryRoot $private 'partial.json' $script:jointState
}
function Invoke-JointOr([string]$Path,[string]$Method='GET',[AllowNull()][object]$Body) {
    $remaining=900-[int]$script:jointClock.Elapsed.TotalSeconds
    if($remaining -le 0){throw 'JOINT_PREPARATION_BUDGET_EXCEEDED'}
    $call=@{BaseUrl=$plan.OntologyBaseUrl;Path=('/onto'+$Path);Method=$Method;BearerToken=$script:jointToken;TimeoutSeconds=[Math]::Min(30,$remaining)}
    if($PSBoundParameters.ContainsKey('Body')){$call.Body=$Body}
    return (Invoke-JointHttp @call).Data
}
function Invoke-JointOrStep([string]$Name,[string]$Path,[string]$Method='POST',[AllowNull()][object]$Body,[string[]]$Fields=@('id'),[string]$SecretName) {
    $old=Get-JointValue $script:jointState.actions $Name
    if($old){return $old}
    if($plan.Mode -eq 'Reuse'){throw 'JOINT_REUSE_RESOURCE_STATE_INCOMPLETE'}
    Set-JointValue $script:jointState 'stage' $Name
    # 写操作只尝试一次；结果未知时留pending，Resume必须先核实，不能重签或盲重试。
    Set-JointValue $script:jointState 'pending' ([pscustomobject]@{action=$Name;method=$Method;path=$Path;phase='REQUEST';startedAt=[DateTime]::UtcNow.ToString('o')});Save-JointStage
    $call=@{Path=$Path;Method=$Method};if($PSBoundParameters.ContainsKey('Body')){$call.Body=$Body}
    $data=Invoke-JointOr @call
    $record=[ordered]@{}
    if($SecretName){
        $credential=$data.credential
        foreach($field in @('id','principalId','spaceId','credentialKind','platformActor')){
            if(-not(Get-JointValue $credential $field)){throw 'JOINT_CREDENTIAL_REPLY_INVALID'}
            $record[$field]=Get-JointValue $credential $field
        }
        $prefix=if($credential.credentialKind -eq 'SERVICE_ACCOUNT'){'ontosa_'}else{'ontopa_'}
        if($data.key -isnot [string] -or $data.key -cnotmatch ('^'+$prefix+'[A-Za-z0-9_-]{43}$')){throw 'JOINT_CREDENTIAL_REPLY_INVALID'}
        $variable='AGENT_SECRET_JOINT_'+$script:jointState.nonce.Substring(0,12).ToUpperInvariant()+'_'+$SecretName
        $record.keyRef='env:'+$variable
        Set-JointValue $script:jointState.actions $Name ([pscustomobject]$record)
        Set-JointValue $script:jointState 'pending' ([pscustomobject]@{action=$Name;phase='SECRET_SAVE';credentialId=$record.id});Save-JointStage
        $values=Read-JointPrivateEnvironment $repositoryRoot $private
        if($values.ContainsKey($variable)){throw 'JOINT_SECRET_REF_COLLISION'}
        $values[$variable]=$data.key
        $lines=@($values.Keys|Sort-Object|ForEach-Object{$_+'='+$values[$_]})
        Write-JointPrivateText $repositoryRoot $private 'credentials.env' (($lines-join [Environment]::NewLine)+[Environment]::NewLine)|Out-Null
        $data=$null;$values=$null
    }else{
        foreach($field in $Fields){
            $value=Get-JointValue $data $field
            if($null -eq $value){throw 'JOINT_RESOURCE_REPLY_INVALID'}
            if($field -eq 'artifacts'){$value=@($value|ForEach-Object{[pscustomobject]@{kind=$_.kind;sha256=$_.sha256;size=$_.size;ready=$_.ready}})}
            $record[$field]=$value
        }
        Set-JointValue $script:jointState.actions $Name ([pscustomobject]$record)
    }
    Set-JointValue $script:jointState 'pending' $null;Save-JointStage
    return [pscustomobject]$record
}
function Invoke-JointSpaceWrite([string]$Name,[object]$Space,[string]$Path,[hashtable]$Body,[string]$Method='POST') {
    if(Get-JointValue $script:jointState.actions $Name){return}
    $current=Invoke-JointOr ('/spaces/'+$Space.id)
    $Body.spaceId=$Space.id;$Body.expectedDraftRev=$current.draftRev
    Invoke-JointOrStep -Name $Name -Path $Path -Method $Method -Body $Body -Fields @('id','draftRev')|Out-Null
}
function Publish-JointRelease([string]$Name,[object]$Space) {
    $current=Invoke-JointOr ('/spaces/'+$Space.id)
    $candidate=Invoke-JointOrStep -Name ($Name+'_candidate') -Path ('/spaces/'+$Space.id+'/candidates') -Body @{expectedDraftRev=$current.draftRev} -Fields @('id','draftRev','sha256','preReleaseId')
    Invoke-JointOrStep -Name ($Name+'_validate') -Path ('/candidates/'+$candidate.id+'/validate') -Fields @('status')|Out-Null
    $tests=Invoke-JointOrStep -Name ($Name+'_tests') -Path ('/candidates/'+$candidate.id+'/tests') -Fields @('passed','candidateSha256')
    if(-not $tests.passed -or $tests.candidateSha256 -cne $candidate.sha256){throw 'JOINT_PUBLICATION_TESTS_FAILED'}
    $bound=@{expectedDraftRev=$candidate.draftRev;candidateSha256=$candidate.sha256;reason=('联合验收会话 '+$SessionId+' 冻结候选逐项复核')}
    Invoke-JointOrStep -Name ($Name+'_review') -Path ('/candidates/'+$candidate.id+'/review') -Body $bound -Fields @('status')|Out-Null
    $prepared=Invoke-JointOrStep -Name ($Name+'_prepare') -Path ('/candidates/'+$candidate.id+'/prepare') -Body $bound -Fields @('status','artifacts')
    Assert-JointPublicationPrepared $prepared
    $release=Invoke-JointOrStep -Name ($Name+'_commit') -Path ('/candidates/'+$candidate.id+'/commit') -Body @{expectedDraftRev=$candidate.draftRev;expectedSha256=$candidate.sha256} -Fields @('id','spaceId','status','bundleSha256')
    if($release.id -cne $candidate.preReleaseId -or $release.spaceId -cne $Space.id -or $release.status -cne 'READY' -or $release.bundleSha256 -cne $candidate.sha256){throw 'JOINT_RELEASE_IDENTITY_INVALID'}
    return $release
}
function Get-JointOwnedSpace([string]$Label,[object]$Space) {
    $code='joint-'+$script:jointState.nonce.Substring(0,16)+'-'+$Label.ToLowerInvariant()
    $observed=Invoke-JointOr ('/spaces/'+$Space.id)
    if($Space.code -cne $code -or $observed.code -cne $code -or $observed.namespaceUri -cne ('https://example.org/joint/'+$script:jointState.nonce+'/'+$Label+'/') -or $observed.role -cne 'OWNER' -or $observed.status -cne 'ACTIVE'){throw 'JOINT_SPACE_OWNERSHIP_MISMATCH'}
    return $observed
}
function Get-JointOperation([string]$Label,[object]$Space,[string]$OpCode) {
    $operations=@(Invoke-JointOr ('/operations?spaceId='+$Space.id)|Where-Object{$_.code -ceq $OpCode})
    $operationId=[Guid]::Empty
    if($operations.Count -ne 1 -or -not [Guid]::TryParse([string]$operations[0].id,[ref]$operationId) -or $operationId -eq [Guid]::Empty){throw 'JOINT_OPERATION_IDENTITY_INVALID'}
    $recorded=Get-JointValue (Get-JointValue $script:jointState 'operationIds') $Label
    if($recorded -and $recorded -cne $operations[0].id){throw 'JOINT_OPERATION_IDENTITY_CONFLICT'}
    return $operations[0]
}
function Initialize-JointOperationIdentity {
    $codes=@();$resources=Get-JointValue $script:jointState 'resources'
    $recorded=Get-JointValue $script:jointState 'opCode'
    if($null -ne $recorded){$codes+=,$recorded}
    if($resources){
        $codes+=,(Get-JointValue $resources 'opCode')
        $codes+=,(Get-JointValue (Get-JointValue $resources 'isolationFixtures') 'opCode')
    }
    if($plan.Mode -eq 'Reuse'){$codes+=,$plan.OpCode}
    foreach($code in $codes){if($code -isnot [string] -or $code -cnotmatch '^[A-Za-z][A-Za-z0-9_-]{0,63}$'){throw 'JOINT_OPERATION_IDENTITY_INVALID'}}
    if($codes.Count -and @($codes|Where-Object{$_ -cne $codes[0]}).Count){throw 'JOINT_OPERATION_IDENTITY_CONFLICT'}
    $confirmed=@('A','B'|Where-Object{Get-JointValue $script:jointState.actions ($_+'_operation')})
    if('B' -cin $confirmed -and 'A' -cnotin $confirmed){throw 'JOINT_OPERATION_IDENTITY_INVALID'}
    # Old journals did not store opCode/operation id. Only their confirmed operation actions
    # plus the actual owned-space directory can attest the legacy joint_list identity.
    if($codes.Count){$code=$codes[0]}elseif($confirmed.Count){$code='joint_list'}else{throw 'JOINT_OPERATION_IDENTITY_UNCONFIRMED'}
    if(-not $confirmed.Count -and $code -cne $plan.OpCode){throw 'JOINT_OPERATION_IDENTITY_CONFLICT'}
    $ids=Get-JointValue $script:jointState 'operationIds'
    if($null -eq $ids){$ids=[pscustomobject]@{}}
    if($ids -isnot [pscustomobject] -and $ids -isnot [Collections.IDictionary]){throw 'JOINT_OPERATION_IDENTITY_INVALID'}
    foreach($label in @(Get-JointNames $ids)){
        $id=[Guid]::Empty
        if($label -cnotin @('A','B') -or $label -cnotin $confirmed -or -not [Guid]::TryParse([string](Get-JointValue $ids $label),[ref]$id) -or $id -eq [Guid]::Empty){throw 'JOINT_OPERATION_IDENTITY_INVALID'}
    }
    foreach($label in $confirmed){
        $space=Get-JointValue $script:jointState.actions ($label+'_space')
        $operationAction=Get-JointValue $script:jointState.actions ($label+'_operation')
        if(-not $space -or $operationAction.id -cne $space.id){throw 'JOINT_OPERATION_IDENTITY_INVALID'}
        Get-JointOwnedSpace $label $space|Out-Null
        $operation=Get-JointOperation $label $space $code
        Set-JointValue $ids $label $operation.id
    }
    Set-JointValue $script:jointState 'opCode' $code
    Set-JointValue $script:jointState 'operationIds' $ids
    Set-JointValue $plan 'OpCode' $code
    Save-JointStage
}
function New-JointOntology([string]$Label) {
    $code='joint-'+$script:jointState.nonce.Substring(0,16)+'-'+$Label.ToLowerInvariant()
    $space=Invoke-JointOrStep -Name ($Label+'_space') -Path '/spaces' -Body @{code=$code;name=('联合验收 '+$SessionId+' '+$Label);namespaceUri=('https://example.org/joint/'+$script:jointState.nonce+'/'+$Label+'/');domain='本地联合验收';description=('session='+$SessionId)} -Fields @('id','code','draftRev','namespaceUri')
    Get-JointOwnedSpace $Label $space|Out-Null
    Invoke-JointSpaceWrite ($Label+'_class') $space '/classes' @{name='JointTask';description='会话冻结任务';parentId=$null;deprecated=$false}
    $query='PREFIX owl:<http://www.w3.org/2002/07/owl#> PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> SELECT ?name WHERE { ?s a owl:Class; rdfs:label ?name FILTER(STR(?name) = "JointTask") } ORDER BY ?name'
    Invoke-JointSpaceWrite ($Label+'_operation') $space '/operations' @{code=$plan.OpCode;name='联合验收查询';queryTemplate=$query;maxRows=20;timeoutMs=5000}
    $operation=Get-JointOperation $Label $space $plan.OpCode
    Set-JointValue $script:jointState.operationIds $Label $operation.id;Save-JointStage
    Invoke-JointSpaceWrite ($Label+'_guards') $space ('/spaces/'+$space.id+'/guards') @{guardCfg=@{region=@{type='STRING';authorization_rule=@{kind='PRINCIPAL_ATTRIBUTE';attribute='region'}}}} 'PUT'
    Invoke-JointSpaceWrite ($Label+'_owner_scope') $space ('/spaces/'+$space.id+'/grants/'+$script:jointState.ownerId) @{role='OWNER';dataScope=@{attr='region';in=@('LOCAL')};attributes=@{region='LOCAL'}} 'PUT'
    $assistant=@{inputSchema=@{type='object';properties=@{};additionalProperties=$false};outputSchema=@{type='object';required=@('columns','rows');properties=@{columns=@{type='array';items=@{type='string'}};rows=@{type='array';items=@{type='object'}}}};assistantTool=@{name='联合验收查询';description='查询冻结的会话任务';guard_attrs=@('region');info_attrs=@();param_examples=@(@{});exec=@{readonly=$true;side_effect=$false;idempotent=$true;cancellable=$true};risk_level='READ_ONLY';error_semantics=@(@{match='HTTP 403';semantic='NO_PERMISSION'});return_spec=@{modelSummary=@{mode='TEMPLATE';template='联合验收结果'};structuredPath='$.data';fileRefPaths=@();cardTemplate='联合验收结果'}}}
    Invoke-JointSpaceWrite ($Label+'_assistant') $space ('/operations/'+$operation.id+'/assistant') $assistant 'PUT'
    Invoke-JointSpaceWrite ($Label+'_test_case') $space '/test-cases' @{name='联合验收冻结精确结果';question='直接查询';operationId=$operation.id;input=@{};expected=@{status='SUCCEEDED';columns=@('name');rows=@(@{name='JointTask'})};enabled=$true;mode='DIRECT';matchMode='EXACT'}
    $first=Publish-JointRelease ($Label+'_first') $space
    $second=$null
    if($Label -eq 'A'){
        Invoke-JointSpaceWrite 'A_revision_class' $space '/classes' @{name='JointRevision';description='替换版本新增但不改变查询结果';parentId=$null;deprecated=$false}
        $second=Publish-JointRelease 'A_replacement' $space
        if($second.id -eq $first.id){throw 'JOINT_REPLACEMENT_RELEASE_NOT_DISTINCT'}
    }
    return [pscustomobject]@{space=$space;first=$first;replacement=$second;opCode=$plan.OpCode}
}
function Get-JointEndpoint([object]$Release) {
    $detail=Invoke-JointOr ('/releases/'+$Release.id)
    if($detail.release.status -cne 'READY' -or $detail.release.spaceId -cne $Release.spaceId -or $detail.release.bundleSha256 -cne $Release.bundleSha256){throw 'JOINT_RELEASE_NOT_READY'}
    Assert-JointArtifactsReady @($detail.artifacts)
    $endpoints=@(Invoke-JointOr ('/releases/'+$Release.id+'/endpoints')|Where-Object{$_.opCode -ceq $plan.OpCode -and $_.status -eq 'ACTIVE'})
    if($endpoints.Count -ne 1){throw 'JOINT_ENDPOINT_NOT_ACTIVE'}
    return $endpoints[0]
}
function New-JointConsumer([string]$Name,[object]$Space,[string]$MemberRoleId) {
    $passwordBytes=New-Object byte[] 32;$random=[Security.Cryptography.RandomNumberGenerator]::Create();try{$random.GetBytes($passwordBytes)}finally{$random.Dispose()}
    $user=Invoke-JointOrStep -Name ($Name+'_user') -Path '/system/users' -Body @{username=('joint-'+$script:jointState.nonce.Substring(0,16)+'-'+$Name.ToLowerInvariant()+'@acceptance.local');displayName=('联合验收 '+$Name);password=[Convert]::ToBase64String($passwordBytes);roleIds=@($MemberRoleId)} -Fields @('id','status')
    $passwordBytes=$null
    if($user.status -ne 'ACTIVE'){throw 'JOINT_CONSUMER_NOT_ACTIVE'}
    Invoke-JointSpaceWrite ($Name+'_space_grant') $Space ('/spaces/'+$Space.id+'/grants/'+$user.id) @{role='VIEWER';dataScope=@{attr='region';in=@('LOCAL')};attributes=@{region='LOCAL'}} 'PUT'
    return $user
}
function New-JointMachineKey([string]$Name,[string]$Kind,[object]$Space,[string]$PrincipalId) {
    $body=@{spaceId=$Space.id;kind=$Kind;platformTenant=$PlatformTenant;platformActor=('joint-'+$script:jointState.nonce.Substring(0,16)+'-'+$Name.ToLowerInvariant());name=('联合验收 '+$Name);deptScope=@{attr='region';in=@('LOCAL')};attributes=@{region='LOCAL'};expiresAt=[DateTime]::UtcNow.AddHours(12).ToString('o')}
    if($Kind -eq 'SERVICE_ACCOUNT'){$body.environment=$Environment}else{$body.principalId=$PrincipalId}
    return Invoke-JointOrStep -Name ('key_'+$Name) -Path '/platform-actors' -Body $body -SecretName $Name
}
function Add-JointBinderRole([string]$Name,[object]$Credential,[string]$BinderRoleId) {
    if(Get-JointValue $script:jointState.actions ($Name+'_binder_role')){return}
    $users=@(Invoke-JointOr '/system/users'|Where-Object id -eq $Credential.principalId)
    if($users.Count -ne 1 -or $users[0].status -ne 'ACTIVE'){throw 'JOINT_BINDER_PRINCIPAL_INVALID'}
    $user=$users[0];$ids=@($user.roles|ForEach-Object{$_.id})
    if($BinderRoleId -notin $ids){$ids+= $BinderRoleId}
    Invoke-JointOrStep -Name ($Name+'_binder_role') -Path ('/system/users/'+$user.id) -Method PUT -Body @{displayName=$user.displayName;status=$user.status;roleIds=$ids;expectedRevision=$user.revision} -Fields @('id','revision')|Out-Null
}
function Get-JointSavedKey([object]$Credential) {
    $values=Read-JointPrivateEnvironment $repositoryRoot $private
    $variable=$Credential.keyRef.Substring(4)
    if(-not $values.ContainsKey($variable)){throw 'JOINT_PRIVATE_REFS_UNAVAILABLE'}
    return $values[$variable]
}
try{
    if($Stage -eq 'All'){throw 'JOINT_ALL_STAGE_NOT_DELIVERED'}
    if($Resume -and $ReuseManifestPath){throw 'JOINT_RESUME_AND_FINAL_REUSE_CONFLICT'}
    if(-not $OntologyCredential){throw 'JOINT_OR_CREDENTIAL_REQUIRED'}
    $plan=Get-JointPreparationPlan -RepositoryRoot $repositoryRoot -SessionId $SessionId -PlatformTenant $PlatformTenant -Environment $Environment -OutputDirectory $OutputDirectory -PlatformBaseUrl $PlatformBaseUrl -OntologyBaseUrl $OntologyBaseUrl -ReuseManifestPath $ReuseManifestPath -ModelMode $ModelMode -ModelEntryId $ModelEntryId
    $existed=Test-Path -LiteralPath $plan.PrivateDirectory
    $private=New-JointPrivateDirectory $repositoryRoot $plan.PrivateDirectory
    $journal=Join-Path $private 'partial.json'
    if($Resume -or $ReuseManifestPath){
        if(-not(Test-Path -LiteralPath $journal -PathType Leaf)){throw 'JOINT_PARTIAL_NOT_FOUND'}
        Assert-JointPrivateFile $journal
        $script:jointState=[IO.File]::ReadAllText($journal)|ConvertFrom-Json -ErrorAction Stop
        Assert-JointSafeMetadata $script:jointState
        foreach($field in @('SessionId','PlatformTenant','Environment','PlatformBaseUrl','OntologyBaseUrl','ModelMode','ModelEntryId')){if((Get-JointValue $script:jointState.context $field) -cne (Get-JointValue $plan $field)){throw 'JOINT_PARTIAL_CONTEXT_MISMATCH'}}
        if($script:jointState.nonce -cnotmatch '^[a-f0-9]{32}$'){throw 'JOINT_PARTIAL_CONTEXT_MISMATCH'}
        if($script:jointState.pending){throw 'JOINT_PENDING_ACTION_NEEDS_RECONCILIATION'}
        if($plan.Mode -eq 'Reuse' -and ($script:jointState.stage -cne 'ONTOLOGY_READY' -or -not $script:jointState.resources)){throw 'JOINT_REUSE_RESOURCE_STATE_INCOMPLETE'}
    }else{
        if($existed -and @(Get-ChildItem -LiteralPath $private -Force).Count){throw 'JOINT_PRIVATE_SESSION_EXISTS'}
        $script:jointState=[pscustomobject]@{sessionId=$SessionId;nonce=[Guid]::NewGuid().ToString('N');context=[pscustomobject]@{SessionId=$SessionId;PlatformTenant=$PlatformTenant;Environment=$Environment;PlatformBaseUrl=$plan.PlatformBaseUrl;OntologyBaseUrl=$plan.OntologyBaseUrl;ModelMode=$ModelMode;ModelEntryId=$ModelEntryId};ownerId=$null;opCode=$plan.OpCode;operationIds=[pscustomobject]@{};actions=[pscustomobject]@{};resources=$null;pending=$null;stage='PREFLIGHT';complete=$false;lastError=$null}
        Save-JointStage
        Write-JointPrivateText $repositoryRoot $private 'credentials.env' ''|Out-Null
    }
    # 首个OR业务写入前从真实gateway读固定已验开发身份，不能按请求体自报租户。
    $platformIdentity=(Invoke-JointHttp -BaseUrl $plan.PlatformBaseUrl -Path '/auth/dev-token' -Method POST).Data
    if($platformIdentity.user.tenantId -cne $PlatformTenant -or $platformIdentity.user.principalId -cne 'developer-v12' -or -not $platformIdentity.accessToken){throw 'JOINT_PLATFORM_IDENTITY_MISMATCH'}
    $platformIdentity=$null
    $login=Invoke-JointHttp -BaseUrl $plan.OntologyBaseUrl -Path '/onto/auth/login' -Method POST -Body @{username=$OntologyCredential.UserName;password=$OntologyCredential.GetNetworkCredential().Password}
    $script:jointToken=$login.Data.token;$login=$null
    if(-not $script:jointToken){throw 'JOINT_OR_LOGIN_INVALID'}
    $owner=Invoke-JointOr '/auth/me'
    if($script:jointState.ownerId -and $script:jointState.ownerId -cne $owner.id){throw 'JOINT_OR_OWNER_MISMATCH'}
    Set-JointValue $script:jointState 'ownerId' $owner.id;Save-JointStage
    Initialize-JointOperationIdentity
    $roles=@(Invoke-JointOr '/system/roles')
    $member=@($roles|Where-Object{$_.code -eq 'MEMBER' -and $_.status -eq 'ACTIVE'})
    $binderRole=@($roles|Where-Object{$_.code -eq 'SERVICE_ACCOUNT_BINDER' -and $_.status -eq 'ACTIVE'})
    if($member.Count -ne 1 -or $binderRole.Count -ne 1){throw 'JOINT_REQUIRED_ROLE_MISSING'}
    $a=New-JointOntology 'A';$b=New-JointOntology 'B'
    $aFirst=Get-JointEndpoint $a.first;$aSecond=Get-JointEndpoint $a.replacement;$bFirst=Get-JointEndpoint $b.first
    $mainConsumer=New-JointConsumer 'CONSUMER' $a.space $member[0].id
    $tempConsumer=New-JointConsumer 'TEMP_CONSUMER' $a.space $member[0].id
    foreach($principal in @($mainConsumer,$tempConsumer)){foreach($endpoint in @($aFirst,$aSecond)){
        Invoke-JointOrStep -Name ('endpoint_'+$endpoint.id+'_'+$principal.id) -Path ('/endpoints/'+$endpoint.id+'/grants/'+$principal.id) -Method PUT -Body @{dataScope=@{attr='region';in=@('LOCAL')};expiresAt=$null;status='ACTIVE'} -Fields @('userId','status')|Out-Null
    }}
    $keys=@{}
    foreach($name in @('READ','BINDER','TEMP_READ','TEMP_BINDER')){$keys[$name]=New-JointMachineKey $name 'SERVICE_ACCOUNT' $a.space $null}
    $keys.CONSUMER=New-JointMachineKey 'CONSUMER' 'PLATFORM_ACTOR' $a.space $mainConsumer.id
    $keys.TEMP_CONSUMER=New-JointMachineKey 'TEMP_CONSUMER' 'PLATFORM_ACTOR' $a.space $tempConsumer.id
    Add-JointBinderRole 'MAIN' $keys.BINDER $binderRole[0].id;Add-JointBinderRole 'TEMP' $keys.TEMP_BINDER $binderRole[0].id
    if(@($keys.Values|ForEach-Object{$_.principalId}|Select-Object -Unique).Count -ne 6){throw 'JOINT_CREDENTIAL_PRINCIPALS_NOT_DISTINCT'}
    $users=@(Invoke-JointOr '/system/users')
    $roles=@(Invoke-JointOr '/system/roles')
    foreach($name in @('READ','BINDER','TEMP_READ','TEMP_BINDER')){
        $user=@($users|Where-Object id -eq $keys[$name].principalId)
        if($user.Count -ne 1 -or $user[0].status -cne 'ACTIVE'){throw 'JOINT_SERVICE_PRINCIPAL_NOT_ACTIVE'}
        $permissions=@();foreach($roleRef in $user[0].roles){$permissions+=@($roles|Where-Object{$_.id -eq $roleRef.id -and $_.status -eq 'ACTIVE'}|ForEach-Object{$_.permissions})}
        if('release.read' -notin $permissions -or ($name -in @('READ','TEMP_READ') -and 'release.bind' -in $permissions) -or ($name -in @('BINDER','TEMP_BINDER') -and 'release.bind' -notin $permissions)){throw 'JOINT_SERVICE_PERMISSION_MISMATCH'}
    }
    $directory=@(Invoke-JointOr ('/platform-actors?spaceId='+$a.space.id))
    foreach($name in $keys.Keys){
        $credential=$keys[$name];$actual=@($directory|Where-Object id -eq $credential.id)
        if($actual.Count -ne 1 -or $actual[0].status -ne 'ACTIVE' -or $actual[0].principalId -cne $credential.principalId -or $actual[0].platformTenant -cne $PlatformTenant -or $actual[0].credentialKind -cne $credential.credentialKind -or ($credential.credentialKind -eq 'SERVICE_ACCOUNT' -and $actual[0].environment -cne $Environment)){throw 'JOINT_CREDENTIAL_NOT_ACTIVE'}
        $key=Get-JointSavedKey $credential
        if($credential.credentialKind -eq 'SERVICE_ACCOUNT'){
            Invoke-JointHttp -BaseUrl $plan.OntologyBaseUrl -Path ('/onto/releases/'+$a.first.id+'/manifest') -ApiKey $key|Out-Null
        }else{foreach($release in @($a.first,$a.replacement)){Invoke-JointHttp -BaseUrl $plan.OntologyBaseUrl -Path ('/onto/r/'+$a.space.code+'/releases/'+$release.id+'/ops/'+$plan.OpCode) -Method POST -Body @{} -ApiKey $key|Out-Null}}
        $key=$null
    }
    foreach($pair in @(@{File='app.env';Role='BINDER'},@{File='engine.env';Role='READ'},@{File='skill.env';Role='CONSUMER'})){
        $credential=$keys[$pair.Role];$variable=$credential.keyRef.Substring(4);$key=Get-JointSavedKey $credential
        $lines=@(($variable+'='+$key),('AGENT_SECRET_BINDINGS='+(@{$variable=$PlatformTenant}|ConvertTo-Json -Compress)))
        if($pair.Role -in @('BINDER','READ')){$lines+=@(('AGENT_ONTOLOGY_URL='+$plan.OntologyBaseUrl),('AGENT_ONTOLOGY_API_KEY_REF='+$credential.keyRef),('AGENT_PIN_ENVIRONMENT='+$Environment))}
        if($pair.Role -eq 'BINDER'){$lines+=@('AGENT_PIN_ENABLED=true','AGENT_PIN_RECORD_ENABLED=true')}
        if($pair.Role -eq 'READ'){$lines+='AGENT_PIN_RESOLUTION_ENABLED=true'}
        Write-JointPrivateText $repositoryRoot $private $pair.File (($lines-join [Environment]::NewLine)+[Environment]::NewLine)|Out-Null;$key=$null
    }
    Set-JointValue $script:jointState 'resources' ([pscustomobject]@{spaceId=$a.space.id;spaceCode=$a.space.code;releaseId=$a.first.id;replacementReleaseId=$a.replacement.id;endpointId=$aFirst.id;replacementEndpointId=$aSecond.id;opCode=$plan.OpCode;readCredentialId=$keys.READ.id;binderCredentialId=$keys.BINDER.id;consumerCredentialId=$keys.CONSUMER.id;readKeyRef=$keys.READ.keyRef;binderKeyRef=$keys.BINDER.keyRef;consumerKeyRef=$keys.CONSUMER.keyRef;revocationFixtures=[pscustomobject]@{readCredentialId=$keys.TEMP_READ.id;binderCredentialId=$keys.TEMP_BINDER.id;consumerCredentialId=$keys.TEMP_CONSUMER.id;readKeyRef=$keys.TEMP_READ.keyRef;binderKeyRef=$keys.TEMP_BINDER.keyRef;consumerKeyRef=$keys.TEMP_CONSUMER.keyRef;readPrincipalId=$keys.TEMP_READ.principalId;binderPrincipalId=$keys.TEMP_BINDER.principalId;consumerPrincipalId=$keys.TEMP_CONSUMER.principalId};isolationFixtures=[pscustomobject]@{spaceId=$b.space.id;spaceCode=$b.space.code;releaseId=$b.first.id;endpointId=$bFirst.id;opCode=$plan.OpCode}})
    if($plan.Mode -eq 'Reuse'){
        foreach($field in @('spaceId','spaceCode','releaseId','replacementReleaseId','opCode','readCredentialId','binderCredentialId','consumerCredentialId','readKeyRef','binderKeyRef','consumerKeyRef')){if((Get-JointValue $plan.Manifest $field) -cne (Get-JointValue $script:jointState.resources $field)){throw 'JOINT_FINAL_MANIFEST_RESOURCE_MISMATCH'}}
        foreach($group in @('revocationFixtures','isolationFixtures')){foreach($field in @(Get-JointNames (Get-JointValue $plan.Manifest $group))){if((Get-JointValue (Get-JointValue $plan.Manifest $group) $field) -cne (Get-JointValue (Get-JointValue $script:jointState.resources $group) $field)){throw 'JOINT_FINAL_MANIFEST_RESOURCE_MISMATCH'}}}
    }
    Set-JointValue $script:jointState 'stage' 'ONTOLOGY_READY';Set-JointValue $script:jointState 'lastError' $null;Save-JointStage
    $exitCode=0
}catch{
    $code='JOINT_PREPARATION_FAILED';if($_.Exception.Message -cmatch '^JOINT_[A-Z_]+$'){$code=$_.Exception.Message}
    if($code -match 'NOT_DELIVERED|CREDENTIAL_REQUIRED'){$exitCode=2}
    if($script:jointState){Set-JointValue $script:jointState 'lastError' $code;if($_.Exception.Data.Contains('HttpStatus')){Set-JointValue $script:jointState 'lastHttpStatus' ([int]$_.Exception.Data['HttpStatus'])};try{Save-JointStage}catch{}}
    Write-Error $code -ErrorAction Continue
}finally{
    if($script:jointToken){try{Invoke-JointOr '/auth/logout' 'POST'|Out-Null}catch{}};$script:jointToken=$null
    if($script:jointState){$summary=[ordered]@{sessionId=$SessionId;stage=$script:jointState.stage;complete=$false;partialPath=(Join-Path $private 'partial.json');resources=$script:jointState.resources;resourceIds=@(foreach($action in @(Get-JointNames $script:jointState.actions)){$item=Get-JointValue $script:jointState.actions $action;if($item.id){[pscustomobject]@{action=$action;id=$item.id;principalId=$item.principalId;spaceId=$item.spaceId}}});lastError=$script:jointState.lastError};Assert-JointSafeMetadata $summary;Write-Output ($summary|ConvertTo-Json -Depth 8)}
}
exit $exitCode
