# Task1 I1 归属单值最小修复

2026-10-20；范围仅 root 接受的独立审查 `review-iam-stage-1.md` 的 I1/P2。唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`。仅修改 `ClaimCeiling.attributes` 的 bizSystemCode/userId 单值有效性和既有 `AuthorityPolicyTest`，没有执行 Task2。

修复结果：两侧归属集合大小为1且精确相等后，增加单值 `null` / `String.isBlank()` 拒绝，沿用 `PlatformException(403,"SYS-AUTH-403",...)`。不 trim、不补身份。非空白同值原样保留（包括两侧都为 `" a "`）；`" a "` 与 `"a"` 不相等。已有缺侧/冲突/多值校验保持不变。

## 真实行为红绿

起步完整复制 common src/POM 的28文件到 [baseline/](iam-stage-1-ownership-fix/baseline/)，SHA在 [baseline-sha256.json](iam-stage-1-ownership-fix/baseline-sha256.json)。先只修改测试，在旧源码上执行：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
mvn.cmd -o -B -ntp -pl admin-common -am test '-Dmaven.repo.local=.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' '-Dtest=AuthorityPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

| 阶段 | 证据 | 结果 |
| --- | --- | --- |
| 原源码行为红 | [behavior-red.log](iam-stage-1-ownership-fix/behavior-red.log)、[red-seam/](iam-stage-1-ownership-fix/red-seam/) 源码/测试/XML | 14项，6断言失败，0错误/跳过；两个key × 空串/单空格的四项未抛异常，两个key × null单元素抛NPE而非要求的PlatformException。不是编译红 |
| 两行实现后 Policy 绿 | [policy-green.log](iam-stage-1-ownership-fix/policy-green.log) | 14/14，0失败/错误/跳过 |
| 同冻结源码一次完整 common | [full-common.log](iam-stage-1-ownership-fix/full-common.log)、[full-surefire/](iam-stage-1-ownership-fix/full-surefire/)、[full-test-summary.json](iam-stage-1-ownership-fix/full-test-summary.json) | 61/61：Policy14 + Configuration37 + 旧CommonBoundary4/Schema5/SecretVault1；0失败/错误/跳过，BUILD SUCCESS |

测试使用 `Collections.singleton(null)` 构造实际可含 null 的归属集合，避免在 Set.of 输入构造阶段失败；六项逐一断言实际异常类型、status403及code。保留合法同值、原样含边缘空格同值与不归一化冲突断言。

完整 common 命令仅执行一次；未重复旧45项组合选择器或七模块：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test '-Dmaven.repo.local=.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
```

## 冻结与实际补丁重建

[changes.json](iam-stage-1-ownership-fix/changes.json) 仅2文件。`ClaimCeiling.java` 从 `4ac19d539152ea33ac1493979dacd4d01e1213facb37c4d1669198ae3347d37c` 变为 `775fb57848a53a7976901c5fa62d1efb742fa47f01a7702b50478a062106d2a2`；`AuthorityPolicyTest.java` 从 `7842e455e04c62cb4c4efd840f875286b4c449e3994226108068aa6c0af45b1c` 变为 `2a513d83daf56714d05bc8e951cc7522df47bb74ef9d7cd0db74a5cde9048201`。

- [frozen/](iam-stage-1-ownership-fix/frozen/)、[source-before.json](iam-stage-1-ownership-fix/source-before.json)、[source-after.json](iam-stage-1-ownership-fix/source-after.json)：完整common28文件，完整回归前后SHA完全相同。
- [full.patch](iam-stage-1-ownership-fix/full.patch)：完整两文件增量，SHA256 **e2306f59498f92b1c009c7aa6f3b0314bb8d7f216822ca0c38e5d4ebdd9ee250**。读取基线/冻结原字节生成完整 unified diff，不依赖不存在的HEAD，不使用空diff。
- [actual-apply.log](iam-stage-1-ownership-fix/actual-apply.log)、[patch-apply-verification.json](iam-stage-1-ownership-fix/patch-apply-verification.json)：真实git apply返回0；`applied-bytes/admin-common` 的全部28文件逐字节等于frozen与当前源码，不仅比较两文件。
- [target-sha256.json](iam-stage-1-ownership-fix/target-sha256.json)：当前common target/classes、test-classes的41输出摘要；target归还root复核/原reviewer I1复审。
- [manifest.json](iam-stage-1-ownership-fix/manifest.json)：新报告/本次全部证据SHA，清单自身不自包含。

```powershell
python docs/evidence/iam-stage-1-ownership-fix/freeze.py freeze
git -c core.autocrlf=false apply --verbose --whitespace=nowarn --directory=docs/evidence/iam-stage-1-ownership-fix/applied-bytes docs/evidence/iam-stage-1-ownership-fix/full.patch
python docs/evidence/iam-stage-1-ownership-fix/freeze.py verify
```

freeze步骤内部已实际执行上述git apply，已应用目录不可重复apply；verify可只读源码并重核。脚本从旧Task1冻结流程复制到本次独立目录，没有修改或执行旧脚本，没有覆盖旧target摘要。

## 旧证据与边界

[prior-evidence-before.json](iam-stage-1-ownership-fix/prior-evidence-before.json) 与 [prior-evidence-after.json](iam-stage-1-ownership-fix/prior-evidence-after.json) 覆盖 **178** 个原报告、原Task1证据与独立review证据文件；全部SHA相同。原 `iam-stage-1.md`、完整patch、artifactmanifest、baseline/frozen、旧55/旧红/失败apply、review报告/probe原字节保留。旧报告所述55项是其原历史结果，不改写为本修复61项；旧当前源码/target摘要自然属于修复前版本，本轮当前摘要位于新目录。

没有改变配置、路由JSON、业务、前端、其他源码/POM；没有读取.env/vault/masterkey/实际凭据，没有启停实际服务及其JVM，没有package/提交/发布/子agent。完整common套件内既有配置测试仍仅创建和关闭自己的随机loopback测试ApplicationContext，未接管实际服务。没有将本作者结果充作独立复审通过，I1复审待root交回原reviewer；生产门禁仍 **BLOCKED_IAM_SCOPE_ACCEPTANCE**。
