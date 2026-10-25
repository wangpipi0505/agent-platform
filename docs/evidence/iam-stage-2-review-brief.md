# G6 Task2 独立任务审查（待 root 移交冻结包）

唯一工程 G:/207/项目/算法重组工具/代码/agent-platform。先读 `iam-stage-2-implementation-brief.md`，含完整原Task2、信任域、配置与数值、Task1实际接口及正式移交；再读作者 `iam-stage-2.md` 与root未来给出的完整差异文件。无HEAD，以完整baseline/frozen/真实apply和SHA审，不造空git diff。此文件本身不授权提前运行。

## 绑定约束（批准计划原文）

- 工作根目录仅 `G:/207/项目/算法重组工具/代码/agent-platform`；不读取相邻旧工程。
- 唯一设计依据 v1.2；`contracts/snapshot/detailed-design-v1.2.md` SHA256=`3c72b2062beb2e8b377f2384843ea935a0074b50c0063e5106566bbd8dcd0c4f`。本次只读核对摘要一致。
- 原四契约不改；模块 2 本体平台范围外。新增 IAM 适配协议是内部基础设施约定，不能回写冻结契约或冒充原设计已有接口。
- PG 是唯一业务权威源；跨服务 HTTP；tenant/principal 只能来自已验证身份。凭据仅 secret_ref；不读取或打印 `.env`、vault 文件、master key 或真实凭据。
- 发布门禁 `BLOCKED_IAM_SCOPE_ACCEPTANCE` 保持。没有生产 IAM 签收不得改为 `IAM_SCOPE_ACCEPTED`，隔离夹具通过也不例外。

## 已明确的 Task2 profile 与阶段接口

root接受受限客户端profile：RS256 access JWT `typ=at+jwt`；门户 `token_use=user`、已签 `client_id` 精确绑定配置客户端、`scope` 为按空格分隔的字符串；内部 `token_use=service` 与 `authority_mode=delegated|workload` 互斥。workload caller/sub/client_id精确登记且动作只取明确机器能力，不得投影用户身份。不要把这一profile当v1.2字段或RFC强制，不声称任意外部IAM兼容。Portal现有属性键名白名单不能替代region等值上限；dataLabels是义务，不能裁剪削弱。实际新增配置/映射以差异核实。

Task1原I1已独立闭合，前置为61 common和28源码；本次应从ownership-fix后的源码起步，避免回滚两行拒绝null/blank归属。Task1目录返回null resource只是能力描述，不可当已核资源。Task2不负责Task3在线authority/response/nonce/持久grant或Task4六服务能力/资源链，不从本阶段验签通过推导它们完成。

## 核对重点与审查方法

对照完整原Task2负向矩阵：固定可信域/算法/用途/精确单aud/issuer/client/必需字段与类型、Clock边界、service与workload分离、真实签名和直接受保护HTTP拒绝、声明上限与scope、JWKS轮换/删除/503和恢复。固定JWKS每次读取，不以旧key/旧决定缓存或HS256回退。普通JWT≤16KiB、JWKS响应≤64KiB/公钥≤8等值按原brief核。

BoundedIamHttp必须在接收时限字节、使有界期限涵盖正文而非仅响应头、无重定向/令牌自报URL发现；核资源取消/关闭与固定目的地。不是读取全部大body后再比较长度。测试限小样本/明确闩锁/下调超时，不能耗尽或攻击式慢探针。网络/不可验证上游503、签名/用途错误401、已验范围拒绝403按原§4核，handler计数不能执行；不要只看异常字符串。

生产mode=iam仍须在业务HTTP监听前闭锁；独立测试Web配置可以装配真实decoder/mapper小链，不能存在生产ready/跳过guard。dev/test仍显式loopback且不从缺失mode回退。六服务配置和当前962运行制品不在本轮迁移/重启，不把本次common结果相加成新七模块统一测试。

一次读完整差异，先检查spec再quality；报告不等于证据，作者设计解释同样需核。差异外只对具体点名风险做定点检查，记录理由/文件，不全工程crawl。作者已跑同码选择器与完整common，勿重复suite；只有明确未回答疑点才有限定向验证。只新增review证据并写 `review-iam-stage-2.md`，不改source/target/POM/原证据、不读真实凭据、不启停服务/package/提交/发布，不派子agent。

给Spec compliance、⚠️无法由差异验证项、具体优点、Critical/Important/Minor的file:line/影响/建议，以及Quality Approved/Needs fixes。任务审查不代替全工程合并审查或生产IAM签收；root处理跨阶段不可验证项与后续统一联测。
