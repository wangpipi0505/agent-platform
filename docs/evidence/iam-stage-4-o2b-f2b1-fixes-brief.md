# O2b F2b1 HTTP 复审修正任务

## 位置与依据

在独立新工程中修正 F2b1 的两个复审问题；此任务仍只覆盖原 Q 的 HTTPS 元数据读取，不开放 EVENTS，不实现 b2 G/E、材料验证或 SSE。用户已授权持续实施，没有专用环境，继续使用新工程的隔离端点。既有 v1.2 设计、四份契约、时间安排均不改。

先完整读取 `docs/evidence/review-iam-stage-4-o2b-f2b1-http.md`（15291B，SHA256 4ef225344b004d74d095590e491629949a1473eca1d6c236a37acb36eb6ac8be），以及原任务 `docs/evidence/iam-stage-4-o2b-f2b1-http-brief.md`、所引用三个冻结补充（budget、log-source、actual-api），只读取具名依赖段。报告中的 I1/I2 是本次必修项，M1 在本次四文件范围内一并处理；M2 的 Windows runner 诊断由 root 留账。

所有 shell 的工作目录固定为 `G:/207/项目/算法重组工具/代码/agent-platform`。不得读取、使用、关联或影响相邻旧工程的源码、配置、数据、依赖、进程。遵守本目录 AGENTS.md。Java21 / Boot3.5.0 / 固定 Apache/Jackson / PG 权威源不变，跨业务仅 HTTP。不得提交、git add -A、重置、部署或修改设计/契约/执行索引。

## 唯一源文件写权

以下四个既有文件允许修改，其他 617 个 source 条目逐字节不动，新增源文件 0，source 总数始终 621：

1. `platform-gateway/src/main/java/cn/agentplatform/gateway/infra/PortalRunEventsHttp.java`：I1 的清理协议及 M1 格式。
2. `platform-gateway/src/test/java/cn/agentplatform/gateway/infra/PortalRunEventsHttpTest.java`：I1/I2 测试、自己的 child 证据目录及 M1 格式。
3. `platform-gateway/src/main/java/cn/agentplatform/gateway/application/RunObservationMetadataClient.java`：仅本轮新增 EVENTS 部分格式，原 STATUS 前缀 6168 字节完全不动；所有 Java 非空白 token（包括注释/字面量）不变。
4. `platform-gateway/src/test/java/cn/agentplatform/gateway/application/RunObservationEventsMetadataTest.java`：仅格式，所有非空白 token（包括注释/字面量）不变。

不改 pom、common、TLS、F1、网关 assembly/controller/main、前端或其他业务服务。不添加抽象框架或生产功能。若确需扩大写权，先报告具体原因，独立工作可继续。

## I1：唯一 cleanup 执行者与实际退出

原代码 finishTransport 在同步检查后未占用清理权，多 caller 能在锁外读到随后被其他 caller 清空的 timer/handle。修正为同一 Session 状态锁下选出唯一清理执行者，并一致地拍取其 transport 引用。其他 caller 只在自己的剩余等待期限内等待结果，或返回 false。状态发布、清引用、opening/facts/fee/Owner 最终关闭各一次。

物理 handle.awaitClosed、timer.awaitTermination 和可能阻塞的 client/manager close 不得持 guards 锁；不得用长持 Session 锁阻塞 timer.abort。cleanup 在超时、打断或异常退出时必须在 finally 交还执行权并通知等待者，允许后续 caller 接续。未确认物理退出、活动 lookup 退出及 timer 真实 termination 前，不得退账或清除后续重试需要的引用。close/abort 并发、lookup finally 的当前 operationThread 清理、完成后的重复 close 均须保持原有语义。

先写确定性关键失败路径测试，再修实现。测试需覆盖两个真实并发 close/awaitClosed caller；用 barrier/latch 控制正在 cleanup，证明等待期间费用保持、第三 Session 被拒绝、短等待按期限返回、释放后两调用正常且一次退账。避免靠概率循环或 sleep 断言竞态。可使用现有同包机械 socket-close gate；若为确定性 RED 需要机械 timer/termination gate，必须只控制等待/退出时点，不能伪造有效 Q、已验证 TLS、facts 或物理退出。报告说明 seam 的工程边界。测试若只能得到结构 API 缺失而非行为失败，必须如实记录，不把它称为行为 RED。不得回退旧实现、重跑旧 RED 或改历史证明。

## I2：真实 HTTPS 的 8192 与 8193 body 边界

在受控真实 TLS peer + 实际 Session/Opening/Q 路径增加或扩充 focused 用例：

- 合法七字段 envelope 后补 JSON 空白得到精确 8192 个 UTF-8 字节，Content-Length:8192 成功；断言七字段 facts、一次原 Q/没有第二 exchange、实际物理关闭及账本释放。
- chunked 的同一合法 8192 字节成功，覆盖没有 CL 的恰好上限路径。
- chunked 的 8193 字节拒绝，明确走真实 bounded read 超限而非 CL 预拒；零 facts、先 abort、未知后续 chunk 不 drain。用受控尾部不提供/等待关闭证明有限退出，区分实际观察与测试策略。

保持 strict schema、请求/响应头和 deadline 边界不变；不能把 parser 8192 正例、CL8193 预拒和 chunked8193 读取混称。合法 compact data 保守上限 2080，4096 合法 DTO 不可达的历史结论不变，不放宽 schema 制造样本。

## M1：局部可读性

四文件中的新代码按普通 Java 缩进拆开多逻辑步骤，尤其清理状态、异常与引用释放顺序；注释解释不变量而非逐句翻译。保留 STATUS prefix 逐字节不动。格式调整不改变字符串/正则/注释 token；不要全仓格式化。HTTP 两文件允许上述行为变化，metadata 两文件只允许机械空白变化并给 token 证明。

## 有限验证与证据约束

原 b1 证据全部冻结：原 report、handoff、owninventory17113、13 stages、18 green、27 peers、原 patch/private Git 与原目标清单不得写入或重跑。尤其现有 HttpTest.child 的硬编码路径指向原 ownedRoot，本次测试前须改为本次新的 `.local/iam-o2b-f2b1-fixes/peers/`，不得污染旧 27 child 集合。所有测试/进程仅使用新工程自己的 JDK、m2、隔离端点；使用既有 `.tools/run-iam-module-tests.mjs`，实际 child 需要原来的短 Unix-domain tmpdir 与固定 TLS 启动参数，不能绕过 JDK classpath 检查。

测试顺序：新的并发行为 RED（如可达且真实）、受影响 focused GREEN、最终同一完整新 source epoch 的 HTTP 全类（新 close 协议影响所有 HTTP 成功/异常出口）。只执行一次已成功的阶段，不强制 mtime/recompile。metadata 两文件 token 不变，所以不因格式重复原 metadata7；原 STATUS prefix 机械证明沿用且本轮再比较，不重跑旧 final18。若相同字节上的 focused GREEN 已等于最终所需 selector，复用实际记录，不重复跑。每次 source 改变都在阶段前记录完整 621 manifest、四个可变源码副本及未变617的继承关系，确保实际编译输入/输出与阶段 sourceEpoch 对应。失败、编译失败、选择器拒绝、诊断均原样保存，不能预计最终测试数。

本次 ownedRoot 仅 `.local/iam-o2b-f2b1-fixes/`、`docs/evidence/iam-stage-4-o2b-f2b1-fixes/`、具名 `.tools/iam-o2b-f2b1-fixes-*`、本报告和 wrapper 实际产生的具名外部阶段日志。一个源码/target writer。每个阶段成功后立即保存身份与状态；后续元数据报错仅恢复未完成部分，不重跑成功测试。不要复制整个 target 或扫描依赖缓存。

before source 为 `.local/iam-o2b-f2b1-http/source-after.json`：621，118929B，SHA256 73d316639100f8be3aa99924fd5cb4cd6ca860a729b1bc3df7d5a753ede185f0；完整冻结副本 `.local/iam-o2b-f2b1-http/source-after/`。before target 为 `.local/iam-o2b-f2b1-http/target-after.json`：2223，464896B，SHA256 db710f41df507b401ce2230fd43c95c4015959b5b3ca3df109e48d5c974d72cc。root 已完整审计原 epoch，结果 `.local/iam-o2b-f2b1-root-check.json`，6 phases、18095 physical identities，未重跑测试/重放；其 PASS 只证明产物身份，原 review 仍 Needs fixes。

固定前述 621 before，在独立私有 Git 中用 `* -text -diff`（git add 前）、隔离 system/global/template 配置生成新的 raw binary patch。check/apply 在精确 before621 上各首次执行一次，比较完整 after621 的字节和名字，不得 apply 后复制源码。Git 证据仅在本次 ownedRoot。完整 source before/after、patch replay 的有限复制与机械身份可以做；每阶段仅保存四个可变文件，另617继承固定 before，避免反复复制全集。

target overlay 仅来自实际 compiler input/created lists、实际资源复制、fixture common-test JAR、实际 Running 的报告；继承 logical2223，不把全 current/classpath 当本轮证明。新 diagnostics 单列，不并入有限 target。每个 child 记录 (run,PID)、请求 cwd、实际退出和 collector join，不以 cancel/EOF 代替退出。

## 交还

完整报告写 `docs/evidence/iam-stage-4-o2b-f2b1-fixes-report.md`，保留原 report 不变；最终返回 DONE / DONE_WITH_CONCERNS / NEEDS_CONTEXT / BLOCKED、真实测试摘要、限制及三个控制文件路径。报告列命令、实际输出/selector/Running/XML/sourceEpoch/真实编译阶段、I1/I2/M1 对应、历史 RED 边界、全部新增 child 退出、patch 和覆盖范围。生成本次 handoff.json 与 own-inventory.json，排除 inventory 自身、包含其他 owned 叶子；写出 frozen 和 source/target/owned 写权交还。八份执行索引由 root 在独立复审通过后更新。

不声称完整 IAM、EVENTS 已开放、真实 engine/PG 全链、生产启动或完整 TLS/JVM 堆容量已验收。3MiB 仍只是已有有限 HTTP 对象图工程模型，新状态字段/栈局部快照须在原 margin 内说明，不能把账本当 RSS。
