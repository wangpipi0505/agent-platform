# =============================================================================
# G5-0 回退脚本（deploy/upgrade-20261118/rollback.ps1）——与 rollback.sh 同构
#
# 用法：
#   .\rollback.ps1 -Database drill_g50_b -BackupFile <abs>\drill_g50_b-pre-upgrade-<stamp>.dump -Force
#
# 唯一受支持回退路径 = 备份恢复（pg_restore）。Flyway 社区版没有 downgrade，
# 本脚本以“删库重建 + pg_restore”把库恢复到备份时点（即升级前快照）。
#
# 安全护栏：
#   - 必须 -Force 才会执行 drop/create（破坏性动作显式确认）。
#   - 拒绝 postgres/template0/template1 等系统库。
#   - 备份文件必须存在，且（若同名 manifest.json 在旁）先校验 sha256。
#   - 只操作显式传入的单个库；绝不触碰共享 test 库与其他卡的验证库。
# =============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Database,
    [Parameter(Mandatory = $true)][string]$BackupFile,
    [switch]$Force,
    [switch]$VerifyBaseline
)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch {}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$SqlDir = Join-Path $ScriptDir 'sql'
$PgHost = '127.0.0.1'
$PgPort = '25432'

function Die([string]$msg) { Write-Error "[rollback][FATAL] $msg"; exit 1 }
function Log([string]$msg) { Write-Output "[rollback] $msg" }

if (-not $Force) { Die '破坏性回退必须显式 -Force 确认（drop + recreate + pg_restore）' }
if ($Database -in @('postgres', 'template0', 'template1')) { Die "拒绝操作系统库: $Database" }
if (-not (Test-Path -LiteralPath $BackupFile)) { Die "备份文件不存在: $BackupFile" }

function EnvVar([string]$name) {
    $line = Select-String -LiteralPath (Join-Path $Root '.env') -Pattern ("^$name=") |
        Select-Object -Last 1
    if ($line) { return ($line.Line -replace "^$name=", '' -replace "`r", '') }
    return $null
}
if (-not $env:AGENT_DB_USER)     { $env:AGENT_DB_USER     = EnvVar 'AGENT_DB_USER' }
if (-not $env:AGENT_DB_PASSWORD) { $env:AGENT_DB_PASSWORD = EnvVar 'AGENT_DB_PASSWORD' }
if (-not $env:AGENT_DB_PASSWORD) { Die 'AGENT_DB_PASSWORD 为空：请确认仓库根 .env 存在' }

$PgBin = $env:AGENT_PG_BIN
if (-not $PgBin) {
    foreach ($cand in @(
        (Join-Path $Root '.tools/postgresql-16.15/pgsql/bin'),
        (Join-Path (Split-Path -Parent $Root) 'agent-platform/.tools/postgresql-16.15/pgsql/bin'))) {
        if ((Test-Path (Join-Path $cand 'psql.exe'))) { $PgBin = $cand; break }
    }
}
if (-not $PgBin) { Die '找不到 psql/pg_restore：设 AGENT_PG_BIN=<pgsql/bin> 后重试' }
$Psql = Join-Path $PgBin 'psql.exe'
$PgRestore = Join-Path $PgBin 'pg_restore.exe'
$env:PGPASSWORD = $env:AGENT_DB_PASSWORD
$env:PGCLIENTENCODING = 'UTF8'

& $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d postgres -At -c 'select 1;' | Out-Null
if ($LASTEXITCODE -ne 0) { Die "专用 PG 不可达: $PgHost`:$PgPort" }

# ---- 备份完整性校验（manifest 在旁则校验 sha256） ----
$manifest = [IO.Path]::ChangeExtension($BackupFile, '.manifest.json')
if (Test-Path -LiteralPath $manifest) {
    $want = (Select-String -LiteralPath $manifest -Pattern '"sha256":\s*"([0-9a-f]+)"').Matches[0].Groups[1].Value
    $got = (Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($want -ne $got) { Die "备份 sha256 不匹配（manifest=$want 实际=$got）——拒绝用损坏备份回退" }
    Log '备份 sha256 校验通过（manifest 在旁）'
} else {
    Log "警告：无 manifest.json，跳过 sha256 校验（$manifest 不存在）"
}

# ---- drop + create + restore ----
$n = & $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d postgres -At -c `
    "select count(*) from pg_database where datname='$Database';"
if ("$n".Trim() -eq '1') {
    Log "drop database: $Database"
    & $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d postgres -c "drop database `"$Database`";" | Out-Null
}
Log "create database: $Database"
& $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d postgres -c "create database `"$Database`";" | Out-Null

Log "pg_restore <- $BackupFile"
& $PgRestore -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d $Database `
    --no-owner --no-privileges --exit-on-error "$BackupFile"
if ($LASTEXITCODE -ne 0) { Die 'pg_restore 失败（回退未完成；库内状态为半恢复，需整库重放本步骤）' }

# ---- 回退后验证 ----
Log '回退后 flyway_schema_history 摘要：'
& $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d $Database -At -f (Join-Path $SqlDir 'history-summary.sql')

if ($VerifyBaseline) {
    Log 'verify-baseline 断言（仅演练基线库适用；dev 库请按 CHECKLIST 与升级前留档 diff）：'
    $out = & $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d $Database -At -f (Join-Path $SqlDir 'verify-baseline.sql')
    $out | ForEach-Object { Write-Output $_ }
    if ($out -match '\|f\s*$') { Die 'verify-baseline 存在失败断言（行尾 f）' }
    Log 'verify-baseline 全部通过'
}

Log "完成：$Database 已恢复到备份时点（回退=备份恢复路径）"
