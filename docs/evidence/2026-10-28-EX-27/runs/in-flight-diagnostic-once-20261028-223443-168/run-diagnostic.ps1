param([string]$Phase = 'diagnostic-red', [switch]$Original)
$ErrorActionPreference = 'Stop'
$ApRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$JdkRoot = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:JAVA_HOME = $JdkRoot
$env:PATH = "$JdkRoot/bin;$env:PATH"
Get-Content -LiteralPath (Join-Path $ApRoot '.env') -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
$env:ENGINE_PG_TEST = 'true'
$RunDir = Join-Path $PSScriptRoot ('runs/' + $Phase + '-' + (Get-Date -Format yyyyMMdd-HHmmss-fff))
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null
Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $RunDir 'run-diagnostic.ps1')
$CollectorHash = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash
$StartedAt = Get-Date
$StartedHead = (& git -C $ApRoot rev-parse HEAD)
$Source = 'platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsSseLifecycleTest.java'
$SourceHash = (Get-FileHash -LiteralPath (Join-Path $ApRoot $Source) -Algorithm SHA256).Hash
$ArgumentLine = '-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
if (-not $Original) { $ArgumentLine += ' -Dex27.forcedReleaseInFlight=true' }
$MavenArgs = @('-B', '-ntp', '-pl', 'platform-gateway', 'test',
    "-Dmaven.repo.local=$ApRoot/.tools/m2", "-DargLine=$ArgumentLine", '-DreuseForks=false',
    '-Dsurefire.timeout=900', '-Dsurefire.failIfNoSpecifiedTests=true',
    '-Dtest=PortalRunEventsSseLifecycleTest#forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion')
Push-Location -LiteralPath $ApRoot
try {
    $ErrorActionPreference = 'Continue'
    & mvn.cmd @MavenArgs *> (Join-Path $RunDir 'raw.log')
    $ExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $Execution = [pscustomobject]@{phase=$Phase; startedAt=$StartedAt.ToString('o');
        processEndedAt=(Get-Date).ToString('o'); exitCode=$ExitCode; command=('mvn.cmd ' + ($MavenArgs -join ' '));
        sourceSha256=$SourceHash; collectorSha256=$CollectorHash; nativeChildExitCode=$null}
    $Execution | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $RunDir 'execution.json') -Encoding UTF8
    $CollectionErrors = @()
    $DiagnosticDirs = @()
    $NativeExitCode = $null
    try {
        if (-not $Original) {
            $Peers = '.local/iam-o2b-f2c2a-http-exit-fixes/peers'
            if (Test-Path -LiteralPath $Peers) {
                $DiagnosticDirs = @(Get-ChildItem -LiteralPath $Peers -Directory -Filter 'forced-release-in-flight-*' |
                    Where-Object { $_.CreationTime -ge $StartedAt })
            }
            if ($DiagnosticDirs.Count -eq 1) {
                $NativeExit = Join-Path $DiagnosticDirs[0].FullName 'exit.properties'
                if (Test-Path -LiteralPath $NativeExit) {
                    Copy-Item -LiteralPath $NativeExit -Destination (Join-Path $RunDir 'native-exit.properties')
                    Get-Content -LiteralPath $NativeExit | ForEach-Object {
                        if ($_ -match '^exit=([0-9]+)$') { $NativeExitCode = [int]$Matches[1] }
                    }
                }
                $Facts = Join-Path $DiagnosticDirs[0].FullName 'ex27-release-diagnostic.json'
                if (Test-Path -LiteralPath $Facts) {
                    Copy-Item -LiteralPath $Facts -Destination (Join-Path $RunDir 'diagnostic.json')
                }
            }
        }
    } catch {
        $CollectionErrors += [pscustomobject]@{stage='native-and-facts'; type=$_.Exception.GetType().FullName}
    }
    $Execution.nativeChildExitCode = $NativeExitCode
    $Execution | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $RunDir 'execution.json') -Encoding UTF8
    $XmlDir = Join-Path $RunDir 'xml'
    New-Item -ItemType Directory -Path $XmlDir -Force | Out-Null
    $Reports = @(Get-ChildItem -LiteralPath 'platform-gateway/target/surefire-reports' -Filter 'TEST-*.xml' |
        Where-Object { $_.LastWriteTime -ge $StartedAt })
    $Methods = @(foreach ($Report in $Reports) {
        try {
            Copy-Item -LiteralPath $Report.FullName -Destination $XmlDir
            $Doc = New-Object System.Xml.XmlDocument
            $Doc.Load($Report.FullName)
        } catch {
            $CollectionErrors += [pscustomobject]@{stage='xml'; file=$Report.Name; type=$_.Exception.GetType().FullName}
            continue
        }
        foreach ($Case in @($Doc.testsuite.testcase)) {
            if ($null -eq $Case) { continue }
            $Status = 'PASS'
            if ($null -ne $Case.failure) { $Status = 'FAIL' }
            elseif ($null -ne $Case.error) { $Status = 'ERROR' }
            elseif ($null -ne $Case.skipped) { $Status = 'SKIP' }
            [pscustomobject]@{classname=[string]$Case.classname; method=[string]$Case.name;
                status=$Status; seconds=[string]$Case.time}
        }
    })
    $Methods | Export-Csv -LiteralPath (Join-Path $RunDir 'methods.csv') -Encoding UTF8 -NoTypeInformation
    $Run = [pscustomobject]@{phase=$Phase; startedAt=$StartedAt.ToString('o'); endedAt=(Get-Date).ToString('o');
        headAtStart=$StartedHead; headAtEnd=(& git rev-parse HEAD); exitCode=$ExitCode;
        command=('mvn.cmd ' + ($MavenArgs -join ' ')); freshForkPerClass=$true;
        diagnosticMode=(-not [bool]$Original); source=$Source; sourceSha256=$SourceHash;
        collectorSha256=$CollectorHash; nativeChildExitCode=$NativeExitCode;
        sourceUnchanged=($SourceHash -eq (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash);
        configurationSha256=(Get-FileHash -LiteralPath '.env' -Algorithm SHA256).Hash;
        developmentFlagPresent=($env:AGENT_DEV_AUTH -eq 'true'); postgresTestsEnabled=($env:ENGINE_PG_TEST -eq 'true');
        reportCount=$Reports.Count; methodCount=$Methods.Count; statuses=@($Methods | Group-Object status |
            ForEach-Object { [pscustomobject]@{status=$_.Name; count=$_.Count} });
        diagnosticDirectoryCount=$DiagnosticDirs.Count; diagnosticFactsPresent=(Test-Path -LiteralPath (Join-Path $RunDir 'diagnostic.json'));
        collectionErrors=$CollectionErrors; independentPowerShellProcessRequired=$true;
        originalWaitSeconds=5; originalParentChildDeadlineSeconds=95}
    $Run | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $RunDir 'run.json') -Encoding UTF8
    $Safe = @(Get-Content -LiteralPath (Join-Path $RunDir 'raw.log') | Where-Object {
        $_ -match '^EX27_|^\[INFO\] (Tests run:|BUILD |Total time:)|^\[ERROR\] (Tests run:|COMPILATION ERROR|.*\.java:\[)'
    })
    $Safe | Set-Content -LiteralPath (Join-Path $RunDir 'safe-facts.txt') -Encoding UTF8
    $Safe
    if ($Run.diagnosticFactsPresent) { Get-Content -LiteralPath (Join-Path $RunDir 'diagnostic.json') -Encoding UTF8 }
    $Run | ConvertTo-Json -Depth 7
} finally { Pop-Location }
exit $ExitCode
