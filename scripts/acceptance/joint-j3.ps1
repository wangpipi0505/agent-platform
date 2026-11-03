<#
.SYNOPSIS
  EX-07 J3 的前置执行器：真实 release 的 context-pack 拉取与结构/摘要核对。

.DESCRIPTION
  只覆盖 J3 中"本体侧能不能给出真实包"这一半：
    - 只读服务账号 GET /onto/releases/{id}/context-pack 必须 200；
    - 载荷必须是四段（conceptCards/termDictionary/relationSummaries/exampleQa）且都是数组；
    - candidateSha256 出现时必须是 64 位小写十六进制；
    - 摘要三对象分开（R05/JOINT-003）：live Envelope 摘要含动态 traceId，仅观测记录；
      -ExpectSha256 只与 -FrozenContextPackPath 指向的冻结制品原件核对，无法取得独立原件时 BLOCKED。
  AP 侧"运行时 snapshot.contextPack 含 space/releaseId/sha256/fetchedAt/truncated"**不在本脚本**：
  那需要一次声明了 ontology 的真实运行（J9 链路），缺候选/评测时就无法伪造，故如实留给后续步骤。

  前置不满足 → [BLOCKED] 退出码 3；断言失败 → 1；通过 → 0。证据 JSON 不含 key。

.EXAMPLE
  $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = '<readonly key>'
  ./joint-j3.ps1 -ReleaseId <uuid> -ExpectSha256 <64hex>
#>
[CmdletBinding()]
param(
    [string]$OntologyBase = 'http://127.0.0.1:18182',
    [string]$ReleaseId,
    [string]$ExpectSha256,
    [string]$FrozenContextPackPath,
    [string]$EvidenceDir
)

$ErrorActionPreference = 'Stop'
$ExitPass = 0
$ExitFail = 1
$ExitBlocked = 3
$UuidPattern = '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'

function Get-Text([object]$Value) {
    if ($null -eq $Value) { return '' }
    return ([string]$Value).Trim()
}

# R05/JOINT-003：三个摘要对象分开，不得混用——
#   ① live HTTP Envelope 摘要（含每请求变化的 traceId，仅观测记录，不可与任何冻结期望比较）；
#   ② 冻结 CONTEXT_PACK 制品原件 SHA（凭本地独立原件核对，期望必须指向它）；
#   ③ 发布元数据中的制品摘要（另按已核发布元数据验证，不在本脚本内）。
# 给了 -ExpectSha256 却无法取得独立原件时该核对记 BLOCKED；不把当前响应现算 SHA 回填期望。
function Resolve-J3DigestDecision([string]$ExpectSha256, [string]$FrozenPath, [bool]$FrozenPathExists, [string]$ObservedEnvelopeSha) {
    $envelope = @{ name = 'J3-Envelope摘要观测'; expected = '仅记录（含动态traceId，不可与冻结制品比较）'; actual = $ObservedEnvelopeSha; ok = $true }
    if (-not (Get-Text $ExpectSha256)) {
        return @{ verdict = 'OBSERVE'; checks = @($envelope); reason = '' }
    }
    if (-not (Get-Text $FrozenPath)) {
        return @{ verdict = 'BLOCKED'; checks = @($envelope); reason = '给了-ExpectSha256但未提供-FrozenContextPackPath：摘要期望对应冻结制品原件，live Envelope含动态traceId不可比较；无法取得独立原件时BLOCKED' }
    }
    if (-not $FrozenPathExists) {
        return @{ verdict = 'BLOCKED'; checks = @($envelope); reason = ('冻结制品原件不存在: ' + $FrozenPath) }
    }
    $Hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $FileSha = ([BitConverter]::ToString($Hasher.ComputeHash([IO.File]::ReadAllBytes($FrozenPath))) -replace '-', '').ToLowerInvariant()
    } finally { $Hasher.Dispose() }
    $artifact = @{ name = 'J3-冻结制品原件'; expected = $ExpectSha256; actual = $FileSha; ok = ($FileSha -cne $ExpectSha256 -eq $false) }
    if ($FileSha -cne $ExpectSha256) {
        return @{ verdict = 'FAIL'; checks = @($envelope, $artifact); reason = '冻结制品原件SHA与期望不一致（对象错配或内容变化，例如把BUNDLE摘要当context-pack期望）' }
    }
    return @{ verdict = 'PASS'; checks = @($envelope, $artifact); reason = '' }
}

$Missing = @()
if (-not (Get-Text $ReleaseId)) { $Missing += '-ReleaseId' }
elseif ($ReleaseId -notmatch $UuidPattern) { $Missing += '-ReleaseId(需UUID)' }
if (-not (Get-Text $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY)) { $Missing += '环境变量 AGENT_SECRET_ONTO_ENGINE_READ_KEY' }
if ($OntologyBase -notmatch '^https?://[^/]+$') { $Missing += '-OntologyBase(需 scheme+host:port，不含路径)' }
if ((Get-Text $ExpectSha256) -and $ExpectSha256 -notmatch '^[0-9a-f]{64}$') { $Missing += '-ExpectSha256(需64位小写hex)' }

if ($Missing.Count -gt 0) {
    Write-Host '[BLOCKED] J3 前置未满足，未发出任何请求：'
    foreach ($Item in $Missing) { Write-Host ('  - ' + $Item) }
    Write-Host '  说明：这是"缺外部输入/参数写错"，不是协议失败；补齐后重跑同一命令即可。'
    exit $ExitBlocked
}

Add-Type -AssemblyName System.Net.Http

function Invoke-PackFetch {
    param([string]$Url, [string]$Key)
    $Client = New-Object System.Net.Http.HttpClient
    $Client.Timeout = [TimeSpan]::FromSeconds(20)
    $Request = New-Object System.Net.Http.HttpRequestMessage
    $Response = $null
    try {
        $Request.Method = [System.Net.Http.HttpMethod]::Get
        $Request.RequestUri = [Uri]$Url
        $Request.Headers.Add('X-API-Key', $Key)
        $Response = $Client.SendAsync($Request).GetAwaiter().GetResult()
        $Text = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $Body = $null
        if ((Get-Text $Text)) { try { $Body = $Text | ConvertFrom-Json } catch { $Body = $null } }
        [pscustomobject]@{
            httpStatus = [int]$Response.StatusCode
            raw        = $Text
            body       = $Body
            code       = $(if ($Body -and $Body.PSObject.Properties['code']) { [string]$Body.code } else { '' })
        }
    } finally {
        if ($Response) { $Response.Dispose() }
        $Request.Dispose()
        $Client.Dispose()
    }
}

$Url = $OntologyBase + '/onto/releases/' + $ReleaseId + '/context-pack'
$Started = Get-Date
$Checks = New-Object System.Collections.ArrayList
$Verdict = 'PASS'
$Reason = ''
$ObservedSha = ''
$EmptySections = @()
$SectionCounts = [ordered]@{}

try {
    $Fetch = Invoke-PackFetch $Url $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY

    [void]$Checks.Add(@{ name = 'J3-取包'; expected = '200'; actual = [string]$Fetch.httpStatus; ok = ($Fetch.httpStatus -eq 200) })
    if ($Fetch.httpStatus -ne 200) { throw ('context-pack 未返回 200：' + $Fetch.httpStatus + ' ' + $Fetch.code) }

    # 摘要按收到的原始字节计算，避免"先解析再序列化"把字节差异掩盖掉。
    $Hasher = [System.Security.Cryptography.SHA256]::Create()
    $Bytes = [Text.Encoding]::UTF8.GetBytes([string]$Fetch.raw)
    $ObservedSha = ([BitConverter]::ToString($Hasher.ComputeHash($Bytes)) -replace '-', '').ToLowerInvariant()
    $Hasher.Dispose()

    $Data = $null
    if ($Fetch.body -and $Fetch.body.PSObject.Properties['data']) { $Data = $Fetch.body.data }
    [void]$Checks.Add(@{ name = 'J3-信封'; expected = 'data 为对象'; actual = $(if ($Data) { 'present' } else { 'missing' }); ok = ($null -ne $Data) })
    if (-not $Data) { throw '响应缺少 data' }

    $Sections = @('conceptCards', 'termDictionary', 'relationSummaries', 'exampleQa')
    foreach ($Section in $Sections) {
        $Property = $Data.PSObject.Properties[$Section]
        $IsArray = $false
        $Count = -1
        if ($Property) {
            $Value = $Property.Value
            $IsArray = ($Value -is [System.Array])
            if ($IsArray) { $Count = $Value.Count } elseif ($null -eq $Value) { $IsArray = $true; $Count = 0 }
        }
        $SectionCounts[$Section] = $Count
        if ($IsArray -and $Count -eq 0) { $EmptySections += $Section }
        [void]$Checks.Add(@{ name = ('J3-四段:' + $Section); expected = '数组'; actual = $(if ($Property) { if ($IsArray) { 'array(' + $Count + ')' } else { 'not-array' } } else { 'missing' }); ok = $IsArray })
    }
    if (-not ($Checks | Where-Object { $_.name -like 'J3-四段:*' -and -not $_.ok } | Select-Object -First 1) -eq $false) {
        throw '四段载荷缺失或不是数组'
    }

    $CandidateSha = ''
    if ($Data.PSObject.Properties['candidateSha256']) { $CandidateSha = [string]$Data.candidateSha256 }
    $ShaOk = ((Get-Text $CandidateSha) -eq '') -or ($CandidateSha -match '^[0-9a-f]{64}$')
    [void]$Checks.Add(@{ name = 'J3-candidateSha256'; expected = '缺省或64位小写hex'; actual = $(if ((Get-Text $CandidateSha)) { $CandidateSha.Substring(0, [Math]::Min(12, $CandidateSha.Length)) + '…' } else { 'absent' }); ok = $ShaOk })
    if (-not $ShaOk) { throw 'candidateSha256 不是 64 位小写十六进制' }

    # R05/JOINT-003：Envelope摘要仅观测；-ExpectSha256只与冻结制品原件核对。
    $FrozenExists = [bool]((Get-Text $FrozenContextPackPath) -and (Test-Path -LiteralPath $FrozenContextPackPath -PathType Leaf))
    $Digest = Resolve-J3DigestDecision $ExpectSha256 $FrozenContextPackPath $FrozenExists $ObservedSha
    foreach ($DigestCheck in @($Digest.checks)) { [void]$Checks.Add($DigestCheck) }
    if ($Digest.verdict -ceq 'FAIL') { throw $Digest.reason }
    if ($Digest.verdict -ceq 'BLOCKED') { $Verdict = 'BLOCKED'; $Reason = $Digest.reason }
} catch {
    $Verdict = 'FAIL'
    $Reason = $_.Exception.Message
}

$Elapsed = [int]((Get-Date) - $Started).TotalMilliseconds
$Evidence = [ordered]@{
    suite        = 'EX-07 J3 context-pack fetch'
    verdict      = $Verdict
    reason       = $Reason
    startedAt    = $Started.ToString('o')
    elapsedMs    = $Elapsed
    inputs       = [ordered]@{ ontologyBase = $OntologyBase; releaseId = $ReleaseId; expectSha256 = $ExpectSha256; frozenContextPackPath = $FrozenContextPackPath }
    observedSha256 = $ObservedSha
    sectionCounts  = $SectionCounts
    emptySections  = @($EmptySections)
    checks       = @($Checks)
    boundaries   = @(
        'J3 只证明本体侧能给出真实、结构完整、可复算的包；不证明 AP 运行期 snapshot.contextPack 正确。',
        'snapshot.contextPack（space/releaseId/sha256/fetchedAt/truncated）需一次声明 ontology 的真实运行，属后续步骤。',
        '空段如实列出（emptySections），不把空包当"通过内容正确"。'
    )
}

Write-Host ''
Write-Host ('== J3 ' + $Verdict + '（' + $Elapsed + ' ms）==')
foreach ($Check in $Checks) {
    $Mark = if ($Check.ok) { 'PASS' } else { 'FAIL' }
    Write-Host ('  [' + $Mark + '] ' + $Check.name + ' | 期望 ' + $Check.expected + ' | 实际 ' + $Check.actual)
}
if ($EmptySections.Count -gt 0) { Write-Host ('  空段（需人工确认是否真的为空）：' + ($EmptySections -join ',')) }
if ($Verdict -in @('FAIL', 'BLOCKED')) { Write-Host ('  [' + $Verdict + '] 原因：' + $Reason) }

if ((Get-Text $EvidenceDir)) {
    if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null }
    $File = Join-Path $EvidenceDir ('j3-' + $Started.ToString('yyyyMMdd-HHmmss') + '.json')
    ($Evidence | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath $File -Encoding UTF8
    Write-Host ('  证据：' + $File)
}

if ($Verdict -eq 'PASS') { exit $ExitPass }
if ($Verdict -eq 'BLOCKED') { exit $ExitBlocked }
exit $ExitFail
