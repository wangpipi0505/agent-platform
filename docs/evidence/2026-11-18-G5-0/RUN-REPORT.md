# G5-0 存量升级+部署演练（回退验证）运行报告

日期：2026-11-18 · 分支：`g50-upgrade-drill`（基于 f1f40e66）· 执行：后端子智能体（G5-0）
交付脚本与检查单：`deploy/upgrade-20261118/`（`upgrade.sh|ps1`、`rollback.sh|ps1`、`CHECKLIST.md`、`README.md`、`sql/`）
配套 runner：`scripts/g50/DrillFlyway.java`（Flyway 11.7.2 编程调用，与服务同源）

## 一、演练机制

- 专用 PG `127.0.0.1:25432`（PostgreSQL 16.15，工程 `.local/pgdata`）。
- 库分工：`drill_g50_a`/`drill_g50_b`=升级工作库；`drill_g50_base_a`/`drill_g50_base_b`=
  基线快照经 pg_restore 恢复出的对照库（同时验证恢复路径本身）。仅新建这四库，未触碰其他库。
- 存量构造：flyway `target` 钉版建基线（engine=V228 / app=V121 / skill=V320，以各模块
  `db/migration` 实际文件核对）；升级链头 = engine V229/V230/V231/V901、app V122/V123/V901、
  skill V321/V322/V901。**V232 schedule 后置预留，链上无此文件，不在演练范围。**
- 回退：**唯一受支持路径 = 备份恢复**（drop+create+`pg_restore --exit-on-error`）；
  Flyway 社区版无 downgrade，脚本与检查单均按此口径。
- 演练 A 用 bash 脚本族（`upgrade.sh`/`rollback.sh`），演练 B 用 PowerShell 脚本族
  （`upgrade.ps1`/`rollback.ps1`）——两遍独立重放同时交叉验证两套交付脚本。
- runner 与服务同构性：`<< Flyway Schema Creation >>` 标记行（rank 0、type=SCHEMA、
  script=`"<schema>"`）与服务自管测试库 `agent_platform_engine_test` 中逐字节一致。

## 二、演练 A（bash；库 drill_g50_a / drill_g50_base_a）

| 段 | 结果 |
|----|------|
| 01 建库+钉版基线 | engine 228 / app 121 / skill 320 全 success（含 Flyway Schema Creation 标记） |
| 02 基线断言 | 顶版本 228/121/320 ✓、失败迁移 0 ✓、无 V901 ✓（verify-baseline 全 pass） |
| 03 快照→base_a | pg_dump custom → pg_restore --exit-on-error 恢复成功；对照库基线断言全 pass |
| 04 升级前状态 | 三 schema 全表行数留档（含 flyway_schema_history 行数） |
| 05 全链升级 | **engine applied=4（228→901）、app applied=3（121→901）、skill applied=3（320→901）**；升级前自动 pg_dump（manifest sha256 在案） |
| 06 链头验证 | 三模块顶版本 901 ✓、失败迁移 0 ✓、种子 **engine 2 / app 38 / skill 32** ✓、激活指针 active() 口径 **每工作区 1 条、manifest_digest 与 workspace_manifest.digest 2行/2一致** ✓ |
| 07 回退 | rollback.sh（drop+create+pg_restore）成功恢复到备份时点 |
| 08 回退后状态 | history 回到 228/121/320 全 success；全表行数留档 |
| 09 一致性 diff | 回退后 vs 升级前行数 **(一致)**；vs 对照库 **(一致)**；schema-only dump（剔除 pg16 随机 \restrict 令牌）**（一致)** |

## 三、演练 B（ps1；库 drill_g50_b / drill_g50_base_b）

与 A 逐段相同的结果：applied 4/3/3、verify-head 全 pass（2/38/32、激活 2行/2一致）、
回退成功、三组一致性 diff 全空。**两遍差异记录**：`DRILL-DIFF.md`（由 `make-drill-diff.sh`
生成）——版本演进、断言行、升级前/后与回退后行数对照逐行一致，差异仅时间戳与
schema dump 随机令牌。

## 四、真实 dev 库升级（agent_platform_dev_v12）

| 项 | 结果 |
|----|------|
| 升级前状态 | **仅 engine schema**（V200–V231 共 33 行 history 全 success；app/skill 未安装）；`agent.demo_seed_skip`=unset；逐表行数留档（`dev/01-pre-state.txt`） |
| 备份 | 独立 pg_dump（custom，sha256 `618b32bd…b2dc`，122,438 bytes，manifest 在案）→ `.local/g50/dev-backup/`（**不入 Git**）；升级脚本内又自动快照一次（双锚点） |
| 升级 | **engine applied=1（231→901，即仅 V901 一段）；app applied=25（V100–V123+V901 首装）；skill applied=24（V300–V322+V901 首装）** |
| 链头验证 | 三模块顶版本 901 ✓、失败迁移 0 ✓、V901 种子 **app 38 / skill 32 / engine 2** ✓、激活指针 **每工作区 1 条 digest 一致（2行/2一致）** ✓ |
| engine 前→后 diff | 仅两处变化：`flyway_schema_history` 33→34（V901 在案）、`memory_entry` 0→2（种子）——其余逐表行数零变化，未触碰存量数据 |
| 回退可用性 | 文档化未执行：`rollback.sh|ps1 --database agent_platform_dev_v12 --backup-file <dump> --force` 即同一入口；回退后以 `01-pre-rowcounts.sorted` 为一致性基准（`dev/05-rollback-availability.md`） |

## 五、诚实登记

1. **回退仅备份恢复路径**受支持（Flyway 社区版无 downgrade）；两遍演练证明该路径可用（含
   sha256 manifest 校验、--exit-on-error、恢复后一致性三重比对）。
2. **V232 schedule 后置预留**，链上无此迁移文件，本期演练与 dev 升级均不含；其到链时需增量重演。
3. dev 库 **engine 已在 V231**：engine 实际增量仅 V901；**app/skill 为首次安装**（无存量数据
   迁移语义），登记如实。V229/V230/V231 的存量升级路径由两遍演练覆盖（从 V228 钉版起迁）。
4. 激活指针验证用 SQL 与 `ConfigReleaseRepository.active(Actor,appCode,scope)` 主查询**逐字同源**
   （去语义视图装饰），未起 Spring 上下文调用 Java 方法。
5. 演练 A 03 步曾三次尝试失败（pg_dump 误加 psql 专属 `-X`、run 脚本根路径错、verify-head.sql
   中 plugin_grant 无 tenant_id 列），失败日志全部保留于 `drill-a/failures/`；修正后的
   `verify-head.sql` 为最终版（plugin_grant 按 demo 插件版本联接计数）。
6. pg16 每次 schema dump 携带随机 `\restrict` psql 防护令牌——一致性比对前剔除该行（非 schema 内容）。
7. 产物位置：备份 dump/manifest 与 schema dump 均在 `.local/g50/`（.gitignore 覆盖，不入 Git）；
   证据日志全部 UTF-8（psql 客户端错误文本可能为控制台编码，已通过零错误运行规避）。

## 六、文件索引

- `drill-a/`：01–09 日志 + `run.sh`（完整重放入口）+ `failures/`（失败尝试留痕）
- `drill-b/`：01–09 日志 + `run.ps1`
- `DRILL-DIFF.md`：两遍差异记录（`make-drill-diff.sh` 生成）
- `dev/`：01 升级前状态 / 02 独立备份 / 03 升级 / 04 链头验证 / 05 回退可用性 + `run-dev.sh`
- 检查单：`../../../deploy/upgrade-20261118/CHECKLIST.md`（五段清单，本报告即其两次完整勾选记录）
