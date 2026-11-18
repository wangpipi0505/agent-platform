# =============================================================================
# G5-0 存量升级脚本（deploy/upgrade-20261118/upgrade.ps1）——与 upgrade.sh 同构
#
# 用法：
#   # 1) 构造“存量”基线（flyway target 钉在升级前版本：engine V228 / app V121 / skill V320）
#   .\upgrade.ps1 -Database drill_g50_b -CreateDatabase -Pin "engine=228,app=121,skill=320"
#
#   # 2) 全链升级到链头（升级动作前自动 pg_dump 一致性快照 -> 回退唯一锚点）
#   .\upgrade.ps1 -Database drill_g50_b -BackupDir <abs-backup-dir> -VerifyHead
#
# 环境约定：
#   - AGENT_DB_USER / AGENT_DB_PASSWORD 只从 .env 读（脚本自动加载仓库根 .env），不进命令行/日志。
#   - 专用 PG：127.0.0.1:25432（与 EngineTestDatabase 同守卫）。
#   - PG 客户端定位：env AGENT_PG_BIN 优先，其次 <repoRoot>\.tools\postgresql-16.15\pgsql\bin，
#     再次相邻主工作树 agent-platform 同名目录。
#   - Flyway 与各服务同源（flyway-core 11.7.2），经 scripts/g50/DrillFlyway.java 单文件启动；
#     classpath 缺失时自动 mvn dependency:build-classpath 生成。
#
# 回退：本脚本不做任何回退。Flyway 社区版无 downgrade——回退唯一受支持路径是
#   rollback.ps1 的“备份恢复”（pg_restore），见 CHECKLIST.md。
# =============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Database,
    [string]$Pin,
    [switch]$CreateDatabase,
    [string]$BackupDir,
    [switch]$VerifyHead
)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch {}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$SqlDir = Join-Path $ScriptDir 'sql'
$PgHost = '127.0.0.1'
$PgPort = '25432'

function Die([string]$msg) { Write-Error "[upgrade][FATAL] $msg"; exit 1 }
function Log([string]$msg) { Write-Output "[upgrade] $msg" }

if ($Database -in @('postgres', 'template0', 'template1')) { Die "拒绝操作系统库: $Database" }

# ---- .env（密钥只从 env；兼容 CRLF） ----
function EnvVar([string]$name) {
    $line = Select-String -LiteralPath (Join-Path $Root '.env') -Pattern ("^$name=") |
        Select-Object -Last 1
    if ($line) { return ($line.Line -replace "^$name=", '' -replace "`r", '') }
    return $null
}
if (-not $env:AGENT_DB_USER)     { $env:AGENT_DB_USER     = EnvVar 'AGENT_DB_USER' }
if (-not $env:AGENT_DB_PASSWORD) { $env:AGENT_DB_PASSWORD = EnvVar 'AGENT_DB_PASSWORD' }
if (-not $env:AGENT_DB_PASSWORD) { Die 'AGENT_DB_PASSWORD 为空：请确认仓库根 .env 存在（凭据只从 .env 读）' }

# ---- PG 客户端定位 ----
$PgBin = $env:AGENT_PG_BIN
if (-not $PgBin) {
    foreach ($cand in @(
        (Join-Path $Root '.tools/postgresql-16.15/pgsql/bin'),
        (Join-Path (Split-Path -Parent $Root) 'agent-platform/.tools/postgresql-16.15/pgsql/bin'))) {
        if ((Test-Path (Join-Path $cand 'psql.exe'))) { $PgBin = $cand; break }
    }
}
if (-not $PgBin) { Die '找不到 psql/pg_dump：设 AGENT_PG_BIN=<pgsql/bin> 后重试' }
$Psql = Join-Path $PgBin 'psql.exe'
$PgDump = Join-Path $PgBin 'pg_dump.exe'
$env:PGPASSWORD = $env:AGENT_DB_PASSWORD
$env:PGCLIENTENCODING = 'UTF8'

# ---- 连通性 + 专用实例守卫 ----
$serverPort = & $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d postgres -At -c 'show port;'
if ($LASTEXITCODE -ne 0) { Die "专用 PG 不可达: $PgHost`:$PgPort" }
if ("$serverPort".Trim() -ne $PgPort) { Die "端口不符（期望 $PgPort，实际 $serverPort）" }

$JdbcUrl = "jdbc:postgresql://${PgHost}:${PgPort}/$Database"

function Test-DbExists([string]$db) {
    $n = & $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d postgres -At -c `
        "select count(*) from pg_database where datname='$db';"
    return ("$n".Trim() -eq '1')
}

# ---- 建库（可选） ----
if ($CreateDatabase) {
    if (-not (Test-DbExists $Database)) {
        & $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d postgres -c "create database `"$Database`";" | Out-Null
        Log "已建库: $Database"
    } else {
        Log "库已存在（不重建）: $Database"
    }
}
if (-not (Test-DbExists $Database)) { Die "库不存在: $Database（构造基线请加 -CreateDatabase）" }

# ---- Flyway runner + classpath ----
$env:G50_REPO_ROOT = $Root
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
}
$Java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path $Java)) { Die "找不到 java：请设置 JAVA_HOME=<jdk21>" }

$CpFile = Join-Path $Root '.local/g50/engine-cp.txt'
if (-not (Test-Path $CpFile)) {
    Log '生成依赖 classpath（mvn dependency:build-classpath）...'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $CpFile) | Out-Null
    Push-Location $Root
    try {
        & mvn -B -ntp -q -pl engine-runtime dependency:build-classpath -Dmdep.outputFile="$CpFile"
        if ($LASTEXITCODE -ne 0) { Die 'classpath 生成失败：请检查 JAVA_HOME(JDK21)/mvn 可用' }
    } finally { Pop-Location }
}
$Cp = (Get-Content -LiteralPath $CpFile -Raw).Trim()

function Invoke-Flyway([string]$mod, [string]$tgt) {
    Log "flyway migrate: module=$mod target=$(if ($tgt) { $tgt } else { 'HEAD' }) db=$Database"
    & $Java '-Dfile.encoding=UTF-8' -cp $Cp (Join-Path $Root 'scripts/g50/DrillFlyway.java') `
        migrate $JdbcUrl $mod $tgt
    if ($LASTEXITCODE -ne 0) { Die "flyway migrate 失败: module=$mod" }
}

# ---- 升级前快照（回退唯一锚点；在首个迁移动作前完成） ----
$hasHistory = & $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d $Database -At -c `
    "select count(*) from information_schema.tables where table_name='flyway_schema_history' and table_schema in ('engine','app','skill');"
if ($BackupDir) {
    New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null
    $stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
    $dump = Join-Path $BackupDir "$Database-pre-upgrade-$stamp.dump"
    Log "pg_dump 一致性快照 -> $dump"
    & $PgDump -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d $Database `
        --format=custom --no-owner --no-privileges --file="$dump"
    if ($LASTEXITCODE -ne 0) { Die '备份失败；按红线拒绝升级（回退锚点必须先在）' }
    $sha = (Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash.ToLowerInvariant()
    $bytes = (Get-Item -LiteralPath $dump).Length
    $manifest = [ordered]@{
        formatVersion = 1; database = $Database
        createdAt     = (Get-Date).ToUniversalTime().ToString('o'); sha256 = $sha
        bytes         = $bytes; postgres = '16.15'
        scope         = 'PostgreSQL only; vault/master key/MinIO objects need separate backup'
    }
    $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $BackupDir "$Database-pre-upgrade-$stamp.manifest.json") -Encoding utf8
    Log "备份 manifest 已写（sha256=$sha）"
} elseif ("$hasHistory".Trim() -ne '0') {
    Log '警告：-BackupDir 未提供；已有迁移历史的库升级将没有回退锚点（演练/首装除外，不建议对 dev 这样做）'
}

# ---- 执行升级（基线 or 链头） ----
$targets = @{}
if ($Pin) {
    foreach ($pair in $Pin.Split(',')) {
        $k = $pair.Split('=')[0]; $v = $pair.Split('=')[1]
        if ($k -notin @('engine', 'app', 'skill')) { Die "--pin 键必须为 engine/app/skill: $pair" }
        $targets[$k] = $v
    }
}
foreach ($mod in @('engine', 'app', 'skill')) {
    $tgt = $null
    if ($targets.ContainsKey($mod)) { $tgt = $targets[$mod] }
    Invoke-Flyway $mod $tgt
}

# ---- 升级后 history 摘要 ----
Log '升级后 flyway_schema_history 摘要：'
& $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d $Database -At -f (Join-Path $SqlDir 'history-summary.sql')

# ---- 可选链头验证 ----
if ($VerifyHead) {
    Log 'verify-head 断言：'
    $out = & $Psql -X -h $PgHost -p $PgPort -U $env:AGENT_DB_USER -d $Database -At -f (Join-Path $SqlDir 'verify-head.sql')
    $out | ForEach-Object { Write-Output $_ }
    if ($out -match '\|f\s*$') { Die 'verify-head 存在失败断言（行尾 f）' }
    Log 'verify-head 全部通过'
}

Log "完成：$Database"
