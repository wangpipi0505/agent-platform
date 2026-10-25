# D4a I1 日期顺序修复：聚焦复审

## Spec Compliance

✅ Spec compliant（本次两文件修复范围）。原 I1 已关闭：`admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityResponseVerifier.java:62` 仅对 elapsedSession 显式要求 `exp > nbf`；无效顺序由同方法 `:65` 的既有 catch 转 503。普通 verify/static verifyPinned 的默认路径及原日期 predicate 保持，未添加 proof 工厂或扩大会话权限。

⚠️ 本次直接验证范围为 fix brief/report/handoff、两文件全上下文补丁和输入哈希。源码/target物理封存、两条实际命令与 XML、四变体原件、57项 own seal、7项 target 归因、重放及保护锚点由 root 独立有限审计，本 reviewer 不重复执行或把报告声明当作亲自核验的事实。完整实际 GatewayApplication legacy 启动未在本批执行；原有限实例化和 root 结构核验仅关闭“新增强制 IAM client 依赖”的窄风险。生产 IAM 闭锁、浏览器及后续 D4b/整体项目验收的边界继续保留。

## Strengths

- **修复直接对应根因且作用域最小。** `admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityResponseVerifier.java:60` 至 `:62` 的注释说明原 `nbf <= now < exp` 隐式关系为何在允许 elapsed 后消失。新条件同时拒绝等号和大于；`:63` 继续检查未来 iat/nbf、nbf>=iat、exp>iat、TTL 和普通路径 exp>now。`:42` 至 `:43` 的公开 static verifyPinned 仍传 false，`:33` 至 `:40` 的会话专用入口仍复用同一验签实现。没有复制签名链或改变非 session 默认异常语义。
- **真实负例瞄准丢失的条件。** `admin-common/src/test/java/cn/agentplatform/common/authority/PortalSessionAuthorizationTest.java:185` 的一个新 @Test 在 `:188` 运行 current/ticket × nbf==exp/nbf>exp 四变体。`:191` 至 `:198` 使用原 fixture 变换设置 exp=iat+1、nbf=iat+1或+2，并将时钟推进3秒；current 只改含 ticket_jti 的真实 authorize 决定，ticket 改真实 exchange ticket。没有未来 nbf 或来源过期掩盖 I1，也没有 mock 成功 proof。
- **测试会在原403行为下跑完四变体。** `PortalSessionAuthorizationTest.java:200` 至 `:213` 将成功返回记录为200、PlatformException记录为实际status，再 soft-assert 503、精确时钟、portal/grant仍live、current真实exchange+authorize或ticket仅exchange、businessSends=0。`:216` 至 `:224` 在 aggregate assert 前保存四条观测并断言数量，故一次 JUnit failure 可以包含四个行为红；没有把循环内部变体充作四个独立 case。意外非Platform异常会直接成为 error，不伪装所需行为红。
- **控制覆盖与变更相关。** `PortalSessionAuthorizationTest.java:129` 的合法 elapsed exchange/authorize403及 `:137` 的普通verify/pinned signed-expired503 控制均未改。作者报告本轮 green 只跑新case和这两项控制（`docs/evidence/iam-stage-4-d4a-time-order-fix-report.md:36`），与一行session日期条件的影响范围相符；不需要为此重跑原HTTP/B2/PG或全部24例。原24+新1仍是分批适用覆盖，不能宣称一次最终同源25-case suite。

## Issues

### Critical（Must Fix）

无。该修复只增加拒绝条件，未发现成功证明或成功字节逃逸的新路径。

### Important（Should Fix）

无。原 I1 在 `AuthorityResponseVerifier.java:62` 完整修复，等号与大于两类非法时间顺序均被处理；合法 elapsed 路径仍可供后续完整请求绑定后归类403。

### Minor（Nice to Have）

**M1，继承的非阻断诊断观察；不是新的行为缺陷。** `docs/evidence/iam-stage-4-d4a-time-order-fix-report.md:54` 明确保留原编译/日志诊断，并报告本轮两份344 B Surefire absolute Manifest-JAR/different-root dumpstream。原 compile deprecation/unchecked、历史失败与预期非法安装 ERROR 不等于意外生产错误；本轮源码没有修改 runner/POM/工具链。应保留原件和诊断范围，由 root 在现有证据审计中核实归因，不采纳关闭 JDK 路径检查的 hint，不清洗、抑制或为得到 pristine 输出重复运行。此项不阻止本次 I1 修复批准。

## Assessment

**Task quality: Approved（聚焦 I1 修复及本批引入问题）。**

三行生产增量包含两行必要注释和一个session限定条件；新增41行单一测试服务于四个真实风险变体及失败证据聚合。未发现新 Important/Critical；批准不重签原任务以外的功能，也不把未执行的完整应用启动或生产 IAM 验收视为通过。

## 实际读取与证据边界

- 沿用首次已完整读取的 `D:/00-Work/02-Application/01-CodeIDE/UserData/Codex/DotCodex/skills/subagent-driven-development/task-reviewer-prompt.md`。本次没有重新读取原4288行全包、未读补丁外业务代码、没有 broad callsite crawl/新子代理。
- 完整读至EOF并实际核SHA256：fix brief 5940 B，`41ceaf92ecb50645cfd9cb4293917cbe3460aa6f9e9dc81f62e023649c5420dc`；fix report 8273 B，`55ecc6a399c7e410b0ebdf091642a09101666dcb36e4ff5db42c74bcb8544f95`；fix handoff.json 3081 B，`89ad64b8046b6ad6e57af7dd91d3817ea1f2c679f21dac654a641ef03bb5f6d3`。
- 唯一补丁 `docs/evidence/review-iam-stage-4-d4a-time-order-fix-package.diff`，23129 B，SHA256 `bbb714e45e395cf2243221406b3a8fc6a275b86b1f12e5b7afd8d670c47dc0b5`。实际一次读取包行1–120、121–240、241–311，完整无截断（311逻辑行，换行split312）；包含 AuthorityResponseVerifier新文件1–71、PortalSessionAuthorizationTest新文件1–228全部上下文。没有另外读取current改变文件。
- 包标识 source manifest基线 `7e1a1b53ae7b494361bf75b36d2878a1f4dbb1e5d0631c3311643a8e34822708` → `f56b205dcf81af1e9be357e49fae284768916a920cb36343d2aac50f0ee5aed2`，无HEAD/commit。本 reviewer 没有自行重建全树或重放patch。
- 原审查 `docs/evidence/review-iam-stage-4-d4a.md` 本次仅核元数据：12551 B，SHA256 `faf39ec6a08f1fcd8376077e237a870385aa2a60d5e814e5f83ed2fed84c2da6`，未追加或修改。
- 作者实际执行声明：red 1 case/1 failure/0 error，四实际403；green 3 cases全pass，新case四实际503；见fix report `:28`、`:36`、`:38`。源码支持这些 oracle 的可信设计与统计口径；实际命令/raw/XML/variants、精确两PID退出及全源/target/own保护是否吻合，由root独立审计。没有将作者红绿结果表述为reviewer执行结果。
- reviewer 没有运行测试、Maven、HTTP、浏览器、探针、资源suite、sleep循环、git命令或旧594/123837/16347扫描。所有shell显式workdir均为 `G:/207/项目/算法重组工具/代码/agent-platform`。
- 唯一写入新文件 `docs/evidence/review-iam-stage-4-d4a-time-order-fix.md`；source/target/旧报告/作者证据/git/index/HEAD均未改。完成后本报告写权归还root。