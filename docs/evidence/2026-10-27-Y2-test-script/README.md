# 测试脚本加固与两个"假绿/假红"陷阱（Y-2 / Y-4）

日期：2026-10-27 晚
范围：`scripts/test.ps1`（G-8 的 Y-2 部分）、`.tools/env-load.sh`（本地工具，不入库）
基线：`78fbe27`

## 1. 为什么改

首轮七模块全量在 `platform-gateway` 崩掉，dumpstream 原文：

```
insufficient memory
Native memory allocation (mmap) failed to map 4194304 bytes ... G1 virtual space
```

原因不是产品缺陷：默认堆上限 = 物理内存 25%（32G → 8G），多模块 fork 出来的 JVM 叠加后 native 内存不足；
`-Dmaven.test.failure.ignore=true` 只忽略 surefire 的用例失败，**对 fork 崩溃无效**，于是 reactor 停在该模块，后面
四个模块根本没跑。`engine-runtime` 还出现过 `RuntimeStreamTest` 挂起 16 分钟无输出的情况（无 fork 超时）。

## 2. 改了什么

`scripts/test.ps1`：

- `param([string[]]$Modules=@(),[switch]$Frontend,[switch]$WithCache,[int]$ForkTimeoutSeconds=900)`
- mvn 追加 `-DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp"` 与 `-Dsurefire.timeout=$ForkTimeoutSeconds`
- 新增 `-WithCache`：显式调用 `scripts/start-cache.ps1`。默认**不**启动缓存——脚本不应顺带改变本机状态；
  `start-cache.ps1` 在专用 rootfs 缺失或 26379 被占时直接报错，不会接管别人的进程。

`pwsh` 在本机是 PowerShell 5.1.26100.9711，把无 BOM 的 UTF-8 当 ANSI 读，`edit` 工具回写丢 BOM 后整文件解析失败。
本次用 `utf-8-sig` + CRLF 写回恢复 `efbbbf`，再用
`[System.Management.Automation.Language.Parser]::ParseFile` 验证通过。**后续改 `.ps1` 必须显式保留 BOM 与 CRLF。**

## 3. 验证

`pwsh scripts/test.ps1 -Modules admin-common`：跑完 319 例（10 Failures / 1 Error，与基线一致），**未再出现 fork native OOM**；
脚本末尾 `throw '后端测试失败'` → exit 1 属预期（admin-common 有既有失败，见基线 README）。

## 4. 陷阱一：`.env` 的 CRLF 让真实库测试"静默全绿"

`.env` 是 CRLF 换行。bash 侧 `export "$key=$value"` 会把尾随 `\r` 一起带进环境变量，而 JUnit 的
`@EnabledIfEnvironmentVariable(named="AGENT_DB_PASSWORD", matches=".+")` 是**全串匹配**、正则 `.` 不匹配行终止符，
于是整类测试被跳过，报告长这样：

```
Tests run: 13, Failures: 0, Errors: 0, Skipped: 13
```

surefire XML 里写得很清楚：`Environment variable [AGENT_DB_PASSWORD] with value [...XCHs&#13;] does not match [+]`。
**处理**：bash 加载 `.env` 时必须剥 `\r`（`.tools/env-load.sh` 已修）；`scripts/test.ps1` 用 `Get-Content` 拆行，
本身不受影响。看到 `Skipped` 先查环境变量尾部是否有 CR。

## 5. 陷阱二：Y-4 是测试基座债务，半修会更糟（本次**未修**，结论入库）

`app-management` 的 20 个错误里，`IntegrationPolicyResolverHttpTest` 15 个是容器级
`PORTAL_CEILING_INVALID`：门户属性上限已收紧（`admin-common/.../authority/AuthorityProperties.java:61-73` 要求
allowlist 覆盖 `bizSystemCode`/`userId` 且各恰一值），而夹具 `LoopbackAuthority.config(...)`
（`admin-common/src/test/java/cn/agentplatform/common/authority/LoopbackAuthority.java:114-115`）仍只有 `region`/`dataLabels`。

实测：只把四键补进配置后，app-management 从 20 错降到 19 错（容器错误消失、5 例转绿），
但 admin-common 反被拖红——`ServiceAuthorizationTest` 0→12 错、`LocalRunAdvanceAuthorityTest` 1→4 错。
根因是 `ClaimCeiling.attributes`（`admin-common/.../authority/ClaimCeiling.java:27-34`）：

```java
for (String key : List.of("bizSystemCode", "userId")) {
    if (!left.containsKey(key) && !right.containsKey(key)) continue;
    if (a == null || b == null || a.size() != 1 || !a.equals(b)) throw denied();  // SYS-AUTH-403 授权主体或归属不一致
```

配置侧有了这两个键、令牌侧没有 → 解令牌即 403；而多处测试各自手工铸造令牌。**半修会让"跨模块"一起红。**
结论：Y-4 要连"配置上限 + 所有手工令牌"一起改，属 G-8 的机械改造，**不在 P0 掺进去**；已回退，工作树保持干净。

## 6. 未覆盖

- 七模块单命令全量仍不可得：Y-3（单测无 Spring → `ServiceTokens` 未登记 deployment mode → 403 被 `SkillClients`
  吞成 502）、Y-5（Redis 未起）、Y-6（gateway 夹具不自建库/无就绪探针）都要在 G-8 里一并处理。
- `-WithCache` 分支未在本机验证（`AGENT_REDIS_*` 指向 26379 专用实例，当时未启动）。
