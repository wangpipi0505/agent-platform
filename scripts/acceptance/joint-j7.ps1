<#
.SYNOPSIS
  EX-07 J7 轮换/撤权：只用另建的临时凭据组做 grace=0 轮换、撤 binder 角色、撤临时消费凭据，
  并核对 manifest 主只读/绑定/消费凭据未被撤销。

.DESCRIPTION
  为什么必须用临时组：本段是破坏性动作，撤权后该主体无法恢复原能力；手册要求不得撤销
  manifest 主 readonly/binder/consumer。manifest.revocationFixtures 就是为这条专门登记的临时主体。

  步骤（全部为 OR 既有接口，human Bearer 需要 grant.write / system.user.write）：
    1) 临时 binder key 基线写绑定（必须 200，否则临时组本身没有 release.bind，记 FAIL）；
    2) POST /onto/platform-actors/{binderCredentialId}/rotate {graceSeconds:0}，拿一次性新明文 key；
    3) 旧 key 再写 → 401 UNAUTHORIZED（grace 0 立即失效）；
    4) 新 key 再写 → 200；
    5) GET /onto/system/users + /roles，PUT /onto/system/users/{id} 去掉 SERVICE_ACCOUNT_BINDER；
       再写 → 403 NO_PERMISSION（按 -PermissionWaitSeconds 有界重试）；
    6) POST /onto/platform-actors/{binderCredentialId}/revoke，新 key 再写 → 401；
    7) POST /onto/platform-actors/{consumerCredentialId}/revoke，
       POST /onto/r/{spaceCode}/releases/{releaseId}/ops/{opCode} → 401；
    8) 主只读 key GET /onto/releases/{releaseId}/manifest 仍 200（未动主凭据）。

  缺输入（含未显式 -DestructiveApproved）→ [BLOCKED] 退出码 3，不发任何请求。
  断言失败 → 1；通过 → 0。证据只记录凭据 ID/状态码/布尔，不记录任何 key 原文。

.PARAMETER DestructiveApproved
  破坏性撤权必须显式批准。批准后才可能改动临时主体；主凭据永远不动。

.PARAMETER PermissionWaitSeconds
  撤角色后有界等待权限生效（默认 5s，范围 1..60）。超时仍 403 之外的响应记 FAIL。

.EXAMPLE
  $env:AGENT_ACCEPTANCE_OR_BEARER = '<OR human admin token>'
  ./joint-j7.ps1 -SpaceId <uuid> -SpaceCode joint-xxx -ReleaseId <uuid> -DestructiveApproved `
      -RevocationBinderKey <ontosa_...> -RevocationBinderCredentialId <uuid> -RevocationBinderPrincipalId <uuid> `
      -RevocationConsumerKey <ontopa_...> -RevocationConsumerCredentialId <uuid>
#>
[CmdletBinding()]
param(
    [string]$OntologyBase = 'http://127.0.0.1:18182',
    [string]$PlatformTenant = 'local-v12',
    [string]$Environment = 'production',
    [string]$SpaceId,
    [string]$SpaceCode,
    [string]$ReleaseId,
    [string]$OpCode,
    [string]$OntologyAdminBearer,
    [string]$AdminBearerEnv = 'AGENT_ACCEPTANCE_OR_BEARER',
    [string]$ReadKey,
    [string]$ReadKeyEnv = 'AGENT_SECRET_ONTO_ENGINE_READ_KEY',
    [string]$CredentialsFile,
    [string]$RevocationBinderKey,
    [string]$RevocationBinderCredentialId,
    [string]$RevocationBinderPrincipalId,
    [string]$RevocationConsumerKey,
    [string]$RevocationConsumerCredentialId,
    [string]$ProbeAppCode,
    [switch]$DestructiveApproved,
    [ValidateRange(1, 60)][int]$PermissionWaitSeconds = 5,
    [string]$EvidenceDir,
    [string]$SessionId
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
$ExitPass = 0
$ExitFail = 1
$ExitBlocked = 3
$Started = Get-Date
$UuidPattern = '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
$ServiceKeyPattern = '^ontosa_[A-Za-z0-9_-]{43}$'
$ConsumerKeyPattern = '^ontopa_[A-Za-z0-9_-]{43}$'
$CodePattern = '^[A-Za-z][A-Za-z0-9_-]{0,79}$'
$Checks = New-Object System.Collections.ArrayList
$Details = [ordered]@{}
$Verdict = 'PASS'
$Reason = ''
$script:FirstFailure = ''

function Get-Text([object]$Value) {
    if ($null -eq $Value) { return '' }
    return ([string]$Value).Trim()
}

function Add-Check([string]$Id, [bool]$Ok, [string]$Detail) {
    [void]$Checks.Add([ordered]@{ id = $Id; ok = $Ok; detail = $Detail })
    if ($Ok) {
        Write-Output ('[PASS] ' + $Id + '  ' + $Detail)
    } else {
        Write-Output ('[FAIL] ' + $Id + '  ' + $Detail)
        if (-not $script:FirstFailure) { $script:FirstFailure = $Id + ': ' + $Detail }
    }
}

function Invoke-StageHttp {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Url,
        [hashtable]$Headers,
        [AllowNull()][object]$Body,
        [int]$Timeout = 30
    )
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.Method = $Method
    $request.Proxy = $null
    $request.AllowAutoRedirect = $false
    $request.Timeout = $Timeout * 1000
    $request.ReadWriteTimeout = $Timeout * 1000
    $request.Accept = 'application/json'
    $request.AutomaticDecompression = [Net.DecompressionMethods]::GZip -bor [Net.DecompressionMethods]::Deflate
    if ($Headers) {
        foreach ($name in $Headers.Keys) { $request.Headers[$name] = [string]$Headers[$name] }
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 32 -Compress
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        $request.ContentType = 'application/json;charset=utf-8'
        $request.ContentLength = $bytes.Length
        $stream = $null
        try {
            $stream = $request.GetRequestStream()
            $stream.Write($bytes, 0, $bytes.Length)
        } finally {
            if ($stream) { $stream.Dispose() }
        }
    } elseif ($Method -in @('POST', 'PUT', 'DELETE')) {
        $request.ContentLength = 0
    }
    $response = $null
    $reader = $null
    try {
        try {
            $response = [Net.HttpWebResponse]$request.GetResponse()
        } catch [Net.WebException] {
            if ($_.Exception.Response) { $response = [Net.HttpWebResponse]$_.Exception.Response } else { throw }
        }
        $status = [int]$response.StatusCode
        $stream = $response.GetResponseStream()
        $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8)
        $raw = $reader.ReadToEnd()
        $parsed = $null
        if (Get-Text $raw) {
            try { $parsed = $raw | ConvertFrom-Json -ErrorAction Stop } catch { $parsed = $null }
        }
        return [pscustomobject]@{ Status = $status; Raw = $raw; Body = $parsed }
    } finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Dispose() }
    }
}

function Test-Envelope {
    param([object]$Response, [int[]]$Status, [string]$Code = 'OK')
    $actualCode = ''
    if ($null -ne $Response.Body) {
        $property = $Response.Body.PSObject.Properties['code']
        if ($null -ne $property) { $actualCode = Get-Text $property.Value }
    }
    $data = $null
    if ($null -ne $Response.Body) {
        $dataProperty = $Response.Body.PSObject.Properties['data']
        if ($null -ne $dataProperty) { $data = $dataProperty.Value }
    }
    $ok = (($Status -contains $Response.Status) -and ($actualCode -ceq $Code))
    return [pscustomobject]@{ Ok = $ok; Status = $Response.Status; Code = $actualCode; Data = $data; Message = $(if ($null -ne $Response.Body -and $null -ne $Response.Body.PSObject.Properties['message']) { Get-Text $Response.Body.message } else { '' }) }
}

function Resolve-StageKey([string]$Explicit, [string]$EnvName, [string]$KeyFile, [string]$Suffix) {
    if (Get-Text $Explicit) { return (Get-Text $Explicit) }
    $name = Get-Text $EnvName
    if ($name) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (Get-Text $value) { return (Get-Text $value) }
    }
    if (-not (Get-Text $KeyFile)) { return '' }
    if (-not (Test-Path -LiteralPath $KeyFile -PathType Leaf)) { return '' }
    $candidates = @()
    foreach ($line in [IO.File]::ReadAllLines($KeyFile)) {
        $trim = $line.Trim()
        if (-not $trim -or $trim.StartsWith('#')) { continue }
        $index = $trim.IndexOf('=')
        if ($index -lt 1) { continue }
        $key = $trim.Substring(0, $index).Trim()
        $value = $trim.Substring($index + 1).Trim()
        if ($key -notmatch '^AGENT_SECRET_[A-Z0-9_]+$') { continue }
        if ($key -cnotmatch ('_' + $Suffix + '$')) { continue }
        if (($Suffix -notlike 'TEMP_*') -and ($key -cmatch ('_TEMP_' + $Suffix + '$'))) { continue }
        $candidates += [pscustomobject]@{ key = $key; value = $value }
    }
    if ($candidates.Count -eq 0) { return '' }
    return [string](($candidates | Sort-Object { $_.key.Length } | Select-Object -First 1).value)
}

function Complete-Stage {
    param([string]$Stage, [string]$FinalVerdict, [string]$FinalReason, [object]$Inputs, [string[]]$Boundaries)
    $finished = Get-Date
    $evidencePath = ''
    if (Get-Text $EvidenceDir) {
        if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null }
        $evidencePath = Join-Path $EvidenceDir ($Stage.ToLowerInvariant() + '-' + $Started.ToString('yyyyMMdd-HHmmss') + '.json')
        $record = [ordered]@{
            suite = $Stage
            verdict = $FinalVerdict
            reason = $FinalReason
            startedAt = $Started.ToUniversalTime().ToString('o')
            finishedAt = $finished.ToUniversalTime().ToString('o')
            inputs = $Inputs
            checks = @($Checks)
            details = $Details
            boundaries = @($Boundaries)
        }
        ($record | ConvertTo-Json -Depth 12) | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    }
    Write-Output ($Stage + ' verdict=' + $FinalVerdict)
    if ($FinalReason) { Write-Output ($Stage + ' reason=' + $FinalReason) }
    if ($evidencePath) { Write-Output ($Stage + ' evidence=' + $evidencePath) }
    if ($FinalVerdict -eq 'PASS') { exit $ExitPass }
    if ($FinalVerdict -eq 'BLOCKED') { exit $ExitBlocked }
    exit $ExitFail
}

function Invoke-OrHttp([string]$Method, [string]$Path, [string]$Bearer, [object]$Body, [string]$ApiKey) {
    $headers = @{}
    if (Get-Text $ApiKey) {
        $headers['X-API-Key'] = $ApiKey
    } elseif (Get-Text $Bearer) {
        $headers['Authorization'] = ('Bearer ' + $Bearer)
    }
    return (Invoke-StageHttp -Method $Method -Url ($OntologyBase.TrimEnd('/') + '/onto' + $Path) -Headers $headers -Body $Body)
}

function Set-ProbeBinding([string]$Key) {
    $body = [ordered]@{
        spaceId = $SpaceId
        platformTenant = $PlatformTenant
        appCode = $ProbeAppCode
        environment = $Environment
        releaseId = $ReleaseId
    }
    $response = Invoke-OrHttp 'POST' '/internal/release-bindings' '' $body $Key
    return (Test-Envelope $response @(200) 'OK')
}

# ---- 前置输入 ----
$AdminBearerValue = Resolve-StageKey $OntologyAdminBearer $AdminBearerEnv $CredentialsFile 'BEARER'
$ReadKeyValue = Resolve-StageKey $ReadKey $ReadKeyEnv $CredentialsFile 'READ'
$Missing = @()
if (-not (Get-Text $SpaceId)) { $Missing += '-SpaceId' } elseif ($SpaceId -notmatch $UuidPattern) { $Missing += '-SpaceId(需UUID)' }
if (-not (Get-Text $SpaceCode)) { $Missing += '-SpaceCode' } elseif ($SpaceCode -notmatch $CodePattern) { $Missing += '-SpaceCode(形状非法)' }
if (-not (Get-Text $ReleaseId)) { $Missing += '-ReleaseId' } elseif ($ReleaseId -notmatch $UuidPattern) { $Missing += '-ReleaseId(需UUID)' }
if (-not (Get-Text $OpCode)) { $Missing += '-OpCode(消费链探针操作)' }
if (-not $AdminBearerValue) { $Missing += 'OR 人工登录 token(-OntologyAdminBearer 或 ' + $AdminBearerEnv + ')' }
if (-not $ReadKeyValue) { $Missing += '主线只读凭据(-ReadKey 或 ' + $ReadKeyEnv + ')' } elseif ($ReadKeyValue -notmatch $ServiceKeyPattern) { $Missing += '主线只读凭据(需 ontosa_ 前缀加 43 位)' }
if (-not (Get-Text $RevocationBinderKey)) { $Missing += '-RevocationBinderKey(manifest.revocationFixtures.binderKeyRef)' } elseif ($RevocationBinderKey -notmatch $ServiceKeyPattern) { $Missing += '-RevocationBinderKey(需 ontosa_ 前缀加 43 位)' }
if (-not (Get-Text $RevocationBinderCredentialId)) { $Missing += '-RevocationBinderCredentialId' } elseif ($RevocationBinderCredentialId -notmatch $UuidPattern) { $Missing += '-RevocationBinderCredentialId(需UUID)' }
if (-not (Get-Text $RevocationBinderPrincipalId)) { $Missing += '-RevocationBinderPrincipalId' } elseif ($RevocationBinderPrincipalId -notmatch $UuidPattern) { $Missing += '-RevocationBinderPrincipalId(需UUID)' }
if (-not (Get-Text $RevocationConsumerKey)) { $Missing += '-RevocationConsumerKey(manifest.revocationFixtures.consumerKeyRef)' } elseif ($RevocationConsumerKey -notmatch $ConsumerKeyPattern) { $Missing += '-RevocationConsumerKey(需 ontopa_ 前缀加 43 位)' }
if (-not (Get-Text $RevocationConsumerCredentialId)) { $Missing += '-RevocationConsumerCredentialId' } elseif ($RevocationConsumerCredentialId -notmatch $UuidPattern) { $Missing += '-RevocationConsumerCredentialId(需UUID)' }
if (-not $DestructiveApproved) { $Missing += '-DestructiveApproved(破坏性撤权必须显式批准)' }
if ($OntologyBase -notmatch '^https?://[^/]+$') { $Missing += '-OntologyBase(需 scheme+host:port，不含路径)' }

# 探针 appCode 只有一个来源：必须写回 $ProbeAppCode（Set-ProbeBinding 与 stageInputs 都读它）。
# 旧实现只赋值给 $ProbeApp，发送绑定请求时 appCode 是空串，复审 P1-2。
$ProbeAppCode = Get-Text $ProbeAppCode
if (-not $ProbeAppCode) {
    $seed = (Get-Text $SessionId) -replace '[^A-Za-z0-9]', ''
    if (-not $seed) { $seed = $Started.ToString('yyyyMMddHHmmss') }
    if ($seed.Length -gt 20) { $seed = $seed.Substring(0, 20) }
    $ProbeAppCode = ('joint_j7_probe_' + $seed).ToLowerInvariant()
}
if ($ProbeAppCode -notmatch $CodePattern) { $ProbeAppCode = 'joint_j7_probe' }

$stageInputs = [ordered]@{
    ontologyBase = $OntologyBase
    platformTenant = $PlatformTenant
    environment = $Environment
    spaceId = (Get-Text $SpaceId)
    spaceCode = (Get-Text $SpaceCode)
    releaseId = (Get-Text $ReleaseId)
    opCode = (Get-Text $OpCode)
    probeAppCode = $ProbeAppCode
    binderCredentialId = (Get-Text $RevocationBinderCredentialId)
    binderPrincipalId = (Get-Text $RevocationBinderPrincipalId)
    consumerCredentialId = (Get-Text $RevocationConsumerCredentialId)
    adminBearerProvided = [bool]$AdminBearerValue
    destructiveApproved = [bool]$DestructiveApproved
    permissionWaitSeconds = $PermissionWaitSeconds
    sessionId = (Get-Text $SessionId)
}

$stageBoundaries = @(
    'J7 只操作 manifest.revocationFixtures 登记的临时主体；主 readonly/binder/consumer 永不撤销，仅在本段末尾确认主只读 key 仍可用。',
    'OR 鉴权即时生效；AP 侧绑定解析有 30s 正缓存。本段只记录 OR 侧撤权结果，不宣称 AP 读缓存在窗口内立即失效。',
    '临时 binder 主体被撤角色/撤凭据后不可恢复；这是 J7 的预期代价，manifest 主凭据保留给 J8/J9。'
)

if ($Missing.Count -gt 0) {
    Write-Output '[BLOCKED] J7 前置未满足，未发出任何请求：'
    foreach ($Item in $Missing) { Write-Output ('  - ' + $Item) }
    Complete-Stage 'J7' 'BLOCKED' ('缺外部输入: ' + ($Missing -join '; ')) $stageInputs $stageBoundaries
}

# ---- 1) 临时 binder 基线写 ----
if ($Verdict -eq 'PASS') {
    $baseline = Set-ProbeBinding $RevocationBinderKey
    Add-Check 'J7-临时binder基线写' $baseline.Ok ('HTTP ' + $baseline.Status + ' code=' + $baseline.Code + ' changed=' + (Get-Text $baseline.Data.changed))
    $Details['baselineWrite'] = [ordered]@{ httpStatus = $baseline.Status; code = $baseline.Code; changed = (Get-Text $baseline.Data.changed); bindingId = (Get-Text $baseline.Data.bindingId) }
    if (-not $baseline.Ok) {
        $Verdict = 'FAIL'
        $Reason = '临时 binder 基线写绑定失败（临时组没有 release.bind）：HTTP ' + $baseline.Status + ' ' + $baseline.Code
    }
}

# ---- 2) grace=0 轮换 ----
$rotatedKey = ''
if ($Verdict -eq 'PASS') {
    $rotateResponse = Invoke-OrHttp 'POST' ('/platform-actors/' + $RevocationBinderCredentialId + '/rotate') $AdminBearerValue ([ordered]@{ graceSeconds = 0 })
    $rotateEnvelope = Test-Envelope $rotateResponse @(200) 'OK'
    Add-Check 'J7-轮换grace0' $rotateEnvelope.Ok ('HTTP ' + $rotateEnvelope.Status + ' code=' + $rotateEnvelope.Code + ' ' + $rotateEnvelope.Message)
    if ($rotateEnvelope.Ok) {
        $rotatedKey = Get-Text $rotateEnvelope.Data.key
        Add-Check 'J7-轮换返回新key' ($rotatedKey -match $ServiceKeyPattern) ('新key形状=' + [bool]($rotatedKey -match $ServiceKeyPattern))
        Add-Check 'J7-轮换未复用旧key' ($rotatedKey -cne $RevocationBinderKey) '新key必须不同于旧key'
        $Details['rotate'] = [ordered]@{ httpStatus = $rotateEnvelope.Status; code = $rotateEnvelope.Code; graceSeconds = 0; newKeyProvided = [bool]$rotatedKey }
        if (-not ($rotatedKey -match $ServiceKeyPattern)) { $Verdict = 'FAIL'; $Reason = '轮换未返回可用新 key' }
    } else {
        $Verdict = 'FAIL'
        $Reason = '轮换失败：HTTP ' + $rotateEnvelope.Status + ' ' + $rotateEnvelope.Code
    }
}

# ---- 3) 旧 key 必须 401 ----
if ($Verdict -eq 'PASS') {
    $oldKeyResponse = Set-ProbeBinding $RevocationBinderKey
    Add-Check 'J7-旧key被拒' (($oldKeyResponse.Status -eq 401) -and ($oldKeyResponse.Code -ceq 'UNAUTHORIZED')) ('HTTP ' + $oldKeyResponse.Status + ' code=' + $oldKeyResponse.Code + ' ' + $oldKeyResponse.Message)
    $Details['oldKeyRejected'] = [ordered]@{ httpStatus = $oldKeyResponse.Status; code = $oldKeyResponse.Code }
    if (-not (($oldKeyResponse.Status -eq 401) -and ($oldKeyResponse.Code -ceq 'UNAUTHORIZED'))) {
        $Verdict = 'FAIL'
        $Reason = 'grace=0 后旧 key 仍可用：HTTP ' + $oldKeyResponse.Status + ' ' + $oldKeyResponse.Code
    }
}

# ---- 4) 新 key 可用 ----
if ($Verdict -eq 'PASS') {
    $newKeyResponse = Set-ProbeBinding $rotatedKey
    Add-Check 'J7-新key可用' $newKeyResponse.Ok ('HTTP ' + $newKeyResponse.Status + ' code=' + $newKeyResponse.Code)
    $Details['newKeyWrite'] = [ordered]@{ httpStatus = $newKeyResponse.Status; code = $newKeyResponse.Code; changed = (Get-Text $newKeyResponse.Data.changed); bindingId = (Get-Text $newKeyResponse.Data.bindingId) }
    if (-not $newKeyResponse.Ok) {
        $Verdict = 'FAIL'
        $Reason = '轮换后的新 key 不可用：HTTP ' + $newKeyResponse.Status + ' ' + $newKeyResponse.Code
    }
}

# ---- 5) 撤 SERVICE_ACCOUNT_BINDER 角色 ----
if ($Verdict -eq 'PASS') {
    $usersResponse = Invoke-OrHttp 'GET' '/system/users' $AdminBearerValue $null ''
    $usersEnvelope = Test-Envelope $usersResponse @(200) 'OK'
    Add-Check 'J7-读取系统用户' $usersEnvelope.Ok ('HTTP ' + $usersEnvelope.Status + ' code=' + $usersEnvelope.Code)
    $user = $null
    if ($usersEnvelope.Ok) {
        $user = @($usersEnvelope.Data | Where-Object { (Get-Text $_.id) -eq $RevocationBinderPrincipalId }) | Select-Object -First 1
        Add-Check 'J7-定位临时主体' ($null -ne $user) ('principalId=' + $RevocationBinderPrincipalId)
    }
    if ($null -ne $user) {
        $rolesResponse = Invoke-OrHttp 'GET' '/system/roles' $AdminBearerValue $null ''
        $rolesEnvelope = Test-Envelope $rolesResponse @(200) 'OK'
        $binderRole = $null
        if ($rolesEnvelope.Ok) {
            $binderRole = @($rolesEnvelope.Data | Where-Object { (Get-Text $_.code) -ceq 'SERVICE_ACCOUNT_BINDER' }) | Select-Object -First 1
        }
        Add-Check 'J7-定位BINDER角色' ($null -ne $binderRole) ('role=' + $(if ($null -ne $binderRole) { Get-Text $binderRole.code } else { '<missing>' }))
        if ($null -ne $binderRole) {
            $remaining = @($user.roles | ForEach-Object { Get-Text $_.id } | Where-Object { $_ -and ($_ -ne (Get-Text $binderRole.id)) })
            $updateBody = [ordered]@{
                displayName = Get-Text $user.displayName
                status = Get-Text $user.status
                roleIds = @($remaining)
                expectedRevision = $user.revision
            }
            $updateResponse = Invoke-OrHttp 'PUT' ('/system/users/' + (Get-Text $user.id)) $AdminBearerValue $updateBody ''
            $updateEnvelope = Test-Envelope $updateResponse @(200) 'OK'
            Add-Check 'J7-撤binder角色' $updateEnvelope.Ok ('HTTP ' + $updateEnvelope.Status + ' code=' + $updateEnvelope.Code + ' 剩余角色=' + $remaining.Count)
            $Details['revokeRole'] = [ordered]@{ httpStatus = $updateEnvelope.Status; code = $updateEnvelope.Code; role = (Get-Text $binderRole.code); remainingRoles = @($remaining); revision = (Get-Text $updateEnvelope.Data.revision) }
            if (-not $updateEnvelope.Ok) {
                $Verdict = 'FAIL'
                $Reason = '撤 binder 角色失败：HTTP ' + $updateEnvelope.Status + ' ' + $updateEnvelope.Code
            }
        } else {
            $Verdict = 'FAIL'
            $Reason = '系统角色列表缺少 SERVICE_ACCOUNT_BINDER，无法撤角色'
        }
    }
}

# ---- 5b) 撤角色后的写必须 403 NO_PERMISSION（有界重试） ----
if ($Verdict -eq 'PASS') {
    $deadline = (Get-Date).AddSeconds($PermissionWaitSeconds)
    $denied = $null
    $attempts = 0
    while ($true) {
        $attempts = $attempts + 1
        $denied = Set-ProbeBinding $rotatedKey
        if (($denied.Status -eq 403) -and ($denied.Code -ceq 'NO_PERMISSION')) { break }
        if ((Get-Date) -ge $deadline) { break }
        Start-Sleep -Seconds 1
    }
    Add-Check 'J7-撤角色后写403' (($denied.Status -eq 403) -and ($denied.Code -ceq 'NO_PERMISSION')) ('HTTP ' + $denied.Status + ' code=' + $denied.Code + ' 尝试=' + $attempts + ' ' + $denied.Message)
    $Details['afterRoleRevoke'] = [ordered]@{ httpStatus = $denied.Status; code = $denied.Code; attempts = $attempts; waitedSeconds = $PermissionWaitSeconds }
    if (-not (($denied.Status -eq 403) -and ($denied.Code -ceq 'NO_PERMISSION'))) {
        $Verdict = 'FAIL'
        $Reason = '撤 binder 角色后写绑定没有被拒为 403 NO_PERMISSION：HTTP ' + $denied.Status + ' ' + $denied.Code
    }
}

# ---- 6) 撤临时 binder 凭据 → 401 ----
if ($Verdict -eq 'PASS') {
    $revokeResponse = Invoke-OrHttp 'POST' ('/platform-actors/' + $RevocationBinderCredentialId + '/revoke') $AdminBearerValue $null ''
    $revokeEnvelope = Test-Envelope $revokeResponse @(200) 'OK'
    Add-Check 'J7-撤临时binder凭据' $revokeEnvelope.Ok ('HTTP ' + $revokeEnvelope.Status + ' code=' + $revokeEnvelope.Code)
    $afterRevoke = Set-ProbeBinding $rotatedKey
    Add-Check 'J7-撤凭据后401' (($afterRevoke.Status -eq 401) -and ($afterRevoke.Code -ceq 'UNAUTHORIZED')) ('HTTP ' + $afterRevoke.Status + ' code=' + $afterRevoke.Code)
    $Details['revokeCredential'] = [ordered]@{ revokeHttpStatus = $revokeEnvelope.Status; revokeCode = $revokeEnvelope.Code; afterHttpStatus = $afterRevoke.Status; afterCode = $afterRevoke.Code }
    if (-not (($afterRevoke.Status -eq 401) -and ($afterRevoke.Code -ceq 'UNAUTHORIZED'))) {
        $Verdict = 'FAIL'
        $Reason = '撤临时 binder 凭据后仍可写：HTTP ' + $afterRevoke.Status + ' ' + $afterRevoke.Code
    }
}

# ---- 7) 撤临时消费凭据 → 401 ----
if ($Verdict -eq 'PASS') {
    $consumerRevoke = Invoke-OrHttp 'POST' ('/platform-actors/' + $RevocationConsumerCredentialId + '/revoke') $AdminBearerValue $null ''
    $consumerEnvelope = Test-Envelope $consumerRevoke @(200) 'OK'
    Add-Check 'J7-撤临时消费凭据' $consumerEnvelope.Ok ('HTTP ' + $consumerEnvelope.Status + ' code=' + $consumerEnvelope.Code)
    $opsUrl = '/onto/r/' + $SpaceCode + '/releases/' + $ReleaseId + '/ops/' + $OpCode
    $opsResponse = Invoke-StageHttp -Method 'POST' -Url ($OntologyBase.TrimEnd('/') + $opsUrl) -Headers @{ 'X-API-Key' = $RevocationConsumerKey } -Body ([ordered]@{})
    Add-Check 'J7-撤消费凭据后401' (($opsResponse.Status -eq 401) -and ((Get-Text $opsResponse.Body.code) -ceq 'UNAUTHORIZED')) ('HTTP ' + $opsResponse.Status + ' code=' + (Get-Text $opsResponse.Body.code))
    $Details['revokeConsumer'] = [ordered]@{ revokeHttpStatus = $consumerEnvelope.Status; revokeCode = $consumerEnvelope.Code; opsHttpStatus = $opsResponse.Status; opsCode = (Get-Text $opsResponse.Body.code) }
    if (-not (($opsResponse.Status -eq 401) -and ((Get-Text $opsResponse.Body.code) -ceq 'UNAUTHORIZED'))) {
        $Verdict = 'FAIL'
        $Reason = '撤临时消费凭据后消费链未被拒为 401：HTTP ' + $opsResponse.Status + ' ' + (Get-Text $opsResponse.Body.code)
    }
}

# ---- 8) 主只读凭据不受影响 ----
if ($Verdict -eq 'PASS') {
    $manifestResponse = Invoke-StageHttp -Method 'GET' -Url ($OntologyBase.TrimEnd('/') + '/onto/releases/' + $ReleaseId + '/manifest') -Headers @{ 'X-API-Key' = $ReadKeyValue }
    $manifestEnvelope = Test-Envelope $manifestResponse @(200) 'OK'
    Add-Check 'J7-主只读key仍可用' $manifestEnvelope.Ok ('HTTP ' + $manifestEnvelope.Status + ' code=' + $manifestEnvelope.Code)
    $Details['mainReadKey'] = [ordered]@{ httpStatus = $manifestEnvelope.Status; code = $manifestEnvelope.Code }
    if (-not $manifestEnvelope.Ok) {
        $Verdict = 'FAIL'
        $Reason = '主线只读凭据受影响：HTTP ' + $manifestEnvelope.Status + ' ' + $manifestEnvelope.Code
    }
}

if ($Verdict -eq 'PASS' -and $script:FirstFailure) {
    $Verdict = 'FAIL'
    $Reason = $script:FirstFailure
}

Complete-Stage 'J7' $Verdict $Reason $stageInputs $stageBoundaries
