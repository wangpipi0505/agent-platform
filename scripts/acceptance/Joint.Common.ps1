# EX-06公共准备基础：装载只定义函数，不读.env、不启动进程、不调用HTTP。
# 准备计划不是READY资源清单；资源状态、已验身份和秘密可用性必须由后续真实阶段核准。
function Get-JointValue([object]$Object, [string]$Name) {
    if ($Object -is [Collections.IDictionary]) { return $Object[$Name] }
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}
function Get-JointNames([object]$Object) {
    if ($Object -is [Collections.IDictionary]) { return @($Object.Keys) }
    if ($null -eq $Object) { return @() }
    return @($Object.PSObject.Properties | ForEach-Object { $_.Name })
}
function Assert-JointRootUrl([string]$Value) {
    $uri = $null
    if (-not [Uri]::TryCreate($Value,[UriKind]::Absolute,[ref]$uri) -or
        $uri.Scheme -ne 'http' -or $uri.Host -notin @('127.0.0.1','localhost','::1','[::1]') -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -ne '/') {
        throw 'JOINT_ROOT_URL_INVALID'
    }
    return $uri.AbsoluteUri.TrimEnd('/')
}
function Assert-JointAcceptancePath([string]$RepositoryRoot, [string]$Path) {
    try {
        if (-not [IO.Path]::IsPathRooted($RepositoryRoot) -or -not [IO.Path]::IsPathRooted($Path)) { throw 'invalid path' }
        $repository = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd('\','/')
        $root = [IO.Path]::GetFullPath((Join-Path $repository '.local/acceptance')).TrimEnd('\','/')
        $full = [IO.Path]::GetFullPath($Path).TrimEnd('\','/')
        if ($full -ne $root -and -not $full.StartsWith($root + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'outside acceptance root' }
        # 写私密会话前拒绝现有junction/symlink祖先，包含.local本身，避免字面路径仍指向仓外。
        $cursor = $full
        while ($cursor -and $cursor.Length -ge $repository.Length) {
            if (Test-Path -LiteralPath $cursor) {
                if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'reparse point' }
            }
            if ($cursor -eq $repository) { break }
            $cursor = Split-Path -Parent $cursor
        }
        return $full
    } catch { throw 'JOINT_ACCEPTANCE_PATH_INVALID' }
}
function Assert-JointManifestShape([object]$Manifest, [string]$SessionId, [string]$PlatformTenant, [string]$Environment) {
    $main = @('sessionId','platformTenant','environment','spaceId','spaceCode','releaseId','replacementReleaseId','appCode','pinAppCode','explicitAppCode','opCode','readCredentialId','binderCredentialId','consumerCredentialId','readKeyRef','binderKeyRef','consumerKeyRef','platformBaseUrl','ontologyBaseUrl')
    $allowed = $main + @('revocationFixtures','isolationFixtures')
    if (@(Get-JointNames $Manifest | Where-Object { $_ -notin $allowed }).Count) { throw 'JOINT_MANIFEST_FIELDS_INVALID' }
    foreach ($name in $main) {
        $value = Get-JointValue $Manifest $name
        if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value)) { throw 'JOINT_MANIFEST_FIELDS_INVALID' }
    }
    if ((Get-JointValue $Manifest 'sessionId') -cne $SessionId -or
        (Get-JointValue $Manifest 'platformTenant') -cne $PlatformTenant -or
        (Get-JointValue $Manifest 'environment') -cne $Environment) { throw 'JOINT_MANIFEST_CONTEXT_MISMATCH' }
    $credentialFields = @('readCredentialId','binderCredentialId','consumerCredentialId')
    $refFields = @('readKeyRef','binderKeyRef','consumerKeyRef')
    $revocation = Get-JointValue $Manifest 'revocationFixtures'
    $isolation = Get-JointValue $Manifest 'isolationFixtures'
    $temporaryFields = $credentialFields + $refFields
    $principalFields = @('readPrincipalId','binderPrincipalId','consumerPrincipalId')
    $isolationFields = @('spaceId','spaceCode','releaseId','endpointId','opCode')
    foreach ($group in @(@{Value=$revocation;Required=$temporaryFields;Allowed=($temporaryFields+$principalFields)},@{Value=$isolation;Required=$isolationFields;Allowed=$isolationFields})) {
        if ($null -eq $group.Value -or @((Get-JointNames $group.Value) | Where-Object { $_ -notin $group.Allowed }).Count) { throw 'JOINT_MANIFEST_FIELDS_INVALID' }
        foreach ($name in $group.Required) {
            $value = Get-JointValue $group.Value $name
            if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value)) { throw 'JOINT_MANIFEST_FIELDS_INVALID' }
        }
    }
    $uuidValues = @((Get-JointValue $Manifest 'spaceId'),(Get-JointValue $Manifest 'releaseId'),(Get-JointValue $Manifest 'replacementReleaseId'))
    $credentialValues = @()
    foreach ($object in @($Manifest,$revocation)) {
        foreach ($name in $credentialFields) { $credentialValues += Get-JointValue $object $name }
        foreach ($name in $refFields) {
            if ((Get-JointValue $object $name) -cnotmatch '^(env:AGENT_SECRET_[A-Z0-9_]+|vault:[^\s]+)$') { throw 'JOINT_MANIFEST_SECRET_REF_INVALID' }
        }
    }
    $uuidValues += $credentialValues
    foreach ($name in @('spaceId','releaseId','endpointId')) { $uuidValues += Get-JointValue $isolation $name }
    foreach ($name in $principalFields) { if (Get-JointValue $revocation $name) { $uuidValues += Get-JointValue $revocation $name } }
    foreach ($value in $uuidValues) {
        $id = [Guid]::Empty
        if (-not [Guid]::TryParse($value,[ref]$id) -or $id -eq [Guid]::Empty) { throw 'JOINT_MANIFEST_ID_INVALID' }
    }
    if (@($credentialValues | Select-Object -Unique).Count -ne 6 -or
        (Get-JointValue $Manifest 'releaseId') -eq (Get-JointValue $Manifest 'replacementReleaseId') -or
        (Get-JointValue $Manifest 'spaceId') -eq (Get-JointValue $isolation 'spaceId') -or
        @(@('appCode','pinAppCode','explicitAppCode') | ForEach-Object { Get-JointValue $Manifest $_ } | Select-Object -Unique).Count -ne 3) { throw 'JOINT_MANIFEST_RESOURCES_NOT_DISTINCT' }
    foreach ($operationCode in @((Get-JointValue $Manifest 'opCode'),(Get-JointValue $isolation 'opCode'))) {
        if ($operationCode -cnotmatch '^[A-Za-z][A-Za-z0-9_-]{0,63}$') { throw 'JOINT_MANIFEST_OPERATION_INVALID' }
    }
    Assert-JointRootUrl (Get-JointValue $Manifest 'platformBaseUrl') | Out-Null
    Assert-JointRootUrl (Get-JointValue $Manifest 'ontologyBaseUrl') | Out-Null
    return $Manifest
}
function Get-JointPreparationPlan {
    param(
        [Parameter(Mandatory=$true)][string]$RepositoryRoot,
        [Parameter(Mandatory=$true)][string]$SessionId,
        [string]$PlatformTenant='local-v12', [string]$Environment='production',
        [string]$OutputDirectory, [string]$PlatformBaseUrl='http://127.0.0.1:18080', [string]$OntologyBaseUrl='http://127.0.0.1:18182',
        [string]$ReuseManifestPath, [ValidateSet('TestFixture','Real')][string]$ModelMode='TestFixture',
        [Alias('RealModelEntryId')][string]$ModelEntryId
    )
    if ($SessionId -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$' -or
        [string]::IsNullOrWhiteSpace($PlatformTenant) -or [string]::IsNullOrWhiteSpace($Environment)) { throw 'JOINT_CONTEXT_INVALID' }
    if ($ModelMode -eq 'Real' -and [string]::IsNullOrWhiteSpace($ModelEntryId)) { throw 'JOINT_REAL_MODEL_REQUIRED' }
    $platform = Assert-JointRootUrl $PlatformBaseUrl
    $ontology = Assert-JointRootUrl $OntologyBaseUrl
    if (-not $OutputDirectory) { $OutputDirectory = Join-Path $RepositoryRoot '.local/acceptance' }
    $output = Assert-JointAcceptancePath $RepositoryRoot $OutputDirectory
    $manifestPath = Join-Path $output 'manifest.json'
    $manifest = $null; $mode = 'Create'
    if (-not $ReuseManifestPath -and (Test-Path -LiteralPath $manifestPath)) { throw 'JOINT_MANIFEST_EXISTS' }
    if ($ReuseManifestPath) {
        $manifestPath = Assert-JointAcceptancePath $RepositoryRoot $ReuseManifestPath
        if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'JOINT_REUSE_MANIFEST_MISSING' }
        try { $manifest = [IO.File]::ReadAllText($manifestPath) | ConvertFrom-Json -ErrorAction Stop } catch { throw 'JOINT_REUSE_MANIFEST_INVALID_JSON' }
        $manifest = Assert-JointManifestShape $manifest $SessionId $PlatformTenant $Environment
        if ((Assert-JointRootUrl $manifest.platformBaseUrl) -cne $platform -or (Assert-JointRootUrl $manifest.ontologyBaseUrl) -cne $ontology) { throw 'JOINT_MANIFEST_CONTEXT_MISMATCH' }
        $mode = 'Reuse'
    }
    if ($manifest) { $operationCode = $manifest.opCode } else {
        $digest = [Security.Cryptography.SHA256]::Create()
        try { $operationCode = 'joint_' + ([BitConverter]::ToString($digest.ComputeHash([Text.Encoding]::UTF8.GetBytes($SessionId))).Replace('-','').ToLowerInvariant()).Substring(0,24) } finally { $digest.Dispose() }
    }
    # 此处只产计划、不创建文件；READY/ACTIVE、主体分离及ref可用均留待真实核验，不能从语法通过推出。
    return [pscustomobject]@{Mode=$mode;OpCode=$operationCode;SessionId=$SessionId;PlatformTenant=$PlatformTenant;Environment=$Environment;OutputDirectory=$output;ManifestPath=$manifestPath;PrivateDirectory=(Join-Path $output $SessionId);PlatformBaseUrl=$platform;OntologyBaseUrl=$ontology;ModelMode=$ModelMode;ModelEntryId=$ModelEntryId;Manifest=$manifest;RequiresLiveValidation=$true;ResourcesVerified=$false}
}
function New-JointPrivateDirectory([string]$RepositoryRoot, [string]$Directory) {
    $path = Assert-JointAcceptancePath $RepositoryRoot $Directory
    $current = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $allowed = @($current.Value,'S-1-5-18','S-1-5-32-544')
    if (-not (Test-Path -LiteralPath $path)) {
        try {
            New-Item -ItemType Directory -Path $path | Out-Null
            $acl = New-Object Security.AccessControl.DirectorySecurity
            $acl.SetAccessRuleProtection($true,$false)
            $acl.SetOwner($current)
            foreach ($sid in $allowed) {
                $identity = New-Object Security.Principal.SecurityIdentifier($sid)
                $rule = New-Object Security.AccessControl.FileSystemAccessRule($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
                $acl.AddAccessRule($rule)
            }
            Set-Acl -LiteralPath $path -AclObject $acl
        } catch { throw 'JOINT_PRIVATE_DIRECTORY_FAILED' }
    }
    if (-not (Test-Path -LiteralPath $path -PathType Container)) { throw 'JOINT_PRIVATE_DIRECTORY_UNSAFE' }
    $actual = Get-Acl -LiteralPath $path
    $rules = @($actual.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    if (-not $actual.AreAccessRulesProtected -or -not $rules.Count -or
        @($rules | Where-Object { $_.IsInherited -or $_.IdentityReference.Value -notin $allowed -or $_.AccessControlType -ne 'Allow' }).Count -or
        -not @($rules | Where-Object { $_.IdentityReference.Value -eq $current.Value -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl }).Count) { throw 'JOINT_PRIVATE_DIRECTORY_UNSAFE' }
    return $path
}

# 一次有界请求：不重定向、不重试写操作、不记录body/headers/异常响应原文。
function Invoke-JointHttp {
    param([Parameter(Mandatory=$true)][string]$BaseUrl,[Parameter(Mandatory=$true)][string]$Path,
        [ValidateSet('GET','POST','PUT','DELETE')][string]$Method='GET',[AllowNull()][object]$Body,
        [string]$BearerToken,[string]$ApiKey,[int]$ExpectedStatus=200,
        [ValidateRange(1,60)][int]$TimeoutSeconds=30,[ValidateRange(256,4194304)][int]$MaxResponseBytes=2097152)
    $base=Assert-JointRootUrl $BaseUrl
    if(-not $Path.StartsWith('/') -or $Path.StartsWith('//') -or $Path.Contains('#')){throw 'JOINT_HTTP_PATH_INVALID'}
    if($BearerToken -and $ApiKey){throw 'JOINT_HTTP_AUTH_AMBIGUOUS'}
    if(($BearerToken -and $BearerToken -match '[\r\n]') -or ($ApiKey -and $ApiKey -match '[\r\n]')){throw 'JOINT_HTTP_AUTH_INVALID'}
    $response=$null;$stream=$null;$buffered=$null
    $requestClock=[Diagnostics.Stopwatch]::StartNew()
    try{
        $request=[Net.HttpWebRequest]::Create($base+$Path)
        $request.Method=$Method;$request.Proxy=$null;$request.AllowAutoRedirect=$false
        $request.Timeout=$TimeoutSeconds*1000;$request.ReadWriteTimeout=$TimeoutSeconds*1000
        $request.Accept='application/json'
        if($BearerToken){$request.Headers['Authorization']='Bearer '+$BearerToken}
        if($ApiKey){$request.Headers['X-API-Key']=$ApiKey}
        if($PSBoundParameters.ContainsKey('Body')){
            $bytes=[Text.Encoding]::UTF8.GetBytes((ConvertTo-Json -InputObject $Body -Depth 32 -Compress))
            $request.ContentType='application/json;charset=utf-8';$request.ContentLength=$bytes.Length
            $out=$null;try{$out=$request.GetRequestStream();$out.Write($bytes,0,$bytes.Length)}finally{if($out){$out.Dispose()}}
        }elseif($Method -in @('POST','PUT','DELETE')){$request.ContentLength=0}
        try{$response=[Net.HttpWebResponse]$request.GetResponse()}catch [Net.WebException]{
            if($_.Exception.Response){$response=[Net.HttpWebResponse]$_.Exception.Response}else{throw 'JOINT_HTTP_TRANSPORT_FAILED'}
        }
        $status=[int]$response.StatusCode
        if($status -ne $ExpectedStatus){$failure=New-Object InvalidOperationException('JOINT_HTTP_UNEXPECTED_STATUS');$failure.Data['HttpStatus']=$status;throw $failure}
        $stream=$response.GetResponseStream();$buffered=New-Object IO.MemoryStream
        $chunk=New-Object byte[] 8192
        while($true){
            if($requestClock.Elapsed.TotalSeconds -ge $TimeoutSeconds){throw 'JOINT_HTTP_TRANSPORT_FAILED'}
            $read=$stream.Read($chunk,0,$chunk.Length)
            if($read -le 0){break}
            if($buffered.Length+$read -gt $MaxResponseBytes){throw 'JOINT_HTTP_BODY_TOO_LARGE'}
            $buffered.Write($chunk,0,$read)
        }
        try{$envelope=[Text.Encoding]::UTF8.GetString($buffered.ToArray())|ConvertFrom-Json -ErrorAction Stop}catch{throw 'JOINT_HTTP_ENVELOPE_INVALID'}
        if(-not $envelope -or -not $envelope.PSObject.Properties['code'] -or $envelope.code -isnot [string] -or
            -not $envelope.PSObject.Properties['data']){throw 'JOINT_HTTP_ENVELOPE_INVALID'}
        if($status -ge 200 -and $status -lt 300 -and $envelope.code -cne 'OK'){throw 'JOINT_HTTP_ERROR_ENVELOPE'}
        return [pscustomobject]@{Status=$status;Code=$envelope.code;Data=$envelope.data}
    }catch{
        if($_.Exception.Message -cmatch '^JOINT_HTTP_[A-Z_]+$'){throw}
        throw 'JOINT_HTTP_TRANSPORT_FAILED'
    }finally{if($stream){$stream.Dispose()};if($buffered){$buffered.Dispose()};if($response){$response.Dispose()}}
}

function Set-JointValue([object]$Object,[string]$Name,[AllowNull()][object]$Value) {
    if($Object -is [Collections.IDictionary]){$Object[$Name]=$Value}else{$Object|Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force}
}
function Assert-JointSafeMetadata([AllowNull()][object]$Value) {
    if($null -eq $Value){return}
    if($Value -is [string]){if($Value -match '(?i)(ontosa_|ontopa_|Bearer\s+|eyJ[A-Za-z0-9_-]+\.eyJ)'){throw 'JOINT_METADATA_SECRET_REJECTED'};return}
    if($Value -is [Collections.IDictionary] -or $Value -is [pscustomobject]){
        foreach($name in @(Get-JointNames $Value)){
            if($name -match '^(?i:key|token|password|authorization|secret)$'){throw 'JOINT_METADATA_SECRET_REJECTED'}
            Assert-JointSafeMetadata (Get-JointValue $Value $name)
        }
    }elseif($Value -is [Collections.IEnumerable]){foreach($item in $Value){Assert-JointSafeMetadata $item}}
}
function Assert-JointPrivateFile([string]$Path) {
    $current=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $allowed=@($current,'S-1-5-18','S-1-5-32-544')
    $rules=@((Get-Acl -LiteralPath $Path).GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    if(-not $rules.Count -or @($rules|Where-Object{$_.IdentityReference.Value -notin $allowed -or $_.AccessControlType -ne 'Allow'}).Count){throw 'JOINT_PRIVATE_FILE_UNSAFE'}
}
# 密钥只由此函数写受限目录里的env；JSON/log由独立的安全元数据检查隔开。
function Write-JointPrivateText([string]$RepositoryRoot,[string]$Directory,[string]$FileName,[string]$Text) {
    if($FileName -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,80}$'){throw 'JOINT_PRIVATE_FILENAME_INVALID'}
    $directory=New-JointPrivateDirectory $RepositoryRoot $Directory
    $path=Assert-JointAcceptancePath $RepositoryRoot (Join-Path $directory $FileName)
    if(Test-Path -LiteralPath $path){Assert-JointPrivateFile $path}
    $temp=Join-Path $directory ('.write-'+[Guid]::NewGuid().ToString('N')+'.tmp');$file=$null
    try{
        $bytes=(New-Object Text.UTF8Encoding($false)).GetBytes($Text)
        $file=New-Object IO.FileStream($temp,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        $file.Write($bytes,0,$bytes.Length);$file.Flush($true);$file.Dispose();$file=$null
        if(Test-Path -LiteralPath $path){[IO.File]::Replace($temp,$path,[System.Management.Automation.Language.NullString]::Value)}else{[IO.File]::Move($temp,$path)}
        Assert-JointPrivateFile $path
    }catch{if($_.Exception.Message -cmatch '^JOINT_'){throw $_.Exception.Message};throw 'JOINT_PRIVATE_WRITE_FAILED'}
    finally{if($file){$file.Dispose()};if(Test-Path -LiteralPath $temp){$safe=Assert-JointAcceptancePath $RepositoryRoot $temp;[IO.File]::Delete($safe)}}
    return $path
}
function Write-JointPrivateJson([string]$RepositoryRoot,[string]$Directory,[string]$FileName,[object]$Value) {
    Assert-JointSafeMetadata $Value
    Write-JointPrivateText $RepositoryRoot $Directory $FileName ((ConvertTo-Json -InputObject $Value -Depth 32)+[Environment]::NewLine) | Out-Null
}
function Read-JointPrivateEnvironment([string]$RepositoryRoot,[string]$Directory,[string]$FileName='credentials.env') {
    if($FileName -cnotmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,80}$'){throw 'JOINT_PRIVATE_FILENAME_INVALID'}
    $directory=New-JointPrivateDirectory $RepositoryRoot $Directory
    $path=Assert-JointAcceptancePath $RepositoryRoot (Join-Path $directory $FileName)
    if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw 'JOINT_PRIVATE_REFS_UNAVAILABLE'}
    Assert-JointPrivateFile $path
    $values=@{}
    foreach($line in [IO.File]::ReadAllLines($path)){
        if($line -cnotmatch '^(AGENT_SECRET_[A-Z0-9_]+)=(.+)$' -or $values.ContainsKey($Matches[1])){throw 'JOINT_PRIVATE_ENV_INVALID'}
        $values[$Matches[1]]=$Matches[2]
    }
    return $values
}
function Assert-JointArtifactsReady([object[]]$Artifacts) {
    if($Artifacts.Count -ne 4 -or (@($Artifacts.kind|Sort-Object)-join ',') -cne 'BUNDLE,CONTEXT_PACK,KNOWLEDGE_INDEX,MANIFEST' -or
        @($Artifacts|Where-Object{-not $_.ready -or $_.sha256 -cnotmatch '^[a-fA-F0-9]{64}$' -or [long]$_.size -le 0}).Count){throw 'JOINT_PUBLICATION_ARTIFACTS_INCOMPLETE'}
}
function Assert-JointPublicationPrepared([object]$Publication) {
    if((Get-JointValue $Publication 'status') -cne 'READY_TO_COMMIT'){throw 'JOINT_PUBLICATION_NOT_READY'}
    Assert-JointArtifactsReady @(Get-JointValue $Publication 'artifacts')
}

function Assert-JointPlatformModelPlan([object]$Plan,[object]$Runtime) {
    # 这是操作者明确确认的隔离运行计划，不是脚本伪造的进程/数据库检查结果。
    $names=@('sessionId','platformTenant','platformBaseUrl','databaseName','redisHost','redisPort','isolationConfirmed','modelDemoMode','engineDemoMode','fixtureBaseUrl')
    if($null -eq $Runtime -or @((Get-JointNames $Runtime)|Where-Object{$_ -cnotin $names}).Count){throw 'JOINT_PLATFORM_RUNTIME_PLAN_INVALID'}
    foreach($name in @('sessionId','platformTenant','platformBaseUrl')){
        if((Get-JointValue $Runtime $name) -cne (Get-JointValue $Plan $name)){throw 'JOINT_PLATFORM_RUNTIME_CONTEXT_MISMATCH'}
    }
    if($Runtime.isolationConfirmed -isnot [bool] -or -not $Runtime.isolationConfirmed -or
        $Runtime.databaseName -cnotmatch '^agent_platform_[a-z0-9_]{1,48}$' -or
        $Runtime.databaseName -cin @('agent_platform_v12','agent_platform_dev_v12','agent_platform_engine_test') -or
        $Runtime.redisHost -cne '127.0.0.1' -or $Runtime.redisPort -is [string] -or
        $Runtime.redisPort -lt 1024 -or $Runtime.redisPort -gt 65535 -or $Runtime.redisPort -eq 26379){throw 'JOINT_PLATFORM_ISOLATION_REQUIRED'}
    if($Runtime.modelDemoMode -isnot [bool] -or $Runtime.engineDemoMode -isnot [bool]){throw 'JOINT_PLATFORM_RUNTIME_PLAN_INVALID'}
    if($Plan.ModelMode -ceq 'TestFixture'){
        if(-not $Runtime.modelDemoMode -or -not $Runtime.engineDemoMode){throw 'JOINT_PLATFORM_DEMO_REQUIRED'}
        $fixture=Assert-JointRootUrl ([string]$Runtime.fixtureBaseUrl)
    }else{
        if($Runtime.modelDemoMode -or $Runtime.engineDemoMode -or $Runtime.fixtureBaseUrl -or
            $Plan.ModelEntryId -cnotmatch '^[1-9][0-9]{0,17}$'){throw 'JOINT_REAL_MODEL_PLAN_INVALID'}
        $fixture=$null
    }
    return [pscustomobject][ordered]@{sessionId=$Plan.SessionId;platformTenant=$Plan.PlatformTenant;platformBaseUrl=$Plan.PlatformBaseUrl;databaseName=$Runtime.databaseName;redisHost=$Runtime.redisHost;redisPort=[int]$Runtime.redisPort;isolationConfirmed=$true;modelDemoMode=$Runtime.modelDemoMode;engineDemoMode=$Runtime.engineDemoMode;fixtureBaseUrl=$fixture}
}

function Invoke-JointPlatformModel {
    param([object]$Plan,[object]$State,[object]$Runtime,[string]$BearerToken,[scriptblock]$Save)
    if(-not $BearerToken -or -not $Save){throw 'JOINT_PLATFORM_IDENTITY_MISMATCH'}
    if($State.pending){throw 'JOINT_PENDING_ACTION_NEEDS_RECONCILIATION'}
    if(-not $State.resources -or -not $State.resources.spaceId -or -not $State.resources.releaseId -or
        $State.complete -ne $false){throw 'JOINT_ONTOLOGY_STAGE_REQUIRED'}
    $runtimePlan=Assert-JointPlatformModelPlan $Plan $Runtime
    if($State.platformRuntime -and (ConvertTo-Json -InputObject $State.platformRuntime -Compress) -cne
        (ConvertTo-Json -InputObject $runtimePlan -Compress)){throw 'JOINT_PLATFORM_RUNTIME_CONTEXT_MISMATCH'}
    Set-JointValue $State 'platformRuntime' $runtimePlan
    & $Save
    $clock=[Diagnostics.Stopwatch]::StartNew()
    function Invoke-ModelRequest([string]$Path,[string]$Method='GET',[AllowNull()][object]$Body){
        $remaining=180-[int]$clock.Elapsed.TotalSeconds
        if($remaining -lt 1){throw 'JOINT_PLATFORM_MODEL_BUDGET_EXCEEDED'}
        $call=@{BaseUrl=$Plan.PlatformBaseUrl;Path=$Path;Method=$Method;BearerToken=$BearerToken;TimeoutSeconds=[Math]::Min(30,$remaining)}
        if($PSBoundParameters.ContainsKey('Body')){$call.Body=$Body}
        return (Invoke-JointHttp @call).Data
    }
    function Save-ModelStep([string]$Name,[string]$Path,[object]$Body,[scriptblock]$Project){
        $old=Get-JointValue $State.actions $Name
        if($old){return $old}
        $pending=[pscustomobject]@{action=$Name;method='POST';path=$Path;phase='REQUEST';startedAt=[DateTime]::UtcNow.ToString('o')}
        Set-JointValue $State 'pending' $pending
        Set-JointValue $State 'stage' $Name
        & $Save
        try{
            $call=@{Path=$Path;Method='POST'};if($null -ne $Body){$call.Body=$Body}
            $data=Invoke-ModelRequest @call
            $record=& $Project $data
            Assert-JointSafeMetadata $record
            Set-JointValue $State.actions $Name $record
            # 先在pending仍存在时保存确认记录，再清pending；落盘失败仍保留未知写结果。
            & $Save
            Set-JointValue $State 'pending' $null
            & $Save
            return $record
        }catch{Set-JointValue $State 'pending' $pending;throw}
    }
    function Assert-ModelEntry([object]$Entry,[long]$ProviderId,[string]$ModelName){
        if(-not $Entry -or [string]$Entry.id -cnotmatch '^[1-9][0-9]{0,17}$' -or
            [string]$Entry.providerId -cnotmatch '^[1-9][0-9]{0,17}$' -or
            $Entry.providerId -ne $ProviderId -or $Entry.modelName -cne $ModelName -or
            $Entry.status -cne 'ENABLED' -or $Entry.provider.status -cne 'ENABLED' -or
            $Entry.provider.id -ne $ProviderId -or 'LOCAL' -cnotin @($Entry.provider.trustLabels) -or
            'chat' -cnotin @($Entry.capabilities) -or 'tools' -cnotin @($Entry.capabilities)){
            throw 'JOINT_MODEL_ENTRY_NOT_READY'
        }
    }
    if($Plan.ModelMode -ceq 'TestFixture'){
        $sha=[Security.Cryptography.SHA256]::Create()
        try{$code='fixture_joint_'+([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Plan.SessionId))).Replace('-','').ToLowerInvariant()).Substring(0,24)}finally{$sha.Dispose()}
        $provider=Save-ModelStep 'model_provider' '/admin/model-providers' @{
            code=$code;name=('EX06 fixture '+$Plan.SessionId);baseUrl=$runtimePlan.fixtureBaseUrl
            protocol='OPENAI_COMPATIBLE';trustLabels=@('LOCAL')
        } {
            param($data)
            if([string]$data.id -cnotmatch '^[1-9][0-9]{0,17}$' -or $data.code -cne $code -or $data.status -cne 'DISABLED'){throw 'JOINT_MODEL_PROVIDER_REPLY_INVALID'}
            [pscustomobject]@{id=[long]$data.id;code=$data.code}
        }
        if([string]$provider.id -cnotmatch '^[1-9][0-9]{0,17}$' -or $provider.code -cne $code){throw 'JOINT_MODEL_PROVIDER_IDENTITY_MISMATCH'}
        $providerRows=@(Invoke-ModelRequest '/admin/model-providers')
        $observed=@($providerRows|Where-Object{$_.id -eq $provider.id})
        if($observed.Count -ne 1 -or $observed[0].code -cne $code -or
            $observed[0].baseUrl -cne $runtimePlan.fixtureBaseUrl -or $observed[0].protocol -cne 'OPENAI_COMPATIBLE' -or
            'LOCAL' -cnotin @($observed[0].trustLabels)){throw 'JOINT_MODEL_PROVIDER_IDENTITY_MISMATCH'}
        Save-ModelStep 'model_provider_test' ('/admin/model-providers/'+$provider.id+'/test') $null {
            param($data)
            if($data.ok -isnot [bool] -or -not $data.ok -or $data.stage -cne 'OK' -or
                'ready.model' -cnotin @($data.discoveredModels)){throw 'JOINT_MODEL_PROVIDER_TEST_FAILED'}
            [pscustomobject]@{id=$provider.id;ok=$true}
        }|Out-Null
        $entry=Save-ModelStep 'model_entry' '/admin/model-entries' @{
            providerId=$provider.id;modelName='ready.model';displayName=('EX06 fixture '+$Plan.SessionId)
            contextWindow=8192;maxOutputTokens=512;capabilities=@('chat','tools')
        } {
            param($data)
            Assert-ModelEntry $data $provider.id 'ready.model'
            [pscustomobject]@{id=[long]$data.id;providerId=$provider.id;modelName='ready.model'}
        }
    }else{
        # Real只复用显式ID，不挑首条、不改供应商、不把fixture作为真实模型。
        $rows=@(Invoke-ModelRequest '/admin/model-entries')
        $chosen=@($rows|Where-Object{$_.id -eq [long]$Plan.ModelEntryId})
        if($chosen.Count -ne 1 -or $chosen[0].provider.code -cmatch 'fixture|^smoke_model_' -or
            [string]::IsNullOrWhiteSpace($chosen[0].modelName)){throw 'JOINT_REAL_MODEL_NOT_READY'}
        Assert-ModelEntry $chosen[0] $chosen[0].providerId $chosen[0].modelName
        $entry=[pscustomobject]@{id=[long]$chosen[0].id;providerId=[long]$chosen[0].providerId;modelName=$chosen[0].modelName}
    }
    if([string]$entry.id -cnotmatch '^[1-9][0-9]{0,17}$' -or [string]$entry.providerId -cnotmatch '^[1-9][0-9]{0,17}$'){throw 'JOINT_MODEL_ENTRY_NOT_READY'}
    $entries=@(Invoke-ModelRequest '/admin/model-entries')
    $readBack=@($entries|Where-Object{$_.id -eq $entry.id})
    if($readBack.Count -ne 1){throw 'JOINT_MODEL_ENTRY_NOT_READY'}
    Assert-ModelEntry $readBack[0] $entry.providerId $entry.modelName
    if($Plan.ModelMode -ceq 'TestFixture' -and $readBack[0].provider.code -cne $code){throw 'JOINT_MODEL_PROVIDER_IDENTITY_MISMATCH'}
    # 健康报告是真实HTTP探测结果；不是评测ACCEPTED，也不代表应用发布或真实外部模型验收。
    $health=Invoke-ModelRequest ('/admin/model-entries/'+$entry.id+'/test') 'POST'
    if($health.ok -isnot [bool] -or -not $health.ok -or $health.status -cne 'HEALTHY' -or
        $health.entryId -ne $entry.id -or $health.providerId -ne $entry.providerId -or $health.model -cne $entry.modelName){throw 'JOINT_MODEL_HEALTH_FAILED'}
    foreach($key in @('config','endpoint','auth','model','tools')){
        $check=@($health.checks|Where-Object{$_.key -ceq $key})
        if($check.Count -ne 1 -or $check[0].status -cne 'PASS'){throw 'JOINT_MODEL_HEALTH_FAILED'}
    }
    Set-JointValue $State 'platformModel' ([pscustomobject]@{modelEntryId=$entry.id;providerId=$entry.providerId;modelName=$entry.modelName;modelMode=$Plan.ModelMode;healthStatus=$health.status;complete=$false})
    Set-JointValue $State 'stage' 'PLATFORM_MODEL_READY'
    Set-JointValue $State 'lastError' $null
    & $Save
    return $State.platformModel
}
