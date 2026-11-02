# R05（JOINT-003）摘要对象协议测试：J3的三个摘要对象必须分开——
# live Envelope摘要（含动态traceId）仅观测；-ExpectSha256只与冻结制品原件核对；
# 无独立原件时BLOCKED；错对象期望（如BUNDLE摘要）与真实内容变化仍FAIL；traceId变化不误判篡改。
# 真实执行 joint-j3.ps1 内的 Resolve-J3DigestDecision 函数。
# 用法：powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/acceptance/Test-JointContextPackDigest.ps1
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'joint-j3.ps1'
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$null, [ref]$parseErrors)
if ($parseErrors -and $parseErrors.Count) { throw ('J3_SCRIPT_PARSE_FAILED: ' + $parseErrors[0].Message) }
foreach ($name in @('Get-Text', 'Resolve-J3DigestDecision')) {
    $wanted = $name
    $function = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $wanted }, $true)
    if (-not $function) { throw ('JOINT_J3_FUNCTION_MISSING: ' + $wanted) }
    Invoke-Expression $function.Extent.Text
}

$script:Cases = 0
$script:Failures = 0
function Assert-DigestCase([string]$Name, [scriptblock]$Check) {
    $script:Cases++
    try { & $Check; Write-Host ('[PASS] ' + $Name) }
    catch { $script:Failures++; Write-Host ('[FAIL] ' + $Name + ': ' + $_.Exception.Message) }
}

# 造一个真实冻结制品原件与其SHA。
$artifactPath = Join-Path ([IO.Path]::GetTempPath()) ('j3-frozen-' + [Guid]::NewGuid().ToString('N') + '.pack')
[IO.File]::WriteAllBytes($artifactPath, [Text.Encoding]::UTF8.GetBytes('conceptCards/termDictionary/relationSummaries/exampleQa'))
$Hasher = [System.Security.Cryptography.SHA256]::Create()
$artifactSha = ([BitConverter]::ToString($Hasher.ComputeHash([IO.File]::ReadAllBytes($artifactPath))) -replace '-', '').ToLowerInvariant()
$Hasher.Dispose()
try {
    Assert-DigestCase '无期望时Envelope摘要仅观测（OBSERVE）' -Check {
        $d = Resolve-J3DigestDecision '' '' $false 'envelope-sha-a'
        if ($d.verdict -cne 'OBSERVE') { throw ('verdict=' + $d.verdict) }
        if ($d.checks[0].actual -cne 'envelope-sha-a') { throw '观测值未记录' }
    }
    Assert-DigestCase '给了期望但无独立原件→BLOCKED，不回填现算SHA' -Check {
        $d = Resolve-J3DigestDecision $artifactSha '' $false 'envelope-sha-a'
        if ($d.verdict -cne 'BLOCKED') { throw ('verdict=' + $d.verdict) }
        if ($d.reason -cnotmatch '冻结制品原件') { throw '原因未说明原件前提' }
    }
    Assert-DigestCase '原件路径不存在→BLOCKED' -Check {
        $d = Resolve-J3DigestDecision $artifactSha 'Z:/no/such.pack' $false 'envelope-sha-a'
        if ($d.verdict -cne 'BLOCKED') { throw ('verdict=' + $d.verdict) }
    }
    Assert-DigestCase '原件匹配→PASS，且traceId变化不改变结论（不误判篡改）' -Check {
        $d1 = Resolve-J3DigestDecision $artifactSha $artifactPath $true 'envelope-sha-a'
        $d2 = Resolve-J3DigestDecision $artifactSha $artifactPath $true 'envelope-sha-b-different-traceid'
        if ($d1.verdict -cne 'PASS' -or $d2.verdict -cne 'PASS') { throw ('verdict=' + $d1.verdict + '/' + $d2.verdict) }
    }
    Assert-DigestCase '错对象期望（如BUNDLE摘要）→FAIL' -Check {
        $bundleLikeSha = ('abcd').PadRight(64, '0')
        $d = Resolve-J3DigestDecision $bundleLikeSha $artifactPath $true 'envelope-sha-a'
        if ($d.verdict -cne 'FAIL') { throw ('verdict=' + $d.verdict) }
        if ($d.reason -cnotmatch '对象错配') { throw '原因未说明对象错配' }
    }
    Assert-DigestCase '制品真实内容变化→FAIL' -Check {
        $changedPath = Join-Path ([IO.Path]::GetTempPath()) ('j3-changed-' + [Guid]::NewGuid().ToString('N') + '.pack')
        [IO.File]::WriteAllBytes($changedPath, [Text.Encoding]::UTF8.GetBytes('tampered-content'))
        try {
            $d = Resolve-J3DigestDecision $artifactSha $changedPath $true 'envelope-sha-a'
            if ($d.verdict -cne 'FAIL') { throw ('verdict=' + $d.verdict) }
        } finally { [IO.File]::Delete($changedPath) }
    }
} finally { [IO.File]::Delete($artifactPath) }

Write-Host ('passed={0} failed={1}' -f ($script:Cases - $script:Failures), $script:Failures)
if ($script:Failures -gt 0) { exit 1 }
exit 0
