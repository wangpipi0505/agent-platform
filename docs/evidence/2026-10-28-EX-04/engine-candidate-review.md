# EX-04 引擎产品候选独立窄审

2026-10-29（Asia/Shanghai），审阅者 `/root/ex04_test_gate`。仅审 ToolOutputPolicy 生产差异、原件/候选快照与此前已静审的两类 47 个测试实例，并按已审 SkillRules 规则核对分页形状。本轮只新增本文件；未改产品、测试、旧件或 index，未运行 Java/Maven/PG/HTTP、环境或仓库脚本，未读凭据，未提交，未使用 Superpower。

**结论：未发现阻断问题，准入实际 PG 桥 fixture 固定后的最终 47 个实例定向绿色验证。此处是静态候选准入，不能记作实际编译、测试通过或整卡签收。**

## 读取身份

| 源 | SHA256 |
| --- | --- |
| Policy 红前原件 | `94cf53c998668fe6d4a7c60d41a063d5c05fced6e7800a96fc3fd1a289fe4c77` |
| Policy 当前候选 | `5d2596babb01a047fc24f8db4e3534eae22bc4e2820f45231299a2d3f91ffebb` |
| `ToolOutputPolicyTest.java` | `0989dce43b6033082ac7d15b807e475b6941426adc2441c2a335d900c5e9e741` |
| `RunToolProfileTest.java` | `4018d136e9b9e7b8bf3eef66a32ce2edecba565eeebc68b9b6f0f40c1461c1c0` |

当前生产字节与 `engine/production-prepare-20261029-103118-156715/ToolOutputPolicy.candidate.java` 完全一致。只读文本比较确认 enforce 与 envelope 全文、resolve 至文件末尾均与 before 原件相同。

ROOT 已提供 `red-engine-20261029-102649-693` 的真实红回执 47＝36P/11F、native1/collector0、预期向量吻合。本次窄审不重跑，也不把所提供红回执当作候选绿色。测试当前 PG 字段仍为初始规范样例，不称实际 PG 读回。

## 分页镜像与输出边界

- `declaredPagination:110–134` 仅在对象声明上识别缺省 PAGE、显式 PAGE 或显式 OFFSET。显式 null、数字、布尔和未知 mode 均返回 null；OFFSET 不会因字段缺失降为 PAGE。
- OFFSET 仅允许 mode/request/maxPageSize/maxRows，mode 已精确识别、request 两参数须存在且为非空文本、两个限额均为正 32 位整型，因而四项都必需。response 与任何未知键被拒绝。
- PAGE 仅允许 mode/request/response/maxPageSize；request.page/size 必需，response 可省略或为空对象，存在的 itemsPath/totalPath 须为非空文本，未知响应键、null/非对象 response 被拒绝。可选 maxPageSize 存在时也必须为正 32 位整型。与 SkillRules 已审保存形状一致；没有新增路径猜测或上限排序限制。
- `nonBlankText` 使用 isTextual；`positiveInt` 使用 isIntegralNumber/canConvertToInt/正值判断，不靠 asText/asInt 把布尔、字符串、小数（含 1.0）或溢出转换成合法声明。缺失节点可安全返回 false；畸形 JSON 声明只返回 null，不新增运行抛错。
- `paginationHint:159–184` 只为 OFFSET 增加 mode 与 maxRows，并保留原 offset/size 名和上限。OFFSET 入口禁止 response，所以 resolve 收到空路径并返回 Missing，observed 保持空对象，即使原响应存在 total/items 也不推测。没有 nextOffset、页码换算、HTTP 参数改写、自动续拉或累计 maxRows 执行逻辑。
- 原 PAGE hint 仍使用原 request/response/maxPageSize/observed/hint 形状，不为 PAGE 增补 mode；原点路径读取逻辑保持。新增 helper 不写 tool 或 result，提示内 request/response 为副本，原始响应不被切片或改写。
- enforce 的 UTF-8 计量和 `actual <= maxBytes` 同节点早返、原七字段错误信封与 reason 兼容键、默认阈值与 resolve 均未改。旧小响应和畸形声明小响应继续在解析前返回。

## 测试适配与后置门

已冻结的 47 实例为原 Policy10＋Profile12＋新增 Policy24（3 普通＋21 参数化）＋Profile1；原 22 方法全文的精确还原见 `tests-static-review.md`。新增断言能检查 OFFSET 六键提示、空 observed、无 response/page/nextOffset、原结果不变、PAGE 可选响应、恰等阈值以及畸形形状；实际 prepareSnapshot 路径接入 Policy，而非手工构造冻结结果。未发现此生产差异绕过这些断言的明显缺口或 Java21 类型疑点。

后续须从技能侧实际 PG 绿色产出固定两字段 fixture，留产出轮次、内容与 SHA 对照，再冻结该最终测试身份并运行 47P/0F/0E/0S。随后完整 engine 方法/原因对照保留原两类级事件与 14 个未执行源 Test，新增非通过/坏类/skip/未执行为零。PG 桥后的 fixture SHA 会变化，不能继续用本表初始 Profile SHA 作为最终运行身份。真实跨服务运行与 OR 业务分页仍不由此静审证明。
