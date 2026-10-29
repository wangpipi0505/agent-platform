# EX-04 定向实际回执与 Full 入口独立复核

2026-10-29（Asia/Shanghai），审阅者 `/root/ex04_test_gate`。只读两组定向归档、XML、source 清单和 Full 入口；解析 XML/CSV、计算文件摘要，并显式使用 Windows PowerShell 5.1 做仅 AST 的文件解码核对。本轮仅新增本文件，未执行产品命令、Maven/Java/PG/HTTP或任何仓库入口，未读取环境文件/凭据，未改产品、测试、index 或旧件，未使用 Superpower。

**结论：实际 green-engine 47P 与 OR 9P 回执可接受；PG 固定数据桥使用正确 consumer 身份。Full 入口的 UTF-8 BOM 格式修复已独立核对，修正后可继续既定实际窗口。完整 skill689/engine932 与非通过原因门仍待 ROOT 运行后另审，本报告不预签这些结果或整卡关闭。**

## 实际定向结果

| 轮次 | 独立重算 XML | 原始 native-exit | source 前后 |
| --- | --- | --- | --- |
| `green-engine-20261029-104408-513` | Policy34＋Profile13＝47P，0F/0E/0S | 0，failureIgnore=false | 202 项完全一致 |
| `or-target-20261029-104759-772` | ToolBindingExposureTest 9P，0F/0E/0S | 0，failureIgnore=false | 18 项完全一致 |

两组 XML 文件的原始摘要、长度均与 xml-manifest 一致，mtime 都位于对应 native 开始/结束区间；逐 XML 派生的 class/method/status 与 methods.csv 完全相等。引擎 47 个完整四字段身份与冻结 expected-methods.csv（SHA `7066558b2c98ec680ca4dd3dbe54f1f9ea73c22bd4cb327343b048edca247e66`）逐项一致；OR 的九个方法与入口固定原九身份精确相等。raw.log 的实际 47/9 零失败汇总和 BUILD SUCCESS 也吻合，实际命令均无 failure.ignore、skip 或诊断覆盖。

归档没有单独保存原始 collector 进程退出码文件。ROOT 明确确认当时外层工具返回已观察 collector=0；本审阅可以独立复核 summary 门与入口退出控制流一致，不能把它们冒称为一份不存在的原始 collector-exit 文件。原始 native-exit 文件的 0 已直接读取，二者证据层次保留区别。

## 实际源与 PG 桥身份

| 轮次 | source-before.json SHA256 | source-after.json SHA256 |
| --- | --- | --- |
| green-engine | `658619edfbf993cb19e431808d0377dc07e9c2e7d2ddbdb42f87899f9a8f9958` | 同左 |
| OR target | `617f0745773c2e54f529878d98377994724f5ddfb3bf2f91cd9fd7b63436799f` | 同左 |

引擎实际 source-before 的 Policy 生产 SHA 为 `5d2596babb01a047fc24f8db4e3534eae22bc4e2820f45231299a2d3f91ffebb`，两 Test 分别为 `666c61bb5e46290be05921a6f6bdc1ff171bb509fe206adf247b0ea20b8a1b51`、`53d4c07f97011f0f4a364044419fdd0375622353751755f67b8ff2db854c6afc`，与既有源码/PG 桥独审一致。该轮冻结模块源清单 SHA `9153a7df0a55478bb87f98870f0c04388d82e2eef8123411d76cac96f76f92d9` 与准入一致；实际 command-end 的 HEAD/env/JAR 摘要也与冻结值相同。

实际 PG 固定数据来源仍是 green-skill 的 137 字节 JSON，SHA `0e99aae36146e3b35a06f9755e2532c1fed3202e095cd5b9cbc89efeedf8b2d9`、producer XML SHA `30220156caf1644beca88d4ef634511a333171d18df84b783ba0bdb4ddae0921`。此前已独立逐字核对两个 consumer，当前实际运行确实用了该最终源身份；默认执行的摘要断言和实际 prepareSnapshot 路径均所在通过方法内。engine 定向不实时连接 PG/业务 HTTP，不能把固定数据桥称为实时跨服务调用。

OR 轮次的 18 个源前后同一；Generator SHA `b19eb968f279544883413e7d568fee6b9419add68d41e38c07da62a97e6d0cfd`、Validator SHA `07389201b7b1e8497fe3582e6ef33ef05105d7ff09061168d0da5adc93f76d2e` 保持既有只读 pin。其入口使用本仓 O:/ 环境和 BASE 配置，选择纯 onto-contract 测试，无 -am/ignore；这里只签生成与 formalize 透传，仍不证明 OR 业务分页或 EX07 J4。

## Full 入口静态边界与最终格式身份

已与入口作者 `/root/algorithm_interrupt_readonly` 协调确认共享两文件封版、停写且没有其它 Full 实际入口替换。初版静读的逻辑如下：

- 以 skill674、engine907 原完整身份集合加获准新增15/25构造预期689/932，拒绝缺失、重名、重命名抵销和意外身份；先核同一 source/head/env/JAR 的定向绿色。
- 先核当窗只读 PG 准入、15 分钟有效期、已知实例/owner/库范围与原两 replay 库授权，collector 不创建/reset/清未知队列；再进入实际命令。
- 原生返回先保存 native-exit，再核 source 与运行上下文，归档新鲜 XML，使用 XmlResolver=null 的文件读取器解析精确身份；native 与 collector 分列。
- Full 的 failure.ignore=true 明示仅用于采集存量红，方法门仍拒绝新增非通过、状态变化、缺失或坏类；summary 保留 reasonComparisonPending=true，不能靠 native0/collector0声称全绿。
- SDK 原五身份、四安全 outcome 与零诊断仍核；engine 两个空 method 类级事件与原14未执行源方法单列，原4个旧 skip 不被新方法数量抵销。相对回归门不能升级为全源方法已执行。

实际 PS5 首次入口在 FREEZE_ADMISSION line16 先停，未建 run、未读 env、未到 Maven；ROOT 在 `collector/pre-maven-stop-20261029/record.json` 保存了当时外层工具返回的记录及无 BOM 原件。原件 20350 字节、SHA `5975446ba5adf620a255065eec4978b6a76ce89bf82d2a6a1756cb6d08083d35`。我之前使用的 PowerShell 7.6 ParseFile 零错误仅证明该解析环境中的语法，漏核 PS5 文件解码条件，不能当作实际入口已具备的证明。

ROOT 修复后，run-full-module 为 20353 字节，头三字节为 EF BB BF，SHA **`a1a0dd3fa344dbd57695bdf0a3956025ba15a41d99e0fc62872a106c04db22b4`**。独立逐字节比较确认去掉新 BOM 后与保存原件完全一致，未改变控制流、参数、产品或断言。显式调用 Windows PowerShell **5.1.26100.9711** 的 ParseFile（不执行入口）复核：两文件都零语法错，但旧无 BOM 版的 taskRoot 字面值不等于实际工作区，新 BOM 版精确相等，验证了该格式修正。

full-report-records 仍为 4363 字节、SHA **`96d70be0951b75c335ea9d2a60460f794424d03d08882cb760be5f48cabd8724`**；独立字节检查全为 ASCII，未受此次中文路径解码问题影响。后续实际冻结应记录修正后的入口 SHA，不继续以旧5975446b作为实际执行入口身份。

完整回归与各原非通过原因的比较尚未在本报告中读取；必须继续保留原始失败/原因及这次 Maven 前停点，不通过替换旧证据、诊断参数或改变断言取得相对门通过。
