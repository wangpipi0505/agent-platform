param([Parameter(Mandatory=$true)][string]$EvidenceDirectory)
$ErrorActionPreference='Stop'
$repositoryRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$phase='PREPARATION_FAILED'; $passed=$false; $safeError=$null; $private=$null; $child=$null; $modelChild=$null; $modelStopped=$false
$checks=New-Object 'System.Collections.Generic.List[string]'
function Assert-Protocol([bool]$Value,[string]$Name) { if (-not $Value) { throw ('ASSERTION_FAILED:'+ $Name) }; $checks.Add($Name) }
function Assert-ProtocolReject([scriptblock]$Action,[string]$Code,[string]$Name) {
    $message=$null;try { & $Action | Out-Null } catch {$message=$_.Exception.Message}
    Assert-Protocol ($message -eq $Code) $Name
    Assert-Protocol (-not $message -or $message -notmatch 'synthetic-raw-value') ($Name+' does not leak response or credentials')
}
# OpenAI replies are raw JSON, so the OR-envelope helper must not decode this protocol.
function Invoke-ModelProtocol {
    param([string]$BaseUrl,[string]$Path,[string]$Method='GET',$Body,[string]$RawBody,[int]$ExpectedStatus=200,[switch]$Chunked,[string]$StopNonce)
    $request=[Net.HttpWebRequest]::Create($BaseUrl+$Path)
    $request.Method=$Method;$request.AllowAutoRedirect=$false;$request.Timeout=3000;$request.ReadWriteTimeout=3000;$request.Proxy=$null
    if($StopNonce){$request.Headers['X-Fixture-Nonce']=$StopNonce}
    $response=$null
    try {
        if($Method -eq 'POST'){
            $text=if($PSBoundParameters.ContainsKey('RawBody')){$RawBody}else{$Body|ConvertTo-Json -Depth 20 -Compress}
            $bytes=[Text.Encoding]::UTF8.GetBytes($text);$request.ContentType='application/json'
            if($Chunked){$request.SendChunked=$true;$request.AllowWriteStreamBuffering=$false}else{$request.ContentLength=$bytes.Length}
            $stream=$request.GetRequestStream();try{$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
        }
        try{$response=$request.GetResponse()}catch [Net.WebException]{if(-not $_.Exception.Response){throw 'JOINT_MODEL_TRANSPORT_FAILED'};$response=$_.Exception.Response}
        if([int]$response.StatusCode -ne $ExpectedStatus){throw 'JOINT_MODEL_STATUS_UNEXPECTED'}
        $modelInput=$response.GetResponseStream();$memory=New-Object IO.MemoryStream
        try{
            $buffer=New-Object byte[] 4096
            while(($n=$modelInput.Read($buffer,0,$buffer.Length)) -gt 0){if($memory.Length+$n -gt 65536){throw 'JOINT_MODEL_RESPONSE_TOO_LARGE'};$memory.Write($buffer,0,$n)}
            $json=[Text.Encoding]::UTF8.GetString($memory.ToArray())|ConvertFrom-Json -ErrorAction Stop
            return $json
        }finally{$modelInput.Dispose();$memory.Dispose()}
    }finally{if($response){$response.Dispose()};$request.Abort()}
}
function Assert-ModelReject {
    param([string]$Path,[int]$Status,[string]$Code,$Body,[string]$RawBody,[switch]$Chunked)
    $modelRequest=@{BaseUrl=$script:modelBase;Path=$Path;Method='POST';ExpectedStatus=$Status}
    if($PSBoundParameters.ContainsKey('RawBody')){$modelRequest.RawBody=$RawBody}else{$modelRequest.Body=$Body}
    if($Chunked){$modelRequest.Chunked=$true}
    $result=Invoke-ModelProtocol @modelRequest
    Assert-Protocol ($result.error.code -ceq $Code) ('model rejects '+$Code)
    Assert-Protocol (($result|ConvertTo-Json -Depth 10 -Compress) -notmatch 'synthetic-raw-value') ('model '+$Code+' has safe error')
}

try {
    $common=Join-Path $repositoryRoot 'scripts/acceptance/Joint.Common.ps1'
    $fixture=Join-Path $repositoryRoot 'scripts/acceptance/fixtures/Joint-ProtocolFixture.mjs'
    if (-not(Test-Path -LiteralPath $common) -or -not(Test-Path -LiteralPath $fixture)) { throw 'JOINT_PROTOCOL_NOT_DELIVERED' }
    $tokens=$null;$errors=$null;$ast=[Management.Automation.Language.Parser]::ParseFile($common,[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'JOINT_HELPER_PARSE_FAILED'}
    $definitions=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst]},$true))
    foreach($required in @('Invoke-JointHttp','Assert-JointPublicationPrepared','Write-JointPrivateText','Read-JointPrivateEnvironment')){if(@($definitions|Where-Object Name -eq $required).Count -ne 1){throw 'JOINT_PROTOCOL_NOT_DELIVERED'}}
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
    $notReady=Invoke-JointHttp -BaseUrl $base -Path '/protocol/not-ready'
    Assert-ProtocolReject {Assert-JointPublicationPrepared $notReady.Data} 'JOINT_PUBLICATION_NOT_READY' 'HTTP success cannot replace publication READY_TO_COMMIT'
    $unitPrivate=New-JointPrivateDirectory $repositoryRoot (Join-Path $private 'secret-storage')
    Write-JointPrivateText $repositoryRoot $unitPrivate 'unit.env' 'AGENT_SECRET_SYNTHETIC=synthetic-raw-value' | Out-Null
    $unitValues=Read-JointPrivateEnvironment $repositoryRoot $unitPrivate 'unit.env'
    Assert-Protocol ($unitValues.AGENT_SECRET_SYNTHETIC -eq 'synthetic-raw-value') 'private env preserves value without public serialization'
    Write-JointPrivateText $repositoryRoot $unitPrivate 'unit.env' ('AGENT_SECRET_SYNTHETIC=synthetic-raw-value'+[Environment]::NewLine+'AGENT_SECRET_SECOND=synthetic-second-value') | Out-Null
    $unitReplaced=Read-JointPrivateEnvironment $repositoryRoot $unitPrivate 'unit.env'
    Assert-Protocol ($unitReplaced.Count -eq 2 -and $unitReplaced.AGENT_SECRET_SYNTHETIC -eq 'synthetic-raw-value') 'atomic private replacement preserves existing secret entry'
    Assert-ProtocolReject {Write-JointPrivateText $repositoryRoot $unitPrivate '../escape.env' 'synthetic-raw-value'} 'JOINT_PRIVATE_FILENAME_INVALID' 'private writer cannot escape session directory'
    # The original 25 checks above remain intact. Model is a separate explicit fixture role.
    $phase='PREPARATION_FAILED'
    $modelReady=Join-Path $private 'model-ready.json';$modelNonce=[Guid]::NewGuid().ToString('N')
    $modelName='ex06-joint-model';$registeredTool='joint_list'
    $modelChild=Start-Process -FilePath $node -ArgumentList @(('"'+$fixture+'"'),'--ready',('"'+$modelReady+'"'),'--nonce',$modelNonce,'--role','Model','--model',$modelName,'--tools',$registeredTool) -WorkingDirectory $repositoryRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $private 'model-stdout.log') -RedirectStandardError (Join-Path $private 'model-stderr.log')
    $deadline=[DateTime]::UtcNow.AddSeconds(8);$modelRegistration=$null
    while(-not $modelRegistration){
        if($modelChild.HasExited -or [DateTime]::UtcNow -ge $deadline){throw 'JOINT_MODEL_FIXTURE_START_FAILED'}
        if(Test-Path -LiteralPath $modelReady){try{$modelRegistration=[IO.File]::ReadAllText($modelReady)|ConvertFrom-Json -ErrorAction Stop}catch{}}
        if(-not $modelRegistration){Start-Sleep -Milliseconds 50}
    }
    Assert-Protocol ($modelRegistration.role -ceq 'Model' -and $modelRegistration.nonce -ceq $modelNonce -and [int]$modelRegistration.pid -eq $modelChild.Id -and [int]$modelRegistration.port -gt 0 -and $modelRegistration.model -ceq $modelName -and @($modelRegistration.tools).Count -eq 1 -and $modelRegistration.tools[0] -ceq $registeredTool) 'explicit model fixture identity and tool registration'
    $phase='PROTOCOL_ASSERTION_FAILED'
    $script:modelBase='http://127.0.0.1:'+$modelRegistration.port
    $catalog=Invoke-ModelProtocol $modelBase '/v1/models'
    Assert-Protocol ($catalog.object -ceq 'list' -and @($catalog.data).Count -eq 1 -and $catalog.data[0].id -ceq $modelName -and $catalog.data[0].owned_by -ceq 'ex06-test-fixture') 'model catalog advertises only configured fixture model'
    $plain=@{model=$modelName;messages=@(@{role='user';content='ping'});stream=$false}
    $answer=Invoke-ModelProtocol $modelBase '/v1/chat/completions' 'POST' $plain
    Assert-Protocol ($answer.object -ceq 'chat.completion' -and $answer.model -ceq $modelName -and $answer.choices[0].message.content -ceq 'EX06_OK' -and $answer.choices[0].finish_reason -ceq 'stop') 'plain model completion returns deterministic terminal text'
    Assert-Protocol ($answer.usage.prompt_tokens -eq 3 -and $answer.usage.completion_tokens -eq 2 -and $answer.usage.total_tokens -eq 5) 'model completion includes actual protocol usage fields'
    $tool=@{type='function';function=@{name=$registeredTool;description='EX06 registered readonly query';parameters=@{type='object';properties=@{};additionalProperties=$false}}}
    $health=@{type='function';function=@{name='health_probe';description='Health probe';parameters=@{type='object';properties=@{};additionalProperties=$false}}}
    $healthBody=@{model=$modelName;messages=@(@{role='user';content='Call health_probe now. Do not answer with plain text.'});tools=@($health);stream=$false}
    $healthAnswer=Invoke-ModelProtocol $modelBase '/v1/chat/completions' 'POST' $healthBody
    Assert-Protocol ($healthAnswer.choices[0].message.tool_calls[0].function.name -ceq 'health_probe' -and $healthAnswer.choices[0].finish_reason -ceq 'tool_calls') 'exact health probe selects declared health tool'
    $firstBody=@{model=$modelName;messages=@(@{role='user';content=('EX06_TOOL:'+$registeredTool)});tools=@($health,$tool);stream=$false}
    $first=Invoke-ModelProtocol $modelBase '/v1/chat/completions' 'POST' $firstBody
    $call=$first.choices[0].message.tool_calls[0]
    Assert-Protocol (@($first.choices[0].message.tool_calls).Count -eq 1 -and $call.type -ceq 'function' -and $call.function.name -ceq $registeredTool -and $call.function.arguments -ceq '{}' -and $first.choices[0].finish_reason -ceq 'tool_calls') 'registered tool selection does not choose first offered tool'
    $secondBody=@{model=$modelName;messages=@(@{role='user';content=('EX06_TOOL:'+$registeredTool)},@{role='assistant';content=$null;tool_calls=@($call)},@{role='tool';tool_call_id=$call.id;content='{"rows":[{"name":"JointTask"}]}'});tools=@($health,$tool);stream=$false}
    $second=Invoke-ModelProtocol $modelBase '/v1/chat/completions' 'POST' $secondBody
    Assert-Protocol ($second.choices[0].message.content -ceq 'EX06_OK' -and -not $second.choices[0].message.tool_calls -and $second.choices[0].finish_reason -ceq 'stop' -and $second.usage.total_tokens -eq 5) 'second round consumes matching tool reply then terminates'
    $safe=Invoke-ModelProtocol $modelBase '/v1/chat/completions' 'POST' @{model=$modelName;messages=@(@{role='user';content='EX06_SAFETY'});tools=@($tool);stream=$false}
    Assert-Protocol ($safe.choices[0].message.content -ceq 'EX06_REFUSE' -and -not $safe.choices[0].message.tool_calls -and $safe.usage.total_tokens -eq 5) 'explicit safety fixture returns fixed refusal without tool dispatch'
    Assert-ModelReject '/v1/chat/completions' 404 'FIXTURE_MODEL_UNKNOWN' @{model='unknown';messages=@(@{role='user';content='synthetic-raw-value'})}
    $unknownTool=@{type='function';function=@{name='unregistered';parameters=@{type='object';properties=@{}}}}
    Assert-ModelReject '/v1/chat/completions' 400 'FIXTURE_TOOL_UNKNOWN' @{model=$modelName;messages=@(@{role='user';content='ping'});tools=@($unknownTool)}
    Assert-ModelReject '/v1/chat/completions' 400 'FIXTURE_TOOL_NOT_OFFERED' @{model=$modelName;messages=@(@{role='user';content=('EX06_TOOL:'+$registeredTool)});tools=@($health)}
    Assert-ModelReject '/v1/chat/completions' 400 'FIXTURE_TOOL_UNKNOWN' @{model=$modelName;messages=@(@{role='user';content='EX06_TOOL:unregistered'});tools=@($tool)}
    Assert-ModelReject '/v1/chat/completions' 400 'FIXTURE_JSON_INVALID' -RawBody '{synthetic-raw-value'
    Assert-ModelReject '/v1/chat/completions' 400 'FIXTURE_CHAT_INVALID' @{model=$modelName;messages='synthetic-raw-value'}
    Assert-ModelReject '/v1/chat/completions' 400 'FIXTURE_STREAM_UNSUPPORTED' @{model=$modelName;messages=@(@{role='user';content='ping'});stream=$true}
    Assert-ModelReject '/v1/chat/completions' 400 'FIXTURE_TOOL_INVALID' @{model=$modelName;messages=@(@{role='user';content='ping'});tools=@($tool,$tool)}
    $wrongReply=@{model=$modelName;messages=@(@{role='user';content=('EX06_TOOL:'+$registeredTool)},@{role='assistant';content=$null;tool_calls=@($call)},@{role='tool';tool_call_id='wrong';content='{}'});tools=@($tool)}
    Assert-ModelReject '/v1/chat/completions' 400 'FIXTURE_TOOL_HISTORY_INVALID' $wrongReply
    $badArguments=@{id=$call.id;type='function';function=@{name=$registeredTool;arguments='{synthetic-raw-value'}}
    Assert-ModelReject '/v1/chat/completions' 400 'FIXTURE_TOOL_HISTORY_INVALID' @{model=$modelName;messages=@(@{role='user';content=('EX06_TOOL:'+$registeredTool)},@{role='assistant';content=$null;tool_calls=@($badArguments)},@{role='tool';tool_call_id=$call.id;content='{}'});tools=@($tool)}
    Assert-ModelReject '/v1/chat/completions' 413 'FIXTURE_REQUEST_TOO_LARGE' -RawBody ('x'*65537)
    Assert-ModelReject '/v1/chat/completions' 413 'FIXTURE_REQUEST_TOO_LARGE' -RawBody ('x'*65537) -Chunked
    Assert-ModelReject '/admin/evaluations' 404 'FIXTURE_ROUTE_NOT_FOUND' @{}
    $badStop=Invoke-ModelProtocol -BaseUrl $modelBase -Path '/fixture/stop' -Method POST -Body @{} -ExpectedStatus 403 -StopNonce 'wrong'
    Assert-Protocol ($badStop.error.code -ceq 'FIXTURE_STOP_DENIED') 'model stop requires own nonce'
    $stillAlive=Invoke-ModelProtocol $modelBase '/v1/models'
    Assert-Protocol ($stillAlive.data[0].id -ceq $modelName) 'rejected stop leaves configured model available'
    $stopped=Invoke-ModelProtocol -BaseUrl $modelBase -Path '/fixture/stop' -Method POST -Body @{} -StopNonce $modelNonce
    Assert-Protocol ($stopped.stopping -eq $true -and $stopped.role -ceq 'Model') 'authorized model stop acknowledged'
    $modelStopped=$modelChild.WaitForExit(3000)
    Assert-Protocol ($modelStopped -and $modelChild.ExitCode -eq 0) 'model process exits within finite stop budget'

    $passed=$true;$phase='PROTOCOL_CHECKS_PASSED'
}catch{
    $safeError='UNEXPECTED_PROTOCOL_FAILURE'
    if($_.Exception.Message -match '^(JOINT_[A-Z_]+|ASSERTION_FAILED:[A-Za-z0-9 /_-]+)$'){$safeError=$_.Exception.Message}
}finally{
    if($modelChild){if(-not $modelChild.HasExited){$modelChild.Kill()};$modelChild.WaitForExit(3000)|Out-Null}
    if($child){if(-not $child.HasExited){$child.Kill()};$child.WaitForExit(3000)|Out-Null}
    if($private){[IO.File]::WriteAllText((Join-Path $private 'protocol-checks.json'),([ordered]@{phase=$phase;passed=$passed;checks=@($checks);safeError=$safeError;protocolFixtureOnly=$true;businessValidationPerformed=$false;modelFixtureOnly=$true;modelStopped=$modelStopped;modelFixturePid=if($modelChild){$modelChild.Id}else{$null};fixturePid=if($child){$child.Id}else{$null}}|ConvertTo-Json -Depth 5),(New-Object Text.UTF8Encoding($false)))}
}
if(-not $passed){Write-Error ($phase+':'+$safeError) -ErrorAction Continue;if($phase -eq 'PREPARATION_FAILED'){exit 2};exit 1}
Write-Output ('PROTOCOL_CHECKS_PASSED count='+$checks.Count+'; businessValidationPerformed=false');exit 0
