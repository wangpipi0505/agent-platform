$ErrorActionPreference='Stop'
$taskRoot='G:/207/项目/算法重组工具/代码/agent-platform'
$taskRun=$PSScriptRoot
$taskPhase='ADMISSION';$taskReason='OWNED_ALGORITHM_DATABASE_ADMISSION_MISMATCH'
$taskPrevious=@{};$taskExit=2
function Write-TaskJson([string]$Name,$Value){[IO.File]::WriteAllText((Join-Path $taskRun $Name),(ConvertTo-Json -InputObject $Value -Depth 14),(New-Object Text.UTF8Encoding($false)))}
try{
 $admission=[IO.File]::ReadAllText((Join-Path $taskRun 'admission.json'))|ConvertFrom-Json
 if(-not $admission.passed -or $admission.pid -ne 15204 -or $admission.database -cne 'agent_platform_skill_algorithm_test' -or $admission.databaseOwner -cne 'agent_v12' -or $admission.schemaOwner -cne 'agent_v12' -or -not $admission.exactCatalogIdentity -or -not $admission.exactClassifiedHistory -or ([DateTimeOffset]::Now-[DateTimeOffset]::Parse($admission.checkedAt)).TotalMinutes -gt 15){throw 'OWNED_ALGORITHM_DATABASE_ADMISSION_MISMATCH'}
 if(Test-Path -LiteralPath (Join-Path $taskRun 'history-facts.json')){throw 'HISTORICAL_READ_ALREADY_EXECUTED'}
 if((Get-FileHash -LiteralPath (Join-Path $taskRoot '.env') -Algorithm SHA256).Hash -cne 'AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A'){throw 'CONFIG_FINGERPRINT_MISMATCH'}
 $taskPhase='AP_ENVIRONMENT';$taskReason='AP_READONLY_CREDENTIAL_PREREQUISITE_MISMATCH'
 $values=@{}
 foreach($line in [IO.File]::ReadAllLines((Join-Path $taskRoot '.env'),[Text.Encoding]::UTF8)){if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
 foreach($name in (@($values.Keys)+@('PGPASSWORD','PGCONNECT_TIMEOUT','PGOPTIONS')|Select-Object -Unique)){$taskPrevious[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
 foreach($name in $values.Keys){[Environment]::SetEnvironmentVariable($name,$values[$name],'Process')}
 if(-not $env:AGENT_DB_PASSWORD){throw 'AP_READONLY_CREDENTIAL_PREREQUISITE_MISMATCH'}
 $env:PGPASSWORD=$env:AGENT_DB_PASSWORD;$env:PGCONNECT_TIMEOUT='5'
 $env:PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=1000 -c timezone=Asia/Shanghai'
 $taskPhase='BOUNDED_ARTIFACT_HISTORY_READ';$taskReason='HISTORICAL_READ_OR_JSON_COLLECTION_FAILED'
 $started=[DateTimeOffset]::Now
 $output=& 'V:/.tools/postgresql-16.15/pgsql/bin/psql.exe' -X -w -h 127.0.0.1 -p 25432 -U agent_v12 -d agent_platform_skill_algorithm_test -qAt -v ON_ERROR_STOP=1 -f (Join-Path $taskRun 'history.sql') 2>> (Join-Path $taskRun 'history.raw.log')
 $native=$LASTEXITCODE
 Write-TaskJson 'read-native-exit.json' ([ordered]@{nativeExit=$native;startedAt=$started.ToString('o');endedAt=[DateTimeOffset]::Now.ToString('o');readOnly=$true;statementTimeoutMs=5000;lockTimeoutMs=1000;maxBusinessRows=13;singleBusinessStatement=$true;sqlSha256=(Get-FileHash -LiteralPath (Join-Path $taskRun 'history.sql') -Algorithm SHA256).Hash})
 if($native -ne 0){throw 'HISTORICAL_READ_OR_JSON_COLLECTION_FAILED'}
 $facts=($output -join "`n")|ConvertFrom-Json
 Write-TaskJson 'history-facts.json' $facts
 $taskExit=0
 Write-Output ('EX27_HISTORY_READ_FINISHED rowsCapped='+$facts.matchedRowsCapped+' groups='+$facts.groupCount+' possibleTruncation='+$facts.possibleTruncation)
}catch{
 Write-TaskJson 'history-error.json' ([ordered]@{phase=$taskPhase;reason=$taskReason;scriptLineNumber=$_.InvocationInfo.ScriptLineNumber;exceptionClass=$_.Exception.GetType().FullName;messagePrinted=$false;javaMavenHttp=$false})
 Write-Output ('EX27_HISTORY_READ_STOP phase='+$taskPhase+' reason='+$taskReason+' line='+$_.InvocationInfo.ScriptLineNumber)
}finally{foreach($name in $taskPrevious.Keys){[Environment]::SetEnvironmentVariable($name,$taskPrevious[$name],'Process')}}
exit $taskExit
