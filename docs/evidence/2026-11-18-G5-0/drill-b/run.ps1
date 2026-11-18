# =============================================================================
# G5-0 演练B 完整重放脚本（PowerShell 脚本族：upgrade.ps1 / rollback.ps1）
# 库：drill_g50_b（工作）+ drill_g50_base_b（基线对照）
# 产物：本目录下 01..09 日志；备份 dump 在 .local/g50/drill-b/backup（不入 Git）。
# 幂等：已存在且非空的步骤日志会跳过（重放时先清空对应日志即可强制重跑）。
# =============================================================================
# PS5.1 注意：'Stop' 会把原生命令（psql NOTICE 等）stderr 当 terminating error；
# 这里改用显式 $LASTEXITCODE 校验关键步骤。
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false) } catch {}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Root = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $ScriptDir))))
$Deploy = Join-Path $Root 'deploy/upgrade-20261118'
$SqlDir = Join-Path $Deploy 'sql'
$env:JAVA_HOME = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot' }

$enc = if ($PSVersionTable.PSVersion.Major -ge 6) { 'utf8NoBOM' } else { 'utf8' }

function Log([string]$m) { Write-Output "[run] $m" }

function Save-Log([string]$name, [string]$header, [scriptblock]$sb) {
    $f = Join-Path $ScriptDir ("{0}.log" -f $name)
    if ((Test-Path -LiteralPath $f) -and ((Get-Item -LiteralPath $f).Length -gt 0)) {
        Log "跳过（已有日志）: $name"; return
    }
    Log "=== $name ==="
    $out = (& $sb 2>&1 | ForEach-Object { "$_" })
    $lines = @($header) + @($out)
    $lines | Out-File -LiteralPath $f -Encoding $enc
    $lines | Select-Object -Last 4 | ForEach-Object { Write-Output "  $_" }
}

# ---- 公共：psql 定位与口令 ----
$PgBin = $env:AGENT_PG_BIN
if (-not $PgBin) {
    foreach ($cand in @(
        (Join-Path $Root '.tools/postgresql-16.15/pgsql/bin'),
        (Join-Path (Split-Path -Parent $Root) 'agent-platform/.tools/postgresql-16.15/pgsql/bin'))) {
        if (Test-Path (Join-Path $cand 'psql.exe')) { $PgBin = $cand; break }
    }
}
if (-not $PgBin) { throw '找不到 psql：设 AGENT_PG_BIN' }
$Psql = Join-Path $PgBin 'psql.exe'
$PgDump = Join-Path $PgBin 'pg_dump.exe'
$PgRestore = Join-Path $PgBin 'pg_restore.exe'
$envVarLine = (Select-String -LiteralPath (Join-Path $Root '.env') -Pattern '^AGENT_DB_PASSWORD=' | Select-Object -Last 1).Line
$env:PGPASSWORD = $envVarLine -replace '^AGENT_DB_PASSWORD=', '' -replace "`r", ''
$env:PGCLIENTENCODING = 'UTF8'
$Header = "## 演练B {0} @ $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))"

# ---- 01 建库+钉版基线（engine=228, app=121, skill=320） ----
Save-Log '01-baseline-build' ($Header -f '01 建库+钉版基线') {
    & (Join-Path $Deploy 'upgrade.ps1') -Database drill_g50_b -CreateDatabase -Pin 'engine=228,app=121,skill=320'
}
# ---- 02 基线断言 ----
Save-Log '02-verify-baseline' ($Header -f '02 基线断言 verify-baseline.sql') {
    Write-Output '$ psql -d drill_g50_b -At -f sql/verify-baseline.sql'
    & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_b -At -f (Join-Path $SqlDir 'verify-baseline.sql')
    Write-Output '== 失败断言（行尾f）=='
    $fails = & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_b -At -f (Join-Path $SqlDir 'verify-baseline.sql') | Where-Object { $_ -match '\|f$' }
    if ($fails) { $fails } else { Write-Output '(无失败断言)' }
}
# ---- 03 基线快照→pg_restore→对照库 drill_g50_base_b ----
Save-Log '03-snapshot-restore-base-b' ($Header -f '03 基线快照+恢复出对照库 drill_g50_base_b') {
    $dump = Join-Path $Root ".local/g50/drill-b/backup/drill_g50_b-baseline-$((Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')).dump"
    Write-Output "-- pg_dump --format=custom --no-owner --no-privileges -> $dump"
    & $PgDump -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_b --format=custom --no-owner --no-privileges --file="$dump"
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump 失败' }
    Write-Output 'pg_dump OK'
    $sha = (Get-FileHash -LiteralPath $dump -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "-- sha256: $sha"
    Write-Output '-- drop/create 对照库 drill_g50_base_b'
    $n = & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -At -c "select count(*) from pg_database where datname='drill_g50_base_b';"
    if ("$n".Trim() -eq '1') {
        & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -c 'drop database "drill_g50_base_b";'
        if ($LASTEXITCODE -ne 0) { throw 'drop database drill_g50_base_b 失败' }
    }
    & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d postgres -c 'create database "drill_g50_base_b";'
    if ($LASTEXITCODE -ne 0) { throw 'create database drill_g50_base_b 失败' }
    Write-Output '-- pg_restore --exit-on-error -> drill_g50_base_b'
    & $PgRestore -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_base_b --no-owner --no-privileges --exit-on-error "$dump"
    if ($LASTEXITCODE -ne 0) { throw 'pg_restore 失败' }
    Write-Output 'pg_restore OK'
    Write-Output '-- restore 后基线断言（对照库）'
    & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_base_b -At -f (Join-Path $SqlDir 'verify-baseline.sql')
}
# ---- 04 升级前状态：row-counts（工作库+对照库） ----
Save-Log '04-pre-upgrade-state' ($Header -f '04 升级前状态 row-counts') {
    Write-Output '== drill_g50_b row-counts（升级前）=='
    & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_b -At -f (Join-Path $SqlDir 'row-counts.sql')
    Write-Output '== drill_g50_base_b row-counts（对照库）=='
    & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_base_b -At -f (Join-Path $SqlDir 'row-counts.sql')
}
(Get-Content -LiteralPath (Join-Path $ScriptDir '04-pre-upgrade-state.log') |
    Where-Object { $_ -match '^(engine|app|skill)\.' } |
    Sort-Object -Unique) | Out-File -LiteralPath (Join-Path $ScriptDir '04-pre-rowcounts.sorted') -Encoding $enc

# ---- 05 全链升级（自动 pg_dump 快照 + verify-head） ----
Save-Log '05-upgrade' ($Header -f '05 全链升级（快照→migrate→verify-head）') {
    & (Join-Path $Deploy 'upgrade.ps1') -Database drill_g50_b -BackupDir (Join-Path $Root '.local/g50/drill-b/backup') -VerifyHead
}
# ---- 06 升级后状态 ----
Save-Log '06-verify-head' ($Header -f '06 升级后状态 verify-head + row-counts') {
    Write-Output '== verify-head 断言明细 =='
    & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_b -At -f (Join-Path $SqlDir 'verify-head.sql')
    Write-Output '== 失败断言（行尾f）=='
    $fails = & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_b -At -f (Join-Path $SqlDir 'verify-head.sql') | Where-Object { $_ -match '\|f$' }
    if ($fails) { $fails } else { Write-Output '(无失败断言)' }
    Write-Output '== drill_g50_b row-counts（升级后）=='
    & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_b -At -f (Join-Path $SqlDir 'row-counts.sql')
}
# ---- 07 回退（备份恢复路径） ----
$dumpFile = Get-ChildItem -LiteralPath (Join-Path $Root '.local/g50/drill-b/backup') -Filter 'drill_g50_b-pre-upgrade-*.dump' |
    Sort-Object Name | Select-Object -First 1
Save-Log '07-rollback' ($Header -f "07 回退 rollback.ps1 <- $($dumpFile.Name)") {
    & (Join-Path $Deploy 'rollback.ps1') -Database drill_g50_b -BackupFile $dumpFile.FullName -Force
}
# ---- 08 回退后状态 ----
Save-Log '08-post-rollback-state' ($Header -f '08 回退后状态 history + row-counts') {
    Write-Output '== drill_g50_b history-summary（回退后）=='
    & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_b -At -f (Join-Path $SqlDir 'history-summary.sql')
    Write-Output '== drill_g50_b row-counts（回退后）=='
    & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_b -At -f (Join-Path $SqlDir 'row-counts.sql')
}
(Get-Content -LiteralPath (Join-Path $ScriptDir '08-post-rollback-state.log') |
    Where-Object { $_ -match '^(engine|app|skill)\.' } |
    Sort-Object -Unique) | Out-File -LiteralPath (Join-Path $ScriptDir '08-post-rowcounts.sorted') -Encoding $enc

# ---- 09 一致性 diff ----
$schemaB = Join-Path $Root '.local/g50/drill-b/schema-base_b.sql'
$schemaR = Join-Path $Root '.local/g50/drill-b/schema-rolledback.sql'
& $PgDump -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_base_b --schema-only --no-owner --no-privileges -f "$schemaB"
& $PgDump -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_b --schema-only --no-owner --no-privileges -f "$schemaR"
Save-Log '09-consistency-diff' ($Header -f '09 回退一致性 diff') {
    Write-Output '== [1] 行数：回退后 vs 升级前（应为空）=='
    $d1 = Compare-Object (Get-Content (Join-Path $ScriptDir '04-pre-rowcounts.sorted')) (Get-Content (Join-Path $ScriptDir '08-post-rowcounts.sorted'))
    if ($d1) { $d1 | Format-Table | Out-String } else { Write-Output '(一致)' }
    Write-Output '== [2] 行数：回退后 vs 对照库 base_b（应为空）=='
    & $Psql -X -h 127.0.0.1 -p 25432 -U agent_v12 -d drill_g50_base_b -At -f (Join-Path $SqlDir 'row-counts.sql') |
        Where-Object { $_ -match '^(engine|app|skill)\.' } |
        Sort-Object -Unique | Out-File -LiteralPath (Join-Path $ScriptDir '09-base-rowcounts.sorted') -Encoding $enc
    $d2 = Compare-Object (Get-Content (Join-Path $ScriptDir '09-base-rowcounts.sorted')) (Get-Content (Join-Path $ScriptDir '08-post-rowcounts.sorted'))
    if ($d2) { $d2 | Format-Table | Out-String } else { Write-Output '(一致)' }
    Write-Output '== [3] schema-only dump：对照库 vs 回退后（应为空；剔除 pg16 随机 \restrict 防护令牌行后比较）=='
    $normB = Get-Content -LiteralPath $schemaB | Where-Object { $_ -notmatch '^\\(un)?restrict ' }
    $normR = Get-Content -LiteralPath $schemaR | Where-Object { $_ -notmatch '^\\(un)?restrict ' }
    $d3 = Compare-Object $normB $normR
    if ($d3) { $d3 | Select-Object -First 40 | Format-Table | Out-String } else { Write-Output '(一致)' }
}
Log '演练B 完成；日志见本目录'
