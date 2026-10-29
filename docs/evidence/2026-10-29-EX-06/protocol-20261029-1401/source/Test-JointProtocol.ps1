param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
$ErrorActionPreference='Stop'
$repositoryRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$phase='PREPARATION_FAILED'; $passed=$false; $safeError=$null; $private=$null; $child=$null
$checks=New-Object 'System.Collections.Generic.List[string]'
function Assert-Protocol([bool]$Value,[string]$Name) { if (-not $Value) { throw ('ASSERTION_FAILED:'+ $Name) }; $checks.Add($Name) }
function Assert-ProtocolReject([scriptblock]$Action,[string]$Code,[string]$Name) {
    $message=$null;try { & $Action | Out-Null } catch {$message=$_.Exception.Message}
    Assert-Protocol ($message -eq $Code) $Name
    Assert-Protocol (-not $message -or $message -notmatch 'synthetic-raw-value') ($Name+' does not leak response or credentials')
}
try {
    $common=Join-Path $repositoryRoot 'scripts/acceptance/Joint.Common.ps1'
    $fixture=Join-Path $repositoryRoot 'scripts/acceptance/fixtures/Joint-ProtocolFixture.mjs'
    if (-not(Test-Path -LiteralPath $common) -or -not(Test-Path -LiteralPath $fixture)) { throw 'JOINT_PROTOCOL_NOT_DELIVERED' }
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($common,[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'JOINT_HELPER_PARSE_FAILED'}
    $definitions=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst]},$true))
    if(-not @($definitions|Where-Object Name -eq 'Invoke-JointHttp').Count){throw 'JOINT_HTTP_NOT_DELIVERED'}
    foreach($definition in $definitions){. ([scriptblock]::Create($definition.Extent.Text))}
    $target=Assert-JointAcceptancePath $repositoryRoot $EvidenceDirectory
    if(Test-Path -LiteralPath $target){throw 'JOINT_TEST_OUTPUT_EXISTS'}
    $private=New-JointPrivateDirectory $repositoryRoot $target
    $ready=Join-Path $private 'fixture-ready.json';$nonce=[Guid]::NewGuid().ToString('N')
    $node=(Get-Command node.exe -ErrorAction Stop).Source
    $child=Start-Process -FilePath $node -ArgumentList @(('"'+$fixture+'"'),'--ready',('"'+$ready+'"'),'--nonce',$nonce) -WorkingDirectory $repositoryRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $private 'fixture-stdout.log') -RedirectStandardError (Join-Path $private 'fixture-stderr.log')
    $deadline=[DateTime]::UtcNow.AddSeconds(8)
    $registration=$null
    while(-not $registration){
        if($child.HasExited -or [DateTime]::UtcNow -ge $deadline){throw 'JOINT_FIXTURE_START_FAILED'}
        if(Test-Path -LiteralPath $ready){try{$registration=[IO.File]::ReadAllText($ready)|ConvertFrom-Json -ErrorAction Stop}catch{}}
        if(-not $registration){Start-Sleep -Milliseconds 50}
    }
    if($registration.nonce -cne $nonce -or [int]$registration.pid -ne $child.Id -or [int]$registration.port -le 0){throw 'JOINT_FIXTURE_IDENTITY_INVALID'}
    $base='http://127.0.0.1:'+ $registration.port
    $phase='PROTOCOL_ASSERTION_FAILED'
    $ok=Invoke-JointHttp -BaseUrl $base -Path '/protocol/ok'
    Assert-Protocol ($ok.Status -eq 200 -and $ok.Data.probe -eq 'protocol-only') 'actual HTTP envelope decoded'
    $denied=Invoke-JointHttp -BaseUrl $base -Path '/protocol/denied' -ExpectedStatus 403 -ApiKey 'synthetic-raw-value'
    Assert-Protocol ($denied.Status -eq 403 -and $denied.Code -eq 'NO_PERMISSION') 'expected HTTP 403 preserves code'
    Assert-ProtocolReject {Invoke-JointHttp -BaseUrl $base -Path '/protocol/denied'} 'JOINT_HTTP_UNEXPECTED_STATUS' 'unexpected status rejects without raw response'
    Assert-ProtocolReject {Invoke-JointHttp -BaseUrl $base -Path '/protocol/malformed'} 'JOINT_HTTP_ENVELOPE_INVALID' 'malformed success JSON rejected'
    Assert-ProtocolReject {Invoke-JointHttp -BaseUrl $base -Path '/protocol/error-envelope'} 'JOINT_HTTP_ERROR_ENVELOPE' 'HTTP 200 business error is not success'
    Assert-ProtocolReject {Invoke-JointHttp -BaseUrl $base -Path '/protocol/redirect'} 'JOINT_HTTP_UNEXPECTED_STATUS' 'redirect is not followed with credential'
    $state=Invoke-JointHttp -BaseUrl $base -Path '/protocol/state'
    Assert-Protocol ($state.Data.redirectTargets -eq 0) 'redirect target never called'
    Assert-ProtocolReject {Invoke-JointHttp -BaseUrl $base -Path '/protocol/oversized' -MaxResponseBytes 1024} 'JOINT_HTTP_BODY_TOO_LARGE' 'response bytes are bounded'
    Assert-ProtocolReject {Invoke-JointHttp -BaseUrl $base -Path '/protocol/delay' -TimeoutSeconds 1} 'JOINT_HTTP_TRANSPORT_FAILED' 'HTTP timeout is finite and safe'
    $echo=Invoke-JointHttp -BaseUrl $base -Path '/protocol/echo' -Method POST -Body @{text='联合协议测试'}
    Assert-Protocol ($echo.Data.text -eq '联合协议测试') 'UTF8 JSON survives actual HTTP'
    $auth=Invoke-JointHttp -BaseUrl $base -Path '/protocol/auth' -ApiKey 'synthetic-raw-value'
    Assert-Protocol ($auth.Data.hasApiKey -and -not $auth.Data.hasAuthorization) 'machine request does not receive human bearer'
    Assert-ProtocolReject {Invoke-JointHttp -BaseUrl $base -Path '/protocol/auth' -ApiKey 'synthetic-raw-value' -BearerToken 'synthetic-raw-value'} 'JOINT_HTTP_AUTH_AMBIGUOUS' 'mixed authentication rejected before request'
    $passed=$true;$phase='PROTOCOL_CHECKS_PASSED'
}catch{
    $safeError='UNEXPECTED_PROTOCOL_FAILURE'
    if($_.Exception.Message -match '^(JOINT_[A-Z_]+|ASSERTION_FAILED:[A-Za-z0-9 /_-]+)$'){$safeError=$_.Exception.Message}
}finally{
    if($child){if(-not $child.HasExited){$child.Kill()};$child.WaitForExit(3000)|Out-Null}
    if($private){[IO.File]::WriteAllText((Join-Path $private 'protocol-checks.json'),([ordered]@{phase=$phase;passed=$passed;checks=@($checks);safeError=$safeError;protocolFixtureOnly=$true;businessValidationPerformed=$false;fixturePid=if($child){$child.Id}else{$null}}|ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($false)))}
}
if(-not $passed){Write-Error ($phase+':'+$safeError) -ErrorAction Continue;if($phase -eq 'PREPARATION_FAILED'){exit 2};exit 1}
Write-Output ('PROTOCOL_CHECKS_PASSED count='+$checks.Count+'; businessValidationPerformed=false');exit 0
