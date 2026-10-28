param([string]$Phase, [string]$Selector = '', [switch]$Install, [switch]$FreshForkPerClass)
$ErrorActionPreference = 'Stop'
$ApRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$JdkRoot = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:JAVA_HOME = $JdkRoot
$env:PATH = "$JdkRoot/bin;$env:PATH"
Get-Content -LiteralPath (Join-Path $ApRoot '.env') | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
$env:ENGINE_PG_TEST = 'true'
$RunId = Get-Date -Format yyyyMMdd-HHmmss-fff
$RunDir = Join-Path $PSScriptRoot "runs/$Phase-$RunId"
New-Item -ItemType Directory -Path $RunDir -Force | Out-Null
$SourceHashes = @('platform-gateway/src/main/java/cn/agentplatform/gateway/application/PublishedRunProxy.java',
    'platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java') |
    ForEach-Object { [pscustomobject]@{ path=$_; sha256=(Get-FileHash -LiteralPath (Join-Path $ApRoot $_) -Algorithm SHA256).Hash } }
$SourceHashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $RunDir 'source-sha256.json') -Encoding UTF8
$ProxyTestPath = Join-Path $ApRoot 'platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java'
@([regex]::Matches((Get-Content -LiteralPath $ProxyTestPath -Raw), '@Test\s+void\s+(\w+)\s*\(') |
    ForEach-Object { $_.Groups[1].Value }) | ConvertTo-Json |
    Set-Content -LiteralPath (Join-Path $RunDir 'source-proxy-methods.json') -Encoding UTF8
$StartedAt = Get-Date
$StartedHead = (& git -C $ApRoot rev-parse HEAD)
$MavenArgs = @('-B', '-ntp', '-pl', 'platform-gateway', 'test',
    "-Dmaven.repo.local=$ApRoot/.tools/m2",
    '-DargLine=-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp',
    '-Dsurefire.timeout=900', '-Dsurefire.failIfNoSpecifiedTests=true')
if ($Selector) { $MavenArgs += "-Dtest=$Selector" }
if ($FreshForkPerClass) { $MavenArgs += '-DreuseForks=false' }
if ($Install) { $MavenArgs = @('-B', '-ntp', 'install', '-DskipTests', "-Dmaven.repo.local=$ApRoot/.tools/m2") }
Push-Location -LiteralPath $ApRoot
try {
    $ErrorActionPreference = 'Continue'
    & mvn.cmd @MavenArgs *> (Join-Path $RunDir 'raw.log')
    $ExitCode = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $XmlDir = Join-Path $RunDir 'xml'
    New-Item -ItemType Directory -Path $XmlDir -Force | Out-Null
    $Reports = @(Get-ChildItem -LiteralPath 'platform-gateway/target/surefire-reports' -Filter 'TEST-*.xml' |
        Where-Object { $_.LastWriteTime -ge $StartedAt })
    $Methods = @()
    foreach ($Report in $Reports) {
        Copy-Item -LiteralPath $Report.FullName -Destination $XmlDir
        $Doc = [xml](Get-Content -LiteralPath $Report.FullName -Raw)
        foreach ($Case in @($Doc.testsuite.testcase)) {
            if ($null -eq $Case) { continue }
            $Status = 'PASS'
            if ($null -ne $Case.failure) { $Status = 'FAIL' }
            elseif ($null -ne $Case.error) { $Status = 'ERROR' }
            elseif ($null -ne $Case.skipped) { $Status = 'SKIP' }
            $Methods += [pscustomobject]@{ module='platform-gateway'; classname=[string]$Case.classname;
                method=[string]$Case.name; status=$Status; seconds=[string]$Case.time }
        }
    }
    $Methods | Export-Csv -LiteralPath (Join-Path $RunDir 'methods.csv') -Encoding UTF8 -NoTypeInformation
    $Run = [pscustomobject]@{phase=$Phase; selector=$Selector; startedAt=$StartedAt.ToString('o');
        endedAt=(Get-Date).ToString('o'); exitCode=$ExitCode; command=('mvn.cmd ' + ($MavenArgs -join ' '));
        head=(& git rev-parse HEAD); headAtStart=$StartedHead; reportCount=$Reports.Count; methodCount=$Methods.Count;
        statuses=@($Methods | Group-Object status | ForEach-Object { [pscustomobject]@{status=$_.Name; count=$_.Count} });
        sourceTestClasses=@(Get-ChildItem -LiteralPath 'platform-gateway/src/test/java' -Filter '*Test.java' -Recurse | ForEach-Object { $_.BaseName } | Sort-Object);
        developmentFlagPresent=($env:AGENT_DEV_AUTH -eq 'true'); postgresTestsEnabled=($env:ENGINE_PG_TEST -eq 'true')}
    $Run | Add-Member -NotePropertyName freshForkPerClass -NotePropertyValue ([bool]$FreshForkPerClass)
    $Run | Add-Member -NotePropertyName configurationSha256 -NotePropertyValue (Get-FileHash -LiteralPath (Join-Path $ApRoot '.env') -Algorithm SHA256).Hash
    if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'dependency-recovery.json')) {
        $Run | Add-Member -NotePropertyName dependencyRecoveryRecord -NotePropertyValue '../../dependency-recovery.json'
    }
    $Run | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $RunDir 'run.json') -Encoding UTF8
    $SafeOutput = @(Get-Content -LiteralPath (Join-Path $RunDir 'raw.log') | Where-Object {
        $_ -match '^EX35_|^\[INFO\] (Tests run:|BUILD |Total time:)|^\[ERROR\] (Tests run:|COMPILATION ERROR|.*\.java:\[)'
    })
    $SafeOutput | Set-Content -LiteralPath (Join-Path $RunDir 'safe-facts.txt') -Encoding UTF8
    $SafeOutput
    $Run | ConvertTo-Json -Depth 6
} finally { Pop-Location }
exit $ExitCode
