<#
.SYNOPSIS
  EX-07 J4 真实契约执行器：读取 OR 正式清单，并按 AP 导入契约生成技能草稿。

.DESCRIPTION
  只做两件事，且刻意不做连接测试/模型选型/发布/真实运行：
    J4a 只读服务账号 GET /onto/releases/{releaseId}/manifest，按 ContractGenerator.formalize 的
        正式清单形状（snake_case、ontology_meta 无 preview、endpoint 为 /onto/r/{space}/releases/{release}/ops/{tool}）
        逐字段核对；
    J4b ADMIN Bearer POST {gateway}/admin/skills/import-ontology-manifests，请求体是
        {manifests, authScheme:'API_KEY', authRef}，authRef 必须是服务端已登记的 secret_ref；
        响应按 {drafts:[{id,status:DRAFT,currentVersion:null,versions:[...]}], warnings:[]} 解析。
  导入只产生 DRAFT，本脚本不得据此声称连接测试/发布/运行完成。

  前置不满足 → [BLOCKED] 退出码 3（不发请求）；断言失败 → 1；通过 → 0。
  证据 JSON 只记录引用是否提供，不记录 key/token 原文。

.PARAMETER PlatformBase
  AP 网关根地址（默认 http://127.0.0.1:18080）。真实契约要求导入打进网关的 /admin/skills/**，
  不是应用服务 18081。

.PARAMETER AppBase
  旧参数名，仅作 PlatformBase 的兼容别名保留；含义仍是网关根地址。

.EXAMPLE
  $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = '<readonly ontosa_ key>'
  ./joint-j4.ps1 -ReleaseId <uuid> -SpaceId <uuid> -SpaceCode joint-xxx -AuthRef env:AGENT_SECRET_JOINT_xxx_CONSUMER -BearerToken <admin jwt>
#>
[CmdletBinding()]
param(
    [string]$OntologyBase = 'http://127.0.0.1:18182',
    [string]$PlatformBase = 'http://127.0.0.1:18080',
    [string]$AppBase,
    [string]$SpaceId,
    [string]$SpaceCode,
    [string]$ReleaseId,
    [string]$AuthRef,
    [string]$ReadKey,
    [string]$BearerToken,
    [string]$EvidenceDir,
    [ValidateRange(1, 60)][int]$TimeoutSeconds = 25
)

$ErrorActionPreference = 'Stop'
$ExitPass = 0
$ExitFail = 1
$ExitBlocked = 3
$Started = Get-Date
$UuidPattern = '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
$KeyPattern = '^ontosa_[A-Za-z0-9_-]{43}$'
$RefPattern = '^(env:AGENT_SECRET_[A-Z0-9_]+|vault:[^\s]+)$'
$Hex64Pattern = '^[a-f0-9]{64}$'
$SpacePattern = '^[A-Za-z][A-Za-z0-9_-]{0,79}$'
$ToolPattern = '^[A-Za-z][A-Za-z0-9_-]{0,63}$'

# 兼容旧调用：-AppBase 曾是应用服务地址；现在只接受网关根地址语义。
if ($PSBoundParameters.ContainsKey('AppBase') -and -not [string]::IsNullOrWhiteSpace($AppBase)) { $PlatformBase = $AppBase }

$Checks = New-Object System.Collections.ArrayList
$Verdict = 'PASS'
$Reason = ''
$Manifests = @()
$ToolCodes = @()
$DraftCount = 0
$WarningCount = 0
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

function Invoke-J4Http {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Url,
        [hashtable]$Headers,
        [AllowNull()][object]$Body
    )
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.Method = $Method
    $request.Proxy = $null
    $request.AllowAutoRedirect = $false
    $request.Timeout = $TimeoutSeconds * 1000
    $request.ReadWriteTimeout = $TimeoutSeconds * 1000
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

function Complete-J4([string]$FinalVerdict, [string]$FinalReason) {
    $finished = Get-Date
    $evidencePath = ''
    if (Get-Text $EvidenceDir) {
        if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null }
        $evidencePath = Join-Path $EvidenceDir ('j4-' + $Started.ToString('yyyyMMdd-HHmmss') + '.json')
        $readKeyText = Get-Text $ReadKey
        if (-not $readKeyText) { $readKeyText = Get-Text $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY }
        $record = [ordered]@{
            suite       = 'J4'
            verdict     = $FinalVerdict
            reason      = $FinalReason
            startedAt   = $Started.ToUniversalTime().ToString('o')
            finishedAt  = $finished.ToUniversalTime().ToString('o')
            inputs      = [ordered]@{
                ontologyBase     = $OntologyBase
                platformBase     = $PlatformBase
                releaseId        = (Get-Text $ReleaseId)
                spaceId          = (Get-Text $SpaceId)
                spaceCode        = (Get-Text $SpaceCode)
                authRefProvided  = [bool](Get-Text $AuthRef)
                readKeyProvided  = [bool]$readKeyText
                bearerProvided   = [bool](Get-Text $BearerToken)
            }
            manifestCount = @($Manifests).Count
            toolCodes     = @($ToolCodes)
            draftCount    = $DraftCount
            warningCount  = $WarningCount
            checks        = @($Checks)
            boundaries    = @(
                'J4 只证明 OR 正式清单可被 AP 导入为 DRAFT 技能；连接测试、模型/技能发布与真实运行不在本段范围。',
                '导入不修改 OR 侧 release，也不证明技能运行期鉴权头。',
                'J4 需要真实已发布 release、只读 ontosa_ 凭据与 ADMIN Bearer；缺一项即 BLOCKED，不得用桩数据记通过。'
            )
        }
        ($record | ConvertTo-Json -Depth 10) | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    }
    Write-Output ('J4 verdict=' + $FinalVerdict)
    if ($FinalReason) { Write-Output ('J4 reason=' + $FinalReason) }
    if ($evidencePath) { Write-Output ('J4 evidence=' + $evidencePath) }
    if ($FinalVerdict -eq 'PASS') { exit $ExitPass }
    if ($FinalVerdict -eq 'BLOCKED') { exit $ExitBlocked }
    exit $ExitFail
}

# ---- 前置输入：缺什么就报什么，绝不带着半套参数发请求。 ----
$ReadKeyValue = Get-Text $ReadKey
if (-not $ReadKeyValue) { $ReadKeyValue = Get-Text $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY }
$Missing = @()
if (-not (Get-Text $SpaceId)) {
    $Missing += '-SpaceId'
} elseif ($SpaceId -notmatch $UuidPattern) {
    $Missing += '-SpaceId(需UUID)'
}
if (-not (Get-Text $ReleaseId)) {
    $Missing += '-ReleaseId'
} elseif ($ReleaseId -notmatch $UuidPattern) {
    $Missing += '-ReleaseId(需UUID)'
}
if (-not $ReadKeyValue) {
    $Missing += '只读凭据(-ReadKey 或环境变量 AGENT_SECRET_ONTO_ENGINE_READ_KEY)'
} elseif ($ReadKeyValue -notmatch $KeyPattern) {
    $Missing += '只读凭据(需 ontosa_ 前缀加 43 位)'
}
if ($OntologyBase -notmatch '^https?://[^/]+$') { $Missing += '-OntologyBase(需 scheme+host:port，不含路径)' }
if ($PlatformBase -notmatch '^https?://[^/]+$') { $Missing += '-PlatformBase(需 scheme+host:port，不含路径)' }
if ((Get-Text $SpaceCode) -and $SpaceCode -notmatch $SpacePattern) { $Missing += '-SpaceCode(需字母开头的 code)' }

if ($Missing.Count -gt 0) {
    Write-Output '[BLOCKED] J4 前置未满足，未发出任何请求：'
    foreach ($Item in $Missing) { Write-Output ('  - ' + $Item) }
    Complete-J4 'BLOCKED' ('缺外部输入: ' + ($Missing -join '; '))
}

# ---- J4a：读取并核对正式清单 ----
$ManifestUrl = $OntologyBase.TrimEnd('/') + '/onto/releases/' + $ReleaseId + '/manifest'
try {
    $manifestResponse = Invoke-J4Http -Method 'GET' -Url $ManifestUrl -Headers @{ 'X-API-Key' = $ReadKeyValue }
} catch {
    Add-Check 'J4a-取清单' $false ('请求异常: ' + $_.Exception.Message)
    $Verdict = 'FAIL'
    $Reason = '读取 OR 清单请求异常: ' + $_.Exception.Message
}

if ($Verdict -eq 'PASS') {
    Add-Check 'J4a-取清单' ($manifestResponse.Status -eq 200) ('HTTP ' + $manifestResponse.Status)
    $envelope = $manifestResponse.Body
    $hasEnvelope = ($null -ne $envelope -and $null -ne $envelope.PSObject.Properties['code'] -and $null -ne $envelope.PSObject.Properties['data'])
    Add-Check 'J4a-信封' ($hasEnvelope -and ([string]$envelope.code -ceq 'OK')) ('code=' + $(if ($hasEnvelope) { [string]$envelope.code } else { '<missing>' }))
    if ($manifestResponse.Status -ne 200) {
        $Verdict = 'FAIL'
        $Reason = '读取 OR 清单失败：HTTP ' + $manifestResponse.Status
    } elseif (-not $hasEnvelope) {
        $Verdict = 'FAIL'
        $Reason = '读取 OR 清单响应不是 {code,data} 信封'
    } elseif ([string]$envelope.code -cne 'OK') {
        $Verdict = 'FAIL'
        $Reason = '读取 OR 清单返回业务错误：' + [string]$envelope.code
    } else {
        $manifestsNode = $envelope.data.manifests
        $isArray = ($null -ne $manifestsNode -and $manifestsNode -is [System.Array])
        $Manifests = @($manifestsNode)
        Add-Check 'J4a-清单数组' ($isArray -and $Manifests.Count -gt 0) ('count=' + $Manifests.Count)
        if (-not $isArray -or $Manifests.Count -eq 0) {
            $Verdict = 'FAIL'
            $Reason = 'OR 清单 data.manifests 必须是非空数组'
        }
    }
}

if ($Verdict -eq 'PASS') {
    $spaceSeen = @()
    $index = 0
    foreach ($manifest in $Manifests) {
        $index = $index + 1
        $tag = 'J4a-清单' + $index
        $toolCode = Get-Text $manifest.tool_code
        $meta = $manifest.ontology_meta
        $metaOk = ($null -ne $meta)
        Add-Check ($tag + '-tool_code') ($toolCode -cmatch $ToolPattern) ('tool_code=' + $toolCode)
        Add-Check ($tag + '-ontology_meta') $metaOk 'ontology_meta 必须存在'
        if ($metaOk) {
            $metaRelease = Get-Text $meta.releaseId
            $metaSpace = Get-Text $meta.spaceCode
            Add-Check ($tag + '-meta-release') ($metaRelease -ceq $ReleaseId) ('releaseId=' + $metaRelease)
            Add-Check ($tag + '-meta-preview') ($meta.preview -ne $true) 'preview 必须为 false 或缺失'
            Add-Check ($tag + '-meta-space') ($metaSpace -cmatch $SpacePattern) ('spaceCode=' + $metaSpace)
            Add-Check ($tag + '-meta-version') ((Get-Text $meta.version) -cmatch '^v[1-9][0-9]*$') ('version=' + (Get-Text $meta.version))
            Add-Check ($tag + '-meta-digest') ((Get-Text $meta.contentDigest) -cmatch $Hex64Pattern) ('contentDigest=' + (Get-Text $meta.contentDigest))
            if (Get-Text $SpaceCode) {
                Add-Check ($tag + '-meta-space-match') ($metaSpace -ceq $SpaceCode) ('expect=' + $SpaceCode)
            }
            $expectedEndpoint = $OntologyBase.TrimEnd('/') + '/onto/r/' + $metaSpace + '/releases/' + $metaRelease + '/ops/' + $toolCode
            Add-Check ($tag + '-endpoint') ((Get-Text $manifest.endpoint) -ceq $expectedEndpoint) ('endpoint=' + (Get-Text $manifest.endpoint))
            $spaceSeen += $metaSpace
        }
        Add-Check ($tag + '-category') ((Get-Text $manifest.category) -ceq 'ontology_service') ('category=' + (Get-Text $manifest.category))
        Add-Check ($tag + '-adapter_type') ((Get-Text $manifest.adapter_type) -ceq 'HTTP') ('adapter_type=' + (Get-Text $manifest.adapter_type))
        Add-Check ($tag + '-invocation_kind') ((Get-Text $manifest.invocation_kind) -ceq 'SYNC') ('invocation_kind=' + (Get-Text $manifest.invocation_kind))
        Add-Check ($tag + '-http_method') ((Get-Text $manifest.http_method) -ceq 'POST') ('http_method=' + (Get-Text $manifest.http_method))
        $name = Get-Text $manifest.name
        Add-Check ($tag + '-name') ($name.Length -gt 0 -and $name.Length -le 128) ('length=' + $name.Length)
        $description = Get-Text $manifest.description
        Add-Check ($tag + '-description') ($description.Length -gt 0 -and $description.Length -le 2000) ('length=' + $description.Length)
        Add-Check ($tag + '-input_schema') ($null -ne $manifest.input_schema -and $manifest.input_schema -isnot [string]) 'object'
        Add-Check ($tag + '-output_schema') ($null -ne $manifest.output_schema -and $manifest.output_schema -isnot [string]) 'object'
        Add-Check ($tag + '-guard_attrs') ($manifest.guard_attrs -is [System.Array] -and @($manifest.guard_attrs).Count -le 16) ('count=' + @($manifest.guard_attrs).Count)
        Add-Check ($tag + '-error_semantics') ($manifest.error_semantics -is [System.Array]) ('count=' + @($manifest.error_semantics).Count)
        Add-Check ($tag + '-return_spec') ($null -ne $manifest.return_spec -and $manifest.return_spec -isnot [string]) 'object'
        $exec = $manifest.exec_attrs
        $execOk = ($null -ne $exec)
        Add-Check ($tag + '-exec_attrs') $execOk 'exec_attrs 必须存在'
        if ($execOk) {
            $flagsOk = ($exec.readonly_flag -is [bool] -and $exec.side_effect -is [bool] -and $exec.idempotent -is [bool] -and $exec.cancellable -is [bool])
            Add-Check ($tag + '-exec-flags') $flagsOk 'readonly_flag/side_effect/idempotent/cancellable 均为布尔'
            $timeout = 0
            if ($null -ne $exec.timeout_ms) { $timeout = [int]$exec.timeout_ms }
            Add-Check ($tag + '-exec-timeout') ($timeout -ge 100 -and $timeout -le 600000) ('timeout_ms=' + $timeout)
        }
        $ToolCodes += $toolCode
        if ($script:FirstFailure) { break }
    }
    if ($script:FirstFailure) {
        # 复审 P1-7：J4a 任一清单校验失败即结束本段，不得继续导入产生副作用。
        $Verdict = 'FAIL'
        $Reason = 'J4a 清单校验失败，未发起导入：' + $script:FirstFailure
    } else {
        Add-Check 'J4a-同空间' (@($spaceSeen | Sort-Object -Unique).Count -eq 1) ('spaceCodes=' + (@($spaceSeen | Sort-Object -Unique) -join ','))
    }
}

# ---- J4b：导入为技能草稿 ----
if ($Verdict -eq 'PASS') {
    if (-not (Get-Text $AuthRef)) {
        $Verdict = 'BLOCKED'
        $Reason = '缺少 -AuthRef（服务端已登记的 secret_ref，如 env:AGENT_SECRET_* 或 vault:*）'
    } elseif ($AuthRef -notmatch $RefPattern) {
        $Verdict = 'FAIL'
        $Reason = '-AuthRef 形状非法：必须是 env:AGENT_SECRET_* 或 vault:*'
    } elseif (-not (Get-Text $BearerToken)) {
        $Verdict = 'BLOCKED'
        $Reason = '缺少 ADMIN Bearer（-BearerToken 或环境变量）'
    }
}

if ($Verdict -eq 'PASS') {
    $payload = [ordered]@{ manifests = @($Manifests); authScheme = 'API_KEY'; authRef = $AuthRef }
    $bodyJson = $payload | ConvertTo-Json -Depth 32 -Compress
    $bodyBytes = [Text.Encoding]::UTF8.GetByteCount($bodyJson)
    Add-Check 'J4b-请求体大小' ($bodyBytes -le 2097152) ('bytes=' + $bodyBytes)
    if ($bodyBytes -gt 2097152) {
        # 复审 P1-7：体积超限同样不得发导入请求。
        $Verdict = 'FAIL'
        $Reason = 'J4b 请求体超过 2MiB，未发起导入'
    }
}

# 只有 J4a 清单校验与 J4b 前置全部通过、且无 FirstFailure 时才允许导入（复审 P1-7）。
if ($Verdict -eq 'PASS' -and -not $script:FirstFailure) {
    $importUrl = $PlatformBase.TrimEnd('/') + '/admin/skills/import-ontology-manifests'
    try {
        $importResponse = Invoke-J4Http -Method 'POST' -Url $importUrl -Headers @{ 'Authorization' = ('Bearer ' + $BearerToken) } -Body $payload
        Add-Check 'J4b-导入HTTP' ($importResponse.Status -eq 200) ('HTTP ' + $importResponse.Status)
        $importEnvelope = $importResponse.Body
        $importEnvelopeOk = ($null -ne $importEnvelope -and $null -ne $importEnvelope.PSObject.Properties['code'] -and $null -ne $importEnvelope.PSObject.Properties['data'])
        Add-Check 'J4b-信封' ($importEnvelopeOk -and ([string]$importEnvelope.code -ceq 'OK')) ('code=' + $(if ($importEnvelopeOk) { [string]$importEnvelope.code } else { '<missing>' }))
        if ($importResponse.Status -ne 200) {
            $Verdict = 'FAIL'
            $Reason = '导入返回 HTTP ' + $importResponse.Status
        } elseif (-not $importEnvelopeOk) {
            $Verdict = 'FAIL'
            $Reason = '导入响应不是 {code,data} 信封'
        } elseif ([string]$importEnvelope.code -cne 'OK') {
            $Verdict = 'FAIL'
            $Reason = '导入返回业务错误：' + [string]$importEnvelope.code
        } else {
            $draftsNode = $importEnvelope.data.drafts
            $warningsNode = $importEnvelope.data.warnings
            $drafts = @($draftsNode)
            $DraftCount = $drafts.Count
            $WarningCount = @($warningsNode).Count
            Add-Check 'J4b-drafts' ($draftsNode -is [System.Array] -and $drafts.Count -eq $Manifests.Count) ('count=' + $drafts.Count + '/' + $Manifests.Count)
            Add-Check 'J4b-warnings' ($warningsNode -is [System.Array]) ('count=' + $WarningCount)
            $byCode = @{}
            foreach ($manifest in $Manifests) { $byCode[[string]$manifest.tool_code] = $manifest }
            foreach ($draft in $drafts) {
                $code = Get-Text $draft.code
                $tag = 'J4b-草稿-' + $code
                $known = $byCode.ContainsKey($code)
                Add-Check ($tag + '-code') $known ('code=' + $code)
                Add-Check ($tag + '-status') ((Get-Text $draft.status) -ceq 'DRAFT') ('status=' + (Get-Text $draft.status))
                Add-Check ($tag + '-id') ((Get-Text $draft.id) -match '^[0-9]+$') ('id=' + (Get-Text $draft.id))
                Add-Check ($tag + '-currentVersion') ($null -eq $draft.currentVersion) 'currentVersion 必须为 null'
                $versions = @($draft.versions)
                Add-Check ($tag + '-versions') ($versions.Count -eq 1) ('count=' + $versions.Count)
                if ($known -and $versions.Count -eq 1) {
                    $manifest = $byCode[$code]
                    $version = $versions[0]
                    Add-Check ($tag + '-versionNo') ($version.versionNo -eq 1) ('versionNo=' + [string]$version.versionNo)
                    Add-Check ($tag + '-version-status') ((Get-Text $version.status) -ceq 'DRAFT') ('status=' + (Get-Text $version.status))
                    Add-Check ($tag + '-sha256') ((Get-Text $version.sha256) -cmatch $Hex64Pattern) '64hex'
                    Add-Check ($tag + '-semanticDigest') ((Get-Text $version.semanticDigest) -cmatch $Hex64Pattern) '64hex'
                    Add-Check ($tag + '-digestVersion') ($version.digestVersion -eq 1) ('digestVersion=' + [string]$version.digestVersion)
                    $definition = $version.definition
                    $definitionOk = ($null -ne $definition)
                    Add-Check ($tag + '-definition') $definitionOk 'definition 必须存在'
                    if ($definitionOk) {
                        $meta = $manifest.ontology_meta
                        Add-Check ($tag + '-def-type') ((Get-Text $definition.type) -ceq 'HTTP') ('type=' + (Get-Text $definition.type))
                        Add-Check ($tag + '-def-invocation') ((Get-Text $definition.invocationKind) -ceq 'SYNC') ('invocationKind=' + (Get-Text $definition.invocationKind))
                        Add-Check ($tag + '-def-http') ((Get-Text $definition.httpMethod) -ceq 'POST') ('httpMethod=' + (Get-Text $definition.httpMethod))
                        Add-Check ($tag + '-def-endpoint') ((Get-Text $definition.endpoint) -ceq (Get-Text $manifest.endpoint)) ('endpoint=' + (Get-Text $definition.endpoint))
                        Add-Check ($tag + '-def-authScheme') ((Get-Text $definition.authScheme) -ceq 'API_KEY') ('authScheme=' + (Get-Text $definition.authScheme))
                        Add-Check ($tag + '-def-authRef') ((Get-Text $definition.authRef) -ceq $AuthRef) 'authRef 引用一致'
                        $source = $definition.importSource
                        $sourceOk = ($null -ne $source)
                        Add-Check ($tag + '-importSource') $sourceOk 'importSource 必须存在'
                        if ($sourceOk) {
                            Add-Check ($tag + '-src-kind') ((Get-Text $source.kind) -ceq 'ONTOLOGY') ('kind=' + (Get-Text $source.kind))
                            Add-Check ($tag + '-src-tool') ((Get-Text $source.toolCode) -ceq $code) ('toolCode=' + (Get-Text $source.toolCode))
                            Add-Check ($tag + '-src-space') ((Get-Text $source.spaceCode) -ceq (Get-Text $meta.spaceCode)) ('spaceCode=' + (Get-Text $source.spaceCode))
                            Add-Check ($tag + '-src-release') ((Get-Text $source.releaseId) -ceq (Get-Text $meta.releaseId)) ('releaseId=' + (Get-Text $source.releaseId))
                            Add-Check ($tag + '-src-version') ((Get-Text $source.version) -ceq (Get-Text $meta.version)) ('version=' + (Get-Text $source.version))
                            Add-Check ($tag + '-src-digest') ((Get-Text $source.contentDigest) -ceq (Get-Text $meta.contentDigest)) 'contentDigest 一致'
                        }
                    }
                }
            }
            Add-Check 'J4b-覆盖清单' (@($drafts | ForEach-Object { Get-Text $_.code } | Sort-Object -Unique).Count -eq $byCode.Count) ('tools=' + $byCode.Count)
        }
    } catch {
        Add-Check 'J4b-导入' $false ('请求异常: ' + $_.Exception.Message)
        $Verdict = 'FAIL'
        $Reason = '导入请求异常: ' + $_.Exception.Message
    }
}

if ($Verdict -eq 'PASS' -and $script:FirstFailure) { $Verdict = 'FAIL' }
if ($Verdict -eq 'FAIL' -and -not $Reason) { $Reason = $script:FirstFailure }
Complete-J4 $Verdict $Reason
