# R05（JOINT-004）请求构造测试：S1-D认证负例必须以其余字段合法的基准仅改NONE+authRef，
# 且不再发送与现契约不符的httpMapping（path/query数组、body字符串）。真实执行脚本内构造函数。
# 用法：powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/acceptance/Test-S1AuthProbeRequest.ps1
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'Invoke-S1Acceptance.ps1'
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$null, [ref]$parseErrors)
if ($parseErrors -and $parseErrors.Count) { throw ('S1_SCRIPT_PARSE_FAILED: ' + $parseErrors[0].Message) }
foreach ($name in @('Get-S1Text', 'Get-S1TextProperty', 'New-S1DConflictBody', 'Format-S1DLegEvidence', 'Invoke-S1DProbe')) {
    $wanted = $name
    $function = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $wanted }, $true)
    if (-not $function) { throw ('S1_FUNCTION_MISSING: ' + $wanted) }
    Invoke-Expression $function.Extent.Text
}

$script:Cases = 0
$script:Failures = 0
function Assert-S1Case([string]$Name, [scriptblock]$Check) {
    $script:Cases++
    try { & $Check; Write-Host ('[PASS] ' + $Name) }
    catch { $script:Failures++; Write-Host ('[FAIL] ' + $Name + ': ' + $_.Exception.Message) }
}

$manifest = [pscustomobject]@{
    ontologyBaseUrl = 'http://127.0.0.1:18182'; spaceCode = 's1_probe'
    releaseId = '9f8e7d6c-1111-2222-3333-444455556666'; opCode = 's1_op'
    consumerKeyRef = 'env:AGENT_SECRET_S1_CONSUMER'
}
$body = New-S1DConflictBody $manifest 's1_conflict_probe'

Assert-S1Case '认证负例仅NONE+authRef其余字段合法' -Check {
    if ($body.authScheme -cne 'NONE') { throw 'authScheme必须为NONE' }
    if ($body.authRef -cne 'env:AGENT_SECRET_S1_CONSUMER') { throw 'authRef必须取consumerKeyRef' }
    foreach ($field in @('code', 'name', 'type', 'endpoint', 'httpMethod', 'execution')) {
        if (-not $body.ContainsKey($field)) { throw ('缺少基准字段: ' + $field) }
    }
    if ($body.type -cne 'HTTP' -or $body.httpMethod -cne 'POST') { throw '基准类型/方法不符' }
    if ($body.execution.kind -cne 'SYNC' -or -not $body.execution.readonly) { throw '基准执行声明不符' }
}
Assert-S1Case '不再发送httpMapping对象' -Check {
    if ($body.ContainsKey('httpMapping')) { throw '认证负例不得携带httpMapping' }
}
Assert-S1Case 'endpoint由真实manifest资源拼出' -Check {
    $expected = 'http://127.0.0.1:18182/onto/r/s1_probe/releases/9f8e7d6c-1111-2222-3333-444455556666/ops/s1_op'
    if ($body.endpoint -cne $expected) { throw ('endpoint不符: ' + $body.endpoint) }
}
Assert-S1Case 'S1-D第1腿失败时保留第1腿HTTP证据并将后续两腿标为NOT_EXECUTED' -Check {
    $probe = Invoke-S1DProbe $manifest 'http://127.0.0.1:18080' @{ Authorization = 'Bearer t' } 'ckey' 's1_conflict_probe' {
        param($m, $u, $h, $b)
        return [pscustomobject]@{ Status = 200; Body = [pscustomobject]@{ code = 'UNEXPECTED_OK'; message = 'should be 400'; traceId = 'tr-leg1' } }
    }
    if ($probe.Passed) { throw '第1腿返回200必须判失败' }
    if ($probe.Evidence.conflict.status -ne 200 -or $probe.Evidence.conflict.code -cne 'UNEXPECTED_OK' -or $probe.Evidence.conflict.traceId -cne 'tr-leg1') {
        throw '第1腿HTTP证据未保留'
    }
    if ($probe.Evidence.direct.status -cne 'NOT_EXECUTED' -or $probe.Evidence.readFace.status -cne 'NOT_EXECUTED') {
        throw '未执行的后续腿必须标注NOT_EXECUTED'
    }
    if ($probe.Evidence.error -cnotmatch 'S1_D_SCHEME_CONFLICT_NOT_ENFORCED') { throw 'error字段未记录失败原因' }
}
Assert-S1Case 'S1-D第2腿失败时保留前两腿HTTP证据并将第3腿标为NOT_EXECUTED' -Check {
    $callCount = 0
    $probe = Invoke-S1DProbe $manifest 'http://127.0.0.1:18080' @{ Authorization = 'Bearer t' } 'ckey' 's1_conflict_probe' {
        param($m, $u, $h, $b)
        $script:leg2Count++
        if ($u -like '*/admin/skills') {
            return [pscustomobject]@{ Status = 400; Body = [pscustomobject]@{ code = 'SKILL_AUTH_SCHEME_CONFLICT'; message = 'conflict'; traceId = 'tr-1' } }
        }
        return [pscustomobject]@{ Status = 403; Body = [pscustomobject]@{ code = 'GUARD_DENIED'; message = 'denied'; traceId = 'tr-2' } }
    }
    if ($probe.Passed) { throw '第2腿返回403必须判失败' }
    if ($probe.Evidence.conflict.status -ne 400 -or $probe.Evidence.conflict.code -cne 'SKILL_AUTH_SCHEME_CONFLICT') {
        throw '第1腿HTTP证据丢失'
    }
    if ($probe.Evidence.direct.status -ne 403 -or $probe.Evidence.direct.code -cne 'GUARD_DENIED' -or $probe.Evidence.direct.traceId -cne 'tr-2') {
        throw '第2腿HTTP证据未保留'
    }
    if ($probe.Evidence.readFace.status -cne 'NOT_EXECUTED') {
        throw '第3腿未执行必须标注NOT_EXECUTED'
    }
}
Assert-S1Case 'S1-D三腿全通过时完整保留三腿脱敏HTTP证据' -Check {
    $probe = Invoke-S1DProbe $manifest 'http://127.0.0.1:18080' @{ Authorization = 'Bearer t' } 'ckey' 's1_conflict_probe' {
        param($m, $u, $h, $b)
        if ($u -like '*/admin/skills') {
            return [pscustomobject]@{ Status = 400; Body = [pscustomobject]@{ code = 'SKILL_AUTH_SCHEME_CONFLICT'; message = 'conflict'; traceId = 'tr-1' } }
        }
        if ($u -like '*/onto/r/*') {
            return [pscustomobject]@{ Status = 200; Body = [pscustomobject]@{ code = 'OK'; message = 'ok'; traceId = 'tr-2' } }
        }
        return [pscustomobject]@{ Status = 401; Body = [pscustomobject]@{ code = 'UNAUTHORIZED'; message = 'no read'; traceId = 'tr-3' } }
    }
    if (-not $probe.Passed) { throw ('三腿应通过: ' + $probe.Reason) }
    if ($probe.Evidence.conflict.status -ne 400 -or $probe.Evidence.direct.status -ne 200 -or $probe.Evidence.readFace.status -ne 401) {
        throw '三腿状态码不完整'
    }
}

Write-Host ('passed={0} failed={1}' -f ($script:Cases - $script:Failures), $script:Failures)
if ($script:Failures -gt 0) { exit 1 }
exit 0
