# 真实纵切脚本与证据独立复核

复核日期：2026-10-20。范围按 `live-smoke-review-brief.md`：四个新增脚本、三份报告/summary、相关原始证据及有限只读 PG 查询。仅新工程，未重跑任何真实脚本，未启停主开发服务，未 package/Maven，未修改业务源码或被审脚本，未委派子任务。使用 code-review、verification-before-completion 和 ponytail 技能。

## 需求符合性结论

**本轮已完成运行的有限成功结论有证据支持。** 四脚本与交接 SHA 全部一致；三组 raw/log SHA 与 summary 全部一致；调度 JAR 实测 SHA 为 `4fb6f8e3856f73098bac0b8ca6c743f8bbf9ceaf692c6a8c14fce319e6e010e7`，与报告一致。未发现需要撤回本轮 MCP、调度或异步评测成功结论的事实矛盾。

| 结论 | 独立核对与限度 |
| --- | --- |
| MCP 完整分页与精确工具绑定 | `tests/smoke-mcp-protocol.mjs:118-125` 对协议、两页工具顺序、完整 Schema 与 VIEWER403/新增会话0进行断言。peer 只提供有限已知响应，不能据此证明供应商能力。 |
| MCP 真实浏览器请求 | `web/tests/mcp-registration-live-browser.mjs:29-49` 观察真实选中草稿版本1的无正文 POST，检查完整原始 Schema 的 pattern、非 GET 请求恰好只有发现 POST、无 pageError，以及1440/390文档宽度。代码无 route mock。独立读图1440、390，两张均显示准确绑定 lookup 与原始 Schema。 |
| 只读业务一次及失败前置拦截 | 只读 PG 核对 call88 为 skill/engine SUCCEEDED，call89/90 为 FAILED/PARAM_ERROR；call88的业务节点247仅一条逻辑提交。脚本 fixture 对 lookup 与 `{region:'A'}` 精确断言，并检查只读调用1次、重复受控ID不重做、缺工具与 Schema 拒绝各0业务调用。 |
| 获批写事实 UNKNOWN 与只查询对账 | 脚本168-192检查审批前0业务调用、EDITOR与snapshotDigest、获批后1调用、两次对账 UNKNOWN/retryBusinessAllowed=false、无新增会话或业务调用、完整原fact快照相等。只读 PG 再确认 call91/attempt283仍为 skill/engine UNKNOWN，审计正好两条UNKNOWN，本地run已CANCELLED。UNKNOWN不能解释成未应用、已撤销或远端停止。 |
| 两个真实引擎 JVM | 三份启动日志分别记录PID58892/21032/47224、对应新工程JAR、专用库与18086/18087。脚本启动前拒绝占用端口、首次HTTP前核对监听PID；停止前检查原ChildProcess状态和PID的java.exe/JAR归属。JAR参数限定在本工程`.local/artifacts`实际路径内。 |
| 调度机会接管 | PG确认handover成功、generation2、RUN_STARTED1、START/END各1attempt；summary记录原generation1、实际租约有效与原owner，代码持START节点行锁至A停止并等待真实dispatch过期后才启动B。支持未领取节点之前的dispatch接管，不支持在途node/外部副作用接管。 |
| 有限队列公平与唯一提交 | 专用库只读核对尾部200/200成功；逐节点核对400节点各恰好1attempt和1逻辑提交，异常节点0；两个不同RunService worker owner分别承担196与204节点，101个head仍RUNNING且attempt0。RunService owner与RunScheduler dispatch owner是不同实例字段，不混为一个ID。 |
| HTTP调度与本轮清理 | 两个引擎各HTTP受理的run在专用库SUCCEEDED，原始结果upstreamReads2。当前只读进程/端口检查：三个记录PID均不存在，18086/18087/28418/28419均无监听。summary的后两个processRecords未含stopped标记是写入早于finally；最终`.local/scheduler-live-processes.json`三条均stopped=true。 |
| 原异步报告身份与单次创建 | 原始浏览器请求显示：单次POST产生`er_350f803c-530b-4aad-9789-1304cc3b9e3d` RUNNING，针对同ID GET1次、resume1次变ACCEPTED；case runId全程为`r_0e2be7f772094f8abd3c8c5abfd68ee4`。只读PG确认此数据集报告数1，候选摘要、draftRev3、datasetVersion、预期和实际输出均一致，该run SUCCEEDED。 |
| 真实FLOW布局及冻结用例 | 异步runner复用既有真实浏览器脚本，未拦截请求；依赖脚本检查独立expected输入、保存revision2的promote与精确评测cases、候选摘要不变、390画布两节点尺寸与边界。原始flow390数据文档宽390/画布244，两节点均在画布内；独立读图accepted-390能看到原报告通过与尚未发布。 |

## 代码质量结论

**请求修复1处P2失败清理问题。** 它影响异常情况下脚本的可靠收尾，不推翻本轮已有成功证据；不应把本轮清理成功推广为所有失败路径都有保证。

### [P2] 单个stop失败会跳过其余已拥有资源的清理

位置：`tests/smoke-run-scheduler-replicas.mjs:147-151`；关联 `stop()`：84-90。

`finally`在循环中直接`await stop(...)`。`stop()`的CIM查询、归属校验、Stop-Process或退出等待任一步失败都会抛异常；此时循环立刻结束，剩余已创建JVM不再尝试停止，后面的`fixture.close()`、socket销毁及最终进程记录写入也被跳过，原验证异常还可能被清理异常覆盖。比如一项受限PID停止被拒绝时，其余可正常停止的本轮子进程也会残留；存活peer会令脚本无法正常退出。

建议保留现有PID/JAR归属保护，对每项清理独立捕获失败，确保尝试其余资源；把peer/socket与最终记录放入保证执行的收尾路径，完成全部尝试后报告清理失败且保留原验证异常。可用不启动Java、不接触实际进程的有限纯Node回归验证：首个stop拒绝后后续stop、peer关闭和记录仍发生。无需为了这处修复重跑两JVM整轮。

## 实际验证记录

- `Get-FileHash -Algorithm SHA256`：四源文件4/4匹配；三raw与三stdout日志6/6匹配；调度JAR匹配。完整新增diff也已阅读。
- `node --check`：四脚本全部退出0。依赖`named-inputs-live-browser.mjs`已完整静态阅读；未执行其真实流程。
- 两个PG库均使用项目自带psql，在`BEGIN READ ONLY`及`statement_timeout='10s'`中查询，两个批次均退出0。查询只涉及本轮ID、明确隔离scheduler库及原评测ID/数据集；没有执行业务写入。
- 独立PG结果：MCP call88/89/90/91分别SUCCEEDED/FAILED/FAILED/UNKNOWN；call91原attempt283、两UNKNOWN审计、本地run CANCELLED。调度tail200成功/head101 RUNNING，400节点的attempt/commit均各1，非唯一0，head attempts0。原评测报告数1、状态ACCEPTED、原case run SUCCEEDED且已知输出正确。
- `Get-CimInstance Win32_Process`仅检查三记录PID，结果为空；`Get-NetTCPConnection`仅检查四测试端口，无监听。此检查只证明检查时状态，历史停止动作依据脚本与日志。
- 实际读图：`frontend-mcp-live-tools-1440.png`、`frontend-mcp-live-tools-390.png`、`frontend-evaluation-async-live-accepted-390.png`。

## 未覆盖及验收边界

本复核未重跑真实脚本、构建、前端127单测或相关独立UI修复套件；那些既有结果不在本次独立重验范围。未做Schema资源防护/耗尽审查、容量或规定500/5000/200压测、分布式资源许可、在途节点/副作用接管、远端停止/效果撤销、正常OS优雅停止、供应商/真实外部联调、写技能完整评测治理、整页刷新创建幂等、生产或业务签收。本报告无资格证明上述验收完成。

## 追加：清理P2修复候选独立复核

2026-10-20，依据 `scheduler-smoke-cleanup-fix-brief.md` 复核补丁、冻结材料及实际候选。候选调度脚本SHA为 `c57a8287febad5b5448aff75c60d98c12f51666bbfd79e3e7161762bfed4fa9c`，helper SHA为 `a8082c33892bd7a0c93aa8daeb7b5483c4d5f71ae2d9305e1c7466c298a977e0`，测试SHA为 `241fdbf5203eb8b208447b478221157bce6a7765e844c44ae7756ccddeced55c`。

**需求符合性结论：原真实成功证据继续成立；本修复对首stop拒绝的正常可结算清理路径有效，但原P2尚不能完全闭环。** 新增两项有限纯Node时序检查显示，异步锁管道错误仍会打断后项清理，peer关闭也可能无限等待。此次没有重跑真实JVM矩阵，不能把纯Node结果当作新候选的真实矩阵成功。

**代码质量结论：请求继续修复。** 原 `stop()` 与基线逐字一致，PID/java.exe/JAR拒绝保护没有放宽；不增加无关进程操作。已有8项测试亲跑8/8通过，证明同步/Promise拒绝后的独立尝试、原异常与多清理异常可检查、仅清理失败不返回成功、最终记录独立尝试和实际脚本接入。缺少以下异步错误/关闭不结算情形。

### [P2，原清理问题未完全关闭] stdin.end返回Writable，异步EPIPE未捕获

位置：`tests/scheduler-smoke-cleanup.mjs:7`，关联捕获方式4-5。

`lock.stdin.end(...)`返回Writable，并不返回完成Promise；`await`无法捕获其稍后发出的`error`事件。真实psql管道可能在`lock.exitCode`仍null时已不可写，产生异步EPIPE。当前锁stdin没有error监听器，此事件会终止Node进程，后续stop/peer/socket/records可能均被跳过，且原验证错误未作为cause/errors保留。

独立有限验证直接导入实际helper，用Node标准库真实`Writable`的`write`实现通过`process.nextTick`回调EPIPE，lock.exitCode=null，两个假child和假stop（首项用setImmediate让出事件循环），其余peer/socket/records均为有界假函数。为观察真实未处理error行为，仅启动一个2秒超时上界的独立Node测试子进程，不启动Java或psql、不连接服务、不操作任何既存进程。实际子进程退出1，stdout只有 `lock-write`、`stop:101`，stderr明确 `Unhandled 'error' event` / `code: 'EPIPE'`；stop102、peer、socket和records均未发生。原validation异常也未进入AggregateError。

建议将锁管道结束包装为能监听异步error并按完成回调结算的有限操作，确保错误进入同一清理汇总且不跳过后项；加入真实Writable异步error的纯Node回归。仅使用同步throw的假stdin.end不足以覆盖此路径。

### [P2] peer关闭回调未返回时，已收集的清理错误与原异常无法结算

位置：`tests/scheduler-smoke-cleanup.mjs:12-19`。

`peerClosed`完全依赖`fixture.close`回调，最终直接无限期await。当某个socket销毁失败、对应连接继续存活时，Server.close回调可能一直等待该连接；虽然后续socket和records已尝试，脚本不能退出，也无法抛出已经收集的清理失败和原验证异常。现有“等待peer回调”用例主动在writeRecords中触发回调，未覆盖回调永不结算。

独立有限纯Node检查注入等待连接的假close和抛错的假socket.destroy，直接调用实际helper。50ms有界观察得到 `pending-after-50ms`，调用顺序为peer/socket/records；手动触发close回调后才得到保留原异常与socket错误的AggregateError。代码静态确认等待无上界。50ms不是生产超时建议，只是有限复现观察；没有实际socket或监听端口。

建议给peer关闭结算明确上界，超时作为清理错误纳入汇总，保证最终拒绝并保留原验证错误；不通过吞错或跳过归属保护处理。

### 本次实际验证与证据延续

- `node --test tests/scheduler-smoke-cleanup.test.mjs`：亲跑8通过/0失败/退出0；三候选脚本分别`node --check`退出0。
- `git apply --reverse --check docs/evidence/scheduler-smoke-cleanup-fix.patch`：退出0，实际候选与patch对应。全量manifest的actual/frozen/baseline、patch与全部证据SHA复核匹配；最后一版manifest含`replay.log`，源文件SHA未改变。
- 原三组成功raw/stdout日志SHA再核6/6匹配；另外三个历史脚本SHA不变。原JVM/PG与浏览器成功结论保持历史源的有效证据，没有以本次清理缺口否定它们。
- 完整静态阅读helper、回归及实用脚本catch/finally，确认运行脚本直接调用被测试helper；stop函数保持原样，继续只对记录PID执行带JAR检查的停止。
- 两项补充检查均为有限纯Node注入，没有真实JVM、Maven、数据库读写或服务启停。本次结果仅说明上述代码路径，未扩大到容量、供应商或生产验收。

## 追加：v2独立复核与P2闭环

2026-10-20，独立复核 `scheduler-smoke-cleanup-fix-v2.md`、完整delta patch与同名SHA manifest。实用调度脚本仍为 `c57a8287febad5b5448aff75c60d98c12f51666bbfd79e3e7161762bfed4fa9c`；v2 helper为 `3bacd8c5db534d495d8327779ac11024b936c0a53115316d0607c4f8adbc9b83`；异步测试为 `9ddd85b02d05524a070811dce27a841d4292f726728b01850f207e06083122db`。patch SHA为 `a0ad4c97d97931328c4e1bbbc29e8c24d9686f892b04f845026d8a5506ac3dfc`。

**需求符合性结论：通过本次有限清理修复要求，原P2及追加的两个异步清理问题关闭。** 首stop拒绝、锁异步EPIPE、锁不完成、socket销毁失败及peer不回调时，后续自有资源和最终记录均继续尝试，原验证错误与可观察清理错误保留；有清理失败时拒绝，不返回脚本成功。历史真实MCP、两JVM调度与异步评测成功证据继续有效，归属于历史运行源；本轮没有重跑真实矩阵。

**代码质量结论：本次修复范围内通过，未发现新的实质问题。** `tests/scheduler-smoke-cleanup.mjs:14-19`在end之前监听error并等待end回调，有意保留监听以处理排队的流error；锁与peer回调各有默认1000ms等待上界，超时以ETIMEDOUT进入同一异常汇总，timer在结束后清除。peer先发起关闭、socket与记录独立尝试，再等待有限结算。`stop()`与原始基线逐字一致，PID/java.exe/JAR保护及拒绝路径未放宽；实用脚本仍直接调用被测helper并传入原失败对象。关闭等待上界不意味着底层未成功清理的资源已被停止，失败仍以错误报告。

### 亲跑验证

| 验证 | 实际结果 |
| --- | --- |
| `node --test tests/scheduler-smoke-cleanup.test.mjs tests/scheduler-smoke-cleanup-async.test.mjs` | 12通过、0失败、退出0，包含真实Writable异步EPIPE独立Node子进程、首stop拒绝、多个错误、原错误和falsy throw、正常/幂等路径、记录独立尝试、peer与锁不结算、实际脚本catch/finally接入。 |
| 四文件分别 `node --check` | 实用脚本、helper、原测试、新异步测试全部退出0。 |
| `git apply --reverse --check docs/evidence/scheduler-smoke-cleanup-fix-v2.patch` | 退出0；delta patch与当前候选对应。 |
| v2及历史SHA核对 | v2七文件actual/frozen/baseline、patch及证据均匹配manifest；原阶段manifest、baseline/frozen、旧patch/证据均匹配。旧三组raw/stdout日志再核6/6匹配；其他三历史脚本未变。 |
| 独立重跑原EPIPE探针 | 直接导入实际helper，用真实Writable.nextTick(EPIPE)，两个假stop/peer/socket/records；独立Node子进程退出0、stderr空，顺序为lock-write→stop101→stop102→peer→socket→records。AggregateError保留原验证对象与EPIPE原对象，未处理error不再发生。 |
| 独立重跑原peer探针，使用生产脚本默认等待值 | peer永不回调、socket.destroy抛错、records仍发生；实测1009ms自动拒绝。AggregateError同时保留原验证错误、socket错误与peer ETIMEDOUT，无需手动放行回调。 |
| 额外独立混合失败探针 | 同一调用中注入真实Writable异步EPIPE、首stop拒绝、第二stop成功、socket失败、records失败、peer不回调（测试界限30ms）。全部后项依次尝试，最后AggregateError共6项：原验证错误+5项清理错误，原验证对象和四个注入错误原对象均可检查，并含peer ETIMEDOUT。 |

补充探针只使用有限假资源和自建Node测试子进程，未启动真实Java/psql或socket、未接触实际服务或已有进程。未重跑Maven、构建、真实两JVM矩阵或供应商联调；本报告最初声明的容量、在途副作用、资源许可及生产验收排除项继续适用。上文第一阶段“未关闭”是当时冻结候选的历史结论；当前v2状态为本节所列有限范围内已闭环。
