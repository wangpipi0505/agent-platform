# G6 Task2 独立审查 I1/I2/I3 集中修复

2026-10-20；唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`。依据 root 接受的 [review-iam-stage-2.md](review-iam-stage-2.md) 三项 P2，实施最小集中修复，待同一 reviewer 独立复审。本轮未执行 Task3，生产仍 **BLOCKED_IAM_SCOPE_ACCEPTANCE**。

## 起步与范围

先只读完整审查报告、原 [probe-run2.log](review-iam-stage-2/probe-run2.log)、当前配置/mapper/decoder及覆盖测试；使用 receiving-code-review、既有TDD/ponytail/verification-before-completion流程。未重新运行原独立probe、未寻找栈阈值、未重放旧schema耗尽探针。

[setup.py](iam-stage-2-review-fixes/setup.py) 在写源码前校验并复制完整common **34** 文件src/POM至 [baseline](iam-stage-2-review-fixes/baseline/)，[baseline-sha256.json](iam-stage-2-review-fixes/baseline-sha256.json) 精确匹配原Task2 `source-after.json`；初始 **53** 个target输出亦匹配原 `target-sha256.json`，保存在 [baseline-target-sha256.json](iam-stage-2-review-fixes/baseline-target-sha256.json)。这不是Task1旧55版本。

仅修改三个生产类 `AuthorityProperties`、`PortalClaimMapper`、`TrustedJwtDecoder`，以及 `AuthorityConfigurationTest`、`PortalClaimMapperTest`、`TrustedJwtDecoderTest`。没有修改Actor、SecurityConfiguration、BoundedIamHttp、ServiceTokens、ClaimCeiling、路由目录、业务服务/POM/迁移/前端/部署/原契约/台账。

## 三项修复及验证映射

| 审查项 | 最小实现 | 回归覆盖 |
| --- | --- | --- |
| I1：合法业务域角色被硬编码三个名称排除 | 删除ADMIN/EDITOR/APPROVER固定全集判断。配置中的业务角色允许任意明确登记的非null、非空白字符串，保留精确字面值不trim/补角色；SERVICE/GATEWAY_AUTH仍禁止。原已签角色∩配置上限的映射不变 | `configuredDomainBusinessRolesAreAcceptedAndReservedRolesRemainForbidden` 接受ops/VIEWER/ops+EDITOR、拒绝SERVICE/GATEWAY_AUTH/空串/空白；`signedConfiguredDomainRolesArePreservedWithoutAdminSubstitution` 使用实际RS256签名验证：配置ops/VIEWER只保留二者，ADMIN/保留角色/未配置角色均删除；同票据在未配置ops/VIEWER的原上限下得到空角色。没有改授ADMIN来替代ops |
| I2：合法scope长度内递归正则栈溢出 | `scopes` 改为一次迭代字符扫描与分隔，不使用递归正则；只接受原ASCII `[A-Za-z0-9:._-]` token及精确单空格规则，空/缺scope仍为空集合。没有新上限、扩大线程栈、catch Error或新增依赖；JWT16384上限不变 | `iterativeScopeParsingPreservesAsciiTokensAndExactSpaceRules` 保留合法字符与去重，拒绝前/后/双空格、tab/newline、非ASCII、斜线/逗号和错误类型；`signedFixed2500ShortScopesWithinJwtLimitProduceTheConfiguredIntersection` 固定2500个a、4999字符实签token，断言≤16384且未配置a时正常空交集；再加platform.access的同量级票据只保留该允许scope |
| I3：已验动作/scope不足误分类401 | 从claims validator移出登记动作/范围判断，在 `selected.decode(token)` **成功返回后**才调用 `requireServiceScope`。原Nimbus密码学、时间、用途/aud、必需字段/类型、client/caller/target、resource三字段、delegated/workload互斥等验证全部先完成；之后未登记动作或scope不足抛PlatformException(403,SYS-AUTH-403)，沿现有filter返回 | `signedUnregisteredActionAndInsufficientScopeAreForbiddenAfterIdentityValidation` 通过真实随机loopback HTTP链验证未登记business.delete与不覆盖的scope均403、错误RSA/错use/错client/缺resource仍401，JWKS503仍503；所有请求handler累计0。原内部矩阵仅将合法结构的action/scope不足改断言403，身份/结构反例仍JwtException |

I3不是把未验claims的BadJwt预检查改成403。原始payload类型/JOSE检查仍是401；新授权判断入口在Nimbus+所有Spring validators之后。即使action/scope本身拟被拒，错误签名或缺resource仍先返回401；网络不可用仍先返回503。

原生产IAM静态BeanFactoryPostProcessor监听前闭锁、可信内部HTTP执行闭锁、workload不能映射Actor、Task1 null/blank归属P2、dataLabels义务并集、region与角色交集及所有旧输入边界均保持。完整common包含Policy14回归；没有将类record或token验签视为在线current authority。

## 真实行为红与原始命令

所有Maven命令从新工程根执行，显式Java21与工程离线仓库；V:仍仅映射该根，用V:/.local/tmp解决本机Unix-domain测试临时路径。未读取.env或任何真实凭据。命令前缀：

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
```

先添加I1/I3三个命名测试，原生产源码未变：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test '-Dmaven.repo.local=.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' '-Dtest=AuthorityConfigurationTest#configuredDomainBusinessRolesAreAcceptedAndReservedRolesRemainForbidden,PortalClaimMapperTest#signedConfiguredDomainRolesArePreservedWithoutAdminSubstitution,TrustedJwtDecoderTest#signedUnregisteredActionAndInsufficientScopeAreForbiddenAfterIdentityValidation' '-Dsurefire.failIfNoSpecifiedTests=false'
```

- 首次原输出保存在 [red-first-attempt.log](iam-stage-2-review-fixes/red-first-attempt.log)：3项，2 failures、1 error；mapper样本在构造decoder时得到真实PORTAL_CEILING_INVALID，属于I1行为异常，不是编译/环境失败。为把该行为写成明确断言，在构造前增加`assertThatCode(config.validate).doesNotThrowAnyException`；没有修改生产源码或放宽测试目标。
- 同命令再次执行的 [i1-i3-behavior-red.log](iam-stage-2-review-fixes/i1-i3-behavior-red.log) 为 **3 tests / 3 assertion failures / 0 errors/skips**：ops配置被拒、实际签名流程配置失败、两个已验HTTP拒绝分支都实际401而非403。源码及原XML在 [red-seam](iam-stage-2-review-fixes/red-seam/)，不是用缺类/编译失败冒充关键红。
- I2直接引用原独立审查已保存的 **JWT7510字符、scope4999字符→StackOverflowError**：[probe-run2.log](review-iam-stage-2/probe-run2.log)。本轮没有再执行旧实现上的该样本，也不宣称新跑了一次旧红。完成线性扫描后才运行固定2500个scope的有限实签回归，未查找阈值。

实施三处生产修复并加入I2正常/非法语法样本后，执行规定三类完整定向选择器：

```powershell
mvn.cmd -o -B -ntp -pl admin-common -am test '-Dmaven.repo.local=.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp' '-Dtest=TrustedJwtDecoderTest,PortalClaimMapperTest,AuthorityConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

[targeted-green.log](iam-stage-2-review-fixes/targeted-green.log)：**58/58，0 failures/errors/skips，BUILD SUCCESS**；Configuration40、Mapper6、Trusted12。I1/I3三项原失败均包含在内；I2固定样本在正常签名/映射路径完成，没有catch Error。

## 冻结源码唯一一次完整 common

```powershell
python docs/evidence/iam-stage-2-review-fixes/freeze.py freeze
mvn.cmd -o -B -ntp -pl admin-common -am test '-Dmaven.repo.local=.tools/m2' '-Djdk.net.unixdomain.tmpdir=V:/.local/tmp'
python docs/evidence/iam-stage-2-review-fixes/freeze.py verify
```

[full-common.log](iam-stage-2-review-fixes/full-common.log)、[完整XML](iam-stage-2-review-fixes/full-surefire/)、[汇总](iam-stage-2-review-fixes/full-test-summary.json)：**82/82，0 failures/errors/skips，BUILD SUCCESS**。Configuration40+Policy14+Mapper6+Trusted12+Common4/Schema5/Vault1。相比原77增加五个测试方法；旧动作/scope断言仅按已接受错误语义改为403，其余反例保持。

本轮仅这一次完整common，不重复旧77或七模块。负向配置启动测试的预期ERROR堆栈不是Surefire错误。HTTP测试只创建并关闭自身随机loopback测试context/issuer，没有启动、关闭或替换六个实际服务/JVM，没有package或更新原固定962 JAR。

## 完整补丁、SHA及实际重建

- [changes.json](iam-stage-2-review-fixes/changes.json)：六文件改动及逐项before/after SHA。完整src/POM [baseline](iam-stage-2-review-fixes/baseline/)、[frozen](iam-stage-2-review-fixes/frozen/) 均34文件；[source-before.json](iam-stage-2-review-fixes/source-before.json) 与 [source-after.json](iam-stage-2-review-fixes/source-after.json) 对应完整回归前后冻结源码，全部相同。
- [full.patch](iam-stage-2-review-fixes/full.patch) 为本轮六文件完整可应用unified diff，SHA256 **df1aad38462d57a0e9ccbca5cc86f85445861b01372f2950486b79fb98c61abb**。从原baseline/frozen字节生成，不依赖不存在的HEAD，不是空diff。
- freeze内部真实执行下列命令，返回0；[actual-apply.log](iam-stage-2-review-fixes/actual-apply.log)、[patch-apply-verification.json](iam-stage-2-review-fixes/patch-apply-verification.json) 与 [applied-bytes](iam-stage-2-review-fixes/applied-bytes/)证明**全部34文件逐字节等于frozen和当前源码**：

```powershell
git -c core.autocrlf=false apply --verbose --whitespace=nowarn --directory=docs/evidence/iam-stage-2-review-fixes/applied-bytes docs/evidence/iam-stage-2-review-fixes/full.patch
```

已应用目录不可重复apply；`freeze.py verify`只读核对当前源码并保存本轮XML/target摘要。[target-sha256.json](iam-stage-2-review-fixes/target-sha256.json)记录当前common classes/test-classes **53**输出，交回root与同一reviewer复审。

[prior-evidence-before.json](iam-stage-2-review-fixes/prior-evidence-before.json)/[after](iam-stage-2-review-fixes/prior-evidence-after.json)覆盖 **591** 个Task1/原Task2/全部旧review与probe证据文件，全部SHA前后相等，含原Task2报告/full.patch/manifest/原77日志、历史红/失败apply和本次独立probe原字节。没有覆盖、修订或把旧Needs fixes报告改写为Approved；本轮报告仅描述作者修复结果。

[manifest.json](iam-stage-2-review-fixes/manifest.json)含本报告及本轮全部证据SHA，清单自身不自包含。生成命令：

```powershell
python docs/evidence/iam-stage-2-review-fixes/freeze.py manifest
```

## 未改变的限制

本轮不新增权限角色全集，不扩大scope合法字符/空格语法，不放宽16384字节上限；不扩大线程栈、不catch Error、不增加依赖。I3只修错误分类与验证顺序，不能授权任何尚缺current authority/具体资源守卫的内部业务执行，HTTP handler保持0。

没有读取相邻旧工程、.env/vault/masterkey/真实凭据，没有实际IAM或生产端点操作，没有业务/POM/迁移/前端/部署/提交/发布/其他smoke/三外部台账修改，没有派子agent。后续Task3–7及真实IAM profile/scope签收仍未完成；**BLOCKED_IAM_SCOPE_ACCEPTANCE**继续保持。源码与target冻结后交还root，不把作者绿色测试当作独立复审通过。
