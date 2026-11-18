# deploy/upgrade-20261118 —— G5-0 存量升级 + 部署演练（回退验证）交付

日期：2026-11-18 · 卡面：台账 §7.6 G5-0 · 分支：`g50-upgrade-drill`（基于 f1f40e66）

## 交付物

| 文件 | 作用 |
|------|------|
| `upgrade.sh` / `upgrade.ps1` | 存量基线构造（`--pin`/`-Pin` 钉版）与全链升级；升级动作前自动 pg_dump 快照（回退唯一锚点） |
| `rollback.sh` / `rollback.ps1` | 回退 = 备份恢复（drop + create + pg_restore）；**唯一受支持回退路径**（Flyway 社区版无 downgrade） |
| `CHECKLIST.md` | 升级前快照 / 升级 / 验证 / 回退验证 / 升级后验证 五段清单（逐项可勾选） |
| `sql/history-summary.sql` | 三模块 `flyway_schema_history` 逐行（留档用） |
| `sql/verify-baseline.sql` | 基线断言：顶版本 = 228/121/320、零失败迁移、无 V901 |
| `sql/verify-head.sql` | 链头断言：顶版本 = 901、零失败迁移、种子 2/38/32、激活指针每工作区 1 条 digest 一致 |
| `sql/row-counts.sql` | 三 schema 全基表逐表行数（升级前/后/回退后三采集互 diff） |

配套 runner：`scripts/g50/DrillFlyway.java`（Flyway 11.7.2 编程调用，与各服务
application.yml 同构：schema=engine/app/skill、location=各模块 `db/migration`、
历史表缺省名；写法对齐 `EngineTestDatabase` 的坐标来源——凭据只从 env）。

## 升级链（以仓库实际迁移文件核对）

- engine `engine-runtime`：V200–V228（存量）→ **V229 / V230 / V231 / V901**（升级段）
- app `app-management`：V100–V121（存量）→ **V122 / V123 / V901**（升级段）
- skill `skill-registry`：V300–V320（存量）→ **V321 / V322 / V901**（升级段）
- **V232 schedule 后置预留：链上无此文件，不在演练范围。**

V901 演示种子行数口径（`tenant_id='demo'`）：engine 2（memory_entry）、
app 38（application2/app_draft2/app_version2/skill_reference12/assistant_experience2+修订2/
integration_profile2+修订2/workspace_manifest2/integration_config_release2/激活指针2/rule_asset3+版本3）、
skill 32（skill14/skill_version14/plugin_source1/plugin1/plugin_version1/plugin_grant1）。

## 演练矩阵（两次独立完整重放，证据见 docs/evidence/2026-11-18-G5-0/）

| 演练 | 库 | 脚本族 | 路径 |
|------|----|--------|------|
| A | `drill_g50_a`（工作）/ `drill_g50_base_a`（对照） | bash（`upgrade.sh`/`rollback.sh`） | 建库→钉版基线→快照恢复出 base_a→全链升级→verify-head→回退→回退后一致性 diff |
| B | `drill_g50_b`（工作）/ `drill_g50_base_b`（对照） | PowerShell（`upgrade.ps1`/`rollback.ps1`） | 同上，独立重放；两遍输出 diff 仅时间戳 |
| dev | `agent_platform_dev_v12` | bash | 升级前状态+pg_dump 留档（`.local/`，不入 Git）→升级→verify-head；回退脚本可用性文档化不执行 |

## 环境约定

- 专用 PG `127.0.0.1:25432`（PostgreSQL 16.15，工程 `.local/pgdata`）；脚本内置 `:25432/` 守卫。
- PG 客户端定位顺序：`AGENT_PG_BIN` → `<repoRoot>/.tools/postgresql-16.15/pgsql/bin`
  → `<repoRoot>/../agent-platform/.tools/postgresql-16.15/pgsql/bin`。
- 凭据只从 `.env` 读；任何命令/日志/证据不得出现明文密钥。
- 备份/快照产物放 `.local/`（已在 .gitignore），**不入 Git**。

## 生产等价库注意事项

1. 演示种子跳过开关（占位符接线，文档化交付）：
   `ALTER DATABASE <prod> SET agent.demo_seed_skip = 'on';` 之后 V901 整段 no-op
   （迁移内置守卫）。验证/演示库不要设置。
2. 升级窗口内三服务停机执行（本卡只做库层；服务滚动启停属 G5 部署线其他卡）。
3. 回退后必须重启服务再验证（连接池内 schema 缓存不跨回退复用）。
