# EX-15a 最小红测候选与静态范围复核

日期：2026-10-29。准备者：独立子智能体 `ex04_pg_scope`。准备基点：AP `67d1d41dc599a99ecad4654a4736446e076f84f7`。

状态：**仅静态准备，尚未编译或运行新增用例，不得登记“实红”或“完成”。** 本次只追加 `MultipartStreamingTest.java` 的测试及本报告；未修改生产、迁移、三份主入口、Git index，未运行 Java/Maven/PG/HTTP 服务或进程。已读 AP `AGENTS.md`、`docs/implementation-brief.md`、`contracts/README.md` 与当前三份主入口；不使用 superpower。

## 1. 已有实现与本卡边界

- `ArtifactStore.java:341–442` 已在上传前登记精确对象，上传后校验原输出 Schema，再登记 SHA-256/字节数/READY；失败保留或清理精确登记键。`:444–456` 按已验主体的 tenant/principal/READY/有效期查询，必要时再核 run；`:557–594` 已有受控引用、下载和复制到新 run。
- `V306__controlled_artifacts.sql` 已有 `skill.artifact`、不可变 owner/run/call/bucket/key、摘要、大小、过期/清理/租约列及触发器；`ArtifactStore.java:641–694` 已有按持久精确键认领与有界清理。没有证据支持为本次两类缺口另建 V318 或另造存储系统。当前源码迁移尾号观察为 V315；这不是对运行库版本的查询。
- `ArtifactPersistenceTest` 已有真实 PG/MinIO 的引用、5 MiB 输出、归属隔离、复制新 run、断流/超限/清理重试测试。既有实现不能写成“artifact 持久链完全未做”；台账 T09.1/PLAT-09 的历史 V318/全内存表述须在实际验收后按证据修正，保留历史事实。
- 64 MiB 是文件字节硬上限；不得为此放宽 JSON 原有 250,000 token、8 MiB 单字符串、嵌套深度等约束。当前 `offload` 上传后仍将有界 JSON 读树并校验，不应声称任意 64 MiB JSON 都满足内存常量或 Schema 限制。

## 2. 两个已定位的产品缺口

1. **生产引用与输入解析断链。** `ArtifactStore.java:463–482` 返回 `/admin/artifacts/art_<32hex>/download` 和 `/v1/runs/<run>/artifacts/art_<32hex>/download`。`MultipartFormMapping.java:353–361` 的路径正则只接收裸 `<32hex>`，随后自己补 `art_`，不能接收生产返回路径。原 `MultipartFormMappingTest.java:222–231` 用裸 hex 假路径，未覆盖真实生成端。直接 opaque ID 路径可用；禁止删除它或放宽外部 URL/物理键拒绝断言。
2. **失败请求未关闭尚未写出的部分。** `MultipartFormMapping.java:165–206` 在 build 时已取得每个 Download，成功构建后由 HTTP 写出器消费。当前已覆盖构建失败与单部分读取失败；但 `SkillAdapters.java:597–649` 请求生命周期未保留/关闭整个 multipart 集合。第一部分断流或传输失败时，后续已装载而尚未交给转换器的流没有关闭责任方。`FilePartBody.close` 已具备幂等 CAS，可复用。

最小待准入生产范围：现卡 `MultipartFormMapping.java` 加 **`SkillAdapters.java` 同步 HTTP 方法的 multipart 生命周期**。优先用简单的统一关闭调用与 `finally`，不新增资源框架、不改异步链、不改 PROCESS、ALGORITHM 中断机制或 Schema 边界。该扩白名单必须在实际红测及独审后回填主入口，再改生产。本次未实施。

## 3. 当前唯一测试候选

文件：`skill-registry/src/test/java/cn/agentplatform/skill/infra/MultipartStreamingTest.java`。

原 7 个 `@Test` 方法及断言未删除或改写；只加 imports、4 个测试及小型辅助方法。`git diff --numstat` 为 `153 0`，该文件 `git diff --check` 返回 0。没有条件跳过、放宽原断言或改全局属性。

| 新方法 | 实际路径与判定 | 未修复代码的预期红原因 |
|---|---|---|
| `真实产物引用生成器的门户下载路径可直接用于文件参数` | 真实 `ArtifactStore.controlledReference` → 返回 `downloadUrl` → `MultipartFormMapping.build` → opaque ID 解析 → 4 字节消费/关闭一次 | 生产路径含 `art_`，被当前正则按 `PARAM_ERROR` 拒绝 |
| `真实产物引用生成器的运行下载路径可直接用于文件参数` | 同上，改用真实 `externalDownloadUrl` | 同一错误正则拒绝运行下载路径 |
| `真实出站第一文件断流后关闭尚未消费的第二文件` | 真实 `SkillAdapters.http` + loopback HTTP + 首文件合成流抛 IOException；两个部分均已装载；第二部分不能读取且必须关闭一次 | 第二个 Download 尚未消费，当前请求 `finally` 不关闭它 |
| `真实出站对端断连后关闭全部已装载文件` | 真实 HTTP 对端收到请求后关闭；首文件按需合成 64 MiB，第二文件 4 字节；确认真实收到请求、两部分已装载、第二部分未读、全部只关一次 | 发送失败后尚未消费的 Download 泄漏；实际失败分项须以原 XML 为准 |

引用生成测试只替代 `JdbcTemplate.queryForList` 返回确定的 owned 行，并使用已有 verified-fixture JWT 模式；生产 `owned` 与 `reference` 方法真实执行，URL 不是测试重新拼接。**此处不证明真实 PG 持久化、JWT 密码学验签或上传后重启**；这些仍由原持久化类与后续验收承担。此前授权的归属/拒绝断言必须保留。

出站测试只替代 ArtifactStore 的下载字节来源，复用原 `SyntheticStream`。实际 HTTP 为测试自身创建的 `127.0.0.1` 动态端口，每次 finally 停止；不连真实业务端点，不造文件，不常驻 64 MiB byte[]。这两个测试运行时不访问 PG/MinIO；首次实际运行由主线程独占窗口安排，本子智能体未启动。

后续顺序：冻结本候选 → 定向整类 11 方法采实红 → 核新失败确属上述产品路径而非夹具/配置 → 更新白名单并最小修复 → 原 7 + 新 4 保持所有断言回归 → 原 artifact 四类及相关 multipart/异步拒绝测试扩面 → 同条件 skill 模块方法/原因基线比较 → 独立复核/三入口/本地提交。

本次红测不替代剩余验收：64 MiB 实上传/下载/出站及 digest；声明大小与实际字节不符的上传边界；真实 fresh-store/重启后持久可读；tenant/principal/过期/错误 run；清理只触及本用例登记键。需逐项确认已有测试是否足够，缺项再最小补充，不能用本 4 例关闭整卡。

## 4. 候选冻结（工作区原始字节 SHA-256）

| 文件 | SHA-256 |
|---|---|
| `MultipartStreamingTest.java`（本候选） | `64a981a93e22351645fa3d9bd77671a0d1f84db0740e6585737114bd6046eb1e` |
| `ArtifactStore.java` | `9deb51566206d548110f1f6b5aaab466619eb80d43e8d30b67f11b7e27b7541d` |
| `MultipartFormMapping.java` | `3f784bbbdd5ff361f4d119c17006d7864eee15c196fcfb11224f2959fe198393` |
| `SkillAdapters.java` | `1ffd4d080af175185fde5e2f7ae5a33d823d657f965253bf336e29579b22fc11` |
| `ArtifactController.java` | `17334e1d4f8348d6d117c567327fbcf9ed517ea4df117461859927a7f9a1667e` |
| `V306__controlled_artifacts.sql` | `44abf9f1d5688b29cc19f6a05ba6811f7ed8f00e0562251ca391b4b23e1afdcf` |

## 5. EX-16 只读附注（不扩大当前实施）

- `RunService.cancel:2359–2405` 只改 engine 状态与事件，未向 skill 发取消。新链应在本地终态确定后进行有界出站，不跨 HTTP 持有原 run→node→call 事务锁；保持 `ToolCancellationRaceTest` 与迟到事实不复活的原语义。
- `RuntimeClients.post:549` 调 `legacy()`，IAM 下 `:508–510` 必拒；`ServiceTokens.issue` 同样拒绝 IAM 旧票据。新端点必须使用当前可信授权与精确 route action/resource，不能打开旧 HMAC 回退或只验证 body 自报 tenant/call。既有 SkillController 可以承载路由时，无须新建控制器。
- `ProcessAdapter:317–318` 在进程启动后才把 callId 注册到 active；`:652–663` 取消只查 callId，条目未保存 tenant/attempt。取消先到、进程尚未入 active 的竞争必须有红例；“未找到 active”不能自动证明后续绝不会启动。重复取消、自然退出竞争、跨 tenant/不同代隔离均需明确判定。
- `ProcessExecutionAdapter` 继承 `SkillAdapter.persistentBinding=false`；`SkillExecution:259` 不为 PROCESS 预写异步占位事实。进行中的 PROCESS 不能依赖 `execution_fact` 必然已存在。取消的授权/运行绑定须来自可信 engine 注册或执行时已有的核实身份，而非临时请求自报；不据此先造新表。
- **禁止 EX-16 与 EX-15b 并发编辑**：直接共享 `ProcessAdapter.java`、`ProcessAdapterHardeningTest.java`。EX-15b 真实接线还涉及 `SkillAdapters.executeLocalProcess:487–511`（当前丢弃 files、未传 runId）以及 `ProcessExecutionAdapter.invoke`；`ProcessAdapter:375–383` 返回前 finally 已删除 callDir，文件转存必须在既有生命周期内完成。这些范围应在 15b 开工前回填，不能仅改协议 record 就宣称文件已持久。
- EX-16 与 EX-13 还共享 `RuntimeClients.java`/`SkillAdapters.java`，与后续工具调用/IAM 卡共享 RunService 与授权上下文，需串行冻结。当前只读结论不构成 EX-16 已就绪实施或已验收。
