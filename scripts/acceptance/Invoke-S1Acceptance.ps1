<#
.SYNOPSIS
  S1 出口验收 A/B/C/D 四段包装入口（EX-06）。

.DESCRIPTION
  A 可配置断言与旧语义：创建评测 → 轮询报告 → 用 psql 定点核对 monitor.evaluation_report
    的 assertion_model/assertion_snapshot。
  B 发布门禁与豁免：precheck 命中 EVAL_GATE_BLOCKED → 创建豁免 → 复检 WAIVED → 撤销 → 恢复阻断
    → 用真实 precheckId 提交发布（仅 -BCommit 明确授权时执行）。
  C SUBMIT_QUERY 异步任务态：用 psql 核对 WAITING_EXTERNAL 快照、external_job.remoteJobId
    与 FAILED 终态 error 的 code/userMessage/traceId，并确认失败 run 不再残留 WAITING_EXTERNAL。
  D 去桥直连本体：NONE+authRef 注册期 400 SKILL_AUTH_SCHEME_CONFLICT；消费凭据直连
    /onto/r/{spaceCode}/releases/{releaseId}/ops/{opCode} 返回 200；消费 key 打发布只读面 401。

  缺外部输入（appCode/候选/psql/runId/manifest/凭据）时该段记 SKIPPED-BLOCKED 且不发起调用。
  -DryRun 只打印计划，不发请求、不写文件。
  退出码：0=PASS，1=FAIL，3=BLOCKED/SKIPPED-BLOCKED。
#>
[CmdletBinding()]
param(
    [string]$PlatformBase = 'http://127.0.0.1:18080',
    [string]$ManifestPath,
    [string]$CredentialsEnvPath,
    [string]$CredentialsDirectory,
    [string]$EvidenceDirectory,
    [ValidateSet('TestFixture', 'Real')][string]$ModelMode = 'TestFixture',
    [string[]]$Stages = @('A', 'B', 'C', 'D'),
    [switch]$DryRun,
    [string]$BearerToken,
    [string]$BearerTokenEnv = 'AGENT_ACCEPTANCE_BEARER',
    [string]$DatabaseConnection,
    [string]$PsqlPath = 'psql',
    [int]$PollTimeoutSeconds = 180,
    [string]$AEvaluationAppCode,
    [long]$ADraftRev = 0,
    [string]$ACandidateSha256,
    [string]$ADatasetVersion = 's1-assertion',
    [string]$ACasesPath,
    [string]$BAppCode,
    # 负例必须落在与正例不同的候选/应用上，否则“同一候选既 409 又 200”不可能成立（EX-06 复审）。
    [string]$BFailAppCode,
    [long]$BPassDraftRev = 0,
    [string]$BPassCandidateSha256,
    [long]$BFailDraftRev = 0,
    [string]$BFailCandidateSha256,
    [string]$BWaiverReason = 'S1 验收：候选缺少已接受评测报告，限时豁免后验证门禁恢复',
    [string]$BWaiverExpiresAt,
    [switch]$BCommit,
    [string]$BIdempotencyRequestNo,
    [string]$CWaitingRunId,
    [string]$CFailedRunId,
    [string]$CExpectedRemoteJobId,
    [string]$DConflictSkillCode = 's1_auth_conflict_probe'
)

$ErrorActionPreference = 'Stop'
# 重定向到文件/管道时使用 UTF-8，避免中文原因在证据日志里按代码页变成乱码。
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
$Started = Get-Date
$UuidPattern = '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
# engine.run.id 真实主键形状是 r_+32 hex（见 engine 迁移与 RunService.newId），不是 UUID；
# 只认 UUID 会让正常输入永远进不了 C 段（EX-06 复核）。
$RunIdPattern = '^(r_[0-9a-f]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$results = New-Object System.Collections.ArrayList

function Get-S1Text([object]$Value) {
    if ($null -eq $Value) { return '' }
    return ([string]$Value).Trim()
}

function Get-S1TextProperty([object]$Object, [string]$Name) {
    if ($null -eq $Object) { return '' }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return '' }
    return (Get-S1Text $property.Value)
}

function Invoke-S1Http {
    param([string]$Method, [string]$Url, [hashtable]$Headers, [AllowNull()][object]$Body)
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.Method = $Method
    $request.Proxy = $null
    $request.AllowAutoRedirect = $false
    $request.Timeout = 30000
    $request.ReadWriteTimeout = 30000
    $request.Accept = 'application/json'
    if ($Headers) { foreach ($name in $Headers.Keys) { $request.Headers[$name] = [string]$Headers[$name] } }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 20 -Compress
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        $request.ContentType = 'application/json;charset=utf-8'
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
    } elseif ($Method -in @('POST', 'PUT')) { $request.ContentLength = 0 }
    $response = $null
    $reader = $null
    try {
        try { $response = [Net.HttpWebResponse]$request.GetResponse() } catch [Net.WebException] {
            if ($_.Exception.Response) { $response = [Net.HttpWebResponse]$_.Exception.Response } else { throw }
        }
        $status = [int]$response.StatusCode
        $reader = New-Object IO.StreamReader($response.GetResponseStream(), [Text.Encoding]::UTF8)
        $raw = $reader.ReadToEnd()
        $parsed = $null
        if (Get-S1Text $raw) { try { $parsed = $raw | ConvertFrom-Json -ErrorAction Stop } catch { $parsed = $null } }
        return [pscustomobject]@{ Status = $status; Raw = $raw; Body = $parsed }
    } finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Dispose() }
    }
}

function Get-S1Bearer {
    if (Get-S1Text $BearerToken) { return (Get-S1Text $BearerToken) }
    $value = [Environment]::GetEnvironmentVariable($BearerTokenEnv)
    if (Get-S1Text $value) { return (Get-S1Text $value) }
    return ''
}

function Get-S1DbValue([string]$Query) {
    $output = @(& $PsqlPath $DatabaseConnection -t -A -F '|' -c $Query 2>&1 | ForEach-Object { [string]$_ })
    if ($LASTEXITCODE -ne 0) { throw 'S1_DB_QUERY_FAILED' }
    $lines = @($output | Where-Object { (Get-S1Text $_) -ne '' })
    if ($lines.Count -eq 0) { return '' }
    return (Get-S1Text $lines[0])
}

# R05/JOINT-004：S1-D认证负例以其余字段合法的基准仅改NONE+authRef；不发送httpMapping
# （现契约要求path/query为数组、body为字符串，对象形状会先在映射校验被拒，认证门未到达）。
function New-S1DConflictBody([object]$Manifest, [string]$SkillCode) {
    return @{
        code = $SkillCode; name = 'S1 auth conflict probe'; type = 'HTTP'
        endpoint = ($Manifest.ontologyBaseUrl + '/onto/r/' + $Manifest.spaceCode + '/releases/' + $Manifest.releaseId + '/ops/' + $Manifest.opCode)
        httpMethod = 'POST'; authScheme = 'NONE'; authRef = $Manifest.consumerKeyRef
        execution = @{ kind = 'SYNC'; readonly = $true; sideEffect = $false; idempotent = $true; cancellable = $false }
    }
}

function New-S1Row([string]$Stage, [string]$Status, [string]$Reason, [string]$Evidence, [object]$Facts) {
    [void]$results.Add([ordered]@{ stage = $Stage; status = $Status; reason = $Reason; evidence = $Evidence; facts = $Facts })
}

function New-S1EvidenceFile([string]$Stage, [object]$Value) {
    if ($DryRun) { return '' }
    if (-not (Test-Path -LiteralPath $EvidenceDirectory)) { New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null }
    $stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
    $path = Join-Path $EvidenceDirectory ('s1-' + $Stage.ToLowerInvariant() + '-' + $stamp + '.json')
    ($Value | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath $path -Encoding UTF8
    return $path
}

# ---- 统一前置：清单与凭据只读装载（不泄漏密钥） ----
$manifest = $null
if (Get-S1Text $ManifestPath) {
    if (Test-Path -LiteralPath $ManifestPath -PathType Leaf) {
        try { $manifest = [IO.File]::ReadAllText($ManifestPath) | ConvertFrom-Json -ErrorAction Stop } catch { $manifest = $null }
    }
}
$credentials = @{}
$credentialFile = ''
if (Get-S1Text $CredentialsEnvPath) { $credentialFile = $CredentialsEnvPath }
elseif (Get-S1Text $CredentialsDirectory) { $credentialFile = Join-Path $CredentialsDirectory 'credentials.env' }
if ((Get-S1Text $credentialFile) -and (Test-Path -LiteralPath $credentialFile -PathType Leaf)) {
    foreach ($line in [IO.File]::ReadAllLines($credentialFile)) {
        if ($line -match '^(AGENT_SECRET_[A-Z0-9_]+)=(.+)$') { $credentials[$Matches[1]] = $Matches[2] }
    }
}
function Get-S1Key([string]$KeyRef) {
    if ($KeyRef -match '^env:(AGENT_SECRET_[A-Z0-9_]+)$' -and $credentials.ContainsKey($Matches[1])) { return [string]$credentials[$Matches[1]] }
    return ''
}

$requested = @()
foreach ($stage in @($Stages)) {
    $upper = (Get-S1Text $stage).ToUpperInvariant()
    if ($upper -notin @('A', 'B', 'C', 'D')) { Write-Output ('[FAIL] 未知 S1 段: ' + $stage); exit 1 }
    if ($requested -notcontains $upper) { $requested += $upper }
}
if ($requested.Count -eq 0) { $requested = @('A', 'B', 'C', 'D') }
if (-not (Get-S1Text $EvidenceDirectory)) { $EvidenceDirectory = Join-Path $repositoryRoot ('.local/s1-evidence-' + $Started.ToString('yyyyMMdd-HHmmss')) }
$bearer = Get-S1Bearer
$psqlReady = $false
if ((Get-S1Text $DatabaseConnection) -and (Get-Command $PsqlPath -ErrorAction SilentlyContinue)) { $psqlReady = $true }

$base = $PlatformBase.TrimEnd('/')
$adminHeaders = @{}
if (Get-S1Text $bearer) { $adminHeaders['Authorization'] = 'Bearer ' + $bearer }

# ---- A：可配置断言与旧语义 ----
if ($requested -contains 'A') {
    $missing = @()
    if (-not (Get-S1Text $AEvaluationAppCode)) { $missing += 'EvalAppCode' }
    if ($ADraftRev -le 0) { $missing += 'DraftRev(>0)' }
    if ((Get-S1Text $ACandidateSha256) -notmatch '^[a-f0-9]{64}$') { $missing += 'CandidateSha256(64hex)' }
    if (-not (Get-S1Text $ACasesPath) -or -not (Test-Path -LiteralPath $ACasesPath -PathType Leaf)) { $missing += 'CasesPath(评测用例JSON)' }
    if (-not $psqlReady) { $missing += 'psql/数据库连接' }
    if (-not (Get-S1Text $bearer)) { $missing += 'ADMIN Bearer' }
    if ($missing.Count) {
        New-S1Row 'A' 'SKIPPED-BLOCKED' ('缺外部输入: ' + ($missing -join '; ')) '' $null
    } elseif ($DryRun) {
        Write-Output ('[PLAN] A: POST ' + $base + '/admin/evaluations app=' + $AEvaluationAppCode + '; GET report; psql monitor.evaluation_report')
        New-S1Row 'A' 'PLANNED' 'DryRun' '' $null
    } else {
        try {
            $cases = @([IO.File]::ReadAllText($ACasesPath) | ConvertFrom-Json -ErrorAction Stop)
            if ($cases.Count -eq 0) { throw 'S1_CASES_EMPTY' }
            $payload = @{ appCode = $AEvaluationAppCode; draftRev = $ADraftRev; candidateSha256 = $ACandidateSha256; datasetVersion = $ADatasetVersion; cases = @($cases) }
            $created = Invoke-S1Http -Method 'POST' -Url ($base + '/admin/evaluations') -Headers $adminHeaders -Body $payload
            $reportId = Get-S1Text $created.Body.data.id
            if ($created.Status -notin @(200, 201) -or -not $reportId) { throw ('S1_A_CREATE_FAILED status=' + $created.Status) }
            $deadline = (Get-Date).AddSeconds($PollTimeoutSeconds)
            $status = ''
            while ((Get-Date) -lt $deadline) {
                Start-Sleep -Seconds 2
                $report = Invoke-S1Http -Method 'GET' -Url ($base + '/admin/evaluations/' + $reportId) -Headers $adminHeaders -Body $null
                if ($report.Status -ne 200) { throw ('S1_A_REPORT_FAILED status=' + $report.Status) }
                $status = Get-S1Text $report.Body.data.status
                if ($status -in @('ACCEPTED', 'FAILED', 'REJECTED')) { break }
            }
            $row = Get-S1DbValue ("select status||'|'||coalesce(assertion_model,'')||'|'||case when assertion_snapshot is null then '0' else '1' end from monitor.evaluation_report where id='" + ($reportId -replace "'", "''") + "'")
            $facts = @{ reportId = $reportId; apiStatus = $status; database = ($row -replace '\|', '/'); modelMode = $ModelMode }
            $parts = @($row -split '\|')
            if ($status -cne 'ACCEPTED' -or $parts.Count -ne 3 -or $parts[0] -cne 'ACCEPTED' -or $parts[1] -cne 'CONFIGURABLE' -or $parts[2] -cne '1') { throw 'S1_A_ASSERTION_NOT_CONFIGURABLE' }
            $evidence = New-S1EvidenceFile 'A' ([ordered]@{ segment = 'A'; reportId = $reportId; apiStatus = $status; databaseRow = $row; modelMode = $ModelMode })
            New-S1Row 'A' 'PASS' '' $evidence $facts
        } catch {
            $reason = $_.Exception.Message
            $evidence = New-S1EvidenceFile 'A' ([ordered]@{ segment = 'A'; error = $reason; modelMode = $ModelMode })
            New-S1Row 'A' 'FAIL' $reason $evidence $null
        }
    }
}

# ---- B：发布门禁与豁免 ----
if ($requested -contains 'B') {
    $missing = @()
    if (-not (Get-S1Text $BAppCode)) { $missing += 'BAppCode' }
    if (-not (Get-S1Text $BFailAppCode)) { $missing += 'BFailAppCode(负例候选所在应用)' }
    elseif ((Get-S1Text $BFailAppCode) -ceq (Get-S1Text $BAppCode)) { $missing += 'BFailAppCode(必须与 BAppCode 不同应用，负例才独立)' }
    if ($BPassDraftRev -le 0) { $missing += 'BPassDraftRev(>0)' }
    if ((Get-S1Text $BPassCandidateSha256) -notmatch '^[a-f0-9]{64}$') { $missing += 'BPassCandidateSha256(64hex)' }
    if ($BFailDraftRev -le 0) { $missing += 'BFailDraftRev(>0)' }
    if ((Get-S1Text $BFailCandidateSha256) -notmatch '^[a-f0-9]{64}$') { $missing += 'BFailCandidateSha256(64hex)' }
    if (-not (Get-S1Text $BWaiverExpiresAt)) { $missing += 'BWaiverExpiresAt(ISO8601)' } else {
        $expiresAt = [DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParse((Get-S1Text $BWaiverExpiresAt), [ref]$expiresAt)) { $missing += 'BWaiverExpiresAt(ISO8601)' }
    }
    if (-not (Get-S1Text $BIdempotencyRequestNo)) { $missing += 'BIdempotencyRequestNo' }
    if (-not $BCommit) { $missing += 'BCommit(需显式授权真实发布)' }
    if (-not (Get-S1Text $bearer)) { $missing += 'ADMIN Bearer' }
    if ($missing.Count) {
        New-S1Row 'B' 'SKIPPED-BLOCKED' ('缺外部输入: ' + ($missing -join '; ')) '' $null
    } elseif ($DryRun) {
        Write-Output ('[PLAN] B: precheck/waiver/撤销/commit app=' + $BAppCode)
        New-S1Row 'B' 'PLANNED' 'DryRun' '' $null
    } else {
        try {
            $failApp = Get-S1Text $BFailAppCode
            $failAppUrl = $base + '/admin/apps/' + $failApp
            $passAppUrl = $base + '/admin/apps/' + $BAppCode
            # 预检按“当前草稿”评估且不需要请求体；先 GET 两个应用，证明冻结的 draftRev/candidateSha256
            # 就是服务端当前候选，避免把参数陈旧导致的门禁结果误判成门禁本身。
            $failAppFact = Invoke-S1Http -Method 'GET' -Url $failAppUrl -Headers $adminHeaders -Body $null
            $passAppFact = Invoke-S1Http -Method 'GET' -Url $passAppUrl -Headers $adminHeaders -Body $null
            if ($failAppFact.Status -ne 200 -or $passAppFact.Status -ne 200) { throw ('S1_B_APP_FACT_UNAVAILABLE fail=' + $failAppFact.Status + ' pass=' + $passAppFact.Status) }
            if ([long]$failAppFact.Body.data.draftRev -ne $BFailDraftRev -or (Get-S1Text $failAppFact.Body.data.candidateSha256) -cne (Get-S1Text $BFailCandidateSha256)) { throw 'S1_B_FAIL_CANDIDATE_STALE' }
            if ([long]$passAppFact.Body.data.draftRev -ne $BPassDraftRev -or (Get-S1Text $passAppFact.Body.data.candidateSha256) -cne (Get-S1Text $BPassCandidateSha256)) { throw 'S1_B_PASS_CANDIDATE_STALE' }
            $blocked = Invoke-S1Http -Method 'POST' -Url ($failAppUrl + '/publish/precheck') -Headers $adminHeaders -Body $null
            if ($blocked.Status -ne 409 -or (Get-S1Text $blocked.Body.code) -cne 'EVAL_GATE_BLOCKED') { throw ('S1_B_GATE_NOT_BLOCKED status=' + $blocked.Status) }
            $waiver = Invoke-S1Http -Method 'POST' -Url ($failAppUrl + '/publish-waivers') -Headers $adminHeaders -Body @{ candidateSha256 = $BFailCandidateSha256; draftRev = $BFailDraftRev; reason = $BWaiverReason; expiresAt = $BWaiverExpiresAt }
            $waiverId = Get-S1Text $waiver.Body.data.id
            if (-not $waiverId) { $waiverId = Get-S1Text $waiver.Body.data.waiverId }
            if ($waiver.Status -notin @(200, 201) -or -not $waiverId) { throw ('S1_B_WAIVER_CREATE_FAILED status=' + $waiver.Status) }
            $waived = Invoke-S1Http -Method 'POST' -Url ($failAppUrl + '/publish/precheck') -Headers $adminHeaders -Body $null
            if ($waived.Status -ne 200 -or $waived.Body.data.passed -ne $true -or (Get-S1Text $waived.Body.data.result) -cne 'PASS') { throw ('S1_B_GATE_NOT_WAIVED status=' + $waived.Status) }
            # gateMode 是门禁模式名（BLOCKING/ADVISORY/OFF），不是 WAIVED；豁免事实看 waiverId 与 check.result=WAIVED。
            if ((Get-S1Text $waived.Body.data.gateMode) -cne 'BLOCKING') { throw ('S1_B_GATE_MODE_INVALID gateMode=' + (Get-S1Text $waived.Body.data.gateMode)) }
            if ((Get-S1Text $waived.Body.data.waiverId) -cne $waiverId) { throw 'S1_B_WAIVER_ID_NOT_APPLIED' }
            $waivedChecks = @($waived.Body.data.report.checks | Where-Object { (Get-S1Text $_.result) -ceq 'WAIVED' -and (Get-S1Text $_.waiverId) -ceq $waiverId })
            if ($waivedChecks.Count -lt 1) { throw 'S1_B_WAIVER_CHECK_MISSING' }
            $deleted = Invoke-S1Http -Method 'DELETE' -Url ($failAppUrl + '/publish-waivers/' + $waiverId) -Headers $adminHeaders -Body $null
            if ($deleted.Status -ne 200 -or (Get-S1Text $deleted.Body.data.status) -cne 'REVOKED') { throw ('S1_B_WAIVER_DELETE_FAILED status=' + $deleted.Status) }
            $restored = Invoke-S1Http -Method 'POST' -Url ($failAppUrl + '/publish/precheck') -Headers $adminHeaders -Body $null
            if ($restored.Status -ne 409 -or (Get-S1Text $restored.Body.code) -cne 'EVAL_GATE_BLOCKED') { throw ('S1_B_GATE_NOT_RESTORED status=' + $restored.Status) }
            $precheck = Invoke-S1Http -Method 'POST' -Url ($passAppUrl + '/publish/precheck') -Headers $adminHeaders -Body $null
            $precheckId = Get-S1Text $precheck.Body.data.precheckId
            if ($precheck.Status -ne 200 -or -not $precheckId -or $precheck.Body.data.passed -ne $true -or (Get-S1Text $precheck.Body.data.result) -cne 'PASS') { throw ('S1_B_PRECHECK_NOT_PASS status=' + $precheck.Status) }
            $commitStatus = 0
            $versionNo = ''
            $replayVersionNo = ''
            if ($BCommit) {
                $commitBody = @{ precheckId = $precheckId; draftRev = $BPassDraftRev; candidateSha256 = $BPassCandidateSha256; idempotencyRequestNo = $BIdempotencyRequestNo }
                if ($precheck.Body.data.environmentConfirmationRequired -eq $true) {
                    # DEMO 非 REAL：提交必须显式确认评测环境，回填预检给出的 report/evidence 摘要（EX-06 §4.6）。
                    $commitBody['evaluationEnvironmentConfirmation'] = @{ confirmed = $true; precheckId = $precheckId; evalReportId = (Get-S1Text $precheck.Body.data.evalReportId); evidenceSha256 = (Get-S1Text $precheck.Body.data.environmentEvidenceSha256) }
                }
                $commit = Invoke-S1Http -Method 'POST' -Url ($passAppUrl + '/publish/commit') -Headers $adminHeaders -Body $commitBody
                if ($commit.Status -ne 200) { throw ('S1_B_COMMIT_FAILED status=' + $commit.Status) }
                $commitStatus = $commit.Status
                $versionNo = Get-S1Text $commit.Body.data.versionNo
                if (-not $versionNo) { throw 'S1_B_COMMIT_VERSION_MISSING' }
                $replay = Invoke-S1Http -Method 'POST' -Url ($passAppUrl + '/publish/commit') -Headers $adminHeaders -Body $commitBody
                if ($replay.Status -ne 200) { throw ('S1_B_COMMIT_REPLAY_FAILED status=' + $replay.Status) }
                $replayVersionNo = Get-S1Text $replay.Body.data.versionNo
                if ($replayVersionNo -cne $versionNo) { throw ('S1_B_COMMIT_REPLAY_NOT_IDEMPOTENT first=' + $versionNo + ' replay=' + $replayVersionNo) }
            }
            $facts = @{ appCode = $BAppCode; failAppCode = $failApp; waiverId = $waiverId; precheckId = $precheckId; commitStatus = $commitStatus; versionNo = $versionNo }
            $evidence = New-S1EvidenceFile 'B' ([ordered]@{ segment = 'B'; appCode = $BAppCode; failAppCode = $failApp; failCandidate = [ordered]@{ draftRev = $BFailDraftRev; candidateSha256 = $BFailCandidateSha256 }; passCandidate = [ordered]@{ draftRev = $BPassDraftRev; candidateSha256 = $BPassCandidateSha256 }; blockedStatus = $blocked.Status; blockedCode = (Get-S1Text $blocked.Body.code); waiverId = $waiverId; waivedGateMode = (Get-S1Text $waived.Body.data.gateMode); waivedCheckResult = 'WAIVED'; revokedStatus = (Get-S1Text $deleted.Body.data.status); restoredStatus = $restored.Status; precheckId = $precheckId; environmentConfirmationRequired = ($precheck.Body.data.environmentConfirmationRequired -eq $true); commitStatus = $commitStatus; versionNo = $versionNo; replayVersionNo = $replayVersionNo })
            New-S1Row 'B' 'PASS' '' $evidence $facts
        } catch {
            $reason = $_.Exception.Message
            $evidence = New-S1EvidenceFile 'B' ([ordered]@{ segment = 'B'; error = $reason; appCode = $BAppCode })
            New-S1Row 'B' 'FAIL' $reason $evidence $null
        }
    }
}

# ---- C：SUBMIT_QUERY 异步任务态 ----
if ($requested -contains 'C') {
    $missing = @()
    if ((Get-S1Text $CWaitingRunId) -notmatch $RunIdPattern) { $missing += 'CWaitingRunId(r_<32hex> 或 UUID)' }
    if ((Get-S1Text $CFailedRunId) -notmatch $RunIdPattern) { $missing += 'CFailedRunId(r_<32hex> 或 UUID)' }
    if (-not (Get-S1Text $CExpectedRemoteJobId)) { $missing += 'CExpectedRemoteJobId' }
    if (-not $psqlReady) { $missing += 'psql/数据库连接' }
    if ($missing.Count) {
        New-S1Row 'C' 'SKIPPED-BLOCKED' ('缺外部输入: ' + ($missing -join '; ')) '' $null
    } elseif ($DryRun) {
        Write-Output ('[PLAN] C: psql engine.run/tool_call for ' + $CWaitingRunId + ' / ' + $CFailedRunId)
        New-S1Row 'C' 'PLANNED' 'DryRun' '' $null
    } else {
        try {
            # engine.run.id 是 bigint 主键，public_id 才是 r_<32hex>/UUID；必须先用 public_id 换数值主键，
            # 再用数值主键关联 node_instance/tool_call（EX-06 复审：不能用外部 id 直接比 bigint）。
            $waitPublicId = Get-S1Text $CWaitingRunId
            $failPublicId = Get-S1Text $CFailedRunId
            $waitKey = Get-S1DbValue ("select id::text from engine.run where public_id='" + ($waitPublicId -replace "'", "''") + "'")
            if ($waitKey -notmatch '^[0-9]+$') { throw ('S1_C_WAITING_RUN_NOT_FOUND publicId=' + $waitPublicId) }
            $failKey = Get-S1DbValue ("select id::text from engine.run where public_id='" + ($failPublicId -replace "'", "''") + "'")
            if ($failKey -notmatch '^[0-9]+$') { throw ('S1_C_FAILED_RUN_NOT_FOUND publicId=' + $failPublicId) }
            $waitStatus = Get-S1DbValue ("select status from engine.run where id=" + $waitKey)
            if ($waitStatus -cne 'WAITING_EXTERNAL') { throw ('S1_C_WAITING_STATUS_INVALID status=' + $waitStatus) }
            $job = Get-S1DbValue ("select external_job::text from engine.tool_call where run_id=" + $waitKey + " and external_job is not null limit 1")
            if (-not $job) { throw 'S1_C_EXTERNAL_JOB_MISSING' }
            $jobObject = $null
            try { $jobObject = $job | ConvertFrom-Json -ErrorAction Stop } catch { throw 'S1_C_EXTERNAL_JOB_INVALID' }
            if ((Get-S1Text $jobObject.remoteJobId) -cne (Get-S1Text $CExpectedRemoteJobId)) { throw 'S1_C_REMOTE_JOB_MISMATCH' }
            $failStatus = Get-S1DbValue ("select status from engine.run where id=" + $failKey)
            if ($failStatus -cne 'FAILED') { throw ('S1_C_FAILED_STATUS_INVALID status=' + $failStatus) }
            # engine.tool_call.error_semantic 是 varchar(32) 文本码（如 EXTERNAL_TIMEOUT），不是 JSON，禁止 ConvertFrom-Json。
            $errorSemantic = Get-S1DbValue ("select coalesce(error_semantic,'') from engine.tool_call where run_id=" + $failKey + " and error_semantic is not null limit 1")
            if (-not $errorSemantic) { throw 'S1_C_ERROR_SEMANTIC_MISSING' }
            if ($errorSemantic -notmatch '^[A-Z][A-Z0-9_]{0,31}$') { throw ('S1_C_ERROR_SEMANTIC_NOT_CODE value=' + $errorSemantic) }
            # 结构化七字段错误在 engine.run.error(jsonb)：code/userMessage/traceId 从这里取，而不是 tool_call.error_semantic。
            $errorCode = Get-S1DbValue ("select coalesce(error->>'code','') from engine.run where id=" + $failKey)
            $errorUserMessage = Get-S1DbValue ("select coalesce(error->>'userMessage','') from engine.run where id=" + $failKey)
            $errorTraceId = Get-S1DbValue ("select coalesce(error->>'traceId','') from engine.run where id=" + $failKey)
            foreach ($pair in @(@('code', $errorCode), @('userMessage', $errorUserMessage), @('traceId', $errorTraceId))) {
                if (-not (Get-S1Text $pair[1])) { throw ('S1_C_ERROR_FIELD_MISSING ' + $pair[0]) }
            }
            $transient = Get-S1DbValue ("select count(*) from engine.node_instance where run_id=" + $failKey + " and status='WAITING_EXTERNAL'")
            if ($transient -ne '0') { throw 'S1_C_TRANSIENT_STATE_NOT_CLEARED' }
            $facts = @{ waitingRunId = $CWaitingRunId; failedRunId = $CFailedRunId; remoteJobId = $CExpectedRemoteJobId; errorSemantic = $errorSemantic }
            $evidence = New-S1EvidenceFile 'C' ([ordered]@{ segment = 'C'; waitingRunPublicId = $waitPublicId; waitingRunKey = $waitKey; waitingRunStatus = $waitStatus; remoteJobId = $CExpectedRemoteJobId; failedRunPublicId = $failPublicId; failedRunKey = $failKey; failedRunStatus = $failStatus; errorSemantic = $errorSemantic; errorCode = $errorCode; errorUserMessage = $errorUserMessage; errorTraceId = $errorTraceId; transientNodes = $transient })
            New-S1Row 'C' 'PASS' '' $evidence $facts
        } catch {
            $reason = $_.Exception.Message
            $evidence = New-S1EvidenceFile 'C' ([ordered]@{ segment = 'C'; error = $reason })
            New-S1Row 'C' 'FAIL' $reason $evidence $null
        }
    }
}

# ---- D：去桥直连本体 ----
if ($requested -contains 'D') {
    $missing = @()
    if ($null -eq $manifest) { $missing += 'ManifestPath(最终manifest)' }
    if (-not (Get-S1Key (Get-S1Text $manifest.readKeyRef))) { $missing += '只读key(credentials.env)' }
    if (-not (Get-S1Key (Get-S1Text $manifest.consumerKeyRef))) { $missing += '消费key(credentials.env)' }
    if (-not (Get-S1Text $bearer)) { $missing += 'ADMIN Bearer' }
    if ($missing.Count) {
        New-S1Row 'D' 'SKIPPED-BLOCKED' ('缺外部输入: ' + ($missing -join '; ')) '' $null
    } elseif ($DryRun) {
        Write-Output ('[PLAN] D: NONE+authRef 冲突 400; 消费key直连 ' + $manifest.ontologyBaseUrl + '/onto/r/' + $manifest.spaceCode + '/releases/' + $manifest.releaseId + '/ops/' + $manifest.opCode)
        New-S1Row 'D' 'PLANNED' 'DryRun' '' $null
    } else {
        try {
            $conflictBody = New-S1DConflictBody $manifest $DConflictSkillCode
            $conflict = Invoke-S1Http -Method 'POST' -Url ($base + '/admin/skills') -Headers $adminHeaders -Body $conflictBody
            if ($conflict.Status -ne 400 -or (Get-S1Text $conflict.Body.code) -cne 'SKILL_AUTH_SCHEME_CONFLICT') { throw ('S1_D_SCHEME_CONFLICT_NOT_ENFORCED status=' + $conflict.Status) }
            $consumerKey = Get-S1Key $manifest.consumerKeyRef
            $direct = Invoke-S1Http -Method 'POST' -Url ($manifest.ontologyBaseUrl + '/onto/r/' + $manifest.spaceCode + '/releases/' + $manifest.releaseId + '/ops/' + $manifest.opCode) -Headers @{ 'X-API-Key' = $consumerKey } -Body @{}
            if ($direct.Status -ne 200 -or (Get-S1Text $direct.Body.code) -cne 'OK') { throw ('S1_D_DIRECT_CALL_FAILED status=' + $direct.Status) }
            $readFace = Invoke-S1Http -Method 'GET' -Url ($manifest.ontologyBaseUrl + '/onto/releases/' + $manifest.releaseId + '/manifest') -Headers @{ 'X-API-Key' = $consumerKey } -Body $null
            if ($readFace.Status -ne 401) { throw ('S1_D_CONSUMER_READ_FACE_NOT_DENIED status=' + $readFace.Status) }
            $facts = @{ spaceCode = $manifest.spaceCode; releaseId = $manifest.releaseId; opCode = $manifest.opCode; directStatus = $direct.Status; readFaceStatus = $readFace.Status }
            # R05：三腿分别保留真实安全回包的status/code/message/traceId（脱敏，不含密钥与完整头）。
            $evidence = New-S1EvidenceFile 'D' ([ordered]@{
                segment = 'D'
                conflict = [ordered]@{ status = $conflict.Status; code = (Get-S1TextProperty $conflict.Body 'code'); message = (Get-S1TextProperty $conflict.Body 'message'); traceId = (Get-S1TextProperty $conflict.Body 'traceId') }
                direct = [ordered]@{ status = $direct.Status; code = (Get-S1TextProperty $direct.Body 'code'); message = (Get-S1TextProperty $direct.Body 'message'); traceId = (Get-S1TextProperty $direct.Body 'traceId') }
                readFace = [ordered]@{ status = $readFace.Status; code = (Get-S1TextProperty $readFace.Body 'code'); message = (Get-S1TextProperty $readFace.Body 'message'); traceId = (Get-S1TextProperty $readFace.Body 'traceId') }
                headersObserved = '需 OR 侧访问日志/抓包证据核对 X-API-Key 与无 Authorization'
            })
            New-S1Row 'D' 'PASS' '' $evidence $facts
        } catch {
            $reason = $_.Exception.Message
            $evidence = New-S1EvidenceFile 'D' ([ordered]@{ segment = 'D'; error = $reason })
            New-S1Row 'D' 'FAIL' $reason $evidence $null
        }
    }
}

# ---- 汇总 ----
$passCount = @($results | Where-Object { $_.status -eq 'PASS' }).Count
$failCount = @($results | Where-Object { $_.status -eq 'FAIL' }).Count
$blockedCount = @($results | Where-Object { $_.status -in @('BLOCKED', 'SKIPPED-BLOCKED') }).Count
$plannedCount = @($results | Where-Object { $_.status -eq 'PLANNED' }).Count
$verdict = 'PASS'
if ($failCount -gt 0) { $verdict = 'FAIL' } elseif ($blockedCount -gt 0) { $verdict = 'BLOCKED' } elseif ($DryRun) { $verdict = 'DRYRUN' }
$summary = [ordered]@{
    suite = 'S1'
    verdict = $verdict
    startedAt = $Started.ToUniversalTime().ToString('o')
    finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    dryRun = [bool]$DryRun
    modelMode = $ModelMode
    inputs = [ordered]@{ platformBase = $base; manifestProvided = ($null -ne $manifest); credentialsProvided = ($credentials.Count -gt 0); databaseProvided = [bool](Get-S1Text $DatabaseConnection); bearerProvided = [bool](Get-S1Text $bearer) }
    results = @($results)
    totals = [ordered]@{ pass = $passCount; fail = $failCount; blocked = $blockedCount; planned = $plannedCount }
    boundaries = @(
        'A/C 依赖真实 PG；脚本用 psql 定点查询，查询失败即 FAIL，不用日志或桩冒充数据库事实。',
        'B 默认不提交真实发布；-BCommit 是显式授权开关，未授权时整段 SKIPPED-BLOCKED。',
        'C 需已产生的两份真实 run（WAITING_EXTERNAL 快照与 FAILED 终态），脚本不制造 run。',
        'D 的本体侧请求头观察仍需 OR 访问日志/抓包证据；脚本只断言 NONE+authRef 冲突、直连 200、发布只读面 401。'
    )
}
if (-not $DryRun) {
    if (-not (Test-Path -LiteralPath $EvidenceDirectory)) { New-Item -ItemType Directory -Path $EvidenceDirectory -Force | Out-Null }
    ($summary | ConvertTo-Json -Depth 20) | Set-Content -LiteralPath (Join-Path $EvidenceDirectory 's1-summary.json') -Encoding UTF8
}
Write-Output ('S1 stages=' + ($requested -join ','))
# 逐段打印状态与原因：缺外部输入时必须能在日志里看到卡在哪一项。
foreach ($row in $results) { Write-Output ('  [' + $row.status + '] ' + $row.stage + ': ' + $row.reason) }
Write-Output ('S1 totals pass=' + $passCount + ' fail=' + $failCount + ' blocked=' + $blockedCount + ' planned=' + $plannedCount)
Write-Output ('S1 verdict=' + $verdict)
if ($verdict -eq 'FAIL') { exit 1 }
if ($verdict -eq 'BLOCKED') { exit 3 }
exit 0
