# EX-27 SSE Test-only await 独立静审

审阅者：`docs_reader_check`；2026-10-29。仅只读源、Git diff、原XML/CSV和保存原件，新增本报告；未运行Java/javac/Maven/PG/HTTP/WSL/Node或服务，未改源、原件、三入口、HEAD/index。历史validate helper由本审阅者编写，故此报告**不独审自己的历史validate运行**；后续collector仅绑定root已实际执行的PASS原件作为来源前提。

## 结论与精确范围

**未见SSE Test-only候选阻断。** `engine-runtime/src/test/java/cn/agentplatform/engine/infra/PortalRunEventsLifecycleTest.java` 当前SHA **`021629a09e978a177f98764af28ba7b96569f707f27dae4dc85994657f1747f8`**，Git diff恰5+/3−：新增只读反射helper读实际owners集合size；在原timeout/complete/shutdown方法保留service引用；原两个4秒await分别加强为exits>=1且owner1、exits==2且owner0。

逐字逆转该精确diff可重构旧Green源SHA **`3ffc0280107643ad942557f1eecf5a6df5da760ee9bb5979603deaf973f3fbe8`**，与真实Green306中该文件身份相同。当前11个@Test方法名与原EX04该类XML的11个唯一身份一致，没有新增、删除、忽略或改名。`await(BooleanSupplier)`仍4秒/10ms轮询，两个Future.get仍4秒；原60/61/62 frames、reader容量、slot订阅和writes断言未放宽，生产源不变。

## 原失败与同步依据

原 `.local/ex27-v222-recovery/v222-full-933-20261029-02/xml/TEST-cn.agentplatform.engine.infra.PortalRunEventsLifecycleTest.xml` SHA **`ec99d4336bf480ba950bb4150e4b290b0ff9b86386349b4ebc7d1ec42339d399`**，恰11例=10P/1F/0E/0S。唯一失败 `timeoutOnCompleteAndShutdownKeepTwoActualGetterOwnersUntilEachReturns` 为AssertionFailedError，原line115精确expected62/actual61。原Full02仍933=817P36F80E0S、native0为ignore采集、collector1/gatefalse，不能倒写成通过。

`PortalRunEventsService` 的运行返回点先于实际资源finally；其Owner.release在当前生产源line137依次close pending/frameLease/response/slot，最后 `owners.remove(this)`。因此getter/exits计数及HTTP future完成并不能单独证明该finally已归还frame/reader/slot。新增helper只读同一个service的并发owners集合，不改变资源、latch或生产行为：owner1为第一个真实finally完成、第二个仍持有；owner0为两个finally均完成。它加强的是既有资源归还断言的观察时点，并没有用重试资源值、吞异常或增加期限接受61。

原EX04 BASE同类XML SHA **`9587fbb79f9f1e4259423706dc8cef69ba21b74e2b95e2ae04fcd1ce73791640`** 的11方法全部原PASS；新Target应完整执行此11身份、0skip/noignore，不只选原失败方法。候选通过仍须实际窗口原XML证明，本静审不预报11P或Full通过。

## 来源和执行候选边界

当前源对真实Green306的逐路径/SHA差异恰为该SSE Test一项；其它305文件原SHA保持。当前317个fixture文件对冻结Full-v2清单逐hash无漂移。原Green4P来源必须继续按旧Green306=RED305+SQL419解释；新SSE SHA不能倒填成旧Green已执行源。新collector应先验证旧Green链，再独立验证唯一SSE delta，保留F4D六依赖和3D2B新JAR/24资源原指纹。

仅另行准备白名单内 `v222-source-recovery/run-sse-regression.ps1` 与 `sse-regression-manifest.json`，从实际9F653/7D6D Full-v2派生。当前只sealed Target：全类11、1 XML、noignore、Xmx1024/timeout900/defaultfork不变。实际11P/native0及完整source/resource/deps/storage/fixture前后门后才生成Target receipt；Full必须另有批准manifest绑定该receipt，仍按EX04原932+资源新增1=933，保留原115非通过、2类级事件和14未具名边界，原因不归一化。

Target可沿既定14库/317fixture只读读取边界，timeout库必须新增0且原names/owners不变；Full仍只允许原timeout fixture恰新增1，不由collector创建/删除。root实际历史validate PASS原件只作明确标注的既有输入绑定，此处不代替独立历史终审。原Full02失败和当前SSE候选指纹应全部保留，任何编译/准入失败不可冒作业务红或11P。
