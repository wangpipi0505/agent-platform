# G6 Task1 独立任务审查（等 root 移交冻结差异后执行）

你审一项任务是否满足要求、质量是否可接受；不是全工程合并审查。root将另行提供完整差异文件及manifest。要求源为 `docs/evidence/iam-stage-1-implementation-brief.md`，报告 `docs/evidence/iam-stage-1.md`。brief末尾已补原批准计划第4节；不能只按开头的Task1摘录审。

## 绑定约束（批准计划原文）

- 工作根目录仅 `G:/207/项目/算法重组工具/代码/agent-platform`；不读取相邻旧工程。
- 唯一设计依据 v1.2；`contracts/snapshot/detailed-design-v1.2.md` SHA256=`3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`。本次只读核对摘要一致。
- 原四契约不改；模块 2 本体平台范围外。新增 IAM 适配协议是内部基础设施约定，不能回写冻结契约或冒充原设计已有接口。
- PG 是唯一业务权威源；跨服务 HTTP；tenant/principal 只能来自已验证身份。凭据仅 secret_ref；不读取或打印 `.env`、vault 文件、master key 或真实凭据。
- 发布门禁 `BLOCKED_IAM_SCOPE_ACCEPTANCE` 保持。没有生产 IAM 签收不得改为 `IAM_SCOPE_ACCEPTED`，隔离夹具通过也不例外。

## 本次具体范围与已裁决的接口库存口径

Task1只写common纯类型、显式目录/属性语义、模式装配及必要测试，六服务源码只读以盘点handler/实际caller。六服务HTTP能力守卫和运行时handler枚举接入属于Task3/4；本Task的目录不是资源授权或已迁移功能声明。现有主六服务继续运行已固定962制品，不在本轮重启或改业务application.yml。

逐真实调用盘点；计划图示未列但源码实际存在的skill→model `POST /internal/model/chat`补最小边。现有handler无实际调用者的caller边明确禁用；尚不存在的material/resolver入口另列未来待办，不能声称已有覆盖。callback尚无已注册具名client，不虚构“已批准”身份，登记禁用并保留后续接入待办，dev/test旧callback源码不改。这些是目录库存范围；未使用能力不表示设计功能取消，未来新增caller/handler须显式再登记。

特别检查：缺mode/未知mode不得回退HS256；dev-hmac的显式dev-auth/loopback及独立test限制；完整iam配置也因后续可信链未装配而在监听前拒绝，不得有配置自报ready开关。单纯route字符串匹配不能授资源；ADMIN不覆盖caller能力。许可交集不能补权，dataLabels限制义务保留并集，主体/归属必须相等，未知属性不映射。已批准计划的规划历史和846叙述不是本次执行状态。

## 审查方法与交付

一次读完整差异及报告，对照实际行为红/绿/完整common原日志；不要把最小缺类接缝编译失败当行为红，也不要把开发旧身份测试当新可信授权通过。源码/完整baseline/frozen/真实apply及SHA用于无HEAD的审查，禁止空git diff作证。不要重复作者同码完整测试；仅当具体疑点未被现有运行回答时做有限定向检查并明确原因，不跑全后端/耗尽/慢攻击。差异外只对可点名风险作定点只读核对，列出看了什么；不能由范围不可见便扩为全工程爬取。

不改source/target/POM/原证据，不读真实凭据、不启停服务、不Maven package/提交/发布。只新增自己的review证据并写 `docs/evidence/review-iam-stage-1.md`。先给Spec compliance与⚠️不可由差异验证项，再给具体优点、Critical/Important/Minor的file:line/影响/修复建议和Quality Approved/Needs fixes。报告中的设计解释也是待核声明，勿按作者自评降级问题；不要预设结果。root处理不可验证项及全工程阶段回归。
