param(
    [Parameter(Mandatory)][string]$BackupDirectory,
    [ValidatePattern('^agent_platform_restore_[a-z0-9_]{1,40}$')][string]$TargetDatabase=('agent_platform_restore_'+(Get-Date -Format 'yyyyMMdd_HHmmss'))
)
$ErrorActionPreference='Stop'
$agentRoot=Split-Path -Parent $PSScriptRoot
$agentBackup=[IO.Path]::GetFullPath($BackupDirectory)
$agentBoundary=[IO.Path]::GetFullPath((Join-Path $agentRoot '.local/backups')).TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
if(-not $agentBackup.StartsWith($agentBoundary,[StringComparison]::OrdinalIgnoreCase)){throw '只允许恢复新工程.local/backups中的备份'}
$agentManifest=Get-Content -LiteralPath (Join-Path $agentBackup 'manifest.json') -Raw | ConvertFrom-Json
$agentDump=Join-Path $agentBackup 'database.dump'
if($agentManifest.formatVersion -ne 1 -or $agentManifest.database -ne 'agent_platform_dev_v12'){throw '备份格式或源数据库不匹配'}
if((Get-FileHash -LiteralPath $agentDump -Algorithm SHA256).Hash.ToLowerInvariant() -ne $agentManifest.sha256){throw '备份摘要不匹配，拒绝恢复'}
& (Join-Path $PSScriptRoot 'start-database.ps1')
$agentPg=Join-Path $agentRoot '.tools/postgresql-16.15/pgsql/bin'
$env:PGPASSWORD=$env:AGENT_DB_PASSWORD
try {
    $agentData=& (Join-Path $agentPg 'psql.exe') -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -c 'show data_directory'
    if($LASTEXITCODE -ne 0){throw '专用数据库不可用'}
    if([IO.Path]::GetFullPath($agentData.Trim()) -notin @([IO.Path]::GetFullPath((Join-Path $agentRoot '.local/pgdata')),[IO.Path]::GetFullPath('V:/.local/pgdata'))){throw '目标数据库实例不属于新工程'}
    $agentExists=& (Join-Path $agentPg 'psql.exe') -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -c "select 1 from pg_database where datname='$TargetDatabase'"
    if($LASTEXITCODE -ne 0 -or $agentExists -eq '1'){throw '目标数据库已存在或状态未知；禁止覆盖和清空'}
    & (Join-Path $agentPg 'createdb.exe') -h 127.0.0.1 -p 25432 -U agent_v12 --template=template0 $TargetDatabase
    if($LASTEXITCODE -ne 0){throw '创建独立恢复数据库失败'}
    & (Join-Path $agentPg 'pg_restore.exe') -h 127.0.0.1 -p 25432 -U agent_v12 -d $TargetDatabase --single-transaction --exit-on-error --no-owner --no-privileges $agentDump
    if($LASTEXITCODE -ne 0){throw '恢复事务失败；保留独立目标库供检查，不切换运行配置'}
    $agentTables=& (Join-Path $agentPg 'psql.exe') -X -h 127.0.0.1 -p 25432 -U agent_v12 -d $TargetDatabase -At -c "select count(*) from pg_tables where schemaname in ('app','engine','skill','model','monitor')"
    if($LASTEXITCODE -ne 0 -or [int]$agentTables -lt 1){throw '恢复后业务schema为空，需检查'}
    [ordered]@{targetDatabase=$TargetDatabase;restoredAt=[DateTimeOffset]::UtcNow.ToString('o');backupSha256=$agentManifest.sha256;businessTables=[int]$agentTables;runtimeConfigurationChanged=$false} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $agentBackup "restore-$TargetDatabase.json") -Encoding utf8
    Write-Output "独立恢复完成：$TargetDatabase，业务表$agentTables；未改变运行数据库连接"
} finally {Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue}
