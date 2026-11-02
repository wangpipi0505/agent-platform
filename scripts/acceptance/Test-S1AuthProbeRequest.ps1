# R05（JOINT-004）请求构造测试：S1-D认证负例必须以其余字段合法的基准仅改NONE+authRef，
# 且不再发送与现契约不符的httpMapping（path/query数组、body字符串）。真实执行脚本内构造函数。
# 用法：powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/acceptance/Test-S1AuthProbeRequest.ps1
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path $PSScriptRoot 'Invoke-S1Acceptance.ps1'
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$null, [ref]$parseErrors)
if ($parseErrors -and $parseErrors.Count) { throw ('S1_SCRIPT_PARSE_FAILED: ' + $parseErrors[0].Message) }
$function = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'New-S1DConflictBody' }, $true)
if (-not $function) { throw 'S1_CONFLICT_BODY_FUNCTION_MISSING' }
Invoke-Expression $function.Extent.Text

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

Write-Host ('passed={0} failed={1}' -f ($script:Cases - $script:Failures), $script:Failures)
if ($script:Failures -gt 0) { exit 1 }
exit 0
