# 异步评测前端独立评审

2026-10-20。结论：**REQUEST_CHANGES：需修改，确认2项P2；本范围未发现P1。** 仅评审新异步评测消费者，不代签整个任务，不将受控Chrome解释为真实G5异步链路验收。

## 范围与证据来源

唯一工程根 `G:/207/项目/算法重组工具/代码/agent-platform`。已读AGENTS、implementation-brief、contracts/README及冻结`contracts/snapshot/detailed-design-v1.2.md`第九部分与发布门禁段落，阅读`frontend-evaluation-async.md`、10文件SHA清单、新Panel/Report/observer/App接线、测试脚本及monitor现有controller契约。使用Vue/code-review/requesting-code-review/frontend-design/verification-before-completion/ponytail检查原则。

开始时10/10源码与测试SHA匹配。NamedInputs和DebugPanel保护摘要匹配，未修改这些组件或原评审历史报告。本次只写此独立报告，没有修改生产或作者测试、启停服务、外部通信、另派agent、运行构建或覆盖dist。

两项Chrome反例对应冻结 `EvaluationPanel.vue` SHA256：`be389357745a15088f130e1093cc209e3f0b6446acac0a99e43dab54d0336658`；App接线SHA256：`405d22aa498311456aff612e9bd730b870f609bada51c08c2e51a045b1e41c33`。两份反例实际输出完整保留于各发现下方JSON，不能用修复后的文件解释为原源码已有通过行为。

作者已有证据：26文件/96测试、vue-tsc及三端构建、3+13项新Chrome和23项兼容场景。已读实际日志、脚本与JSON；这些是作者执行记录，不称本次重跑。缺模块的unit-red与旧App行为超时红按其实际性质区分。

本次新增执行3个隔离Chrome探针与13项observer定向测试。Chrome使用本工程现有15175、临时上下文、`component-fixture-only`票据，全部业务路由由fixture拦截；没有真实后端写入或复用旧profile。G5真实runner未执行。

## 确认问题

### [P2] 创建ACK在权限变化时丢失，却允许再次创建

位置：`web/admin/src/EvaluationPanel.vue:43`，关联34、38—39行。

POST创建已经发出而响应未回时，身份/权限watcher会递增generation并abort请求，随后直接清`creating`。这不是取消后端评测：服务端可能已经受理创建。旧请求catch因generation失效不会再设置`unknownCreate`，watcher也没有记录这个未知ACK。同一账号恢复EDITOR后，仍在同一候选/同一已载入cases页面，用户可以立即再次POST创建，绕过应有的“从列表找回”保护。可能造成第二组昂贵模型/技能评测，与“离页只停止观察、ACK未知不得重发”约束相违。

本次独立Chrome复现：

1. 预检并载入v1；点击执行候选评测，route确认第一个POST已到达后延迟ACK。
2. 调用fixture的`window.denyEvaluation()`撤销EDITOR，再通过该fixture现有Pinia auth store恢复同一owner的EDITOR；不改候选/cases。
3. 放行旧ACK。旧响应正确被丢弃，但页面没有未知创建警告，创建按钮已启用。
4. 再点创建，收到第二个POST。

进程退出0，实际输出：

```json
{"probe":"permission-change-during-create-ack","fixtureOnly":true,"newCreateEnabledWithoutRecoveringFirst":true,"createPosts":2,"unknownAckWarningVisible":0,"problem":true}
```

建议：在失效请求之前，若创建已发出且ACK未确认，保留绑定原候选/账号的“创建结果未知”状态；同身份恢复时禁新建，先显式读取列表找回。停止旧观察及拒绝晚包仍应保留。无需新增后端requestId契约，但不能以abort代表未受理。回归须覆盖权限撤销/恢复发生在创建ACK窗口，而不只是在后续GET观察窗口。

### [P2] 找回已有报告详情在途时，仍可并发创建新报告

位置：`web/admin/src/EvaluationPanel.vue:24,29,33,48`。

`choose()`清空report并置loading后等待已有报告GET；此时`running`因report为空而变false。创建按钮和`create()`只检查creating/running/unknownCreate，没有检查正在读取选定报告。用户已选“找回”的RUNNING报告，详情尚未返回，页面仍允许POST另一份报告，而不是等待确定并恢复原ID。新创建又修改selected，使原详情在返回后被丢弃。慢网络下两个互斥意图可以同时进行，产生额外评测。

本次独立Chrome先载入v1，再选择列表中RUNNING的`er_existing`；延迟该详情GET，检查创建按钮并点击。新报告返回ACCEPTED后放行旧详情。进程退出0，输出：

```json
{"probe":"choose-existing-versus-create","fixtureOnly":true,"createEnabledDuringExistingRead":true,"newCreatePosts":1,"problem":true,"afterOldDetailAcceptedHeading":1,"oldRunningVisible":0}
```

建议：为选报详情使用独立的在途状态，与创建互斥；按钮和函数入口同时阻止。不要只依赖模板disabled或把列表刷新/详情请求共用loading后由另一操作清除。应补“已有RUNNING详情未回，试图创建”的真实SFC回归，并继续允许读取终态后用户明确发起下一次评测。

## 本次实际验证与未采纳的疑点

在本工程web目录实际执行：

```text
npm test -- --run tests/evaluation-observer.test.ts
```

07:23:00，退出0，1文件/13测试通过、189ms。覆盖同ID串行观察/恢复、ERROR显式恢复、等待不误计失败、400/401/403/404/409停、有界网络退避、abort晚包、候选/owner不符及依赖错误统计。没有重跑96项或构建。

3个Chrome探针的命令入口均为PowerShell内联JavaScript `@' ... '@ | node --input-type=module`，在web目录导入现有`@playwright/test`，使用已安装Chrome、`browser.newPage()`和`finally browser.close()`。页面为`http://127.0.0.1:15175/evaluation-async.html#/apps/async_a/orchestration/publish`。前置fixture返回draftRev3、候选SHA为64个a、owner、v1及独立expected/cases；通过route gate延迟指定POST或GET，输出上述计数。未落盘探针脚本或覆盖作者证据。

另对ERROR“重试此报告”做同步双击探针，延迟resume响应；实际只收到1个resume，不作为问题：

```json
{"probe":"error-retry-double-click","fixtureOnly":true,"resumeCallsBeforeAnyResponse":1,"problem":false}
```

## 其余范围内核验

- observer只串行读取/恢复原报告ID，冻结appCode/draftRev/candidateSha256/createdBy并验证返回归属。不会重新POST创建、替换cases、批准或cancel业务。ERROR默认停止，显式恢复才POST原id/resume；401/403和其他4xx停止；网络连续4次后暂停，等待1/2/4/8秒有界。
- 选报校验id、候选和createdBy元数据；非owner仅可读，未知principal默认不resume。候选/应用/账号权限变化以及卸载使旧generation/AbortSignal失效。上述权限ACK窗口和详情互斥是独立未覆盖缺口。
- 创建体在await前冻结当前app/rev/SHA及EvaluationInputs原cases；没有从actual生成expected。普通ACK丢失/不合法响应分支保留unknownCreate并阻新建，作者已有找回场景成立，但不能覆盖本次权限变化分支。
- Report先展示状态、通过/失败/待完成、安全结果，原JSON和expected/actual折叠；pendingCase不把等待passed=false误算失败，依赖error计失败。未引入前端评分器。
- App在活动/未完成/待重新预检时阻发布；ACCEPTED后仍须明确重新预检并绑定，候选变更清旧报告，最终由服务端发布凭证判定。没有把ACCEPTED直接当成可以跳过预检。
- 实际用view_image复核`frontend-evaluation-async-green.png`、`frontend-evaluation-async-390.png`，状态/安全统计/动作可见，窄屏内容换行。本范围无确定新增视觉阻断。

## 限制与交接

最近200份tenant列表、完整output精确判分、多用例治理/LLM judge/生产IAM/供应商/容量等是已声明边界，本次不另列无关整改。历史真实纯FLOW链不充当本轮异步G5验收；待部署后真实runner须另行执行。

两项P2已向root即时报告，待修复及定向复核后再改变本局部结论。**本轮web target已释放**；后续可由root统一运行全套及构建。本 reviewer没有修改旧前端review历史结论。

## 两项P2修复后的独立复验

2026-10-20。**APPROVE（本增量）：原P2-1与P2-2均关闭；限定复验范围未发现新的确定P1/P2。** 本节更新原REQUEST_CHANGES状态，前文发现、反例与源码SHA作为历史完整保留，不代签G5后端或整个任务。

已读`frontend-evaluation-review-fixes.md`、对应SHA清单、唯一生产修改`EvaluationPanel.vue`及新增六项真实SFC测试。复验开始时补丁5文件SHA全部匹配，原10文件after值全部匹配，其中其余9项未变。原manifest摘要及本报告追加前摘要与作者交接记录相同。复验对象：

```text
EvaluationPanel.vue SHA256
565f4db747169ba30144e6696b515094d202258dc0feb5bdea164396b2b5441d
```

### 修复判断

- **P2-1关闭**：发送创建前即记录未确认标记，角色/token/账号/候选变化或卸载不删除该旧标记，旧ACK仍按generation/abort丢弃。同身份回到原候选时unknownCreate重新生效。标记键包含候选身份、tenant和principal，值仅datasetVersion；普通有效ACK、明确4xx或同候选/原创建人/相同datasetVersion的有效找回才解除。其他人的报告或错数据集的详情不能解除原标记。作用域为组件模块，SPA退出再登录导致的子组件重挂载不会清空它。
- **P2-2关闭**：listLoading与choosing独立，create函数与按钮均检查choosing，选报入口检查creating/choosing；详情finally受generation与detailSequence共同约束。列表刷新只改变listLoading，逆序列表完成不会解除详情锁，旧候选详情finally不会解除新候选的锁。400详情错误释放本次自己的锁并显示错误，不遗留永久loading。
- 模块内Map是防误重发的内存提示状态，不保存token、cases、报告正文或身份票据，也不是PG权威源或后端幂等实现。整页刷新/关闭会清空它；创建API没有客户端幂等键，所以不保证整页刷新后的exactly-once。这个明确边界没有被本次APPROVE改写。

### 亲跑原两个独立Chrome反例

重新使用上轮的路由fixture、真实App/SFC、延迟首个创建ACK/已有详情的交互，在web目录以PowerShell内联脚本 `@' ... '@ | node --input-type=module` 执行。全新隔离Chrome上下文；全部业务请求拦截，未改作者脚本/证据、未真实业务I/O。

修复后禁用创建时另外强制dispatch click，确认函数入口也阻止重发。详情分支以400结束，验证锁恢复。进程退出0，亲跑输出：

```json
[
  {"probe":"permission-change-during-create-ack","fixtureOnly":true,"createDisabledWithoutRecoveringFirst":true,"createPosts":1,"unknownAckWarningVisible":1,"pageErrors":[],"passed":true},
  {"probe":"choose-existing-versus-create","fixtureOnly":true,"createDisabledDuringExistingRead":true,"newCreatePostsDuringRead":0,"enabledAfterDetail400":true,"pageErrors":[],"passed":true}
]
```

原先2次POST变为1次且保留未知ACK提示；原先详情在途产生新创建变为0次，均与原反例逐项对应。

### 亲跑补充状态与观察回归

实际命令（web目录）：

```text
npm test -- --run tests/evaluation-panel-review-fixes.test.ts tests/evaluation-observer.test.ts
```

07:38:43，退出0，2文件/19测试通过、553ms。六项真实SFC包含权限/token往返、账号/候选返回、同owner且同dataset找回、他人/错误dataset不能解标记、逆序列表finally、新旧候选详情finally；13项observer原有边界继续通过。未重跑102项全套或构建。

另亲跑作者新增八场景Chrome脚本的**内存重放**：读取其原源码，断言与业务fixture不变，只将persistent context换成新临时browser、截图改为返回内存buffer、移除最终JSON落盘语句，并将root定位等价指向本工程；不修改原文件、不覆盖作者图片/JSON。脚本入口为web目录PowerShell内联Node，执行输出：

```json
{"checks":{"permissionRestoreKeepsUnknownAck":true,"detailAndCreationMutuallyExclusive":true,"listRefreshCannotUnlockDetail":true,"detailFailureReleasesOnlyItsLock":true,"candidateReturnPreservesOriginalUnknownAck":true,"accountReturnPreservesOriginalUnknownAck":true,"otherOwnerCannotClearOriginalUnknownAck":true,"sign-inReturnPreservesOriginalUnknownAck":true},"pageErrors":[]}
```

最终进程退出0。这里明确是本reviewer亲跑原测试逻辑，不将其称为第二套独立设计的八场景。首次临时重放曾把截图调用整个删掉，丢失原脚本渲染等待，导致转390后即时横溢断言失败；该次未完成八场景，不作为产品缺陷或绿证据。保留截图动作但只返回buffer后，同断言及八场景完整通过，未改产品或业务断言。

### 仅阅作者记录、视觉与交接

已读作者`frontend-evaluation-review-fixes-unit-all.log`的27文件/102测试、构建日志的三端产物完成，及8+16+23场景记录；这些结果与本次19项/两独立探针/八场景内存重放区分，不冒称本次全量执行。

实际view_image检查`frontend-evaluation-review-fixes-unknown-390.png`及`frontend-evaluation-review-fixes-detail-1440.png`：未知ACK提示、找回入口、禁用创建及详情载入提示可见，未发现本次新增视觉阻断。ERROR双击仍为原评审非发现，不追加整改。

本次仅追加本报告，未改生产、作者测试或其他历史文件，未启停后端/Vite、派子agent或跑真实G5 runner。**web target已释放**。G5后端review返修与部署后真实异步联通仍须独立完成，本APPROVE不宣称其已通过。
