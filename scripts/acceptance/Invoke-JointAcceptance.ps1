<#
.SYNOPSIS
  EX-06 联合验收统一入口：按段调用 Prepare-Joint.ps1 与 joint-*.ps1，输出 joint-summary.json。

.DESCRIPTION
  - -Stages 默认 J1,J2,J3,J4；J2 由 joint-j1-j2.ps1 与 J1 合并执行，但仍逐段记录。
  - J5–J9 各有一个真实阶段脚本（joint-j5..j9.ps1），语义对齐《联合验收与回归执行手册》
    §4 原检查项：J5 绑定更新/显式优先、J6 解绑/故障边界、J7 轮换/撤权、J8 租户/空间、
    J9 平台真实发布→pin。入口只负责编排，不替代真实 OR/AP 与凭据。
  - 缺外部输入时该段记 SKIPPED-BLOCKED 且不发起任何调用；实际失败记 FAIL。
    J5 需要 -ReplacementReleaseId；J6 需要 -FaultPlatformBase（注入 404/5xx 的第二个 AP）；
    J7 需要 -DestructiveApproved 与 revocationFixtures；J8 需要 isolationFixtures 与 OR 人工 Bearer；
    J9 需要 -LivePublicationApproved。缺一项即 BLOCKED，绝不以弱断言代替。
  - -DryRun 只打印将执行的命令与缺失项，不发请求、不写证据文件、不创建目录。
  - 退出码：0=PASS（或 DryRun 计划完成），1=FAIL，3=BLOCKED/SKIPPED-BLOCKED。
  - -Prepare 时先跑 Prepare-Joint.ps1（只准备本体资源，不跑平台阶段），成功后从
    session 私密目录解析三把 key 与 consumerKeyRef，再执行各段。
  - 提供 -ManifestPath（Prepare All 的 manifest.json）时，从中取 spaceId/releaseId/
    replacementReleaseId/appCode/pinAppCode/explicitAppCode/opCode、isolationFixtures 与
    revocationFixtures；命令行显式值优先，manifest 只补缺。

  J1–J9 需要真实 OR/AP 服务与真实 spaceId/releaseId/凭据；本入口只负责编排与证据，
  不把桩数据当通过。J4 只导入 DRAFT 技能，不代表连接测试/发布/运行完成。
#>
[CmdletBinding()]
param(
    [string]$ConfigFile,
    [string[]]$Stages = @('J1', 'J2', 'J3', 'J4'),
    [string]$EvidenceDir,
    [switch]$DryRun,
    [string]$OntologyBase = 'http://127.0.0.1:18182',
    [string]$PlatformBase = 'http://127.0.0.1:18080',
    [string]$SpaceId,
    [string]$SpaceCode,
    [string]$ReleaseId,
    [string]$AppCode,
    [string]$ExplicitAppCode,
    [string]$PinAppCode,
    [string]$ReplacementReleaseId,
    [string]$OpCode,
    [string]$FaultPlatformBase,
    [string]$EvaluationPlanPath,
    [string]$IsolationSpaceId,
    [string]$IsolationReleaseId,
    [string]$IsolationOpCode,
    # J8 第二合法身份（B 空间 binder）：显式参数或专用环境变量，缺失时该腿记 SKIPPED-BLOCKED。
    [string]$IsolationBinderKey,
    [string]$IsolationBinderKeyEnv = 'AGENT_SECRET_JOINT_ISOLATION_BINDER_KEY',
    [string]$IsolationAppCode,
    [string]$OntologyAdminBearer,
    [string]$OntologyAdminBearerEnv = 'AGENT_ACCEPTANCE_OR_BEARER',
    [switch]$DestructiveApproved,
    [switch]$LivePublicationApproved,
    [string]$CredentialsFile,
    [ValidateRange(0, 90)][int]$CacheWaitSeconds = 31,
    [string]$PlatformTenant = 'local-v12',
    [string]$Environment = 'production',
    [string]$ExpectSha256,
    [string]$AuthRef,
    [string]$ReadKey,
    [string]$BinderKey,
    [string]$BearerToken,
    [string]$ReadKeyEnv = 'AGENT_SECRET_ONTO_ENGINE_READ_KEY',
    [string]$BinderKeyEnv = 'AGENT_SECRET_ONTO_APP_BINDER_KEY',
    [string]$BearerTokenEnv = 'AGENT_ACCEPTANCE_BEARER',
    [string]$ManifestPath,
    [string]$OutputDirectory,
    [string]$SessionId,
    [string]$PrepareStage = 'Ontology',
    [switch]$Prepare,
    [string]$PrepareSummaryPath,
    [pscredential]$OntologyCredential,
    [string]$OntologyCredentialUserEnv,
    [string]$OntologyCredentialPasswordEnv
)

$ErrorActionPreference = 'Stop'
# 重定向到文件/管道时使用 UTF-8，避免中文检查项在证据日志里按代码页变成乱码。
try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
$ExitPass = 0
$ExitFail = 1
$ExitBlocked = 3
$Started = Get-Date
$UuidPattern = '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
$RefPattern = '^(env:AGENT_SECRET_[A-Z0-9_]+|vault:[^\s]+)$'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'Joint.Common.ps1')

# 记录显式绑定的参数，配置文件不得覆盖命令行显式值。
$script:BoundParameters = @{}
foreach ($name in $PSBoundParameters.Keys) { $script:BoundParameters[$name] = $true }

$script:ReadKeyValue = ''
$script:BinderKeyValue = ''
$script:IsolationBinderKeyValue = ''
$script:BearerValue = ''
$script:Credentials = $null
$script:PreparedSummary = $null

function Get-JointText([object]$Value) {
    if ($null -eq $Value) { return '' }
    return ([string]$Value).Trim()
}

function Test-JointUuid([string]$Value) {
    return ((Get-JointText $Value) -match $UuidPattern)
}

function Set-JointVariable([string]$Name, [object]$Value) {
    # 配置覆盖必须落在脚本作用域；-Scope 1 会写进 Import-JointConfig 的临时函数作用域而丢失。
    Set-Variable -Name $Name -Value $Value -Scope Script
}

function Import-JointConfig([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'JOINT_CONFIG_NOT_FOUND' }
    try { $config = [IO.File]::ReadAllText($Path) | ConvertFrom-Json -ErrorAction Stop } catch { throw 'JOINT_CONFIG_INVALID_JSON' }
    if ($null -eq $config -or $config -is [string]) { throw 'JOINT_CONFIG_INVALID_JSON' }
    $mapping = @(
        @{ Name = 'OntologyBase'; Key = 'ontologyBase' },
        @{ Name = 'PlatformBase'; Key = 'platformBase' },
        @{ Name = 'SpaceId'; Key = 'spaceId' },
        @{ Name = 'SpaceCode'; Key = 'spaceCode' },
        @{ Name = 'ReleaseId'; Key = 'releaseId' },
        @{ Name = 'ReplacementReleaseId'; Key = 'replacementReleaseId' },
        @{ Name = 'AppCode'; Key = 'appCode' },
        @{ Name = 'ExplicitAppCode'; Key = 'explicitAppCode' },
        @{ Name = 'PinAppCode'; Key = 'pinAppCode' },
        @{ Name = 'OpCode'; Key = 'opCode' },
        @{ Name = 'FaultPlatformBase'; Key = 'faultPlatformBase' },
        @{ Name = 'IsolationSpaceId'; Key = 'isolationSpaceId' },
        @{ Name = 'IsolationReleaseId'; Key = 'isolationReleaseId' },
        @{ Name = 'IsolationOpCode'; Key = 'isolationOpCode' },
        @{ Name = 'PlatformTenant'; Key = 'platformTenant' },
        @{ Name = 'Environment'; Key = 'environment' },
        @{ Name = 'ExpectSha256'; Key = 'expectSha256' },
        @{ Name = 'AuthRef'; Key = 'authRef' },
        @{ Name = 'ReadKeyEnv'; Key = 'readKeyEnv' },
        @{ Name = 'BinderKeyEnv'; Key = 'binderKeyEnv' },
        @{ Name = 'BearerTokenEnv'; Key = 'bearerTokenEnv' },
        @{ Name = 'ManifestPath'; Key = 'manifestPath' },
        @{ Name = 'OutputDirectory'; Key = 'outputDirectory' },
        @{ Name = 'SessionId'; Key = 'sessionId' },
        @{ Name = 'PrepareStage'; Key = 'prepareStage' },
        @{ Name = 'EvidenceDir'; Key = 'evidenceDir' }
    )
    foreach ($item in $mapping) {
        if ($script:BoundParameters.ContainsKey($item.Name)) { continue }
        $property = $config.PSObject.Properties[$item.Key]
        if ($null -ne $property -and -not [string]::IsNullOrWhiteSpace([string]$property.Value)) { Set-JointVariable $item.Name ([string]$property.Value) }
    }
    if (-not $script:BoundParameters.ContainsKey('Stages')) {
        $stageProperty = $config.PSObject.Properties['stages']
        if ($null -ne $stageProperty) { Set-JointVariable 'Stages' @($stageProperty.Value | ForEach-Object { [string]$_ }) }
    }
    $prepareProperty = $config.PSObject.Properties['prepare']
    if (-not $script:BoundParameters.ContainsKey('Prepare') -and $null -ne $prepareProperty) { if ([bool]$prepareProperty.Value) { Set-JointVariable 'Prepare' $true } }
    $userEnvProperty = $config.PSObject.Properties['ontologyUserEnv']
    if ($null -ne $userEnvProperty) { Set-JointVariable 'OntologyCredentialUserEnv' ([string]$userEnvProperty.Value) }
    $passwordEnvProperty = $config.PSObject.Properties['ontologyPasswordEnv']
    if ($null -ne $passwordEnvProperty) { Set-JointVariable 'OntologyCredentialPasswordEnv' ([string]$passwordEnvProperty.Value) }
}

function Resolve-JointCredential([string]$Explicit, [string]$KeyRef, [string]$DefaultEnvName) {
    if (Get-JointText $Explicit) { return (Get-JointText $Explicit) }
    $name = Get-JointText $DefaultEnvName
    if ($KeyRef -match '^env:(AGENT_SECRET_[A-Z0-9_]+)$') {
        $name = $Matches[1]
        if ($null -ne $script:Credentials -and $script:Credentials.ContainsKey($name)) { return [string]$script:Credentials[$name] }
    }
    if ($name) {
        $value = [Environment]::GetEnvironmentVariable($name)
        if (Get-JointText $value) { return (Get-JointText $value) }
    }
    return ''
}

function Invoke-JointAcceptanceHttp {
    param([string]$Method, [string]$Url, [hashtable]$Headers, [AllowNull()][object]$Body)
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.Method = $Method
    $request.Proxy = $null
    $request.AllowAutoRedirect = $false
    $request.Timeout = 20000
    $request.ReadWriteTimeout = 20000
    $request.Accept = 'application/json'
    if ($Headers) { foreach ($name in $Headers.Keys) { $request.Headers[$name] = [string]$Headers[$name] } }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 20 -Compress
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        $request.ContentType = 'application/json;charset=utf-8'
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
    } elseif ($Method -in @('POST', 'PUT')) {
        $request.ContentLength = 0
    }
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
        if (Get-JointText $raw) { try { $parsed = $raw | ConvertFrom-Json -ErrorAction Stop } catch { $parsed = $null } }
        return [pscustomobject]@{ Status = $status; Body = $parsed }
    } finally {
        if ($reader) { $reader.Dispose() }
        if ($response) { $response.Dispose() }
    }
}

function Get-JointDeveloperToken {
    if (Get-JointText $script:BearerValue) { return $script:BearerValue }
    $fromEnv = Resolve-JointCredential '' '' $BearerTokenEnv
    if (Get-JointText $fromEnv) { return $fromEnv }
    try {
        $identity = Invoke-JointAcceptanceHttp -Method 'POST' -Url ($PlatformBase.TrimEnd('/') + '/auth/dev-token') -Headers @{} -Body $null
        if ($identity.Status -eq 200 -and $null -ne $identity.Body -and (Get-JointText $identity.Body.code) -ceq 'OK' -and $null -ne $identity.Body.data.accessToken) {
            if ([string]$identity.Body.data.user.tenantId -cne $PlatformTenant) { throw 'JOINT_PLATFORM_IDENTITY_MISMATCH' }
            return [string]$identity.Body.data.accessToken
        }
    } catch {
        return ''
    }
    return ''
}

function Resolve-JointFixtureCredential([object]$Fixture, [string]$KeyName) {
    # 清单里的撤权/隔离夹具只登记 secret_ref（env:AGENT_SECRET_*），明文只在本机私密目录/环境变量。
    if ($null -eq $Fixture) { return '' }
    $ref = Get-JointText (Get-JointValue $Fixture $KeyName)
    if (-not $ref) { return '' }
    return (Resolve-JointCredential '' $ref '')
}

function Get-JointStageRequirements([string]$Suite) {
    $missing = @()
    if ($Suite -eq 'J1') {
        if (-not (Test-JointUuid $SpaceId)) { $missing += 'SpaceId(UUID)' }
        if (-not (Test-JointUuid $ReleaseId)) { $missing += 'ReleaseId(UUID)' }
        if (-not (Get-JointText $AppCode)) { $missing += 'AppCode' }
        if (-not (Get-JointText $script:ReadKeyValue)) { $missing += '只读key(' + $ReadKeyEnv + ')' }
        if (-not (Get-JointText $script:BinderKeyValue)) { $missing += 'binder key(' + $BinderKeyEnv + ')' }
    } elseif ($Suite -eq 'J3') {
        if (-not (Test-JointUuid $ReleaseId)) { $missing += 'ReleaseId(UUID)' }
        if (-not (Get-JointText $script:ReadKeyValue)) { $missing += '只读key(' + $ReadKeyEnv + ')' }
        if ((Get-JointText $ExpectSha256) -and (Get-JointText $ExpectSha256) -notmatch '^[a-f0-9]{64}$') { $missing += 'ExpectSha256(需64位小写hex)' }
    } elseif ($Suite -eq 'J4') {
        if (-not (Test-JointUuid $SpaceId)) { $missing += 'SpaceId(UUID)' }
        if (-not (Test-JointUuid $ReleaseId)) { $missing += 'ReleaseId(UUID)' }
        if (-not (Get-JointText $script:ReadKeyValue)) { $missing += '只读key(' + $ReadKeyEnv + ')' }
        if (-not (Get-JointText $AuthRef)) { $missing += 'AuthRef(已登记secret_ref)' } elseif ((Get-JointText $AuthRef) -notmatch $RefPattern) { $missing += 'AuthRef(形状非法)' }
        if (-not (Get-JointText $script:BearerValue)) { $missing += 'ADMIN Bearer(' + $BearerTokenEnv + ')' }
    } elseif ($Suite -eq 'J5') {
        if (-not (Test-JointUuid $SpaceId)) { $missing += 'SpaceId(UUID)' }
        if (-not (Test-JointUuid $ReleaseId)) { $missing += 'ReleaseId(UUID)' }
        if (-not (Test-JointUuid $ReplacementReleaseId)) { $missing += 'ReplacementReleaseId(UUID)' }
        elseif ($ReplacementReleaseId -ceq $ReleaseId) { $missing += 'ReplacementReleaseId(必须不同于ReleaseId)' }
        if (-not (Get-JointText $AppCode)) { $missing += 'AppCode(绑定解析应用)' }
        if (-not (Get-JointText $ExplicitAppCode)) { $missing += 'ExplicitAppCode(显式优先对照应用)' }
        if (-not (Get-JointText $script:BinderKeyValue)) { $missing += 'binder key(' + $BinderKeyEnv + ')' }
        if (-not (Get-JointText $script:BearerValue)) { $missing += 'ADMIN Bearer(' + $BearerTokenEnv + ')' }
    } elseif ($Suite -eq 'J6') {
        if (-not (Test-JointUuid $SpaceId)) { $missing += 'SpaceId(UUID)' }
        if (-not (Get-JointText $AppCode)) { $missing += 'AppCode(绑定解析应用)' }
        if (-not (Get-JointText $script:BinderKeyValue)) { $missing += 'binder key(' + $BinderKeyEnv + ')' }
        if (-not (Get-JointText $script:BearerValue)) { $missing += 'ADMIN Bearer(' + $BearerTokenEnv + ')' }
        if (-not (Get-JointText $FaultPlatformBase)) { $missing += 'FaultPlatformBase(注入非BINDING_NOT_FOUND的404/5xx的第二个AP)' }
    } elseif ($Suite -eq 'J7') {
        if (-not (Test-JointUuid $SpaceId)) { $missing += 'SpaceId(UUID)' }
        if (-not (Get-JointText $SpaceCode)) { $missing += 'SpaceCode' }
        if (-not (Test-JointUuid $ReleaseId)) { $missing += 'ReleaseId(UUID)' }
        if (-not (Get-JointText $OpCode)) { $missing += 'OpCode' }
        if (-not (Get-JointText $script:OntologyBearerValue)) { $missing += 'OR人工Bearer(' + $OntologyAdminBearerEnv + ')' }
        if (-not (Get-JointText $script:ReadKeyValue)) { $missing += '只读key(' + $ReadKeyEnv + ')' }
        if (-not (Get-JointText (Resolve-JointFixtureCredential $script:Revocation 'binderKeyRef'))) { $missing += 'revocationFixtures.binderKeyRef' }
        if (-not (Get-JointText (Resolve-JointFixtureCredential $script:Revocation 'consumerKeyRef'))) { $missing += 'revocationFixtures.consumerKeyRef' }
        if (-not (Test-JointUuid (Get-JointText (Get-JointValue $script:Revocation 'binderCredentialId')))) { $missing += 'revocationFixtures.binderCredentialId(UUID)' }
        if (-not (Test-JointUuid (Get-JointText (Get-JointValue $script:Revocation 'binderPrincipalId')))) { $missing += 'revocationFixtures.binderPrincipalId(UUID)' }
        if (-not (Test-JointUuid (Get-JointText (Get-JointValue $script:Revocation 'consumerCredentialId')))) { $missing += 'revocationFixtures.consumerCredentialId(UUID)' }
        if (-not $DestructiveApproved) { $missing += 'DestructiveApproved(破坏性撤权显式批准)' }
    } elseif ($Suite -eq 'J8') {
        if (-not (Test-JointUuid $SpaceId)) { $missing += 'SpaceId(UUID)' }
        if (-not (Test-JointUuid $ReleaseId)) { $missing += 'ReleaseId(UUID)' }
        if (-not (Get-JointText $AppCode)) { $missing += 'AppCode(绑定解析应用)' }
        if (-not (Test-JointUuid $IsolationSpaceId)) { $missing += 'IsolationSpaceId(UUID)' }
        if (-not (Test-JointUuid $IsolationReleaseId)) { $missing += 'IsolationReleaseId(UUID)' }
        if (-not (Get-JointText $IsolationOpCode)) { $missing += 'IsolationOpCode' }
        if (-not (Get-JointText $script:BinderKeyValue)) { $missing += 'binder key(' + $BinderKeyEnv + ')' }
        if (-not (Get-JointText $script:OntologyBearerValue)) { $missing += 'OR人工Bearer(' + $OntologyAdminBearerEnv + ')' }
    } elseif ($Suite -eq 'J9') {
        if (-not (Test-JointUuid $SpaceId)) { $missing += 'SpaceId(UUID)' }
        if (-not (Test-JointUuid $ReleaseId)) { $missing += 'ReleaseId(UUID)' }
        if (-not (Test-JointUuid $ReplacementReleaseId)) { $missing += 'ReplacementReleaseId(UUID)' }
        elseif ($ReplacementReleaseId -ceq $ReleaseId) { $missing += 'ReplacementReleaseId(必须不同于ReleaseId)' }
        if (-not (Get-JointText $PinAppCode)) { $missing += 'PinAppCode(发布pin应用)' }
        if (-not (Get-JointText $script:BinderKeyValue)) { $missing += 'binder key(' + $BinderKeyEnv + ')' }
        if (-not (Get-JointText $script:BearerValue)) { $missing += 'ADMIN Bearer(' + $BearerTokenEnv + ')' }
        if (-not $LivePublicationApproved) { $missing += 'LivePublicationApproved(真实发布显式批准)' }
    }
    return @($missing)
}

function Get-SuiteEvidencePattern([string]$Suite) {
    if ($Suite -eq 'J1') { return 'j1-j2-*.json' }
    if ($Suite -eq 'J3') { return 'j3-*.json' }
    if ($Suite -eq 'J4') { return 'j4-*.json' }
    return ($Suite.ToLowerInvariant() + '-*.json')
}

function Get-LatestEvidence([string]$Suite) {
    if (-not (Get-JointText $EvidenceDir)) { return '' }
    if (-not (Test-Path -LiteralPath $EvidenceDir -PathType Container)) { return '' }
    $file = Get-ChildItem -LiteralPath $EvidenceDir -Filter (Get-SuiteEvidencePattern $Suite) -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $file) { return '' }
    return $file.FullName
}

function Format-JointPlanArgs([hashtable]$Arguments, [string[]]$SecretKeys) {
    $parts = @()
    foreach ($key in ($Arguments.Keys | Sort-Object)) {
        $value = [string]$Arguments[$key]
        if ($SecretKeys -contains $key) {
            if (Get-JointText $value) { $parts += ('-' + $key + ' <redacted>') }
        } else {
            $parts += ('-' + $key + ' "' + $value + '"')
        }
    }
    return ($parts -join ' ')
}

# ---- 配置与阶段规范化 ----
try {
    if (Get-JointText $ConfigFile) { Import-JointConfig $ConfigFile }
} catch {
    Write-Output ('[FAIL] 配置加载失败: ' + $_.Exception.Message)
    exit $ExitFail
}

$requested = @()
$stagePieces = @()
foreach ($stage in @($Stages)) {
    # -File 从命令行只能传一个字符串，允许 "-Stages J5,J6,J7" 这种逗号写法。
    foreach ($piece in ([string]$stage).Split(',')) { if (Get-JointText $piece) { $stagePieces += (Get-JointText $piece) } }
}
foreach ($stage in $stagePieces) {
    $upper = (Get-JointText $stage).ToUpperInvariant()
    if (-not $upper) { continue }
    if ($upper -notmatch '^J[1-9]$') {
        Write-Output ('[FAIL] 未知阶段: ' + $stage + '（允许 J1..J9）')
        exit $ExitFail
    }
    if ($requested -notcontains $upper) { $requested += $upper }
}
if ($requested.Count -eq 0) { $requested = @('J1', 'J2', 'J3', 'J4') }
$requested = @($requested | Sort-Object)

if (-not (Get-JointText $SessionId)) { $SessionId = 'joint-' + $Started.ToString('yyyyMMdd-HHmmss') }
if (-not (Get-JointText $OutputDirectory)) { $OutputDirectory = Join-Path $repositoryRoot '.local/acceptance' }
if (-not (Get-JointText $PrepareSummaryPath)) { $PrepareSummaryPath = Join-Path $OutputDirectory ('prepare-' + $SessionId + '.json') }
if (-not (Get-JointText $EvidenceDir)) { $EvidenceDir = Join-Path $repositoryRoot ('.local/joint-evidence-' + $Started.ToString('yyyyMMdd-HHmmss')) }

$results = New-Object System.Collections.ArrayList
$prepareExit = 0

# ---- 可选：先跑准备阶段（真实写入 OR，DryRun 只打印） ----
if ($Prepare) {
    $prepareArgs = @{
        SessionId      = $SessionId
        OntologyBaseUrl = $OntologyBase
        PlatformBaseUrl = $PlatformBase
        PlatformTenant  = $PlatformTenant
        Environment     = $Environment
        Stage           = $PrepareStage
        SummaryPath     = $PrepareSummaryPath
        OutputDirectory = $OutputDirectory
    }
    if ($OntologyCredential) {
        $prepareArgs.OntologyCredential = $OntologyCredential
    } elseif ((Get-JointText $OntologyCredentialUserEnv) -and (Get-JointText $OntologyCredentialPasswordEnv)) {
        $userName = [Environment]::GetEnvironmentVariable($OntologyCredentialUserEnv)
        $password = [Environment]::GetEnvironmentVariable($OntologyCredentialPasswordEnv)
        if ((Get-JointText $userName) -and (Get-JointText $password)) {
            $secure = ConvertTo-SecureString -String $password -AsPlainText -Force
            $prepareArgs.OntologyCredential = New-Object System.Management.Automation.PSCredential($userName, $secure)
        }
    }
    if ($DryRun) {
        Write-Output ('[PLAN] Prepare-Joint.ps1 ' + (Format-JointPlanArgs $prepareArgs @('OntologyCredential')))
    } else {
        & (Join-Path $PSScriptRoot 'Prepare-Joint.ps1') @prepareArgs | Out-Host
        $prepareExit = $LASTEXITCODE
        if ($prepareExit -eq 0) {
            try {
                $script:PreparedSummary = [IO.File]::ReadAllText($PrepareSummaryPath) | ConvertFrom-Json -ErrorAction Stop
                $resources = $script:PreparedSummary.resources
                if ($null -eq $resources) { throw 'JOINT_PREPARE_SUMMARY_INCOMPLETE' }
                if (-not (Get-JointText $SpaceId)) { $SpaceId = [string]$resources.spaceId }
                if (-not (Get-JointText $SpaceCode)) { $SpaceCode = [string]$resources.spaceCode }
                if (-not (Get-JointText $ReleaseId)) { $ReleaseId = [string]$resources.releaseId }
                if (-not (Get-JointText $AuthRef)) { $AuthRef = [string]$resources.consumerKeyRef }
                if (-not (Get-JointText $AppCode) -and $null -ne $script:PreparedSummary.platformAppDrafts) { $AppCode = [string]$script:PreparedSummary.platformAppDrafts.appCode }
                $privateDir = Join-Path $OutputDirectory $SessionId
                $script:Credentials = Read-JointPrivateEnvironment $repositoryRoot $privateDir
            } catch {
                Write-Output ('[FAIL] 准备摘要解析失败: ' + $_.Exception.Message)
                exit $ExitFail
            }
        } else {
            Write-Output ('[FAIL] Prepare-Joint.ps1 退出码 ' + $prepareExit)
        }
    }
}

# ---- 可选：从 Prepare All 的 manifest.json 补缺（命令行显式值优先） ----
$script:Manifest = $null
$script:Revocation = $null
$script:Isolation = $null
if (Get-JointText $ManifestPath) {
    if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
        Write-Output ('[FAIL] ManifestPath 不存在: ' + $ManifestPath)
        exit $ExitFail
    }
    try { $script:Manifest = [IO.File]::ReadAllText($ManifestPath) | ConvertFrom-Json -ErrorAction Stop } catch {
        Write-Output ('[FAIL] manifest 解析失败: ' + $_.Exception.Message)
        exit $ExitFail
    }
    $manifestMap = @(
        @{ Name = 'SpaceId'; Key = 'spaceId' },
        @{ Name = 'SpaceCode'; Key = 'spaceCode' },
        @{ Name = 'ReleaseId'; Key = 'releaseId' },
        @{ Name = 'ReplacementReleaseId'; Key = 'replacementReleaseId' },
        @{ Name = 'AppCode'; Key = 'appCode' },
        @{ Name = 'PinAppCode'; Key = 'pinAppCode' },
        @{ Name = 'ExplicitAppCode'; Key = 'explicitAppCode' },
        @{ Name = 'OpCode'; Key = 'opCode' },
        @{ Name = 'OntologyBase'; Key = 'ontologyBaseUrl' },
        @{ Name = 'PlatformBase'; Key = 'platformBaseUrl' },
        @{ Name = 'PlatformTenant'; Key = 'platformTenant' },
        @{ Name = 'Environment'; Key = 'environment' }
    )
    foreach ($item in $manifestMap) {
        $current = Get-Variable -Name $item.Name -ValueOnly -ErrorAction SilentlyContinue
        if (Get-JointText $current) { continue }
        $value = Get-JointText (Get-JointValue $script:Manifest $item.Key)
        if ($value) { Set-JointVariable $item.Name $value }
    }
    $rev = Get-JointValue $script:Manifest 'revocationFixtures'
    if ($null -ne $rev) { $script:Revocation = $rev }
    $iso = Get-JointValue $script:Manifest 'isolationFixtures'
    if ($null -ne $iso) {
        $script:Isolation = $iso
        if (-not (Get-JointText $IsolationSpaceId)) { $IsolationSpaceId = Get-JointText (Get-JointValue $iso 'spaceId') }
        if (-not (Get-JointText $IsolationReleaseId)) { $IsolationReleaseId = Get-JointText (Get-JointValue $iso 'releaseId') }
        if (-not (Get-JointText $IsolationOpCode)) { $IsolationOpCode = Get-JointText (Get-JointValue $iso 'opCode') }
    }
}

# session 私密目录里的 credentials.env 供各段按 keyRef 取值（J5/J7/J8/J9 需 -CredentialsFile）。
if (-not (Get-JointText $CredentialsFile)) {
    $candidateCredentials = Join-Path (Join-Path $OutputDirectory $SessionId) 'credentials.env'
    if (Test-Path -LiteralPath $candidateCredentials -PathType Leaf) { $CredentialsFile = $candidateCredentials }
}

$script:ReadKeyValue = Resolve-JointCredential $ReadKey '' $ReadKeyEnv
$script:BinderKeyValue = Resolve-JointCredential $BinderKey '' $BinderKeyEnv
# 第二合法身份不按 *_BINDER 后缀猜凭据文件，避免误用主 binder；只认显式值/专用环境变量。
$script:IsolationBinderKeyValue = Resolve-JointCredential $IsolationBinderKey '' $IsolationBinderKeyEnv
$script:BearerValue = Resolve-JointCredential $BearerToken '' $BearerTokenEnv
$script:OntologyBearerValue = Resolve-JointCredential $OntologyAdminBearer '' $OntologyAdminBearerEnv
if (-not $DryRun -and $prepareExit -eq 0) {
    $platformBearerStages = @($requested | Where-Object { @('J4', 'J5', 'J6', 'J9') -contains $_ })
    if ($platformBearerStages.Count -gt 0 -and -not (Get-JointText $script:BearerValue)) {
        $script:BearerValue = Get-JointDeveloperToken
    }
}

# 准备失败时不允许出现“某段通过”。
if ($prepareExit -ne 0) {
    foreach ($stage in $requested) {
        [void]$results.Add([ordered]@{ stage = $stage; status = 'SKIPPED-BLOCKED'; reason = ('Preparation 未就绪（退出码 ' + $prepareExit + '）'); evidence = ''; log = '' })
    }
    $verdict = 'FAIL'
    if ($prepareExit -eq 2) { $verdict = 'BLOCKED' }
    $summary = [ordered]@{
        suite      = 'JOINT'
        verdict    = $verdict
        startedAt  = $Started.ToUniversalTime().ToString('o')
        finishedAt = (Get-Date).ToUniversalTime().ToString('o')
        dryRun     = [bool]$DryRun
        inputs     = [ordered]@{ ontologyBase = $OntologyBase; platformBase = $PlatformBase; releaseId = $ReleaseId; spaceId = $SpaceId; spaceCode = $SpaceCode; appCode = $AppCode; platformTenant = $PlatformTenant; environment = $Environment; sessionId = $SessionId; prepareExit = $prepareExit }
        results    = @($results)
        totals     = [ordered]@{ pass = 0; fail = 0; blocked = 0; skipped = $results.Count; planned = 0 }
        boundaries = @('Prepare 未通过，所有段均未执行，不得记通过。')
    }
    if (-not $DryRun) {
        if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null }
        ($summary | ConvertTo-Json -Depth 10) | Set-Content -LiteralPath (Join-Path $EvidenceDir 'joint-summary.json') -Encoding UTF8
    }
    Write-Output ('JOINT verdict=' + $verdict)
    if ($verdict -eq 'BLOCKED') { exit $ExitBlocked }
    exit $ExitFail
}

# ---- 逐段执行 ----
$suites = @()
foreach ($suite in @('J1', 'J3', 'J4', 'J5', 'J6', 'J7', 'J8', 'J9')) {
    if ($requested -contains $suite) { $suites += $suite }
    elseif ($suite -eq 'J1' -and $requested -contains 'J2') { $suites += $suite }
}

$oldReadKey = [Environment]::GetEnvironmentVariable('AGENT_SECRET_ONTO_ENGINE_READ_KEY')
$oldBinderKey = [Environment]::GetEnvironmentVariable('AGENT_SECRET_ONTO_APP_BINDER_KEY')

try {
    foreach ($suite in $suites) {
        $rowStages = @($suite)
        if ($suite -eq 'J1' -and $requested -contains 'J2') { $rowStages = @('J1', 'J2') }
        $missing = @(Get-JointStageRequirements $suite)
        if ($missing.Count -gt 0) {
            $reason = '缺外部输入: ' + ($missing -join '; ')
            Write-Output ('[BLOCKED] ' + $suite + ' 未执行：' + $reason)
            foreach ($rowStage in $rowStages) {
                [void]$results.Add([ordered]@{ stage = $rowStage; status = 'SKIPPED-BLOCKED'; reason = $reason; evidence = ''; log = '' })
            }
            continue
        }
        $scriptMap = @{ J1 = 'joint-j1-j2.ps1'; J3 = 'joint-j3.ps1'; J4 = 'joint-j4.ps1'; J5 = 'joint-j5.ps1'; J6 = 'joint-j6.ps1'; J7 = 'joint-j7.ps1'; J8 = 'joint-j8.ps1'; J9 = 'joint-j9.ps1' }
        $scriptPath = Join-Path $PSScriptRoot $scriptMap[$suite]
        $callArgs = [ordered]@{}
        if ($suite -eq 'J1') {
            $callArgs = [ordered]@{ OntologyBase = $OntologyBase.TrimEnd('/'); SpaceId = $SpaceId; ReleaseId = $ReleaseId; AppCode = $AppCode; PlatformTenant = $PlatformTenant; Environment = $Environment; EvidenceDir = $EvidenceDir }
        } elseif ($suite -eq 'J3') {
            $callArgs = [ordered]@{ OntologyBase = $OntologyBase.TrimEnd('/'); ReleaseId = $ReleaseId; EvidenceDir = $EvidenceDir }
            if (Get-JointText $ExpectSha256) { $callArgs.ExpectSha256 = $ExpectSha256 }
        } elseif ($suite -eq 'J4') {
            $callArgs = [ordered]@{ OntologyBase = $OntologyBase.TrimEnd('/'); PlatformBase = $PlatformBase.TrimEnd('/'); SpaceId = $SpaceId; SpaceCode = $SpaceCode; ReleaseId = $ReleaseId; AuthRef = $AuthRef; ReadKey = $script:ReadKeyValue; BearerToken = $script:BearerValue; EvidenceDir = $EvidenceDir }
        } elseif ($suite -eq 'J5') {
            $callArgs = [ordered]@{ OntologyBase = $OntologyBase.TrimEnd('/'); PlatformBase = $PlatformBase.TrimEnd('/'); PlatformTenant = $PlatformTenant; Environment = $Environment; SpaceId = $SpaceId; SpaceCode = $SpaceCode; ReleaseId = $ReleaseId; ReplacementReleaseId = $ReplacementReleaseId; AppCode = $AppCode; ExplicitAppCode = $ExplicitAppCode; BinderKey = $script:BinderKeyValue; BearerToken = $script:BearerValue; CacheWaitSeconds = $CacheWaitSeconds; EvidenceDir = $EvidenceDir; SessionId = $SessionId }
            if (Get-JointText $CredentialsFile) { $callArgs.CredentialsFile = $CredentialsFile }
        } elseif ($suite -eq 'J6') {
            $callArgs = [ordered]@{ OntologyBase = $OntologyBase.TrimEnd('/'); PlatformBase = $PlatformBase.TrimEnd('/'); FaultPlatformBase = $FaultPlatformBase.TrimEnd('/'); PlatformTenant = $PlatformTenant; Environment = $Environment; SpaceId = $SpaceId; AppCode = $AppCode; BinderKey = $script:BinderKeyValue; BearerToken = $script:BearerValue; CacheWaitSeconds = $CacheWaitSeconds; EvidenceDir = $EvidenceDir; SessionId = $SessionId }
            if (Get-JointText $CredentialsFile) { $callArgs.CredentialsFile = $CredentialsFile }
        } elseif ($suite -eq 'J7') {
            $callArgs = [ordered]@{
                OntologyBase = $OntologyBase.TrimEnd('/'); PlatformTenant = $PlatformTenant; Environment = $Environment
                SpaceId = $SpaceId; SpaceCode = $SpaceCode; ReleaseId = $ReleaseId; OpCode = $OpCode
                OntologyAdminBearer = $script:OntologyBearerValue; ReadKey = $script:ReadKeyValue
                RevocationBinderKey = (Resolve-JointFixtureCredential $script:Revocation 'binderKeyRef')
                RevocationBinderCredentialId = (Get-JointText (Get-JointValue $script:Revocation 'binderCredentialId'))
                RevocationBinderPrincipalId = (Get-JointText (Get-JointValue $script:Revocation 'binderPrincipalId'))
                RevocationConsumerKey = (Resolve-JointFixtureCredential $script:Revocation 'consumerKeyRef')
                RevocationConsumerCredentialId = (Get-JointText (Get-JointValue $script:Revocation 'consumerCredentialId'))
                EvidenceDir = $EvidenceDir; SessionId = $SessionId; DestructiveApproved = $true
            }
            if (Get-JointText $CredentialsFile) { $callArgs.CredentialsFile = $CredentialsFile }
        } elseif ($suite -eq 'J8') {
            $callArgs = [ordered]@{ OntologyBase = $OntologyBase.TrimEnd('/'); PlatformTenant = $PlatformTenant; Environment = $Environment; SpaceId = $SpaceId; ReleaseId = $ReleaseId; AppCode = $AppCode; IsolationSpaceId = $IsolationSpaceId; IsolationReleaseId = $IsolationReleaseId; IsolationOpCode = $IsolationOpCode; IsolationBinderKey = $script:IsolationBinderKeyValue; IsolationAppCode = $IsolationAppCode; BinderKey = $script:BinderKeyValue; OntologyAdminBearer = $script:OntologyBearerValue; EvidenceDir = $EvidenceDir; SessionId = $SessionId }
            if (Get-JointText $CredentialsFile) { $callArgs.CredentialsFile = $CredentialsFile }
        } else {
            $callArgs = [ordered]@{ OntologyBase = $OntologyBase.TrimEnd('/'); PlatformBase = $PlatformBase.TrimEnd('/'); PlatformTenant = $PlatformTenant; Environment = $Environment; SpaceId = $SpaceId; SpaceCode = $SpaceCode; ReleaseId = $ReleaseId; ReplacementReleaseId = $ReplacementReleaseId; PinAppCode = $PinAppCode; BinderKey = $script:BinderKeyValue; BearerToken = $script:BearerValue; EvidenceDir = $EvidenceDir; SessionId = $SessionId; LivePublicationApproved = $true }
            if (Get-JointText $CredentialsFile) { $callArgs.CredentialsFile = $CredentialsFile }
            if (Get-JointText $EvaluationPlanPath) { $callArgs.EvaluationPlanPath = $EvaluationPlanPath }
        }
        $secretKeys = @('ReadKey', 'BinderKey', 'BearerToken', 'OntologyAdminBearer', 'RevocationBinderKey', 'RevocationConsumerKey')
        $display = '& ' + $scriptMap[$suite] + ' ' + (Format-JointPlanArgs $callArgs $secretKeys)
        if ($DryRun) {
            Write-Output ('[PLAN] ' + $display)
            foreach ($rowStage in $rowStages) {
                [void]$results.Add([ordered]@{ stage = $rowStage; status = 'PLANNED'; reason = 'DryRun 未执行'; evidence = ''; log = '' })
            }
            continue
        }
        Write-Output ('[RUN ] ' + $display)
        if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null }
        $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = $script:ReadKeyValue
        $env:AGENT_SECRET_ONTO_APP_BINDER_KEY = $script:BinderKeyValue
        $logPath = Join-Path $EvidenceDir ($suite.ToLowerInvariant() + '.log')
        $output = @()
        try {
            $output = @(& $scriptPath @callArgs 2>&1 | ForEach-Object { [string]$_ })
            $suiteExit = $LASTEXITCODE
        } finally {
            [Environment]::SetEnvironmentVariable('AGENT_SECRET_ONTO_ENGINE_READ_KEY', $oldReadKey)
            [Environment]::SetEnvironmentVariable('AGENT_SECRET_ONTO_APP_BINDER_KEY', $oldBinderKey)
        }
        foreach ($line in $output) { Write-Output $line }
        if ($output.Count -gt 0) { ($output -join [Environment]::NewLine) | Set-Content -LiteralPath $logPath -Encoding UTF8 }
        $status = 'FAIL'
        if ($suiteExit -eq 0) { $status = 'PASS' } elseif ($suiteExit -eq 3) { $status = 'BLOCKED' }
        $reason = ''
        if ($status -ne 'PASS') {
            $failureLine = @($output | Where-Object { $_ -match '^\[(FAIL|BLOCKED)\]' } | Select-Object -First 1)
            if ($failureLine.Count -gt 0) { $reason = $failureLine[0].Trim() }
        }
        $evidencePath = Get-LatestEvidence $suite
        foreach ($rowStage in $rowStages) {
            [void]$results.Add([ordered]@{ stage = $rowStage; status = $status; reason = $reason; evidence = $evidencePath; log = $logPath })
        }
    }

    # 兜底：请求了但没有对应行的阶段一律如实记 SKIPPED-BLOCKED，不得静默跳过。
    foreach ($stage in $requested) {
        if (@($results | Where-Object { $_.stage -eq $stage }).Count -eq 0) {
            [void]$results.Add([ordered]@{ stage = $stage; status = 'SKIPPED-BLOCKED'; reason = '该阶段未产生执行行（编排缺失，不得记通过）'; evidence = ''; log = '' })
        }
    }
} finally {
    [Environment]::SetEnvironmentVariable('AGENT_SECRET_ONTO_ENGINE_READ_KEY', $oldReadKey)
    [Environment]::SetEnvironmentVariable('AGENT_SECRET_ONTO_APP_BINDER_KEY', $oldBinderKey)
}

# ---- 汇总 ----
$passCount = @($results | Where-Object { $_.status -eq 'PASS' }).Count
$failCount = @($results | Where-Object { $_.status -eq 'FAIL' }).Count
$blockedCount = @($results | Where-Object { $_.status -eq 'BLOCKED' }).Count
$skippedCount = @($results | Where-Object { $_.status -eq 'SKIPPED-BLOCKED' }).Count
$plannedCount = @($results | Where-Object { $_.status -eq 'PLANNED' }).Count

if ($failCount -gt 0) {
    $verdict = 'FAIL'
} elseif ($blockedCount -gt 0 -or $skippedCount -gt 0) {
    $verdict = 'BLOCKED'
} elseif ($DryRun) {
    $verdict = 'DRYRUN'
} else {
    $verdict = 'PASS'
}

$summary = [ordered]@{
    suite      = 'JOINT'
    verdict    = $verdict
    startedAt  = $Started.ToUniversalTime().ToString('o')
    finishedAt = (Get-Date).ToUniversalTime().ToString('o')
    dryRun     = [bool]$DryRun
    inputs     = [ordered]@{ ontologyBase = $OntologyBase; platformBase = $PlatformBase; faultPlatformBase = $FaultPlatformBase; releaseId = $ReleaseId; replacementReleaseId = $ReplacementReleaseId; spaceId = $SpaceId; spaceCode = $SpaceCode; appCode = $AppCode; pinAppCode = $PinAppCode; explicitAppCode = $ExplicitAppCode; opCode = $OpCode; isolationSpaceId = $IsolationSpaceId; isolationReleaseId = $IsolationReleaseId; isolationOpCode = $IsolationOpCode; isolationAppCode = $IsolationAppCode; isolationBinderKeyProvided = [bool](Get-JointText $script:IsolationBinderKeyValue); platformTenant = $PlatformTenant; environment = $Environment; expectSha256 = $ExpectSha256; sessionId = $SessionId; destructiveApproved = [bool]$DestructiveApproved; livePublicationApproved = [bool]$LivePublicationApproved; authRefProvided = [bool](Get-JointText $AuthRef); readKeyProvided = [bool](Get-JointText $script:ReadKeyValue); binderKeyProvided = [bool](Get-JointText $script:BinderKeyValue); bearerProvided = [bool](Get-JointText $script:BearerValue); ontologyAdminBearerProvided = [bool](Get-JointText $script:OntologyBearerValue); manifestPath = $ManifestPath }
    results    = @($results)
    totals     = [ordered]@{ pass = $passCount; fail = $failCount; blocked = $blockedCount; skipped = $skippedCount; planned = $plannedCount }
    boundaries = @(
        'J1/J2、J3 需要真实 OR/AP 服务、真实 spaceId/releaseId 与 ontosa_ 凭据；本入口只做编排，不替代真实输入。',
        'J4 只证明正式清单可导入为 DRAFT 技能，不代表连接测试、模型/技能发布或真实运行完成。',
        'J5–J9 对齐手册 §4 原检查项；缺段输入（ReplacementReleaseId/FaultPlatformBase/revocationFixtures/isolationFixtures/显式批准）一律 BLOCKED，不做弱断言。',
        'J6 的 pinFailure 记录只存在于 AP 运行快照（数据库），HTTP 只暴露 observation；本段以“非 503 + observation.releaseId 为空”证明跳过策略，不伪造字段。',
        'J7 只动 revocationFixtures 临时主体，主 readonly/binder/consumer 凭据不动；撤权后临时主体不可恢复。',
        'stub 仅用于脚本契约自检，桩数据不得作为验收通过证据。',
        'S1 A/B/C/D 不在本入口；由 Invoke-S1Acceptance.ps1 单独执行。'
    )
}

if (-not $DryRun) {
    if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null }
    ($summary | ConvertTo-Json -Depth 10) | Set-Content -LiteralPath (Join-Path $EvidenceDir 'joint-summary.json') -Encoding UTF8
}

Write-Output ('JOINT stages=' + ($requested -join ','))
# 逐段打印状态与原因，便于证据日志直接看到哪一段被跳过、卡在哪个输入。
foreach ($row in $results) { Write-Output ('  [' + $row.status + '] ' + $row.stage + ': ' + $row.reason) }
Write-Output ('JOINT totals pass=' + $passCount + ' fail=' + $failCount + ' blocked=' + $blockedCount + ' skipped=' + $skippedCount + ' planned=' + $plannedCount)
Write-Output ('JOINT verdict=' + $verdict)
if (-not $DryRun) { Write-Output ('JOINT summary=' + (Join-Path $EvidenceDir 'joint-summary.json')) }
if ($verdict -eq 'FAIL') { exit $ExitFail }
if ($verdict -eq 'BLOCKED') { exit $ExitBlocked }
exit $ExitPass
