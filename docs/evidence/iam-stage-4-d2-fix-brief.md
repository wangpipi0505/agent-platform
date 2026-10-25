# D2 一次集中返修：原认证锚点与初始上限

root已阅读全文 review-iam-stage-4-d2.md，接受 I1。⚠2是本轮新增 external-policy fixture 的实际建Grant缺口，依据原§2.3“原委托上限 ∩ issuer/client声明上限 ∩ 当前策略”并入同一修复，不留给后续虚假正例。其余⚠移交D3/E/F；生产真实IAM仍闭锁。唯一新工程，不改原设计/四契约、生产接口、密钥格式、业务源或前序证据。

## 范围与起点

原D2 source332/frozen/replay与三个target已归还，原报告 SHA3622e92a45a4fd5bf21776f6bc379f4cca5bd36ee9efffd5161a93c8adecdf0b，原patch1577fea756786bc8b193fb0004628ca71ee7c88232b75b748d99e01bc788b69f。原D2目录完全保留，不能覆盖其final或日志。只允许修改common测试LoopbackAuthority、app IntegrationPolicyResolverHttpTest及其必要私有helper；确需CurrentAuthorityClientTest补直接共享fixture验证可修改该具名测试，其他文件先报root。相关target由你独占，root/reviewer不运行Maven。

新证据目录 docs/evidence/iam-stage-4-d2-fixes/，报告在原 iam-stage-4-d2-report.md 末追加修复段，保留原前缀字节/SHA与原结论历史。独立审查报告仅同一reviewer可追加。无commit/发布/主服务操作，不派agent。

## I1：稳定已验原认证标识

- 严格验证external原集成认证的固定issuer、必需且有界jti以及已有全部主体/credential/app/biz/user绑定。旧ServiceTokens原格式本来含iss和随机jti，不改对外格式。错issuer、缺/错型/空jti和错主体在PG前拒绝。
- 稳定认证锚点以已验issuer+jti为主，绑定原tenant/principal/credential/app/biz/user及初始版本/上限；同一认证标识换绑定或放宽原声明必须拒绝。raw token hash仅可作附加信息，不能成为唯一查找键，不能把同认证另一序列化表示当fresh。
- 实际PG同policy digest保持、revision+1后：原token、等价signature pad-bits表示、同认证标识另一合法序列化的exchange均拒绝；旧resume/authorize拒绝。只有明确新jti/token认证才能成功。可额外拒绝非规范编码，但不能靠此替代稳定issuer+jti锚点。
- 每决定至多一次真实resolver查询，失败不消耗新Grant额度。认证结构/签名失败PG0；旧版拒绝可PG1，不能为探版本查两次。不得新增无限缓存或生产IAM服务，有限已有Grant结构保存足够。

## root接受⚠2为本次新fixture上限修复

当前external分支直接policyActor(actual)建Grant，PG最大允许集合不等于原token已经授予的初始集合。请解析实际已验source token的roles/attributes（现有真实出票本来提供，测试helper也应符合实际格式），复用已有CeilingPolicy等纯规则：角色/许可属性取交，biz/user/tenant/principal归属精确绑定，dataLabels限制义务保留并集，不从PG补回原token未授予的值；剔除内部平台角色，未知字段按既有mapper限制。不能只修改新测试让它等于PG上限。

真实PG测试至少原token role/region窄于PG：PG允许A/B与两种业务角色，原token只A与一角色；Grant initial_ceiling和current都不能增回B/第二角色。原token独有标签与PG标签都保留，后续同Grant/换票/authorize上限不变；合法窄上限成功。缺/错型原claims默认拒绝，不把空上限补成PG全量。samepolicy旧source变体与fresh新jti仍在这条真实HTTP/PG链验证。

## 必须先红、再绿的实际覆盖

覆盖测试具名文件：IntegrationPolicyResolverHttpTest（真实app/controller/security/PG + external authority），CurrentAuthorityClientTest（仅若涉及共享默认fixture），最终一轮完整common（fixture为公共测试依赖，原150默认行为必须保持）。

1. 修改/新增有限测试，保留原实现触发的真正行为红：同认证不同文本取得新policy版本，以及初始窄claims被PG补宽。setup/编译失败不能计行为红。可复用reviewer .local/review-iam-stage-4-d2-nimbus-check.java生成一个确定pad-bits变体，不枚举/耗尽，不打印密钥/token。
2. 修复后先运行新增具名方法/直接影响覆盖。每轮完成立即归档该轮XML+rawlog，再进行下一轮；不要让target覆盖后拿后轮XML代替。
3. 一轮完整common与最终app真实HTTP测试足以覆盖本修复；无新问题不重复绿轮、不跑七reactor/旧业务无关suite。沿用受控 .tools/run-iam-module-tests.mjs 与原专用DB，唯一iam-stage-4-d2-fix-*阶段名。源码与生产相关文件未动，gateway5无须为确认重复。
4. 报告必须有具名测试、真实命令、原输出/结果、红绿区分、最终同码SHA。原D2完整补丁与本次patch均保存，或给出相对原D2baseline的合并patch并实际apply全部最终源字节一致；不得覆盖原patch。保护旧D2目录与原报告前缀，新增目录例外明确，冻结source/targets后归还root再交同reviewer复审。

## root逐项接收的跨Task事项

⚠1实际生产receiver/全gateway R3在D3；⚠3真实HTTPS/持久IAM锚点和签收在F/G且生产闭锁；⚠4原业务全量回归在迁移后统一轮、原契约root已独立检查+原v1.2真实SHA3c72；这些不提前计闭。M1日志/CRLF、M2中间绿色XML粒度列open-review-notes供最终wholebranch复核，本次不改历史或倒跑。

补充：本工程没有同名CeilingPolicy类，上文该名称仅指已有上限集合语义；root已确认复用现AuthorityProtocol等纯规则/最小external私有helper，不要求新建生产类。许可属性依既有白名单逐项收窄，未知不补权。
