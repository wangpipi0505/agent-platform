# EX-01 独立只读评审

**最终结论：EX-01 冻结候选可接受；待本地提交并把实际提交号与回滚方式回填统一台账 DoD⑤后，可转完成。** 不称模块全绿，不外推完整业务运行或 J9。前置实质核验与本次有限终评共同构成 DoD④；终评没有重跑已验证阶段。

- 评审对象：EX-01 服务级环境配置与有效开关；不代验完整联合 J1～J9，不关闭 EX-35/EX-27 的 SSE 问题。
- 作者：`/root/ex01_service_environment`。
- 审阅者：独立代理 `/root/ex02_independent_review`；未参与本卡实现、采集器修改或真实运行，只读源码/公开证据并新增本评审文件；未使用 superpower。
- 前置候选：当时 AP HEAD `0cc967278007343bd5ab09faf9953d86cd573bf8`，五文件逐项 SHA256 已核对；该时点记录保留。
- 最终候选：parent AP `41b62e3f324ec8ef61630a9628d4664f2daae8c9`；[index-candidate.json](index-candidate.json) SHA256 `8D31A05D377B606FA4C4FD9E94E79BE5306C432EADC80A29A5A436233C299F9B`，冻结 tree `317078504080e7b876b740df2da6662ad50e5055`，精确66文件（五产品/测试＋本卡证据61）。审阅者逐项核对66个 working SHA256/staged Git blob与index文件集合，差异0、越界文件0；五文件仍为下表已核指纹。清单与本review随后附入，其他卡候选不属于本卡。

| 文件 | 已核 SHA256 |
| --- | --- |
| scripts/start-backend.ps1 | `542CC2BB88FADF93B7EF6C847B5FC67A42D0F03D27AB29D672DF959E8980530F` |
| scripts/Test-ServiceEnvironment.ps1 | `0DCDC836BED01060D27AC814162D0CEDA278C2A122573E062386B2CCE9723FCD` |
| .env.example | `2722AA085361FF7D256250127ADACE8A071E6906CD5AFC5A5B071AD4B87860FC` |
| engine-runtime/src/main/resources/application.yml | `562054834CC0B726BA445D3035EF05D908397CC121B892254EFC33C034E1664C` |
| platform-gateway/src/main/resources/application.yml | `3EA5DB6975B4278F0375A37D5E7F4144B25C1EEAF7E038227CBFB2897171AD06` |

- 证据：本卡 [README](README.md)、PS 5.1 隔离/静态记录、74项 Java 定向、skill/engine模块方法 CSV 与对照、正式 legacy/PROCESS 轮、[局部真实运行](runs/live-partial-20261028-225338-553/run.json)、[engine-only 补验](runs/live-engine-20261028-232216-804/run.json)及其 execution.json、EX-36 新包元数据、[engine-supplement-static.json](engine-supplement-static.json)。
- 已核查：两仓 AGENTS 与三主入口 EX-01/§14.4；参数/服务白名单、绝对普通文件/本仓忽略目录/重解析点拒绝、严格服务文件解析、固定服务身份、服务私有 key 所有权/引用/绑定表过滤、成功与失败的父环境恢复、旧无文件兼容、日志仅列键名；YAML 默认、共享 SecretVault/服务票据/PROCESS/配额/限流实际消费点；公开记录与探针分层、失败/补采诚实性与来源指纹。
- 未覆盖：审阅者未执行 PS 子进程、Maven、Java、PG、HTTP或操作运行进程；未读真实 `.env`、服务环境文件、OR配置或私有 fixture，未读 raw/XML properties。以公开元数据/CSV及源码独立判断实际证据。旧运行进程拒绝复用、链接路径拒绝只有静态核查；没有完整业务 publish/run/J9，没有真实外部 IAM provider 验收。

私有集合按已确认范围理解：收集所有服务文件中定义、env引用、绑定声明的 `AGENT_SECRET_*` key；对未获该 key 的服务同时去除其值、指向它的继承引用及绑定项。没有在任何服务文件声明的全局变量继续旧继承。46项实际 PS5.1 微测通过，既测试成功/子进程启动失败的局部 finally，也直接检查生产 outer finally 对全局覆盖和原本不存在变量的恢复。日志与异常只记录键名/固定信息，不输出配置行或绑定值。无服务文件时只额外设置固定 serviceid，保留旧单文件加载语义。

真实证据按以下层次认可：

- legacy/PROCESS：实际新空库先建立；legacy 不传服务文件，以临时标准 datasource 覆盖并由实际 skill PID 的 JMX 核对有效 URL。正式 JAR 的 PROCESS=true/空白名单退出1并匹配生产构造拒绝；随后清空标准 datasource/JSON 覆盖，再验证服务文件中的 AGENT_DB_URL。原报告器失败与负例完整原始日志未保留的限制仍保留，不补造首轮成功。
- app/skill/gateway：实际登记 PID/JAR 健康200、JMX配置及密钥存在/外服务密钥缺席得到验证。独立 Spring 上下文从当次 app JAR/YAML 装配 ReleasePins，真实 OR binder 首次 true、重复 false，同 bindingId；readonly 写入403。gateway 的17秒/上限2及true/0由实际PID配置核对，加同JAR独立配置 Bean 的第三次拒绝/32次放行证明。后者不是完整业务 HTTP 鉴权链。
- engine：EX-36 新包的 engine/common 154份源文件集合与每项 SHA、磁盘 engine JAR `76AB67C3A72CD1931E2DC64E053CAE8A11A86A850CFA9DA5EB95FEE1132B419C`均由审阅者独立重算一致，包记录exit0。PID50360/6276分别通过健康/JMX9属性检查，readonly引用和值存在、binder值缺席，父原值/引用恢复；窗口97、三级301/211/101及true/三个0实际生效。读取 OR binding/context-pack由同一新JAR类路径的**独立 Spring 探针**完成，不是在该实际PID中触发业务 run 或 J9。true/0不限语义关联实际PG定向中已有零阈值方法PASS，不冒称新业务准入已执行。
- AUTHORITY：正式skill进程验证属性为AUTHORITY；缺provider返回 `SERVICE_TICKET_PROVIDER_MISSING` 且不回落，由本次 `ServiceTicketsTest#providerMissingIsAssemblyError` PASS及未修改的生产路径支撑，不冒称已对接真实IAM。

实际记录的 `.env` 前后摘要一致、仅停止本卡登记进程、日志/公开证据扫描无两把授权key值；审阅者没有读取这些真实值，未将作者的精确值扫描解释为穷尽所有秘密种类的独立保证。Jar解包目录按Jar摘要分开，避免engine新包复用旧类路径；JMX与独立CLI的来源、执行层次分离准确。

| 问题ID/级别 | 场景/定位 | 问题与影响 | 当前处理与复核状态 |
| --- | --- | --- | --- |
| PRE-01 / P2 运行隔离 | 早期 run-live 先legacy后建库的源码顺序 | skill有启动清理、outbox、产物回收路径，不能据无engine就称旧库启动无副作用 | 已改为先新库，实际legacy JMX核有效datasource；标准覆盖在正向前清除。该问题关闭；没有据草案推断发生旧库副作用 |
| PRE-02 / P2 统计口径 | engine原完整轮 comparison.json | 906报告项含904具名方法＋2个method为空的原类级事件，collectionComplete=false；“906方法”不能外推为完整方法采集 | 已由 engine-report-scope-correction.json 与最终README明确，含原14个源码@Test未执行；EX-36的907=905＋同2事件也如实列明。关闭 |
| GATE-01 / 共享回归门 | gateway共享完整模块 | 旧EX-01轮与EX-35 frozen状态相同，但对EX-00仍有新的SSE失败，原gate=false不能豁免或promote基线 | 最终EX-27共享轮27类/288项，回归门true、native1、15原非通过原因不变；独立复核见下。门已补齐，历史红保留 |
| GATE-02 / 最终候选 | EX-01 index/最终文件清单 | 前置阶段仅有工作区五文件指纹 | 现已核66文件精确index及范围；manifest/review随后附入，DoD⑤仍待实际提交。冻结门关闭 |
| PRE-03 / P2 原始证据字节 | red-isolation-test.txt与精确.gitattributes | 初次收尾暂存blob为509字节，工作原件516字节；新增-text属性后原index仍是旧归一化blob，不能称原字节入Git | root仅对该路径重新归一暂存，未改工作文件；最终两端均516字节、同blob。属性仅作用该原始stdout，产品格式规则未放宽。关闭 |

- 逐卡结论：**EX-01 冻结候选可接受；DoD⑤实际提交/台账回填后可转完成。** 未发现须产品回改的开放问题；既有模块失败、engine未执行源用例及真实业务链路限制保留。
- 结论依据：DoD①满足：新生产隔离函数缺失的PS真红→PS46实际通过；Java定向46＋22＋6=74PASS，正式配置/隔离/开关证据如上。DoD②满足本卡差异门：skill664报告项与EX-00身份/状态一致，原native exit丢失仍为unknown，补采未伪填0；engine906报告项独立比较差异0，保留两个原类级事件与14个未执行源用例；最终gateway共享门通过但仍有15原非通过，不称模块全绿。DoD③满足：最终README、command-index、共享结果与统计更正文件给出命令/数字/限制。DoD④由前置与本次有限终评满足；DoD⑤待实际提交及台账回填，未伪填未来SHA。
- 作者处置：本页未替作者修改 README、采集器或任何实现。真实环境文件及共享凭据保持原归属；回滚只恢复本卡精确修改与新增测试脚本，不重置协作者文件，不擅自撤共享key或删除旧数据。
- 审阅者最终复核：2026-10-29（Asia/Shanghai）只核新增收口材料、共享gateway证据及精确index，不重跑阶段、不改作者材料或index。结论绑定上列最终指纹；相关源或证据变化须复核受影响部分。

## 有限终评核对记录

[gateway-shared-regression.json](gateway-shared-regression.json) 引用EX-27 `safety-module-20261028-233458-012`。其7份公开sourceRecords摘要全部独立匹配；288个方法报告独立计数为273PASS/7FAIL/8ERROR；15份存量非通过原因记录的same均为true。原254、恢复Proxy原30、新Proxy3、新SSE1齐，Proxy33PASS、SSE38PASS、49个原生子进程全0，新非通过/坏类/缺失/重复均0，native Maven exit1与allTestsPassed=false保留。TLS原FAIL→PASS只作观测，不归为EX-01修复；原红轮没有被覆盖或promote成新基线。五文件中的gateway YAML指纹与该共享轮一致。

最新README准确区分engine906报告项（904具名＋2类级事件）和EX-36新907项（905具名＋同2事件），均保留collectionComplete=false与14个未执行源码测试。实际PID配置与独立Spring新Jar类路径读取OR的层次没有合并成“PID内J9”。本卡skill664的历史回归仍按其实际候选/时点成立；EX-03后续SDK GET_BODY问题不由配置卡豁免或算作已修复。

原始红stdout的精确属性为本卡目录中单行 `red-isolation-test.txt -text -whitespace`。独立check-attr确认只该文件text/whitespace为unset，产品脚本/YAML仍text:auto、未放宽whitespace。初始错误暂存blob `042eab47879f847e6ae3b3282d591f9f8a152b74`已在rawPreservationProof保留；最终工作原字节与staged blob均为 `0d5a1aab6df99d5cdd1afcc7ca85e7a7e8bbfe6e`、516字节。全部66工作SHA与最终manifest匹配，cached diff --check退出0；未为消除空白告警改写原始证据。

候选敏感扫描记录为两把授权literal及PEM/JWT形态0命中，范围与限制明确；审阅者未读取真实值，不声称对所有秘密的穷尽保证。新增manifest/review只含公开指纹、路径与判断，不包含私有环境或凭据。
