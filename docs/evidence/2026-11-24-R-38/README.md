# R-38 USER_MGMT 真实来源验证

用户指定外部项目 `D:/00-Work/01-Project/user-mgmt-system`。核对 README、`SysApiKeyController` 与 `SysApiKeyServiceImpl.identityScope`。按部署项目既有方式启动后端 18070 和独立 Redis 16379。未改外部项目源码。

两套新建 MySQL 测试库分别由 18072 与 18073 提供 HTTP 查询，各初始化 6 个种子账号。authority 测试把两套目录分别绑定到不同平台测试租户，用同名主体证明租户绑定与跨租户拒绝。

| 检查 | 观察 |
| --- | --- |
| 本人及指定用户查询 | HTTP 200，body code 200，可用状态字符串 0 |
| 返回字段 | userName、nickName、status、deptId、deptName、roles，无租户字段 |
| 本地已有停用或缺失账号 | HTTP 200，body 非成功；authority 用同一 401 拒绝 |
| 两套真实目录适配 | 匹配租户查询成功，跨租户与缺失主体拒绝 |
| 用户列表接口 | 两实例的 `/system/user/list?userName=operator&pageNum=1&pageSize=5` 均返回 2 行与展示所需字段 |

JDK 21 定向回归共 22 例，零失败、零错误、零跳过：

```text
mvn -B -ntp -pl authority-service -am -Dtest=SessionEndpointTest,IdentityProfileSessionTest,DelegatedSubjectTokenTest,DelegatedForwardContractTest,DelegatedDisabledTest -Dsurefire.failIfNoSpecifiedTests=false test
```

进程以环境引用传入隔离目录的测试密钥。红灯基线中已签门户票在用户停用后仍能换票，期望 401，实际 200。回归验证已签票下一次委派查询重新读当前可用状态。真实环境需接入有 `system:apikey:identity` 权限的凭据引用 `AGENT_AUTHORITY_IDENTITY_SUBJECT_KEY_REF`。平台角色交叉验收随 R-39。
