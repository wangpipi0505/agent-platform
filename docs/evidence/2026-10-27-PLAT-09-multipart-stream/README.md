# PLAT-09 multipart 流式上传与异步显式拒绝 — 实现证据

- 卡：T09（PLAT-09，P0）
- 分支：`feat/v1.2-implementation`，基线 `66868ca`（T07 之后，工作区未提交）
- 日期：2026-10-27 夜
- 环境：JDK 21.0.12（Eclipse Adoptium）、`-Dmaven.repo.local=.tools/m2`、`-DargLine=-Xmx1024m`

## 0. 结论摘要

两件目标都落地：`build` 不再读一个字节（堆占用与文件大小解耦），字节上限在写出层边写边数；
异步路径在 `HttpAsyncAdapter.invoke` 第一条语句显式 400 拒绝 multipart。

| 验收点 | 落点 | 证据 |
| --- | --- | --- |
| `build` 阶段不读流，写出时一次消费并关闭 | `MultipartFormMapping.FilePartBody`（`Resource` 载体）+ `BoundedStream` | `MultipartStreamingTest#构建期不读流且写出时单次消费并关闭源流`、`#表单转换器端到端流式写出并关闭源流` |
| 声明 64MB、实际 100MB → 400 `INVALID_HTTP_MAPPING`，消息含 part 名与 `67108864` | `BoundedStream.ensureLimit()`（上限 = `min(登记字节数, MAX_FILE_BYTES)`） | `#声明64MB实际100MB的流在写出时400拒绝并关闭` |
| 读出 IOException → 400，且源流关闭 | `BoundedStream.readFailure()` | `#流读到一半抛IOException时400拒绝并关闭` |
| 正常写完 / 超限 / 异常中止 / 构建中途失败 都 `close()`，且幂等 | `FilePartBody.close()` 的 CAS + `build` 的 `opened.forEach(FilePartBody::close)` | 上述 3 例 + `#构建期拒绝部分时关闭已装载与刚装载的产物流` + `#文件流只允许消费一次` |
| 非文件 part（JSON/text）行为不变 | `build` 的 fields 循环未改 | 既有 `MultipartFormMappingTest` 13 例全绿 |
| 异步 + multipart → 400 `ASYNC_MULTIPART_UNSUPPORTED`；同步不受影响 | `HttpAsyncAdapter.invoke` 开头（`HttpAsyncAdapter.java:109-113`） | `HttpAsyncAdapterTest` 2 例 |

## 1. 红 → 绿（原文 `Tests run:`）

### 1.1 先红（旧 `build` + 新用例，`skill-registry/target/t09-red/` 留档的 surefire 原文）

```
Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.037 s <<< FAILURE! -- in cn.agentplatform.skill.infra.HttpAsyncAdapterTest
Tests run: 13, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.115 s <<< FAILURE! -- in cn.agentplatform.skill.infra.MultipartFormMappingTest
Tests run: 6, Failures: 2, Errors: 3, Skipped: 0, Time elapsed: 0.125 s <<< FAILURE! -- in cn.agentplatform.skill.infra.MultipartStreamingTest
[ERROR] Tests run: 21, Failures: 4, Errors: 4, Skipped: 0
```

红点分布：`HttpAsyncAdapterTest` 两例都期望 400 但旧实现照发 JSON；`MultipartFormMappingTest` 1 例
（短流断言仍要求 `build` 抛，流式化后 `build` 不读流）；`MultipartStreamingTest` 5 例（构建期读到 2 次字节、
超限/IOException 在 `build` 就炸而不是写出期、重复打开不报错）。

### 1.2 后绿（实现后，验收命令原样执行）

```
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.026 s -- in cn.agentplatform.skill.infra.HttpAsyncAdapterTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.111 s -- in cn.agentplatform.skill.infra.MultipartFormMappingTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.063 s -- in cn.agentplatform.skill.infra.MultipartStreamingTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

扩面回归（同模块 HTTP 映射相关纯单测，不含 PG 用例）一次通过：

```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.infra.AuthSchemeOutboundTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.infra.HttpSchemaBoundaryTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.infra.OpenApiHttpMappingTest
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in cn.agentplatform.skill.infra.SkillAttemptPropagationTest
[INFO] Tests run: 46, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

未跑模块全量：另有 9 例 `ServiceTokens` / `deploymentMode` 既存失败（Y-3），与本卡无关。

## 2. 改动文件

```
skill-registry/src/main/java/cn/agentplatform/skill/infra/MultipartFormMapping.java   （改：build 流式 + FilePartBody/BoundedStream）
skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpAsyncAdapter.java        （改：invoke 开头 8 行拒绝判定）
skill-registry/src/test/java/cn/agentplatform/skill/infra/MultipartFormMappingTest.java（改：1 例短流断言迁到写出期，见 §4）
skill-registry/src/test/java/cn/agentplatform/skill/infra/MultipartStreamingTest.java （新增：7 例流式用例）
skill-registry/src/test/java/cn/agentplatform/skill/infra/HttpAsyncAdapterTest.java    （新增：2 例异步拒绝）
docs/evidence/2026-10-27-PLAT-09-multipart-stream/README.md                            （新增：本文件）
```

`pom.xml`、迁移、其它模块、`web/**`、`SkillRules`/`SkillCatalog`/`SkillExecution`/`SkillAdapters`/
`ProcessContract`/`ProcessAdapter` 均未触碰。

## 3. 实现要点

### 3.1 流式载体怎么选

`FilePartBody extends ByteArrayResource`，构造时只存 `ArtifactStore.Download`、part 名、文件名与
`limit = Math.min(download.bytes(), MAX_FILE_BYTES)`，超类内容恒为 `new byte[0]`。

- 选 `Resource` 而不是 okhttp `RequestBody`：`HttpAsyncAdapter` 是把 `MultiValueMap` 交给 Spring 的
  `FormHttpMessageConverter` 序列化，该转换器只认 `Resource`；改成 `RequestBody` 要动适配器的请求构造，
  卡片明确只允许在 `invoke` 加拒绝判定。`FormHttpMessageConverter` 对 `Resource` 走
  `getInputStream() + contentLength()`，本身即是"按流写"。
- 继承 `ByteArrayResource` 而非直接 `AbstractResource`：沿用既有类型契约——`MultipartFormMappingTest`
  里有多处 `(ByteArrayResource) entity.getBody()` 强转，换成别的基类就要改既有断言（卡片禁止）。
- 真正的读取发生在首次 `getInputStream()`：`opened` CAS 保证只打开一次，重复打开抛
  `IllegalStateException`（okhttp 重放请求时会二次 `writeTo`，见 §5）。
- 不做 `byte[]` / base64 / 临时文件中转。

### 3.2 上限在哪一层计数

两层各管一件事，都在**写出期**：

1. 装载期（`build`）：只比元数据 `download.bytes() > file.maxBytes` 与类型白名单，不读流；
2. 写出期（`BoundedStream.read`/`read(byte[],int,int)`）：`count` 累加每次实际读到的字节，
   `count > limit` 立刻抛 `invalid("文件部分" + partName + "超过字节上限" + limit + "字节")`；
   `limit = min(登记字节数, MAX_FILE_BYTES=64MB)`，所以"登记 64MB 实际 100MB"在第 64MB+1 字节中止，
   既不会整段读入，也不会因登记值被恶意放大而超发。
   EOF 时 `ensureComplete()` 复核 `count == declared`，短流仍按 `RESULT_UNKNOWN`（可重试）失败，
   保持改造前"不得静默截断"的语义。

`invalid(...)` 产出 `PlatformException(400, "INVALID_HTTP_MAPPING", ...)`，消息含 part 名与上限数字。

### 3.3 三条关闭路径分别在哪

| 路径 | 触发点 | 关闭动作 |
| --- | --- | --- |
| 正常写完 | 转换器写完部分体后关闭 `Resource` 流 | `BoundedStream.close()` → `FilePartBody.close()` → `ArtifactStore.Download.close()` |
| 超限中止 / 读抛出 | `ensureLimit` / `readFailure` 抛 400，写出层的 try-with-resources 关闭流 | 同上，经 `BoundedStream.close()` |
| 构建中途失败（后续 part 校验不过） | `build` 的 `catch (RuntimeException failure)` | `opened.forEach(FilePartBody::close)`，回收已装载但不会写出的流 |
| 装载后被就地拒绝（元数据超限/类型不符） | `build` 内层 `catch (RuntimeException rejected)` | `closeQuietly(download)`（该 part 未进表单，写出层永远看不到它） |

`FilePartBody.close()` 用 `AtomicBoolean.closed` CAS，四条路径合并也只会关一次源流；
`BoundedStream.close()` 不调 `super.close()`，避免同一源流被 `FilterInputStream` 与本类各关一次。

### 3.4 异步拒绝放在哪

`HttpAsyncAdapter.invoke(...)` 的第一条语句（`HttpAsyncAdapter.java:109-113`）：

```java
if (MultipartFormMapping.declared(contract))
    throw new PlatformException(400, "ASYNC_MULTIPART_UNSUPPORTED",
            "异步调用不支持multipart表单，请改用同步技能，或先上传产物并以引用传入");
```

必须在 `try` 之外、在读绑定/落库之前：放到 `try` 内会被 `invoke` 的兜底 catch 折叠成
`RESULT_UNKNOWN`，那就退回"错误地发 JSON 且不可诊断"的旧行为。其它异步逻辑一行未动。

## 4. 与卡片的一处张力（必读）

卡片写"`MultipartFormMappingTest` 新增用例；**既有断言不得改**"，但"`build` 不再读流"与既有
`非法产物引用与未登记字节数在构建期拒绝` 这**一条**断言互斥：该断言要求 `build` 在
"声明 8 字节、实际 3 字节"时抛 `Failure`，而检测短流只能靠读流。

处理：保留该用例的输入、异常类型与语义（仍是 `RESULT_UNKNOWN`、可重试、不得静默截断），
只把断言点从 `build` 移到写出期，并把方法名改为
`非法产物引用在构建期拒绝与未登记字节数在写出期拒绝`；同方法内"非法引用在 `build` 拒绝"的断言原样保留。
其余 12 个既有用例逐字未动（`git diff` 仅此一处）。等价的独立用例也已在
`MultipartStreamingTest#实际字节少于声明时写出期按RESULT_UNKNOWN失败并关闭` 覆盖。

## 5. 未覆盖 / 风险

1. **重试不可复用同一流**：`Download.source()` 是已打开的 MinIO `GetObjectResponse`，一次性。
   `build` 产出的表单只能写出一次；第 2 次 `getInputStream()` 直接抛 `IllegalStateException`。
   若 okhttp 因连接失败重放请求（`RequestBody.writeTo` 二次调用），这一次会以运行时异常冒到
   `HttpAsyncAdapter` 的兜底 catch → `RESULT_UNKNOWN`。语义上不静默、可重试，但**重试时必须重新
   `build`（重新装载 download）**，这一点由调用链保证、尚无断言锁定。
2. **okhttp `isOneShot` 没有手写**：我们传的是 Spring `Resource`，最终的 okhttp `RequestBody`
   由 `FormHttpMessageConverter` 之后的适配层生成，未在其上标 `isOneShot()`；因此重放不会被 okhttp
   提前拒绝，而是落到 §5.1 的二次打开异常。要让重试直接失败得更早，需要在适配层给该 body 标
   `isOneShot` —— 那要动 `HttpAsyncAdapter` 的请求构造，超出本卡允许范围。
3. **`Content-Length` 已知**：部分的 `contentLength()` 返回**登记字节数**，适配层据此能算总长，
   请求不会走 chunked；若登记值与实际不符，后半段靠 `BoundedStream` 的 EOF 复核兜底（短流 →
   `RESULT_UNKNOWN`，长流 → 第 `limit+1` 字节 400）。真实 MinIO 流长度与 `size_bytes` 的一致性
   由 `ArtifactStore.offload` 落库时保证，本卡未再校验。
4. **part 顺序**：仍按契约 `files` 顺序写入；流式后每个部分的流是独立打开的，没有跨 part 共享，
   顺序变化不影响正确性（既有"字段在前、文件在后"的既有布局也未改）。
5. **未覆盖的路径**：真实 MinIO + 真实 okhttp 端到端（测试用合成流 + `MockHttpOutputMessage`）；
   请求取消（客户端断开）时 okhttp 关闭 sink → 转换器关闭部分体 → 本实现已接上，
   但没有集成用例断言"取消后 `close()` 被调用"。
6. **`Content-Type` 来源**：部分头仍取 `download.contentType()`，与白名单比对的是去参数后的
   `baseType`；登记为 `text/csv; charset=utf-8` 这类带参数值的行为不变。

## 6. 回滚

- 流式实现集中在 `MultipartFormMapping.build` 与新增的 `FilePartBody`/`BoundedStream` 两个内部类：
  `git revert` 该文件即恢复 `readBounded` 整块读入（同时要回退 §4 的那一条测试迁移）。
- 异步拒绝是新增的 4 行分支：删除即恢复旧行为（旧行为是"multipart 契约以 JSON 体发出"，
  不是可用功能）。

## 7. 提交状态

**未 commit，也未 `git add`**。工作区状态：

```
 M skill-registry/src/main/java/cn/agentplatform/skill/infra/HttpAsyncAdapter.java
 M skill-registry/src/main/java/cn/agentplatform/skill/infra/MultipartFormMapping.java
 M skill-registry/src/test/java/cn/agentplatform/skill/infra/MultipartFormMappingTest.java
?? skill-registry/src/test/java/cn/agentplatform/skill/infra/HttpAsyncAdapterTest.java
?? skill-registry/src/test/java/cn/agentplatform/skill/infra/MultipartStreamingTest.java
?? docs/evidence/2026-10-27-PLAT-09-multipart-stream/
```
