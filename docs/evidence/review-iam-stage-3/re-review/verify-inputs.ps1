$ErrorActionPreference = 'Stop'
$workspace = 'G:\207\项目\算法重组工具\代码\agent-platform'
$fix = Join-Path $workspace 'docs\evidence\iam-stage-3-review-fixes'
$final = Join-Path $fix 'i3'
$review = Join-Path $workspace 'docs\evidence\review-iam-stage-3\re-review'
$sourceMap = Get-Content -LiteralPath (Join-Path $final 'source-after.json') -Raw | ConvertFrom-Json
$sourceBases = @($workspace, (Join-Path $final 'frozen'), (Join-Path $final 'applied'), (Join-Path $final 'i3-applied'))
foreach ($entry in $sourceMap.PSObject.Properties) {
    foreach ($base in $sourceBases) {
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $base $entry.Name)).Hash.ToLowerInvariant()
        if ($actual -ne $entry.Value) { throw "Source differs: $base/$($entry.Name)" }
    }
}
$targetMap = Get-Content -LiteralPath (Join-Path $final 'target-after.json') -Raw | ConvertFrom-Json
foreach ($entry in $targetMap.PSObject.Properties) {
    foreach ($base in @((Join-Path $workspace 'admin-common\target'), (Join-Path $final 'frozen-target'))) {
        $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $base $entry.Name)).Hash.ToLowerInvariant()
        if ($actual -ne $entry.Value) { throw "Target differs: $base/$($entry.Name)" }
    }
}
$xmlAudit = @()
foreach ($relative in @('red-selected-xml', 'i3\red-xml', 'i3\green-named-xml', 'i3\final-common-xml')) {
    $suites = @()
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $fix $relative) -Filter '*.xml') {
        [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
        $suites += [pscustomobject]@{
            file=$file.Name
            sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
            tests=[int]$xml.testsuite.tests
            failures=[int]$xml.testsuite.failures
            errors=[int]$xml.testsuite.errors
            skipped=[int]$xml.testsuite.skipped
            new_testcases=@($xml.testsuite.testcase | Where-Object name -Like 'reviewI*' | ForEach-Object name)
        }
    }
    $xmlAudit += [pscustomobject]@{
        directory=$relative
        suites=$suites
        totals=@(
            ($suites | Measure-Object tests -Sum).Sum,
            ($suites | Measure-Object failures -Sum).Sum,
            ($suites | Measure-Object errors -Sum).Sum,
            ($suites | Measure-Object skipped -Sum).Sum)
    }
}
$logAudit = @()
foreach ($relative in @('red.log', 'i3\red.log', 'i3\green-named.log', 'i3\final-common.log')) {
    foreach ($match in Select-String -LiteralPath (Join-Path $fix $relative) -Pattern 'Tests run:|<<< FAILURE|Expecting|HttpCurrentAuthorityClient.approver|BUILD FAILURE|BUILD SUCCESS') {
        $logAudit += [pscustomobject]@{file=$relative;line=$match.LineNumber;text=$match.Line}
    }
}
$patchHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $final 'full.patch')).Hash.ToLowerInvariant()
if ($patchHash -ne '7c7e3c3f4e51e429c02e8b187ba32255ebb1a1058a8f568f5ba4de5d7e42f2c2') { throw 'Patch mismatch' }
$report = Join-Path $workspace 'docs\evidence\review-iam-stage-3.md'
$prefixHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $report).Hash.ToLowerInvariant()
if ($prefixHash -ne 'df842cf0e5b1d987f077419e4f3b9ba2794c7307bdda7eabb35ca605fbc4e9d9') { throw 'Original review prefix changed' }
$audit = @{
    patch_sha256=$patchHash
    original_review_prefix_sha256=$prefixHash
    original_review_prefix_bytes=(Get-Item -LiteralPath $report).Length
    source_files_each=@($sourceMap.PSObject.Properties).Count
    source_roots=4
    source_sha_equal=$true
    target_files_each=@($targetMap.PSObject.Properties).Count
    target_roots=2
    target_sha_equal=$true
    suites_not_rerun=$true
    probe_not_run=$true
    xml=$xmlAudit
}
$audit | ConvertTo-Json -Depth 9 | Set-Content -LiteralPath (Join-Path $review 'verified-inputs.json') -Encoding utf8
$logAudit | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $review 'original-log-extract.json') -Encoding utf8
[pscustomobject]@{source_each=$audit.source_files_each;target_each=$audit.target_files_each;original_prefix_bytes=$audit.original_review_prefix_bytes;xml=@($xmlAudit | Select-Object directory,totals)} | ConvertTo-Json -Depth 6
