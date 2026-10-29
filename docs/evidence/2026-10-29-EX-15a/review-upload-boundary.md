# EX-15a 上传长度边界与持久链独立实质复核

日期：2026-10-29。复核者：`artifact_boundary_review`，独立于测试作者 `ex04_pg_scope` 和实际执行者 `root`。本次只读源码、差异、原 XML、运行清单及日志，独立复算方法身份、结果和摘要；唯一写入为本报告。未运行 Node、Java/Maven、PG、MinIO、HTTP 或仓库测试，未读取凭据、改代码、改 Git index 或提交。未使用 Superpower。

## 结论

**接受本上传子步，无阻断。** v3 测试先实证长流被错误接受，再由 `ArtifactStore.upload` 四行最小增补修复；同一 v3 测试完整 16 项通过，未改的 multipart 11 项再次通过。可继续卡片相关扩面和模块回归。本结论不关闭 EX-15a，不代替真正 JVM/服务进程重启、完整 skill 相对回归和最终精确提交。

上一子步的 URL/同步 HTTP 资源生命周期修复继续以 `review-multipart.md` 为准；本次没有把其 11 项结果冒充真实上传持久链，也没有扩大到 AlgorithmIo、异步、PROCESS、Schema 或迁移。

## 冻结身份与变化范围

| 文件 | 当前原始字节 SHA-256 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ArtifactStore.java` | `967849596ae6179a2b0858fb39d50a365c65f2370d4e2ad0658dfee44a0569b1` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ArtifactPersistenceTest.java` | `16cbd8fd8abe960ccc6b72fe7fb1c41dd5883fbd0a3f15f0298a1f46cab75d66` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/MultipartStreamingTest.java` | `64a981a93e22351645fa3d9bd77671a0d1f84db0740e6585737114bd6046eb1e` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/MultipartFormMapping.java` | `d58ac69edce07090d3c3800da2e2785318d00dcf8acb5badbdc0572fb0fb95d9` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | `7e4b33db18435af0429b7ea483be3d74c9bf73151912e0eeaf16ab7385c8935a` |

四轮各自的 source-before/source-after 均有 150 个源文件，独立比较轮内差异为零。v2 红到 v3 红仅测试从 `29c970e3c80cdae62b519e5fb8b7fc940ea28e6cc816fa1ffe497d342b014a0e` 变为上表版本；v3 红到上传绿仅 `ArtifactStore` 从 `9deb51566206d548110f1f6b5aaab466619eb80d43e8d30b67f11b7e27b7541d` 变为上表版本；上传绿到 multipart 绿的 150 源差异为零。当前 150 个源也逐项匹配上传绿清单。两个本子步文件 `git diff --check` 退出 0。

v3 的测试修正限定为新增 `CompletionException` import，按固定链 `ExecutionException → CompletionException → ErrorResponseException → NoSuchKey` 验证精确对象不存在。没有循环剥壳或把任意异常当删除成功。原长度、引用、READY、DELETED、错误事实、关闭次数、摘要等断言保留。原 retention 用例已在首次运行前改用 `tenant + "-unrelated"`，继续断言返回 0 和自身对象 READY；没有查询或清理旧固定租户。

## 原件独立复算

下表直接解析 methods.xml 的 testcase 及 failure/error/skipped 子节点，并核对 native-exit.json；没有仅采信 summary 的计数。

| 原件目录 | native | 复算结果 | XML SHA-256 |
| --- | ---: | --- | --- |
| `upload-red-20261029-1250` | 1 | 16 = 13P / 3F / 0E / 0S | `6c39fc562b086a85a7929e83c45e5545431f9f0251c8bec173e79571c01849aa` |
| `upload-red-v3-20261029-1257` | 1 | 16 = 15P / 1F / 0E / 0S | `4e3dfdf0fbc6e68de9a053807f023b278ed47bcf514dd6df63f58bf299ab4903` |
| `upload-green-20261029-1305` | 0 | 16P / 0F / 0E / 0S | `972091e72cc30001006d2f90db952cf2b6321f72fe7540106b6c017c2d9e971c` |
| `multipart-green-upload-20261029-1305` | 0 | 11P / 0F / 0E / 0S | `2cc199273dd21bea2af51c93f4e4f38d4993d55a41005965481292c67ab694d8` |

v2、v3、绿轮 16 项方法身份集合相同；multipart 本次 11 项也与前一绿轮的身份集合相同。所有 context 均记录 failureIgnore=false、同一环境指纹和 common JAR 指纹，使用完整指定类、1 GiB 堆及 900 秒 fork 超时；没有选择单个方法、跳过或忽略失败。运行脚本在解析 XML 前保存原生退出码并检查 XML 新鲜度。

v2 的三项失败分属两种原因：

- `longUploadNeverBecomesReadyAndCleansExactObject` 的拒绝、无引用、DELETED 三项同时失败，实际返回引用并记 bytes=8、状态 READY。v3 在产品未改时仍以相同三项断言失败，构成产品实红。原轮未独立下载该坏对象，不能据返回 metadata 推断 MinIO 对象体也是 8 字节。
- short/broken 两项唯一失败是 helper 错把 `CompletionException` 直接当作 `ErrorResponseException`；v2 未走到 NoSuchKey，broken 也未走到 helper 之后的精确断流位置断言。v3 保留强断言并补准确包装链后这两项通过，才实际证明其物理对象不存在及 broken 到达 5 MiB+13 的断流位置。

同一 v3 测试在生产修复后全部通过，长流不再返回引用，持久事实为 DELETED/UPLOAD_FAILED、deleted_at 非空，精确对象 stat 为 NoSuchKey，源流关闭一次。没有为了变绿改写测试。

## 最小修复和资源语义

`ArtifactStore.upload:523–526` 只增加两行说明和两行检查：在 SDK putObject 返回之后、现有 try-with-resources 内、READY 更新及引用返回之前，要求 `bounded.count == size` 且原 source 下一次读取为 EOF。

- SDK 若预读超出声明长度，由计数不等立即拒绝；若恰停在声明长度，直接探测原 source 的一个字节发现尾随数据。计数已不符时短路，不再读取尾部。
- 正确 EOF 不增加摘要或计数字节；单字节尾探测不上传、不进入成功摘要，也不分配大缓冲。超过声明的内容只进入拒绝路径。64 MiB 正确边界不会因为探测 EOF 被当成第 64 MiB+1 字节。
- 新 `400 ARTIFACT_SIZE_MISMATCH` 沿用原 catch：先将本次 tenant/artifact 置 CLEANUP_PENDING/UPLOAD_FAILED，再按本次登记的 bucket/object_key 清理，最后保留 PlatformException。删除失败继续保留原恢复机制；没有先 READY 再补救，也没有新建旁路状态或清理队列。
- 检查留在原 DigestInputStream 资源范围内，正常、大小不符和读取异常都继续由原资源关闭路径释放 source；没有新增一次 close、重试、线程或执行器。声明超限的原预检仍由调用方持有源流。
- 生产调用方仍仅为 `ArtifactController` 的 MultipartFile 输入；该改动不涉及 offload 的 JSON 校验、64 MiB 硬上限、AlgorithmIo 或其它适配器。测试实跑验证拒绝与补偿不变量；新 400/错误码由本次源码审查确认，测试没有另加该新码断言。

## 真实持久链与边界

存储前置原件记录 `agent_platform_v12|agent_v12|agent_v12|skill.artifact|0`、MinIO 29000 健康 200；脚本将 PG 固定到 127.0.0.1:25432，验证 MinIO 为本机 http/29000 且无 URI 用户信息。v3 红与上传绿日志均明确 skill schema 为 315、无需迁移；没有据硬编码 schemaMigrationChanged 字段单独推断迁移状态。

整类继续每例使用 `artifact-test-<UUID>`。SQL 清理限定该 tenant；个别按 artifactId 的旧操作只使用本例新建 ID。对象删除与 stat 均由该例持久登记键取得；补偿中 multipart 列表按精确键前缀且再次核完整 Key，不扫描未知桶对象。派生的 `tenant + "-unrelated"` 不创建对象；没有操作旧固定 tenant。

64 MiB 正例真实经过：按需源流 → upload → PG READY/长度/摘要 → 关闭原 store → 新 store 从 MinIO 下载并流入 DigestOutputStream → 生产下载 URL → SkillAdapters 同步 multipart → 本例动态 loopback HTTP 对端，最终核对完整长度、摘要、content-type、字段名及文件名。源每次最多生成 8 KiB，接收端以 8 KiB 缓冲处理文件且限制头与结束边界，没有 64 MiB 数组、字符串或 base64 替代该链。该方法在 v3 红轮和修复后的绿轮均真正通过。

HTTP 处理结束关闭 exchange，主测试 finally 停止 server；生产下载流仍经既有幂等关闭路径。30 秒配置在 POST 路径是读取超时，10 秒 future.get 是等待上限，不能冒称整个操作绝对在 10/30 秒内结束；本次运行保留 900 秒进程兜底且原生正常退出。原 11 项也保留 tenant/principal/run/有效期拒绝、精确清理、批量上限和清理重试覆盖。

## 未完成项

本次已证明真实 PG/MinIO 下的上传边界、精确失败清理及 64 MiB 上传/下载/完整同步 HTTP 出站；fresh-store 是同一 JVM 内组件重建，**没有证明真正 JVM/服务进程重启**。测试身份由 JWT fixture 注入，不能替代完整网关验签和跨服务授权验收。

ArtifactBoundTest、ArtifactReferenceTest、MultipartFormMappingTest、异步拒绝相邻回归、完整 skill 与既有非通过原因比较、真实进程重启及最终 index/本地提交仍由主线程继续收口。本子步不证明任意 64 MiB JSON 都符合原 token/string/Schema 限制，不扩大异步 multipart 承诺。原 v2/v3 红及上一子步原件均须保留。
