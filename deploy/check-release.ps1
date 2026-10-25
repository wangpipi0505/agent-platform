param([Parameter(Mandatory=$true)][string]$EnvFile)
$ErrorActionPreference = 'Stop'
# config is a local parser only. Capture its JSON because resolved environment may contain secrets.
$composeJson = & docker compose --env-file $EnvFile -f (Join-Path $PSScriptRoot 'compose.yaml') --profile acceptance --profile ops config --format json
if ($LASTEXITCODE -ne 0) { throw 'Compose configuration unresolved; no deployment attempted.' }
$config = ($composeJson -join "`n") | ConvertFrom-Json
foreach ($entry in $config.services.PSObject.Properties) {
    $image = [string]$entry.Value.image
    if ($image -notmatch '^.+@sha256:[a-f0-9]{64}$' -or $image -match '@sha256:0{64}$') { throw "Explicit non-placeholder image digest required for $($entry.Name)." }
    if ($entry.Value.environment.AGENT_DEV_AUTH -and $entry.Value.environment.AGENT_DEV_AUTH -ne 'false') { throw 'dev-auth is forbidden.' }
    if ($entry.Value.environment.AGENT_RELEASE_GATE -and $entry.Value.environment.AGENT_RELEASE_GATE -ne 'IAM_SCOPE_ACCEPTED') { throw 'IAM/service scope acceptance gate is blocked.' }
}
Write-Output 'Release configuration checks passed. No build, start, cluster connection, or production acceptance performed.'
