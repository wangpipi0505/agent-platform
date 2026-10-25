# Initial 材料旧测试输入迁移正式任务

B及借用修复已Accepted，本文件现为正式任务要求。root已确认B修复仅Reader/BudgetTest/PGTest三项，所需STATUS frame/Controller/fixture API均沿用既有冻结准备，旧Initial文件身份仍相同。唯一 workdir `G:/207/项目/算法重组工具/代码/agent-platform`。任何 shell 显式 workdir；不访问相邻旧工程，不改 v1.2/四契约、不 commit。

## 唯一需求

只改 `engine-runtime/src/test/java/cn/agentplatform/engine/infra/InitialMaterialScopePostgresTest.java` 中 `sameRootStatusPurposeCannotReadInitialMaterial` 和必要 imports。12个原测试名与顺序全部保留，其他11个方法的原始字节必须不变；不改 fixture/生产/runner/POM/其它测试。

先完整阅读 `.local/iam-o2a-b-initial-test-migration-notes.md`（12799B，SHA256 `bec015b38922e906f69d1917ce5051a4333368cba326f5dca2047f042ab067bb`，72内容行到EOF），其中真实setup、三参handler和拒绝顺序是本任务实施依据。该准备文件未执行测试。

旧文件基线12829B，SHA256 `923f157c28235d5bc58421997e8282de80a38ee6ed8b97f433e7ad7892aff5ed`。原B final01真实RED在 exchange 旧132行终止，STATUS+app-version 已不合法，尚未到 read 断言。见冻结 `.local/iam-o2a-b-inherited-test-failure-check.json`、`docs/evidence/iam-stage-4-o2a-b-inherited-test-decision.md`、B独立审核。不得重跑原65项或旧错误输入；保留该历史错误，不把它记成 B reader 行为失败，也不把旧测试语义放宽为只断言exchange失败。

## 精确行为

1. 真实production受理得到 run 和 initial ref。对原 STATUS+app-versionROOT 的 gateway.exchange 显式断言403，保留不合法用途负例。
2. 从真实 `persistedBinding` 取得 owner/publicId/**已接受 snapshotSha**，不拿候选 ROOT.versionDigest 冒充接受摘要。用既有可信测试登记 `f.iam.registerObservation(b.owner(),b.publicId(),b.snapshotSha())`，构造 `RunObservationSelectors.status(b.publicId(),b.snapshotSha())` 与 STATUS_ACTION。
3. 同 Portal 的新 exchange 结果必须经实际 TrustedJwtDecoder、真实 MockHttpServletRequest GET `/admin/runs/`+id、servletPath、`BEST_MATCHING_PATTERN=/admin/runs/{id}`、URI变量、真实 `RunController.status(String,HttpServletRequest,HttpServletResponse)` HandlerMethod。`new RunController(null,null,null,null)` 只用于handler描述，不调用Controller业务。
4. 用 `ServiceAuthorization(engine,policy,f.engine).preHandle` 真建立 frame，JWT放 SecurityContext、request放 RequestContext。通过 `AuthorityContext.requireInitialFor(Actor.current(),resource)` 取得合法 STATUS 已验证 proof；在拒绝前明确 requireVerified/requireBound，并核精确call/caller gateway/source+origin PORTAL/owner/resource。
5. 在同active frame调用原 MaterialScopeStore.read(ref,proof) retained重载，精确断言403/RUN_AUTHORITY_DENIED、payload读取0。允许原有限控制元数据SQL；同时确认 beforeSql 中没有snapshot/inputs/version_refs/row_to_json等完整材料读取。不要声称整个SQL为0。STATUS purpose在payload前被拒，不能因失效/缺frame/错误owner或resource抢先失败而伪装验证成功。
6. guard.afterCompletion、security.clear、request.reset 使用嵌套finally，decoder/fixture正确close。不得fake VA/Retained、重签阳性、生产放宽权限；不得用 `f.admitted(call,work)`，它固定POST handler，会造成新的错误路由负例。

## 验证与有限交接

只执行一次未修改IAM runner：`node .tools/run-iam-module-tests.mjs iam-stage-o2a-initial-migration-final01 engine-runtime InitialMaterialScopePostgresTest`，完整12方法。原冻结失败作为RED来源，修复后这一次全类是GREEN。若真实新错误需要修复，只针对失败方法，不重复已绿全类，最终精确记录source epoch。禁止重测旧B两类、原65、旧Service14、Profile28等已结项。

新local独占 `.local/iam-o2a-initial-migration/`，新evidence独占 `docs/evidence/iam-stage-4-o2a-initial-migration/`，helper `.tools/iam-o2a-initial-migration-*`。交付报告 `docs/evidence/iam-stage-4-o2a-initial-migration-report.md`、handoff.json、sole-self-excluded own-inventory.json。只读继承最新Accepted593与逻辑target，不全树扫描；保存单文件pre/post、12名顺序/其他11方法原字节机械比较、actual command/Running/XML/raw、有限compiler candidate overlay和单独new diagnostics。source593仅1测试更新、生产0、其余592继承；所有成功物理阶段journal/hash缓存。

本任务新补丁只含1测试，私有593源码首个真实check/apply与结果等同须记录，不使用修复后copy掩盖apply；若原成功物理阶段完成后metadata失败，只恢复未完成元数据，不重复test/replay/hash。没有HEAD，不做commit、不改工程.git属性，不清理其它目录。所有产物封存后归还写权；返回短状态/测试结果/报告、handoff、inventory身份和限制。fresh reviewer 后 root审计再关闭该一个迁移待办，不宣布整体回归或所有IAM完成。

## 正式Accepted基线

- source `.local/iam-o2a-b-borrow-fixes/source-after.json`：593文件/112856B，SHA256 `83e1fc79f72a4b21bdeb75a3cc41b7d0406797070f25e4242fc74407b42f1a20`。target同目录target-after.json：逻辑2060/427783B，SHA256 `a23b333e7e0efd1f4d74cfac75804d9c791a3f9bbc4905c3ca92b132009e1fac`。
- review `docs/evidence/review-iam-stage-4-o2a-b-material-reader.md`：19026B，SHA256 `821899b48f6410564834c3b6b86961ae545d56998fbc624663d45dec0b93e793`，最终Spec/Quality Approved，C0/I0/M1工具噪声。
- root原B7phase5836与fix4phase2170分别PASS；8索引已同步，checkpoint `.local/checkpoint-iam-o2a-b-docs-before/manifest.json`：4259B，SHA256 `6798b4d61b137ef0c87199319b7703ffddb4e9f2531f69cb22f9813269f85422`。全部只读继承，不重复旧审计/重放/测试。
- 当前唯一源码写权只为本文指定Initial测试文件；C未授权、无其它writer。目标报告路径保持上文，helpers仅自己的新前缀。
