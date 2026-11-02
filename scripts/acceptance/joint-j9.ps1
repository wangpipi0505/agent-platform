<#
.SYNOPSIS
  EX-07 J9：平台真实发布 → pin。把 pinAppCode 的冻结 releaseId 从旧版改成 replacement，
  重新评测/预检/commit，断言 AP 的 pin 与 OR 内部绑定一致；再用同一 requestNo 重放，
  断言不产生重复版本/重复绑定；pin 落库状态可查，SKIPPED/DISABLED/FAILED 不算成功。

.DESCRIPTION
  按手册 §4 J9 原检查项与 §3「J9 以 pinApp 的新候选把冻结 releaseId 从旧版改 replacement」：
    1) 读 pinApp：config.ontology.releaseId 必须是旧版，pin-status 必须是 PINNED（旧版）；
    2) PUT config：只改 ontology.releaseId → replacement，断言 draftRev+1、candidateSha256 变化；
    3) POST /admin/evaluations（真实评测）并轮询到 ACCEPTED，超时 → BLOCKED；
    4) POST publish/precheck → PASS / gateMode BLOCKING；
    5) POST publish/commit（新 requestNo）→ versionNo+1、pin.state=PINNED、bindingId 非空、changed=true；
    6) 读回 pin-status 与 OR 内部绑定，断言 releaseId/bindingId/versionNo 三方一致；
    7) 同一 requestNo 重放 commit → 版本号与 bindingId 不变、版本行数不增、pin 行不变；
    8) pin 状态严格等于 PINNED：SKIPPED/DISABLED/FAILED 或 fail-closed 502 PIN_FAILED 都判 FAIL。

  缺输入 → [BLOCKED] 退出码 3（不发请求）；断言失败 → 1；通过 → 0。
  证据只记录状态码/业务码/ID/哈希，不记录 key/token 原文。

.PARAMETER ReplacementReleaseId
  新的 pin 目标 release（manifest.replacementReleaseId）。调用方必须显式传 `-LivePublicationApproved`
  才发真实发布请求；本段本身不创建 release，也不改 Java 产品代码。

.PARAMETER EvaluationPlanPath
  可选评测数据集 JSON：{datasetVersion, cases:[{id,inputs.message,expected,safety,assertion}]}。
  真实环境应提供本应用能命中的用例；不提供时用内置确定性两用例（仅适合本地桩）。

.EXAMPLE
  ./joint-j9.ps1 -SpaceId <uuid> -ReleaseId <old uuid> -ReplacementReleaseId <new uuid> `
      -PinAppCode joint_xxx_pin -LivePublicationApproved -EvaluationPlanPath fixtures/j9-eval.json
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
    [string]$PinAppCode,
    [string]$BinderKey,
    [string]$BinderKeyEnv = 'AGENT_SECRET_ONTO_APP_BINDER_KEY',
    [string]$BearerToken,
    [string]$BearerTokenEnv = 'AGENT_ACCEPTANCE_BEARER',
    [string]$CredentialsFile,
    [string]$EvaluationPlanPath,
    [int]$PollTimeoutSeconds = 300,
    [int]$PollIntervalSeconds = 2,
    [string]$EvidenceDir,
    [string]$SessionId,
    [switch]$LivePublicationApproved
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
$PrecheckPattern = '^pc_[0-9a-fA-F-]{36}$'
$ReportPattern = '^er_[0-9a-fA-F-]{36}$'
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
        [int]$Timeout = 60
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
        ($record | ConvertTo-Json -Depth 14) | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    }
    Write-Output ($Stage + ' verdict=' + $FinalVerdict)
    if ($FinalReason) { Write-Output ($Stage + ' reason=' + $FinalReason) }
    if ($evidencePath) { Write-Output ($Stage + ' evidence=' + $evidencePath) }
    if ($FinalVerdict -eq 'PASS') { exit $ExitPass }
    if ($FinalVerdict -eq 'BLOCKED') { exit $ExitBlocked }
    exit $ExitFail
}

function Get-PlatformHeaders {
    return @{ Authorization = ('Bearer ' + $BearerTokenValue) }
}

function Get-Platform([string]$Path) {
    $response = Invoke-StageHttp -Method 'GET' -Url ($PlatformBase.TrimEnd('/') + $Path) -Headers (Get-PlatformHeaders)
    return (Test-Envelope $response @(200) 'OK')
}

function Send-Platform([string]$Method, [string]$Path, [object]$Body, [int[]]$Status) {
    $response = Invoke-StageHttp -Method $Method -Url ($PlatformBase.TrimEnd('/') + $Path) -Headers (Get-PlatformHeaders) -Body $Body
    return (Test-Envelope $response $Status 'OK')
}

# ---- 前置输入 ----
$BinderKeyValue = Resolve-StageKey $BinderKey $BinderKeyEnv $CredentialsFile 'BINDER'
$BearerTokenValue = Resolve-StageKey $BearerToken $BearerTokenEnv $CredentialsFile 'BEARER'
$Missing = @()
foreach ($pair in @(
        @{ Name = '-SpaceId'; Value = $SpaceId },
        @{ Name = '-ReleaseId'; Value = $ReleaseId },
        @{ Name = '-ReplacementReleaseId'; Value = $ReplacementReleaseId })) {
    if (-not (Get-Text $pair.Value)) { $Missing += $pair.Name } elseif ($pair.Value -notmatch $UuidPattern) { $Missing += ($pair.Name + '(需UUID)') }
}
if ((Get-Text $ReplacementReleaseId) -and ((Get-Text $ReplacementReleaseId) -eq (Get-Text $ReleaseId))) { $Missing += '-ReplacementReleaseId(必须不同于-ReleaseId)' }
if (-not (Get-Text $PinAppCode)) { $Missing += '-PinAppCode' } elseif ($PinAppCode -notmatch $CodePattern) { $Missing += '-PinAppCode(形状非法)' }
if (-not $BinderKeyValue) { $Missing += 'binder凭据(-BinderKey 或 ' + $BinderKeyEnv + ')' } elseif ($BinderKeyValue -notmatch $ServiceKeyPattern) { $Missing += 'binder凭据(需 ontosa_ 前缀加 43 位)' }
if (-not $BearerTokenValue) { $Missing += 'AP 登录 token(-BearerToken 或 ' + $BearerTokenEnv + ')' }
if ($OntologyBase -notmatch '^https?://[^/]+$') { $Missing += '-OntologyBase(需 scheme+host:port，不含路径)' }
if ($PlatformBase -notmatch '^https?://[^/]+$') { $Missing += '-PlatformBase(需 scheme+host:port，不含路径)' }
if (-not $LivePublicationApproved) { $Missing += '-LivePublicationApproved(真实发布需显式批准)' }

$stageInputs = [ordered]@{
    ontologyBase = $OntologyBase
    platformBase = $PlatformBase
    platformTenant = $PlatformTenant
    environment = $Environment
    spaceId = (Get-Text $SpaceId)
    spaceCode = (Get-Text $SpaceCode)
    releaseId = (Get-Text $ReleaseId)
    replacementReleaseId = (Get-Text $ReplacementReleaseId)
    pinAppCode = (Get-Text $PinAppCode)
    binderKeyProvided = [bool]$BinderKeyValue
    bearerTokenProvided = [bool]$BearerTokenValue
    evaluationPlanPath = (Get-Text $EvaluationPlanPath)
    livePublicationApproved = [bool]$LivePublicationApproved
    sessionId = (Get-Text $SessionId)
}

$stageBoundaries = @(
    'J9 的“请求含 spaceId”由 AP ReleasePins 固定载荷（spaceId+platformTenant+appCode+environment+releaseId）与 OR 的凭据空间校验共同保证；本段在 HTTP 侧不做抓包，只以绑定成功且三方一致作为运行期证据。',
    'pin 失败响应/落库可查：本段断言 pin-status 可读且状态严格为 PINNED；fail-closed 502 PIN_FAILED 判 FAIL。DISABLED/SKIPPED/FAILED 的“不算成功”由严格等值断言与本地桩 pin-skipped/pin-failed 模式证明，真实环境未主动制造坏 pin。',
    '本段不创建 release、不改 Java 产品代码；ReplacementReleaseId 必须是 Prepare 已准备好的第二个 READY release。'
)

if ($Missing.Count -gt 0) {
    Write-Output '[BLOCKED] J9 前置未满足，未发出任何请求：'
    foreach ($Item in $Missing) { Write-Output ('  - ' + $Item) }
    Complete-Stage 'J9' 'BLOCKED' ('缺外部输入: ' + ($Missing -join '; ')) $stageInputs $stageBoundaries
}

# ---- 评测数据集 ----
# 无 -EvaluationPlanPath 时用 TestFixture 合成集：必须在输入/证据里明确标注 modelMode=TestFixture，
# 不得让“桩数据评测”读起来像真实模型评测（卡片要求）。
$cases = @()
$datasetVersion = ''
$modelMode = 'TestFixture'
if (Get-Text $EvaluationPlanPath) {
    $modelMode = 'Real'
    if (-not (Test-Path -LiteralPath $EvaluationPlanPath -PathType Leaf)) {
        Complete-Stage 'J9' 'BLOCKED' ('缺外部输入: -EvaluationPlanPath 不存在: ' + $EvaluationPlanPath) $stageInputs $stageBoundaries
    }
    $plan = Get-Content -LiteralPath $EvaluationPlanPath -Raw | ConvertFrom-Json
    $datasetVersion = Get-Text $plan.datasetVersion
    $cases = @($plan.cases)
    if (-not $datasetVersion) { Complete-Stage 'J9' 'BLOCKED' '缺外部输入: 评测计划缺 datasetVersion' $stageInputs $stageBoundaries }
    if ($cases.Count -lt 2 -or $cases.Count -gt 200) { Complete-Stage 'J9' 'BLOCKED' ('缺外部输入: 评测用例数需 2..200，实际 ' + $cases.Count) $stageInputs $stageBoundaries }
} else {
    $seedSource = (Get-Text $SessionId)
    if (-not $seedSource) { $seedSource = $Started.ToString('yyyyMMddHHmmss') }
    $sha = [Security.Cryptography.SHA256]::Create()
    $hashBytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($seedSource))
    $seed = -join ($hashBytes[0..11] | ForEach-Object { $_.ToString('x2') })
    $datasetVersion = 'joint_' + $seed + '_eval_j9_v1'
    # 运行输出是对象 {text,usage,actualModel}，拿字符串做 EXACT/CONTAINS 必然对不上
    # （复审 18:0x P1·J9）。这里复用候选实现里既有的 text 常量 Schema 形状：
    #   expected = {type:'object', required:['text'], properties:{text:{type:'string', const:<常量>}}}
    # 断言统一用 SCHEMA（安全例产品只允许 EXACT/SCHEMA，见 EvaluationAssertions.java:31/135）；
    # 正例常量对应默认模型桩的正常回答 EX06_OK，安全例对应拒绝回答 EX06_REFUSE。
    $textSchema = {
        param([string]$Const)
        return [ordered]@{
            type = 'object'
            required = @('text')
            properties = [ordered]@{ text = [ordered]@{ type = 'string'; const = $Const } }
        }
    }
    $cases = @(
        [ordered]@{ id = 'j9_pin'; inputs = [ordered]@{ message = ('EX06_TOOL:' + (Get-Text $PinAppCode)) }; expected = (& $textSchema 'EX06_OK'); safety = $false; assertion = 'SCHEMA' },
        [ordered]@{ id = 'j9_safety'; inputs = [ordered]@{ message = 'EX06_SAFETY' }; expected = (& $textSchema 'EX06_REFUSE'); safety = $true; assertion = 'SCHEMA' }
    )
}
$stageInputs['modelMode'] = $modelMode
$stageInputs['datasetVersion'] = $datasetVersion
Add-Check 'J9-评测数据来源' $true ('modelMode=' + $modelMode + ' datasetVersion=' + $datasetVersion)

# ---- 1) 读 pinApp 与既有 pin ----
$appData = $null
$baselineVersionNo = 0
$baselineBindingId = ''
if ($Verdict -eq 'PASS') {
    $app = Get-Platform ('/admin/apps/' + $PinAppCode)
    Add-Check 'J9-读pinApp' $app.Ok ('HTTP ' + $app.Status + ' code=' + $app.Code)
    if (-not $app.Ok) {
        $Verdict = 'FAIL'
        $Reason = '读取 pinApp 失败：HTTP ' + $app.Status + ' ' + $app.Code + ' ' + $app.Message
    } else {
        $appData = $app.Data
        $config = $appData.config
        $ontology = $null
        if ($null -ne $config) { $ontology = $config.ontology }
        $frozenRelease = ''
        if ($null -ne $ontology) { $frozenRelease = Get-Text $ontology.releaseId }
        Add-Check 'J9-pinApp冻结旧release' ($frozenRelease -ceq (Get-Text $ReleaseId)) ('config.ontology.releaseId=' + $frozenRelease)
        if ($frozenRelease -cne (Get-Text $ReleaseId)) {
            $Verdict = 'BLOCKED'
            $Reason = 'pinApp 的冻结 releaseId 不是旧版（' + $frozenRelease + '），无法用新候选验证 pin 从旧版改 replacement；请先由 Prepare 发布到旧版或改用新 replacement'
        }
        $baselineVersionNo = 0
        if ($null -ne $appData.currentVersionNo) { $baselineVersionNo = [int]$appData.currentVersionNo }
        $Details['pinApp'] = [ordered]@{ status = (Get-Text $appData.status); draftRev = (Get-Text $appData.draftRev); currentVersionNo = $baselineVersionNo; candidateSha256 = (Get-Text $appData.candidateSha256) }
    }
}

if ($Verdict -eq 'PASS') {
    $pin = Get-Platform ('/admin/apps/' + $PinAppCode + '/pin-status')
    Add-Check 'J9-pin落库可查' $pin.Ok ('HTTP ' + $pin.Status + ' code=' + $pin.Code)
    if ($pin.Ok -and $null -ne $pin.Data) {
        $pinState = Get-Text $pin.Data.state
        $pinRelease = Get-Text $pin.Data.releaseId
        $baselineBindingId = Get-Text $pin.Data.bindingId
        $pinReason = Get-Text $pin.Data.reason
        Add-Check 'J9-基线pin为PINNED旧版' (($pinState -ceq 'PINNED') -and ($pinRelease -ceq (Get-Text $ReleaseId))) ('state=' + $pinState + ' releaseId=' + $pinRelease + ' reason=' + $pinReason)
        Add-Check 'J9-基线bindingId非空' ($baselineBindingId -match $UuidPattern) ('bindingId=' + $baselineBindingId)
        $Details['baselinePin'] = [ordered]@{ state = $pinState; releaseId = $pinRelease; bindingId = $baselineBindingId; versionNo = (Get-Text $pin.Data.versionNo); reason = $pinReason }
        if ($pinState -cne 'PINNED') {
            $Verdict = 'FAIL'
            $Reason = 'pinApp 基线 pin 不是 PINNED（' + $pinState + ' ' + $pinReason + '）：SKIPPED/DISABLED/FAILED 都不算成功'
        }
    } elseif ($pin.Ok) {
        $Verdict = 'BLOCKED'
        $Reason = 'pinApp 没有 app.publish_pin 记录（pin-status 返回空），无法验证 pin 落库一致；应先由 Prepare 发布 pinApp'
    } else {
        $Verdict = 'FAIL'
        $Reason = '读取 pin-status 失败：HTTP ' + $pin.Status + ' ' + $pin.Code
    }
}

# ---- 2) 改 config 到 replacement ----
$newDraftRev = 0
$newCandidateSha = ''
if ($Verdict -eq 'PASS') {
    $saveConfig = $appData.config
    $saveConfig.ontology.releaseId = (Get-Text $ReplacementReleaseId)
    $body = [ordered]@{ draftRev = $appData.draftRev; config = $saveConfig }
    $saved = Send-Platform 'PUT' ('/admin/apps/' + $PinAppCode + '/config') $body @(200)
    Add-Check 'J9-保存replacement配置' $saved.Ok ('HTTP ' + $saved.Status + ' code=' + $saved.Code + ' ' + $saved.Message)
    if (-not $saved.Ok) {
        $Verdict = 'FAIL'
        $Reason = '保存新候选配置失败：HTTP ' + $saved.Status + ' ' + $saved.Code
    } else {
        $newDraftRev = [int]$saved.Data.draftRev
        $newCandidateSha = Get-Text $saved.Data.candidateSha256
        Add-Check 'J9-draftRev递增' ($newDraftRev -eq ([int]$appData.draftRev + 1)) ('draftRev ' + $appData.draftRev + ' -> ' + $newDraftRev)
        Add-Check 'J9-candidateSha变化' (($newCandidateSha -match '^[0-9a-f]{64}$') -and ($newCandidateSha -ne (Get-Text $appData.candidateSha256))) ('sha=' + $newCandidateSha)
        Add-Check 'J9-配置指向replacement' ((Get-Text $saved.Data.config.ontology.releaseId) -ceq (Get-Text $ReplacementReleaseId)) ('releaseId=' + (Get-Text $saved.Data.config.ontology.releaseId))
        $Details['newCandidate'] = [ordered]@{ draftRev = $newDraftRev; candidateSha256 = $newCandidateSha }
        if ($newDraftRev -ne ([int]$appData.draftRev + 1) -or $newCandidateSha -notmatch '^[0-9a-f]{64}$') {
            $Verdict = 'FAIL'
            $Reason = '新候选未正确生成'
        }
    }
}

# ---- 3) 真实评测并轮询 ACCEPTED ----
$evalReportId = ''
if ($Verdict -eq 'PASS') {
    $evalBody = [ordered]@{
        appCode = (Get-Text $PinAppCode)
        draftRev = $newDraftRev
        candidateSha256 = $newCandidateSha
        datasetVersion = $datasetVersion
        cases = $cases
    }
    $submitted = Send-Platform 'POST' '/admin/evaluations' $evalBody @(200)
    Add-Check 'J9-提交评测' $submitted.Ok ('HTTP ' + $submitted.Status + ' code=' + $submitted.Code + ' ' + $submitted.Message)
    if (-not $submitted.Ok) {
        $Verdict = 'FAIL'
        $Reason = '提交评测失败：HTTP ' + $submitted.Status + ' ' + $submitted.Code + ' ' + $submitted.Message
    } else {
        $evalReportId = Get-Text $submitted.Data.id
        Add-Check 'J9-evalReportId形状' ($evalReportId -match $ReportPattern) ('evalReportId=' + $evalReportId)
        $deadline = (Get-Date).AddSeconds($PollTimeoutSeconds)
        $report = $null
        while ($true) {
            $poll = Get-Platform ('/admin/evaluations/' + $evalReportId)
            if ($poll.Ok -and $null -ne $poll.Data) { $report = $poll.Data }
            $status = ''
            if ($null -ne $report) { $status = Get-Text $report.status }
            if ($status -in @('ACCEPTED', 'REJECTED', 'ERROR')) { break }
            if ((Get-Date) -ge $deadline) { break }
            Start-Sleep -Seconds $PollIntervalSeconds
        }
        if ($null -eq $report) {
            $Verdict = 'FAIL'
            $Reason = '评测报告不可读（evalReportId=' + $evalReportId + '）'
        } else {
            $finalStatus = Get-Text $report.status
            if ($finalStatus -ceq 'RUNNING') {
                $Verdict = 'BLOCKED'
                $Reason = '评测在 ' + $PollTimeoutSeconds + 's 内未完成（evalReportId=' + $evalReportId + '），真实评测需要可用模型'
            } elseif ($finalStatus -cne 'ACCEPTED') {
                $Verdict = 'FAIL'
                $Reason = '评测未通过：status=' + $finalStatus + ' accepted=' + (Get-Text $report.accepted)
            } else {
                Add-Check 'J9-评测ACCEPTED' (($finalStatus -ceq 'ACCEPTED') -and ($report.accepted -eq $true)) ('status=' + $finalStatus + ' accepted=' + $report.accepted)
                Add-Check 'J9-评测绑定新候选' (((Get-Text $report.appCode) -ceq (Get-Text $PinAppCode)) -and ([int]$report.draftRev -eq $newDraftRev) -and ((Get-Text $report.candidateSha256) -ceq $newCandidateSha) -and ((Get-Text $report.datasetVersion) -ceq $datasetVersion)) ('appCode=' + (Get-Text $report.appCode) + ' draftRev=' + (Get-Text $report.draftRev) + ' datasetVersion=' + (Get-Text $report.datasetVersion))
                $Details['evaluation'] = [ordered]@{ evalReportId = $evalReportId; status = $finalStatus; accepted = $report.accepted; datasetVersion = $datasetVersion }
            }
        }
    }
}

# ---- 4) 预检 ----
$precheckId = ''
$confirmationRequired = $false
if ($Verdict -eq 'PASS') {
    $precheck = Send-Platform 'POST' ('/admin/apps/' + $PinAppCode + '/publish/precheck') $null @(200)
    Add-Check 'J9-发布预检' $precheck.Ok ('HTTP ' + $precheck.Status + ' code=' + $precheck.Code + ' ' + $precheck.Message)
    if (-not $precheck.Ok) {
        $Verdict = 'FAIL'
        $Reason = '发布预检未通过：HTTP ' + $precheck.Status + ' ' + $precheck.Code + ' ' + $precheck.Message
    } else {
        $precheckId = Get-Text $precheck.Data.precheckId
        $confirmationRequired = [bool]$precheck.Data.environmentConfirmationRequired
        Add-Check 'J9-预检PASS' (($precheck.Data.passed -eq $true) -and ((Get-Text $precheck.Data.result) -ceq 'PASS') -and ((Get-Text $precheck.Data.gateMode) -ceq 'BLOCKING')) ('passed=' + $precheck.Data.passed + ' result=' + (Get-Text $precheck.Data.result) + ' gateMode=' + (Get-Text $precheck.Data.gateMode))
        Add-Check 'J9-预检绑定新候选' (((Get-Text $precheck.Data.evalReportId) -ceq $evalReportId) -and ([int]$precheck.Data.draftRev -eq $newDraftRev) -and ((Get-Text $precheck.Data.candidateSha256) -ceq $newCandidateSha)) ('evalReportId=' + (Get-Text $precheck.Data.evalReportId))
        $Details['precheck'] = [ordered]@{ precheckId = $precheckId; publicationTarget = (Get-Text $precheck.Data.publicationTarget); evaluationEnvironment = (Get-Text $precheck.Data.evaluationEnvironment); environmentConfirmationRequired = $confirmationRequired }
        if (-not (($precheck.Data.passed -eq $true) -and ((Get-Text $precheck.Data.result) -ceq 'PASS'))) {
            $Verdict = 'FAIL'
            $Reason = '预检结果不是 PASS'
        }
    }
}

# ---- 5) commit（真实发布 → pin） ----
$versionNo = 0
$pinBindingId = ''
$requestNo = ''
if ($Verdict -eq 'PASS') {
    $sha = [Security.Cryptography.SHA256]::Create()
    $hashBytes = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes((Get-Text $SessionId) + ':' + (Get-Text $PinAppCode) + ':' + $precheckId))
    $suffix = -join ($hashBytes[0..19] | ForEach-Object { $_.ToString('x2') })
    $requestNo = 'ex06-j9-' + $suffix
    $commitBody = [ordered]@{
        idempotencyRequestNo = $requestNo
        precheckId = $precheckId
        candidateSha256 = $newCandidateSha
        draftRev = $newDraftRev
    }
    if ($confirmationRequired) {
        $commitBody['evaluationEnvironmentConfirmation'] = [ordered]@{
            confirmed = $true
            precheckId = $precheckId
            evalReportId = $evalReportId
            evidenceSha256 = (Get-Text $precheck.Data.environmentEvidenceSha256)
        }
    }
    $committed = Send-Platform 'POST' ('/admin/apps/' + $PinAppCode + '/publish/commit') $commitBody @(200, 502)
    $commitCode = $committed.Code
    Add-Check 'J9-发布commit' ($committed.Ok -or (($committed.Status -eq 502) -and ($commitCode -ceq 'PIN_FAILED'))) ('HTTP ' + $committed.Status + ' code=' + $commitCode + ' ' + $committed.Message)
    if (($committed.Status -eq 502) -and ($commitCode -ceq 'PIN_FAILED')) {
        $Verdict = 'FAIL'
        $Reason = '发布时 pin 失败（fail-closed 502 PIN_FAILED）：pin 未成功，本段不算通过'
    } elseif (-not $committed.Ok) {
        $Verdict = 'FAIL'
        $Reason = '发布 commit 失败：HTTP ' + $committed.Status + ' ' + $commitCode + ' ' + $committed.Message
    } else {
        $versionNo = [int]$committed.Data.versionNo
        $pinState = Get-Text $committed.Data.pin.state
        $pinBindingId = Get-Text $committed.Data.pin.bindingId
        $pinChanged = $committed.Data.pin.changed
        Add-Check 'J9-commit版本递增' ($versionNo -eq ($baselineVersionNo + 1)) ('versionNo ' + $baselineVersionNo + ' -> ' + $versionNo)
        Add-Check 'J9-pin为PINNED' ($pinState -ceq 'PINNED') ('pin.state=' + $pinState + ' reason=' + (Get-Text $committed.Data.pin.reason))
        Add-Check 'J9-pin-bindingId非空' ($pinBindingId -match $UuidPattern) ('bindingId=' + $pinBindingId)
        Add-Check 'J9-pin-changed=true' ($pinChanged -eq $true) ('pin.changed=' + (Get-Text $pinChanged))
        $Details['commit'] = [ordered]@{ requestNo = $requestNo; versionNo = $versionNo; pinState = $pinState; pinBindingId = $pinBindingId; pinChanged = $pinChanged; pinReason = (Get-Text $committed.Data.pin.reason) }
        if ($pinState -cne 'PINNED' -or $pinBindingId -notmatch $UuidPattern -or $pinChanged -ne $true) {
            $Verdict = 'FAIL'
            $Reason = '发布返回的 pin 不满足 PINNED + bindingId + changed=true（state=' + $pinState + ' changed=' + (Get-Text $pinChanged) + '）'
        }
    }
}

# ---- 6) pin-status 与 OR 内部绑定一致 ----
if ($Verdict -eq 'PASS') {
    $pin = Get-Platform ('/admin/apps/' + $PinAppCode + '/pin-status')
    $pinState = Get-Text $pin.Data.state
    $pinRelease = Get-Text $pin.Data.releaseId
    $pinBinding = Get-Text $pin.Data.bindingId
    $pinVersion = Get-Text $pin.Data.versionNo
    Add-Check 'J9-pin-status一致' ($pin.Ok -and ($pinState -ceq 'PINNED') -and ($pinRelease -ceq (Get-Text $ReplacementReleaseId)) -and ($pinBinding -ceq $pinBindingId) -and ([int]$pinVersion -eq $versionNo)) ('state=' + $pinState + ' releaseId=' + $pinRelease + ' bindingId=' + $pinBinding + ' versionNo=' + $pinVersion)
    $Details['pinStatus'] = [ordered]@{ state = $pinState; releaseId = $pinRelease; bindingId = $pinBinding; versionNo = $pinVersion }

    $query = '?platformTenant=' + [Uri]::EscapeDataString($PlatformTenant) +
        '&appCode=' + [Uri]::EscapeDataString((Get-Text $PinAppCode)) +
        '&environment=' + [Uri]::EscapeDataString($Environment)
    $bindingResponse = Invoke-StageHttp -Method 'GET' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/release-bindings' + $query) -Headers @{ 'X-API-Key' = $BinderKeyValue }
    $binding = Test-Envelope $bindingResponse @(200) 'OK'
    $orBindingId = Get-Text $binding.Data.bindingId
    $orRelease = Get-Text $binding.Data.releaseId
    Add-Check 'J9-OR绑定落库一致' ($binding.Ok -and ($orRelease -ceq (Get-Text $ReplacementReleaseId)) -and ($orBindingId -ceq $pinBindingId)) ('HTTP ' + $binding.Status + ' code=' + $binding.Code + ' releaseId=' + $orRelease + ' bindingId=' + $orBindingId)
    $Details['ontologyBinding'] = [ordered]@{ httpStatus = $binding.Status; code = $binding.Code; bindingId = $orBindingId; releaseId = $orRelease }
    if (-not ($binding.Ok -and ($orRelease -ceq (Get-Text $ReplacementReleaseId)) -and ($orBindingId -ceq $pinBindingId))) {
        $Verdict = 'FAIL'
        $Reason = 'AP pin 与 OR 内部绑定不一致（AP bindingId=' + $pinBindingId + ' OR bindingId=' + $orBindingId + '）'
    }
}

# ---- 7) 同 requestNo 重放不重复副作用 ----
if ($Verdict -eq 'PASS') {
    $versionsBefore = Get-Platform ('/admin/apps/' + $PinAppCode + '/versions')
    $countBefore = 0
    if ($versionsBefore.Ok) { $countBefore = @($versionsBefore.Data).Count }

    $replayBody = [ordered]@{
        idempotencyRequestNo = $requestNo
        precheckId = $precheckId
        candidateSha256 = $newCandidateSha
        draftRev = $newDraftRev
    }
    if ($confirmationRequired) {
        $replayBody['evaluationEnvironmentConfirmation'] = [ordered]@{
            confirmed = $true
            precheckId = $precheckId
            evalReportId = $evalReportId
            evidenceSha256 = (Get-Text $precheck.Data.environmentEvidenceSha256)
        }
    }
    $replay = Send-Platform 'POST' ('/admin/apps/' + $PinAppCode + '/publish/commit') $replayBody @(200)
    Add-Check 'J9-重放commit幂等' $replay.Ok ('HTTP ' + $replay.Status + ' code=' + $replay.Code)
    if ($replay.Ok) {
        $replayVersion = [int]$replay.Data.versionNo
        $replayPinState = Get-Text $replay.Data.pin.state
        $replayBinding = Get-Text $replay.Data.pin.bindingId
        $replayChanged = $replay.Data.pin.changed
        Add-Check 'J9-重放同版本' ($replayVersion -eq $versionNo) ('versionNo=' + $replayVersion + ' 期望=' + $versionNo)
        Add-Check 'J9-重放不重复绑定' (($replayPinState -ceq 'PINNED') -and ($replayBinding -ceq $pinBindingId) -and ($replayChanged -ne $true)) ('state=' + $replayPinState + ' bindingId=' + $replayBinding + ' changed=' + (Get-Text $replayChanged))
        $Details['replay'] = [ordered]@{ versionNo = $replayVersion; pinState = $replayPinState; bindingId = $replayBinding; changed = (Get-Text $replayChanged) }

        $versionsAfter = Get-Platform ('/admin/apps/' + $PinAppCode + '/versions')
        $countAfter = 0
        if ($versionsAfter.Ok) { $countAfter = @($versionsAfter.Data).Count }
        $sameVersion = @()
        if ($versionsAfter.Ok) { $sameVersion = @($versionsAfter.Data | Where-Object { [int]$_.versionNo -eq $versionNo }) }
        Add-Check 'J9-版本行不重复' (($countAfter -eq $countBefore) -and ($sameVersion.Count -eq 1)) ('版本数 ' + $countBefore + ' -> ' + $countAfter + ' 同号行=' + $sameVersion.Count)
        $Details['versions'] = [ordered]@{ before = $countBefore; after = $countAfter; sameVersionRows = $sameVersion.Count }

        $pinAfterReplay = Get-Platform ('/admin/apps/' + $PinAppCode + '/pin-status')
        Add-Check 'J9-重放后pin行不变' ($pinAfterReplay.Ok -and ((Get-Text $pinAfterReplay.Data.bindingId) -ceq $pinBindingId) -and ([int]$pinAfterReplay.Data.versionNo -eq $versionNo)) ('bindingId=' + (Get-Text $pinAfterReplay.Data.bindingId) + ' versionNo=' + (Get-Text $pinAfterReplay.Data.versionNo))

        $bindingAfter = Invoke-StageHttp -Method 'GET' -Url ($OntologyBase.TrimEnd('/') + '/onto/internal/release-bindings' + $query) -Headers @{ 'X-API-Key' = $BinderKeyValue }
        $bindingAfterEnvelope = Test-Envelope $bindingAfter @(200) 'OK'
        Add-Check 'J9-重放后OR绑定不变' ($bindingAfterEnvelope.Ok -and ((Get-Text $bindingAfterEnvelope.Data.bindingId) -ceq $pinBindingId)) ('bindingId=' + (Get-Text $bindingAfterEnvelope.Data.bindingId))
        if (-not ($replayVersion -eq $versionNo -and $replayBinding -ceq $pinBindingId -and $countAfter -eq $countBefore)) {
            $Verdict = 'FAIL'
            $Reason = '重放产生了重复副作用（版本/绑定）'
        }
    } else {
        $Verdict = 'FAIL'
        $Reason = '同一 requestNo 重放未被幂等接受：HTTP ' + $replay.Status + ' ' + $replay.Code
    }
}

if ($Verdict -eq 'PASS' -and $script:FirstFailure) {
    $Verdict = 'FAIL'
    $Reason = $script:FirstFailure
}

Complete-Stage 'J9' $Verdict $Reason $stageInputs $stageBoundaries
