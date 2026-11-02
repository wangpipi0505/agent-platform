# EX-06 R03（JOINT-001）协议测试：Assert-JointPublicationPrepared 的四制品数组必须按真实4项计。
# Get-JointValue 已用一元逗号保数组；本测试锁定修复后语义：调用处先以变量接收、再显式传参，
# 不再用 @() 外包成 1 元素嵌套数组。全部用例真实调用 Joint.Common.ps1 中的断言函数。
# 用法：powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/acceptance/Test-JointPublicationArtifacts.ps1
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Joint.Common.ps1')

$script:Cases = 0
$script:Failures = 0
$ValidSha = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

function New-TestArtifact([string]$Kind) {
    [pscustomobject]@{ kind = $Kind; ready = $true; size = 1716; sha256 = $ValidSha }
}

function New-TestPublication([object]$Artifacts, [string]$Status) {
    [pscustomobject]@{ status = $Status; artifacts = $Artifacts }
}

function Assert-Case([string]$Name, [scriptblock]$Action, [string]$ExpectError) {
    $script:Cases++
    try {
        & $Action
        if ($ExpectError) {
            $script:Failures++
            Write-Host ('[FAIL] {0}: 期待异常 {1} 但未抛出' -f $Name, $ExpectError)
        } else {
            Write-Host ('[PASS] {0}' -f $Name)
        }
    } catch {
        if ($ExpectError -and ($_.Exception.Message -ceq $ExpectError)) {
            Write-Host ('[PASS] {0}' -f $Name)
        } else {
            $script:Failures++
            Write-Host ('[FAIL] {0}: {1}' -f $Name, $_.Exception.Message)
        }
    }
}

# 1. 正例：四类唯一、READY、非空长度、合法SHA —— 必须通过（不抛出）。
Assert-Case '四类唯一READY制品通过' -Action {
    Assert-JointPublicationPrepared (New-TestPublication @(
        (New-TestArtifact 'BUNDLE'), (New-TestArtifact 'CONTEXT_PACK'),
        (New-TestArtifact 'KNOWLEDGE_INDEX'), (New-TestArtifact 'MANIFEST')) 'READY_TO_COMMIT')
} -ExpectError $null

# 2. 缺类（仅3项）拒绝。
Assert-Case '缺类拒绝' -Action {
    Assert-JointPublicationPrepared (New-TestPublication @(
        (New-TestArtifact 'BUNDLE'), (New-TestArtifact 'CONTEXT_PACK'),
        (New-TestArtifact 'MANIFEST')) 'READY_TO_COMMIT')
} -ExpectError 'JOINT_PUBLICATION_ARTIFACTS_INCOMPLETE'

# 3. 重复类拒绝。
Assert-Case '重复类拒绝' -Action {
    Assert-JointPublicationPrepared (New-TestPublication @(
        (New-TestArtifact 'BUNDLE'), (New-TestArtifact 'BUNDLE'),
        (New-TestArtifact 'KNOWLEDGE_INDEX'), (New-TestArtifact 'MANIFEST')) 'READY_TO_COMMIT')
} -ExpectError 'JOINT_PUBLICATION_ARTIFACTS_INCOMPLETE'

# 4. 非READY拒绝。
Assert-Case '非READY拒绝' -Action {
    $row = New-TestArtifact 'BUNDLE'; $row.ready = $false
    Assert-JointPublicationPrepared (New-TestPublication @(
        $row, (New-TestArtifact 'CONTEXT_PACK'),
        (New-TestArtifact 'KNOWLEDGE_INDEX'), (New-TestArtifact 'MANIFEST')) 'READY_TO_COMMIT')
} -ExpectError 'JOINT_PUBLICATION_ARTIFACTS_INCOMPLETE'

# 5. 零长度拒绝。
Assert-Case '零长度拒绝' -Action {
    $row = New-TestArtifact 'BUNDLE'; $row.size = 0
    Assert-JointPublicationPrepared (New-TestPublication @(
        $row, (New-TestArtifact 'CONTEXT_PACK'),
        (New-TestArtifact 'KNOWLEDGE_INDEX'), (New-TestArtifact 'MANIFEST')) 'READY_TO_COMMIT')
} -ExpectError 'JOINT_PUBLICATION_ARTIFACTS_INCOMPLETE'

# 6. 坏SHA拒绝。
Assert-Case '坏SHA拒绝' -Action {
    $row = New-TestArtifact 'BUNDLE'; $row.sha256 = 'nothex'
    Assert-JointPublicationPrepared (New-TestPublication @(
        $row, (New-TestArtifact 'CONTEXT_PACK'),
        (New-TestArtifact 'KNOWLEDGE_INDEX'), (New-TestArtifact 'MANIFEST')) 'READY_TO_COMMIT')
} -ExpectError 'JOINT_PUBLICATION_ARTIFACTS_INCOMPLETE'

# 7. artifacts 为 null 拒绝。
Assert-Case 'null制品拒绝' -Action {
    Assert-JointPublicationPrepared (New-TestPublication $null 'READY_TO_COMMIT')
} -ExpectError 'JOINT_PUBLICATION_ARTIFACTS_INCOMPLETE'

# 8. 缺 artifacts 属性拒绝。
Assert-Case '缺artifacts属性拒绝' -Action {
    Assert-JointPublicationPrepared ([pscustomobject]@{ status = 'READY_TO_COMMIT' })
} -ExpectError 'JOINT_PUBLICATION_ARTIFACTS_INCOMPLETE'

# 9. 状态非 READY_TO_COMMIT 仍先拒绝（修复不得改变状态门）。
Assert-Case '非READY_TO_COMMIT状态拒绝' -Action {
    Assert-JointPublicationPrepared (New-TestPublication @(
        (New-TestArtifact 'BUNDLE'), (New-TestArtifact 'CONTEXT_PACK'),
        (New-TestArtifact 'KNOWLEDGE_INDEX'), (New-TestArtifact 'MANIFEST')) 'PREPARING')
} -ExpectError 'JOINT_PUBLICATION_NOT_READY'

# 10. 空 missingFields/missingStages 经 Get-JointValue 仍是空数组，不得当缺失值（getter语义未改）。
Assert-Case '空missingFields/missingStages保持空数组' -Action {
    $shape = [pscustomobject]@{ missingFields = @(); missingStages = @() }
    $missingFields = Get-JointValue $shape 'missingFields'
    $missingStages = Get-JointValue $shape 'missingStages'
    if ($null -eq $missingFields -or $null -eq $missingStages) { throw 'JOINT_EMPTY_ARRAY_LOST' }
    if ($missingFields.Count -ne 0 -or $missingStages.Count -ne 0) { throw 'JOINT_EMPTY_ARRAY_NOT_EMPTY' }
} -ExpectError $null

Write-Host ('passed={0} failed={1}' -f ($script:Cases - $script:Failures), $script:Failures)
if ($script:Failures -gt 0) { exit 1 }
exit 0
