param([Parameter(Mandatory=$true)][string]$AfterRun)
$ErrorActionPreference = 'Stop'
$ApRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
$Ex00 = Join-Path $PSScriptRoot '../2026-10-28-EX-00'
$Reference = Get-Content -LiteralPath (Join-Path $Ex00 'comparison-baseline.json') -Raw | ConvertFrom-Json
$Baseline = @(Import-Csv -LiteralPath (Join-Path $Ex00 ($Reference.module_baselines.'platform-gateway' + '/methods.csv')) |
    Where-Object { $_.module -eq 'platform-gateway' })
$AfterDir = Join-Path $PSScriptRoot ('runs/' + $AfterRun)
$After = @(Import-Csv -LiteralPath (Join-Path $AfterDir 'methods.csv'))
$StatusNames = @{passed='PASS'; failure='FAIL'; error='ERROR'; skipped='SKIP'}
$BeforeByKey = @{}
foreach ($Method in $Baseline) {
    $Key = $Method.classname + '#' + $Method.method
    if ($BeforeByKey.ContainsKey($Key)) { throw "Duplicate baseline method: $Key" }
    $BeforeByKey[$Key] = $Method
}
$AfterByKey = @{}
foreach ($Method in $After) {
    $Key = $Method.classname + '#' + $Method.method
    if ($AfterByKey.ContainsKey($Key)) { throw "Duplicate after method: $Key" }
    $AfterByKey[$Key] = $Method
}
$Differences = @(foreach ($Key in @(@($BeforeByKey.Keys) + @($AfterByKey.Keys) | Sort-Object -Unique)) {
    $Before = $BeforeByKey[$Key]
    $Current = $AfterByKey[$Key]
    $BeforeStatus = if ($null -ne $Before) { $StatusNames[$Before.status] } else { 'UNREPORTED' }
    $AfterStatus = if ($null -ne $Current) { $Current.status } else { 'MISSING' }
    if ($BeforeStatus -ne $AfterStatus) {
        [pscustomobject]@{ key=$Key; before=$BeforeStatus; after=$AfterStatus;
            baselineAttempt=$(if ($null -ne $Before) { $Before.attempt } else { '' }) }
    }
})
$Differences | Export-Csv -LiteralPath (Join-Path $AfterDir 'method-differences.csv') -NoTypeInformation -Encoding UTF8
$Nonpass = @($After | Where-Object { $_.status -ne 'PASS' })
$Nonpass | Export-Csv -LiteralPath (Join-Path $AfterDir 'nonpass.csv') -NoTypeInformation -Encoding UTF8
$Run = Get-Content -LiteralPath (Join-Path $AfterDir 'run.json') -Raw | ConvertFrom-Json
$ReportedClasses = @($After | ForEach-Object { ($_.classname -split '\.')[-1] } | Sort-Object -Unique)
$MissingClasses = @($Run.sourceTestClasses | Where-Object { $_ -notin $ReportedClasses })
$UnexpectedClasses = @($ReportedClasses | Where-Object { $_ -notin $Run.sourceTestClasses })
$MissingMethods = @($Differences | Where-Object { $_.after -eq 'MISSING' })
$ClassLevelCases = @($After | Where-Object { [string]::IsNullOrWhiteSpace($_.method) })
$TestPath = 'platform-gateway/src/test/java/cn/agentplatform/gateway/application/GatewayPublishedRunProxyTest.java'
$OriginalSource = (& git -C $ApRoot show ('HEAD:' + $TestPath)) -join "`n"
$OriginalMethods = @([regex]::Matches($OriginalSource, '@Test\s+void\s+(\w+)\s*\(') | ForEach-Object { $_.Groups[1].Value })
$ProxyMethods = @($After | Where-Object { $_.classname -eq 'cn.agentplatform.gateway.application.GatewayPublishedRunProxyTest' })
$SourceInventory = Join-Path $AfterDir 'source-proxy-methods.json'
$SourceInventoryPresent = Test-Path -LiteralPath $SourceInventory
$ExpectedProxyMethods = if ($SourceInventoryPresent) { @(Get-Content -LiteralPath $SourceInventory -Raw | ConvertFrom-Json) } else { @() }
$MissingOriginalProxy = @($OriginalMethods | Where-Object { $_ -notin $ProxyMethods.method })
$MissingExpectedProxy = @($ExpectedProxyMethods | Where-Object { $_ -notin $ProxyMethods.method })
$Summary = [pscustomobject]@{
    reference='2026-10-28-EX-00/comparison-baseline.json'
    baselineSourceHead=$Reference.source_head
    baselineMethodCount=$Baseline.Count
    baselineAttempts=@($Baseline | Group-Object attempt | ForEach-Object { [pscustomobject]@{attempt=$_.Name; count=$_.Count} })
    afterRun=$AfterRun; afterExitCode=$Run.exitCode; afterMethodCount=$After.Count; afterReportCount=$Run.reportCount
    afterStatuses=@($After | Group-Object status | ForEach-Object { [pscustomobject]@{status=$_.Name; count=$_.Count} })
    expectedSourceClassCount=$Run.sourceTestClasses.Count; reportedClassCount=$ReportedClasses.Count
    missingClasses=$MissingClasses; unexpectedClasses=$UnexpectedClasses
    removedBaselineMethods=$MissingMethods
    classLevelCases=$ClassLevelCases
    addedOrRecoveredMethods=@($Differences | Where-Object { $_.before -eq 'UNREPORTED' })
    changedReportedMethods=@($Differences | Where-Object { $_.before -ne 'UNREPORTED' -and $_.after -ne 'MISSING' })
    newlyNonpassing=@($Differences | Where-Object { $_.after -in @('FAIL','ERROR','SKIP') -and $_.before -in @('UNREPORTED','PASS') })
    retainedNonpassing=@($Nonpass | Where-Object {
        $Key=$_.classname + '#' + $_.method
        $BeforeByKey.ContainsKey($Key) -and $StatusNames[$BeforeByKey[$Key].status] -ne 'PASS'
    } | Select-Object classname,method,status)
    originalProxyMethodCount=$OriginalMethods.Count
    proxySourceInventoryPresent=$SourceInventoryPresent
    newProxySourceMethods=@($ExpectedProxyMethods | Where-Object { $_ -notin $OriginalMethods })
    newProxyReportedMethods=@($ProxyMethods.method | Where-Object { $_ -notin $OriginalMethods })
    missingExpectedProxyMethods=$MissingExpectedProxy
    missingOriginalProxyMethods=$MissingOriginalProxy
    nonpassingProxyMethods=@($ProxyMethods | Where-Object { $_.status -ne 'PASS' })
    proxyReportedMethodCount=$ProxyMethods.Count
    classXmlCoverageComplete=($MissingClasses.Count -eq 0 -and $UnexpectedClasses.Count -eq 0)
    collectionComplete=($MissingClasses.Count -eq 0 -and $UnexpectedClasses.Count -eq 0 -and $MissingMethods.Count -eq 0 -and $ClassLevelCases.Count -eq 0 -and $MissingOriginalProxy.Count -eq 0 -and $MissingExpectedProxy.Count -eq 0)
    allTestsPassed=($Nonpass.Count -eq 0 -and $MissingClasses.Count -eq 0 -and $MissingMethods.Count -eq 0 -and $ClassLevelCases.Count -eq 0 -and $MissingOriginalProxy.Count -eq 0 -and $MissingExpectedProxy.Count -eq 0)
}
$Summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $AfterDir 'comparison.json') -Encoding UTF8
$Summary | Select-Object baselineMethodCount,afterMethodCount,afterReportCount,expectedSourceClassCount,reportedClassCount,
    collectionComplete,allTestsPassed,originalProxyMethodCount,proxyReportedMethodCount | ConvertTo-Json
