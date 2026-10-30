<#
.SYNOPSIS
  EX-07 J1/J2 绑定协议的可重复执行器（把手册 §3.2 的内存探针固化成脚本）。

.DESCRIPTION
  只做两件事，且刻意不做 J3～J9：
    J1  只读服务账号 POST 内部绑定面 → 必须 403 NO_PERMISSION，且不得留下绑定（副作用检查）；
    J2  binder 服务账号 POST 两次 → 首次 changed=true、第二次 false 且同 bindingId/releaseId；
        再用只读凭据 GET 内部绑定查询，读回必须与首次一致。
  参数一律从外部传入，不把模板字面量当 UUID；两把 key 只从进程环境读取，绝不打印、绝不落盘。
  前置不满足时输出 [BLOCKED] 并以退出码 3 结束（区别于断言失败 1、通过 0），避免"没跑"被读成"没通过"。

.EXAMPLE
  $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = '<readonly key>'
  $env:AGENT_SECRET_ONTO_APP_BINDER_KEY   = '<binder key>'
  ./joint-j1-j2.ps1 -SpaceId <uuid> -ReleaseId <uuid> -AppCode ex07-probe -PlatformTenant local-v12
#>
[CmdletBinding()]
param(
    [string]$OntologyBase = 'http://127.0.0.1:18182',
    [string]$SpaceId,
    [string]$ReleaseId,
    [string]$AppCode,
    [string]$PlatformTenant = 'local-v12',
    [string]$Environment = 'production',
    [string]$EvidenceDir,
    [switch]$SkipSideEffectCheck
)

$ErrorActionPreference = 'Stop'
$ExitPass = 0
$ExitFail = 1
$ExitBlocked = 3

function Get-Text([object]$Value) {
    if ($null -eq $Value) { return '' }
    return ([string]$Value).Trim()
}

# 前置检查：缺什么就报什么，绝不带着半套参数去发请求。
$Missing = @()
if (-not (Get-Text $SpaceId)) { $Missing += '-SpaceId' }
elseif ($SpaceId -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') { $Missing += '-SpaceId(需UUID)' }
if (-not (Get-Text $ReleaseId)) { $Missing += '-ReleaseId' }
elseif ($ReleaseId -notmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') { $Missing += '-ReleaseId(需UUID)' }
if (-not (Get-Text $AppCode)) { $Missing += '-AppCode' }
if (-not (Get-Text $PlatformTenant)) { $Missing += '-PlatformTenant' }
if (-not (Get-Text $Environment)) { $Missing += '-Environment' }
if (-not (Get-Text $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY)) { $Missing += '环境变量 AGENT_SECRET_ONTO_ENGINE_READ_KEY' }
if (-not (Get-Text $env:AGENT_SECRET_ONTO_APP_BINDER_KEY)) { $Missing += '环境变量 AGENT_SECRET_ONTO_APP_BINDER_KEY' }
if ($OntologyBase -notmatch '^https?://[^/]+$') { $Missing += '-OntologyBase(需 scheme+host:port，不含路径)' }

if ($Missing.Count -gt 0) {
    Write-Host '[BLOCKED] J1/J2 前置未满足，未发出任何请求：'
    foreach ($Item in $Missing) { Write-Host ('  - ' + $Item) }
    Write-Host '  说明：这是"缺外部输入"，不是协议失败；补齐后重跑同一命令即可。'
    exit $ExitBlocked
}

Add-Type -AssemblyName System.Net.Http

function Invoke-BindingProbe {
    param([string]$Method, [string]$Url, [string]$Key, [string]$JsonBody)
    $Client = New-Object System.Net.Http.HttpClient
    $Client.Timeout = [TimeSpan]::FromSeconds(15)
    $Request = New-Object System.Net.Http.HttpRequestMessage
    $Response = $null
    try {
        if ($Method -eq 'POST') { $Request.Method = [System.Net.Http.HttpMethod]::Post }
        else { $Request.Method = [System.Net.Http.HttpMethod]::Get }
        $Request.RequestUri = [Uri]$Url
        $Request.Headers.Add('X-API-Key', $Key)
        if ($JsonBody) {
            $Request.Content = New-Object System.Net.Http.StringContent -ArgumentList @($JsonBody, [Text.Encoding]::UTF8, 'application/json')
        }
        $Response = $Client.SendAsync($Request).GetAwaiter().GetResult()
        $Text = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $Body = $null
        if ((Get-Text $Text)) { try { $Body = $Text | ConvertFrom-Json } catch { $Body = $null } }
        # 错误信封的 data 允许为 null；不假设字段存在，避免 StrictMode 下报错。
        $Fact = [ordered]@{ httpStatus = [int]$Response.StatusCode; code = ''; bindingId = ''; releaseId = ''; changed = $null }
        if ($Body -and $Body.PSObject.Properties['code']) { $Fact.code = [string]$Body.code }
        $Data = $null
        if ($Body -and $Body.PSObject.Properties['data']) { $Data = $Body.data }
        if ($Data) {
            foreach ($Field in @('bindingId', 'releaseId', 'changed')) {
                $Property = $Data.PSObject.Properties[$Field]
                if ($Property) { $Fact[$Field] = $Property.Value }
            }
        }
        [pscustomobject]$Fact
    } finally {
        if ($Response) { $Response.Dispose() }
        $Request.Dispose()
        $Client.Dispose()
    }
}

$BindingUrl = $OntologyBase + '/onto/internal/release-bindings'
$Payload = @{
    spaceId        = $SpaceId
    releaseId      = $ReleaseId
    platformTenant = $PlatformTenant
    appCode        = $AppCode
    environment    = $Environment
} | ConvertTo-Json -Compress
$Query = 'platformTenant={0}&appCode={1}&environment={2}' -f [Uri]::EscapeDataString($PlatformTenant), [Uri]::EscapeDataString($AppCode), [Uri]::EscapeDataString($Environment)

$Started = Get-Date
$Checks = New-Object System.Collections.ArrayList
$Verdict = 'PASS'
$Reason = ''

try {
    # J1：只读凭据写绑定必须被拒。
    $Negative = Invoke-BindingProbe 'POST' $BindingUrl $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY $Payload
    [void]$Checks.Add(@{ name = 'J1-403'; expected = '403 NO_PERMISSION'; actual = ('{0} {1}' -f $Negative.httpStatus, $Negative.code); ok = ($Negative.httpStatus -eq 403 -and $Negative.code -eq 'NO_PERMISSION') })
    if (-not $Checks[$Checks.Count - 1].ok) { throw 'J1 未满足：只读凭据的写请求不是 403 NO_PERMISSION' }

    # J1 副作用：被拒之后不得存在该 appCode 的 ACTIVE 绑定。
    if (-not $SkipSideEffectCheck) {
        $Before = Invoke-BindingProbe 'GET' ($BindingUrl + '?' + $Query) $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY ''
        $Absent = ($Before.httpStatus -eq 404 -and $Before.code -eq 'BINDING_NOT_FOUND')
        [void]$Checks.Add(@{ name = 'J1-无副作用'; expected = '404 BINDING_NOT_FOUND'; actual = ('{0} {1}' -f $Before.httpStatus, $Before.code); ok = $Absent })
        if (-not $Absent) { throw ('J1 之后已存在绑定：' + $Before.httpStatus + ' ' + $Before.code + '；换一个独立 appCode 重试') }
    }

    # J2：binder 首次写、重复写必须幂等。
    $First = Invoke-BindingProbe 'POST' $BindingUrl $env:AGENT_SECRET_ONTO_APP_BINDER_KEY $Payload
    [void]$Checks.Add(@{ name = 'J2-首次pin'; expected = '200 changed=true'; actual = ('{0} changed={1}' -f $First.httpStatus, $First.changed); ok = ($First.httpStatus -eq 200 -and $First.changed -eq $true -and (Get-Text $First.bindingId)) })
    if (-not $Checks[$Checks.Count - 1].ok) { throw 'J2 首次 pin 未创建（200/changed=true/bindingId 三者需同时成立）' }

    $Again = Invoke-BindingProbe 'POST' $BindingUrl $env:AGENT_SECRET_ONTO_APP_BINDER_KEY $Payload
    [void]$Checks.Add(@{ name = 'J2-幂等'; expected = '200 changed=false 同 bindingId'; actual = ('{0} changed={1}' -f $Again.httpStatus, $Again.changed); ok = ($Again.httpStatus -eq 200 -and $Again.changed -eq $false -and (Get-Text $Again.bindingId) -eq (Get-Text $First.bindingId)) })
    if (-not $Checks[$Checks.Count - 1].ok) { throw 'J2 重复 pin 不幂等（应为 200/changed=false/同 bindingId）' }

    # J2 读回：只读凭据走内部查询面。
    $ReadBack = Invoke-BindingProbe 'GET' ($BindingUrl + '?' + $Query) $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY ''
    $SameBinding = ((Get-Text $ReadBack.bindingId) -eq (Get-Text $First.bindingId))
    $SameRelease = ((Get-Text $ReadBack.releaseId) -eq (Get-Text $ReleaseId))
    [void]$Checks.Add(@{ name = 'J2-读回'; expected = '200 同 bindingId/releaseId'; actual = ('{0} binding={1} release={2}' -f $ReadBack.httpStatus, $ReadBack.bindingId, $ReadBack.releaseId); ok = ($ReadBack.httpStatus -eq 200 -and $SameBinding -and $SameRelease) })
    if (-not $Checks[$Checks.Count - 1].ok) { throw 'J2 内部读回不一致' }
} catch {
    $Verdict = 'FAIL'
    $Reason = $_.Exception.Message
}

$Elapsed = [int]((Get-Date) - $Started).TotalMilliseconds
$Evidence = [ordered]@{
    suite       = 'EX-07 J1/J2 binding protocol'
    verdict     = $Verdict
    reason      = $Reason
    startedAt   = $Started.ToString('o')
    elapsedMs   = $Elapsed
    # 只记录脱敏输入：不含 key、不含 header。
    inputs      = [ordered]@{
        ontologyBase   = $OntologyBase
        spaceId        = $SpaceId
        releaseId      = $ReleaseId
        appCode        = $AppCode
        platformTenant = $PlatformTenant
        environment    = $Environment
        sideEffectCheck = (-not $SkipSideEffectCheck)
    }
    checks      = @($Checks)
    boundaries  = @(
        'J1/J2 只证明绑定协议本身，不等于 J9：没有证明 AP 的 ReleasePins 发出了正确载荷。',
        'J3～J8 未执行；用真实应用发布链路验证 pin 需另有合格候选与评测/发布前提。'
    )
}

Write-Host ''
Write-Host ('== J1/J2 ' + $Verdict + '（' + $Elapsed + ' ms）==')
foreach ($Check in $Checks) {
    $Mark = if ($Check.ok) { 'PASS' } else { 'FAIL' }
    Write-Host ('  [' + $Mark + '] ' + $Check.name + ' | 期望 ' + $Check.expected + ' | 实际 ' + $Check.actual)
}
if ($Verdict -eq 'FAIL') { Write-Host ('  原因：' + $Reason) }

if ((Get-Text $EvidenceDir)) {
    if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null }
    $File = Join-Path $EvidenceDir ('j1-j2-' + $Started.ToString('yyyyMMdd-HHmmss') + '.json')
    ($Evidence | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath $File -Encoding UTF8
    Write-Host ('  证据：' + $File)
}

if ($Verdict -eq 'PASS') { exit $ExitPass }
exit $ExitFail
