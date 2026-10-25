$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
function Read-Json($relative) {
    Get-Content -LiteralPath (Join-Path $taskRoot $relative) -Raw | ConvertFrom-Json
}
function Sha($path) { (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
$artifact = Read-Json 'docs/evidence/iam-stage-1/artifact-sha256.json'
$source = Read-Json 'docs/evidence/iam-stage-1/source-after.json'
$target = Read-Json 'docs/evidence/iam-stage-1/target-sha256.json'
foreach ($entry in $artifact.PSObject.Properties) {
    if ((Sha (Join-Path $taskRoot $entry.Name)) -ne $entry.Value) { throw "Artifact mismatch: $($entry.Name)" }
}
foreach ($entry in $source.PSObject.Properties) {
    foreach ($prefix in @('', 'docs/evidence/iam-stage-1/frozen/', 'docs/evidence/iam-stage-1/applied-bytes/')) {
        if ((Sha (Join-Path $taskRoot ($prefix + $entry.Name))) -ne $entry.Value) { throw "Source mismatch: $prefix$($entry.Name)" }
    }
}
foreach ($entry in $target.PSObject.Properties) {
    if ((Sha (Join-Path $taskRoot ('admin-common/target/' + $entry.Name))) -ne $entry.Value) { throw "Target mismatch: $($entry.Name)" }
}
$inventory = @(Read-Json 'docs/evidence/iam-stage-1/controller-inventory.json')
$routes = @(Read-Json 'admin-common/src/main/resources/agent-service-authority.json')
$inventoryKeys = @($inventory | ForEach-Object { "$($_.target)|$($_.method)|$($_.template)" } | Sort-Object -Unique)
$routeKeys = @($routes | Where-Object { !$_.source.StartsWith('springdoc-') } | ForEach-Object { "$($_.target)|$($_.method)|$($_.template)" } | Sort-Object -Unique)
if (Compare-Object $inventoryKeys $routeKeys) { throw 'Inventory/catalog difference' }
$summary = [ordered]@{
    artifactFilesVerified = @($artifact.PSObject.Properties).Count
    sourceFrozenAppliedFilesVerified = @($source.PSObject.Properties).Count
    targetFilesVerifiedUnchanged = @($target.PSObject.Properties).Count
    patchSha256 = Sha (Join-Path $taskRoot 'docs/evidence/iam-stage-1/full.patch')
    designSha256 = Sha (Join-Path $taskRoot 'contracts/snapshot/detailed-design-v1.2.md')
    inventoryUniqueTemplates = $inventoryKeys.Count
    catalogRows = $routes.Count
    modes = @($routes | Group-Object mode | Select-Object Name,Count)
}
$summary | ConvertTo-Json -Depth 5
