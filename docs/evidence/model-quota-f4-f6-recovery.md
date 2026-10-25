# F4 / F6 配额与用量恢复修复证据

日期：2026-10-20。仅在独立新工程 `G:/207/项目/算法重组工具/代码/agent-platform` 工作；先读本工程 `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md` 和模型评审。使用 TDD、systematic-debugging、ponytail 与 code-review 自审。没有读取、关联或修改相邻工程、数据、进程。没有提交 Git、启动/停止服务或打包运行源码。

## 改动范围与 F4 规则

- `ModelQuota` 的同一次 Redis Lua 准入现在检查并预留：供应商 TPM/RPM 桶、`tenant+UTC日+app` 应用日额、`tenant+UTC日+principal` 用户日额、现有应用/用户并发。用户日额不带 app 或 provider；任何一项拒绝均不会预扣其他额度。
- `ModelStore.quota` 独立解析 provider 的默认桶配置、应用额度和用户额度。非空 `userPrincipal` 的日额记录在该租户内对该用户跨应用、跨供应商生效，不覆盖应用日额。用户记录沿用旧配置表的 app/provider 列，但不再用它们缩窄用户额度作用域。
- 同一共享维度存在多条配置时取最严值：应用日额/并发取租户内 `userPrincipal=''` 且 app 为空或当前 app 的最小值；用户日额/并发取租户内该 principal 所有配置的最小值；无用户日额配置默认 1000000。供应商 TPM/RPM 只取该 provider 的空 app/空 user 配置。这样切换 app/provider 不会扩大同一用户的有效日额。现有配置字段和管理授权没有扩展。
- receipt 同时记录两个日计数器；对账在同一次 Lua 中调整两者，保持幂等。`ModelCalls` 仅增加独立用户日额入参与账务终态传递，不改模型请求、绑定或计量实现。

## F6 恢复不变量与持久终态

PG 的 `model.call_log` 继续作为权威用量流水，保存实际 usage、原始预留和不可变 quota receipt。receipt 的时间来源为准入 Lua 内的 Redis `TIME`；PG 与 Redis 两阶段之间不宣称分布式事务。

1. **明确恢复截止**：`reconcileUntil=预留时刻+24h`。Redis marker 的绝对过期时间为 `reconcileUntil+24h`。marker 不会因为正常 TTL 到期而在允许重投的窗口内消失。任何恢复请求首先由 Lua 按 Redis 当前时间检查截止时间；过窗即返回 `EXPIRED`，即使 marker 已不存在，也永不再次应用差额或创建计数器。
2. **供应商窗口**：仅在预留后 60s 内、provider key 仍存在且有 TTL、且 `generation` 仍与 receipt 相同的条件下调整 TPM。generation 在桶新建时生成，防止旧退款进入重建后的新桶。超过此窗口只允许尚有效的日额调整，不把旧退款记到当前活跃桶。
3. **日额窗口**：只调整 UTC 所属日尚未结束、存在且仍有正 TTL 的 app/user 日 key。`SET KEEPTTL` 只在通过上述检查后执行，全部在同一个 Lua 内；过期或缺失的日 key 不会复活。旧日尚物理保留的计数器也不会再被调整。
4. **持久幂等**：窗口内通过单次 Lua 同时写入差额与 marker；重复请求直接返回 marker 中已保存的终态。PG 确认使用 `PENDING → RECONCILED/PARTIAL/EXPIRED` 条件更新，持久终态不会被迟到的工作者覆盖，且不再被 `pendingUsage()` 选出。Redis 已成功而 PG 未确认时，恢复仍受同一 receipt 的原截止时间约束；截止时间绝不因重试延长。
5. **旧 receipt**：没有 `reconcileUntil` 的旧格式缺乏可证明的安全重放窗口，一律返回 `EXPIRED`，不读取或调整它引用的 Redis key。不会回退到旧的无限重投逻辑。

终态含义：`RECONCILED` 表示三个计数器均已在有效窗口内处理；`PARTIAL` 表示仅部分计数器在其有效窗口内处理；`EXPIRED` 表示已超恢复期限、旧格式无法安全恢复或已无可调整的有效计数器。后三种情况中跳过的计数器保留原预留，不自动退款或补扣，不重发模型请求。PG 真实用量与预留仍可审计。

`ModelStore.statistics`（现有 `/internal/statistics`）增加按租户统计的 `usage_pending`、`usage_partial`、`usage_expired`，便于运维发现需要核查的保守预留。没有新增终端用户操作或自动冲正接口；人工核查不得把旧流水重置为 PENDING 以重新应用差额。

## 真实红绿记录

全部使用本工程 `.env` 加载的专用 PG/Redis。PG 为 `127.0.0.1:25432/agent_platform_model_test`，Redis 为 `127.0.0.1:26379`；没有输出密码或密钥。Java 21 与 Maven 依赖均使用指定路径。

命令（工程根目录执行，事先按 `scripts/test.ps1` 的方式只向进程环境加载本工程 `.env`）：

```powershell
mvn.cmd -B -ntp -pl model-gateway -am test `
  -Dtest=ModelQuotaRedisTest,ModelQuotaPostgresTest `
  -Dsurefire.failIfNoSpecifiedTests=false `
  -Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2 `
  -Djdk.net.unixdomain.tmpdir=V:/.local/tmp
```

| 阶段 | 日志 | 实际结果 |
| --- | --- | --- |
| 原实现红测试 | `.local/model-quota-red.log` | 10 项，5 失败，0 错误，0 跳过；跨 app 用户可超用、应用限额被用户覆盖、Redis 成功后 marker 过期重投使日额从 5 变 0、过期 key 复活均真实复现 |
| 第一轮修复 | `.local/model-quota-green.log` | 10 项，0 失败/错误/跳过，BUILD SUCCESS |
| 运维状态红测试 | `.local/model-quota-observability-red.log` | 16 项，2 失败，0 错误，0 跳过；缺少 usage_expired/usage_partial 统计字段 |
| 模型全量回归 | `.local/model-quota-full-green.log` | model-gateway 43 项，admin-common 5 项，全部 0 失败/错误/跳过，BUILD SUCCESS |
| 自审后最终配额回归 | `.local/model-quota-final-green.log` | 16 项，0 失败/错误/跳过，BUILD SUCCESS；最终断言明确验证 Redis 已调整到 5、PG 仍 PENDING，然后 marker 真正 TTL 到期、越过缩短的恢复截止后执行真实 ModelCalls 恢复 |

全量回归命令是在上面命令中去掉 `-Dtest` 与 `-Dsurefire.failIfNoSpecifiedTests=false`；仅执行 test 生命周期，没有 package。实际 Flyway 验证仍为 V304，无新增迁移，未修改已有 V304。

关键回归包括：同用户跨 app/provider；同 app 不同用户独立用户额度且共享 app 总额；拒绝时两个计数器都不增加；已知 usage 同时扣退两个日额且重复结算不变；Redis 已成功、PG 未确认、marker 真正过期后的重投；日/provider key 真正 TTL 到期后不复活；旧日与新 provider generation 不受旧 receipt 调整；迟到 provider 只结日额并报告 PARTIAL；旧格式 receipt 保守退出；PG 终态不可被迟到工作者改写。

## 自审与剩余边界

- 重新逐项核对 Lua 的五个准入键、四个对账键、ARGV、receipt 三个账务键与并发释放索引；所有键保留同一 tenant hash tag，未引入跨租户 key。PG 配额查询、统计保持 tenant 约束。
- 保持已有 ADMIN、provider config_rev、完整 schema 计量与计量参数限制安全增量；全量原有模型测试保持通过。
- 恢复安全证明针对正常 Redis TTL 与服务/PG 确认故障。工程缓存启动脚本配置 `appendonly yes`、`maxmemory-policy noeviction`；未模拟 Redis 持久文件损坏、回滚、人工删除 marker 或整库恢复丢失。在恢复截止前丢失 Redis 已确认写入的完整性，不在本轮可声称的 exactly-once 保证内；24h 截止后无论 marker 是否丢失都不会重扣退。
- Redis 不可用仍拒绝新准入；现有日计数器丢失后的完整 PG 重建、热升级前旧调用的用户日额回填、人工冲正/告警前端不在本轮范围。首次引入用户日 key 的切换日历史用量不能视为已回填。
- 日额度沿用 UTC，JVM 选日与 Redis 当前日不一致时准入拒绝；批量等待跨 UTC 日边界时本次许可可拒绝并由调用方重新提交。没有将时钟漂移转为静默跨日记账。
- `UNAVAILABLE` usage 仍保留 NULL 和预留，未实现供应商事后补取；`PARTIAL/EXPIRED` 的保守余额不宣称已自动最终结清。
