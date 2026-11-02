<#
.SYNOPSIS
  EX-07 J6 解绑/故障边界：真实解绑后按 30s 缓存窗口验证“跳过并写 pinFailure”策略，
  再用故障侧的 AP 验证网关 404/坏响应/5xx 必须变成 503，不能被当成“无绑定”。

.DESCRIPTION
  两条独立腿，缺任一条前提都不执行，直接 BLOCKED（不发请求）：

  J6a 真 404 BINDING_NOT_FOUND（正常 AP）：
    1) GET /onto/internal/release-bindings 读基线绑定；
    2) DELETE /onto/internal/release-bindings/{bindingId} 解绑（binder 服务凭据）；
    3) GET 同查询必须 404 + code=BINDING_NOT_FOUND；
    4) 等缓存窗口（默认 31s，AP 正缓存 TTL 30s）后真实发起一次运行；
    5) 运行必须 HTTP 200 且不是 503；observation.releaseId 必须为空（按策略跳过注入）。
   说明：pinFailure/reason 只写在运行冻结快照里，HTTP 状态接口不返回该字段；
   本段以“运行未被 503 打断 + observation 无 releaseId”作为该策略的可观测证据，并记入边界。

  J6b 故障边界（-FaultPlatformBase 指向一个连不上/返回坏响应的 OR 的 AP 实例）：
    同一次运行请求必须返回 503 + code=CONTEXT_PACK_UNAVAILABLE。
    故障注入发生在环境侧（AP 的 agent.ontology.url 指向故障桩），不在本脚本里伪造成功。

  最后恢复基线绑定（POST 回原 releaseId）并核对 bindingId，避免把环境留在解绑状态。

  缺输入 → [BLOCKED] 退出码 3（不发请求）；断言失败 → 1；通过 → 0。
  证据只记录 ID/哈希/布尔，不记录 key/token 原文。

.PARAMETER FaultPlatformBase
  故障侧 AP 根地址（默认空）。必须指向一个“本体调用会 404(非 BINDING_NOT_FOUND)/坏响应/5xx”的 AP，
  例如把 agent.ontology.url 指到故障桩后启动的 AP。缺该参数即 BLOCKED——不静态伪造故障。

.EXAMPLE
  $env:AGENT_SECRET_ONTO_APP_BINDER_KEY = '<binder ontosa_ key>'
  ./joint-j6.ps1 -SpaceId <uuid> -AppCode joint_xxx_resolve -BearerToken <admin jwt> `
      -FaultPlatformBase http://127.0.0.1:18086
#>
[CmdletBinding()]
param(
    [string]$OntologyBase = 'http://127.0.0.1:18182',
    [string]$PlatformBase = 'http://127.0.0.1:18080',
    [string]$FaultPlatformBase,
    [string]$PlatformTenant = 'local-v12',
    [string]$Environment = 'production',
    [string]$SpaceId,
    [string]$AppCode,
    [string]$BinderKey,
    [string]$BearerToken,
    [string]$BinderKeyEnv = 'AGENT_SECRET_ONTO_APP_BINDER_KEY',
    [string]$BearerTokenEnv = 'AGENT_ACCEPTANCE_BEARER',
    [string]$CredentialsFile,
    [ValidateRange(0, 90)][int]$CacheWaitSeconds = 31,
    [ValidateRange(10, 900)][int]$RunTimeoutSeconds = 180,
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
$Checks = New-Object System.Collections.ArrayList
$Details = [ordered]@{}
$Verdict = 'PASS'
$Reason = ''
$script:FirstFailure = ''

function Get-Text([object]$Value) {
    if ($null -eq $Value) { return '' }
    return ([string]$Value).Trim()
}

function Get-StageJson([object]$Value) {
    if ($null -eq $Value) { return '' }
    try { return ($Value | ConvertTo-Json -Depth 8 -Compress) } catch { return '<unserializable>' }
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
        [int]$Timeout = 300
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

function Wait-StageCache([int]$Seconds, [string]$Label) {
    if ($Seconds -le 0) {
        Write-Output ('[WAIT] ' + $Label + ' 跳过等待（-CacheWaitSeconds 0）')
        return
    }
    Write-Output ('[WAIT] ' + $Label + ' 等待 ' + $Seconds + 's 绑定缓存窗口')
    Start-Sleep -Seconds $Seconds
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

function Get-StageBinding([string]$Code, [string]$Key) {
    $query = '?platformTenant=' + [Uri]::EscapeDataString($PlatformTenant) +
        '&appCode=' + [Uri]::EscapeDataString($Code) +
        '&environment=' + [Uri]::EscapeDataString($Environment)
    $response = Invoke-StageHttp -Method 'GET' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/release-bindings' + $query) -Headers @{ 'X-API-Key' = $Key }
    return (Test-Envelope $response @(200) 'OK')
}

function Invoke-StageRun([string]$Base, [string]$Code, [string]$Label) {
    $result = [pscustomobject]@{ Ok = $false; RunId = ''; RunStatus = ''; HttpStatus = 0; Code = ''; Observation = $null; Error = ''; Data = $null }
    $body = [ordered]@{ inputs = [ordered]@{ message = ('EX06 ' + $Label + ' 绑定缺失探针') } }
    $headers = @{ Authorization = ('Bearer ' + $BearerValue) }
    try {
        $response = Invoke-StageHttp -Method 'POST' -Url ($Base.TrimEnd('/') + '/admin/apps/' + $Code + '/runs') -Headers $headers -Body $body
    } catch {
        $result.Error = '请求异常: ' + $_.Exception.Message
        return $result
    }
    $envelope = Test-Envelope $response @(200, 201) 'OK'
    $result.HttpStatus = $envelope.Status
    $result.Code = $envelope.Code
    $result.Data = $envelope.Data
    if (-not $envelope.Ok) {
        $result.Error = 'HTTP ' + $envelope.Status + ' code=' + $envelope.Code + ' ' + $envelope.Message
        return $result
    }
    $runId = Get-Text $envelope.Data.runId
    if ($runId -notmatch '^r_[a-f0-9]{32}$') {
        $result.Error = 'runId 形状非法: ' + $runId
        return $result
    }
    $result.RunId = $runId
    $deadline = (Get-Date).AddSeconds($RunTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $statusResponse = Invoke-StageHttp -Method 'GET' -Url ($Base.TrimEnd('/') + '/admin/runs/' + $runId) -Headers $headers
        } catch {
            $result.RunStatus = 'UNREADABLE'
            $result.Error = '读取run状态异常: ' + $_.Exception.Message
            return $result
        }
        $statusEnvelope = Test-Envelope $statusResponse @(200) 'OK'
        if (-not $statusEnvelope.Ok) {
            $result.RunStatus = 'UNREADABLE'
            $result.Error = '读取run状态失败: HTTP ' + $statusEnvelope.Status + ' code=' + $statusEnvelope.Code
            return $result
        }
        $status = Get-Text $statusEnvelope.Data.status
        $result.RunStatus = $status
        if (@('SUCCEEDED', 'FAILED', 'CANCELLED') -contains $status) {
            $result.Observation = $statusEnvelope.Data.observation
            $result.Ok = $true
            return $result
        }
        Start-Sleep -Seconds 2
    }
    $result.RunStatus = 'TIMEOUT'
    $result.Error = '运行超时（' + $RunTimeoutSeconds + 's）'
    return $result
}

# ---- 前置输入：缺什么报什么，绝不带半套参数发请求 ----
$BinderKeyValue = Resolve-StageKey $BinderKey $BinderKeyEnv $CredentialsFile 'BINDER'
$BearerValue = Resolve-StageKey $BearerToken $BearerTokenEnv $CredentialsFile 'BEARER'
$Missing = @()
if (-not (Get-Text $SpaceId)) { $Missing += '-SpaceId' } elseif ($SpaceId -notmatch $UuidPattern) { $Missing += '-SpaceId(需UUID)' }
if (-not (Get-Text $AppCode)) { $Missing += '-AppCode(绑定解析应用)' }
if (-not $BinderKeyValue) { $Missing += 'binder凭据(-BinderKey 或 ' + $BinderKeyEnv + ')' } elseif ($BinderKeyValue -notmatch $ServiceKeyPattern) { $Missing += 'binder凭据(需 ontosa_ 前缀加 43 位)' }
if (-not $BearerValue) { $Missing += 'AP ADMIN Bearer(-BearerToken 或 ' + $BearerTokenEnv + ')' }
if (-not (Get-Text $FaultPlatformBase)) { $Missing += '-FaultPlatformBase(故障侧AP，用于404/5xx→503腿)' } elseif ($FaultPlatformBase -notmatch '^https?://[^/]+$') { $Missing += '-FaultPlatformBase(需 scheme+host:port，不含路径)' }
if ($OntologyBase -notmatch '^https?://[^/]+$') { $Missing += '-OntologyBase(需 scheme+host:port，不含路径)' }
if ($PlatformBase -notmatch '^https?://[^/]+$') { $Missing += '-PlatformBase(需 scheme+host:port，不含路径)' }

$stageInputs = [ordered]@{
    ontologyBase = $OntologyBase
    platformBase = $PlatformBase
    faultPlatformBase = (Get-Text $FaultPlatformBase)
    platformTenant = $PlatformTenant
    environment = $Environment
    spaceId = (Get-Text $SpaceId)
    appCode = (Get-Text $AppCode)
    binderKeyProvided = [bool]$BinderKeyValue
    bearerProvided = [bool]$BearerValue
    cacheWaitSeconds = $CacheWaitSeconds
    runTimeoutSeconds = $RunTimeoutSeconds
    sessionId = (Get-Text $SessionId)
}

$stageBoundaries = @(
    'J6 的 pinFailure/reason 只写进运行冻结快照，HTTP 状态接口不暴露该字段；本段以“运行未被 503 打断 + observation 无 releaseId”为可观测证据，并如实记录该边界。',
    'J6b 的故障来自环境（故障侧 AP 的本体地址指向 404/坏响应/5xx），脚本不伪造故障；缺 -FaultPlatformBase 即 BLOCKED。',
    'J6 在结束后恢复原绑定；恢复失败记为 FAIL，不把环境留在解绑状态。'
)

if ($Missing.Count -gt 0) {
    Write-Output '[BLOCKED] J6 前置未满足，未发出任何请求：'
    foreach ($Item in $Missing) { Write-Output ('  - ' + $Item) }
    Complete-Stage 'J6' 'BLOCKED' ('缺外部输入: ' + ($Missing -join '; ')) $stageInputs $stageBoundaries
}

# ---- J6a 故障边界：先做，避免解绑状态干扰 ----
if ($Verdict -eq 'PASS') {
    $faultRun = Invoke-StageRun $FaultPlatformBase $AppCode 'J6b-故障侧'
    Add-Check 'J6b-故障侧运行返回503' (($faultRun.HttpStatus -eq 503) -and ($faultRun.Code -ceq 'CONTEXT_PACK_UNAVAILABLE')) ('http=' + $faultRun.HttpStatus + ' code=' + $faultRun.Code + ' ' + $faultRun.Error)
    $Details['faultLeg'] = [ordered]@{ platformBase = (Get-Text $FaultPlatformBase); httpStatus = $faultRun.HttpStatus; code = $faultRun.Code; runId = $faultRun.RunId }
    if (-not (($faultRun.HttpStatus -eq 503) -and ($faultRun.Code -ceq 'CONTEXT_PACK_UNAVAILABLE'))) {
        $Verdict = 'FAIL'
        $Reason = '故障侧未被正确拒绝：HTTP ' + $faultRun.HttpStatus + ' code=' + $faultRun.Code + '（要求 503 CONTEXT_PACK_UNAVAILABLE，不得当无绑定）'
    }
}

# ---- J6b 真实解绑 + 跳过策略 ----
$baselineBindingId = ''
$baselineReleaseId = ''
if ($Verdict -eq 'PASS') {
    $baseline = Get-StageBinding $AppCode $BinderKeyValue
    Add-Check 'J6-基线绑定' $baseline.Ok ('HTTP ' + $baseline.Status + ' code=' + $baseline.Code)
    if ($baseline.Ok) {
        $baselineBindingId = Get-Text $baseline.Data.bindingId
        $baselineReleaseId = Get-Text $baseline.Data.releaseId
        Add-Check 'J6-基线bindingId' ($baselineBindingId -match $UuidPattern) ('bindingId=' + $baselineBindingId)
        $Details['baselineBinding'] = [ordered]@{ bindingId = $baselineBindingId; releaseId = $baselineReleaseId; status = 'ACTIVE' }
        if ($baselineBindingId -notmatch $UuidPattern) { $Verdict = 'FAIL'; $Reason = '基线绑定缺少 bindingId，无法解绑' }
    } else {
        $Verdict = 'BLOCKED'
        $Reason = '没有可解绑的基线绑定（HTTP ' + $baseline.Status + ' ' + $baseline.Code + '）；J2/J5 应先建立绑定'
    }
}

if ($Verdict -eq 'PASS') {
    $deleteResponse = Invoke-StageHttp -Method 'DELETE' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/release-bindings/' + $baselineBindingId) -Headers @{ 'X-API-Key' = $BinderKeyValue }
    $deleteEnvelope = Test-Envelope $deleteResponse @(200) 'OK'
    Add-Check 'J6-解绑请求' $deleteEnvelope.Ok ('HTTP ' + $deleteEnvelope.Status + ' code=' + $deleteEnvelope.Code)
    $deleteStatus = Get-Text $deleteEnvelope.Data.status
    Add-Check 'J6-解绑返回UNBOUND' ($deleteStatus -ceq 'UNBOUND') ('status=' + $deleteStatus)
    $Details['unbind'] = [ordered]@{ httpStatus = $deleteEnvelope.Status; code = $deleteEnvelope.Code; status = $deleteStatus; bindingId = $baselineBindingId }
    if (-not $deleteEnvelope.Ok -or $deleteStatus -cne 'UNBOUND') {
        $Verdict = 'FAIL'
        $Reason = '解绑失败：HTTP ' + $deleteEnvelope.Status + ' code=' + $deleteEnvelope.Code + ' status=' + $deleteStatus
    }
}

if ($Verdict -eq 'PASS') {
    $gone = Get-StageBinding $AppCode $BinderKeyValue
    Add-Check 'J6-解绑后读回404' (($gone.Status -eq 404) -and ($gone.Code -ceq 'BINDING_NOT_FOUND')) ('HTTP ' + $gone.Status + ' code=' + $gone.Code + ' ' + $gone.Message)
    $Details['afterUnbind'] = [ordered]@{ httpStatus = $gone.Status; code = $gone.Code; message = $gone.Message }
    if (-not (($gone.Status -eq 404) -and ($gone.Code -ceq 'BINDING_NOT_FOUND'))) {
        $Verdict = 'FAIL'
        $Reason = '解绑后仍能读到绑定或错误码不是 BINDING_NOT_FOUND：HTTP ' + $gone.Status + ' code=' + $gone.Code
    }
}

if ($Verdict -eq 'PASS') {
    Wait-StageCache $CacheWaitSeconds 'J6'
    $skipRun = Invoke-StageRun $PlatformBase $AppCode 'J6a-跳过策略'
    Add-Check 'J6-缺绑定运行未被503' (($skipRun.HttpStatus -eq 200) -and ($skipRun.Ok -or $skipRun.RunId)) ('http=' + $skipRun.HttpStatus + ' code=' + $skipRun.Code + ' status=' + $skipRun.RunStatus + ' ' + $skipRun.Error)
    if (($skipRun.HttpStatus -eq 200) -and $skipRun.RunId) {
        Add-Check 'J6-跳过策略保留observation' ($null -ne $skipRun.Observation) '运行必须返回 observation（非保护运行）'
        $observedRelease = ''
        if ($null -ne $skipRun.Observation) { $observedRelease = Get-Text $skipRun.Observation.releaseId }
        Add-Check 'J6-跳过策略不注入release' ($observedRelease -eq '') ('observation.releaseId=' + $observedRelease)
        $Details['skipLeg'] = [ordered]@{ runId = $skipRun.RunId; runStatus = $skipRun.RunStatus; httpStatus = $skipRun.HttpStatus; observedReleaseId = $observedRelease; pinFailureObservable = $false }
    }
    if (-not (($skipRun.HttpStatus -eq 200) -and $skipRun.RunId)) {
        $Verdict = 'FAIL'
        $Reason = '缺绑定的运行没有被按策略跳过：' + $skipRun.Error
    }
}

# ---- 恢复基线绑定 ----
if (Get-Text $baselineBindingId) {
    $restoreBody = [ordered]@{
        spaceId = $SpaceId
        platformTenant = $PlatformTenant
        appCode = $AppCode
        environment = $Environment
        releaseId = $baselineReleaseId
    }
    try {
        $restoreResponse = Invoke-StageHttp -Method 'POST' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/release-bindings') -Headers @{ 'X-API-Key' = $BinderKeyValue } -Body $restoreBody
        $restoreEnvelope = Test-Envelope $restoreResponse @(200) 'OK'
        $restoredRelease = Get-Text $restoreEnvelope.Data.releaseId
        Add-Check 'J6-恢复基线绑定' ($restoreEnvelope.Ok -and $restoredRelease -eq $baselineReleaseId) ('HTTP ' + $restoreEnvelope.Status + ' code=' + $restoreEnvelope.Code + ' releaseId=' + $restoredRelease)
        $Details['restore'] = [ordered]@{ httpStatus = $restoreEnvelope.Status; code = $restoreEnvelope.Code; releaseId = $restoredRelease; bindingId = (Get-Text $restoreEnvelope.Data.bindingId) }
        if (-not $restoreEnvelope.Ok) { $Verdict = 'FAIL'; $Reason = '恢复基线绑定失败：HTTP ' + $restoreEnvelope.Status + ' code=' + $restoreEnvelope.Code }
    } catch {
        Add-Check 'J6-恢复基线绑定' $false ('请求异常: ' + $_.Exception.Message)
        $Verdict = 'FAIL'
        $Reason = '恢复基线绑定请求异常: ' + $_.Exception.Message
    }
}

if ($Verdict -eq 'PASS' -and $script:FirstFailure) {
    $Verdict = 'FAIL'
    $Reason = $script:FirstFailure
}

Complete-Stage 'J6' $Verdict $Reason $stageInputs $stageBoundaries
