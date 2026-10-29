# EX-15b 首个实红：父目录路径段最小修复候选

日期：2026-10-29。作者：`ex04_pg_scope`。本次仅修改 `skill-registry/src/main/java/cn/agentplatform/skill/infra/ProcessAdapter.java`，并新增本文；未改测试、ProcessContract、其它产品、契约、三主入口或索引，未运行 Java/Maven/PG/HTTP/Node。

## 已有真实红

主线程实际执行 `path-red-20261029-01`，本次只读核对其原 summary/XML：nativeExit=1，2=1P1F/0E0S，identityComplete/actualBehaviorRed/sourceStable/contextStable/noProductFixInThisRun 均 true。collector0 由主线程回执提供。

- `acceptsValidEnvelopeWithoutOutputFiles`：PASS。
- `rejectsParentRelativeOutputPathBeforeReturningSuccess`：FAIL，精确原文为 `Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.`。
- 原 `methods.xml` SHA-256：`41D01FD9811ACCC34FE6011E443481878B23FBEA51842CF3074EEBF868DCE890`。
- 原 `summary.json` SHA-256：`068A00CB624C2D4A963ED824C140A521B54BEC344A8010855C35B720A6C073FA`。

这是现有 execute API 的行为红，原件未改。它只证明父目录声明未被拒绝，不代表完整 files 通道已经得到红绿覆盖。

## 精确修改

只在 `ProcessAdapter.success` 的 result 校验后、创建并返回 Output 前，先沿既有 `arrayOrEmpty` 读取 files，然后遍历各项已有文本 path：

```java
for (String segment : path.asText().replace('\\', '/').split("/", -1))
    if ("..".equals(segment)) throw invalid("响应 files.path 不允许父目录段 ..");
```

Windows 反斜杠与正斜杠统一按分隔符解释；只拒绝完整段 `..`，不使用 `contains("..")`。因此 `../escape.bin`、`output/../escape.bin`、`output\\..\\escape.bin` 命中；`output/report..json`、`output/.../file.bin` 的连续点文件名不因本检查被误判。这里是静态代码含义，尚无本次绿测结果。

拒绝沿既有 `invalid` 返回 **502 / PROCESS_OUTPUT_INVALID**；Output 使用已读取的同一 files 数组。没有新增字段/API、文件 IO、ArtifactStore 接线或迁移；其它缺失/非法字段形状、绝对路径、symlink/junction、TOCTOU、大小与摘要、持久链均未在这一小步扩改。完整 EX-15b 仍进行中。

Git 只读 diff 确认该产品文件 **10 行新增、1 行替换/删除**，两个 hunk 都位于 success；没有改旧无文件信封或全局 arrayOrEmpty（warnings 仍沿原逻辑）。

## 冻结指纹与待复核

| 文件 | SHA-256 |
|---|---|
| ProcessAdapter 修改前 raw | `6898320E164BE2A254DCF009CFC24CE00BE0AA9B63954FFD7B6F9BA26D58A293` |
| ProcessAdapter 修改后 raw | `5A0C87FD88EECE89C2B5A2F85C95B599C59124468830E5A500C734A935118E80` |
| ProcessFilesTest 原样保留 | `7B363256F026266C5A65D4C85E2BEB79D4AF3DADDC497AC1831E30834868A7D7` |

主线程先独审这两个 hunk，再按 `test-candidate.md` 的同一 Maven 目标/测试 SHA、原环境和超时采集新的唯一 run，重跑同两例。预期两例 PASS，但**本文未运行，不能记已绿或整卡完成**。红 XML/summary 必须保留，后续完整 IO 白名单与协议冻结仍按 preparation 独立推进。
