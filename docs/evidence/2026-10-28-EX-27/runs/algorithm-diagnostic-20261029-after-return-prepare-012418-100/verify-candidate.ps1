param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$relativeSource = 'skill-registry/src/test/java/cn/agentplatform/skill/infra/AlgorithmOffloadInterruptionTest.java'
$sourcePath = Join-Path $RepositoryRoot $relativeSource
$sourceText = [IO.File]::ReadAllText($sourcePath).Replace("`r`n", "`n")
$headBefore = (& git -C $RepositoryRoot rev-parse HEAD) -join ''
if ($LASTEXITCODE -ne 0) { throw 'Cannot read repository HEAD' }
$baselineText = (& git -C $RepositoryRoot show ('HEAD:' + $relativeSource)) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Cannot read baseline test source' }

function Get-Assertions([string]$Text) {
    $normalized = $Text.Replace('quiescentAtFirstCheck', 'scoped.get().algorithmIoQuiescent()')
    foreach ($match in [regex]::Matches($normalized, '(?s)\bassert(?:True|False|Equals|NotNull)\s*\(.*?\);')) {
        [regex]::Replace($match.Value, '\s+', '')
    }
}

function Get-Waits([string]$Text) {
    foreach ($match in [regex]::Matches($Text, '(?:\.await|\.join|Thread\.sleep|TimeUnit\.SECONDS\.toNanos)\([^;]*?\)')) {
        [regex]::Replace($match.Value, '\s+', '')
    }
}

$baselineAssertions = @(Get-Assertions $baselineText)
$candidateAssertions = @(Get-Assertions $sourceText)
$baselineWaits = @(Get-Waits $baselineText)
$candidateWaits = @(Get-Waits $sourceText)
$methodPattern = '(?m)^    void ([A-Za-z0-9]+)\([^)]*\)'
$baselineMethods = @([regex]::Matches($baselineText, $methodPattern) | ForEach-Object { $_.Groups[1].Value })
$candidateMethods = @([regex]::Matches($sourceText, $methodPattern) | ForEach-Object { $_.Groups[1].Value })
$unconfirmedPattern = '(?s)    @Test\n    void unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit\(\).*?(?=\n    /\*\*)'
$baselineUnconfirmed = [regex]::Match($baselineText, $unconfirmedPattern).Value
$candidateUnconfirmed = [regex]::Match($sourceText, $unconfirmedPattern).Value
$peerMarker = '    static final class LatchedS3 implements AutoCloseable {'
$baselinePeer = $baselineText.Substring($baselineText.IndexOf($peerMarker))
$candidatePeer = $sourceText.Substring($sourceText.IndexOf($peerMarker))
$baselinePeer = $baselinePeer.TrimEnd()
$candidatePeer = $candidatePeer.TrimEnd()

& git -C $RepositoryRoot diff --check -- $relativeSource
$diffCheckExit = $LASTEXITCODE
$headAfter = (& git -C $RepositoryRoot rev-parse HEAD) -join ''
if ($LASTEXITCODE -ne 0) { throw 'Cannot reread repository HEAD' }
$checks = [ordered]@{
    headStable = $headBefore -ceq $headAfter
    originalAssertionsIdenticalAfterFirstCheckAlias = ($baselineAssertions -join "`0") -ceq ($candidateAssertions -join "`0")
    originalAssertionCountNonzero = $baselineAssertions.Count -gt 0
    originalWaitTokensUnchanged = ($baselineWaits -join "`0") -ceq ($candidateWaits -join "`0")
    originalTestMethodsUnchanged = ($baselineMethods -join "`0") -ceq ($candidateMethods -join "`0")
    exactlyTwoTestDeclarations = [regex]::Matches($sourceText, '(?m)^    @(?:Test|ParameterizedTest)\s*$').Count -eq 2
    originalParameterOrderUnchanged = $sourceText.Contains('@ValueSource(strings = {"PUT", "GET", "GET_BODY"})')
    unconfirmedVectorUnchanged = $baselineUnconfirmed.Length -gt 0 -and $baselineUnconfirmed -ceq $candidateUnconfirmed
    finitePeerUnchanged = $baselinePeer -ceq $candidatePeer
    noDisabledOrAssumptionAdded = -not [regex]::IsMatch($sourceText, '@Disabled|Assumptions\.|assumeTrue\(|assumeFalse\(')
    explicitDefaultOffDiagnostic = $sourceText.Contains('Boolean.getBoolean("ex27.algorithmInterruptDiagnostic")')
    firstBooleanCapturedBeforeAssert = [regex]::IsMatch($sourceText, '(?s)boolean quiescentAtFirstCheck = scoped\.get\(\)\.algorithmIoQuiescent\(\);\s*diagnostic\.firstCheck\(quiescentAtFirstCheck\);\s*assertTrue\(\s*quiescentAtFirstCheck,')
    gitDiffCheckPassed = $diffCheckExit -eq 0
}
$allPassed = @($checks.Values | Where-Object { -not $_ }).Count -eq 0
$result = [ordered]@{
    phase = 'STATIC_PREPARATION_ONLY_NOT_COMPILED_OR_RUN'
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    powershellVersion = $PSVersionTable.PSVersion.ToString()
    head = $headAfter
    sourceSha256 = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
    originalAssertionCount = $baselineAssertions.Count
    candidateAssertionCount = $candidateAssertions.Count
    originalWaitTokenCount = $baselineWaits.Count
    candidateWaitTokenCount = $candidateWaits.Count
    testDeclarations = $candidateMethods
    reportIdentities = @(
        'interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[1]:PUT',
        'interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[2]:GET',
        'interruptedSdkWaitStopsOriginalOperationBeforeReturningAndNeverRacesCleanup(String)[3]:GET_BODY',
        'unconfirmedStopRetainsOriginalFutureUntilActualFiniteSourceExit'
    )
    checks = $checks
    allStaticChecksPassed = $allPassed
    compiled = $false
    javaMavenPgHttpRun = $false
}
$json = $result | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'candidate-static-check.json'), $json, (New-Object Text.UTF8Encoding($false)))
Write-Output $json
if (-not $allPassed) { exit 1 }
