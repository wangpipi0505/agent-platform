# PROCESS 受控文件 I/O 工程增补（EX-15b/1）

本增补承接现有 PROCESS 执行方式、algorithm-process/1 信封及既有 artifact 权威存储。它定义后续实施目标；当前已交付父目录段拒绝，以及 PhaseA 的 files 布尔开关校验、启用后的输出相对路径与普通文件存在性校验；PhaseB 已补齐显式输入的同主体 READY/有效期授权、受控文件名流式物化、数量/字节/摘要检查与流关闭；定向回归66项通过。输出持久化已接线但待验，二进制回放仍待。完整 I/O 的实际进度、准入与验收以统一台账 EX-15b 为准。原冻结契约及 EX-05 工程增补不改写，不引入新执行器、迁移、公共上传接口或 OS 沙箱承诺。

## 1. 显式启用与兼容边界

仅 process.stdin 是对象时，其顶层 files 为本增补保留的布尔键。true 启用输入物化及成功输出校验/持久化；false 或键缺省保留既有无文件 I/O 路径，不把业务 arguments.files 解释为控制信息。旧 stdin 的 scalar/array 及其它字段原样保存，不顺带要求 stdin 必须为对象。

兼容例外：旧 object.stdin.files 的字符串、数值、对象、数组或 null 原本可被保存；从本增补实施起，这一保留键的非布尔值以 400/PROCESS_CONTRACT_INVALID 拒绝。不能把该有意收紧描述为所有旧任意 stdin 都不变。旧发布记录不就地改写，遇该冲突应由所有者显式修正新草稿；历史原件仍保留。

启用不改变已发布技能的协议、命令白名单、timeout、环境白名单、1 MiB 信封/stdout 上限或执行/授权门。queryOnly 不启动进程。未启用时既有父目录段拒绝仍适用。

## 2. 入参先受原 schema 和权限约束

arguments.files 是完整 arguments 的一部分，先通过既有 inputSchema 和 guard。stdin.files=true 不自动删除该键来绕过 schema，也不修改技能 schema。additionalProperties=false 且未声明 files 的 schema 应拒绝该输入；需要输入文件的技能须由其 schema 明确允许该键。输出文件能力不要求请求必须带输入 files。

通过原校验后，adapter 仅在一份参数副本中移除控制键；调用者原对象、其余业务字段与数组顺序不改。worker 信封仍使用原 parameters 字段，文件映射在独立 files 字段，不新增第二套信封。

启用时 arguments.files 可缺省为空映射；存在时为 logicalName 到 {artifactId} 的对象。不得接受 URL、物理对象 key、本机路径、主体或 sourceRun 覆盖字段。logicalName 不参与本机路径拼接。引用必须由既有 ArtifactStore 核同 tenant/principal、READY、未过期；同 owner 先前上传的输入不要求 sourceRun 等于当前执行 run。原 schema/guard 仍先行拒绝。既有 ArtifactStore 对不可见、跨 owner 或失效引用统一404/ARTIFACT_NOT_FOUND；PROCESS 输入边界在已列 SkillAdapters 内窄映射为404/PROCESS_INPUT_ARTIFACT_NOT_FOUND，使既有 SkillExecution 的 PROCESS_ 错误通路保留真实状态，维持 owner 不可区分且不泄露源详情。不宣称原 ARTIFACT_NOT_FOUND 码已端到端透传，不扩大 SkillExecution 生产白名单。此拒绝时 worker 不得启动、无输出行；其它真实存储故障不能伪装成404或worker输出错误，其必要映射由输入阶段按实际异常单独冻结。

平台在本次私有 callDir/input 中生成文件名，信封 files 只映射 logicalName 到 input/0001.bin 等受控相对路径。按实际读取字节验长度和 SHA，关闭每个输入流。最多16个输入、单个16 MiB、合计64 MiB；实际超限必须在启动 worker 前停止，不信任声明值。

## 3. 输出与持久化不变量

仅显式启用的受控 I/O 路径负责把成功响应 files 声明转换为 artifact 引用。worker 先返回完整成功信封；result 仍通过原 outputSchema。files 声明沿用 name/path/contentType/bytes/sha256，路径只允许本次 callDir/output 内的相对文件。拒绝绝对路径、UNC/盘符、完整父目录段、符号链接/junction/reparse、缺失或目录、重复 name/path、错字节数或 SHA。最多16个输出、单个及合计均不超过64 MiB；输入、输出配额独立，不能放大原 stdout 或超时上限。

先验证整个声明集合，再在同一安全文件句柄或平台独占稳定快照上核验和流式写入。实际已登记 worker 及后代退出、写入者收敛与防替换事实是验收前提；普通路径检查或一次 stat 不能代替。Windows 无等价安全保证时 fail closed，不能声称获得 OS 沙箱。所有路径拒绝沿既有 502/PROCESS_OUTPUT_INVALID；持久化/资源故障按其实际错误保留，不用该码吞掉输入授权失败。

使用真实 Actor/runId/callId/attempt、既有 V306 key 和 UPLOADING→READY 链；不得复用普通上传的 r_upload/call1 代替执行归属。fileRefs 只返回既有 artifact 引用和安全元数据，不返回本机路径。后续失败只补偿本次调用已创建的精确对象/行，不删除源输入、其它调用或旧队列；保留原失败并记录补偿失败。

LAST_REAL/录制回放对 PROCESS 二进制附件使用流式复制并再次核 owner/sourceRun，生成新 run/call 归属，不经 JSON offload。原 JSON offload 路径保持。重启读回、二进制一致、跨 owner 拒绝和新归属必须实测。

## 4. 分阶段验收

PhaseA 只批准三个既有测试源的原25加新5方法，先真实失败再最小修复；绝对路径负例的真实空文件必须已在本次 output 子目录内，使绝对性成为唯一非法项。所有旧断言、进程身份/收敛和原预算保持。参数 schema 拒绝、false/缺省保留业务 files、scalar/array stdin 兼容须有后续真实信封/worker 正例。

输入授权/额度、输出链接与替换、存储补偿、二进制回放及正式新 JVM 读回分阶段取得真实 PG/MinIO/进程证据。纯 mock、三条红转绿或当前 PROCESS 路径元数据不能替代完整文件链验收。真实业务算法 worker 仍依 X-1 单列，协议夹具不冒充该外部验收。
### 2026-10-30 输入实现与错误映射

输入引用形状错误沿422/PROCESS_INPUT_INVALID，数量或字节超限沿400/PROCESS_INPUT_TOO_LARGE；仅真实404/ARTIFACT_NOT_FOUND窄映射为404/PROCESS_INPUT_ARTIFACT_NOT_FOUND。读取/写入及实际长度或摘要不符为503/ARTIFACT_STORAGE_UNAVAILABLE，其它PlatformException保持。输入准备与worker使用同一剩余timeout预算，准备失败不进入失败转储。单次SDK阻塞读取的严格返回时限及配额/存储损坏分支尚未专项验证，不视为已获OS隔离保证。

按用户最新策略，本批仅两轮定向测试：25项22P3F确认缺口，最小两生产文件修复后六相关类66P；不追加全仓回归。注入认证上下文的adapter测试不冒称入口JWT验签，完整schema/guard入口、输出归属、回放和重启另批推进。

### 2026-10-30 输出持久化实现状态

已接通callDir回收前的二进制输出登记，沿现SkillExecution已claim/submitted传入的Actor/run/call/attempt，使用独立process object_key及UPLOADING→READY，不复用普通上传r_upload/call1。全组声明先核路径/数量/bytes/SHA，Windows保护句柄同源转存；中途失败仅补偿本批新增对象，预存输入不触。当前保护打开仅支持Windows，其他平台拒绝；不宣称OS沙箱、祖先替换绝对防护或跨服务最新代原子发布/取消晚到闭环。

测试两轮已到止：22项19P3F确认缺口；实现后69项67P1F1E。后两未通过由JDK Windows BasicFileAttributes.fileKey固定null导致提前拒绝；已据本机JDK源修正为可选补充比较，保留NOSHARE/NOFOLLOW/路径和实际字节摘要检查，未重跑。状态为实现已改、待验，原结果不改写、不认领69通过。跨服务取消/晚到由EX16及联合阶段接续，完整回放/正式重启另批。

### 2026-10-30 运行取消生产接线（EX-16）

引擎先提交 PG 的 CANCELLED，再对当前同代/同租约、非 mock、冻结 PROCESS 且 cancellable 的活跃工具调用，向固定 skill origin 发送 `POST /internal/skill-calls/{callId}/cancel`。body 仅 `{runId,attemptId,skillVersionId}`；全轮最多5秒、单次最多2秒、收据64KiB，不跟随重定向。失败只记未确认，不撤销终态；外层事务尚未提交则明确 `OUTER_TRANSACTION_PENDING`，不持事务发送 HTTP。

技能入口要求 SERVICE，并回读 engine 已认证的同 tenant/principal/run/call/attempt/version 与已提交 CANCELLED 状态，核实际 PROCESS/cancellable 契约，再命中本地同代句柄。排队起登记所有权，取消和启动交接共用单调用锁；旧代不能杀新代。收据 `{callId,runId,attemptId,localCallMatched,cancelRequested,reason}` 仅表示请求/匹配，不保证远端树已退出；重复或无句柄为 `NO_LOCAL_MATCH`。输出交接后取消只补偿本批新产物。

当前传输沿现有 legacy ServiceTokens；IAM 模式明确拒绝 legacy transport，IAM delegated 出站和多实例句柄路由仍归 EX-26/部署联合，不伪造 caller 证明。两侧分别用真实 PG 与签名 HTTP peer、真实双层测试 JVM 验证，尚未作为同一套正式服务做联合取消；不关闭真实 worker/服务重启待验项。
