# Initial model-input producer — DONE / frozen pending review

本报告只交付 engine 的初始输入持久生产组件和认证 manifest GET。尚未 Accepted，未接通公开模型运行或 model consumer；没有 tokenizer、measure、provider、SecretVault 调用入口，没有发送许可或 response lease。真实正向根由组件夹具调用既有 `RunAuthorityStore.createPublished` 建立，位于仍闭锁的公开 CHAT/model-bearing 受理门禁以下，不能据此宣称生产模型链已经存在。

## 范围与实现

正式 brief、架构计划、原 frozen-inputs 身份保持。另绑定 root 的 regression-isolation-01～06 补充，尤其04把规范修改范围精确扩大到3 existing modified +8 new；源码总数644→652。既有 catalog 仅增加一条 model→engine、delegated、`material.scope.read`、integrationAllowed=false 的实际新控制器路由；其 callerSource 明确写未来消费者尚计划中。两份既有 PG 测试只同步成功迁移顶点期待217→218，其他字节、旧数据库字面量、断言与逻辑未动。完整差异在 `final/task-delta.json` 和 `final/source-644-to-652.patch`；`final/source/` 是652份全文，patch不是全文。四契约、v1.2、默认 IAM guard、公开 GraphRules/TraceStore/driver 门禁和8份保护文档均未改。

`ModelInputMaterials.prepareInitial(runId)` 不接受调用方消息、scope、owner 或 binding。先对既有 continuation loader 的控制 JSON 做 PG scalar 探长，再在事务外取得既有 retained run.advance proof/CAS；同一写事务锁 run/authority，核三根 scope、实际 snapshot/inputs/versionRefs 与根/owner/版本、期限和无历史/旧 invocation 关联。只支持 history-free initial-chat-main-v1：ContextAssembler + FrozenModels，空历史/空工具，稳定 `material:m_…:MAIN`，callKind=FORMAL。它与 run.trigger_type=TASK/mode=FULL 不混用。

requestSha256 是完整规范受理请求 JSON 的 SHA256，包括诊断 manifest 和计算后 labels，保留有序消息/工具数组顺序；不等于 Spring AI wire 字节。原始 initial ceiling、全部父义务和可信当前 labels 均保留。PG 原子追加 request、manifest 与 model-input scope；unique(run_id) 限定一次初始 profile。存储 API 会重新从锁定 PG 源组装核对，公开数据 record 不会变成 proof。异常标记真实 ConnectionHolder rollback-only，即使外层捕获也不能部分提交。旧 ModelExchange、budget dispatch、sdk attempts 和模型网关均未调用。

GET `/internal/material-scopes/model-input/{m_32hex}?version={64hex}` 走真实新控制器、既有真实服务安全链、canonical catalog 和签名当前 proof。严格用途、DELEGATED/PORTAL、model caller、精确 root/owner/operation 校验；重复或未知查询键拒绝。PG 仅读取元数据，**不 select request payload**。它核 scope/manifest 摘要、不可变根和三父 exact closure，并在返回前复核 current；locator header 不是信任凭据。没有可信 IAM bean 的有限 legacy Spring 上下文能构造新 bean，prepare/export 在受保护读取前拒绝。默认完整 IAM 工程仍受原 guard 拒绝；有限 fixture 的成功不外推为全默认 launcher 验收。

边界为每根1MiB/合计3MiB、request2MiB、manifest及响应256KiB、解析/树深32、三父、128 roles、32 entitlement keys、每key256 values、256 labels、标识和值256字符上限（或更严格既有规则）。PG 先探长/CASE有界取文，再解析/计数/规范字节复核。没有作全 JVM 或容量结论。集合由 manifest 本地 serializer 显式排序；scopeSha 仍复用 MaterialAuthorization.scopeDigest，没有修改 common canonicalization。

## 实际验证与失败记录

所有命令 cwd 为唯一新工程；真实 PG25432/agent_v12，所有任务库随机命名 `iam_model_input_*` 并先核不存在。V218落笔之前，preflight-04真实 Flyway V200～V217成功且源无重号。preflight-01/02 的 JDK 非 ASCII Unix-domain 临时路径失败，以及 preflight-03 错把 Flyway schema marker 的 null version 纳入期待，均保留；它们不是产品 RED。仅使用既有 V:/.local/tmp 映射，未改全局临时目录。

| epoch | 实际结果 | 含义 |
|---|---|---|
| red-01 | 2 failures /0 errors | 可编译 skeleton 未拒绝缺父 scope、未生产正向持久事实 |
| green-01 | 3/3 | 最初行为与迁移前置转绿 |
| green-02 | 20例中1 failure/3 errors | 真 GET/重放发现14字段误写15；两例外层 rollback-only 实际抛 UnexpectedRollbackException，测试未期待；已修复并保留原结果 |
| green-03 |20/20|上述问题转绿|
| green-04 |23/23|新增真实连接 canary、JFR正控、并发和旧关联拒绝|
| green-final-01 |26/26：HTTP11、PG15|完整当时候选；含真实CA-only信任/SAN、双独立JVM摘要复算|
| red-bound-01 |2 failures /0 errors|发现 ref 额外字段/字符串模型ID及>256签名decision ID应拒绝而未拒绝|
| green-final-02 |15/15：HTTP11、受影响PG4|修复上述既定边界后定向验证；其 source.json 与最终652源 manifest 逐字相同|
| supplemental-final-02 |7项实际诊断全通过|补充精确签名错根、PG model/binding/manifest摘要/根元数据不一致、7个事务内无IAM网络、公开CHAT/model-bearing guard拒绝|

跨epoch共27个独立新 JUnit 用例获得绿终态；**没有声称最终同一字节完整27/27一次运行**。最终补充诊断使用最终真实 controller/PG/TLS 类，没有改规范源，也不混入 JUnit 数。supplemental-final-01源文件启动器的 package-private 跨类加载器访问失败保留，改为自有 javac cache 后通过，不算产品 RED。

PG验证涵盖完整受理请求摘要/标签/三父、幂等重算、同 retained proof 的并发存储收敛一个ref、scope插入故障即使捕获仍全回滚、伪 currentOnly/请求模型关联拒绝、锁等待过期、最终本地检查过期回滚、commit后过期拒绝返回但保留已提交事实、immutable UPDATE/DELETE、源长度/深度、旧 invocation/chat_turn 拒绝等。并发组件上的既有 current CAS 仍保留其拒绝/重试语义；没有更改授权 backoff。IAM/时间与PG commit不原子，后commit拒绝不能伪称撤销已提交事实；该事实永不自行授权发送或后续GET。

HTTP验证实际签名/issuer/audience/caller/target/action/owner、workload/response/PORTAL/INTEGRATION用途、selector、父缺失、元数据超界、scope行不一致、fresh第二次读取、503无manifest和两个header；正常CA链、错误主机名与不信任证书均用标准TLS验证。重定向用例只证明本任务夹具 transport 不跟随302，不宣称未来 model 固定origin/redirect客户端已实现。root/model/binding/manifest元数据补充负向分别在其真实层次拒绝，错签名根的拒绝发生在PG读取前；未冒充后续scope读取检查。

## 受影响旧回归的限定

regression-01实际执行92例，原失败全部保留。旧业务族已绿部分不重跑；已授权的两个迁移顶点适配分别执行于原epoch与regression-02单方法补跑。最终规范218与实际执行缓存218的等价（仅数据库字面量不同）在 `final/canonical-executed-test-equivalence.json`。

RunAdmissionHttpTest最初遭接受快照旧测试@Configuration bean冲突，依次保留01/02失败；03按root01～06规则，静态ASM注解识别并隔离31个精确、可追溯到accepted644的无关旧测试组件及内部类，同时保留全部真实生产类、新组件、新@TestConfiguration、原测试体及必要setup。实际原始/适配源、javac输入、包含/排除、过滤fixture JAR、JVMclasspath顺序和class-load来源全部留存；最后该类5/5绿。不能称未过滤全classpath默认主类启动通过。GatewayPublishedEngineFixtureMain 的实际接受源码和首次加载来源都在 engine-runtime 测试路径，不能凭 common 包名误写为admin-common源/JAR。

common RouteCoverageVerifierTest在engine依赖图中意外激活JDBC自动装配的失败保留；正常 common 模块classpath独立9/9绿。修正终态覆盖原计划92个独立旧回归用例，但不是声称某一次92/92整套Maven命令绿；见 `final/test-epochs.json` 逐阶段Running/XML。

## 构建、零外发与资源

package-01：实际编译/JAR阶段完成，Boot repackage重命名活动target JAR为.jar.original失败。没有直接证据证明根因是主进程文件锁；没有处理已有.original、查杀/重启进程或重跑原target失败构建。

package-02：在 `.local/iam-model-input-producer/package-work/` 由最终652份源/POM逐字复制的独立物理工作副本，实际 `-pl admin-common,engine-runtime -am package -DskipTests` 成功。输出为该副本下两个module的target，不冒充原活动target打包通过，也不把skipTests当测试。最终engine可执行JAR为51,812,309 bytes / SHA256 `a89eb35c8963bd13d5952ffd5fe0f6ac00f59eb7dfb46056a4abed413636c5e4`；7个新生产class和嵌套common内catalog与最终实际编译/规范资源逐字相等。实际compiler input/createdFiles/resources/fixture JAR、Running报告及产物字节均按有限集合保存，没有扫描历史全target/node_modules。

model/tokenizer/provider canary经三个真实端点正控命中，再用冻结entry实际配置的端点和credential ref执行组件，调用增量为0。SecretVault正控命中 `jdk.FileRead` threshold=0并保留调用栈，组件阶段该resolve调用栈的文件读取事件为0；JFR是所记录文件读取范围的诊断证据，不能单独证明任意零方法调用。结论另由新生产调用链静态无SecretVault/RuntimeClients/model调用入口和实际连接canary支撑；授权控制面及真实manifest HTTP是有意必要网络，不计入模型外发零数。

最终资源审计：77个本任务库保留供独立复核，PG连接会话0；36个已观察任务进程PID均退出；58个已记录任务端口没有监听。测试server/client/context/executor按fixture结束，Maven与collector全部wait返回；未操作主18080～18085、15173～15175。未删除临时目录或测试库；没有主仓提交、部署、外部发送或自动化。

## 冻结与交接

最终全文 manifest：125471 bytes / SHA256 `747463804aeadbbad190cf37a8186dacb4f3f23f05ad4c20dfe0d8cb6d0c24a4`；完整native差异：110355 bytes / SHA256 `2f9b228d15378f218ef74788b281b061232b46cfb44f76793d235c7b877a4d3c`。自有scratch对before644执行plain/binary-capable diff、apply --check、apply，652份after逐字一致；不是主仓git变更操作。完整源码EOF、641份不变源、8文档AFTER身份与3+8差异已核。

作者状态为 DONE/frozen pending review，所有源码、有限target、测试数据库/HTTP、helpers、owned cache/evidence/report写权和运行权一次全部归还root；冻结后不再修改。control/inventory为最终交接索引，由返回的精确身份定位。G6 Task4 streaming、Task5 history/SUMMARY/RETRY/results、RR1 drain、RR2恢复模型链和真实IAM/K8s/容量等仍未由本组件签收；34全局状态/T01唯一完成保持，后续实现另行授权。
