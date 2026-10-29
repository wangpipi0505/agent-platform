$ErrorActionPreference = 'Stop'
$taskRoot = 'G:/207/项目/算法重组工具/代码/agent-platform'
$taskRun = $PSScriptRoot
$taskPhase = 'BOOT'
$taskReason = 'UNEXPECTED_COLLECTOR_ERROR'
$taskPrevious = @{}
$taskLocation = Get-Location
$taskPassed = $false
$taskPg = 'V:/.tools/postgresql-16.15/pgsql/bin/psql.exe'
$taskDatabase = 'agent_platform_skill_algorithm_test'
$taskExpectedHead = 'ae2d1860071c9e49d07e80ecace331e41f898c9d'
$taskExpectedEnv = 'AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A'
$taskFrozen = [ordered]@{
    'skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java' = '155DD67F8F256DB2227DDA4430B1FE1D99C3CB7127F1AE0B54E02D0ADB96AEB1'
    'skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java' = '9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D'
}
function Write-TaskJson([string]$Name, $Value) {
    [IO.File]::WriteAllText((Join-Path $taskRun $Name), (ConvertTo-Json -InputObject $Value -Depth 12), (New-Object Text.UTF8Encoding($false)))
}
function Read-TaskPg([string]$Database, [string]$Sql) {
    $output = & $taskPg -X -w -h 127.0.0.1 -p 25432 -U agent_v12 -d $Database -At -v ON_ERROR_STOP=1 -c $Sql 2>> (Join-Path $taskRun 'pg-preflight.raw.log')
    if ($LASTEXITCODE -ne 0) { throw 'PG_READ_FAILED' }
    (($output -join "`n") | ConvertFrom-Json)
}
function Fixed-TaskValue($Value, [string[]]$Allowed) {
    if ($null -eq $Value) { return 'NULL' }
    if ($Allowed -ccontains [string]$Value) { return [string]$Value }
    'OTHER'
}
try {
    Set-Location -LiteralPath $taskRoot
    $taskPhase = 'SOURCE_ADMISSION'; $taskReason = 'FROZEN_HEAD_ENV_SOURCE_MISMATCH'
    if (((& git rev-parse HEAD) -join '') -cne $taskExpectedHead) { throw 'FROZEN_HEAD_ENV_SOURCE_MISMATCH' }
    if ((Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash -cne $taskExpectedEnv) { throw 'FROZEN_HEAD_ENV_SOURCE_MISMATCH' }
    foreach ($path in $taskFrozen.Keys) {
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -cne $taskFrozen[$path]) { throw 'FROZEN_HEAD_ENV_SOURCE_MISMATCH' }
    }
    $taskPhase = 'ENVIRONMENT_LOAD'; $taskReason = 'AP_ENVIRONMENT_PREREQUISITE_MISMATCH'
    $taskValues = @{}
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $taskRoot '.env'), [Text.Encoding]::UTF8)) {
        if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $taskValues[$Matches[1]] = $Matches[2] }
    }
    foreach ($name in (@($taskValues.Keys) + @('PGPASSWORD','PGCONNECT_TIMEOUT','PGOPTIONS') | Select-Object -Unique)) { $taskPrevious[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
    foreach ($name in $taskValues.Keys) { [Environment]::SetEnvironmentVariable($name,$taskValues[$name],'Process') }
    if (-not $env:AGENT_DB_PASSWORD -or $env:AGENT_DEV_AUTH -cne 'true') { throw 'AP_ENVIRONMENT_PREREQUISITE_MISMATCH' }
    $env:PGPASSWORD = $env:AGENT_DB_PASSWORD
    $env:PGCONNECT_TIMEOUT = '5'
    $env:PGOPTIONS = '-c statement_timeout=5000 -c default_transaction_read_only=on'
    $taskPhase = 'LISTENER_ADMISSION'; $taskReason = 'PG_LISTENER_PROCESS_MISMATCH'
    $taskListeners = @(Get-NetTCPConnection -LocalPort 25432 -State Listen)
    if ($taskListeners.Count -ne 1 -or $taskListeners[0].LocalAddress -cne '127.0.0.1' -or $taskListeners[0].OwningProcess -ne 15204) { throw 'PG_LISTENER_PROCESS_MISMATCH' }
    $taskProcess = Get-CimInstance Win32_Process -Filter 'ProcessId=15204'
    if ($taskProcess.ExecutablePath.Replace([char]92,[char]47) -cne 'V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe' -or $taskProcess.CommandLine -notlike '*V:/.local/pgdata*') { throw 'PG_LISTENER_PROCESS_MISMATCH' }
    Write-TaskJson 'listener.json' ([ordered]@{pid=15204;port=25432;loopback=$true;expectedExecutable=$true;expectedDataArgument=$true})
    $taskPhase = 'PG_INSTANCE'; $taskReason = 'PG_INSTANCE_DIRECTORY_ROLE_MISMATCH'
    $taskInstance = Read-TaskPg 'postgres' "select json_build_object('directory',current_setting('data_directory'),'role',current_user)"
    $taskDirectoryOkay = $taskInstance.directory.Replace([char]92,[char]47).TrimEnd('/') -ceq 'V:/.local/pgdata'
    Write-TaskJson 'pg-instance.json' ([ordered]@{directory=if($taskDirectoryOkay){'V:/.local/pgdata'}else{'OTHER'};role=(Fixed-TaskValue $taskInstance.role @('agent_v12'));directoryMatched=$taskDirectoryOkay})
    if (-not $taskDirectoryOkay -or $taskInstance.role -cne 'agent_v12') { throw 'PG_INSTANCE_DIRECTORY_ROLE_MISMATCH' }
    $taskPhase = 'PG_DATABASE'; $taskReason = 'EXISTING_DATABASE_OWNER_MISMATCH'
    $taskOwner = Read-TaskPg 'postgres' "select json_build_object('database',datname,'owner',pg_get_userbyid(datdba)) from pg_database where datname='agent_platform_skill_algorithm_test'"
    Write-TaskJson 'pg-database.json' ([ordered]@{exists=($null -ne $taskOwner);database=(Fixed-TaskValue $taskOwner.database @($taskDatabase));owner=(Fixed-TaskValue $taskOwner.owner @('agent_v12'))})
    if (-not $taskOwner -or $taskOwner.database -cne $taskDatabase -or $taskOwner.owner -cne 'agent_v12') { throw 'EXISTING_DATABASE_OWNER_MISMATCH' }
    $taskPhase = 'PG_SCHEMA'; $taskReason = 'EXISTING_SKILL_SCHEMA_HISTORY_IDENTITY_MISMATCH'
    $taskSchema = Read-TaskPg $taskDatabase "select json_build_object('database',current_database(),'role',current_user,'schemaOwner',pg_get_userbyid(n.nspowner),'display',to_regclass('skill.flyway_schema_history')::text,'historySchema',h.nspname,'historyName',c.relname,'historyOwner',pg_get_userbyid(c.relowner)) from pg_namespace n left join pg_class c on c.oid=to_regclass('skill.flyway_schema_history') left join pg_namespace h on h.oid=c.relnamespace where n.nspname='skill'"
    $taskLegacySchema = $taskSchema -and $taskSchema.database -ceq $taskDatabase -and $taskSchema.role -ceq 'agent_v12' -and $taskSchema.schemaOwner -ceq 'agent_v12' -and $taskSchema.display -ceq 'skill.flyway_schema_history'
    $taskSchemaOkay = $taskSchema -and $taskSchema.database -ceq $taskDatabase -and $taskSchema.role -ceq 'agent_v12' -and $taskSchema.schemaOwner -ceq 'agent_v12' -and $taskSchema.historySchema -ceq 'skill' -and $taskSchema.historyName -ceq 'flyway_schema_history' -and $taskSchema.historyOwner -ceq 'agent_v12'
    Write-TaskJson 'pg-schema.json' ([ordered]@{database=(Fixed-TaskValue $taskSchema.database @($taskDatabase));role=(Fixed-TaskValue $taskSchema.role @('agent_v12'));schemaOwner=(Fixed-TaskValue $taskSchema.schemaOwner @('agent_v12'));regclassDisplay=(Fixed-TaskValue $taskSchema.display @('skill.flyway_schema_history','flyway_schema_history'));historySchema=(Fixed-TaskValue $taskSchema.historySchema @('skill'));historyName=(Fixed-TaskValue $taskSchema.historyName @('flyway_schema_history'));historyOwner=(Fixed-TaskValue $taskSchema.historyOwner @('agent_v12'));legacyDisplayPredicatePassed=[bool]$taskLegacySchema;exactCatalogIdentityPassed=[bool]$taskSchemaOkay})
    if (-not $taskSchemaOkay) { throw 'EXISTING_SKILL_SCHEMA_HISTORY_IDENTITY_MISMATCH' }
    $taskPhase = 'PG_HISTORY'; $taskReason = 'FLYWAY_UNKNOWN_TYPE_OWNER_OR_SCRIPT'
    $taskHistory = @(Read-TaskPg $taskDatabase "select coalesce(json_agg(json_build_object('rank',installed_rank,'version',version,'type',type,'script',script,'checksum',checksum,'installedBy',installed_by,'success',success) order by installed_rank),'[]'::json) from skill.flyway_schema_history")
    $taskFiles = @(Get-ChildItem -LiteralPath 'skill-registry/src/main/resources/db/migration' -File -Filter 'V*.sql' | ForEach-Object {$_.Name})
    $taskSafeHistory = @(foreach ($row in $taskHistory) {
        [pscustomobject]@{rank=$row.rank;version=if($null -eq $row.version){$null}elseif($row.version -cmatch '^\d+(\.\d+)*$'){$row.version}else{'OTHER'};type=(Fixed-TaskValue $row.type @('SQL','SCHEMA'));script=(Fixed-TaskValue $row.script (@($taskFiles)+@('"skill"','skill')));checksum=$row.checksum;installedBy=(Fixed-TaskValue $row.installedBy @('agent_v12'));success=$row.success;scriptInProjectVFiles=($taskFiles -ccontains $row.script)}
    })
    Write-TaskJson 'pg-history.json' $taskSafeHistory
    $taskLegacyHistory = $taskHistory.Count -gt 0 -and @($taskHistory | Where-Object {-not $_.success -or $_.installedBy -cne 'agent_v12' -or $taskFiles -cnotcontains $_.script}).Count -eq 0
    $taskSql = @($taskHistory | Where-Object {$_.type -ceq 'SQL'})
    $taskSchemaRows = @($taskHistory | Where-Object {$_.type -ceq 'SCHEMA'})
    $taskBadRows = @($taskHistory | Where-Object {
        -not $_.success -or $_.installedBy -cne 'agent_v12' -or
        (($_.type -ceq 'SQL') -and ($null -eq $_.version -or $taskFiles -cnotcontains $_.script)) -or
        (($_.type -ceq 'SCHEMA') -and ($_.rank -ne 0 -or $null -ne $_.version -or $_.script -cne '"skill"')) -or
        @('SQL','SCHEMA') -cnotcontains $_.type
    })
    $taskHistoryOkay = $taskSql.Count -gt 0 -and $taskSchemaRows.Count -le 1 -and $taskBadRows.Count -eq 0
    Write-TaskJson 'pg-history-predicates.json' ([ordered]@{legacyAllRowsRequireVFilePassed=$taskLegacyHistory;exactClassifiedHistoryPassed=$taskHistoryOkay;sqlRows=$taskSql.Count;schemaRows=$taskSchemaRows.Count;badRows=$taskBadRows.Count;schemaRule='rank0/versionNULL/typeSCHEMA/scriptQuotedSkill/installedByAgentV12/successTrue';sqlRule='typeSQL/versionPresent/currentProjectVScript/installedByAgentV12/successTrue';unknownTypesAllowed=$false})
    if (-not $taskHistoryOkay) { throw 'FLYWAY_UNKNOWN_TYPE_OWNER_OR_SCRIPT' }
    $taskPhase = 'ADMISSION_COMPLETE'; $taskReason = 'NONE'
    $taskPassed = $true
    Write-TaskJson 'admission.json' ([ordered]@{passed=$true;phase=$taskPhase;reason=$taskReason;checkedAt=[DateTimeOffset]::Now.ToString('o');head=$taskExpectedHead;envSha256=$taskExpectedEnv;sourceHashes=$taskFrozen;pid=15204;port=25432;database=$taskDatabase;databaseOwner='agent_v12';schemaOwner='agent_v12';exactCatalogIdentity=$taskSchemaOkay;exactClassifiedHistory=$taskHistoryOkay;legacySchemaDisplayPredicate=$taskLegacySchema;legacyHistoryPredicate=$taskLegacyHistory;created=$false;reset=$false;drop=$false;truncate=$false;markerAdded=$false;javaMavenStarted=$false})
    Write-Output 'EX27_ALGORITHM_READONLY_ADMISSION_PASSED'
} catch {
    Write-TaskJson 'preflight-error.json' ([ordered]@{phase=$taskPhase;reason=$taskReason;scriptLineNumber=$_.InvocationInfo.ScriptLineNumber;exceptionClass=$_.Exception.GetType().FullName;messagePrinted=$false;javaMavenStarted=$false})
    Write-Output ('EX27_ALGORITHM_READONLY_ADMISSION_STOPPED phase=' + $taskPhase + ' reason=' + $taskReason + ' line=' + $_.InvocationInfo.ScriptLineNumber)
} finally {
    foreach ($name in $taskPrevious.Keys) { [Environment]::SetEnvironmentVariable($name,$taskPrevious[$name],'Process') }
    Set-Location -LiteralPath $taskLocation
}
if (-not $taskPassed) { exit 2 }
