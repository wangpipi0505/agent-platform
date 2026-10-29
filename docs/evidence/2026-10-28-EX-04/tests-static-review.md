# EX-04 测试候选独立静审

2026-10-29（Asia/Shanghai），审阅者 `/root/ex04_test_gate`。依据 AP `AGENTS.md`、三份主入口的 EX-04 条款及 `preflight-execution-review.md`，独立阅读四个候选测试、引擎 before/candidate 原件和技能侧红回执。本轮只新增本文件；未改产品或测试，未运行 Java/Maven/PG/HTTP、服务、仓库脚本，未读环境文件或凭据，未操作 Git index/提交，未使用 Superpower。

**结论：测试候选可接受，未发现阻断既定红→最小修复流程的问题。技能侧真实红回执与静态预期吻合。引擎 47 份报告目前仅完成静态身份核对，不能记作已运行或通过；PG 读回契约向引擎的真实来源衔接仍是绿后必做项。此结论不签收生产实现或整卡关闭。**

## 候选身份与数量

以下 SHA256 为本次实际读取的原始测试文件，技能侧同时与 `red-skill-20261029-101922-472/source-before.json` 一致。

| 测试文件 | SHA256 |
| --- | --- |
| `OntologyManifestImportsTest.java` | `4b2573fa159e250b00e6dd19aaf990299abb6c204f1e880285108b9b9f533bc4` |
| `OntologyProjectionImportTest.java` | `ef0d3b60c0846d79744b5b18c9b57538d2c0c348aac6599e15cdcb3e2bf99b52` |
| `ToolOutputPolicyTest.java` | `0989dce43b6033082ac7d15b807e475b6941426adc2441c2a335d900c5e9e741` |
| `RunToolProfileTest.java` | `4018d136e9b9e7b8bf3eef66a32ce2edecba565eeebc68b9b6f0f40c1461c1c0` |

- 技能侧为原 Importer 18 方法＋新 Projection 15 方法，共 33。原风险字段“不映射”断言改为精确映射 `READ_ONLY`，符合本卡新增规格；没有通过删断言恢复旧行为。旧类该方法预计 1F；新类无声明兼容方法预计 1P，因此总计 18P/15F，不能表述为原 18 全绿＋新增 15 全红。
- 引擎侧原 Policy 10 方法、Profile 12 方法。通过只读字符串反撤六条新 import 和新增测试块，两个文件均可与 `engine/tests-prepare-20261029-095033-701644/*Test.before.java` 逐字符完全还原；原 22 方法全文保留。
- 新增 Policy 为 3 个普通方法＋21 个参数化实例，Profile 为 1 个普通方法，共新增 25 份报告。Policy 34＋Profile 13＝47。这里的“47”是预期测试实例数，不是 47 个源码方法。

## 技能测试的实质覆盖

1. 正式来源、API_KEY 与 exposure/显式风险/OFFSET 精确映射并列断言；OFFSET 参数保留 `startAt/takeRows`，且故意令 `maxRows=2 < maxLimit=5`，可防止引入未授权排序限制或把 offset 重命名为 page。
2. 来源分页覆盖每键缺失/null、0/负数、小数（含 1.0）、字符串、溢出、布尔、空参数名/非字符串和未知键；规范分页覆盖 null/空对象/非对象、必需键、未知 mode、混用 page/offset、OFFSET response 及 PAGE 非法响应键。错误须为 400、消息含分页路径；无效导入同时断言零 create。
3. 原 PAGE 缺 mode 形状、显式 PAGE、可选空 response、32 位正整数上界、无投影/分页缺键兼容均有锚点；不通过补默认响应路径或默认 mode 凑规范。
4. QUERY 冲突、MUTATION readonly 冲突、未知 exposure/风险和风险降低有拒绝锚点；MUTATION 两种 sideEffect 值逐一断言原值保留，且不合成审批/角色。旧手工 API 低风险和未知风险继续按原执行旗标推导。
5. MCP/SYNC、ALGORITHM/SUBMIT_QUERY 在无 endpoint/http_method 后定位未登记 `binding_ref`；非 HTTP 携 HTTP 字段先定位矛盾字段，未知类型和不支持组合定位对应字段。没有伪造名义绑定成功正例。
6. 混合批四类后项为分页、QUERY、风险降低和类型组合；mock 层在每轮清理调用历史后验证首 create 前拒绝。PG 层每轮在同一独立租户中核对前后 skill 数量，并断言 DRAFT 为零，不能只靠 create 内部 normalize 回滚。
7. 三个新 PG 方法均先查询既有 EX03 专库的 owner 与准确 marker，再允许 Flyway；不自动建库、重置或清未知数据。每个测试方法使用 UUID 租户，实际写入代码也使用 UUID。环境条件导致跳过时不满足本卡零新 skip 门。
8. 真实目录链覆盖创建→读回→省略字段保存→显式分页修改→发布→再读，检查摘要保留/变化、API_KEY/正式来源、显式 null 保存失败后原定义与摘要不变。只读高风险与 MUTATION 草稿缺 confirmRoles 的发布拒绝分别检查。HTTP 头由本地真实 HTTP 服务采集；模型和 engine 权威接口仍是明示的协议替身，不能称真实 OR 或 J4。

## 引擎断言与后续来源门

Policy 新增有效 OFFSET 测试使用真实超过阈值的输出，检查错误信封、精确四项声明信息、空 observed、无 response/page/nextOffset，并比较调用前后原始结果及 20 项数组不变。PAGE 显式 mode、旧缺 mode/可选 response、恰等 UTF-8 字节阈值同节点返回均独立保留。21 个非法分页实例在 1024 阈值与 4KiB 输出下检查防御读取返回 null、运行不附提示且仍产生原错误信封，避免存量低于默认阈值的测试误判。

Profile 新方法调用既有反射夹具中的实际 `RunService.prepareSnapshot`，读取只允许技能版本的 client stub，并比较 definition 整体、exposure/pagination、execution/guard/schema/digest/versionId；再把实际冻结出的 tool 交给实际 Policy。它证明实际冻结代码透传，不能扩大为真实跨服务运行。

当前 Profile 中的 `skip/take/37/7` 是明确标注的规范样例，尚非 PG 读回。绿后必须用 `EX04_PERSISTED_PROJECTION` 的实际安全两字段替换固定 fixture，保留原产出轮次、内容与 SHA 对照后重新定向；不能仅把另一份手工同形样例改名为跨模块衔接。

## 已有技能真实红的只读复核

读取 `collector/runs/red-skill-20261029-101922-472`：native exit 1、failureIgnore=false；独立解析 methods.csv 得 33＝18 passed＋15 failure，15 个 class/method/status/exception_type 与候选 expected-red.csv 精确一致，均为 `AssertionFailedError`。summary 的 redVectorGatePassed=true、allGreen=false、compileOrFixtureFailureNotTargetRed=false；前后两份 source 清单均为 150 项且逐项相同。红前冻结三生产 pin 与既有预审一致；ROOT 已在该轮之后开始获准产品修复，本文不把红前 pin 冒称当前工作区产品身份。

raw.log 真实记录 `EX04_BATCH_COUNTS={before:0,after:2,drafts:2,rejected:false}`，直接暴露旧实现对第一类无效分页后项留下两行草稿的问题。该 PG 方法在首次失败时停止，因此本轮实际红不能声称四类 PG 后项都分别执行到断言；四类 mock/PG 循环在绿色运行中都须完整到达。其余红同样只证明到达各方法的首个失败断言，不替代绿后全部断言。

## 尚待独立复核

引擎实际红、产品四文件最小实现、两侧定向绿色、PG 安全契约衔接、OR 生成/正式化透传以及完整 skill/engine 方法与原因门仍须按唯一实际窗口顺序执行并另审。完整回归保留原 4 个 JDK8 skip、engine 两类级事件与 14 个未执行源 Test 的真实基线，要求新增非通过/坏类/skip/未执行为零；不能把本报告或 collector 退出码当 all-green。源代码中未由本次方法逐项单列的组合边界，仍要在产品审阅中对照主入口核实，不据循环方法数量声称穷举全部组合。
