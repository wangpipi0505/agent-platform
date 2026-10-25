# 模型接入增量实现与验证

日期：2026-10-20。范围仅本独立工程 `model-gateway/`、模型依赖闭环所需的 `app-management/`；没有读取、导入或改动相邻业务工程。按 v1.2 模型册、运行时安全审查以及实际 Spring AI 1.0.0 / Resilience4j 2.3.0 源码实现，不替换技术底座。最终另经独立模型绑定审查发现问题后继续补充，记录见文末。

## 已落地路径

- `model.binding_snapshot` 按 tenant+内容摘要固化完整非秘密 entry/provider 运行配置。新冻结要求模型与供应商启用；调用/计量只接受本租户权威 `bindingId+sha256`，额外 `modelEntryId` 必须一致。请求中的 `resolvedModel`、`allowDisabled` 不授予任何例外。
- 冻结保留模型名、窗口、输出上限、能力、tokenizer、协议、端点、信任标签等；不复制凭据值或 secret_ref。每次调用从权威 provider 行读取当前 secret_ref 并经租户绑定 SecretVault 解析。编辑元数据、停用供应商后旧快照仍用原配置，凭据轮换立即生效。
- 应用首次候选读取将模型 refs 按 tenant/app/rev/候选摘要持久化，后续相同候选复用首次结果。候选评测和发布均消费该数组。预检报告固定 refs；发布事务核对候选存档和报告 refs 完全一致。发布前启用状态复核在事务外，事务内不发送 HTTP。正常重放同一发布请求不重新要求供应商在线。固定六组预检中的“引用有效性”包含模型冻结/能力检查。
- 管理面登记、编辑供应商/条目、停用、测试和轮换统一仅 ADMIN；EDITOR 只选择与消费已登记条目。公开列表没有 secret_ref 或明文凭据。连接测试必须列出模型并实际执行 max_tokens=1 短对话，分 ENDPOINT/MODEL/AUTH 阶段，列清单成功不能独自启用。供应商修改与轮换共用 PG 行锁；config_rev 使旧配置探测结果不能启用新配置或覆盖刚完成的停用。
- Redis Lua 同一次原子准入覆盖供应商 TPM/RPM 双桶、应用 UTC 日 token 配额、用户及应用并发租约。Redis 不可用返回 SYS-MODEL-503，绝无内存替代。交互快速 429（消息包含 retryAfter 秒数），评测使用 Redis 有界队列：32 槽、最多等待 10 秒；配额及并发许可跨 ModelQuota 实例共享。
- 供应商级 Resilience4j：5 次滑动窗口、最少 5 次、60% 失败率、30 秒半开窗口、单半开许可。外部失败后 PG 可见 DEGRADED；普通调用包含冷启动实例都读取持久 DEGRADED 后拒绝，只允许列模型+短对话的半开健康探测恢复。人工 DISABLED 不被定时器重新启用，历史冻结引用仍可按停用规则运行。
- 统一供应商 401/403→SYS-MODEL-401，429→429，503→503，408/504/超时→504；不回显供应商错误正文。出域标签失败为 SYS-EGRESS-4031；上下文准入失败为 SYS-MODEL-413。同步和真实 SSE 都在实际序列化字节持久化之后才发 HTTP；响应按实际字节硬限制 2 MiB，工具执行保持关闭。
- 展示估算、准入计量、供应商 usage 三档分开入库。jtokkit 仅支持其明确映射的模型，完整请求材料（包括 output schema）canonical+64 结构余量标记 exact=false；真正发 HTTP 之前再次对完整 SDK wire（包括 response_format）计量，超过预留立即拒绝，不消耗未预留 TPM/日额。专用 tokenizer 收到完整 request 与 request_sha256；带格式请求必须确认同一摘要的覆盖才可使用其 count，且 tokenizer 必须与供应商同源。不存在未知模型的固定字符密度准入兜底。
- 展示校准按完整模型配置、工具集和请求格式分 scope 累积 usage/baseEstimate，后续相同 scope 修正展示估算，绝不改变准入门槛。缺 usage 保留 NULL/UNAVAILABLE，不伪造 0。模型成功响应和实际 usage 先持久化，随后 Redis 对账；失败留 PENDING 供定时账务重投，重投不重发模型，Redis receipt 幂等。

追加迁移：model V301/V302/V303/V304；app V103。没有修改已应用 V300 或 app V100—V102。

## 测试证据

环境：Java 21.0.12、Boot 3.5.0、Spring AI 1.0.0；本工程 PG 25432，模型测试库 `agent_platform_model_test`、应用测试库 `agent_platform_v12`；本工程专用 Redis 26379（真实 Redis 8.8.0），受控 HTTP 服务只存在于测试目录。不清空 Redis 实例或运行开发库；凭据由独立 .env 注入，不写入本报告。

实际命令（PowerShell 先安全载入 .env 并设置独立 JAVA_HOME）：

```powershell
mvn.cmd -B -ntp -pl model-gateway,app-management -am test `
  -Dtest=ModelCallsPostgresTest,ModelQuotaRedisTest,ModelPolicyTest,PublishTransactionTest `
  -Dsurefire.failIfNoSpecifiedTests=false `
  '-Dmaven.repo.local=G:/207/项目/算法重组工具/代码/agent-platform/.tools/m2' `
  '-DargLine=-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
```

审查前阶段模型结果：`ModelCallsPostgresTest` 18 + `ModelQuotaRedisTest` 5 + `ModelPolicyTest` 3，26 项，0 失败/错误/跳过。日志 `.local/model-final-green.log`，03:27:50 BUILD SUCCESS；其中供应商半开测试真实等待 30.5 秒后通过 HTTP 验证恢复，不用手工改 PG 状态冒充恢复。独立审查后的新用例结果另记文末，不能用此阶段结果代表后续代码已通过。

应用另跑全模块：`mvn.cmd -B -ntp -pl app-management -am test`（同独立仓库与 JVM 参数），app 22 项、common 5 项，全绿；`.local/model-app-module-green.log`，03:29:55 BUILD SUCCESS。应用事务测试从原 10 增至 13，覆盖相同候选首次 refs 复用、报告 refs 被篡改拒绝、精确发布输入读取、输入默认值包含 modelEntryId 不误作模型依赖。

关键红阶段均有真实执行记录：

| 日志 | 失败证据与后续修复 |
| --- | --- |
| model-app-freeze-red | 候选没有 dependencyVersions，断言 not null 失败 |
| model-health-red | 仅列清单仍使短对话失败供应商启用：expected false / true |
| model-errors-red | 供应商401被转为502：expected401 / 502 |
| model-circuit-red | 熔断后第六次仍外发：expected5 / 6 |
| model-halfopen-red | HTTP半开恢复后PG仍DEGRADED：expected ENABLED / DEGRADED |
| model-metering-red | 缺 admission_tokens / 三档持久结构 |
| model-egress-red | 出域失败错误状态：expected403 / 400 |
| model-app-input-default-red | 普通输入默认值 modelEntryId 导致错误的远程 freeze 请求 |

冻结、凭据轮换、配额配置等新 API 的首轮红阶段也包含缺少方法/类型的编译失败；不把这些编译失败描述为已复现业务运行错误。最终全部用例均实际运行、没有跳过失败测试。

## 原接口补充

- `POST /internal/model-bindings/freeze`：`{modelEntryId}` → `{modelEntryId,bindingId,sha256,entry}`。
- `POST /internal/model-bindings/validate`：`{bindingId,sha256}`，仅新绑定/发布复核使用。
- chat/measure 请求：`modelBindingRef:{bindingId,sha256}`；权威引擎同时传 appCode、runId、callKind。冻结数组在 `dependencyVersions.modelRefs:[{modelEntryId,modelBindingRef,entry}]`，不改业务 config，避免污染既有候选摘要。
- `PUT /admin/model-providers/{id}`，`POST /{id}/disable`，`POST /{id}/credentials/rotate`（`{secret}` 或 `{secretRef}`），`PUT /{id}/quota`（tpm/rpm/dailyTokens/userConcurrency/appConcurrency，可选 appCode/userPrincipal）。
- `PUT /admin/model-entries/{id}`；`GET /{id}/metering` 返回分 scope 校准账。
- `GET /admin/apps/{code}/versions/{versionNo}` 返回同租户精确历史 snapshot。
- `GET /v1/apps/{code}/input-schema?versionNo=可选` → `{appCode,versionNo,inputs,inputSchema?}`。必须存在已发布 currentVersionNo 且未 DISABLED/ARCHIVED；默认 current；inputs 优先冻结 definition.inputs，再 config.inputs；编辑新草稿不影响旧发布输入与运行；不返回整个内部快照。

## 独立审查后的定向补充

`.local/model-review-red.log` 4/4 运行失败后，`.local/model-review-green.log` 4/4 通过，覆盖大schema准入低估、冷实例忽略PG熔断、EDITOR自登记信任标签、旧probe覆盖新配置/停用。再增加专用tokenizer完整schema计量，以及两个真实PG事务间的行锁闩锁回归，验证轮换持锁时另一元数据修改确实等待、EDITOR端点改向拒绝且零模型外发。新增 V304 固化 config_rev。

应用双轨回归 `.local/model-app-dualtrack-red/green.log`：发布v1后编辑草稿，旧实现默认 published()/inputs 拒绝；现默认仍返回不可变v1，未发布草稿仍拒绝，可停用后阻止新运行。13项应用事务回归已通过，时间03:34:50。此修复在前述03:29全模块22项之后，后续整合测试另记最新日志。

**最新整合验证**：`.local/model-reviewed-final-green.log`，2026-10-20 03:39:50 BUILD SUCCESS。同轮 app `PublishTransactionTest` 13 项；model `ModelCallsPostgresTest` 24 + `ModelQuotaRedisTest` 5 + `ModelPolicyTest` 3，共32项；全部0失败/0错误/0跳过。半开恢复用例先新建冷实例，验证持久熔断拒绝，再真实等待30.5秒，经列模型+短对话健康探测恢复后才允许普通调用。源码已 google-java-format 1.25.2 `--aosp` 对齐四空格；未因纯格式重复运行长套件，最后测试同时覆盖实际行为修复。

本报告只证明上述局部模块路径；不代表 34 项全部完成，也不代替真实供应商、跨副本容量或最终整体验收。
