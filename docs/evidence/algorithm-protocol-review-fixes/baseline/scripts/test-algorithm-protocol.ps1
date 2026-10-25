param([string]$Tests='AlgorithmProtocolTest,AlgorithmExecutionPostgresTest,AlgorithmMigrationPostgresTest',[string]$Log='algorithm-directed.log')
$ErrorActionPreference='Stop'
$algorithmRoot=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $algorithmRoot
# Only this project's explicitly configured environment. Never emit secret values or start services.
if ([string]::IsNullOrWhiteSpace($env:AGENT_DB_PASSWORD)) {
    Get-Content -LiteralPath (Join-Path $algorithmRoot '.env') | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process') }
    }
}
if ([string]::IsNullOrWhiteSpace($env:AGENT_DB_PASSWORD)) { throw 'AGENT_DB_PASSWORD required' }
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
if ($Log -notmatch '^[a-zA-Z0-9_.-]+\.log$') { throw 'Log filename only' }
if ($Tests -notmatch '^(\*|[A-Za-z0-9_]+(#[A-Za-z0-9_*]+)?(,[A-Za-z0-9_]+(#[A-Za-z0-9_*]+)?)*)$') { throw 'Quoted comma-separated test class names required' }
$algorithmStarted=Get-Date
$algorithmArgs=@('-B','-ntp','-pl','skill-registry','-am','test',"-Dmaven.repo.local=$algorithmRoot/.tools/m2",'-Djdk.net.unixdomain.tmpdir=V:/.local/tmp','-Dsurefire.failIfNoSpecifiedTests=false')
if ($Tests -ne '*') { $algorithmArgs+="-Dtest=$Tests" }
& mvn.cmd @algorithmArgs 2>&1 | Tee-Object -FilePath (Join-Path $algorithmRoot "docs/evidence/algorithm-protocol-artifacts/$Log")
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$algorithmReports=@(Get-ChildItem -LiteralPath (Join-Path $algorithmRoot 'skill-registry/target/surefire-reports') -Filter 'TEST-*.xml' | Where-Object { $_.LastWriteTime -ge $algorithmStarted.AddSeconds(-1) })
if ($algorithmReports.Count -eq 0) { throw 'No fresh skill tests ran; Maven success alone is not verification' }
foreach($algorithmReport in $algorithmReports) {
    [xml]$algorithmXml=Get-Content -LiteralPath $algorithmReport.FullName -Raw
    if ([int]$algorithmXml.testsuite.tests -lt 1 -or [int]$algorithmXml.testsuite.failures -ne 0 -or [int]$algorithmXml.testsuite.errors -ne 0 -or [int]$algorithmXml.testsuite.skipped -ne 0) { throw 'Fresh test report contains failure/error/skip or no tests' }
}
if($Tests -ne '*') {
    foreach($algorithmSuite in ($Tests -split ',' | ForEach-Object { ($_ -split '#')[0] })) {
        if(-not ($algorithmReports.Name -like "TEST-*.$algorithmSuite.xml")) { throw "Requested suite did not run: $algorithmSuite" }
    }
}
