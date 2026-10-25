# D4b生命周期修复：退出订阅与时钟回退

状态：正式后续修复要求。原I1/I2七文件波次已先行封存交还，163叶seal不改；本波另开own目录，不接受此前暂停/扩写同目录的已撤回设想。用户已授权持续按计划实施，继续隔离测试，不问是否继续。

依据原D4b brief、原IAM计划§2.7及独立补充审查`docs/evidence/review-iam-stage-4-d4b-lifecycle-supplement.md`（5731字节，SHA256 `1cd7e3931c6dc288a085b05a02fb5fec03d6123520a118ef67272203465ddde5`）。root完整读补充并接受I3/I4；原初审I1/I2和补充不改，最终交原reviewer一起聚焦复审。

唯一工程root `G:/207/项目/算法重组工具/代码/agent-platform`，每shell显式workdir，遵守AGENTS，不访问相邻旧工程代码/数据/配置/依赖/缓存/进程。原v1.2/四契约/固定依赖/lockfile/SQL/后端/主进程/八索引不改，无工期/部署。无真实外部IAM，受控HTTP/宿主仅软件隔离验证。

## 1. I3的精确生命周期

原plan明确退出取消provider订阅；原portal-auth仅卸载dispose时unsubscribe，而Shell退出只卸载App，宿主事件仍connect。原测试还将到期后宿主事件自动恢复当成成功，需要纠正该预期，而非用它否定已有要求。

root澄清如下，不重开用户决策：

- 正常已连接或正在连接期间，宿主onSessionChanged仍应启动新连接；同token产生新generation，正在等待的旧provider/session不可写回。
- **显式退出必须取消现provider订阅**、清身份并终止旧请求/观察，等待手动重连；已排队的旧宿主回调即使unsubscribe后抵达也不能取票/登录。
- **401和到期进入手动恢复状态后，宿主事件不得自动恢复登录**；这两种情况可状态门控或取消订阅，原计划没有同一句硬性要求二者都unsubscribe。手动重新连接才解除暂停、按需恢复一份有效订阅，不能重复注册。
- 403/503入口的原手动重试保持；正常宿主真正切换身份的处理按上述活跃状态语义，不扩大外部消息渠道。不改外部PortalTokenProvider两方法签名，不新增令牌存储、全局事件总线或认证框架。
- 卸载/dispose退订并释放内部watcher/timer/请求，dispose幂等。订阅安装/清理时的同步或排队宿主回调不能使旧订阅复活；先使本地回调归属失效再做外部清理。新generation不能被旧timer/provider callback清除。

可在controller内用现有Vue watch或同等最小生命周期机制，不需要重写App。退出/401/到期的旧组件卸载及store.invalidate仍走同一入口；不能只在按钮上做特殊处理而漏掉401/expiry。

## 2. I4的期限上限

原api.install每次deadline-Date.now后最多60秒重排，只限制检查间隔，不限制总寿命，系统回退可延长token/user/roles/旧页面保留。后台独立鉴权仍在，本修复不是修服务端越权。

安装时固定本次已验证expiresAt所对应的剩余寿命，并以浏览器单调时间建立不延长上限；后续检查取wall-clock deadline与该单调上限较早者。继续最多60秒分段、generation归属和统一invalidate；wall-clock向前跳应保守到期，回退不能把安装时寿命变长。也允许明确检测回退即失效的更简单方案，实施前说明选择和取舍；不得仅增加Date.now检查频率来声称解决。使用现有标准能力，不加时钟库/服务。

准确边界：该机制防安装后的时钟回退延长页面保留，不证明初始本机时钟与服务器完全同步，也不承诺浏览器被冻结/后台调度时的即时到期SLA；恢复执行时必须按真实已定截止检查。UI期限不成为缓存后端许可，expiresAt仍由受保护session响应提供。

## 3. 文件与行为测试

候选生产仅`web/shared/portal-auth.ts`、`web/shared/api.ts`；若实际生命周期需要`web/admin/src/PortalShell.vue`一处窄接线，先向root说明，不预先改App或其他组件。测试仅既有`web/tests/portal-auth.test.ts`、`web/tests/portal-browser.mjs`，必要直接受影响expiry/login test先报具体理由。I1/I2的artifact/events/observer生产修复保持，默认portal和SDK兼容不变。

实施前回报精确文件/API/订阅状态与时钟算法/测试selectors，root常规范围批准后写source。先复现关键红，再最小修复；只新/直接受影响命名用例，不重跑57/127或原browser32，也不因新store改动盲重跑已绿I1/I2全部17。必要验证：

1. 实际退出unsubscribe一次；旧callback在退出后不取票/不请求session/不恢复工作区；手动重连恢复一份订阅，活跃宿主切换仍成功，同token新gen/旧promise忽略。含同步订阅/退订回调或明确等价旧排队callback对照。
2. 当前401、expiry各进入手动恢复；宿主事件不恢复，点击手动重连后成功；provider调用/session/业务POST/cancel-run计数不因旧事件增长。原“expiry后host自动恢复”测试改成符合既有要求的断言，并保留此前错误预期的封存证据。
3. 安装剩60秒后wall-clock回退一小时，推进单调时间至原期限仍清token/user/roles/旧数据；向前跳、超长分段、旧timer不能清新gen、dispose释放保持。测试用可控时钟/闩锁，不长sleep或只检查setTimeout调用次数。
4. 真实PortalShell/App浏览器只补退出→宿主旧通知→手动重连、401/expiry的停止/恢复必要路径；能复用一例中的阶段，不重跑所有旧交互。保留已验当前附件401路径对照，证明本次生命周期变化没有恢复刚失效身份。无需重复未变布局截图，产生新的状态图则实际view。

最终共享api变化被三个应用消费，最终source冻结后完整vue-tsc+三个应用build各一次，并同步SDK到webapp/dist验SHA。原I1/I2波次的三build保持其阶段归属，不当成本次最终构建；M1大chunk警告原样记录，不扩打包重构消噪。任何setup/oracle/工具错误保留，不能计行为红；生产改动后的覆盖按最后有效阶段说明，不把多轮拼为一次全绿。

## 4. 所有权与交还

本波作者独占`.local/iam-d4b-lifecycle-fix/`、`docs/evidence/iam-stage-4-d4b-lifecycle-fix/`及`docs/evidence/iam-stage-4-d4b-lifecycle-fix-report.md`。唯一获批source/三个dist writer，root不读半成品。原D4b908叶、I1/I2新163叶/两报告/所有review/seal和八索引一律不改；浏览器harness输出应先判断新的lifecycle phase写本波目录，不能让原fix-规则落到已封163叶目录。

基线为已交还I1/I2 composition/handoff：source572=backend380+frontend192，backend target1933继承；dist15。root对I1/I2有限审计`.local/iam-d4b-error-stop-root-check.json`已PASS，核392物理文件：当前192源/新163叶/实际9命令/17绿+三browser/重放与保护；尚未单独宣告I1/I2代码Approved，最终原reviewer一并裁定。复用已封存谱系，不重扫908+163旧叶/后端target/旧10GB，不复制全量JAR或历史源码树。每命令前完整必要source SHA+仅变动物理overlay；before与scope增补保留真实时间。

交还精确source/dist delta、最终frozen overlay、真实rootcwdpatch check/apply与192前端＋380继承的572逻辑源names/bytes/SHA一致、阶段命令/cwd/exit/raw/counter及资源关闭、新own manifest/bytes/SHA。报告不冒充生产IAM、完整D/E/F/17功能34任务30验收。写完新报告/seal后冻结交还全部source/dist/own所有权；root有限核验及原reviewer聚焦复审全部未闭合项后，才更新八索引和推进O1a。
