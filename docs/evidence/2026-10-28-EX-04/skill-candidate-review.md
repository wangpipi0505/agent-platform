# EX-04 三技能产品候选独立窄审

2026-10-29（Asia/Shanghai），审阅者 `/root/ex04_test_gate`。沿用已读三份主入口 EX-04 与独立预审，以当前工作区相对红前基线的三个产品 Java 差异及其必要上下文为审阅对象。本轮只新增本文件；未改产品、测试或旧证据，未运行 Java/Maven/PG/HTTP、环境或仓库脚本，未读环境/凭据，未操作 index/提交，未使用 Superpower。测试仍按 `tests-static-review.md` 的冻结候选处理；本轮不审 engine 产品。

**结论：未发现阻断问题，准入一次 green-skill 定向验证，随后仍须执行完整 skill 回归与方法/原因门。此处为静态源码候选准入，不是编译通过、测试绿色、最终实现签收或整卡关闭。**

## 本次候选身份

| 产品源 | SHA256 |
| --- | --- |
| `OntologyManifestImports.java` | `0538830d9c0e511d868e4c6b4ba21582a0600a5fc3c9240062affc3008e9cc8d` |
| `SkillRules.java` | `221bfa61cc137d94b2d9c70c02aec1b5fd1c5052a19be39d212cb305eebe98bd` |
| `SkillCatalog.java` | `f4557381e94ab15b94e42bccee7a1f7065da71d639ffc3da2a028e5c6241754e` |

这些是读取时的工作区原始字节身份；之后发生变化须以新的实际 source manifest 复核。既有红技能轮次 `red-skill-20261029-101922-472` 为 33＝18P/15F/0E/0S，证明红先于本候选，不能把红前生产 pin 作为当前实现身份。

## 实质核对

- **全批零写入预检**：Importer `:76–77` 在全部 `toSkillBody` 和重复编码检查完成后、任何 `catalog.create` 之前遍历完整 `catalog.normalize`。新增转换与 `SkillRules.projection` 均只读取输入、构造规范副本；本体入口固定 HTTP/SYNC，预检不会进入目录的 CALLBACK 凭据解析或异步执行分支。这里没有增加数据库写入或批次事务。create 仍独立重验，真正 create 阶段的唯一冲突等保持既有部分创建边界，不扩大为任意失败全批回滚。
- **实际类型权威与源错误字段**：Importer `:92` 委托当前 Catalog 实例的 registry 校验类型/组合；没有另建默认 registry 或硬编码第二套 MCP/ALGORITHM 组合。非 HTTP 先拒绝出现的 endpoint/http_method，再核调用组合；无实际名义绑定目录时统一以 binding_ref 拒绝。HTTP 非 SYNC 随后的显式 require 仍定位 invocation_kind，已注册异步组合不会仅凭 registry 枚举被接受。分页和风险的源错误分别映回 exposure.pagination 的四个源键及 risk_level/exec_attrs，不泄漏凭据值。
- **新结构严格且不静默丢弃**：来源 exposure 仅容许 kind 与可选 pagination，四个来源分页键必须精确齐全。共享规则严格限定 exposure 规范键、模式、request 坐标和 response 键，null/空分页、未知键与 page/offset 混用无法落库；OFFSET 四项必需，PAGE 缺 mode 不补键、response/maxPageSize 仍可选且空 response 合法。上限通过 JsonNode 的 integral/canConvertToInt/正值检查，拒绝 1.0、字符串和溢出，不使用 longValue 截断；没有引入 maxRows≥maxPageSize、参数名必须不同或推测响应路径的限制。
- **读写与风险边界**：QUERY 真值条件为 readonly=true 且 sideEffect=false；MUTATION 必须 readonly=false，sideEffect 不被改写。来源 ONTOLOGY 或新 exposure 声明才启用风险枚举/降级拒绝；旧无来源、无 exposure 的手工风险推导仍沿 Catalog 既有表达式。显式 CONFIRM_REQUIRED 在只读时保留；缺省由原执行旗标推导，不合成角色、审批或协议。
- **保存兼容**：Catalog `:357–371` 在既有行锁/保存事务内按 containsKey 补回省略的 exposure/pagination，显式 null 不被 putIfAbsent 吞掉，交由共享规则拒绝。只有原来源为 ONTOLOGY 或原定义已带 exposure 时，才补回被省略的 CONFIRM_REQUIRED；没有顺手改变旧普通手工技能的风险省略行为。显式新分页/风险值仍走完整 normalize 与原 sha/semanticDigest 计算，不修改已发布版本。
- **最小性与编译静查**：实现集中在三处既有职责，新增一个共享纯入口及小型字段辅助函数、一个 Catalog registry 委托；没有新执行器、目录、迁移、依赖或框架。Diff 中其它变化为格式化，不见额外行为。Java21 的否定 instanceof 模式变量、短路条件、泛型 Map 通配与 Map.of 使用未发现明显类型/作用域问题；此判断不代替真实编译。

## 实际验收仍须到达

一次 green-skill 应在冻结测试不放宽的前提下到达 33P/0F/0E/0S，尤其检查四类混合批的 mock 零 create 与 PG 四轮行数输出、创建→读回→省略保存→显式改分页→发布链、显式 null 回滚和高风险确认角色门。随后核完整 skill 的逐方法/原因差异，不用 native/collector 成功文案替代测试结果。

PG 实际读回两字段与 engine fixture 的来源/内容/SHA 衔接、engine 真实红→绿和完整回归、OR 透传复验仍按主入口分列。本次静审不对这些尚未读取的实际证据作通过断言。
