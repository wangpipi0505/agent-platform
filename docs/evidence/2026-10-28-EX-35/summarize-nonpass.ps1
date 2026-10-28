param([Parameter(Mandatory=$true)][string]$Run)
$ErrorActionPreference = 'Stop'
$RunDir = Join-Path $PSScriptRoot ('runs/' + $Run)
$Facts = @(foreach ($Report in Get-ChildItem -LiteralPath (Join-Path $RunDir 'xml') -Filter 'TEST-*.xml') {
    $Doc = [xml](Get-Content -LiteralPath $Report.FullName -Raw)
    foreach ($Case in @($Doc.testsuite.testcase)) {
        $Failure = if ($null -ne $Case.failure) { $Case.failure } else { $Case.error }
        if ($null -eq $Failure) { continue }
        $Trace = [string]$Failure.InnerText
        [pscustomobject]@{
            classname=[string]$Case.classname; method=[string]$Case.name; type=[string]$Failure.type
            causeTypes=@([regex]::Matches($Trace, 'Caused by: ([A-Za-z0-9_.$]+)') |
                ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique)
            businessFrames=@($Trace -split "`n" | Where-Object { $_ -match '^\s*at cn\.agentplatform\.' } | Select-Object -First 6)
            hasNoSuchFile=$Trace.Contains('NoSuchFileException')
            hasMissingBean=$Trace.Contains('NoSuchBeanDefinitionException')
            hasDevelopmentModeConflict=$Trace.Contains('AGENT_DEV_AUTH')
            hasPortBind=$Trace.Contains('BindException')
        }
    }
})
$Facts | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $RunDir 'nonpass-safe-facts.json') -Encoding UTF8
$Facts | Where-Object { $_.classname -in @('cn.agentplatform.common.authority.GatewayPublishedChainPostgresTest',
    'cn.agentplatform.common.authority.GatewayRunStatusChainPostgresTest','cn.agentplatform.gateway.IntegrationRateLimitTest') } |
    Select-Object -First 3 | ConvertTo-Json -Depth 6
