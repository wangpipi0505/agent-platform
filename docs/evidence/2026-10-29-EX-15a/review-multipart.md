# EX-15a multipart 最小修复独立复核

日期：2026-10-29。复核者：`algorithm_interrupt_readonly`，独立于实现者。范围仅为两处生产修复、未变化的 `MultipartStreamingTest` 和本卡既有红绿原件。复核只读取源码、Git 差异、JSON 与原 XML；没有运行 Java/Maven、PG、HTTP 或仓库测试，没有读取 `.env`，未修改实现、测试、原件、主入口或 Git index。不使用 Superpower。

## 结论

两处最小修复未发现阻断，可继续原 artifact/multipart 范围回归及本卡剩余持久链验收。**本结论只接受两个已实证缺口的定向修复，不关闭整张 EX-15a，也不声称 skill 模块全绿。** 原红证据保留。

## 冻结身份

以下为复核时工作区及绿轮 source manifest 的真实字节 SHA-256：

| 文件 | SHA-256 |
|---|---|
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/MultipartFormMapping.java` | `d58ac69edce07090d3c3800da2e2785318d00dcf8acb5badbdc0572fb0fb95d9` |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/SkillAdapters.java` | `7e4b33db18435af0429b7ea483be3d74c9bf73151912e0eeaf16ab7385c8935a` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/MultipartStreamingTest.java` | `64a981a93e22351645fa3d9bd77671a0d1f84db0740e6585737114bd6046eb1e` |

两轮记录 HEAD 均为 `67d1d41dc599a99ecad4654a4736446e076f84f7`。复核比较两轮各自的 `source-before.json` 与 `source-after.json`：各含 150 个相同源文件，轮内差异均为零；红到绿恰好两处生产 SHA 变化，与上表相符，其余 148 份不变。测试始终为同一 `64a981…`，原七方法和断言未删改；新四方法也未为变绿而改写。两个生产文件的 `git diff --check` 无问题。

## 源码审查

1. **生产生成端与解析端一致。** `ArtifactStore.reference` 真实生成 `/admin/artifacts/art_<32hex>/download` 及 `/v1/runs/<run>/artifacts/art_<32hex>/download`。`MultipartFormMapping.load` 仅在已有受控路径正则中加入可选 `art_`，仍使用 `matcher.matches()` 全串匹配，并归一为同一 opaque `art_<32hex>`。直接 artifact ID 和历史裸 hex 路径均保留；没有接纳完整外部 URL、查询串、物理对象键或任意路径。解析后仍经同一 `FileSource` / `ArtifactStore.download` 所有权、READY、有效期路径，不把 URL 当作授权。
2. **请求拥有已装载部分的关闭责任。** `SkillAdapters.http` 将本次 `MultipartFormMapping.build` 返回的集合保存在局部变量，并在原请求 `finally` 调 `closeFiles`。`closeFiles` 仅遍历实际 `HttpEntity<FilePartBody>`，复用 `FilePartBody.close` 的 CAS 和现有 `Download.close`；已经被转换器关闭的源流不会二次关闭，尚未被消费的部分也有关闭责任方。构建中途抛错时赋值尚未完成，仍由原 build 的局部回收负责，外层 null 路径无额外动作。
3. **顺序和原边界保留。** 统一关闭发生在同步 `request.exchange` 返回或抛错之后；没有在写出前关闭资源，也没有新增预读、byte[] 缓冲、重试或另一个执行器。原 `Failure`/通用异常分类及 PATCH 客户端关闭保留；异步 multipart 拒绝、64 MiB 限额、仅消费一次、短流/超限语义未改。
4. **测试确实经过被修路径。** 两个 URL 来自真实 `controlledReference → owned → reference`，测试只替代持久查询。两例失败请求经过真实 `SkillAdapters.http`、HTTP 请求工厂、表单转换器及 loopback 对端；下载字节源为有界合成流。装载集合、第一流读次数、第二流零读取及每流只关闭一次均有原断言。对端断连例另要求真实收到请求。测试没有把模拟字节源或 JDBC 返回行写成真实持久验收。

## 原始红绿证据复算

复核直接解析以下原 XML 的 `testcase` 与 `failure/error/skipped` 子节点，并逐方法比较身份，未运行采集脚本：

| 原件目录 | 原生退出码 | XML 方法身份 | 独立复算 | XML SHA-256 |
|---|---:|---:|---|---|
| `red-20261029-1240/` | 1 | 11 | 7 PASS / 2 FAILURE / 2 ERROR / 0 SKIP | `c0f1a64341968e78de040a68e362af0620951d0a472feb0e35974406cedda063` |
| `green-20261029-1247/` | 0 | 11 | 11 PASS / 0 FAILURE / 0 ERROR / 0 SKIP | `9ca45459e60e079b044308e8cc7a72dd708fe4cbada183b074073c7f02bcdbf0` |

两轮 `context.json` 均记录 `failureIgnore=false`，原方法集合完全相同，原七 PASS 全部保持。四个原非通过的精确停点与绿轮结果为：

| 原方法 | 红轮停点 | 绿轮 |
|---|---|---|
| `真实产物引用生成器的门户下载路径可直接用于文件参数` | `SkillAdapters$Failure`：文件参数必须为产物ID或受控下载路径；生产 URL 被解析器拒绝 | PASS |
| `真实产物引用生成器的运行下载路径可直接用于文件参数` | 同一解析拒绝 | PASS |
| `真实出站第一文件断流后关闭尚未消费的第二文件` | `MultipleFailuresError` 内唯一失败：后续已装载部分 close 预期 1、实际 0 | PASS |
| `真实出站对端断连后关闭全部已装载文件` | `MultipleFailuresError` 内唯一失败：未消费的已装载部分 close 预期 1、实际 0 | PASS |

这不是只比较总数：原两个 ERROR 与两个 FAILURE 的身份、类型和停点均吻合预定缺口，原七 PASS 未变化，修复后完整新增后置断言全部走完。轮内 sourceStable 由 manifest 比较另行核实，不仅采信 summary 标记。原记录环境配置 SHA 与 common JAR SHA 两轮相同；本复核只读取该安全指纹，没有装载凭据。

## 未覆盖及后续门

- 以上是 URL 生成/解析和真实 HTTP 失败资源生命周期的定向证据；没有证明真实 PG/MinIO 上传、真实存储下载、JWT 验签或跨租户完整服务链。
- 64 MiB 合成流没有常驻分配整份文件；本轮断连不等于 64 MiB 完整上传/下载/出站成功，也不构成任意大 JSON 满足原 Schema/内存限制的证据。
- 本卡仍需按主入口完成 artifact 四类及相关 multipart/异步拒绝回归、当前 skill 完整方法与原原因对照，以及真实 64 MiB 持久链、digest、fresh-store/重启可读、tenant/principal/run/有效期拒绝、精确登记键清理。既有实现和原证据可逐项核实，不能用此 11 PASS 替代剩余门。
- 若后续扩大源码、测试或环境冻结栈，须以新 RunId 留存新证据；不得覆盖本次原红或将未完成项提前关闭。

本复核唯一新增文件为本报告；实现、测试及既有红绿原件保持原样。
