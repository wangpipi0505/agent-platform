# R-41 门户主体代查开发证据

保留 USER_MGMT 命名查询。authority 在正常部署中启动时校验独立代查引用。仅显式启用 `test` 或 `dev` 的进程可以在缺少引用时启动，换票仍返回 503。会话签发关闭时，代查按目录返回的当前状态决定准入。签发已开启而代查凭据缺失时，启动记录告警。

## 命令和结果

使用 JDK 21。从仓库根目录运行下列 PowerShell 命令。需要进行真实目录联调时，从本地测试环境注入 `AGENT_R38_LIVE_SUBJECT_KEY`，并启动绑定不同测试数据库的 18072 和 18073 实例。

```powershell
mvn -B -ntp -pl authority-service -am '-Dtest=SubjectLookupPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
$selection = (Get-ChildItem -LiteralPath 'authority-service/src/test/java' -Recurse -Filter '*Test.java' |
    ForEach-Object BaseName) -join ','
mvn -B -ntp -pl authority-service -am "-Dtest=$selection" '-Dsurefire.failIfNoSpecifiedTests=false' test
docker compose -f deploy/compose-test.yaml config --no-interpolate --quiet
git diff --check
```

- 原实现的三个用例分别复现缺引用仍启动、换票代查未拒绝和关闭会话签发后有效主体被拒绝。修复后覆盖默认与混合 profile、启动告警、可用主体和停用主体。
- authority-service 全模块 147 例通过。包含两套真实目录的有效主体、跨租户和缺失主体校验。Compose 配置解析及差异检查通过。
- reactor 全量运行在 `admin-common` 阶段报两处失败和一处错误。对照 `73b6c0e8` 确认该模块和被扫描模块未改动，并独立复跑 `AuthorityPolicyTest`、`LocalRunAdvanceAuthorityTest`、`ServiceTokensDeploymentModeTest`，三处结果一致。
- [测试输出摘录](maven-result.txt)。本地原始输出位于同目录的 `raw-01-before.log`、`raw-05-authority-live.log` 和 `raw-06-admin-baseline.log`。

E2 部署验证待执行。编排的代查配置见 [USER_MGMT 主体查询契约](../../../contracts/user-mgmt-subject-lookup.md)。
