$ErrorActionPreference = 'Stop'
$taskRoot = 'G:/207/项目/算法重组工具/代码/agent-platform'
$taskRun = $PSScriptRoot
$taskExpectedHead = '28aa99ea12cb96bbbf781859c017acebd30647a3'
$taskExpectedEnv = 'AF038F68E0CA2E3EEEEB0C5240B2C2DA1EF647D680500F63AF1564975EBAFD4A'
$taskTest = 'skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java'
$taskStore = 'skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java'
$taskFrozen = [ordered]@{
    $taskTest = 'D64CD4620B234D11EBE76BDEA78917C1D6D71257986C7A5EB26E832D45FEDE5A'
    $taskStore = '9DEB51566206D548110F1F6B5AAAB466619EB80D43E8D30B67F11B7E27B7541D'
}
$taskBeforeEnvironment = @{}
$taskOriginalLocation = Get-Location
$taskNativeExit = $null
$taskCollectorExit = 2
$taskStarted = $null
$taskPg = 'V:/.tools/postgresql-16.15/pgsql/bin/psql.exe'
$taskDatabase = 'agent_platform_skill_algorithm_test'
function Write-TaskJson([string]$Name, $Value) {
    $json = ConvertTo-Json -InputObject $Value -Depth 18
    [IO.File]::WriteAllText((Join-Path $taskRun $Name), $json, (New-Object Text.UTF8Encoding($false)))
}
function Get-TaskHashes {
    @(foreach ($path in $taskFrozen.Keys) {
        [pscustomobject]@{path=$path;sha256=(Get-FileHash -LiteralPath (Join-Path $taskRoot $path) -Algorithm SHA256).Hash}
    })
}
function Read-TaskPg([string]$Database, [string]$Sql) {
    $output = & $taskPg -X -w -h 127.0.0.1 -p 25432 -U agent_v12 -d $Database -At -v ON_ERROR_STOP=1 -c $Sql 2>> (Join-Path $taskRun 'pg-preflight.raw.log')
    if ($LASTEXITCODE -ne 0) { throw 'Read-only PG preflight failed' }
    (($output -join "`n") | ConvertFrom-Json)
}
try {
    Set-Location -LiteralPath $taskRoot
    $taskHeadBefore = (& git rev-parse HEAD) -join ''
    if ($LASTEXITCODE -ne 0 -or $taskHeadBefore -cne $taskExpectedHead) { throw 'HEAD admission mismatch' }
    $taskEnvBefore = (Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash
    if ($taskEnvBefore -cne $taskExpectedEnv) { throw 'Environment fingerprint admission mismatch' }
    $taskSourceBefore = @(Get-TaskHashes)
    foreach ($row in $taskSourceBefore) {
        if ($row.sha256 -cne $taskFrozen[$row.path]) { throw 'Frozen source admission mismatch' }
    }
    Write-TaskJson 'source-before.json' $taskSourceBefore
    $taskEnvValues = @{}
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $taskRoot '.env'), [Text.Encoding]::UTF8)) {
        if ($line -match '^([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $taskEnvValues[$Matches[1]] = $Matches[2] }
    }
    $taskNames = @($taskEnvValues.Keys) + @('JAVA_HOME','PATH','ENGINE_PG_TEST','PGPASSWORD','PGCONNECT_TIMEOUT','PGOPTIONS')
    foreach ($name in ($taskNames | Select-Object -Unique)) {
        $taskBeforeEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    foreach ($name in $taskEnvValues.Keys) { [Environment]::SetEnvironmentVariable($name, $taskEnvValues[$name], 'Process') }
    if (-not $env:AGENT_DB_PASSWORD) { throw 'Missing authorized AP PG prerequisite' }
    $env:JAVA_HOME = if ($env:AGENT_JAVA_HOME) { $env:AGENT_JAVA_HOME } else { 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot' }
    if ($env:JAVA_HOME.Replace('\','/') -cne 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot') { throw 'JDK admission mismatch' }
    $env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
    $env:ENGINE_PG_TEST = 'true'
    $env:PGPASSWORD = $env:AGENT_DB_PASSWORD
    $env:PGCONNECT_TIMEOUT = '5'
    $env:PGOPTIONS = '-c statement_timeout=5000 -c default_transaction_read_only=on'
    if ($env:AGENT_DEV_AUTH -cne 'true') { throw 'Baseline development flag mismatch' }
    $taskListeners = @(Get-NetTCPConnection -LocalPort 25432 -State Listen)
    if ($taskListeners.Count -ne 1 -or $taskListeners[0].LocalAddress -cne '127.0.0.1' -or $taskListeners[0].OwningProcess -ne 15204) { throw 'Owned PG listener admission mismatch' }
    $taskProcess = Get-CimInstance Win32_Process -Filter 'ProcessId=15204'
    if ($taskProcess.ExecutablePath.Replace('\','/') -cne 'V:/.tools/postgresql-16.15/pgsql/bin/postgres.exe' -or $taskProcess.CommandLine -notlike '*V:/.local/pgdata*') { throw 'Owned PG process admission mismatch' }
    $taskPgIdentity = Read-TaskPg 'postgres' "select json_build_object('dataDirectory',current_setting('data_directory'),'role',current_user)"
    if ($taskPgIdentity.dataDirectory.Replace('\','/').TrimEnd('/') -cne 'V:/.local/pgdata' -or $taskPgIdentity.role -cne 'agent_v12') { throw 'PG authority directory or role mismatch' }
    $taskOwner = Read-TaskPg 'postgres' "select json_build_object('database',datname,'owner',pg_get_userbyid(datdba)) from pg_database where datname='agent_platform_skill_algorithm_test'"
    if (-not $taskOwner -or $taskOwner.database -cne $taskDatabase -or $taskOwner.owner -cne 'agent_v12') { throw 'Existing algorithm database owner mismatch; no provisioning authorized' }
    $taskSchema = Read-TaskPg $taskDatabase "select json_build_object('database',current_database(),'role',current_user,'schemaOwner',pg_get_userbyid(nspowner),'history',to_regclass('skill.flyway_schema_history')::text) from pg_namespace where nspname='skill'"
    if (-not $taskSchema -or $taskSchema.database -cne $taskDatabase -or $taskSchema.role -cne 'agent_v12' -or $taskSchema.schemaOwner -cne 'agent_v12' -or $taskSchema.history -cne 'skill.flyway_schema_history') { throw 'Existing algorithm schema or Flyway ownership mismatch' }
    $taskHistory = @(Read-TaskPg $taskDatabase "select coalesce(json_agg(json_build_object('rank',installed_rank,'version',version,'script',script,'checksum',checksum,'installedBy',installed_by,'success',success) order by installed_rank),'[]'::json) from skill.flyway_schema_history")
    $taskMigrationFiles = @(Get-ChildItem -LiteralPath 'skill-registry/src/main/resources/db/migration' -File -Filter 'V*.sql' | ForEach-Object { $_.Name })
    if ($taskHistory.Count -eq 0 -or @($taskHistory | Where-Object { -not $_.success -or $_.installedBy -cne 'agent_v12' -or $taskMigrationFiles -cnotcontains $_.script }).Count -gt 0) { throw 'Existing Flyway history does not match this project migration names or owner' }
    Write-TaskJson 'pg-precondition.json' ([ordered]@{pid=15204;port=25432;executable=$taskProcess.ExecutablePath;dataDirectory=$taskPgIdentity.dataDirectory;database=$taskDatabase;owner=$taskOwner.owner;schemaOwner=$taskSchema.schemaOwner;history=$taskHistory;allHistoryScriptsInCurrentProject=$true;allHistorySucceeded=$true;readOnlyAdmission=$true;created=$false;reset=$false;drop=$false;truncate=$false;markerAdded=$false})
    [Environment]::SetEnvironmentVariable('PGOPTIONS',$taskBeforeEnvironment['PGOPTIONS'],'Process')
    [Environment]::SetEnvironmentVariable('PGCONNECT_TIMEOUT',$taskBeforeEnvironment['PGCONNECT_TIMEOUT'],'Process')
    $taskJar = Join-Path $taskRoot '.tools/m2/cn/agentplatform/admin-common/0.1.0-SNAPSHOT/admin-common-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Existing local admin-common artifact absent; no reactor expansion authorized' }
    $taskArgs = @('-B','-ntp','-pl','skill-registry','test',"-Dmaven.repo.local=$taskRoot/.tools/m2",'-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp -Dex27.algorithmInterruptDiagnostic=true','-Dsurefire.timeout=900','-Dsurefire.failIfNoSpecifiedTests=true','-Dtest=AlgorithmOffloadInterruptionTest')
    Write-TaskJson 'environment.json' ([ordered]@{head=$taskHeadBefore;powershell=$PSVersionTable.PSVersion.ToString();jdk=$env:JAVA_HOME;envSha256=$taskEnvBefore;nonSecretFlags=@{AGENT_DEV_AUTH=($env:AGENT_DEV_AUTH -eq 'true');ENGINE_PG_TEST=($env:ENGINE_PG_TEST -eq 'true');AGENT_SECURITY_MODE=if([string]::IsNullOrEmpty($env:AGENT_SECURITY_MODE)){'UNSET'}elseif(@('LEGACY','AUTHORITY') -contains $env:AGENT_SECURITY_MODE){$env:AGENT_SECURITY_MODE}else{'OTHER'}};adminCommonJarSha256=(Get-FileHash -LiteralPath $taskJar -Algorithm SHA256).Hash;diagnostic=$true;failureIgnore=$false;skip=$false;reactorExpansion=$false;environmentValuesPrinted=$false;independentProcess=$true})
    $taskStarted = Get-Date
    Write-TaskJson 'command.json' ([ordered]@{command='mvn.cmd';arguments=$taskArgs;startedAt=$taskStarted.ToString('o');nativeExit=$null;state='STARTED';collectorSha256=(Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash})
    $taskPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & mvn.cmd @taskArgs *> (Join-Path $taskRun 'raw.log')
        $taskNativeExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $taskPreference
        $taskEnded = Get-Date
        Write-TaskJson 'native-exit.json' ([ordered]@{nativeExit=$taskNativeExit;startedAt=$taskStarted.ToString('o');endedAt=$taskEnded.ToString('o');failureIgnore=$false;nativeExitPersistedBeforeXml=$true})
    }
    $taskXmlDir = Join-Path $taskRun 'xml'
    New-Item -ItemType Directory -Path $taskXmlDir | Out-Null
    $taskFresh = @(Get-ChildItem -LiteralPath 'skill-registry/target/surefire-reports' -File -Filter 'TEST-*.xml' | Where-Object { $_.LastWriteTime -ge $taskStarted })
    $taskMethods = New-Object 'System.Collections.Generic.List[object]'
    $taskDiagnostics = New-Object 'System.Collections.Generic.List[object]'
    $taskFailures = New-Object 'System.Collections.Generic.List[object]'
    $taskManifests = @(foreach ($file in $taskFresh) {
        $copy = Join-Path $taskXmlDir $file.Name
        Copy-Item -LiteralPath $file.FullName -Destination $copy
        $document = New-Object System.Xml.XmlDocument
        $document.XmlResolver = $null
        $document.Load($copy)
        foreach ($case in $document.SelectNodes("//*[local-name()='testcase']")) {
            $status = 'PASS'
            foreach ($candidateStatus in @('failure','error','skipped')) {
                $event = $case.SelectSingleNode("./*[local-name()='$candidateStatus']")
                if ($event) {
                    $status = @{failure='FAIL';error='ERROR';skipped='SKIP'}[$candidateStatus]
                    $frames = @($event.InnerText -split "`r?`n" | Where-Object { $_ -match 'cn\.agentplatform\.skill\.infra\.AlgorithmOffloadInterruptionTest\.[A-Za-z]+\(AlgorithmOffloadInterruptionTest\.java:\d+\)' })
                    $taskFailures.Add([pscustomobject]@{method=$case.GetAttribute('name');status=$status;type=$event.GetAttribute('type');ownFrames=$frames})
                    break
                }
            }
            $taskMethods.Add([pscustomobject]@{classname=$case.GetAttribute('classname');method=$case.GetAttribute('name');status=$status;seconds=$case.GetAttribute('time');xml=$file.Name})
        }
        foreach ($output in $document.SelectNodes("//*[local-name()='system-out']")) {
            foreach ($line in ($output.InnerText -split "`r?`n")) {
                if ($line -match '^ALGORITHM_INTERRUPT_DIAGNOSTIC (\{.*\})$') { $taskDiagnostics.Add(($Matches[1] | ConvertFrom-Json)) }
            }
        }
        [pscustomobject]@{file=$file.Name;lastWriteTime=$file.LastWriteTime.ToString('o');bytes=$file.Length;sha256=(Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash}
    })
    $taskMethods.ToArray() | Export-Csv -LiteralPath (Join-Path $taskRun 'methods.csv') -Encoding UTF8 -NoTypeInformation
    Write-TaskJson 'xml-manifest.json' $taskManifests
    Write-TaskJson 'diagnostic-facts.json' $taskDiagnostics.ToArray()
    Write-TaskJson 'failure-facts.json' $taskFailures.ToArray()
    $taskExpectedMethods = @('interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[1]','interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[2]','interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[3]','unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit')
    $taskIdentityOkay = $taskMethods.Count -eq 4 -and @($taskMethods | Where-Object { $_.classname -cne 'cn.agentplatform.skill.infra.AlgorithmOffloadInterruptionTest' -or $taskExpectedMethods -cnotcontains $_.method }).Count -eq 0 -and @($taskExpectedMethods | Where-Object { $expected=$_; @($taskMethods | Where-Object { $_.method -ceq $expected }).Count -ne 1 }).Count -eq 0
    $taskSourceAfter = @(Get-TaskHashes)
    Write-TaskJson 'source-after.json' $taskSourceAfter
    $taskSourceStable = @($taskSourceAfter | Where-Object { $_.sha256 -cne $taskFrozen[$_.path] }).Count -eq 0
    $taskHeadAfter = (& git rev-parse HEAD) -join ''
    $taskEnvAfter = (Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash
    $taskCollectorExit = if ($taskNativeExit -eq 0 -and $taskIdentityOkay -and $taskFresh.Count -eq 1 -and $taskDiagnostics.Count -eq 3 -and $taskSourceStable -and $taskHeadAfter -ceq $taskHeadBefore -and $taskEnvAfter -ceq $taskEnvBefore) { 0 } else { 1 }
    $taskSummary = [ordered]@{kind='ONE_CLASS_DIAGNOSTIC_ONLY';nativeExit=$taskNativeExit;collectorExit=$taskCollectorExit;freshXml=$taskFresh.Count;reports=$taskMethods.Count;identitiesExactlyOriginalFour=$taskIdentityOkay;passed=@($taskMethods | Where-Object {$_.status -eq 'PASS'}).Count;failure=@($taskMethods | Where-Object {$_.status -eq 'FAIL'}).Count;error=@($taskMethods | Where-Object {$_.status -eq 'ERROR'}).Count;skipped=@($taskMethods | Where-Object {$_.status -eq 'SKIP'}).Count;diagnosticStages=@($taskDiagnostics | ForEach-Object {$_.stage});diagnosticCount=$taskDiagnostics.Count;headBefore=$taskHeadBefore;headAfter=$taskHeadAfter;headStable=($taskHeadAfter -ceq $taskHeadBefore);sourceStable=$taskSourceStable;envBefore=$taskEnvBefore;envAfter=$taskEnvAfter;envStable=($taskEnvAfter -ceq $taskEnvBefore);originalFullNewFailureStillOpen=$true;fullModuleRun=$false;productOrFixtureRootCauseConfirmed=$false}
    Write-TaskJson 'summary.json' $taskSummary
    Write-Output ('EX27_ALGORITHM_ARCHIVE=' + $taskRun)
    $taskSummary | ConvertTo-Json -Depth 5
} catch {
    Write-TaskJson 'collector-error.json' ([ordered]@{type=$_.Exception.GetType().FullName;nativeExit=$taskNativeExit;nativeExitPersisted=(Test-Path -LiteralPath (Join-Path $taskRun 'native-exit.json'));state=if($null -eq $taskStarted){'ADMISSION_FAILED_NO_MAVEN'}else{'COLLECTION_OR_COMMAND_FAILED'};secretsPrinted=$false})
    Write-Output ('EX27_ALGORITHM_STOPPED archive=' + $taskRun + ' nativeExit=' + $taskNativeExit + ' errorClass=' + $_.Exception.GetType().FullName)
} finally {
    foreach ($name in $taskBeforeEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $taskBeforeEnvironment[$name], 'Process') }
    Set-Location -LiteralPath $taskOriginalLocation
}
exit $taskCollectorExit
