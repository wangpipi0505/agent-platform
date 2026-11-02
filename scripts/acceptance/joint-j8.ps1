<#
.SYNOPSIS
  EX-07 J8 租户/空间隔离：用 A 空间的 binder 凭据去写/读 B 空间的绑定与操作，
  断言被稳定拒绝、无泄漏、B 空间无新增绑定，且伪造 X-User-* 头不能扩大权限。

.DESCRIPTION
  全部使用 OR 既有边界（凭据空间由服务端从凭据行解析，body.spaceId 只是复述）：
    1) A 凭据 POST 绑定 body.spaceId=B 空间 + B release → 403 NO_PERMISSION（跨空间），
       随后用人工 Bearer 读 B release 的生效绑定，断言没有新行（无新绑定）；
    2) A 凭据 POST 绑定 body.spaceId=A 空间 + B release → 403 NO_PERMISSION（版本不属于该空间）；
    3) A 凭据 GET 绑定查询带 platformTenant=其它租户 → 403 NO_PERMISSION（不泄漏他人绑定）；
    4) 第 1 步重放并附加 X-User-Id/X-User-Roles/X-Tenant 伪造头 → 仍 403 NO_PERMISSION（不得扩权）；
    5) A 凭据 POST /onto/internal/releases/{B release}/ops/{op} → 403/404 的稳定业务码，且无 data；
    6) 结束读回 A 空间原绑定，bindingId/releaseId 未变。

  缺输入 → [BLOCKED] 退出码 3（不发请求）；断言失败 → 1；通过 → 0。
  证据只记录状态码/业务码/ID，不记录 key/token 原文。

.PARAMETER IsolationSpaceId / IsolationReleaseId / IsolationOpCode
  manifest.isolationFixtures 的本轮 B 空间真实 release/endpoint 操作（同一 session 登记）。
  没有真实 B 空间资源就没有可验证的隔离面 → BLOCKED，不得用 A 空间自证。

.EXAMPLE
  ./joint-j8.ps1 -SpaceId <A uuid> -ReleaseId <A uuid> -AppCode joint_xxx_resolve `
      -IsolationSpaceId <B uuid> -IsolationReleaseId <B uuid> -IsolationOpCode joint_list `
      -OntologyAdminBearer <human token>
#>
[CmdletBinding()]
param(
    [string]$OntologyBase = 'http://127.0.0.1:18182',
    [string]$PlatformTenant = 'local-v12',
    [string]$Environment = 'production',
    [string]$SpaceId,
    [string]$ReleaseId,
    [string]$AppCode,
    [string]$IsolationSpaceId,
    [string]$IsolationReleaseId,
    [string]$IsolationOpCode,
    # 第二合法身份（B 空间 binder 服务凭据）：有则证“合法身份跨空间仍被拒”，
    # 没有则记 SKIPPED-BLOCKED，明确缺什么，不用伪造身份头替代（EX-06 复审 J8）。
    [string]$IsolationBinderKey,
    [string]$IsolationBinderKeyEnv = 'AGENT_SECRET_JOINT_ISOLATION_BINDER_KEY',
    [string]$IsolationAppCode,
    [string]$BinderKey,
    [string]$BinderKeyEnv = 'AGENT_SECRET_ONTO_APP_BINDER_KEY',
    [string]$OntologyAdminBearer,
    [string]$AdminBearerEnv = 'AGENT_ACCEPTANCE_OR_BEARER',
    [string]$CredentialsFile,
    [string]$ProbeAppCode,
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
$CodePattern = '^[A-Za-z][A-Za-z0-9_-]{0,79}$'
$OpPattern = '^[A-Za-z][A-Za-z0-9_-]{0,63}$'
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

function Get-OrBinding([string]$Code, [string]$Tenant, [hashtable]$ExtraHeaders, [string]$ApiKey) {
    $query = '?platformTenant=' + [Uri]::EscapeDataString($Tenant) +
        '&appCode=' + [Uri]::EscapeDataString($Code) +
        '&environment=' + [Uri]::EscapeDataString($Environment)
    $key = $BinderKeyValue
    if (Get-Text $ApiKey) { $key = Get-Text $ApiKey }
    $headers = @{ 'X-API-Key' = $key }
    if ($ExtraHeaders) { foreach ($name in $ExtraHeaders.Keys) { $headers[$name] = $ExtraHeaders[$name] } }
    $response = Invoke-StageHttp -Method 'GET' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/release-bindings' + $query) -Headers $headers
    return (Test-Envelope $response @(200) 'OK')
}

function Set-OrBinding([string]$TargetSpace, [string]$TargetRelease, [string]$Code, [hashtable]$ExtraHeaders, [string]$ApiKey) {
    $body = [ordered]@{
        spaceId = $TargetSpace
        platformTenant = $PlatformTenant
        appCode = $Code
        environment = $Environment
        releaseId = $TargetRelease
    }
    $key = $BinderKeyValue
    if (Get-Text $ApiKey) { $key = Get-Text $ApiKey }
    $headers = @{ 'X-API-Key' = $key }
    if ($ExtraHeaders) { foreach ($name in $ExtraHeaders.Keys) { $headers[$name] = $ExtraHeaders[$name] } }
    $response = Invoke-StageHttp -Method 'POST' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/release-bindings') -Headers $headers -Body $body
    return (Test-Envelope $response @(200, 401, 403, 404) 'NO_PERMISSION')
}

# ---- 前置输入 ----
$BinderKeyValue = Resolve-StageKey $BinderKey $BinderKeyEnv $CredentialsFile 'BINDER'
# 第二合法身份只从显式参数/专用环境变量读取：credentials.env 可能有多个 *_BINDER，
# 按后缀猜会误用主 binder，因此这里不读凭据文件。
$IsolationBinderKeyValue = Get-Text $IsolationBinderKey
if (-not $IsolationBinderKeyValue -and (Get-Text $IsolationBinderKeyEnv)) { $IsolationBinderKeyValue = Get-Text ([Environment]::GetEnvironmentVariable($IsolationBinderKeyEnv)) }
$AdminBearerValue = Resolve-StageKey $OntologyAdminBearer $AdminBearerEnv $CredentialsFile 'BEARER'
$Missing = @()
foreach ($pair in @(
        @{ Name = '-SpaceId'; Value = $SpaceId },
        @{ Name = '-ReleaseId'; Value = $ReleaseId },
        @{ Name = '-IsolationSpaceId'; Value = $IsolationSpaceId },
        @{ Name = '-IsolationReleaseId'; Value = $IsolationReleaseId })) {
    if (-not (Get-Text $pair.Value)) { $Missing += $pair.Name } elseif ($pair.Value -notmatch $UuidPattern) { $Missing += ($pair.Name + '(需UUID)') }
}
if ((Get-Text $IsolationSpaceId) -and ((Get-Text $IsolationSpaceId) -eq (Get-Text $SpaceId))) { $Missing += '-IsolationSpaceId(必须不同于-SpaceId)' }
if ((Get-Text $IsolationReleaseId) -and ((Get-Text $IsolationReleaseId) -eq (Get-Text $ReleaseId))) { $Missing += '-IsolationReleaseId(必须不同于-ReleaseId)' }
if (-not (Get-Text $AppCode)) { $Missing += '-AppCode(A空间绑定解析应用)' }
if (-not (Get-Text $IsolationOpCode)) { $Missing += '-IsolationOpCode' } elseif ($IsolationOpCode -notmatch $OpPattern) { $Missing += '-IsolationOpCode(形状非法)' }
if (-not $BinderKeyValue) { $Missing += 'binder凭据(-BinderKey 或 ' + $BinderKeyEnv + ')' } elseif ($BinderKeyValue -notmatch $ServiceKeyPattern) { $Missing += 'binder凭据(需 ontosa_ 前缀加 43 位)' }
if ($IsolationBinderKeyValue -and $IsolationBinderKeyValue -notmatch $ServiceKeyPattern) { $Missing += '-IsolationBinderKey(需 ontosa_ 前缀加 43 位)' }
if (-not $AdminBearerValue) { $Missing += 'OR 人工登录 token(-OntologyAdminBearer 或 ' + $AdminBearerEnv + ')' }
if ($OntologyBase -notmatch '^https?://[^/]+$') { $Missing += '-OntologyBase(需 scheme+host:port，不含路径)' }

$ProbeApp = Get-Text $ProbeAppCode
if (-not $ProbeApp) {
    $seed = (Get-Text $SessionId) -replace '[^A-Za-z0-9]', ''
    if (-not $seed) { $seed = $Started.ToString('yyyyMMddHHmmss') }
    if ($seed.Length -gt 20) { $seed = $seed.Substring(0, 20) }
    $ProbeApp = ('joint_j8_probe_' + $seed).ToLowerInvariant()
}
if ($ProbeApp -notmatch $CodePattern) { $ProbeApp = 'joint_j8_probe' }
# B 空间合法身份写入的探针 appCode 必须与 A 空间探针分开，否则“B 空间无新增绑定”会把合法行算成泄漏。
$IsolationProbeApp = Get-Text $IsolationAppCode
if (-not $IsolationProbeApp) { $IsolationProbeApp = ($ProbeApp -creplace '^joint_j8_probe','joint_j8_iso') }
if ($IsolationProbeApp -notmatch $CodePattern) { $IsolationProbeApp = 'joint_j8_iso' }

$stageInputs = [ordered]@{
    ontologyBase = $OntologyBase
    platformTenant = $PlatformTenant
    environment = $Environment
    spaceId = (Get-Text $SpaceId)
    releaseId = (Get-Text $ReleaseId)
    appCode = (Get-Text $AppCode)
    isolationSpaceId = (Get-Text $IsolationSpaceId)
    isolationReleaseId = (Get-Text $IsolationReleaseId)
    isolationOpCode = (Get-Text $IsolationOpCode)
    probeAppCode = $ProbeApp
    isolationProbeAppCode = $IsolationProbeApp
    isolationBinderKeyProvided = [bool]$IsolationBinderKeyValue
    binderKeyProvided = [bool]$BinderKeyValue
    adminBearerProvided = [bool]$AdminBearerValue
    sessionId = (Get-Text $SessionId)
}

$stageBoundaries = @(
    'J8 的隔离面来自本轮登记的 isolationFixtures（B 空间真实 release/endpoint）；没有真实 B 空间资源时本段 BLOCKED，不用 A 空间自证。',
    'OR 从凭据行解析空间/租户，body.spaceId 只是复述；因此跨空间/跨租户负例不需要第二个租户的登录身份，也不需要伪造合法身份头。',
    '本段只证明“A 凭据对 B 空间写/读/执行被拒且无新增绑定”，不证明 B 空间自身业务可用。'
    '额外的第二合法身份腿（-IsolationBinderKey）只证明“在 B 空间合法的 binder 身份跨到 A 空间仍被拒”；缺该输入时如实记 SKIPPED-BLOCKED，不影响 A 凭据负例。'
)

if ($Missing.Count -gt 0) {
    Write-Output '[BLOCKED] J8 前置未满足，未发出任何请求：'
    foreach ($Item in $Missing) { Write-Output ('  - ' + $Item) }
    Complete-Stage 'J8' 'BLOCKED' ('缺外部输入: ' + ($Missing -join '; ')) $stageInputs $stageBoundaries
}

# ---- 1) A 空间基线绑定 ----
$baselineBindingId = ''
$baselineReleaseId = ''
if ($Verdict -eq 'PASS') {
    $baseline = Get-OrBinding $AppCode $PlatformTenant $null
    Add-Check 'J8-A空间基线绑定' $baseline.Ok ('HTTP ' + $baseline.Status + ' code=' + $baseline.Code)
    if ($baseline.Ok) {
        $baselineBindingId = Get-Text $baseline.Data.bindingId
        $baselineReleaseId = Get-Text $baseline.Data.releaseId
        Add-Check 'J8-A空间基线bindingId' ($baselineBindingId -match $UuidPattern) ('bindingId=' + $baselineBindingId)
        $Details['baseline'] = [ordered]@{ bindingId = $baselineBindingId; releaseId = $baselineReleaseId }
    } else {
        $Verdict = 'BLOCKED'
        $Reason = 'A 空间没有可对照的基线绑定（HTTP ' + $baseline.Status + ' ' + $baseline.Code + '）；J2/J5 应先建立绑定'
    }
}

# ---- 2) 跨空间写绑定必须 403 ----
if ($Verdict -eq 'PASS') {
    $crossSpace = Set-OrBinding $IsolationSpaceId $IsolationReleaseId $ProbeApp $null
    Add-Check 'J8-跨空间写绑定403' (($crossSpace.Status -eq 403) -and ($crossSpace.Code -ceq 'NO_PERMISSION')) ('HTTP ' + $crossSpace.Status + ' code=' + $crossSpace.Code + ' ' + $crossSpace.Message)
    $Details['crossSpaceBind'] = [ordered]@{ httpStatus = $crossSpace.Status; code = $crossSpace.Code; message = $crossSpace.Message; spaceId = $IsolationSpaceId }
    if (-not (($crossSpace.Status -eq 403) -and ($crossSpace.Code -ceq 'NO_PERMISSION'))) {
        $Verdict = 'FAIL'
        $Reason = '跨空间写绑定未被拒为 403 NO_PERMISSION：HTTP ' + $crossSpace.Status + ' ' + $crossSpace.Code
    }
}

# ---- 3) B 空间不得出现新绑定（人工 Bearer 读 B release 的生效绑定） ----
if ($Verdict -eq 'PASS') {
    $bBindings = Invoke-StageHttp -Method 'GET' -Url ($OntologyBase.TrimEnd('/') + '/onto/releases/' + $IsolationReleaseId + '/bindings') -Headers @{ Authorization = ('Bearer ' + $AdminBearerValue) }
    $bEnvelope = Test-Envelope $bBindings @(200) 'OK'
    Add-Check 'J8-读B空间绑定' $bEnvelope.Ok ('HTTP ' + $bEnvelope.Status + ' code=' + $bEnvelope.Code)
    if ($bEnvelope.Ok) {
        $rows = @($bEnvelope.Data)
        $leaked = @($rows | Where-Object { (Get-Text $_.appCode) -ceq $ProbeApp })
        Add-Check 'J8-B空间无新增绑定' ($leaked.Count -eq 0) ('B绑定数=' + $rows.Count + ' 探针appCode命中=' + $leaked.Count)
        $Details['isolationBindings'] = [ordered]@{ httpStatus = $bEnvelope.Status; count = $rows.Count; probeAppCodeHits = $leaked.Count }
        if ($leaked.Count -ne 0) { $Verdict = 'FAIL'; $Reason = 'B 空间出现了探针 appCode 的新绑定' }
    } else {
        $Verdict = 'FAIL'
        $Reason = '无法读取 B 空间绑定以证明“无新绑定”：HTTP ' + $bEnvelope.Status + ' ' + $bEnvelope.Code
    }
}

# ---- 4) A 空间 + B release 必须 403 ----
if ($Verdict -eq 'PASS') {
    $crossRelease = Set-OrBinding $SpaceId $IsolationReleaseId $ProbeApp $null
    Add-Check 'J8-跨空间release403' (($crossRelease.Status -eq 403) -and ($crossRelease.Code -ceq 'NO_PERMISSION')) ('HTTP ' + $crossRelease.Status + ' code=' + $crossRelease.Code + ' ' + $crossRelease.Message)
    $Details['crossReleaseBind'] = [ordered]@{ httpStatus = $crossRelease.Status; code = $crossRelease.Code; message = $crossRelease.Message }
    if (-not (($crossRelease.Status -eq 403) -and ($crossRelease.Code -ceq 'NO_PERMISSION'))) {
        $Verdict = 'FAIL'
        $Reason = 'A 空间引用 B release 未被拒为 403 NO_PERMISSION：HTTP ' + $crossRelease.Status + ' ' + $crossRelease.Code
    }
}

# ---- 5) 跨租户读不泄漏 ----
if ($Verdict -eq 'PASS') {
    $otherTenant = 'joint-other-tenant'
    $crossTenant = Get-OrBinding $AppCode $otherTenant $null
    Add-Check 'J8-跨租户读403' (($crossTenant.Status -eq 403) -and ($crossTenant.Code -ceq 'NO_PERMISSION')) ('HTTP ' + $crossTenant.Status + ' code=' + $crossTenant.Code + ' ' + $crossTenant.Message)
    $Details['crossTenantRead'] = [ordered]@{ httpStatus = $crossTenant.Status; code = $crossTenant.Code; message = $crossTenant.Message; tenant = $otherTenant; dataLeaked = ($null -ne $crossTenant.Data) }
    if (-not (($crossTenant.Status -eq 403) -and ($crossTenant.Code -ceq 'NO_PERMISSION'))) {
        $Verdict = 'FAIL'
        $Reason = '跨租户读绑定未被拒为 403 NO_PERMISSION：HTTP ' + $crossTenant.Status + ' ' + $crossTenant.Code
    }
}

# ---- 6) 伪造身份头不得扩权 ----
if ($Verdict -eq 'PASS') {
    $forged = Set-OrBinding $IsolationSpaceId $IsolationReleaseId $ProbeApp @{ 'X-User-Id' = 'admin'; 'X-User-Roles' = 'SYSTEM_ADMIN,OWNER'; 'X-Tenant' = $PlatformTenant; 'X-User-Principal' = 'admin' }
    Add-Check 'J8-伪造身份头仍403' (($forged.Status -eq 403) -and ($forged.Code -ceq 'NO_PERMISSION')) ('HTTP ' + $forged.Status + ' code=' + $forged.Code)
    $Details['forgedHeaders'] = [ordered]@{ httpStatus = $forged.Status; code = $forged.Code; headers = @('X-User-Id', 'X-User-Roles', 'X-Tenant', 'X-User-Principal') }
    if (-not (($forged.Status -eq 403) -and ($forged.Code -ceq 'NO_PERMISSION'))) {
        $Verdict = 'FAIL'
        $Reason = '伪造身份头改变了鉴权结果：HTTP ' + $forged.Status + ' ' + $forged.Code
    }
}

# ---- 6b) 第二合法身份（B 空间 binder）跨空间仍被拒 ----
if ($Verdict -eq 'PASS') {
    if ($IsolationBinderKeyValue) {
        $ownBind = Set-OrBinding $IsolationSpaceId $IsolationReleaseId $IsolationProbeApp $null $IsolationBinderKeyValue
        $ownOk = ($ownBind.Status -eq 200) -and ($ownBind.Code -ceq 'OK') -and ((Get-Text $ownBind.Data.bindingId) -match $UuidPattern)
        Add-Check 'J8-B空间合法身份绑定' $ownOk ('HTTP ' + $ownBind.Status + ' code=' + $ownBind.Code + ' bindingId=' + (Get-Text $ownBind.Data.bindingId) + ' changed=' + (Get-Text $ownBind.Data.changed))
        if (-not $ownOk) {
            $Verdict = 'FAIL'
            $Reason = 'B 空间合法 binder 无法在本空间写绑定，第二合法身份腿不成立：HTTP ' + $ownBind.Status + ' ' + $ownBind.Code
        } else {
            $Details['legalIdentityOwnBind'] = [ordered]@{ httpStatus = $ownBind.Status; code = $ownBind.Code; bindingId = (Get-Text $ownBind.Data.bindingId); changed = $ownBind.Data.changed }
            # 读绑定按“凭据所属空间”解释：B 空间 key 查询 A 的 appCode 在 B 空间没有 ACTIVE 行，
            # 正确语义是 404 BINDING_NOT_FOUND 且不带数据；403 NO_PERMISSION 只用于显式跨空间/跨租户写路径。
            $legalCrossRead = Get-OrBinding $AppCode $PlatformTenant $null $IsolationBinderKeyValue
            $legalReadOk = ($legalCrossRead.Status -eq 404) -and ($legalCrossRead.Code -ceq 'BINDING_NOT_FOUND') -and ($null -eq $legalCrossRead.Data)
            Add-Check 'J8-合法身份跨读A404' $legalReadOk ('HTTP ' + $legalCrossRead.Status + ' code=' + $legalCrossRead.Code + ' data=' + $(if ($null -eq $legalCrossRead.Data) { '<null>' } else { '<present>' }))
            $Details['legalIdentityCrossRead'] = [ordered]@{ httpStatus = $legalCrossRead.Status; code = $legalCrossRead.Code; dataPresent = ($null -ne $legalCrossRead.Data) }
            if (-not $legalReadOk) { $Verdict = 'FAIL'; $Reason = 'B 空间合法身份跨读 A 空间未按凭据空间语义返回 404 BINDING_NOT_FOUND（或泄漏 data）：HTTP ' + $legalCrossRead.Status + ' ' + $legalCrossRead.Code }
            if ($Verdict -eq 'PASS') {
                $legalCrossWrite = Set-OrBinding $SpaceId $ReleaseId $AppCode $null $IsolationBinderKeyValue
                $legalWriteOk = ($legalCrossWrite.Status -eq 403) -and ($legalCrossWrite.Code -ceq 'NO_PERMISSION')
                Add-Check 'J8-合法身份跨写A403' $legalWriteOk ('HTTP ' + $legalCrossWrite.Status + ' code=' + $legalCrossWrite.Code)
                $Details['legalIdentityCrossWrite'] = [ordered]@{ httpStatus = $legalCrossWrite.Status; code = $legalCrossWrite.Code }
                if (-not $legalWriteOk) { $Verdict = 'FAIL'; $Reason = 'B 空间合法身份跨写 A 空间未被拒为 403 NO_PERMISSION：HTTP ' + $legalCrossWrite.Status + ' ' + $legalCrossWrite.Code }
            }
        }
    } else {
        Write-Output '[SKIPPED-BLOCKED] J8-合法身份跨空间 缺 -IsolationBinderKey（B 空间合法 binder 服务凭据，或 isolationFixtures.foreignBinderKeyRef）：未证“合法身份跨空间仍被拒”，不伪造身份头替代。'
        $Details['legalIdentity'] = [ordered]@{ status = 'SKIPPED-BLOCKED'; missing = '-IsolationBinderKey / isolationFixtures.foreignBinderKeyRef' }
    }
}

# ---- 7) 跨空间执行操作必须被拒且无数据 ----
if ($Verdict -eq 'PASS') {
    # 跨空间执行必须改用“只对 A 空间有权的身份”：人登录管理账号在夹具里同时是 A/B 的 OWNER，
    # 用它打 B 只能证明“端点未授权”（ENDPOINT_FORBIDDEN），证明不了空间隔离（复审 18:0x P1·J8）。
    # 这里改用 A 空间自己的 ontosa_ binder 凭据：服务端按凭据行解析空间=A，再用 B 的 release
    # 调执行入口，正确语义是“稳定业务码拒绝且无 data”，而不是越权成功。
    $opsResponse = Invoke-StageHttp -Method 'POST' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/releases/' + $IsolationReleaseId + '/ops/' + $IsolationOpCode) -Headers @{ 'X-API-Key' = $BinderKeyValue } -Body ([ordered]@{})
    $opsCode = Get-Text $opsResponse.Body.code
    # 三种都是稳定拒绝：跨空间/跨租户写路径 NO_PERMISSION、release 不属于该空间时会解析不到
    # RELEASE_NOT_FOUND、A 空间对该端点没有授予记录时 ENDPOINT_FORBIDDEN；关键是“拒绝 + 无 data”。
    $stableCodes = @('NO_PERMISSION', 'RELEASE_NOT_FOUND', 'ENDPOINT_FORBIDDEN')
    $opsData = $null
    if ($null -ne $opsResponse.Body -and $null -ne $opsResponse.Body.PSObject.Properties['data']) { $opsData = $opsResponse.Body.data }
    Add-Check 'J8-跨空间执行拒绝' ((@(403, 404) -contains $opsResponse.Status) -and ($stableCodes -contains $opsCode)) ('HTTP ' + $opsResponse.Status + ' code=' + $opsCode)
    Add-Check 'J8-跨空间执行无数据' ($null -eq $opsData) ('data=' + (Get-Text $opsData))
    $Details['crossSpaceOps'] = [ordered]@{ httpStatus = $opsResponse.Status; code = $opsCode; dataPresent = ($null -ne $opsData) }
    if (-not ((@(403, 404) -contains $opsResponse.Status) -and ($stableCodes -contains $opsCode))) {
        $Verdict = 'FAIL'
        $Reason = 'A 凭据对 B release 执行操作未被稳定拒绝：HTTP ' + $opsResponse.Status + ' code=' + $opsCode
    }
}

# ---- 7b) 机器凭据不得打人登录面 ----
if ($Verdict -eq 'PASS') {
    $machineOnHuman = Invoke-StageHttp -Method 'POST' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/releases/' + $IsolationReleaseId + '/ops/' + $IsolationOpCode) -Headers @{ 'X-API-Key' = $BinderKeyValue } -Body ([ordered]@{})
    $machineCode = Get-Text $machineOnHuman.Body.code
    # 真实服务在人登录面用机器 key 时回 401 UNAUTHORIZED（鉴权层先拒），只有显式来源校验才回
    # SERVICE_CREDENTIAL_REQUIRED；两者都是“机器凭据不得打人面”的合法表达。
    $machineOk = ($machineOnHuman.Status -eq 401) -and (@('UNAUTHORIZED', 'SERVICE_CREDENTIAL_REQUIRED') -contains $machineCode)
    Add-Check 'J8-机器凭据打人登录面401' $machineOk ('HTTP ' + $machineOnHuman.Status + ' code=' + $machineCode)
    $Details['machineOnHumanSurface'] = [ordered]@{ httpStatus = $machineOnHuman.Status; code = $machineCode }
    if (-not $machineOk) { $Verdict = 'FAIL'; $Reason = '机器凭据打 /onto/internal 未被拒为 401（UNAUTHORIZED/SERVICE_CREDENTIAL_REQUIRED）：HTTP ' + $machineOnHuman.Status + ' ' + $machineCode }
}

# ---- 8) A 空间绑定未变 ----
if ($Verdict -eq 'PASS') {
    $after = Get-OrBinding $AppCode $PlatformTenant $null
    Add-Check 'J8-A空间绑定未变' ($after.Ok -and ((Get-Text $after.Data.bindingId) -eq $baselineBindingId) -and ((Get-Text $after.Data.releaseId) -eq $baselineReleaseId)) ('bindingId=' + (Get-Text $after.Data.bindingId) + ' releaseId=' + (Get-Text $after.Data.releaseId))
    $Details['after'] = [ordered]@{ bindingId = (Get-Text $after.Data.bindingId); releaseId = (Get-Text $after.Data.releaseId) }
    if (-not ($after.Ok -and ((Get-Text $after.Data.bindingId) -eq $baselineBindingId))) {
        $Verdict = 'FAIL'
        $Reason = 'A 空间绑定被负例影响：' + (Get-Text $after.Data.bindingId) + ' != ' + $baselineBindingId
    }
}

if ($Verdict -eq 'PASS' -and $script:FirstFailure) {
    $Verdict = 'FAIL'
    $Reason = $script:FirstFailure
}

Complete-Stage 'J8' $Verdict $Reason $stageInputs $stageBoundaries
