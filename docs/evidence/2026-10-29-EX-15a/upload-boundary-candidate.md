# EX-15a 上传边界与 64 MiB 成功链测试候选

日期：2026-10-29。准备者：`ex04_pg_scope`。

状态：**仅测试候选，未编译、未运行，不登记实红/实绿。** 主线程已报告上一子步 `red-20261029-1240` 为 11=7P/2F/2E、`green-20261029-1247` 为 11P；本文件不替代其原件。本子步只追加 `ArtifactPersistenceTest.java` 和本报告，`ArtifactStore.java` 未改，未改迁移、三主入口、index 或旧证据，不使用 superpower。

## 1. 精确范围及冻结

- 测试：`skill-registry/src/test/java/cn/agentplatform/skill/infra/ArtifactPersistenceTest.java`。
- 原 11 个测试、生命周期方法及断言保持不变，只增加 imports、5 个测试和仅本类使用的辅助代码；整类现为 16 个 `@Test`。`git diff --numstat` 为 `255 0`，该文件 `git diff --check` 返回 0。
- 候选原始字节 SHA-256：`517fbfb7cb2b035fc4fc111c90f928ab0d81bda74596cbc6091cb71e6dfbad1f`。
- 上传产品 `ArtifactStore.java` 原始字节 SHA-256 保持 `9deb51566206d548110f1f6b5aaab466619eb80d43e8d30b67f11b7e27b7541d`。
- 上一子步 `MultipartStreamingTest.java` 保持 `64a981a93e22351645fa3d9bd77671a0d1f84db0740e6585737114bd6046eb1e`，11 方法没有改变。

## 2. 新增方法与不变量

| 方法 | 真实调用与必须保留的断言 |
|---|---|
| `declaredUploadOverHardLimitDoesNotReadOrRegister` | 声明 64 MiB+1 调真实 `store.upload`；413/`ARTIFACT_TOO_LARGE`；源流读取次数为 0；本例 tenant 新登记行为 0。预检失败由调用方 try-with-resources 关闭，与 ArtifactController 的所有权边界一致，不冒称产品已经接管了预检前的源流。 |
| `shortUploadNeverBecomesReadyAndCleansExactObject` | 声明 8 字节、实际 3 字节；必须拒绝、不得返回引用、源流只关闭一次；本例唯一登记行为 DELETED/UPLOAD_FAILED/deleted_at 非空，精确对象 stat 为 NoSuchKey。 |
| `longUploadNeverBecomesReadyAndCleansExactObject` | 声明 3 字节、实际 8 字节；义务同上，禁止 MinIO 按声明截断后把前 3 字节当完整 READY 文件。当前 SDK/产品是否真会截断必须由实跑确认，不凭静态猜测改产品。 |
| `brokenUploadCleansExactObjectAndClosesSource` | 声明 6 MiB，在实际读取 5 MiB+13 字节时抛 IOException；拒绝/无引用/关闭一次/精确删除同上，并核确实到达断流位置，避免前置错误冒充目标红。 |
| `sixtyFourMiBUploadSurvivesFreshStoreAndStreamsToHttpWithMatchingDigest` | 按需生成恰 64 MiB → 真实上传 PG/MinIO → READY 行/返回值/源摘要和长度一致 → 关闭原 store → 新 store 下载至摘要 sink，核完整长度/摘要 → 由真实生产下载 URL 经同步 SkillAdapters/HTTP multipart 发至本例 loopback 对端 → 接收端完整文件长度/摘要/content-type/文件名一致。 |

大小不符或断流的辅助断言使用 `assertAll`，同时记录是否异常、是否错误返回了引用、关闭计数和实际持久状态。长流如错误成功，原 XML 会保留 READY/引用等断言失败；原 `@AfterEach` 仍只清理自身 tenant，不为保留失败而留下泄漏对象。

不预先规定短流/长流的新业务错误码；本子步首先验证拒绝及持久/资源不变量。只有已存在的声明超限码按原协议固定。任何实际失败必须先区分产品、SDK和夹具原因，不降低断言。

## 3. 夹具与实际执行准入边界

- 沿用原整类条件 `AGENT_MINIO_ENDPOINT`、原固定 PG `127.0.0.1:25432/agent_platform_v12`/`agent_v12` 和原 `skill` Flyway 初始化；本次没有在准备阶段查询/写入数据库或启动任何端点。主线程先核原环境/专库/owner/迁移/MinIO，再取得唯一实际窗口。环境未满足而跳过不算通过。
- 每个测试仍使用原 `artifact-test-<UUID>` tenant，原角色/主体 fixture；新负例只查询自己的 tenant 和其唯一对象。只对该持久登记的 bucket/object_key 做 stat，不列桶、不扫描或删除未知对象。
- 新负例每例最多 1 个登记对象；正例 1 个 64 MiB 对象。原 `cleanup` 将自身 tenant 的事实到期并 `cleanTenant(tenant,8)`，再关闭当前 store。正例将字段替换为新 store，因此原 teardown 负责新实例。未改旧 11 方法的清理边界。
- `UploadSource` 每次最多生成 8 KiB，按位置生成确定字节并累计 SHA-256；无大 byte[]、base64 或整文件字符串。注入断流精确到 5 MiB+13，不依赖计时/线程调度。
- 下载用 `DigestOutputStream(OutputStream.nullOutputStream(),digest)`；HTTP 接收器只有 8 KiB 文件缓冲、最多 16 个头、单头最多 8 KiB。只接受本例约定的一份文件、正确的 Content-Length 与结束 boundary，拒绝短帧、额外文件或尾随字节；没有泛化为新 multipart 框架。
- 正例 HTTP 仅本测试创建的 `127.0.0.1` 动态端口，contract timeout 30 秒，finally stop；对象 stat 和已收到结果的 future 均为最多 10 秒等待。没有改变既有测试、生产配置或主线程 Maven 超时/内存配置。
- 正例是**原存储组件关闭后冷重建**，并非 JVM/服务进程重启。真实 JVM 重启由主线程后续单列验收；不得用本例替代它。

## 4. 后续执行顺序

1. 独审候选及真实 PG/MinIO 前置；冻结 16 个方法和当前产品源。
2. 单次原整类采集退出码、完整 XML、方法身份与源前后指纹；保留失败原件。尤其先核长流是否按声明截断后 READY，不能先改 ArtifactStore。
3. 如产品缺口坐实，登记精确最小改动与错误/流所有权语义，再批准修复；不得先造表、占 V318 或改 AlgorithmIo。
4. 修复后保持全部 16 个方法及原 11 个 multipart 方法断言，完成定向、卡片相关扩面、模块基线比较与独立复核。
5. 真实 JVM 重启、主入口状态及本地提交另行收口。当前仍不能关闭 EX-15a。

## 5. 独立准入发现后的候选 v2（仍未实跑）

独立审阅者 artifact_boundary_review 指出原整类的固定 unrelated-tenant 清理可能触及非本例对象。主线程仅将该既有夹具改为本例 UUID tenant + "-unrelated"（不创建该租户对象），原 0 返回/READY 状态等断言完全保留。没有查询或清理旧固定 tenant；未动生产。这是测试数据隔离修正，不是放宽验收。上方初候选 SHA 和初始静态事实保留作历史。

候选 v2 raw SHA-256：`29c970e3c80cdae62b519e5fb8b7fc940ea28e6cc816fa1ffe497d342b014a0e`。首次实际整类运行必须使用 v2，仍需独立准入复核。
