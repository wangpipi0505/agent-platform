# O2a A lifecycle 窄修复任务

此任务由 root 批准执行，无需再次询问用户。用户已授权独立工程按计划持续开发，并在缺少外部环境时使用隔离端点验证。唯一工程及每条 shell workdir：G:/207/项目/算法重组工具/代码/agent-platform。禁止读取、复用或触碰相邻已有工程；原设计/四契约/时间安排不动。没有commit、手动package、部署、自动化或全局完成声明。

## 冻结输入和写权

原 A 不是 Accepted。正式 review Needs fixes，C0/I3/M1：docs/evidence/review-iam-stage-4-o2a-a-portal-events-response.md，11685B，SHA256 1d0119aad355d76ff9e999818ef784624efe19cdfafb60380820e41b70f0d18b，62行EOF。完整原diff为同目录 review-iam-stage-4-o2a-a-portal-events-response-package.diff，196931B/SHA54df9be546e89c1a79eed83d2d73260d62e1a0b58558bf7fbf3dbdde6f338030，2911内容行+尾LF；不需机械重读全包，可先读review，再分段完整读本次确实要改的文件和明确依赖。根已读review到EOF。

A原正式brief13055B/SHA1b1de0bb861a1996d08ab2fa30358233176004ace0aee58f9be951b893298cad与seam2939B/SHA2a82a5415e6f34a62bc84b10a906d0489f39a73fa199e067d4e6f2db0a47042d仍约束签名/权限/first anchor/闭门/资源政策。最终冻结输入 docs/evidence/iam-stage-4-o2a-a-portal-events-response/final02/source-after.json111999B/SHA6fa517a594efdf523b83c45188b03b9e7a9ad14482a29e7d8113cfe7227fd271，source589=backend397+frontend192；target-after.json419611B/SHAcbb51a971a6111c503babff9be6ded0846497cf4e79a95b2ba7e5e1076943f02，historical logical2023。上述目录为所有 final02 输入前缀。作者全部写权已交回。

你是本任务唯一 source/target writer。只准修改以下4个既有文件（若M1不需fixture则3个），0新增0删除；不得扩大到 ServiceAuthorizationTest、其他旧测试/production、engine/gateway/frontend、catalog、POM或migration：

- admin-common/src/main/java/cn/agentplatform/common/authority/HttpCurrentAuthorityClient.java
- admin-common/src/main/java/cn/agentplatform/common/authority/PortalEventsResponse.java
- admin-common/src/test/java/cn/agentplatform/common/authority/PortalEventsAuthorityProfileTest.java
- admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java（仅M1真正隔离短grant所需的有限fixture配置，不改变既有用途默认值）

自己的新证据目录 .local/iam-o2a-a-lifecycle-fixes/、docs/evidence/iam-stage-4-o2a-a-lifecycle-fixes/；报告 docs/evidence/iam-stage-4-o2a-a-lifecycle-fixes-report.md。原作者所有封存件及root/reviewer件只读，不覆盖，不删除。原 target/source frozen 并非当前修改后的执行身份，务必分开记录。

## 必须完成的修复和复现

I1：holder.stop 发布 cancellationStarted 后到 slot.stop 提交前，client.close 可跳过该holder取消交付并 shutdown workers，原线程恢复后取消callback被拒绝，永未执行。必须把 runtime shutdown 与实际取消交付协调为可验证顺序，区分 stop发布与callback交付，callback在生命周期锁外实际恰好执行一次；实际 callback退出前仍持slot。拒绝/close路径不能把未执行callback算完成。补确定性barrier交错测试，不用概率循环或sleep冒充证明，不允许用无界新线程/队列执行拒绝callback。

I2：renewEvents先创建task/addWork，再 previous.remainingNanos；如果期限/关闭在后续检查抛错，task未发布/未提交，reservation无人回收。先完成可能失败的期限计算，且任何预留后到发布/提交前失败都有一次可靠回收。确定性在关键交错到期/关闭，续期HTTP为0，callback真正完成后slots归零、容量可再次使用。已发布queued-opening timeout不等价此覆盖。

I3：pending发布到execute之间可cancel/finish；execute仍注册timer/排队；或schedule返回到timeout赋值之间finish看不到timer。必须可靠协调注册/提交和cancel/finish，终态task不再建立timer/worker项，交错产生的timer必取消。单次isDone检查不足。两个确定性交错均覆盖；检查实际scheduler/worker队列、reservation只归还一次和迟到结果不能install。保留2workers/queue64/64slots/250mswatchdog，不增加无界逃生执行器、每holder线程池或泛化framework。

M1：现grant测试推进301秒而source/binding更早到期，不能算独立grant边界。优先以真实签名、短于其他硬边界的grant隔离验证；若协议本身使期限不可独立，准确改名称/报告限制，不通过修改生产规则或不合法签名事实伪造隔离。允许上述fixture文件内最小新配置且默认不变。具体条件和实际首触发边界写清。

保留已正确的签名全部关系、私有mint/owner/frame、不可重置first anchor、current≤5秒/first55秒、serviceJWT仅打开后原续流可跨期、generation/本地material检查、历史与新observer ceiling/origin不强制相同、STATUS-only receiver。没有PG/SSE公开开启。

## 验证与封存

先做实际行为RED以复现I1/I2/I3各自交错；compile/setup错误不算RED。允许为复现添加最小确定性测试接缝，但不能新建公共权限工厂；如确需超出4文件先向root说明具体阻碍，不问用户。使用既有skills（SDD/TDD/systematic-debugging/verification/ponytail等适用指令）、现有Java21、unchanged .tools/run-iam-module-tests.mjs、offline .tools/m2，独立 iam-stage-o2a-a-fix-* stage，不用+连接test selector。

修复后只一次完整新Profile类最终命令；如修改fixture则同一命令追加新ReceiverClosed类。旧6类/旧Service13冲突/engine/gateway/PG/POST/SESSION不重跑。新失败需要的定向修复/重跑原样保留，不把不同阶段相加成一次测试。原final01=85/72PASS13ERROR、后green04=Profile22/22是历史分阶段证据，不改写。本修复新增的方法数与实际Running/XML一致。

每条实际命令立即保留执行前后完整source、必要target清单，实际任务文件文字快照、raw和仅实际Running XML；executedSource与finalSource独立。当前基线589，最后仍589，逐项组合源delta并比较。private no-commit replay基于本次冻结589原字节，first check/apply实际0/0、全部589 names/bytes/SHA匹配，apply后不得copy final进去；失败保留只恢复未完成阶段，不重做已成功物理阶段。原A的freeze01和final02两个先前成功replay只读继承，不能称整个A只有一次replay。

编译/资源/report候选按实际createdFiles/inputFiles及actualRunning限定，继承logical2023逐项overlay，不全扫旧target或重编app。已知app4差异已修正入当前lineage，不重新发现/归因。root原证据审计 .local/iam-o2a-a-root-check.json5359B/SHA31346eb9fd848047960fa09c4ca6f09bc71891e8e54c31f61c7ee82d715aceef，5phase/2464physical PASS，**仅证据，不是代码Accepted**。其元数据检查曾两次helper错误及一次真实作者清单错误，journal原样保留并缓存续行，未重测/重放。

root两份纠正必须引用：docs/evidence/iam-stage-4-o2a-a-diagnostic-attribution-correction.json41759B/SHAdfafb36faf1735dd726fd472fb022a027993ec8b672514a6bdfb99573cf8ffbf（194中183历史源日志、11真正新增；旧logical2004本含908历史dump）；docs/evidence/iam-stage-4-o2a-a-service-error-method-correction.json2176B/SHA9dd13931a47abe2441920e58e10256f0caf8fec17c58c3f0c13a17fbe4b43b15（旧error清单误含health、遗漏actorProjection，实际13按XML和root原决定核对）。自己的新dump只按本次命令前后差异登记，不复制/改称183历史日志本次新产物。

8执行索引/README/原设计保持原SHA；当前checkpoint .local/checkpoint-iam-o1c-docs-before/manifest.json SHA9285fc0593b60ca1a9b44f0902dcc99ca2fb880c6f074d352658b80cab999503。仅root在最终Accepted后更新8索引。own inventory精确自己新leaves、self exclusions、原失败/诊断归因、实际资源退出、EOF/bytes/SHA、无未交回写权；交还report/handoff/source/target/inventory/replay refs和全部写权后停止写。原reviewer做窄复审，root有限审计，不自行宣布Accepted。后续独立旧Service测试单文件迁移再B，不在本任务内做。

EOF
