# EX-04 独立实质终审

2026-10-29（Asia/Shanghai），审阅者 `/root/ex04_test_gate`。独立核八源最终身份、原始 XML/退出码/方法与原因、PG 来源及后置范围；未参与产品或测试编写，未执行产品命令、Maven/Java/PG/HTTP，未读环境/凭据，未改源码、index 或旧证据，未使用 Superpower。本轮仅新增本报告。

**结论：接受 EX-04 八源实现与本轮限定环境下的验收证据，实质范围可关闭并进入精确 Git 门收口。两个完整模块仍有原存量非通过；本结论不等于全模块全绿、clean 构建、真实 OR 业务分页或 J4，也不签收尚未进行的索引/提交。**

## 最终八源身份

以下当前原始字节均逐项匹配最终两 Full 的 source-before；完整路径见相应 source 清单。

| 模块/文件 | SHA256 |
| --- | --- |
| skill-registry / OntologyManifestImports.java | `0538830d9c0e511d868e4c6b4ba21582a0600a5fc3c9240062affc3008e9cc8d` |
| skill-registry / SkillRules.java | `221bfa61cc137d94b2d9c70c02aec1b5fd1c5052a19be39d212cb305eebe98bd` |
| skill-registry / SkillCatalog.java | `c9c3a0b6e69a76cd49939f639ac0b7cf014e67fedb9dd86311c807199abc47fd` |
| engine-runtime / ToolOutputPolicy.java | `5d2596babb01a047fc24f8db4e3534eae22bc4e2820f45231299a2d3f91ffebb` |
| skill-registry / OntologyManifestImportsTest.java | `4b2573fa159e250b00e6dd19aaf990299abb6c204f1e880285108b9b9f533bc4` |
| skill-registry / OntologyProjectionImportTest.java | `ef0d3b60c0846d79744b5b18c9b57538d2c0c348aac6599e15cdcb3e2bf99b52` |
| engine-runtime / ToolOutputPolicyTest.java | `666c61bb5e46290be05921a6f6bdc1ff171bb509fe206adf247b0ea20b8a1b51` |
| engine-runtime / RunToolProfileTest.java | `53d4c07f97011f0f4a364044419fdd0375622353751755f67b8ff2db854c6afc` |

生产职责与限制见[技能候选审阅](skill-candidate-review.md)、[引擎候选审阅](engine-candidate-review.md)。完整回归发现的旧手工保存缺键问题已按[最小修复独审](legacy-save-repair-review.md)补 Map guard；严格 object/normalize、分页/风险与原断言未放宽，三旧 Test 未修改。原22个引擎方法与 helper 全文保留，四测试最终身份未再改变。

## 实际结果与保留原件

| collector/runs 下轮次 | 实际报告 | 原生/ignore | 结论 |
| --- | --- | --- | --- |
| red-skill-20261029-101922-472 | 33＝18P/15F | 1/false | 15个预定语义红精确吻合 |
| red-engine-20261029-102649-693 | 47＝36P/11F | 1/false | 11个预定语义红精确吻合 |
| green-skill-legacy-save-20261029-1110 | 33P | 0/false | 修复后定向绿色 |
| green-engine-20261029-104408-513 | 47P | 0/false | 最终 PG 固定数据与实际冻结路径通过 |
| or-target-20261029-104759-772 | 9P | 0/false | 既有生成/formalize 透传复验 |
| full-skill-20261029-1102 | 689＝565P/6F/114E/4S | 0/true | 新8E的失败轮次保留，不记通过 |
| full-skill-legacy-save-20261029-1112 | 689＝573P/6F/106E/4旧S，71XML | 0/true | 方法及有界原因门接受 |
| full-engine-20261029-1124 | 932＝817P/35F/80E，93XML | 0/true | 方法及下述精确表示差异解释接受 |

已独立从归档 XML 重算最终两 Full，逐项等于 methods.csv 和冻结预期；原674/907身份与状态差异为0，新增15/25全P。最终 skill150源与 engine202源前后原始清单各自完全相同，分别与同模块最终定向使用相同源集合。XML 摘要/长度/新鲜时间有效，旧两组语义红与1102的 XML 仍全部匹配各自原 manifest。

旧8E在最终 XML 恢复8P；SDK五方法、四安全 outcome 与零诊断独立重算成立。skill原4旧skip、engine原2个空 method 类级事件及14个无具名执行的源 Test 保留，未计作通过或全源执行。Full 的 native0/ignore仅是采集返回。成功采集阶段的collector0由ROOT原工具观察及控制流复核支持，归档没有另存独立原始 collector-exit 文件，未用派生摘要冒充原件。细节见[定向与入口复核](runtime-review.md)。

## 原因解释与 PG 来源

技能原116项非通过的状态/type/caused-by/首项目帧全部保留：115项原始文本相同；唯一迁移案例只涉及精确2个UUID与2个时间戳，message/detail各计数2，范围外全文相同。未对其它原因泛化归一化。

引擎原115项原因结构同样0差，92项原文完全相同。原严格文本门仍为false，原 `full-reason-comparison.json`（`b89b6297…`）及方法 summary（`33b99ae9…`）未覆写。另存[23项精确名单](collector/runs/full-engine-20261029-1124/engine-representation-allowlist.csv)及[有界表示证明](collector/runs/full-engine-20261029-1124/engine-bounded-representation-proof.json)，后者SHA为 `0bb515517d460d0012dd5118496fedb4eaf1584f00a1013dbdbb30d85450afc8`。

我逐项核名单、原 XML/raw hash pair 与438个捕获位置，0不符。16个Spring案例每字段严格12或13个带具体类名的`@hex`表示值；7个Metadata案例的`connection=`值次数为4/3/4/4/4/2/4，来源明确是未改测试源码的 `System.identityHashCode(connection)`。每case联合message/detail的前后双向映射唯一、相同/不同绑定模式一致；7个SQL案例各只有一个唯一连接值，其重复关系不变。仅实际变化标识被映射，类/字段邻接、顺序、SQL/隔离/只读/自动提交、堆栈及其它文本逐字符保持。接受仅绑定本轮、这23身份及原始摘要对的派生解释；不称原始hash相等，不形成未来运行规则，不放宽JUnit断言。

PG链见[来源桥复核](pg-bridge-review.md)。测试构造formal manifest进入真实AP PG保存/编辑/发布，API_KEY/来源及实际本地HTTP头、四类混合批零写入均到达；模型及engine权威接口仍为明示替身。初始真实PG两键为137字节/SHA `0e99aae3…`；修复后green两键SHA `14e0418b…`只因根键序不同，解析逐字段完全相同；最终skill Full又实际产出原 `0e99aae3…`。两engine consumer仍逐字节引用原真实产出并默认校验摘要，无需改断言，实际prepareSnapshot路径已运行。它不代替真实OR业务offset/limit消费或EX07 J4。

## 数据与构建范围

独立复核[14库范围](engine-full-pg-scope.md)、[既有V222限定](legacy-runtime-context-review.md)及ROOT实际后置归档：固定14库前后name/owner无变化；timeout库集合7→8，仅原测试新建一个精确前缀库且owner为agent_v12，旧每行catalog身份未变，未据此声称所有业务数据未变化。原fixture清理/故障路径保持，collector无DDL。

V222仅现有target残留，286字节/SHA `419c753c652b326dbd59de40409f080a16f26b98787a7f8cf870b6ecf2859a90`前后未变，源码无此文件。独立逐UTF8行CRC32得701700386，与归档历史及11库精确SQL/222/owner/success记录相符；V200/V201控制也匹配。未clean/repair/补源码迁移或改历史，不能称clean构建验证；遗留一致性仍归EX27/Y4。

TestInputHttp专库前后仍schema/history缺失，其原9E为同一decoder BeanDefinitionOverrideException，未预迁移、补属性或冒作HTTP通过。PS5 BOM及前置拒绝记录、1102真实失败原件均保留，没有倒改为首次即通过。

本报告签收本卡实现、定向与上述现有环境相对回归；未签未来Git索引、提交或外部发布。后续精确Git门以本表八源和已保留证据为准。
