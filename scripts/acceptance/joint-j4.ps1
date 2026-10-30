<#
.SYNOPSIS
  EX-07 J4 的前置执行器：本体 release 清单 → 平台技能导入（两段式，前半段可独立跑）。

.DESCRIPTION
  J4 = "本体侧 manifest 被平台真正导入成技能草稿"。它跨两仓，且第二段要人身份（ADMIN JWT），
  所以本脚本把两段拆开、各自给结论：
    - 第一段（本体侧）：只读服务账号 GET /onto/releases/{id}/manifest，断言 200 且
      `data.manifests` 是数组、每项有 `toolCode`/`spaceCode`/`releaseId`，releaseId 与入参一致；
    - 第二段（平台侧）：带 ADMIN Bearer POST /admin/skills/import-ontology-manifests，
      断言返回逐项结果且没有失败项（`failed=0` 或每项 `ok=true`）。
  缺第二段的 token 时：仍然跑第一段（它是真实验证），然后整体判 **BLOCKED(3)** 并说明缺什么——
  不把"本体侧通了"说成 J4 通过。

  前置缺失（-ReleaseId 或只读 key）→ BLOCKED(3)，不发请求；断言失败 → 1；两段都通过 → 0。

.EXAMPLE
  $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = '<readonly key>'
  ./joint-j4.ps1 -ReleaseId <uuid> -AppBase http://127.0.0.1:18081 -BearerToken '<admin jwt>'
#>
[CmdletBinding()]
param(
    [string]$OntologyBase = 'http://127.0.0.1:18182',
    [string]$AppBase = 'http://127.0.0.1:18081',
    [string]$ReleaseId,
    [string]$BearerToken,
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

Add-Type -AssemblyName System.Net.Http

function Invoke-Json {
    param([string]$Method, [string]$Url, [hashtable]$Headers, [string]$Body)
    $Client = New-Object System.Net.Http.HttpClient
    $Client.Timeout = [TimeSpan]::FromSeconds(25)
    $Request = New-Object System.Net.Http.HttpRequestMessage
    $Response = $null
    try {
        $Request.Method = [System.Net.Http.HttpMethod]::new($Method)
        $Request.RequestUri = [Uri]$Url
        foreach ($Name in $Headers.Keys) { $Request.Headers.Add($Name, [string]$Headers[$Name]) }
        if ((Get-Text $Body)) {
            $Request.Content = New-Object System.Net.Http.StringContent($Body, [Text.Encoding]::UTF8, 'application/json')
        }
        $Response = $Client.SendAsync($Request).GetAwaiter().GetResult()
        $Text = $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $Parsed = $null
        if ((Get-Text $Text)) { try { $Parsed = $Text | ConvertFrom-Json } catch { $Parsed = $null } }
        [pscustomobject]@{
            httpStatus = [int]$Response.StatusCode
            raw        = $Text
            body       = $Parsed
            code       = $(if ($Parsed -and $Parsed.PSObject.Properties['code']) { [string]$Parsed.code } else { '' })
        }
    } finally {
        if ($Response) { $Response.Dispose() }
        $Request.Dispose()
        $Client.Dispose()
    }
}

$Checks = New-Object System.Collections.ArrayList
$Verdict = 'PASS'
$Reason = ''
$Missing = @()
if (-not (Get-Text $ReleaseId)) { $Missing += '-ReleaseId' }
elseif ($ReleaseId -notmatch $UuidPattern) { $Missing += '-ReleaseId(需UUID)' }
if (-not (Get-Text $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY)) { $Missing += '环境变量 AGENT_SECRET_ONTO_ENGINE_READ_KEY' }
if ($Missing.Count -gt 0) {
    Write-Host '[BLOCKED] J4 第一段前置未满足，未发出任何请求：'
    foreach ($Item in $Missing) { Write-Host ('  - ' + $Item) }
    exit $ExitBlocked
}

$Started = Get-Date
$Manifests = @()
try {
    # 第一段：本体清单（只读服务账号）
    $ManifestUrl = $OntologyBase + '/onto/releases/' + $ReleaseId + '/manifest'
    $Fetch = Invoke-Json 'GET' $ManifestUrl @{ 'X-API-Key' = $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY } ''
    [void]$Checks.Add(@{ name = 'J4a-取清单'; expected = '200'; actual = [string]$Fetch.httpStatus; ok = ($Fetch.httpStatus -eq 200) })
    if ($Fetch.httpStatus -ne 200) { throw ('manifest 未返回 200：' + $Fetch.httpStatus + ' ' + $Fetch.code) }

    $Data = $null
    if ($Fetch.body -and $Fetch.body.PSObject.Properties['data']) { $Data = $Fetch.body.data }
    $Items = $null
    if ($Data -and $Data.PSObject.Properties['manifests']) { $Items = $Data.manifests }
    $IsArray = ($Items -is [System.Array])
    [void]$Checks.Add(@{ name = 'J4a-manifests'; expected = '数组'; actual = $(if ($IsArray) { 'array(' + $Items.Count + ')' } else { 'missing/not-array' }); ok = $IsArray })
    if (-not $IsArray) { throw 'manifest 载荷缺少 manifests 数组' }

    $BadItems = @()
    foreach ($Item in $Items) {
        $HasKeys = ($Item.PSObject.Properties['toolCode']) -and ($Item.PSObject.Properties['spaceCode']) -and ($Item.PSObject.Properties['releaseId'])
        $SameRelease = $HasKeys -and ([string]$Item.releaseId -eq $ReleaseId)
        if (-not ($HasKeys -and $SameRelease)) { $BadItems += [string]$Item.toolCode }
    }
    [void]$Checks.Add(@{ name = 'J4a-逐项字段'; expected = 'toolCode+spaceCode+releaseId 且 releaseId 与入参一致'; actual = $(if ($BadItems.Count -eq 0) { 'all-ok(' + $Items.Count + ')' } else { 'bad:' + (($BadItems | Select-Object -First 3) -join ',') }); ok = ($BadItems.Count -eq 0 -and $Items.Count -gt 0) })
    if ($BadItems.Count -gt 0) { throw '清单项字段不全或 releaseId 不一致（可能导致平台导错版本）' }
    if ($Items.Count -eq 0) { throw '清单为空：该 release 没有可导入的 operation 工具' }
    $Manifests = $Items

    # 第二段：平台导入（人身份）
    if (-not (Get-Text $BearerToken)) {
        $Verdict = 'BLOCKED'
        $Reason = '第一段（本体清单）已通过；第二段需要 ADMIN Bearer token（-BearerToken）才能真导入'
    } else {
        $ImportUrl = $AppBase + '/admin/skills/import-ontology-manifests'
        $Payload = @{ spaceCode = [string]$Manifests[0].spaceCode; releaseId = $ReleaseId; manifests = @($Manifests) } | ConvertTo-Json -Depth 10
        $Import = Invoke-Json 'POST' $ImportUrl @{ Authorization = ('Bearer ' + $BearerToken) } $Payload
        [void]$Checks.Add(@{ name = 'J4b-导入'; expected = '200/201'; actual = [string]$Import.httpStatus; ok = ($Import.httpStatus -eq 200 -or $Import.httpStatus -eq 201) })
        if (-not ($Import.httpStatus -eq 200 -or $Import.httpStatus -eq 201)) { throw ('导入未成功：' + $Import.httpStatus + ' ' + $Import.code) }

        $ImportData = $null
        if ($Import.body -and $Import.body.PSObject.Properties['data']) { $ImportData = $Import.body.data }
        $Created = 0
        $Failed = 0
        if ($ImportData) {
            if ($ImportData.PSObject.Properties['created']) { $Created = [int]$ImportData.created }
            if ($ImportData.PSObject.Properties['failed']) { $Failed = [int]$ImportData.failed }
        }
        [void]$Checks.Add(@{ name = 'J4b-逐项结果'; expected = 'failed=0 且 created>0'; actual = ('created=' + $Created + ' failed=' + $Failed); ok = ($Failed -eq 0 -and $Created -gt 0) })
        if ($Failed -ne 0) { throw ('导入有失败项：failed=' + $Failed) }
        if ($Created -le 0) { throw '导入未创建任何技能草稿（created=0）' }
    }
} catch {
    if ($Verdict -ne 'BLOCKED') { $Verdict = 'FAIL' }
    $Reason = $_.Exception.Message
}

$Evidence = [ordered]@{
    suite      = 'EX-07 J4 manifest import'
    verdict    = $Verdict
    reason     = $Reason
    startedAt  = $Started.ToString('o')
    inputs     = [ordered]@{ ontologyBase = $OntologyBase; appBase = $AppBase; releaseId = $ReleaseId; bearerProvided = [bool](Get-Text $BearerToken) }
    manifestCount = $Manifests.Count
    toolCodes  = @($Manifests | ForEach-Object { [string]$_.toolCode })
    checks     = @($Checks)
    boundaries = @(
        '第一段只证明本体清单可取且字段自洽；不证明平台导入成功。',
        '第二段需要 ADMIN 人身份；没有 token 时整体判 BLOCKED，不冒充 J4 通过。',
        '导入产生的是 DRAFT 技能，仍需连接测试与验收才能发布（hit_rate>=0.8）。'
    )
}

Write-Host ''
Write-Host ('== J4 ' + $Verdict + ' ==')
foreach ($Check in $Checks) {
    Write-Host ('  [' + $(if ($Check.ok) { 'PASS' } else { 'FAIL' }) + '] ' + $Check.name + ' | 期望 ' + $Check.expected + ' | 实际 ' + $Check.actual)
}
if ($Verdict -ne 'PASS') { Write-Host ('  说明：' + $Reason) }

if ((Get-Text $EvidenceDir)) {
    if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null }
    $File = Join-Path $EvidenceDir ('j4-' + $Started.ToString('yyyyMMdd-HHmmss') + '.json')
    ($Evidence | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath $File -Encoding UTF8
    Write-Host ('  证据：' + $File)
}

switch ($Verdict) {
    'PASS' { exit $ExitPass }
    'BLOCKED' { exit $ExitBlocked }
    default { exit $ExitFail }
}
