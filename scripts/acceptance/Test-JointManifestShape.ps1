# EX-06 复审 P1-1 单测级证明：Assert-JointManifestShape 必须接受 missingStages，
# 并拒绝类型/取值/与 stages 自相矛盾的清单。全部用例真实调用 Joint.Common.ps1 中的断言函数。
# 用法：powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/acceptance/Test-JointManifestShape.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Joint.Common.ps1')

$SessionId = 'joint-shape-test'
$PlatformTenant = 'local-v12'
$Environment = 'production'
$script:Failures = 0
$script:Cases = 0
$Guids = @(
    '11111111-1111-4111-8111-111111111111',
    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    '22222222-2222-4222-8222-222222222222',
    '33333333-3333-4333-8333-333333333333',
    '44444444-4444-4444-8444-444444444444',
    '55555555-5555-4555-8555-555555555555',
    '66666666-6666-4666-8666-666666666666',
    '77777777-7777-4777-8777-777777777777',
    '88888888-8888-4888-8888-888888888888',
    '99999999-9999-4999-8999-999999999999',
    'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
)

function New-TestManifest {
    param([object]$Complete, [object]$MissingStages, [object[]]$StageRows)
    $manifest = [ordered]@{
        sessionId            = $SessionId
        platformTenant       = $PlatformTenant
        environment          = $Environment
        spaceId              = $Guids[0]
        spaceCode            = 'joint_a'
        releaseId            = $Guids[1]
        replacementReleaseId = $Guids[2]
        appCode              = 'joint_app_resolve'
        pinAppCode           = 'joint_app_pin'
        explicitAppCode      = 'joint_app_explicit'
        opCode               = 'joint_list'
        readCredentialId     = $Guids[3]
        binderCredentialId   = $Guids[4]
        consumerCredentialId = $Guids[5]
        readKeyRef           = 'env:AGENT_SECRET_JOINT_READ'
        binderKeyRef         = 'env:AGENT_SECRET_JOINT_BINDER'
        consumerKeyRef       = 'env:AGENT_SECRET_JOINT_CONSUMER'
        platformBaseUrl      = 'http://127.0.0.1:18080'
        ontologyBaseUrl      = 'http://127.0.0.1:18182'
        revocationFixtures   = [ordered]@{
            readCredentialId     = $Guids[6]
            binderCredentialId   = $Guids[7]
            consumerCredentialId = $Guids[8]
            readKeyRef           = 'env:AGENT_SECRET_JOINT_TEMP_READ'
            binderKeyRef         = 'env:AGENT_SECRET_JOINT_TEMP_BINDER'
            consumerKeyRef       = 'env:AGENT_SECRET_JOINT_TEMP_CONSUMER'
        }
        isolationFixtures    = [ordered]@{
            spaceId    = $Guids[9]
            spaceCode  = 'joint_b'
            releaseId  = $Guids[10]
            endpointId = $Guids[11]
            opCode     = 'joint_b_list'
        }
    }
    if ($null -ne $Complete) { $manifest['complete'] = $Complete }
    if ($PSBoundParameters.ContainsKey('MissingStages')) {
        # 空数组/单元素数组传给 [object] 参数时会被绑定成 $null 或标量，先归一化再写入。
        $missing = @()
        if ($null -ne $MissingStages) { $missing = @($MissingStages) }
        $manifest['missingStages'] = $missing
    }
    if ($null -ne $StageRows) {
        $manifest['schemaVersion'] = 1
        $manifest['verdict'] = 'PASS'
        $manifest['generatedAt'] = '2026-11-02T00:00:00+00:00'
        $manifest['missingFields'] = @()
        $manifest['stages'] = @($StageRows)
    }
    return $manifest
}

function New-StageRows {
    param([string[]]$Stages)
    $rows = @()
    foreach ($stage in $Stages) {
        $rows += [ordered]@{ stage = $stage; status = 'PASS'; reason = ''; evidence = '' }
    }
    return $rows
}

function Invoke-ShapeAssertion {
    param([object]$Manifest)
    Assert-JointManifestShape $Manifest $SessionId $PlatformTenant $Environment | Out-Null
}

function Assert-Accepts {
    param([string]$Name, [object]$Manifest)
    $script:Cases++
    try {
        Invoke-ShapeAssertion $Manifest
        Write-Output ('[PASS] ' + $Name)
    } catch {
        $script:Failures++
        Write-Output ('[FAIL] ' + $Name + ' 意外抛异常: ' + $_.Exception.Message)
    }
}

function Assert-Rejects {
    param([string]$Name, [object]$Manifest)
    $script:Cases++
    try {
        Invoke-ShapeAssertion $Manifest
        $script:Failures++
        Write-Output ('[FAIL] ' + $Name + ' 未抛 JOINT_MANIFEST_FIELDS_INVALID')
    } catch {
        if ($_.Exception.Message -cne 'JOINT_MANIFEST_FIELDS_INVALID') {
            $script:Failures++
            Write-Output ('[FAIL] ' + $Name + ' 抛了意外错误: ' + $_.Exception.Message)
        } else {
            Write-Output ('[PASS] ' + $Name)
        }
    }
}

$allStages = @('J1', 'J2', 'J3', 'J4', 'J5', 'J6', 'J7', 'J8', 'J9')

# 回归核心：J1–J9 全 PASS + complete=true + missingStages=[] 曾因白名单漏字段而必抛，现在必须通过。
$completeManifest = New-TestManifest $true @() (New-StageRows $allStages)
Assert-Accepts 'complete=true/missingStages=[] 全 PASS 清单通过' $completeManifest

# 持久化后再读回：ConvertFrom-Json 对单元素/空数组的展开不能把数组判成标量。
$roundTrip = ($completeManifest | ConvertTo-Json -Depth 10) | ConvertFrom-Json
Assert-Accepts 'complete=true 清单 JSON 往返后仍通过' $roundTrip

# 缺段清单：complete=false，missingStages 非空且与 stages 不矛盾。
$missingManifest = New-TestManifest $false @('J5', 'J9') (New-StageRows @('J1', 'J2', 'J3', 'J4', 'J6', 'J7', 'J8'))
Assert-Accepts 'complete=false/missingStages=J5,J9 缺段清单通过' $missingManifest

# 单元素数组：不能因为 PowerShell 标量展开被误判。
$singleMissing = New-TestManifest $false @('J5') (New-StageRows @('J1', 'J2', 'J3', 'J4', 'J6', 'J7', 'J8', 'J9'))
Assert-Accepts 'missingStages 单元素数组通过' $singleMissing

Assert-Rejects 'complete=true 但 missingStages 非空' (New-TestManifest $true @('J5') (New-StageRows $allStages))

# 完全缺字段：构造后移除，避免位置参数把 $null 归一成空数组。
$missingFieldManifest = New-TestManifest $true @() (New-StageRows $allStages)
$missingFieldManifest.Remove('missingStages')
Assert-Rejects 'complete=true 但缺 missingStages 字段' $missingFieldManifest

# 标量字符串：直接改属性，模拟外部清单类型错误。
$scalarManifest = New-TestManifest $false @() (New-StageRows @('J1'))
$scalarManifest['missingStages'] = 'J5'
Assert-Rejects 'missingStages 是字符串而非数组' $scalarManifest
Assert-Rejects 'missingStages 含非 J1..J9 元素' (New-TestManifest $false @('J10') (New-StageRows @('J1')))
Assert-Rejects 'missingStages 含重复元素' (New-TestManifest $false @('J5', 'J5') (New-StageRows @('J1')))
Assert-Rejects 'missingStages 元素不是字符串' (New-TestManifest $false @(5) (New-StageRows @('J1')))

$contradiction = New-TestManifest $false @('J5') (New-StageRows @('J1', 'J2', 'J5'))
Assert-Rejects 'missingStages 与 stages 已交付行自相矛盾' $contradiction

if ($script:Failures -ne 0) {
    Write-Output ('RESULT=FAIL cases=' + $script:Cases + ' failures=' + $script:Failures)
    exit 1
}
Write-Output ('RESULT=PASS cases=' + $script:Cases + ' failures=0')
exit 0
