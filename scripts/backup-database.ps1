param([ValidateSet('agent_platform_dev_v12')][string]$Database='agent_platform_dev_v12')
$ErrorActionPreference='Stop'
$agentRoot=Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'start-database.ps1')
$agentPg=Join-Path $agentRoot '.tools/postgresql-16.15/pgsql/bin'
$agentStamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$agentBackup=Join-Path $agentRoot ".local/backups/$agentStamp"
New-Item -ItemType Directory -Path $agentBackup | Out-Null
$env:PGPASSWORD=$env:AGENT_DB_PASSWORD
try {
    $agentData=& (Join-Path $agentPg 'psql.exe') -X -h 127.0.0.1 -p 25432 -U agent_v12 -d $Database -At -c 'show data_directory'
    if($LASTEXITCODE -ne 0){throw '专用数据库不可用'}
    $agentExpected=@([IO.Path]::GetFullPath((Join-Path $agentRoot '.local/pgdata')), [IO.Path]::GetFullPath('V:/.local/pgdata'))
    if([IO.Path]::GetFullPath($agentData.Trim()) -notin $agentExpected){throw '数据库目录不属于本工程，拒绝备份'}
    $agentDump=Join-Path $agentBackup 'database.dump'
    # pg_dump以一致性快照导出。只写本工程备份，不触碰其他数据库或服务。
    & (Join-Path $agentPg 'pg_dump.exe') -h 127.0.0.1 -p 25432 -U agent_v12 -d $Database --format=custom --no-owner --no-privileges --file=$agentDump
    if($LASTEXITCODE -ne 0){throw '备份失败；不生成可恢复声明'}
    $agentManifest=[ordered]@{formatVersion=1;database=$Database;createdAt=[DateTimeOffset]::UtcNow.ToString('o');sha256=(Get-FileHash -LiteralPath $agentDump -Algorithm SHA256).Hash.ToLowerInvariant();bytes=(Get-Item -LiteralPath $agentDump).Length;postgres='16.15';scope='PostgreSQL only; encrypted vault, master key and MinIO objects require separate protected backup'}
    $agentManifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $agentBackup 'manifest.json') -Encoding utf8
    Write-Output "数据库一致性备份已生成：$agentBackup"
} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
