# CR-05 最终源码整合与检查边界

2026-11-04。阶段1保存点为`5da4d115`；本记录承接后续新增来源链，不覆盖阶段1失败或未复跑记录。只用现有DEV worktree，不使用superpower，不启动整栈/三路线/独立审核/全量验收。

## 新增实现

- 原始规格＋模板/包精确版本＋人工合同＋批准连接，按session/plan一次冻结。app V120保存不可变来源；V121追加IMPORT_SKILLS；skill V320保存幂等真实草稿回执。相同请求恢复原草稿，人工改稿或异摘要拒绝。
- 新技能在原工作台完成显式连接授权、真实连接测试、选型验收和发布；返回向导后只读精确PUBLISHED版本并按原生OPENAPI五字段、Schema/httpMapping、端点/守卫等严格比较。不自动授予新code，不生成expected/PASS。
- source关系由真实数据库绑定决定，AppService保留旧全局包分支。新分支核完整canonical来源、原包/计划/文档摘要和冻结连接，再用原checkCapabilityReferences；同会话新计划能改绑新不可变来源，旧源/旧发布快照保留，人工draftRev冲突不覆盖。
- RunSnapshot保留原四字段mapping摘要和精确技能ID/版本/sha断言，额外冻结session来源审计坐标；SkillGovernance/engine草稿测试从服务器不可变receipt元数据取refs，拒绝请求覆盖，真实连接证据必须匹配同refs。
- AppAuthorityTransport只向固定已部署origin、目录注册路径和action出站，有界2MiB/30s/调用deadline、禁重定向。IAM使用当前同root能力；旧DEV路径保持。新DEV导入额外验证app专用HMAC请求证明与原服务JWT、角色，未配置即关闭，IAM绝不回退DEV。
- NONE批准绑定明确允许且只允许空secretRef，其它鉴权仍须真实引用；native OPENAPI批准根重绑避免重复base路径，原冻结合同不改。

## 已运行的检查

阶段1：专属DEV PG5/5、规则12/12、网关1/1通过。前端第二轮12/13，最后el-option标签夹具修正未复跑；原失败与断言保留。

新增链路仅编译：JDK21，2026-11-04 19:07:48–19:07:56 +08:00，`mvn -q -pl app-management,skill-registry,engine-runtime -am -DskipTests test-compile`，exit0。最终AppAuthorityTransport SHA256=`f6f913de11b3a6e9cd6f4077794cc3849b19072e39ffe42cd3d31f87e2b72dff`，覆盖最后截止时间检查；前端vue-tsc exit0。此后仅补文档、源码定位元数据与差异检查，没有第三轮功能检查。

只读核对专库Flyway历史：127.0.0.1:35432/agent_cr05_20261104中V117/V118/V119成功；V120/V121/V320未执行。V117的DDL成功不代表CR02五项未复跑修正已通过；skill V319也未作本轮真实迁移复核。

## 使用前配置及仍未复核

- 首版新导入仅支持与冻结包环境根一致的同步只读HTTP、无固定tool.skillRef、精确Schema/守卫/人工合同；新资源需合法包/模板版本。原已发布技能运行换批准B仍归CR02。
- 显式DEV仅为app与skill配置`agent.security.mode=dev-hmac`、`agent.onboarding.dev-app-key-ref=env:AGENT_ONBOARDING_DEV_APP_KEY`及独立密钥环境。私有准备元数据在`D:/CodexPrep/algorithm-reorg/dual-20261103/private/onboarding-service-resources.json`；没有启动服务，密钥不入Git或报告、不共享给engine/model/monitor。
- IAM的DEBUG/controlledDraft仍按原有范围拒绝，不借本项扩大执行域；源码编译不是IAM完整接入已通过。
- 新增SQL/请求证明/跨服务导入与实际验证发布、浏览器真实操作尚未功能复核。原资料/批准连接、新code grant、模型选型及应用评测用例仍须真实提供；不支持/缺资料返回明确missing。CR05继续标“实现已补齐，功能复核未收口”，不能拿阶段1绿替代新链。

分项证据：`cr05-onboarding/CR-05-import-runtime-wiring.md`、`cr05-r2-onboarding-skill-import-20261104.md`、`cr05-import-rules-20261104.md`、`cr05-r6-onboarding-import-ui-20261104.md`、`cr05-app-authority-transport-20261104.md`、`cr05-onboarding-mode-20261104.md`。
