# SEM-01.2 后续profile注册表与兼容边界（P席，2026-11-03）

基线：AP HEAD `fd384653`；OR HEAD `7fed730`（upgrade/20261103）。

## 交付

- AP `contracts/semantic-runtime.md` 增补 §7「后续 profile 注册表与兼容边界」+ 头部归属更新：能力标识注册表（`read-only@1.0` 已冻结；`decision@1.0`/`condition@1.0`/`goal@1.0` 已定义未实现，实现时新建独立 Schema 文件并回表登记）；三个未实现 profile 的封闭字段集（conclusion/conditionKind/outcomeState 五枚举封闭）；未实现 profile 服务端返回 `SEMANTIC_PROFILE_UNKNOWN`、禁止静默降级；信封不共用/演进规则/三类摘要独立/错误码扩展回§3 登记/旧 `passed` 字符串协议不无版本改型。
- OR `contracts/semantic-runtime.md` 增补 §4 同步注册表口径。
- **不实现执行器**：`semantic-runtime-v1.schema.json` 本子步零改动（`decisionRef:false` 保持）；SEM-04/07/08 实现时各自新建 Schema 文件。

## 功能检查（两轮，均通过）

- 第 1 轮（12 项）：四个能力标识两稿齐全、三 profile 标注"已定义未实现"、三组封闭枚举完整、旧协议兼容表述、未知 profile 错误码在 v1 枚举、降级禁止双稿、Schema 文件回表登记×3、三类摘要独立——11 项即过；1 项 FAIL 为**检查断言自身错误**（要求 md 不得引用 `"decisionRef":false` 字面量，而 md 在 §7.1 合法引用该字面量描述互斥性），产物无缺陷。
- 第 2 轮（6 项）：互斥声明、禁止回填/伪造、schema v1 未被改动、decision 内必填结构化对象说明、OR 稿口径、演进规则——全过。

## 未覆盖与后续

- `decision@1.0`/`condition@1.0`/`goal@1.0` 的正式 Schema 文件随 SEM-04/07/08 各卡实现时创建并回 §7 注册表登记；本子步不启用任何执行路径。
- 激活与 freeze.json 登记随 SEM-01 整卡收口执行。

## Git 与回退

AP 精确暂存（semantic-runtime.md＋本报告）、OR 精确暂存（semantic-runtime.md）；本地提交不推送。回退：revert 对应提交。
