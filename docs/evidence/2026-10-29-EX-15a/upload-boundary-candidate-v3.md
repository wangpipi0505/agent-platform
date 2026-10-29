# EX-15a 上传边界候选 v3：精确修正 MinIO 异常解包

日期：2026-10-29。准备者：`ex04_pg_scope`。

本轮只改 `ArtifactPersistenceTest.assertUploadFailureCleaned` 的一层已知包装解包并新增 import；所有 16 个测试、长度/引用/READY/删除/关闭/摘要断言保留，`ArtifactStore` 未改。没有运行 Java/Maven/PG/MinIO/HTTP。v3 尚未实跑，不登记新的通过结论。

## 原红与版本来源

- 初始测试候选：[upload-boundary-candidate.md](upload-boundary-candidate.md)，raw SHA `517fbfb7cb2b035fc4fc111c90f928ab0d81bda74596cbc6091cb71e6dfbad1f`。
- 主线程在独立审阅后，将原 retention 用例固定 `unrelated-tenant` 改为 `tenant + "-unrelated"`，保持原 0/READY 断言，以消除触及别的测试数据的风险。实际 v2 raw SHA 为 `29c970e3c80cdae62b519e5fb8b7fc940ea28e6cc816fa1ffe497d342b014a0e`；本轮读取确认并完整保留。
- 原实际轮：[upload-red-20261029-1250/summary.json](upload-red-20261029-1250/summary.json)，native 1、16=13P/3F/0E/0S、sourceStable=true。64 MiB 上传→原 store 关闭→新 store 下载→真实 HTTP 完整出站的正例为 P；这是组件重建证据，仍不是 JVM 重启证据。
- 原 XML SHA：`6c39fc562b086a85a7929e83c45e5545431f9f0251c8bec173e79571c01849aa`；原 summary SHA：`c55829ef995b6c74bc6e995ef3c64051c020b842d9891db5837971a4c71c7711`。本轮未改原红文件。

三项 F 必须分开记：

1. `longUploadNeverBecomesReadyAndCleansExactObject`：声明 3/实际源 8，真实上传返回 READY 引用并报告 bytes=8/源摘要；“必须拒绝”“不得返回引用”“必须 DELETED”三项失败。不能据此推断 MinIO 实际对象体也是 8 字节；原轮未用独立下载测其对象体。这是仍待修的产品边界缺口，不能以 SDK 行为或其他用例通过豁免。
2. `shortUploadNeverBecomesReadyAndCleansExactObject`：唯一失败为 helper 期待直接 `ErrorResponseException`，实际是 `CompletionException`。已有拒绝、无引用、DELETED/UPLOAD_FAILED/deleted_at/关闭计数断言通过；**NoSuchKey 断言尚未执行，不可提前宣称物理对象不存在已证实**。
3. `brokenUploadCleansExactObjectAndClosesSource`：同一 helper 包装判断失败；物理不存在结论同样待 v3 严格断言确认。方法中 helper 之后的断流位置断言原轮也未到达，不能提前算通过。

## 依赖与精确包装链

本次只用 Python 读取本仓固定 JAR 的 ZIP/class 常量池与字节码，没有运行 JVM 或下载依赖。

- 依赖：`.tools/m2/io/minio/minio/8.5.17/minio-8.5.17.jar`。
- JAR SHA-256：`9cd43ea8c79ca3c6ce81e7d4be81c5c8115febd2750f493f699fa41b92e209b3`。
- `MinioAsyncClient.statObject` 委托 `S3Base.statObjectAsync`；后者通过 `executeHeadAsync` 取得 future。
- `S3Base.executeHeadAsync` 使用 `exceptionally`/`thenCompose`；其 `lambda$executeHeadAsync$3` 对传入 throwable 建立 `CompletionException(Throwable)`（字节码 29/34），而底层 `S3Base$1.onResponse(Response)` 构造 `ErrorResponseException`（1041/1055）并通过 `completeExceptionally` 交付（1066）。这与原 XML 中 `ExecutionException.getCause()` 为 `CompletionException` 一致。
- v3 严格验证固定链：`ExecutionException → CompletionException → ErrorResponseException`，随后仍必须 `errorResponse.code == "NoSuchKey"`。不循环剥壳，不接受任意 RuntimeException、权限失败、超时、网络失败或任意 404，不用 message 文本替代错误类型/服务错误码。

## v3 冻结与下一步

- `ArtifactPersistenceTest.java` raw SHA：`16cbd8fd8abe960ccc6b72fe7fb1c41dd5883fbd0a3f15f0298a1f46cab75d66`。
- 相对 v2：增加 `CompletionException` import，原一行直接 cause 断言替换为明确的包装类型断言与原最终类型断言；`NoSuchKey` 原断言字面不变。定向 `git diff --check` 返回 0。
- 先由主线程原环境重跑同 16 整类，验证包装纠正后真实物理不存在及断流位置，并确认只剩长流产品红；若下层链或 NoSuchKey 不符，应继续拒绝，不放宽本候选。
- 只有上述结果明确且独审通过，才批准 `ArtifactStore.upload` 的最小长度一致性修复。禁止改 AlgorithmIo、原 Schema/字节上限、迁移或全局错误语义。
