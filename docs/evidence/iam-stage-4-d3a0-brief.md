# D3a0：published 元数据查询组件

正式授权：D2集中返修已独立Spec/Quality Approved，root已全文读取最终复审；source/target归还。起点332源码/POM、common150/HTTP19，返修patch e805f81979349d81e719eee49acb36c6a7a81c3a152fd8f14882f0dc3b8588a4。最终D2 source-after在iam-stage-4-d2-fixes，root核验见.local/iam-stage-4-d2-fix-root-handoff-check.json；生产源与原D2相同，仅两测试修复。实际D2当前API见iam-stage-4-d2-report.md及common源码。原Task4原文在iam-stage-4-implementation-brief.md，原D3/E资源矩阵与root正式裁决在iam-stage-4-d3-root-handoffs.md（末节优先）。本组件不计完整D3/R3，不取代后续真实gateway入口、E受理/材料及六服务迁移；不开放半套IAM。

唯一工作根G:/207/项目/算法重组工具/代码/agent-platform；不读旧工程/凭据。v1.2/四契约/原模块与固定技术版本不改，PG唯一权威，跨服务HTTP，注释解释查询资源版本与主资源版本的区别。无需新metadata JWT/receipt、生产IAM服务或产品页面步骤。

## 精确语义

- 新action app.published.metadata.read；target app；caller仅gateway；GET /internal/apps/{code}/published-metadata。首组件仅portal来源正例；integrationAllowed=false，集成实际app绑定投影在后续真实入口落地再明确打开，不能只信path/header或gatewayActor。workload与DELEGATED来源不得作首入口查询。
- 新kind metadata-selector-v1。descriptor=规范对象 {schema:app-published-selector.v1, tenant_id:已验tenant, app_code:已校验code, selector:PUBLISHED, projection:app-published-metadata.v1}。id为无歧义canonical tuple编码，versionDigest=descriptor规范SHA；v1是查询语义版本，不是假的app/集合revision。query kind与该action/target双向有限绑定，其他payload action即使被fixture故意误签此kind也在普通接收守卫前拒绝。
- 新查询Grant由原sourceToken正常C.exchange产生，只做受限查询。主Grant基于同原sourceToken明确再次正常exchange，从开始绑定真实app-version(code@versionNo, stored candidate SHA)。它与queryGrant不同，不升级/forward/resume换根、不持久queryGrant作run授权，不延长原认证/已开始的主operation。
- 查询不授payload许可。真正主域仍独立核真实主体/当前权限/域归属/PG版本后才payload；已知真实版本的合法主请求不强制先消费一次lookup，故无需receipt。

## 本次最小写入范围

1. common一个纯MetadataSelectors或同等小helper，确定性构造published selector、精确action-kind用途约束；ServiceAuthorization仅加该新kind用途检查，不放松D1/D2接缝；agent-service-authority.json增加唯一精确delegated条目及相关清单/语义测试。原158+policy的已登记集合比较继续完整，新增实际普通入口准确纳入，不仅改计数。
2. app api/application/domain内一个published-metadata controller、小service/DTO；必要最窄装配。实际SQL只读application id/code/status/current pointer与app_version id/version_no/存储candidate_sha256，按verified tenant+code限定。无个人app owner列则不虚构owner。DISABLED/ARCHIVED/未发布保留409，未知404。不得调用AppService.get/published/publishedInputs或DraftMetadataService.get：它们先读draft/snapshot/config等payload。
3. gateway一个小的published metadata客户端/受理资源helper，正常C.exchange后只向固定受信app端点发送目标service ticket，校验严格4字段appCode/versionId/versionNo/versionSha256，返回真实app-version root以供后续主exchange。原token仅送authority；不传成app Authorization。固定端点/TLS身份、禁redirect/任意URL/跨主体缓存，响应限字节/期限与实际I/O关闭可观察，参考已有严格transport机制但绝不把authority凭据送业务域。
4. 新有限测试：common selector+用途守卫、app真实Spring HTTP/PG metadata、gateway客户端对真实app metadata HTTP的组件链（common testjar可仅test scope复用，gateway POM允许该依赖；可最小公开已有有限LoopbackAuthority的portal测试入口，保留D2认证/上限修复与默认行为，不复制测试authority）。不改实际GatewayController/IntegrationAccess/六服务模式/静态出票统一/前端，本组件不声称这些已迁移。它们紧随本组件与E1继续。

原AppService payload方法的全面resource守卫属于下一段实际接入；本组件新增kind必须经通用用途检查，不能给旧方法新增一种宽通行能力。所有iam生产入口仍在原startup闭锁，组件测试使用明确isolated context真实controller/PG/ServiceSecurity+globalMappedInterceptor+runtimecoverage，不能漏守卫来跑正例。

## 必验与不夸大边界

- 先保存真正行为红：合法selector查询原不存在/不匹配（仅404路由缺失不算全部验收红），metadata kind误用payload或descriptor伪造不可放行；新增最小可编译接缝后以实际缺少用途/归属/DTO限制产生具名断言红。缺类/编译失败不算行为红。
- 正确portal query→实际app窄SQL→严格metadata，PG query1/payload0；原source与下游header不相等，正确service target/aud/jti；缺上下文、错caller/target/action/method/tenant/selector/code，普通workload/portal直连/integration/DELEGATED错域均0业务读取。controller用实际path与verified tenant重建selector，不能对JWT自身字段自比。
- 即使fixture签metadata kind的已有payload action票，真实普通接收guard拒绝、业务handler/payload0；反方向payload kind调用metadata也拒绝。跨app/非法code/unknown参数/字段类型/多余DTO/异常大响应/重定向拒绝，样本有限一次，无耗尽测试。
- 两个Grant用途分离：query Grant换实际root拒绝，主Grant明确源同原认证；在查询后推进source到期或当前权限收窄，第二exchange/主当前检查拒绝，不自动重认证。主payload合法闭环/当前published指针重核与run受理，待E1/D3真实资源守卫，不以本组件签收。
- 测试专用新PG候选agent_platform_iam_metadata_v12，只迁移现有app100–104，不改D2专用库与main；无新增生产migration需要。凭据沿root受控runner注入，不打印.env/密码；JWT/master/vault临时隔离。
- 聚焦后一次完整common（新生产语义/目录），app/gateway新具名测试及直接影响覆盖，旧mode未迁移业务测试准确列未跑；不跑七reactor/主服务/package。每轮XML/rawlog/sourceSHA立即归档，不拼轮次，不重复同码绿轮。

报告拟docs/evidence/iam-stage-4-d3a0-report.md及同前缀目录；baseline/frozen完整patch实际apply字节、source/target/保护SHA、真红绿命令输出、实际开源源码阅读、自审疑点，归还source/target后独立双审。仅此D3a0组件由新作者按本稿实施；D3其余源/配置/门户/持久材料未纳入本段，全部后续验收保持。

## root补充的组件联测方式与有限读取界限

禁止通过反射/classloader在app测试进程加载gateway target/classes来规避跨服务依赖边界。app/gateway各自JUnit测本域与HTTP客户端；跨模块真实组件链用独立、受控、顺序smoke runner，在编译完成后启动actual app isolated Spring/controller/PG/guard/coverage fixture与使用真实gateway客户端的测试main，跨JVM HTTP通信。允许必要的两个src/test fixture main与本阶段evidence runner，不加业务JAR/POM依赖；各进程只加载本域class、common和已解析库。可CLI生成本新工程Maven dependency classpath，不改父POM/生产启动；不并行Maven/写同target，不package/启停主服务，缺前置条件不能skip计绿。smoke另行记账，finally只关闭本次自身子进程/端点。

认可客户端采用Java HttpClient固定origin与生产HTTPS/hostname验证，显式test loopback例外、禁止redirect；4KiB响应上限和2s完整body期限/真实cancel属于本组件有限安全读取界限，不是生产性能SLA。按实际既有transport机制简洁实现，不向app发送authority凭据或原source token。公共有限fixture可最窄公开portal(long ttl)及advanceClock(Duration)已有能力，以验证source到期；仅测试，不新增身份/无界预算，保留默认C与D2修复。

root补充测试hook裁决：允许有限LoopbackAuthority测试源码增加testClientSecret(service)，仅app/gateway两值，其他拒绝，复用既有测试凭据。跨JVM fixture私有stdin传递，不能放命令行、readyfile、日志、报告或异常文本；每域仍有独立client身份，不共享万能调用者，不新增配置record或生产服务/SecretVault逻辑。

root补充受控启动裁决：既有 .tools/run-iam-module-tests.mjs 只启动 Maven tests，不另复制 .env 读取逻辑。允许极小 gateway JUnit launcher（单独 stage）调用本域 smoke main，再以私有 stdin 启动 app fixture main。launcher 只负责有界等待、收集结果、核对退出码/关键计数及 finally 清理，不包含业务实现或假响应。报告必须分开列“Maven launcher 1 项”和独立 smoke 的实际检查，不把内部检查膨胀成 JUnit 用例数。缺前置条件、无结果或子进程失败都应失败，禁止 skip/吞异常；仍保持两个独立 classpath、凭据仅私有 stdin、PG 沿受控环境继承。
