<#
.SYNOPSIS
  EX-07 J5 绑定更新/显式优先：把 appCode 与 explicitAppCode 的 ACTIVE 绑定改到 replacementReleaseId，
  再分别真实运行两个应用，核对“冻结配置无 releaseId 的走新绑定、冻结配置带旧 releaseId 的仍走旧版本”。

.DESCRIPTION
  本段只调用平台/本体既有接口，不改产品代码：
    - OR POST /onto/internal/release-bindings（binder 服务凭据；body 必须含 spaceId），changed=true 表示换绑；
    - OR GET  /onto/internal/release-bindings?platformTenant=&appCode=&environment= 读回当前生效绑定；
    - AP GET  /admin/apps/{code} 读取 explicitAppCode 的冻结 config.ontology.releaseId；
    - AP POST /admin/apps/{code}/runs 触发一次真实运行，GET /admin/runs/{id} 读 status 与 observation。

  判据（手册 §4 J5）：
    1) 换绑后 appCode 在最多 30s 缓存窗口内解析到 replacementReleaseId；
    2) explicitAppCode 的“当前 pin=新、冻结配置=旧、最终 run=旧”三元组被如实保存，证明显式优先；
    3) 发布新本体不得自动改 pin：本段在运行前后核对绑定未被运行期改写。

  缺输入 → [BLOCKED] 退出码 3（不发出任何请求）；断言失败 → 1；通过 → 0。
  证据只记录 ID/哈希/布尔与 changed 标志，不记录 key/token 原文。
  observation 只在非保护运行上返回；缺失时按 FAIL 记录，不降级通过。

.PARAMETER ReplacementReleaseId
  A 空间的第二个 READY release（Prepare 的 resources.replacementReleaseId），必须不同于 -ReleaseId。

.PARAMETER ExplicitAppCode
  冻结配置里带旧 -ReleaseId 的对照应用（Prepare 的 platformAppDrafts.explicitAppCode）。

.PARAMETER CacheWaitSeconds
  AP 绑定正缓存 TTL 为 30s，默认等 31s；0 表示不等（只用于本地桩/调试，真实环境必须 ≥31）。

.EXAMPLE
  $env:AGENT_SECRET_ONTO_APP_BINDER_KEY = '<binder ontosa_ key>'
  ./joint-j5.ps1 -SpaceId <uuid> -ReleaseId <old uuid> -ReplacementReleaseId <new uuid> `
      -AppCode joint_xxx_resolve -ExplicitAppCode joint_xxx_explicit -BearerToken <admin jwt>
#>
[CmdletBinding()]
param(
    [string]$OntologyBase = 'http://127.0.0.1:18182',
    [string]$PlatformBase = 'http://127.0.0.1:18080',
    [string]$PlatformTenant = 'local-v12',
    [string]$Environment = 'production',
    [string]$SpaceId,
    [string]$SpaceCode,
    [string]$ReleaseId,
    [string]$ReplacementReleaseId,
    [string]$AppCode,
    [string]$ExplicitAppCode,
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
    return [pscustomobject]@{
        Ok = $ok
        Status = $Response.Status
        Code = $actualCode
        Data = $data
        Message = $(if ($null -ne $Response.Body -and $null -ne $Response.Body.PSObject.Properties['message']) { Get-Text $Response.Body.message } else { '' })
    }
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
    param(
        [string]$Stage,
        [string]$FinalVerdict,
        [string]$FinalReason,
        [object]$Inputs,
        [string[]]$Boundaries
    )
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

# ---- 业务步骤 ----
function Get-StageBinding([string]$Code, [string]$Key) {
    $query = '?platformTenant=' + [Uri]::EscapeDataString($PlatformTenant) +
        '&appCode=' + [Uri]::EscapeDataString($Code) +
        '&environment=' + [Uri]::EscapeDataString($Environment)
    $response = Invoke-StageHttp -Method 'GET' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/release-bindings' + $query) -Headers @{ 'X-API-Key' = $Key }
    return (Test-Envelope $response @(200) 'OK')
}

function Set-StageBinding([string]$Code, [string]$Release, [string]$Key) {
    $body = [ordered]@{
        spaceId = $SpaceId
        platformTenant = $PlatformTenant
        appCode = $Code
        environment = $Environment
        releaseId = $Release
    }
    $response = Invoke-StageHttp -Method 'POST' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/release-bindings') -Headers @{ 'X-API-Key' = $Key } -Body $body
    return (Test-Envelope $response @(200) 'OK')
}

function Invoke-StageRun([string]$Code, [string]$Label) {
    $result = [pscustomobject]@{ Ok = $false; RunId = ''; RunStatus = ''; HttpStatus = 0; Code = ''; Observation = $null; Error = '' }
    $body = [ordered]@{ inputs = [ordered]@{ message = ('EX06 ' + $Label + ' 绑定解析探针') } }
    $headers = @{ Authorization = ('Bearer ' + $BearerValue) }
    try {
        $response = Invoke-StageHttp -Method 'POST' -Url ($PlatformBase.TrimEnd('/') + '/admin/apps/' + $Code + '/runs') -Headers $headers -Body $body
    } catch {
        $result.Error = '请求异常: ' + $_.Exception.Message
        return $result
    }
    $envelope = Test-Envelope $response @(200, 201) 'OK'
    $result.HttpStatus = $envelope.Status
    $result.Code = $envelope.Code
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
            $statusResponse = Invoke-StageHttp -Method 'GET' -Url ($PlatformBase.TrimEnd('/') + '/admin/runs/' + $runId) -Headers $headers
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
            $result.Ok = ($status -eq 'SUCCEEDED')
            if (-not $result.Ok) {
                $result.Error = '运行终态=' + $status + ' error=' + (Get-StageJson $statusEnvelope.Data.error)
            }
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
if (-not (Get-Text $SpaceId)) {
    $Missing += '-SpaceId'
} elseif ($SpaceId -notmatch $UuidPattern) {
    $Missing += '-SpaceId(需UUID)'
}
if (-not (Get-Text $ReleaseId)) {
    $Missing += '-ReleaseId(冻结旧release)'
} elseif ($ReleaseId -notmatch $UuidPattern) {
    $Missing += '-ReleaseId(需UUID)'
}
if (-not (Get-Text $ReplacementReleaseId)) {
    $Missing += '-ReplacementReleaseId'
} elseif ($ReplacementReleaseId -notmatch $UuidPattern) {
    $Missing += '-ReplacementReleaseId(需UUID)'
} elseif ($ReplacementReleaseId -eq $ReleaseId) {
    $Missing += '-ReplacementReleaseId(必须不同于-ReleaseId)'
}
if (-not (Get-Text $AppCode)) { $Missing += '-AppCode(绑定解析应用)' }
if (-not (Get-Text $ExplicitAppCode)) { $Missing += '-ExplicitAppCode(显式优先对照应用)' }
if (-not $BinderKeyValue) {
    $Missing += 'binder凭据(-BinderKey 或 ' + $BinderKeyEnv + ')'
} elseif ($BinderKeyValue -notmatch $ServiceKeyPattern) {
    $Missing += 'binder凭据(需 ontosa_ 前缀加 43 位)'
}
if (-not $BearerValue) { $Missing += 'AP ADMIN Bearer(-BearerToken 或 ' + $BearerTokenEnv + ')' }
if ($OntologyBase -notmatch '^https?://[^/]+$') { $Missing += '-OntologyBase(需 scheme+host:port，不含路径)' }
if ($PlatformBase -notmatch '^https?://[^/]+$') { $Missing += '-PlatformBase(需 scheme+host:port，不含路径)' }

$stageInputs = [ordered]@{
    ontologyBase = $OntologyBase
    platformBase = $PlatformBase
    platformTenant = $PlatformTenant
    environment = $Environment
    spaceId = (Get-Text $SpaceId)
    spaceCode = (Get-Text $SpaceCode)
    releaseId = (Get-Text $ReleaseId)
    replacementReleaseId = (Get-Text $ReplacementReleaseId)
    appCode = (Get-Text $AppCode)
    explicitAppCode = (Get-Text $ExplicitAppCode)
    binderKeyProvided = [bool]$BinderKeyValue
    bearerProvided = [bool]$BearerValue
    cacheWaitSeconds = $CacheWaitSeconds
    runTimeoutSeconds = $RunTimeoutSeconds
    sessionId = (Get-Text $SessionId)
}

$stageBoundaries = @(
    'J5 只证明 AP 运行期按绑定解析（冻结配置无 releaseId）与显式 releaseId 优先；不发布新本体，故“发布新本体不自动改 pin”只以运行前后绑定不变作旁证。',
    'observation 仅在非保护运行上返回；若 AP 处于 IAM 保护受理，GET /admin/runs/{id} 不带 observation，本段按 FAIL 记录，不降级通过。',
    'J5 需要三应用已真实发布（Prepare 的 PlatformAppPublish）与 replacementReleaseId；缺一项即 BLOCKED，不得用桩数据记通过。'
)

if ($Missing.Count -gt 0) {
    Write-Output '[BLOCKED] J5 前置未满足，未发出任何请求：'
    foreach ($Item in $Missing) { Write-Output ('  - ' + $Item) }
    Complete-Stage 'J5' 'BLOCKED' ('缺外部输入: ' + ($Missing -join '; ')) $stageInputs $stageBoundaries
}

# ---- J5a：换绑前读回当前绑定 ----
$before = Get-StageBinding $AppCode $BinderKeyValue
Add-Check 'J5-解析应用-基线绑定' $before.Ok ('HTTP ' + $before.Status + ' code=' + $before.Code)
$beforeBindingId = ''
$beforeRelease = ''
if ($before.Ok) {
    $beforeBindingId = Get-Text $before.Data.bindingId
    $beforeRelease = Get-Text $before.Data.releaseId
    Add-Check 'J5-解析应用-基线bindingId' ($beforeBindingId -match $UuidPattern) ('bindingId=' + $beforeBindingId)
    Add-Check 'J5-解析应用-基线releaseId' ($beforeRelease -match $UuidPattern) ('releaseId=' + $beforeRelease)
}
$Details['resolveBindingBefore'] = [ordered]@{ httpStatus = $before.Status; code = $before.Code; bindingId = $beforeBindingId; releaseId = $beforeRelease }
if (-not $before.Ok) {
    $Verdict = 'FAIL'
    $Reason = '读取 appCode 当前绑定失败：HTTP ' + $before.Status + ' ' + $before.Code
}

$explicitFrozenRelease = ''
if ($Verdict -eq 'PASS') {
    try {
        $explicitApp = Invoke-StageHttp -Method 'GET' -Url ($PlatformBase.TrimEnd('/') + '/admin/apps/' + $ExplicitAppCode) -Headers @{ Authorization = ('Bearer ' + $BearerValue) }
    } catch {
        $explicitApp = [pscustomobject]@{ Status = 0; Raw = ''; Body = $null }
        Add-Check 'J5-显式应用-读取' $false ('请求异常: ' + $_.Exception.Message)
    }
    $explicitEnvelope = Test-Envelope $explicitApp @(200) 'OK'
    Add-Check 'J5-显式应用-读取' $explicitEnvelope.Ok ('HTTP ' + $explicitEnvelope.Status + ' code=' + $explicitEnvelope.Code)
    if ($explicitEnvelope.Ok) {
        $explicitFrozenRelease = Get-Text $explicitEnvelope.Data.config.ontology.releaseId
        Add-Check 'J5-显式应用-冻结配置为旧release' ($explicitFrozenRelease -eq $ReleaseId) ('config.ontology.releaseId=' + $explicitFrozenRelease + ' 期望=' + $ReleaseId)
        Add-Check 'J5-显式应用-冻结配置无新release' ($explicitFrozenRelease -ne $ReplacementReleaseId) ('releaseId=' + $explicitFrozenRelease)
        $Details['explicitFrozenConfig'] = [ordered]@{ appCode = $ExplicitAppCode; releaseId = $explicitFrozenRelease; currentVersionNo = Get-Text $explicitEnvelope.Data.currentVersionNo; status = Get-Text $explicitEnvelope.Data.status }
        if ($explicitFrozenRelease -ne $ReleaseId) { $Verdict = 'FAIL'; $Reason = 'explicitAppCode 冻结配置不是 -ReleaseId：' + $explicitFrozenRelease }
    } else {
        $Verdict = 'FAIL'
        $Reason = '读取 explicitAppCode 失败：HTTP ' + $explicitEnvelope.Status + ' ' + $explicitEnvelope.Code
    }
}

# ---- J5b：两个应用都换绑到 replacementReleaseId ----
$resolveBindingId = ''
if ($Verdict -eq 'PASS') {
    $resolveBind = Set-StageBinding $AppCode $ReplacementReleaseId $BinderKeyValue
    Add-Check 'J5-解析应用-换绑请求' $resolveBind.Ok ('HTTP ' + $resolveBind.Status + ' code=' + $resolveBind.Code + ' ' + $resolveBind.Message)
    if ($resolveBind.Ok) {
        $resolveBindingId = Get-Text $resolveBind.Data.bindingId
        $changedValue = $null
        $changedProperty = $resolveBind.Data.PSObject.Properties['changed']
        if ($null -ne $changedProperty) { $changedValue = $changedProperty.Value }
        $converged = ($beforeRelease -eq $ReplacementReleaseId)
        Add-Check 'J5-解析应用-换绑生效' (($changedValue -eq $true) -or $converged) ('changed=' + [string]$changedValue + ' 基线releaseId=' + $beforeRelease + ' 已收敛=' + [string]$converged)
        Add-Check 'J5-解析应用-换绑目标' ((Get-Text $resolveBind.Data.releaseId) -eq $ReplacementReleaseId) ('releaseId=' + (Get-Text $resolveBind.Data.releaseId))
        Add-Check 'J5-解析应用-换绑bindingId' ($resolveBindingId -match $UuidPattern) ('bindingId=' + $resolveBindingId)
        $Details['resolveBind'] = [ordered]@{ httpStatus = $resolveBind.Status; changed = $changedValue; bindingId = $resolveBindingId; releaseId = (Get-Text $resolveBind.Data.releaseId); convergedBefore = $converged }
        if (-not (($changedValue -eq $true) -or $converged)) { $Verdict = 'FAIL'; $Reason = 'appCode 换绑未产生变更：changed=' + [string]$changedValue }
    } else {
        $Verdict = 'FAIL'
        $Reason = 'appCode 换绑失败：HTTP ' + $resolveBind.Status + ' ' + $resolveBind.Code
    }
}

if ($Verdict -eq 'PASS') {
    $explicitBind = Set-StageBinding $ExplicitAppCode $ReplacementReleaseId $BinderKeyValue
    Add-Check 'J5-显式应用-换绑请求' $explicitBind.Ok ('HTTP ' + $explicitBind.Status + ' code=' + $explicitBind.Code + ' ' + $explicitBind.Message)
    if ($explicitBind.Ok) {
        $explicitBindingId = Get-Text $explicitBind.Data.bindingId
        Add-Check 'J5-显式应用-换绑目标' ((Get-Text $explicitBind.Data.releaseId) -eq $ReplacementReleaseId) ('releaseId=' + (Get-Text $explicitBind.Data.releaseId))
        Add-Check 'J5-显式应用-换绑bindingId' ($explicitBindingId -match $UuidPattern) ('bindingId=' + $explicitBindingId)
        $Details['explicitBind'] = [ordered]@{ httpStatus = $explicitBind.Status; changed = (Get-Text $explicitBind.Data.changed); bindingId = $explicitBindingId; releaseId = (Get-Text $explicitBind.Data.releaseId) }
    } else {
        $Verdict = 'FAIL'
        $Reason = 'explicitAppCode 换绑失败：HTTP ' + $explicitBind.Status + ' ' + $explicitBind.Code
    }
}

# ---- J5c：等缓存窗口后分别真实运行 ----
$resolveObservationRelease = ''
$explicitObservationRelease = ''
if ($Verdict -eq 'PASS') {
    Wait-StageCache $CacheWaitSeconds 'J5'
    $resolveRun = Invoke-StageRun $AppCode 'J5-解析应用'
    Add-Check 'J5-解析应用-运行' $resolveRun.Ok ('runId=' + $resolveRun.RunId + ' status=' + $resolveRun.RunStatus + ' http=' + $resolveRun.HttpStatus + ' ' + $resolveRun.Error)
    if ($resolveRun.Ok) {
        Add-Check 'J5-解析应用-observation可见' ($null -ne $resolveRun.Observation) 'GET /admin/runs/{id} 必须返回 observation（非保护运行）'
        $resolveObservationRelease = Get-Text $resolveRun.Observation.releaseId
        Add-Check 'J5-解析应用-取新绑定' ($resolveObservationRelease -eq $ReplacementReleaseId) ('observation.releaseId=' + $resolveObservationRelease + ' 期望=' + $ReplacementReleaseId)
        Add-Check 'J5-解析应用-空间一致' ((Get-Text $resolveRun.Observation.spaceId) -eq $SpaceId) ('observation.spaceId=' + (Get-Text $resolveRun.Observation.spaceId))
        $Details['resolveRun'] = [ordered]@{ runId = $resolveRun.RunId; status = $resolveRun.RunStatus; observation = [ordered]@{ traceId = (Get-Text $resolveRun.Observation.traceId); releaseId = $resolveObservationRelease; spaceId = (Get-Text $resolveRun.Observation.spaceId) } }
    } else {
        $Verdict = 'FAIL'
        $Reason = 'appCode 运行未成功：' + $resolveRun.Error
    }
}

if ($Verdict -eq 'PASS') {
    $explicitRun = Invoke-StageRun $ExplicitAppCode 'J5-显式应用'
    Add-Check 'J5-显式应用-运行' $explicitRun.Ok ('runId=' + $explicitRun.RunId + ' status=' + $explicitRun.RunStatus + ' http=' + $explicitRun.HttpStatus + ' ' + $explicitRun.Error)
    if ($explicitRun.Ok) {
        Add-Check 'J5-显式应用-observation可见' ($null -ne $explicitRun.Observation) 'GET /admin/runs/{id} 必须返回 observation（非保护运行）'
        $explicitObservationRelease = Get-Text $explicitRun.Observation.releaseId
        Add-Check 'J5-显式应用-仍取冻结旧release' ($explicitObservationRelease -eq $ReleaseId) ('observation.releaseId=' + $explicitObservationRelease + ' 期望=' + $ReleaseId)
        Add-Check 'J5-显式应用-未取新绑定' ($explicitObservationRelease -ne $ReplacementReleaseId) ('observation.releaseId=' + $explicitObservationRelease + ' 当前pin=' + $ReplacementReleaseId)
        $Details['explicitRun'] = [ordered]@{ runId = $explicitRun.RunId; status = $explicitRun.RunStatus; observation = [ordered]@{ traceId = (Get-Text $explicitRun.Observation.traceId); releaseId = $explicitObservationRelease; spaceId = (Get-Text $explicitRun.Observation.spaceId) } }
        $Details['conflictTriple'] = [ordered]@{
            currentPin = $ReplacementReleaseId
            frozenConfig = $explicitFrozenRelease
            finalRun = $explicitObservationRelease
            resolvedBy = 'config.ontology.releaseId（显式优先）'
        }
        Add-Check 'J5-冲突三元组' (($explicitObservationRelease -eq $explicitFrozenRelease) -and ($explicitObservationRelease -ne $ReplacementReleaseId)) ('currentPin=' + $ReplacementReleaseId + ' frozenConfig=' + $explicitFrozenRelease + ' finalRun=' + $explicitObservationRelease)
    } else {
        $Verdict = 'FAIL'
        $Reason = 'explicitAppCode 运行未成功：' + $explicitRun.Error
    }
}

# ---- J5d：运行期不得自动改 pin ----
if ($Verdict -eq 'PASS') {
    $after = Get-StageBinding $AppCode $BinderKeyValue
    Add-Check 'J5-运行后绑定读回' $after.Ok ('HTTP ' + $after.Status + ' code=' + $after.Code)
    if ($after.Ok) {
        $afterRelease = Get-Text $after.Data.releaseId
        $afterBindingId = Get-Text $after.Data.bindingId
        Add-Check 'J5-运行后绑定未改变' (($afterRelease -eq $ReplacementReleaseId) -and ($afterBindingId -eq $resolveBindingId)) ('releaseId=' + $afterRelease + ' bindingId=' + $afterBindingId + ' 期望bindingId=' + $resolveBindingId)
        $Details['resolveBindingAfter'] = [ordered]@{ httpStatus = $after.Status; bindingId = $afterBindingId; releaseId = $afterRelease }
    } else {
        $Verdict = 'FAIL'
        $Reason = '运行后读取绑定失败：HTTP ' + $after.Status + ' ' + $after.Code
    }
}

if ($Verdict -eq 'PASS' -and $script:FirstFailure) {
    $Verdict = 'FAIL'
    $Reason = $script:FirstFailure
}

Complete-Stage 'J5' $Verdict $Reason $stageInputs $stageBoundaries
