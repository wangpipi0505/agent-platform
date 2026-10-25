param([switch]$AfterAppend)
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
function Read-Json($relative) { Get-Content -LiteralPath (Join-Path $taskRoot $relative) -Raw | ConvertFrom-Json }
function Sha($path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
$prefix = 'docs/evidence/iam-stage-1-ownership-fix/'
$manifest = Read-Json ($prefix + 'manifest.json')
$source = Read-Json ($prefix + 'source-after.json')
$original = Read-Json 'docs/evidence/iam-stage-1/source-after.json'
$target = Read-Json ($prefix + 'target-sha256.json')
$prior = Read-Json ($prefix + 'prior-evidence-after.json')
foreach ($entry in $manifest.PSObject.Properties) {
    if ((Sha (Join-Path $taskRoot $entry.Name)) -ne $entry.Value) { throw "Manifest mismatch: $($entry.Name)" }
}
foreach ($entry in $source.PSObject.Properties) {
    foreach ($copy in @('', ($prefix + 'frozen/'), ($prefix + 'applied-bytes/'))) {
        if ((Sha (Join-Path $taskRoot ($copy + $entry.Name))) -ne $entry.Value) { throw "Source mismatch: $copy$($entry.Name)" }
    }
}
foreach ($entry in $original.PSObject.Properties) {
    if ((Sha (Join-Path $taskRoot ($prefix + 'baseline/' + $entry.Name))) -ne $entry.Value) { throw "Baseline mismatch: $($entry.Name)" }
}
foreach ($entry in $target.PSObject.Properties) {
    if ((Sha (Join-Path $taskRoot ('admin-common/target/' + $entry.Name))) -ne $entry.Value) { throw "Target mismatch: $($entry.Name)" }
}
foreach ($entry in $prior.PSObject.Properties) {
    if ($AfterAppend -and $entry.Name -eq 'docs/evidence/review-iam-stage-1.md') {
        $before = Read-Json 'docs/evidence/iam-stage-1-ownership-review/verified-before-append.json'
        $bytes = [IO.File]::ReadAllBytes((Join-Path $taskRoot $entry.Name))
        $digest = [Security.Cryptography.SHA256]::Create().ComputeHash([byte[]]$bytes[0..($before.reportBytesBeforeAppend - 1)])
        $prefixSha = ([BitConverter]::ToString($digest)).Replace('-', '').ToLowerInvariant()
        if ($prefixSha -ne $entry.Value) { throw 'Original review report prefix changed' }
        continue
    }
    if ((Sha (Join-Path $taskRoot $entry.Name)) -ne $entry.Value) { throw "Prior mismatch before authorized append: $($entry.Name)" }
}
$changed = @($source.PSObject.Properties | Where-Object { $original.($_.Name) -ne $_.Value } | ForEach-Object Name)
if ($changed.Count -ne 2) { throw 'Expected exactly two changed common inputs' }
$report = Join-Path $taskRoot 'docs/evidence/review-iam-stage-1.md'
[ordered]@{
    manifestFilesVerified = @($manifest.PSObject.Properties).Count
    currentFrozenAppliedFilesVerified = @($source.PSObject.Properties).Count
    originalBaselineFilesVerified = @($original.PSObject.Properties).Count
    targetFilesVerifiedUnchanged = @($target.PSObject.Properties).Count
    priorEvidenceVerified = @($prior.PSObject.Properties).Count
    originalReviewPrefixPreserved = [bool]$AfterAppend
    changedFromTask1 = $changed
    patchSha256 = Sha (Join-Path $taskRoot ($prefix + 'full.patch'))
    reportBytesBeforeAppend = $(if ($AfterAppend) { $before.reportBytesBeforeAppend } else { (Get-Item -LiteralPath $report).Length })
    reportSha256BeforeAppend = $(if ($AfterAppend) { $before.reportSha256BeforeAppend } else { Sha $report })
    currentReportSha256 = Sha $report
} | ConvertTo-Json -Depth 4
