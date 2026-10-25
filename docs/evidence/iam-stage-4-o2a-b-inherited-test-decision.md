# O2a B 最终命令中既有 initial-material 测试前提冲突

root 只读核验决定；不改 B 已冻结593源码/8路径，不重跑 final，不放宽生产权限或受控 authority fixture。独立代码审核仍需判断实际变更，本文不替代其结论。

实际 `iam-stage-o2a-b-final01` 为6类65方法，64通过、0 assertion failure、1 error。Running 与原始日志逐类一致：GraphRules1、FlowMaterialScope11、FlowMaterialStatus11、InitialMaterialScope12（1 error）、新Budget13、新Postgres17。本轮新增两类30方法全通过；不能声称整个最终命令成功，也不能与先前 GREEN 阶段相加成一套测试。

两份原件位于 `docs/evidence/iam-stage-4-o2a-b-material-reader/iam-stage-o2a-b-final01/`，root已逐份核 bytes/SHA，缓存于 `.local/iam-o2a-b-inherited-test-failure-check.json`：

- `iam-stage-o2a-b-final01.log`：38318 B，SHA `268579ab99fad5b83276cc67e08b4d0c19c04ca9a08166b813cdcb922631a0ad`，保留 BUILD FAILURE。
- `TEST-cn.agentplatform.engine.infra.InitialMaterialScopePostgresTest.xml`：55936 B，SHA `c743031e7e7cd218deae64e03017848d58a77a24f6e758ac96fe8ee56605fff7`，12/0/1/0。

唯一错误为 `sameRootStatusPurposeCannotReadInitialMaterial`。Accepted A 冻件中的同一旧测试文件12829 B，SHA `923f157c28235d5bc58421997e8282de80a38ee6ed8b97f433e7ad7892aff5ed`，与迁移后 source589清单相符。root读116–137 EOF，关键128–133：先创建真实 run，再用 `proxy.engine.get.RunController.status` 加 `RunAuthorityFixture.ROOT`（app-version）做 exchange，最后才断言 initial-material reader拒绝且payload0。

Accepted A `RunObservationSelectors.requirePurpose` 41–49 已要求 STATUS 双向匹配 run-snapshot；LoopbackAuthority.requireObservationStatus 518–523 在 exchange时先核该规则与受信观察注册。XML栈在旧测试132行经 exchange→portalExchange→query→denied结束，未进入133行 MaterialScopeStore.read。B没有改这个旧测试、selector或fixture。因而此执行证明的是旧测试 setup 与先前已生效权限规则不兼容，不能当作已验证了它原本意图的“initial material reader拒绝STATUS”。

保持真实失败原件和所有成功阶段，不重写历史、不把error改成跳过/预期拒绝。B报告需明确这项尚未闭环；root安排独立、单个旧测试文件的迁移任务，在 B 独立审核与有限产物核验后处理，C正式开启之前完成。拟保留其余11方法及本方法的拒绝目的：非法 STATUS+app-version 首层拒绝；另取得实际合法 run-snapshot STATUS、签名/JWT/真实 receiver frame成功，再验证不能进入执行 initial-material reader、payload0，避免无效票或未绑定frame造成假负例。具体 API 与校验顺序由只读准备和新的正式 brief 冻结，本文不授权作者扩大当前 B范围。

这是本次 engine 回归中新暴露的一项，之前13项 ServiceAuthorizationTest 冲突已独立迁移闭环，历史两次失败命令各自保留，不混作同一遗留清单。

EOF
