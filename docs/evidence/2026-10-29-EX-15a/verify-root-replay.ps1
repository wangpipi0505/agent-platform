param([string]$ApRoot = (Get-Location).Path)
$ErrorActionPreference = 'Stop'
$evidence = Join-Path $ApRoot 'docs/evidence/2026-10-29-EX-15a'
$run = 'gateway-module-20261029-ex15a-02'
$proofPath = Join-Path $evidence "gateway/runs/$run/bounded-representation-proof.json"
$proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
function Require([bool]$Condition, [string]$Label) { if (-not $Condition) { throw $Label } }
function TextHash([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-','').ToLowerInvariant() }
    finally { $sha.Dispose() }
}
foreach ($inputName in @('baseline','actual','summary','fixtureSource')) {
    $inputSpec = $proof.inputs.$inputName
    Require ((Get-FileHash -LiteralPath (Join-Path $ApRoot $inputSpec.path)).Hash -eq $inputSpec.sha256) "input hash: $inputName"
}
$baseline = @(Get-Content -LiteralPath (Join-Path $ApRoot $proof.inputs.baseline.path) -Raw | ConvertFrom-Json)
$actual = @(Get-Content -LiteralPath (Join-Path $ApRoot $proof.inputs.actual.path) -Raw | ConvertFrom-Json)
Require ($baseline.Count -eq 15 -and $actual.Count -eq 15 -and $proof.rows.Count -eq 15) '15 original identities'
Require (@($baseline.key | Select-Object -Unique).Count -eq 15 -and @($actual.key | Select-Object -Unique).Count -eq 15) 'unique identities'
Require (@(Compare-Object $baseline.key $actual.key).Count -eq 0) 'same identities'
$metadata = @('key','classname','method','status','type','causes','firstProjectFrame','classEvent','xml')
$hashes = 0; $fields = 0; $literals = 0; $exact = 0; $bounded = 0; $excluded = 0
foreach ($row in $proof.rows) {
    $old = @($baseline | Where-Object key -CEQ $row.key)
    $new = @($actual | Where-Object key -CEQ $row.key)
    Require ($old.Count -eq 1 -and $new.Count -eq 1) 'one original identity'
    $old = $old[0]; $new = $new[0]
    Require (@($old.PSObject.Properties).Count -eq 13 -and @($new.PSObject.Properties).Count -eq 13) 'same 13-field schema size'
    foreach ($name in $metadata) {
        Require (($old.$name | ConvertTo-Json -Depth 20 -Compress) -ceq ($new.$name | ConvertTo-Json -Depth 20 -Compress)) "metadata: $name"
    }
    foreach ($name in @('message','detail')) {
        Require ((TextHash $old.$name) -eq $old.("${name}Sha256")) 'baseline stored hash'
        Require ((TextHash $new.$name) -eq $new.("${name}Sha256")) 'actual stored hash'
        $hashes += 2
    }
    if ($row.category -eq 'excluded_missing_class_change') {
        Require ($row.key -ceq 'cn.agentplatform.gateway.application.PublishedMetadataSmokeLauncherTest#launchTwoIsolatedJvmFixturesWithFiniteDeadline') 'exact excluded method'
        $excluded++; continue
    }
    if ($row.category -eq 'exact_original_record') { $exact++ }
    else { Require ($row.category -in @('fixture_uuid_literal_only','spring_object_identity_literals_only')) 'bounded category'; $bounded++ }
    foreach ($field in $row.fields) {
        $name = $field.field
        $text = [string]$old.$name
        foreach ($replacement in $field.literalReplacements) {
            $from = [string]$replacement.oldLiteral; $to = [string]$replacement.newLiteral
            Require ($from.Length -gt 0 -and $to.Length -gt 0) 'nonempty literal'
            Require ([regex]::Matches($text,[regex]::Escape($from)).Count -eq 1) 'one old literal'
            Require ([regex]::Matches([string]$new.$name,[regex]::Escape($to)).Count -eq 1) 'one actual literal'
            Require (-not $text.Contains($to) -and -not ([string]$new.$name).Contains($from)) 'inverse literal absent'
            Require ($text.IndexOf($from,[StringComparison]::Ordinal) -eq $replacement.baselineOffsetUtf16) 'baseline offset'
            Require (([string]$new.$name).IndexOf($to,[StringComparison]::Ordinal) -eq $replacement.actualOffsetUtf16) 'actual offset'
            $text = $text.Replace($from,$to); $literals++
        }
        Require ([string]::Equals($text,[string]$new.$name,[StringComparison]::Ordinal)) 'entire remaining field'
        Require ((TextHash $text) -eq $field.afterExactReplacementsSha256) 'replay field hash'
        $fields++
    }
}
Require ($hashes -eq 60 -and $fields -eq 28 -and $literals -eq 34 -and $exact -eq 3 -and $bounded -eq 11 -and $excluded -eq 1) 'replay counts'
$summary = Get-Content -LiteralPath (Join-Path $ApRoot $proof.inputs.summary.path) -Raw | ConvertFrom-Json
Require ($summary.nativeMavenExit -eq 1 -and $summary.collectorExit -eq 1 -and -not $summary.original15RawReasonGate -and -not $summary.regressionGate -and -not $summary.allTestsPassed) 'raw failure gates preserved'
$result = [ordered]@{
    reviewer = 'root'; checkedAtUtc = [DateTime]::UtcNow.ToString('o'); powershellVersion = $PSVersionTable.PSVersion.ToString()
    pureFileReplay = $true; proofSha256 = (Get-FileHash -LiteralPath $proofPath).Hash.ToLowerInvariant()
    identities = 15; originalTextHashesVerified = $hashes; entireFieldsCompared = $fields
    exactRecords = $exact; boundedRepresentationRecords = $bounded; exactLiteralOccurrences = $literals
    excludedTechnicalAttributionRecords = $excluded; replayPassed = $true
    originalSummarySha256 = (Get-FileHash -LiteralPath (Join-Path $ApRoot $proof.inputs.summary.path)).Hash.ToLowerInvariant()
    originalRawReasonGate = $false; originalRegressionGate = $false; allTestsPassed = $false
    note = 'The excluded missing-class change is not normalized or accepted by this replay; it needs its separate technical review.'
}
$result | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $evidence 'root-replay.json') -Encoding UTF8
$result | ConvertTo-Json -Depth 10
