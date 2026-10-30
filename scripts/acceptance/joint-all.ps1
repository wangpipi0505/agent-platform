<#
.SYNOPSIS
  EX-07 联合验收的统一入口：按配置依次跑 J1/J2、J3、J4，汇总成一个结论。

.DESCRIPTION
  四张探针脚本各自会判 PASS/FAIL/BLOCKED，但"缺哪个输入、卡在哪一步"需要一眼看见，
  所以这里只做编排，不改它们的判定逻辑：
    - 每个 suite 缺必需输入 → 记为 SKIPPED-BLOCKED 并写明缺什么，**不调用**该脚本（避免假装跑过）；
    - 调用后原样保留各脚本自己的证据目录（<EvidenceDir>/j1j2、/j3、/j4）；
    - 汇总退出码：任一 FAIL → 1；否则任一 BLOCKED → 3；全 PASS → 0。FAIL 优先，因为它更严重。

  配置可以直接给参数，也可以给 -ConfigFile（JSON，键名见 Get-Defaults）。
  token 不落配置文件：`-BearerTokenEnv` 指向环境变量名，值从进程环境读。

.EXAMPLE
  ./joint-all.ps1 -ConfigFile .local/joint-config.json -Suites J3,J4
#>
[CmdletBinding()]
param(
    [string]$ConfigFile,
    [string]$OntologyBase = 'http://127.0.0.1:18182',
    [string]$AppBase = 'http://127.0.0.1:18081',
    [string]$SpaceId,
    [string]$ReleaseId,
    [string]$AppCode,
    [string]$PlatformTenant = 'local-v12',
    [string]$Environment = 'production',
    [string]$ExpectSha256,
    [string]$BearerTokenEnv = 'AGENT_ACCEPTANCE_BEARER',
    [string]$EvidenceDir,
    [string[]]$Suites = @('J1', 'J3', 'J4')
)

$ErrorActionPreference = 'Stop'
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path

if ($ConfigFile) {
    if (-not (Test-Path -LiteralPath $ConfigFile)) { throw ('配置文件不存在：' + $ConfigFile) }
    $Conf = Get-Content -LiteralPath $ConfigFile -Raw | ConvertFrom-Json
    foreach ($Name in @('ontologyBase', 'appBase', 'spaceId', 'releaseId', 'appCode', 'platformTenant', 'environment', 'expectSha256', 'evidenceDir', 'bearerTokenEnv')) {
        if ($Conf.PSObject.Properties[$Name] -and $Conf.$Name) {
            switch ($Name) {
                'ontologyBase' { $OntologyBase = [string]$Conf.$Name }
                'appBase' { $AppBase = [string]$Conf.$Name }
                'spaceId' { $SpaceId = [string]$Conf.$Name }
                'releaseId' { $ReleaseId = [string]$Conf.$Name }
                'appCode' { $AppCode = [string]$Conf.$Name }
                'platformTenant' { $PlatformTenant = [string]$Conf.$Name }
                'environment' { $Environment = [string]$Conf.$Name }
                'expectSha256' { $ExpectSha256 = [string]$Conf.$Name }
                'evidenceDir' { $EvidenceDir = [string]$Conf.$Name }
                'bearerTokenEnv' { $BearerTokenEnv = [string]$Conf.$Name }
            }
        }
    }
    if ($Conf.PSObject.Properties['suites'] -and $Conf.suites -and -not $PSBoundParameters.ContainsKey('Suites')) {
        $Suites = @($Conf.suites)
    }
}

$Started = Get-Date
if (-not $EvidenceDir) { $EvidenceDir = '.local/joint-evidence-' + $Started.ToString('yyyyMMdd-HHmmss') }
if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null }
$EvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path

$Bearer = ''
if ($BearerTokenEnv) {
    # 环境变量名来自参数，不能写 $env:$name（PowerShell 会把冒号后面的当成静态名）。
    $Item = Get-Item -Path ('env:' + $BearerTokenEnv) -ErrorAction SilentlyContinue
    if ($Item) { $Bearer = [string]$Item.Value }
}

function Get-Missing {
    param([string]$Suite)
    $Missing = @()
    if (-not $ReleaseId) { $Missing += '-ReleaseId/-ConfigFile.releaseId' }
    if (-not $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY) { $Missing += 'env AGENT_SECRET_ONTO_ENGINE_READ_KEY（只读服务账号 key）' }
    if ($Suite -eq 'J1' -and -not $SpaceId) { $Missing += '-SpaceId（J1/J2 绑定的空间）' }
    if ($Suite -eq 'J1' -and -not $AppCode) { $Missing += '-AppCode（J1/J2 绑定的应用）' }
    if ($Suite -eq 'J4' -and -not $Bearer) { $Missing += ('env ' + $BearerTokenEnv + '（J4 第二段要 ADMIN Bearer）') }
    return $Missing
}

$Rows = New-Object System.Collections.ArrayList
foreach ($Suite in $Suites) {
    $Name = $Suite.Trim().ToUpperInvariant()
    if ($Name -eq 'J2') { $Name = 'J1' }   # J1/J2 共用同一执行器，避免重复跑
    $Missing = Get-Missing $Name
    if ($Missing.Count -gt 0) {
        [void]$Rows.Add([pscustomobject]@{ suite = $Name; status = 'SKIPPED-BLOCKED'; exit = 3; missing = ($Missing -join '; ') })
        continue
    }
    $Args = @{
        OntologyBase = $OntologyBase
        ReleaseId    = $ReleaseId
        EvidenceDir  = (Join-Path $EvidenceDir $Name.ToLowerInvariant())
    }
    switch ($Name) {
        'J1' {
            $Args['SpaceId'] = $SpaceId
            $Args['AppCode'] = $AppCode
            $Args['PlatformTenant'] = $PlatformTenant
            $Args['Environment'] = $Environment
            $Script = Join-Path $Here 'joint-j1-j2.ps1'
        }
        'J3' {
            if ($ExpectSha256) { $Args['ExpectSha256'] = $ExpectSha256 }
            $Script = Join-Path $Here 'joint-j3.ps1'
        }
        'J4' {
            $Args['AppBase'] = $AppBase
            $Args['BearerToken'] = $Bearer
            $Script = Join-Path $Here 'joint-j4.ps1'
        }
        default { throw ('未知 suite：' + $Name) }
    }
    & $Script @Args | Out-Host
    $Code = $LASTEXITCODE
    $Status = switch ($Code) { 0 { 'PASS' } 3 { 'BLOCKED' } default { 'FAIL' } }
    [void]$Rows.Add([pscustomobject]@{ suite = $Name; status = $Status; exit = $Code; missing = '' })
}

$Fails = @($Rows | Where-Object { $_.status -eq 'FAIL' })
$Blocked = @($Rows | Where-Object { $_.status -like '*BLOCKED*' })
$Overall = if ($Fails.Count -gt 0) { 1 } elseif ($Blocked.Count -gt 0) { 3 } else { 0 }
$Verdict = switch ($Overall) { 0 { 'PASS' } 3 { 'BLOCKED' } default { 'FAIL' } }

Write-Host ''
Write-Host '==== EX-07 联合验收汇总 ===='
foreach ($Row in $Rows) {
    Write-Host ('  ' + $Row.suite.PadRight(4) + ' ' + $Row.status.PadRight(15) + ' exit=' + $Row.exit + $(if ($Row.missing) { '  缺：' + $Row.missing } else { '' }))
}
Write-Host ('  总体：' + $Verdict + '（退出码 ' + $Overall + '）')
Write-Host ('  证据目录：' + $EvidenceDir)

$Summary = [ordered]@{
    suite     = 'EX-07 joint acceptance (J1/J2 + J3 + J4)'
    verdict   = $Verdict
    startedAt = $Started.ToString('o')
    finishedAt = (Get-Date).ToString('o')
    inputs    = [ordered]@{
        ontologyBase = $OntologyBase; appBase = $AppBase; spaceId = $SpaceId; releaseId = $ReleaseId
        appCode = $AppCode; platformTenant = $PlatformTenant; environment = $Environment
        expectSha256 = $ExpectSha256; bearerEnv = $BearerTokenEnv; bearerProvided = [bool]$Bearer
    }
    results   = @($Rows)
    boundaries = @(
        '本入口只编排，不替代各脚本的判定；每张卡的证据在 <EvidenceDir>/<suite>/ 下。',
        'SKIPPED-BLOCKED 表示缺输入而未调用该脚本，不是"跑过但通过"。',
        'J1/J2 共用执行器：J2 只是 J1 之后的第二次调用语义，编排列一次避免重复。'
    )
}
$File = Join-Path $EvidenceDir 'joint-summary.json'
($Summary | ConvertTo-Json -Depth 8) | Set-Content -LiteralPath $File -Encoding UTF8
Write-Host ('  汇总：' + $File)

exit $Overall
