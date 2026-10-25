# O2b D后续：仅Selectors旧测试迁移正式任务

前置D作者已冻结交权；fresh D功能规格Spec compliant/Quality Approved，C0/I0/M3；root有限审计5阶段6476项PASS。root已完整读独立报告1–103EOF并确认不需D生产代码修复。现在授权本唯一测试方法体修改和本任务有限target，其他源码无写权。独立review为docs/evidence/review-iam-stage-4-o2b-d-common.md，15845B/SHA18114524d7002e5cc996ddfa34ec91771f8c4c39d6a4451b73e1e65fdc8f5393；root审计8764B/SHA854a6785b5bf8d0a83a4018c9da6195f3556442669c14a67fc68eb41ee71250d。唯一workdir G:/207/项目/算法重组工具/代码/agent-platform，每条shell明确设置；不访问相邻工程，不改v1.2/四契约/时间，不commit/deploy，不改production/fixture/runner/POM。应用receiving-code-review/TDD/verification/ponytail。

D最终真实六类87=86pass/1F/0E/0S；新G18+child22及A28/closed2/Service14已绿，旧Selectors3中的statusPurposeIsBidirectionalAndOnlyAcceptsFreshPortalSnapshot:15仍将engine EVENTS放在拒绝列表。该测试旧5714B/SHAf5224ed46f0673cecc30f511c8f67e112f4eb8323ea2ec819c40c092aa9d8d78在Accepted C600清单已存在；C已允许run-snapshot↔STATUS|EVENTS。D未改public requirePurpose须由前置review/identity证据确认。原87/1F永久保留，既有实际failed XML为本次RED，不重复跑它来再现。

## 唯一修改

admin-common/src/test/java/cn/agentplatform/common/authority/RunObservationSelectorsTest.java，仅第一个既有方法体statusPurposeIsBidirectionalAndOnlyAcceptsFreshPortalSnapshot；3方法名/顺序不变，其他两方法canonicalQueryBindsVerifiedSubjectPathAndProjection、invalidInputIs400AndPurposeIsBidirectional原始字节不变，所有imports/helper/方法外字节不变。不得修改production或新增helper源码。

把EVENTS从错误负action表迁入真实正例，与STATUS分别验证正确engine/gateway/delegated/PORTAL/PORTAL/run-snapshot用途配对；同样核错误resource/target/caller/mode/source/origin被拒，旧Q/执行POST/run.advance不能拿run-snapshot通过。G action gateway.run.events.observe仍是private purpose，公开requirePurpose必须拒绝其真实gateway/engine目标的尝试，不能借测试迁移放宽G。使用现有EVENTS_ACTION和明确固定值，不造stub/VA/Retained正例。此类只验证selector grammar/purpose，不声称PG新鲜度或真实HTTP/SSE。注释解释STATUS/EVENTS公开用途与G私有用途，不写逐句废注释。

最终只一次完整3方法命令：node .tools/run-iam-module-tests.mjs iam-stage-o2b-d-selector-migration-final admin-common RunObservationSelectorsTest。source epoch先保存、实际raw/XML/txt/command/cwd/exit立即归档；没有PG、不重跑D六类87或C29/24/全后端。目标3/0/0/0；生产0/fixture0，实际原87/1F不能重写为87全绿。若具体编译/语义阻碍，先给root证据，不扩大范围或改旧方法名。

基线source603 .local/iam-o2b-d-common/source-after.json SHAab4a97cab7548de056a5548e129b49c6e19c0c4692ac82fa8f4857e19ec0087c，target .local/iam-o2b-d-common/target-after.json SHA5f425586d177676f2a205b5330ef9ffa63ef21ce9fd1cdd2405529aef6ce1191；D根审计.local/iam-o2b-d-root-check.json，私有完整603 baseline .local/iam-o2b-d-common/final-replay/。前述身份和路径已由root实际有限核验。唯一一个test delta，source数量603保持；before/after和三方法/方法外字节不变证据。有限target仅本轮实际common两compiler阶段/资源/tests fixtureJAR/actual Running一类XML+txt，新dumpstream独立；继承D历史逻辑不全树hash或扫描旧target。

新local .local/iam-o2b-d-selector-migration/，evidence docs/evidence/iam-stage-4-o2b-d-selector-migration/，helper .tools/iam-o2b-d-selector-migration-*；报告 docs/evidence/iam-stage-4-o2b-d-selector-migration-report.md。原D/C全部report/证据/inventory/incident/replay/journal不可改；八索引root在D与此迁移/必要fix均Approved后一次更新。首次真实新私有raw patch check/apply，final603名称/bytes/SHA一致，无apply后copy/no commit；所有dependency命令/session必须等真实exit0再下一步，成功物理步骤journal/hash-cache，不重演D早期index竞争/证据丢失。compiler lists/原始日志用copyFile或直接Buffer写原字节，不能通用JSON写入器把Buffer转为type/data对象。own-inventory仅自身排除，含私有Git/精确helper，封存后交还全部写权且不再改；随后fresh independent reviewer/root有限新审计。作者完成不是Accepted。

封存handoff.json请使用清晰的file/bytes/sha256条目并明确sourceBefore/sourceAfter、targetBefore/targetAfter、finiteTargetOverlay、finalEpoch、testResults、patch/replayCommands/replayRoot、ownInventory、journal、writeAuthority，数组先给count/schema再读取，避免输出完整manifest。可只建一个完整603私有Git基线，生成patch后还原该唯一test到before再一次check/apply，避免不必要重复整棵copy；绝无apply后copy。新stage实际源数量603/唯一delta必须确认。
