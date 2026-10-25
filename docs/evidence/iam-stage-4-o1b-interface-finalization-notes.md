# O1b 接口最终化笔记：对照 O1a 冻结原件

状态：只读接口核对；候选设计输入，不是正式 brief，不是 O1b 实现或测试结果。O1a 已由 root 正式 Accepted；O1b 仍须 root 确定正式 brief、精确范围并移交唯一后端写权后才能实施。本文不修改已冻结的 O1b proposal。

## 1. 本次基线与核对边界

唯一工程及所有 shell workdir：`G:/207/项目/算法重组工具/代码/agent-platform`。O1a 交还基线为 source 578（后端 386、前端 192），target 1948；root 已正式通知 O1a Accepted：有限 641 项审计 PASS；独立 review Spec compliant / quality Approved，C0/I0/M3。独立报告为 `docs/evidence/review-iam-stage-4-o1a-run-metadata.md`，14309 bytes，SHA-256 `c28e50f9ba869c9b260982bd98294149a610e5f43549c3161c08831985426ee0`；八索引已由 root 更新，checkpoint manifest SHA-256 `04f6c2789ccbf9346e206debb511018a11a1d51374e0ae5f1bec407a3152a65b`。这些 Accepted/审计/review/index 状态及标识来自 root 交接，本次没有重读或重审它们。T01 唯一完成，原 v1.2 不动。

物理核对所得：

| 文件（工程相对路径） | Bytes | SHA-256 |
|---|---:|---|
| `docs/evidence/iam-stage-4-o1a-run-metadata/source-after.json` | 109562 | `719caa92b7dc1c33ee620e923247bb57bad45cee5c0b9c210401dc9fde463510` |
| `docs/evidence/iam-stage-4-o1a-run-metadata/target-after.json` | 402031 | `1404555e5bd1f0adfca1a3f74077d952dc9e9f62097a99dc60bf0ed2eed7bda2` |
| `docs/evidence/iam-stage-4-o1a-run-metadata-report.md` | 14351 | `f60db9ef148115e1591fda39eb1b0bc2ef5da45934fc5119238bd556818e3d9e` |

原 O1b 候选为 `docs/evidence/iam-stage-4-o1b-readonly-status-proposal.md`，28769 bytes，SHA-256 `d1d68162f3fd11a7921296d0e4861f991fec7102c9db39ec87f80cd896636888`。该候选的首次 write 建议由本文 §5 进一步限定，其余未重写。

本次全文读 O1a 交还报告；只读其 frozen-overlay 中八份相关原件的具体段落，未读当前活动 source、未读 O1a 测试全文、未重扫工程或 target、未重读之前四份 N4b2 frozen 方法。报告中的执行结果仅引用历史交还：common green02 七例、engine green03 十六例不是同一轮二十三例。未重新测试或探测。root 提示的 static HttpClient 未显式 close、SQL allowlist `.*` 两项 Minor 由后续台账处理，不为本次核对修改旧源或重跑。

## 2. 已冻结的真实 API 与可见性

### 2.1 common query selector 与路由

`RunObservationSelectors` 是 public final 类；public 常量 `KIND="run-observation-selector-v1"`、`ACTION="run.observation.metadata.read"`；private constructor。

- `public static ResourceBinding metadata(SubjectKey verified,String publicRunId)`：主体非空白且长度≤128；run id 匹配 `r_[0-9a-f]{32}`。id 为五元组 canonical JSON（schema、tenant、principal、run、projection）；digest 为 descriptor canonical SHA。它描述 subject/path 查询，不是 acceptedSnapshotSha。
- `public static void requirePurpose(ServiceCall call,String caller,String mode,String source,String origin)`：kind/action 两者都不是 query 时直接返回；只命中一者即拒绝；命中 query 时要求 gateway→engine、delegated、PORTAL/PORTAL。它目前**没有 status 规则**。
- `ServiceAuthorization.route` 101–119 在在线 authorize 前调用上述双向检查（105–106），然后核 token_use、authority_mode、target、action/resource，并依据实际 method 和 BEST_MATCHING_PATTERN_ATTRIBUTE 匹配 catalog。扩展同一 selector 类中的固定目的检查即可复用这次调用位置，未必需要再改 ServiceAuthorization。
- catalog 2018–2029 仅新增 `GET /internal/runs/{id}/observation-metadata`，query action，delegated，integrationAllowed=false。原 status 三行仍共用 `proxy.engine.get.RunController.status`：admin 794–804、debug 806–816、v1 818–828；v1 行 integrationAllowed=true。O1b 不得据此开放 v1/INTEGRATION，也不应为了 admin 独立读批量改 catalog。

### 2.2 engine query API / application / domain

`RunObservationMetadataController` public final：constructor 接收 metadata service；`public Object metadata(String id,HttpServletRequest request) throws IOException` 仅映射内部 metadata GET。其 18–22 行检查 REQUEST、GET、精确模板、contextPath+精确 URI、raw query（包含空串 bare ?）、content length 以及实际 body stream EOF；返回 `ApiResponse.ok(service.metadata(id))`，没有 status material 或输出能力。

`RunObservationMetadataService` public final，`@Service`；constructor 仅接收 `JdbcTemplate`，内部 `new RunAuthorityStore(dataSource,Clock.systemUTC())`，没有新增 IAM-only bean；`public RunObservationMetadata metadata(String publicRunId)`：先拒绝 ambient transaction，再由 live AuthorityContext actor 创建 SubjectKey、建 query selector、requireQuery；事务外 `requireInitialFor(actor,selector)` 刷新；调用 store；`Json.write(data).getBytes(UTF_8).length <=4096`；返回前 `requireBound(proof)`+requireQuery。`requireQuery` 是 private static，不能直接从 status 复用且目的不匹配。

`public record RunObservationMetadata(String runId,SubjectKey owner,ResourceBinding root,long appVersionId,long appVersionNo,String acceptedSnapshotSha256,String initialCeilingSha256)` 恰七字段。constructor 核 run 格式、owner 长度、app-version root、code@versionNo 格式、两个 positive long、三个 lowercase 64hex。root 是原执行 app-version，acceptedSnapshotSha 是独立观察资源的版本输入，不能将整个 root 直接作 status resource。record 无 retained/grant/actor/capability。4096 是 service 对 data 的限制，不是 record 自带全局限制，也不是完整 status 的输出上限。

### 2.3 infra metadata 与可复用装载接口

最终新增：`public RunObservationMetadata observationMetadata(SubjectKey owner,String publicId,RetainedAuthorization proof)`（85–116）。先拒绝任意 ambient transaction，创建新的 readOnly REPEATABLE_READ；入场 requireBound，并严格检查 query purpose、owner、selector/call/operation；窄 SQL 按 public id+owner 查询控制信息，排除非 TASK/FULL、debug/eval/controlled 标识和 tool_call。不存在/其他 owner 返回404；归属但控制损坏拒绝。调用 load(true)，构造七字段 DTO，事务内末尾 requireBound；事务 commit 后由 service 再终检。

已存在且本次 frozen 仍可直接使用的底层接口：

- `static Binding load(JdbcTemplate db,long runId,boolean complete)`（117–133）为 **package-private**；不建立事务、不联网。SELECT run 控制列+a.*；校验 run/authority/initial owner、positive versions、app-version root/code@no、PORTAL、hash 和 initial ceiling；complete=true 时读并校验三根 scopes。
- `public Binding load(long runId)`（83）调用 MaterialScopeStore.snapshot。O1b 已有 RR 内优先用上述 package-private overload；不要靠 public helper 的隐式加入行为证明顶层只读 RR。
- `Binding` 仍是数据载体，持有旧 delegation/initial/root/snapshot 等事实；不是读能力。仅仅调用 load(true) 不会证明本次 status purpose。
- `strict(Binding,RetainedAuthorization)`（155–158）仍是原执行用途检查：原 execution root、delegation、initial ceiling、origin 完全匹配；O1b 不调用、不放宽它。

可复用的是底层数据校验，不是 `observationMetadata` 的授权/事务包装。该 public 方法被 query action/kind 钉死，传新 status proof 会拒绝；在 O1b 的 RR 中调用还会因 ambient transaction 拒绝。这与候选中需要新 reader 的判断一致。

## 3. 候选哪些可直接保留，哪些须明确调整

| 候选接缝 | 冻结后裁决输入 |
|---|---|
| fresh query → fresh status exchange | 可保留。Q 的 selector 是 subject/path，M 是 `run-snapshot/publicRunId/acceptedSnapshotSha256`；M 不 resume/forward Q 或原执行 grant；Q DTO 仅帮助构造 M。 |
| 用 O1a service/store 获取 status 的受信 Binding | 必须改为新 status 路径自己的受信 PG 读取；不能调用 query 专用入口或把七字段 record 反转成 capability。 |
| 复用 O1a 完整三根 metadata 检查 | 可以在同一个 status RR 中调用包内 load(db,id,true)。还须复用/重做其 owner/public id、TASK/FULL、unsupported/tools 的窄控制校验，load(true) 本身不含全部用途排除。 |
| 顶层 RR 与 refresh 顺序 | 保留 ambient 先拒绝、网络 refresh 在 RR 外。status 已持有签名 run-snapshot resource，可先核其格式和路径，再 refresh 同 M，RR 内查控制绑定；如果保留候选的事务外预解析，也必须无 payload，且 RR 内重核。不要为了取得 refresh 的 resource 先读 snapshot。 |
| 现有 current proof / 客户端 | O1a 未改 CurrentAuthorityClient/HttpCurrentAuthorityClient；新 M 仍用已有 delegated retained 协议，未发现本切片必须修改客户端协议的接口依据。新 status 目的与材料读 proof 仍须实施和实测。 |
| MaterialScopeStore reader | O1a 未改该文件。此前只接受原 POST/local 的读取限制仍适用；新增封闭 status proof 消费口，共享底层固定 payload/normalize/digest，不让 query/VA/DTO 成为读许可。 |
| snapshot 内容与 candidate 绑定 | O1a 只核 metadata，合法自洽 resource_digest 替换明确会成功。O1b 仍须先授权 scopes 后读真实 snapshot，按原受理关系核 candidate/root、version/appVersionId/appCode/versionRefs，并重算完整 accepted snapshot 摘要；该负例必须 payload>0、业务 wire=0。 |
| composeStatus 与既有 POST | O1a 未改 RunService/TraceStore；保持原输出和 POST reader，独立新增 observer 路径，不重新解释原 strict/blocked。 |
| 首次响应写入 | 必须采用 §5 更新顺序；O1a 仅 service 返回前检查，不能继承为 wire 保证。 |

最小建议是从 RunAuthorityStore.observationMetadata 的控制查询/绑定验证抽出 **package-private、只返回 Binding 的纯 PG helper**（候选名 `loadObservationBinding(JdbcTemplate,SubjectKey,String)`），供原 query wrapper 与新 status reader各自调用。helper 不联网、不启动事务、不 mint proof；调用者各自负责明确用途和顶层 RR。如此共享 owner/unsupported/三根校验，避免在 TraceStore 复制同一大段 SQL。该提取会改变 O1a 实现文件，必须保留原 query 行为并带其具名回归；不是已有 API，不在本次声称已实现。若 root 选择不提取，则新 status reader须完整列出重复的控制列和验证，不能只 load(true) 就称 O1a 全检查复用。

初始 ceiling/provenance 继续来自原受理；新 M current 逐份覆盖 required material floor。不能要求旧材料 authorizationVersion/admissionDecision 等于新 M；不能以新 M 初始 actor 覆盖旧 initial ceiling，也不能要求用户新 portal token 与原受理 originAuthenticationId 相同。新 Q/M 使用同 originalPortal，而与执行 grant 分离。

## 4. 精确候选实施文件与 proof 点位

以下为 root 最终 brief 的最小候选集合；路径相对工程，正式实现前锁定，不允许自然扩域。

| 文件 | O1b 必要变化 |
|---|---|
| `admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java` | 增加固定 status purpose/资源构造或校验：gateway→engine、delegated、PORTAL/PORTAL、action=`proxy.engine.get.RunController.status`、run-snapshot/publicId/64hex；query 原 canonical/双向规则保持。新规则不得将 query digest 当 snapshot sha。可保留 KIND/ACTION 代表 query，新增明确 STATUS 常量，避免重命名影响旧调用。 |
| `admin-common/src/test/java/cn/agentplatform/common/authority/RunObservationSelectorsTest.java` | 加真实目的错配的具名 selector 测试；query 与 status 双向隔离、原 POST/local/event/integration 不得接受 status。 |
| `admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java` | 新 status 分支按可信 source subject 与有限 run-snapshot policy 绑定，exchange/authorize 均核原 call 和精确 resource；维持 Q/M 独立 grant。不能用全局 scopeAllowed 或通用路由字符串接受冒称 run 级拒绝。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunController.java` | 仅实际 admin GET IAM 分支，精确 method/template/URI/dispatcher/query/body；原 legacy 不变。增加同步受限输出与 §5 first-write 终检；相应反射方法参数同步。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunService.java` | 新 observeStatus 调用、已验 snapshot 的纯 FLOW 校验、复用 composeStatus；不 prepareSnapshot，不启动 worker。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/TraceStore.java` | 独立 statusObserved；ambient 先拒绝、事务外 refresh、同 RR 控制重核/完整 scope 预检/材料读/返回终检。封闭 StatusReadProof 可嵌套于此，避免不必要新增通用域框架。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/MaterialScopeStore.java` | package 内固定 status proof reader，沿固定 SQL 与 exact normalize/digest；public VA 拒绝/执行 writer 严格性保持。 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java` | 若采用上述包内 helper，仅提取控制+load(true)；保留 query wrapper/strict 原边界。 |
| `engine-runtime/src/test/java/cn/agentplatform/engine/infra/RunObservationStatusHttpPostgresTest.java`（候选新增） | 实际 engine HTTP/security/handler+PG、新 Q/M client+finite authority、真实 SQL 和 wire 边界；不称 gateway 联合 GET。 |

ServiceAuthorization 当前 hook 可复用，默认无需源改。catalog 已有 admin GET 行，默认无需新增重复行；若调整 source locator/handler 装配，应逐行说明，不批改 debug/v1 的已有策略。O1a 新 controller/service/domain 默认不改。新 status 结果类若确有跨 application/API 封闭终检需要，可作为嵌套类型；不得以公开 constructor 接受 Runnable/VerifiedAuthority 模拟可信证明。

proof **mint**：真实 status frame、同 retained M、PG owner/public id/root/snapshot/initial 与签名资源一致后，在新 reader 的同一 RR 内私有构造；随后完成全部 required scopes 当前覆盖预检才读第一份 payload。proof 绑定真实 run、原执行根、accepted SHA、owner、initial floor、M/frame。proof **consume**：每次固定材料读前后、RR commit 后、compose 后、serialize 后、取得实际 stream 后紧邻 write；不能传给 strict/writer/异步线程、不能以 Binding/DTO 公开 mint。对上层只暴露受封闭持有的终检方法，不暴露 raw 可复用授权对象。

Loopback 最终源码目前只有 query 的 requireObservationQuery（379–385）：从已验 source tenant/sub 与 tuple[3] 重构 selector；exchange 198–200、authorize 229–231 使用它。它没有证明 run-snapshot 在 subject 下的 accepted SHA。O1b 新 finite policy 的可信 run/owner/SHA 输入由受理 fixture/可信 metadata 建立并固定，不能由待验 HTTP 请求任意登记；至少同 owner 同 app 两 run 的互换、错误 SHA 和另 tenant/owner 必须在相应 authority/engine 边界真实拒绝。不要求 authority 直接连 engine DB，更不新增生产跨库查询。

建议 root 锁定的新测试 selectors（均为待编写，不是已存在方法）：`freshStatusDoesNotResumeExecutionGrant`、`queryCannotBecomeStatusPermission`、`statusCannotBecomeWriteOrAliasPermission`、`sameAppDifferentRunCannotSwapStatusTicket`、`missingOrDeniedScopeReadsNoPayload`、`snapshotCandidateMismatchReadsPayloadButWritesNoStatus`、`oneReadOnlyRepeatableReadSnapshot`、`ambientRejectedBeforeRefresh`、`expiryAfterStreamAcquisitionWritesNoStatus`。现 O1a 原具名回归由 root 从最终清单挑选，不把本次未读测试方法名臆造为已有 selector。

## 5. 首次实际 write 的必要顺序修正

root 新约束成立：Servlet `getOutputStream()` 本身可能延时、阻塞或被 wrapper 插入动作，所以“序列化后检查 → getOutputStream → write”存在未检窗口。

有限同步路径应为：payload/read 与真实 RR commit 完成 → compose/终检 → 序列化原 ApiResponse 至完整字节 → 同 frame 本地终检 → 获取**此次实际 response 的实际 ServletOutputStream** → 再核同一个封闭观察结果/retained/frame/期限/用途 → 紧接首次 `stream.write(bytes)`。取得 stream 后不要再做潜在长耗时转换或外部调用；若 header 设置等步骤必需，放在最终终检之前。不要提前 flush、sendError 或写出成功 envelope。最终 IAM 分支不得再交给 converter 第二次输出；legacy 按原返回逻辑。

新增真实 HTTP fixture 必须使获取实际 stream 的步骤被可控延时并推进 clock/关闭 frame，确认该步骤返回后拒绝、业务字节零；还要保留 serialize 后失效、commit 后失效等各自窗口。HTTP 错误 envelope 可以存在，业务 wire=0 不等于整个 socket 字节为零。此保证止于可观察的首次 write 前终检，不承诺一次阻塞 write 内连续撤权检测；如果已部分输出，应关闭并记录真实部分输出，不换成功 JSON 或伪称回收字节。

## 6. 正式实施前仍须由 root 完成的门槛

1. O1a 正式 Accepted/封存依赖已满足；上表 manifest 和冻结 overlay 为本次已核基线。独立 review 三项 Minor 按 root 台账处理，其中本次收到的 HttpClient/SQL allowlist 两项不扩大本次设计，不另等待 O1a 批准。
2. 正式 O1b brief 锁定实际 admin GET、fresh run-snapshot、私有 purpose/proof、包内控制 helper 选择、首次 write 顺序及精确 writer 文件集合；O1a metadata API不再是“待猜”接口，但新 status API仍未实现。
3. 对未被 O1a 改动的 RunController/RunService/TraceStore/MaterialScopeStore 沿原 frozen composition 继承；本文没有重新全面审计旧路径，也没有替它们产生新测试证明。
4. 新红绿必须真实 HTTP+PG；snapshot读后关系失败的 payload>0/wire业务0、所有材料 scope 预检失败的 payload0、单 RR 与在线 refresh 事务外、same-app cross-run、query/status/write 分离分别记录。SQL 观测不得继承宽 `.*` 即声称列级安全；只为新路径补严格实际 SQL 分类，不默改旧报告。
5. 唯一 source writer 获授权后才编辑；不新增 gateway O1c/SSE/RR1/RR2/CHAT/技能模型恢复、迁移/POM/生产部署范围。

## 7. 本次冻结原件定位

以下文件统一位于绝对物理目录：`G:/207/项目/算法重组工具/代码/agent-platform/docs/evidence/iam-stage-4-o1a-run-metadata/frozen-overlay/`。下表相对路径与该前缀拼接即实际读取原件；SHA 对完整文件计算，行范围表示内容核对范围。未使用当前工程源替代冻结原件。

| Overlay 相对路径 | Bytes / SHA-256 | 窄读范围 |
|---|---|---|
| `admin-common/src/main/java/cn/agentplatform/common/authority/RunObservationSelectors.java` | 1967 / `dfc6ee1d17cd3d26aa842c75f631b82cb924a8ef5fd784243090bc45979e8846` | 1–28 |
| `admin-common/src/main/java/cn/agentplatform/common/authority/ServiceAuthorization.java` | 9322 / `8b97f99496135e8d3af0f874528f536a1164a7d285ad0b03c7feff4197727281` | 88–120；新增调用105–106 |
| `admin-common/src/main/resources/agent-service-authority.json` | 86120 / `fa37b6d8602e470e6511284b463b9905600aba1cd392a4f868927ce475515d9b` | 2010–2030；三status action上下各5行定位 |
| `admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java` | 47724 / `d9547d13413602715d498bdb3647041cdaffc542bfc2d89f7af520059ab877ff` | 186–240、375–389；resource/profile/scope等符号窄rg |
| `engine-runtime/src/main/java/cn/agentplatform/engine/api/RunObservationMetadataController.java` | 1528 / `396aec51cd058391401445222176c649c9844703ba9a81ca28fb7ca531396cf8` | 1–26 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/application/RunObservationMetadataService.java` | 2519 / `5b1c68d636c6cfd3a3117ab0220fda8d3d93da4bc529a4fe4ba60dbf480d8b2d` | 1–40 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/domain/RunObservationMetadata.java` | 1147 / `3530511ad46319a1175c0f289cb42dd4766862c84035190e574dc998b75c53f6` | 1–19 |
| `engine-runtime/src/main/java/cn/agentplatform/engine/infra/RunAuthorityStore.java` | 19772 / `a4116e74d817a29026490ae860890f125571ad52ed33446845716e95045af21c` | 80–166，重点85–133；purpose/签名窄rg |

仅新增本文。交还后冻结本文件，供 root 快速形成正式 O1b brief。