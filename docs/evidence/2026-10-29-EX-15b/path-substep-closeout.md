# EX-15b 父目录路径段拒绝：提交候选收口

2026-10-29。本记录只准备 path-only 子步的精确本地提交候选；没有 stage、提交、重新运行测试或修改旧原件。EX-15b 的完整 PROCESS 文件 I/O 仍进行中。

## 改动与验证结论

唯一产品变更是 `ProcessAdapter.success` 在返回成功 Output 前检查响应 files 的文本 path，将反斜杠视为路径分隔符，拒绝完整 `..` 段，沿既有错误域返回 502/PROCESS_OUTPUT_INVALID。真实 Git 差异为 10 行新增、1 行替换；未新增文件打开、ArtifactStore 接线、存储上传、迁移或契约字段。

唯一测试源是新增 `ProcessFilesTest` 两例。它保留无文件信封成功、父目录段拒绝的精确状态/错误码，以及真实 worker 身份、stdin EOF、线程/进程退出和自有目录清理断言。

| 源文件 | 当前 raw SHA-256 |
| --- | --- |
| `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessAdapter.java` | `5A0C87FD88EECE89C2B5A2F85C95B599C59124468830E5A500C734A935118E80` |
| `skill-registry/src/test/java/cn/agentplatform/skill/infra/ProcessFilesTest.java` | `7B363256F026266C5A65D4C85E2BEB79D4AF3DADDC497AC1831E30834868A7D7` |

原功能独审见 [path-fix-review.md](path-fix-review.md)，其当时“后续模块回归仍待”的事实不改写；本记录接续新增后续回执。

| 原实际 run | 原结果及含义 |
| --- | --- |
| `path-red-20261029-01` | native 1、1P1F/0E0S；父目录例精确为期望 PlatformException 却未抛，collector 0 仅表示实红采集符合预期 |
| `path-green-20261029-01` | native 1、0P2F/0E0S，collector 2；worker identity 临时文件移动遇 AccessDenied，未完成握手；原失败保留，不能追认为绿 |
| `path-green-20261029-02` | 同 7B363 测试、5A0C 产品，native/collector 0，2P/0F0E0S；普通用户 Temp 与 red 一致，green01 的沙箱 Temp 权限差异明确保留 |
| `adjacent/runs/process-adjacent-20261029-01` | 原 81 加新 2 共 83P，native 0、10 fresh XML、source/context/identity/regression gate true；这轮 adjacent **本来就是通过** |
| `collector/runs/full-skill-ex15b-20261029-01` | 700＝583P7F106E4旧S，source/context 稳定但 regressionGatePassed=false；新增失败是既有 SDK handoff 方法，不能用 native 0 或 adjacent 通过掩盖 |

旧 Full700 的新失败身份为 `AlgorithmOffloadInterruptionTest.originalGetBodyHandoffPreservesConfirmedOrExplicitlyUnconfirmedStopSafety`。后续小范围 SDK5 再次通过不能倒写该原 Full700；本 path 提交不纳入 SDK 诊断器、诊断 run 或 EX27 产品/测试源。

## EX27 联合回归如何支撑本子步

EX27 响应所有权子步由另一份独立候选承担；见 [运行结果独立终审](../2026-10-28-EX-27/response-ownership-runtime-review.md)。该终审明确接受本 path 的最终模块回归覆盖，同时保留原 Full700 和未证明其历史唯一根因的限制。

| EX27 run（只引用，不纳入本提交） | 实际结果 | summary SHA-256 |
| --- | --- | --- |
| `response-ownership-full/runs/sdk-six-20261029-01` | SDK6，6P、native 0/noignore、无新增诊断，relative gate true | `B43992DA448F7AD2E82327C4BFC69782B70ABDBA68B3757C679A1DBF32EA7BE0` |
| `response-ownership-full/runs/adjacent-20261029-01` | 原 adjacent83 全通过、native 0/noignore，relative gate true | `74EEE7B68A655B1A1013B332D725A3CBCF69B31BBB6811F9507257E8346695D8` |
| `response-ownership-full/runs/full-701-20261029-01` | 701＝585P6F106E4旧S，73 XML；无缺失/意外身份/状态变化/新增跳过，relative gate true，allGreen=false | `58AB80FD4A1BD83BCF0DA5A6CE5C59B846EB89C1C87E503B1E544C7736F1F32B` |

Full701 保留 rawReasonComparisonPassed=false，并只沿已冻结的一项有界可变表示证据得到 reasonComparisonPassed=true；没有把原始 gate 改成 true。SDK 默认与实际所有权见证通过，四个旧 skip 保留，collector 没有 DDL。本记录引用已完成的独审和原件，不重新声称本代理运行或重算了整批 XML。

Full701 的 source-after 同时包含本 path 两份上述 SHA，以及 EX27 的 `ArtifactStore.java` SHA `75F89B938A054BE6AC385C7152F49A9525D25E696BCE3634C42C7BD3201AE907`、新增 `AlgorithmOffloadResponseOwnershipTest.java` SHA `40474F829DEBD58D0B1D689D8F139F5BD89E2B2A07B298470D90C870960397F6`。因此这是**同一联合工作树的共享回归**，不能改称只含 path 补丁的隔离提交树已经完整通过。EX27 两源和全部 EX27 证据由其负责人另行准备精确提交，本清单不包含它们。

时间与 Git 身份也分开保存：三次 path 定向 run 的 HEAD 是 `1cc453388950ec90eba51a1484994208c78d67fd`；旧 adjacent83、Full700 和新联合 SDK6/Adjacent83/Full701 的 HEAD 是 `acad6bdee223b23f331420c6de50045cb5fa8941` 加各轮已登记的未提交源。随后 EX06 单独提交 `73a4ada6de5ed5ee34f623a8a435c949040a2e7d`，它是本次提交准备时 HEAD，不是上述运行 HEAD；不倒写旧 command/admission/summary。两提交间 skill-registry/admin-common/POM 无差异。

## 精确提交边界

`path-commit-manifest.json` 列出 70 个准确 commitPaths：2 源＋65 份现有安全公开证据＋本目录 `.gitattributes`、本说明、manifest 本身。manifest 为其余 69 文件记录 raw SHA/字节数与应用精确 Git attributes 后的 blob OID；manifest 自身最终 SHA 在交接回执固定，避免自引用摘要。stage 后仍须独审实际 index OID/路径集合，不能把这份工作树候选直接当作 index 已通过。

本目录新 `.gitattributes` 只逐一列出本 manifest 的证据路径，用 `-text -whitespace` 保留历史记录字节；没有目录通配、没有作用于两份 Java 产品/测试源，也不覆盖其他卡属性。生产 Java 继续使用仓库原 text/eol/whitespace 规则。

提交排除所有 raw XML、日志、环境文件、凭据、`.local` 运行材料以及完整 CSV 原始失败消息；manifest 只保存本卡相关 raw 文件路径/大小/SHA，原件继续在本地原位置。原 source-before/after、summary、native、命令、迁移守卫与 XML 清单等安全结构化证据保留。没有修改或删除任何旧 run。完整 I/O preparation、phase-A patch、SDK5 诊断材料亦不纳入 path-only 提交。

已知实际凭据检查只在内存读取当前 `.env` 及进程环境中的凭据值，对候选字节做精确匹配，不输出值；初次 67 份既有候选中 4 个已知凭据零命中，JWT/私钥材料模式零命中。新生成的三份文件还须在最终 manifest 封版时再次检查；manifest 记录最终结果。此检查不把 SHA 或引用配置变量名当作秘密，也不把未提交 raw 文件的内容搬进公开证据。

可以准备独立本地提交标题 `fix(skill): reject parent segments in process output paths`；本记录不执行 stage/commit、不授权扩大 payload。精确提交与独审完成后，最多关闭“父目录路径段拒绝”子步。完整文件输入/输出映射、描述符/大小/SHA 校验、绝对路径与链接/竞态防护、授权持久引用、补偿与重启链仍待后续准确范围，EX15b、PROCESS I/O 和相关取消接线不能随本子步关闭。
