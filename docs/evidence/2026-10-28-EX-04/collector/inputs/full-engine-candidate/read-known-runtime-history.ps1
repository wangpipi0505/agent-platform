$ErrorActionPreference='Stop'
$taskRoot='G:/207/项目/算法重组工具/代码/agent-platform'
$old=@{};foreach($n in @('PGPASSWORD','PGCONNECT_TIMEOUT','PGOPTIONS')){$old[$n]=[Environment]::GetEnvironmentVariable($n,'Process')}
try{
 $values=@{};foreach($line in [IO.File]::ReadAllLines((Join-Path $taskRoot '.env'),[Text.Encoding]::UTF8)){if($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$'){$values[$Matches[1]]=$Matches[2]}}
 $env:PGPASSWORD=$values['AGENT_DB_PASSWORD'];$env:PGCONNECT_TIMEOUT='5';$env:PGOPTIONS='-c statement_timeout=5000 -c default_transaction_read_only=on'
 $out=& 'V:/.tools/postgresql-16.15/pgsql/bin/psql.exe' -X -w -h 127.0.0.1 -p 25432 -U agent_v12 -d agent_platform_engine_test -At -v ON_ERROR_STOP=1 -c "select json_agg(json_build_object('database',current_database(),'version',version,'type',type,'script',script,'checksum',checksum,'installedBy',installed_by,'success',success) order by installed_rank) from engine.flyway_schema_history where version in ('200','201','222')" 2>> (Join-Path $PSScriptRoot 'pg-preflight.raw.log')
 if($LASTEXITCODE -ne 0){throw 'READ_FAILED'}
 $data=($out -join "`n")|ConvertFrom-Json
 [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'legacy-runtime-history.json'),(ConvertTo-Json -InputObject $data -Depth 6),(New-Object Text.UTF8Encoding($false)))
 Write-Output 'KNOWN_HISTORY_READ_COMPLETED'
}finally{foreach($n in $old.Keys){[Environment]::SetEnvironmentVariable($n,$old[$n],'Process')}}
