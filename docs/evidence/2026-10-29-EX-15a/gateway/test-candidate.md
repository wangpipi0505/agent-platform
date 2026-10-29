# EX-15a 网关请求边界：test-only 候选

2026-10-29。作者：`artifact_boundary_review`。本文件是实现说明，**不是独立审查或实际运行回执**。主线程确认主卡白名单后仅新增本测试、本文和 `run-selected.ps1`；没有改生产、既有测试、Git index，也没有编译、运行测试、调用业务 API/PG/MinIO 或启动服务。

## 固定输入

| 文件 | raw SHA-256 |
| --- | --- |
| `platform-gateway/src/main/java/cn/agentplatform/gateway/api/GatewayController.java`（只读，原 2 MiB） | `d920ea867ff02f0433aa65bc33540b7ca17a9799f2cb910be95134edd756ae2b` |
| `platform-gateway/src/test/java/cn/agentplatform/gateway/ArtifactUploadGatewayTest.java`（新增 11 方法） | `b4fcd5ab65dc80f0f509c31c1f5bd9ce56d1dd57942ed9d802f8198d99994f32` |
| `docs/evidence/2026-10-29-EX-15a/gateway/run-selected.ps1` | `5750ea69ec0b71bd748834290f2478eb4619d4bb2481dfb39ffd8f016a83b202` |

静态核对的已安装 `admin-common-0.1.0-SNAPSHOT.jar` 为 `1b0d7b40fc3ae02b207294d3b6be7ea7f7bb9dcd35af8ea62a372f916fa14b87`；运行器会重新记录实用 jar 摘要。脚本保存为 UTF-8 BOM（`EF-BB-BF`），实际调用 System32 `powershell.exe` 的 **5.1.26100.9711** AST Parser，语法错误 0，未执行脚本。Java 的 11 个 `@Test` 名称已静态清点，尚无编译或测试结论。

## 断言与证明边界

直接调用真实 `GatewayController.proxy`，`MockHttpServletRequest` 仅承载方法、路径、Content-Type、已知/未知 Content-Length 和生成式 `ServletInputStream`；上游是每次新建的真实 `127.0.0.1:随机端口` HTTP server。没有 MockMvc 大数组，也没有 mock Controller、路由、`IntegrationAccess.check` 或代理转发。

用户测试票据先由 Nimbus 校验签名、issuer 和时间，再显式装入 SecurityContext。**这属于已认证上下文测试前提，不是正式入口 FilterChain/完整入站 HTTP 的验收。** 上游会实际验内部票据签名，并断言 tenant/principal、内部 audience、`token_use=service`、`EDITOR+SERVICE` 以及没有转发原用户票据；伪造 `X-Tenant-Id`、`X-User-Id`、`X-User-Roles` 不能改变这些断言。不打印任何票据。

11 方法的预期如下；旧生产的 **7P4F/native 1 仅为预测**，必须由主线程取得实际新 XML，不能把预测写成实红：

| 方法/组 | 数量 | 断言 |
| --- | ---: | --- |
| `forwards64MiBMultipartWithKnownLengthAndVerifiedInternalIdentity` / `forwards64MiBMultipartWithoutContentLength` | 2 | 文件恰为 64 MiB，multipart wire 加 framing；已知长度及未知长度均 200，逐字节总长、wire SHA、文件 SHA 一致；包含合法 quoted boundary 和大小写媒体类型 |
| `acceptsExactly65MiBTotalWireWithKnownLength` / `acceptsExactly65MiBTotalWireWithoutContentLength` | 2 | 精确 65 MiB 总 wire 通过，不把 65 MiB 当文件上限 |
| `rejects65MiBTotalWirePlusOneWithKnownLength` / `rejects65MiBTotalWirePlusOneWithoutContentLength` | 2 | 多 1 字节仍为原 502/`DEPENDENCY_UNAVAILABLE`；上游接收不超过 65 MiB，输入最多越界一次 8 KiB 读取 |
| `ordinaryRequestsKeepExact2MiBAllowance` / `ordinaryRequestsReject2MiBPlusOneIncludingUnknownLength` | 2 | 普通 JSON 请求已知/未知长度的 2 MiB 与 +1，保持原边界 |
| `putMultipartDoesNotReceiveUploadAllowance` | 1 | PUT 即使同路径和 multipart 仍为 2 MiB |
| `otherRoutesAndArtifactDownloadPathKeep2MiB` | 1 | `/admin/skills` 与有合法路由的 `/admin/artifacts/art_fixture/download` 不因 multipart 放宽 |
| `missingInvalidSimilarAndWildcardContentTypesKeep2MiB` | 1 | 缺失、空串、JSON、缺 subtype、`multipart/form-datax`、`multipart/*`、`*/*`、未闭合 boundary 引号均保留 2 MiB |

65 MiB wire 正例中的文件尺寸为 `65 MiB - framing`，会大于下游 64 MiB 文件限制。这里的上游只是 wire 接收器；该正例只验证网关总请求门，不宣称生产下游会接受这个文件。真正 64 MiB 文件正例与该 wire 边界分开。`Content-Length` 只作请求元数据；负例也覆盖未知长度，不以声明长度替代真实流计数。

payload 由确定性字节流生成；framing 是短 ASCII 数组。接收、摘要、生成读块显式缓冲均不超过 8192 字节，没有完整 payload `byte[]`、临时文件或 `readAllBytes`。每次生成新的随机 tenant 仅作为票据字段，不创建业务行或对象。

## 资源和运行权限

每次调用只拥有一个随机 loopback HTTP listener、一个 peer worker 和一个 deadline worker。30 秒期限会 `peer.stop(0)` 关闭本次交换并解除代理 I/O 阻塞；finally 停 listener、关闭由夹具拥有的 Servlet 输入、恢复 SecurityContext，并分别最多等待 5 秒线程退出。JUnit 单方法 60 秒，Surefire fork 180 秒提供外层上界。输入由夹具关闭是 Servlet 所有权约定，不能把这个断言称为生产 Controller 主动关闭输入的证明。

`run-selected.ps1` 是专用定向采集器：

- `-Phase red` 先核原生产 SHA，避免修复后冒充先红；运行目录必须新建，旧目录不覆盖。
- 不读取 `.env`，不访问 PG/MinIO、正式服务或固定端口；Java 进程只使用公开 fixture key，运行后恢复调用进程原环境。拒绝继承非空 `JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`_JAVA_OPTIONS`、`MAVEN_OPTS`，不把隐藏 JVM 参数带入本轮。
- `platform-gateway` 的这一类单独运行，`reuseForks=false`、`forkCount=1`、`-Xmx256m`、`surefire.timeout=180`、明确 `maven.test.failure.ignore=false`。不执行 clean/install，也不修改 Java 源码。
- 保留原生 Maven 日志与退出值、新鲜该类 XML、逐方法结果、选中方法清单，以及 gateway/admin-common 的全部 src 和三个 pom 的轮前/轮后 SHA；不复制旧 XML 冒充本轮。HEAD 和已安装 admin-common jar SHA 均前后复核为 `contextStable`；HEAD 动态记录，不硬绑某次文档提交。XML 的 `XmlResolver=null`。
- 先记录原生退出，再计算 `phaseExpectationMet`；红预期要求 11 身份完整、7P4F、4 条指定大请求失败、0E/0S、源码和上下文稳定、native 1。绿预期要求 11P、0F/0E/0S、源码和上下文稳定、native 0。任何不匹配都保留真实结果供归因，不修改断言去凑预测。
- 本脚本返回 Maven 原生退出；红轮退出 1 是事实，不能按它伪造 collector 成功或整卡通过。

静态审查通过后，仅主线程在唯一实际窗口执行，RunId 当场取唯一值：

```powershell
powershell.exe -NoProfile -File ./docs/evidence/2026-10-29-EX-15a/gateway/run-selected.ps1 -RunId gateway-red-YYYYMMDD-HHMMSS -Phase red
```

本候选不授权测试作者开实际窗口。生产修复仍须等实红归因，且只限主卡规定的 POST 精确路径/严格 MediaType 选择 65 MiB。全 gateway 必须另对照 EX-27 `safety-module-20261028-233458-012` 的原 27 类、288 记录（273P/7F/8E），原 288 身份及 15 个非通过原因保留，新 11 个方法另计；不以本类通过代替模块门。正式 18080 入站验签、PG/MinIO 64 MiB 链与新 skill JVM 同 artifact 读回仍待独立 live 验收，EX-15a 尚未闭环。
