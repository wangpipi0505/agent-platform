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
    Assert-DigestCase 'joint-j3.ps1脚本入口：缺省空FrozenContextPackPath不抛参数绑定异常，且给ExpectSha256无原件时以退出码3(BLOCKED)退出' -Check {
        $listener = New-Object System.Net.HttpListener
        $port = Get-Random -Minimum 29100 -Maximum 29800
        $prefix = ('http://127.0.0.1:{0}/' -f $port)
        $listener.Prefixes.Add($prefix)
        $listener.Start()
        $oldKey = $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY
        $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = 'ontosa_test_key'
        $releaseId = '11111111-2222-3333-4444-555555555555'
        $payloadBytes = [Text.Encoding]::UTF8.GetBytes('{"code":"OK","data":{"conceptCards":[{}],"termDictionary":[{}],"relationSummaries":[{}],"exampleQa":[{}]}}')
        try {
            # 第1次调用：不传 -FrozenContextPackPath 且不传 -ExpectSha256 → 走 OBSERVE 分支并以 0 (PASS) 退出
            $async1 = $listener.BeginGetContext($null, $null)
            $psi1 = New-Object System.Diagnostics.ProcessStartInfo('powershell.exe', ('-NoProfile -ExecutionPolicy Bypass -File "{0}" -OntologyBase "{1}" -ReleaseId "{2}"' -f $scriptPath, $prefix.TrimEnd('/'), $releaseId))
            $psi1.UseShellExecute = $false
            $proc1 = [System.Diagnostics.Process]::Start($psi1)
            if (-not $async1.AsyncWaitHandle.WaitOne(10000)) { throw 'stub listener timeout (call 1)' }
            $ctx1 = $listener.EndGetContext($async1)
            $ctx1.Response.StatusCode = 200
            $ctx1.Response.ContentType = 'application/json;charset=utf-8'
            $ctx1.Response.OutputStream.Write($payloadBytes, 0, $payloadBytes.Length)
            $ctx1.Response.Close()
            [void]$proc1.WaitForExit(10000)
            if ($proc1.ExitCode -ne 0) { throw ('无FrozenContextPackPath应退出0，实际exit=' + $proc1.ExitCode) }

            # 第2次调用：传 -ExpectSha256 但不传 -FrozenContextPackPath → 必须以退出码 3 ($ExitBlocked) 退出，不得退 1
            $async2 = $listener.BeginGetContext($null, $null)
            $psi2 = New-Object System.Diagnostics.ProcessStartInfo('powershell.exe', ('-NoProfile -ExecutionPolicy Bypass -File "{0}" -OntologyBase "{1}" -ReleaseId "{2}" -ExpectSha256 "{3}"' -f $scriptPath, $prefix.TrimEnd('/'), $releaseId, $artifactSha))
            $psi2.UseShellExecute = $false
            $proc2 = [System.Diagnostics.Process]::Start($psi2)
            if (-not $async2.AsyncWaitHandle.WaitOne(10000)) { throw 'stub listener timeout (call 2)' }
            $ctx2 = $listener.EndGetContext($async2)
            $ctx2.Response.StatusCode = 200
            $ctx2.Response.ContentType = 'application/json;charset=utf-8'
            $ctx2.Response.OutputStream.Write($payloadBytes, 0, $payloadBytes.Length)
            $ctx2.Response.Close()
            [void]$proc2.WaitForExit(10000)
            if ($proc2.ExitCode -ne 3) { throw ('BLOCKED分支应退出3，实际exit=' + $proc2.ExitCode) }
        } finally {
            $env:AGENT_SECRET_ONTO_ENGINE_READ_KEY = $oldKey
            $listener.Stop()
            $listener.Close()
        }
    }
    Assert-DigestCase 'Invoke-JointAcceptance.ps1与joint-all.ps1支持并向J3透传-FrozenContextPackPath' -Check {
        foreach ($entryScript in @('Invoke-JointAcceptance.ps1', 'joint-all.ps1')) {
            $entryPath = Join-Path $PSScriptRoot $entryScript
            $entryErrors = $null
            [void][System.Management.Automation.Language.Parser]::ParseFile($entryPath, [ref]$null, [ref]$entryErrors)
            if ($entryErrors -and $entryErrors.Count) { throw ($entryScript + ' parse error: ' + $entryErrors[0].Message) }
        }
        $planOut = & (Join-Path $PSScriptRoot 'Invoke-JointAcceptance.ps1') -DryRun -Stages @('J3') -ReleaseId '11111111-2222-3333-4444-555555555555' -ReadKey 'ontosa_k' -ExpectSha256 $artifactSha -FrozenContextPackPath $artifactPath
        $planText = ($planOut -join "`n")
        if ($planText -cnotmatch 'FrozenContextPackPath') { throw ('Invoke-JointAcceptance DryRun计划未透传FrozenContextPackPath: ' + $planText) }
    }
} finally { [IO.File]::Delete($artifactPath) }

Write-Host ('passed={0} failed={1}' -f ($script:Cases - $script:Failures), $script:Failures)
if ($script:Failures -gt 0) { exit 1 }
exit 0
