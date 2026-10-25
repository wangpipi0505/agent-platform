# 组件三档验收登记（规则 v1.0）

本登记依据用户要求应用《组件验收口径-条件验收规则》v1.0。规则只改变判定口径；不自动改变既有组件状态，不替代项目负责人批准，不修改冻结 v1.2 和四契约。规则快照：`.local/component-conditional-acceptance-rule-v1.md`，SHA256 `0817c6021decb015a6daef3ebdb4f3d1fe301babdc91ed0d38748cd4d9404b05`。

本次核对的四个组件：**0 项完全验收 + 0 项条件验收 + 4 项不通过**。数量仅指下表，不是全工程验收统计。各组件已有的代码评审结论保留；开发可以使用已评审的限定代码基线，组件正式验收和生产放行另受本登记约束。

| 组件 | 独立复核依据 | 本次正式档位 | 判定及处置 |
| --- | --- | --- | --- |
| 冻结初始 Prompt 转换 | `review-component-conditional-acceptance-v1.md`，H/P 缺口清单 | NOT_ACCEPTED | 代码 Approved 保留；未能证明全部继承问题仅为 §3 证据缺口。边界影响未知不等于已发现生产代码缺陷，但不得推定安全后自动转档。 |
| 初始流式 SDK 请求体校验 | 同上，H/W 缺口清单 | NOT_ACCEPTED | 代码评审结论保留；隔离、canonical 打包影响和 bootstrap 范围等原始事实仍缺完整闭合依据，§7 示例不代替正式适用审查。 |
| 初始模型流式组件 | 同上，H/S 缺口清单 | NOT_ACCEPTED | 代码评审结论保留；另含历史凭据/输出范围与隔离处置问题。后来的新密钥、NOLOGIN 和资源关闭不能回溯证明原执行边界。 |
| 引擎初始发送与恢复（最终 source684） | `review-engine-initial-sender-fix.md`；`.local/engine-initial-sender-development-baseline.json` | NOT_ACCEPTED | 生产代码 SpecCompliant / QualityApproved，C0/I0/M0；本轮测试与物理交付核对通过。已披露的实际合成凭据字面量输出违反保密执行要求，不能扩张解释为纯“缺少输出证据”。代码通过与正式组件档位分开登记。 |

上表四项没有符合 §6 的独立条件档提案，因此本次不产生转档批准请求，也不伪造项目负责人签字。若后续具备合法转档前提，应提出逐条编号缺口清单、白名单归类、不能补证的原因、具体对冲计划，再由项目负责人批准并追加记录。既有原始 NOT_ACCEPTED 及旧证书全文保留。

## 初始发送组件的代码基线与验证范围

最终源码清单 `.local/engine-initial-sender-fix/final4-source/source.json`：684 项，SHA256 `1d11e82ab89aa589aa8415fee8a14e36b38fab40dd18e4567c9b085ea9153901`。本轮修复为 7 MOD / 677 SAME；相对已评审 source680 为 7 MOD / 4 ADD / 673 SAME。独立复审关闭 I1–I4、M1 及新增终态租约窗口问题。

本轮实际验证分开记录：最终 final4 的 12 个 DIRECT 方法、第二隔离数据库的 6 个真实依赖回归方法；普通 Surefire 为 4 PASS，另有 1 个 ModelExchangeTest 的类条件 SKIP 投影。SKIP 不计为执行或通过；两个串行补核方法保留原 final3 标签。原 candidate684 的 53 项旧结果不累加冒充本轮 final4 全量结果。

Root 新交付核对：三个源码树共 2052 次文件身份核对；7 个最终变更源码的成功编译封存输入；两份实际运行类路径捕获各 663 文件 / 634 个 Java21 类 / 156 依赖；三份新制品及 250 个生产条目与运行捕获一致；嵌入 common 与独立 common 一致；2988 个本轮清单引用；265 个 writer guard 记录及 686 次身份观察；52 个 Linux Redis 原 PID/start 对应关闭记录；两个保留数据库会话为 0、角色 NOLOGIN/无特权/无成员关系和 schema 权限。运行类路径捕获不声称每个类都被 JVM 加载；没有重启已关闭环境。

遗留 MAIN/SUMMARY 的实际通过来自原 candidate 群组。新复审确认 legacy exchange/summary 与共享 fail/commit 调用逻辑未变；root 另核 ModelExchange 从 legacy exchange 到文件尾字节不变，common/OutputPolicy 的源身份未变。故保留原实际通过的覆盖归属，不把类条件 SKIP 改写为新执行通过。

## 逐项执行限定与后续处置

历史三个组件的 26 项编号清单、归类、未知事实 Q1–Q6、OP1/OP2、OW1/OW2、OS1/OS2/OS3 对冲方案，完整保留于 `review-component-conditional-acceptance-v1.md`，本登记引用其全文，不以摘要省略条件。

| 本轮编号 | 事实及不可回补边界 | 当前处置 |
| --- | --- | --- |
| EIS-01 | 初始 sender 原执行两次输出固定测试 provider 凭据字面量；fix 波次一次输出合成 TLS 解锁字面量。不是新的实际环境密钥结论，也不重印值或编造会话输出摘要。 | 保留原报告及独立复审；已实际发生的输出不能以以后脱敏消除。超出纯证据类白名单，正式档位保持 NOT_ACCEPTED。以后检查仅 AST 符号/参数名或在输出前省略全部字面量与注释值。 |
| EIS-02 | final3 schema/budget 两阶段曾重叠，峰值 5 个 server JVM + 2 Redis；原群组不能被后续串行成功覆盖抹去。 | 原结果排除保留；受影响两方法已串行核对，仍标原 final3。后续实际完成上一个群组后才能启动下一个，维持 3 server JVM + 1 Redis 限额。 |
| EIS-03 | root 一次离线 body 比较通过，但未有启动前封存与稳定身份 guard；不能补造原 PID/时间证据。 | 仅保留其有限比较结论与限定；后续新物理审计均通过已封存 guard 执行，不能回溯豁免历史。 |
| EIS-04 | root 一次只读文件名查找丢失 exec 返回的 exit/session 元数据；另一次已退出的查找不证明前一次。 | 无代码、数据库或服务写入；原精确结束身份不可证，不声称全局 noBackground。未普查进程或终止未知进程。后续完整保留执行返回对象。 |

代码评审通过不撤销上述记录。更完整的边界说明见 `.local/engine-initial-sender-fix-root-execution-notes.md` 和两份独立复审；未发现新的生产功能/安全 C/I 不等于历史安全与隔离事实已获批准。

## 联合验收与交付硬闸

下列为尚未执行的覆盖义务，不是补测豁免，也不关闭全局任务。

- **T31 / A27**：在 17 项需求联合链与获准启用的真实生产 IAM/D3 下，逐组件关联版本、当前授权、冻结输入、实际 SDK 字节、真实请求/流式接收、引擎持久 attempt/request/DISPATCHED、原始 response 与逻辑结果及监控回放。Prompt、wire、stream 分别落实 OP1、OW1、OS1；引擎需展示实际调用入口至一次提交的同次链路。
- **T32 / A09/A10/A16/A20/A23/A24/A28**：落实 OP2、OW2、OS2；引擎覆盖撤销/权限收窄/过期、owner/dispatch/node lease、事务回滚与 commit ACK 不明、已付费响应保留、预算失败及恢复不重复发送/计费。每个 seam 按实际位置命名，旧失败留存。
- **T33 / A29**：真实 500 并发 run、每副本 5000+ SSE、首 token p95、200 并发×5MB、慢客户端/背压/取消与清理，落实 OP2/OW2/OS3 及引擎有界流消费。T33 当前未启动，隔离单组件正控不充当容量结果。
- **T34 / A30**：依赖 T31/T32/T33；逐组件核对合法档位、条件清单、实际对冲覆盖、IAM/D3、部署/恢复/secret 注入与交付材料。未获准或未被覆盖不得生产签收。未来若条件档组件暴露功能缺陷，立即撤回为 NOT_ACCEPTED，保留失败并重新修复、复审、批准。

功能 owner 提交对应证据，联合验收 owner 维护逐需求覆盖，独立复核验证，项目负责人负责适用批准。未虚构具体人员、签字或时间安排。T01 仍为唯一全局完成项；T09/T11/T12 进行中；默认生产 IAM/D3、公共 CHAT/session/token 流等权限不因本登记改变。

## 2026-10-23 追加：首次无会话 CHAT（I1 修补后 source688）

新增核对一个组件，连同上文四项，当前本登记所涉五个组件为 **0 项完全验收 + 0 项条件验收 + 5 项不通过**，不是全平台统计。原四项和原 CHAT 作者/复审/失败记录保留，不以本条修改旧结论。

首次无会话 CHAT 的创建、单AGENT执行/恢复、状态和持久事实观察已形成限定实现；本次修补仅在共享请求gate补齐四个顶层会话/历史保留名，null/空/非空都拒绝，三处requestId回读均在gate之后。最终688份源码，清单SHA256 `2625ce4d7f40fcf40c0d529f8b0d59284ceda1ff40253eca629f9bc75ff18ca7`，相对原688为3MOD/685same，无新增删除。

独立结论：`review-initial-chat-admission-fix.md`，34204B，SHA256 `c95c1c1deb950b1211cc72330aaf44cff5e141d16102bd7ff0b7aa797b2ecf04`；Spec Compliant、生产Quality Approved、生产C0/I0/M0，原I1关闭。真实RED为顶层history空数组误接受200；四GREEN方法通过，新建和回读各12请求均403、无多建/多发，合法CHAT/TASK/FLOW正控通过。原九重点群组不重跑、不合并冒称本轮全套。

**正式档位：NOT_ACCEPTED；仅批准后续隔离开发使用限定代码。** P6部分早期输出原定位/完整内容仍UNKNOWN，尚不能证成全部遗留缺口为§3白名单；这不是已知泄露或新生产缺陷结论。原P1–P14、本fix F1–F10及逐条对冲完整见独立报告，不把候选分类当已批准条件清单。当前没有条件档提案，故无负责人转档批准请求，也无自签批准。

| 追加编号 | 准确事实与处置 |
| --- | --- |
| IC-01 / 原P6 | 三个可见有限片段未见凭据；部分早期newbuild/迁移片段/Redis参数输出的精确定位和完整边界仍未知。不普扫日志、不重印原值、不伪造transcript摘要，不以新GREEN或负责人一般批准替代§8判断。 |
| IC-02 / M1 | 原closure81项中79个真正guard、2份metadata；原869身份枚举中176有效PID/start观察、693空项。root和独立复审已确认分类；176有效观察在原收尾核验时aliveNow均false，六Linux及DB收尾独立成立。原报告/closure不改；准确口径以本条及限定证书追加，control另列审计后3次真实guard结束，不把79当全历史总数。 |
| IC-03 / 新F1/F3 | 一次只读outer执行前封存缺口和内部输出PREabsence粒度缺口按精确路径保留；后续正确封存不倒填过去，也不为美化历史重跑绿功能。 |
| IC-04 / 覆盖范围 | 只涵盖首次无会话CHAT/FULL、两message/零tools/三来源父集合；公共token、取消、session/二轮、默认生产IAM/D3、部署和容量不因此闭环。 |

根限定依据：`.local/initial-chat-admission-development-baseline.json`、`.local/initial-chat-admission-root-final-qualification.md`、`.local/initial-chat-admission-fix-root-qualification-v1.json`；原688交付物理核对另见 `.local/initial-chat-admission-root-qualification-v1.json`。新四JAR317生产条目与实际捕获一致；2752文件检查、721运行文件/686Java21类/158依赖、16preseal、有限inventory/control及注册资源收口均已核对，不把比较目录称JVM全部加载。

T31/T32/T33及真实IAM/D3逐项义务见 `.local/initial-chat-admission-joint-acceptance-obligations.md`；T34未覆盖/未批准不得生产签收，未来条件档暴露功能/安全缺陷立即撤回。全局仍T01唯一完成、T09/T11/T12进行中、T33未启动；冻结v1.2、四契约、不排工期保持。