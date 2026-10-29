$ErrorActionPreference='Stop'
$ap='G:\207\项目\算法重组工具\代码\agent-platform';$or='G:\207\项目\算法重组工具\代码\ontorepo'
. (Join-Path $ap 'scripts/acceptance/Joint.Common.ps1')
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff';$control=New-JointPrivateDirectory $ap (Join-Path $ap ('.local/acceptance/ex06-or-control-'+$stamp))
$output=Join-Path $ap '.local/acceptance/ex06-or-20261029';$session='joint-or-20261029-01';$owned=$null;$native=$null;$stage='admission';$endError=$null
$expected=@{'scripts/acceptance/Prepare-Joint.ps1'='87709F9FE5CC0C7BB387ED3A09433C713B7CE238F6B78E2BD64AE7C8B17C52EC';'scripts/acceptance/Joint.Common.ps1'='4CC93F7A3563809D8F4971079B13E440026AE035CBC2068A5D9AC013B533F342'}
function Save($name,$data){[IO.File]::WriteAllText((Join-Path $control $name),(ConvertTo-Json -InputObject $data -Depth 10),(New-Object Text.UTF8Encoding($false)))}
function Match-Process($record){$process=Get-CimInstance Win32_Process -Filter ('ProcessId='+$record.pid) -ErrorAction SilentlyContinue;if(-not $process){return $null};if($process.CreationDate.ToString('o') -cne $record.creation -or $process.Name -cne 'java.exe' -or -not $process.CommandLine.Contains($record.jar)){throw 'EX06_OWN_PROCESS_CHANGED'};return $process}
try{
 foreach($path in $expected.Keys){if((Get-FileHash -LiteralPath (Join-Path $ap $path)).Hash -cne $expected[$path]){throw 'EX06_SOURCE_DRIFT'}}
 if(-not(Test-Path -LiteralPath (Join-Path $output $session))){throw 'EX06_SESSION_MISSING'}
 $priorState=Join-Path (Join-Path $output $session) 'partial.json'
 Copy-Item -LiteralPath $priorState -Destination (Join-Path $control 'before-operation-partial.json')
 $credentialHashBefore=(Get-FileHash -LiteralPath (Join-Path (Join-Path $output $session) 'credentials.env')).Hash
 Save 'before-credentials-sha.json' @{sha256=$credentialHashBefore}
 if(Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue){throw 'EX06_GATEWAY_ALREADY_OWNED'}
 $listeners=@(Get-NetTCPConnection -LocalPort 18182 -State Listen -ErrorAction Stop);$orProcess=Get-CimInstance Win32_Process -Filter ('ProcessId='+$listeners[0].OwningProcess)
 if($listeners.Count -ne 1 -or $orProcess.Name -ne 'java.exe' -or -not $orProcess.CommandLine.Contains((Join-Path $or 'onto-designer-api'))){throw 'EX06_OR_PROCESS_NOT_THIS_REPOSITORY'}
 $jar=Join-Path $ap 'platform-gateway/target/platform-gateway-0.1.0-SNAPSHOT.jar'
 Save 'admission.json' @{head=(((& git -C $ap rev-parse HEAD)-join '').Trim());sources=$expected;gatewayJarSha=(Get-FileHash -LiteralPath $jar).Hash;envSha=(Get-FileHash -LiteralPath (Join-Path $ap '.env')).Hash;orPid=$orProcess.ProcessId;orCreation=$orProcess.CreationDate.ToString('o');session=$session;output=$output}
 foreach($kind in @('out','err')){$log=Join-Path $ap ('.local/logs/platform-gateway.'+$kind+'.log');if(Test-Path -LiteralPath $log){Copy-Item -LiteralPath $log -Destination (Join-Path $control ('before-gateway.'+$kind+'.log'))}}
 $file=Join-Path $control 'gateway.env';[IO.File]::WriteAllLines($file,@('AGENT_SECURITY_MODE=dev-hmac','AGENT_BIND_ADDRESS=127.0.0.1','AGENT_DEV_AUTH=true','AGENT_DEMO_MODE=false','AGENT_PROCESS_ENABLED=false','SERVER_PORT=18080'),(New-Object Text.UTF8Encoding($true)))
 $started=[DateTime]::Now;$stage='gateway-start'
 & (Join-Path $ap 'scripts/start-backend.ps1') -Services @('platform-gateway') -SkipBuild -ServiceEnvironmentFiles @{'platform-gateway'=$file} *> (Join-Path $control 'gateway-start.log')
 $manifest=Get-Content -LiteralPath (Join-Path $ap '.local/services.json') -Raw -Encoding UTF8|ConvertFrom-Json;$entries=@($manifest|Where-Object service -eq 'platform-gateway');if($entries.Count -ne 1){throw 'EX06_GATEWAY_REGISTRATION'}
 $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+$entries[0].pid);if(-not $process -or $process.CreationDate -lt $started -or -not $process.CommandLine.Contains($jar)){throw 'EX06_GATEWAY_IDENTITY'}
 $owned=[pscustomobject]@{pid=$process.ProcessId;creation=$process.CreationDate.ToString('o');jar=$jar};Save 'owned-gateway.json' $owned
 $deadline=[DateTime]::UtcNow.AddSeconds(90);$healthy=$false
 while([DateTime]::UtcNow -lt $deadline){if(-not(Match-Process $owned)){throw 'EX06_GATEWAY_EXITED'};try{$response=Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:18080/actuator/health' -TimeoutSec 3;if($response.StatusCode -eq 200){$healthy=$true;break}}catch{};Start-Sleep -Milliseconds 500}
 if(-not $healthy){throw 'EX06_GATEWAY_NOT_READY'}
 $stage='ontology';$config=Get-Content -LiteralPath (Join-Path $or '.runtime/local-env.json') -Raw -Encoding UTF8|ConvertFrom-Json
 if(-not $config.ONTO_BOOTSTRAP_USERNAME -or -not $config.ONTO_BOOTSTRAP_PASSWORD){throw 'EX06_OR_CREDENTIAL_MISSING'}
 $secret=ConvertTo-SecureString $config.ONTO_BOOTSTRAP_PASSWORD -AsPlainText -Force;$credential=New-Object Management.Automation.PSCredential($config.ONTO_BOOTSTRAP_USERNAME,$secret);$config=$null
 & (Join-Path $ap 'scripts/acceptance/Prepare-Joint.ps1') -SessionId $session -OntologyBaseUrl 'http://127.0.0.1:18182' -PlatformBaseUrl 'http://127.0.0.1:18080' -PlatformTenant 'local-v12' -Environment 'production' -ModelMode TestFixture -OutputDirectory $output -Stage Ontology -Resume -OntologyCredential $credential *> (Join-Path $control 'ontology-private.log')
 $native=$LASTEXITCODE;$credential=$null;$secret=$null
 $stage='finished'
}catch{$endError='EX06_STAGE_FAILED';if($_.Exception.Message -cmatch '^EX06_[A-Z_]+$'){$endError=$_.Exception.Message};[IO.File]::WriteAllText((Join-Path $control 'failure-private.log'),($_|Out-String),(New-Object Text.UTF8Encoding($false)))}
finally{
 if(-not $owned -and $started -and (Test-Path -LiteralPath (Join-Path $control 'gateway-start.log'))){
  $lines=[IO.File]::ReadAllText((Join-Path $control 'gateway-start.log'));$match=[regex]::Matches($lines,'platform-gateway 已启动，PID=([0-9]+)，端口=18080')
  if($match.Count -eq 1){$partial=Get-CimInstance Win32_Process -Filter ('ProcessId='+$match[0].Groups[1].Value) -ErrorAction SilentlyContinue;if($partial -and $partial.Name -eq 'java.exe' -and $partial.CreationDate -ge $started -and $partial.CommandLine.Contains($jar)){$owned=[pscustomobject]@{pid=$partial.ProcessId;creation=$partial.CreationDate.ToString('o');jar=$jar};Save 'partial-owned-gateway.json' $owned}}
 }
 if($owned){try{if(Match-Process $owned){$manifest=Get-Content -LiteralPath (Join-Path $ap '.local/services.json') -Raw -Encoding UTF8|ConvertFrom-Json;$entry=@($manifest|Where-Object service -eq 'platform-gateway');if($entry.Count -eq 1 -and [int]$entry[0].pid -eq $owned.pid){& (Join-Path $ap 'scripts/stop-backend.ps1') -Services @('platform-gateway') *> (Join-Path $control 'gateway-stop.log')}else{Stop-Process -Id $owned.pid;Save 'direct-owned-stop.json' @{pid=$owned.pid;creation=$owned.creation;reason='OWN_START_NOT_REGISTERED'}};Wait-Process -Id $owned.pid -Timeout 15 -ErrorAction SilentlyContinue};if(Match-Process $owned){throw 'EX06_OWN_PROCESS_STILL_ALIVE'}}catch{$endError='EX06_STOP_FAILED'}}
 foreach($kind in @('out','err')){$log=Join-Path $ap ('.local/logs/platform-gateway.'+$kind+'.log');if($owned -and (Test-Path -LiteralPath $log)){Copy-Item -LiteralPath $log -Destination (Join-Path $control ('after-gateway.'+$kind+'.log'))}}
 $stable=$true;foreach($path in $expected.Keys){if((Get-FileHash -LiteralPath (Join-Path $ap $path)).Hash -cne $expected[$path]){$stable=$false}}
 Save 'run.json' @{stage=$stage;nativeExit=$native;failure=$endError;sourcesStable=$stable;control=$control;session=$session;output=$output;gateway=$owned;wholeCardComplete=$false;endedAtUtc=[DateTime]::UtcNow.ToString('o')}
 if($stage -eq 'finished' -and $native -eq 0){
  $before=[IO.File]::ReadAllText((Join-Path $control 'before-operation-partial.json'))|ConvertFrom-Json
  $after=[IO.File]::ReadAllText($priorState)|ConvertFrom-Json
  $fields=[ordered]@{}
  foreach($field in @('actions','resources','context')){$fields[$field]=((ConvertTo-Json -InputObject $before.$field -Depth 20 -Compress) -ceq (ConvertTo-Json -InputObject $after.$field -Depth 20 -Compress))}
  $sameSecret=$credentialHashBefore -ceq (Get-FileHash -LiteralPath (Join-Path (Join-Path $output $session) 'credentials.env')).Hash
  $ids=@($after.operationIds.PSObject.Properties)
  $idShape=$ids.Count -eq 2 -and @($ids|Where-Object{$_.Name -cnotin @('A','B') -or $_.Value -cnotmatch '^[0-9a-f-]{36}$'}).Count -eq 0
  $equal=$fields.actions -and $fields.resources -and $fields.context -and $sameSecret -and @($after.actions.PSObject.Properties).Count -eq 49 -and $after.opCode -ceq 'joint_list' -and $after.complete -eq $false -and $null -eq $after.pending -and $idShape
  Save 'operation-resume-comparison.json' @{passed=$equal;fields=$fields;actions=@($after.actions.PSObject.Properties).Count;credentialFileSha256=$credentialHashBefore;credentialBytesUnchanged=$sameSecret;opCode=$after.opCode;operationIds=$after.operationIds;complete=$after.complete;pending=$after.pending;wholeCardComplete=$false}
  if(-not $equal){$endError='EX06_OPERATION_RESUME_CHANGED'}
 }
 Write-Output ('EX06_STAGE_RESULT control='+$control+' stage='+$stage+' native='+$native+' failure='+$endError+' sourcesStable='+$stable)
}
if($endError -or -not $stable){exit 2};if($null -eq $native){exit 2};exit $native