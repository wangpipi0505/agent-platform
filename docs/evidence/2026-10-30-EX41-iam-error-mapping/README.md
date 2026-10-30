# EX-41（M2 第一步）：IAM 判权拒绝不再折成依赖故障

## 1. 问题

`admin-common/src/main/java/cn/agentplatform/common/authority/BoundedIamHttp.java` 原先在响应头回调里写：

```java
if(info.statusCode()!=200 || /* content-length 超限 */) subscriber.reject();
```

而 `reject()` 恒完成 `unavailable()` = `503 IAM_AUTHORITY_UNAVAILABLE`。后果：

1. authority 服务给出的 **403 判权否决**在调用方看来是“依赖服务暂不可用”，会被当成可重试故障（`SkillClients` 的 502 折叠是同一类问题的下游表现）。
2. 错误面丢语义：`HttpCurrentAuthorityClient` 里 `catch (PlatformException denied) { throw denied; }` 这类分支永远接不到真实状态码。
3. 与 `Y-3`/`Y-8` 同族：**否定回答与传输故障不可区分**。

## 2. 改动

| 文件 | 改动 |
| --- | --- |
| `admin-common/src/main/java/cn/agentplatform/common/authority/BoundedIamHttp.java` | 新增 `rejected(int status)`：400/401/403/409 按原状态码与稳定错误码透出，其余（含 5xx）仍 `503 IAM_AUTHORITY_UNAVAILABLE`；`LimitedBody.reject` 改为接收异常；`ExecutionException` 解包后再上抛，避免刚映射好的结论又被折回 503。**响应体仍不读取**，错误消息不含服务端自由文本。 |
| `admin-common/src/test/java/cn/agentplatform/common/authority/BoundedIamHttpErrorTest.java` | 新增 7 例：400/401/403/409 各一、500 仍 503、网络不可达仍 503、200 正常返回字节；每个“拒绝”用例同时断言响应体里的 `SECRET-INTERNAL`/中文细节**不出现**在消息里。 |

错误码对照：

| 上游 | 平台侧 | 说明 |
| --- | --- | --- |
| 400 | `400 IAM_AUTHORITY_REJECTED` | 请求本身不合法 |
| 401 | `401 IAM_AUTHORITY_UNAUTHORIZED` | 调用方未被认证（凭据/证书问题） |
| 403 | `403 IAM_AUTHORITY_FORBIDDEN` | 判权否决（确定性，不该重试） |
| 409 | `409 IAM_AUTHORITY_CONFLICT` | 状态冲突（重复签发/版本竞态） |
| 5xx / 超时 / 断连 | `503 IAM_AUTHORITY_UNAVAILABLE` | 可重试的基础设施问题 |

## 3. 测试

```
mvn -B -ntp -o -Dmaven.repo.local=.tools/m2 -pl admin-common test \
  -Dtest='TrustedJwtDecoderTest,AuthorityConfigurationTest,ServiceAuthorizationTest,BoundedIamHttpErrorTest'
```

```
Tests run: 40, Failures: 0, Errors: 0, Skipped: 0 -- AuthorityConfigurationTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -- BoundedIamHttpErrorTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- ServiceAuthorizationTest
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- TrustedJwtDecoderTest
Tests run: 73, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

红阶段（映射与解包都改之前）：`Tests run: 7, Failures: 4`，失败全部是“期望 400/401/403/409，实际 503”。

## 4. 未覆盖

- authority 服务的 `operation=forward`（M2 的另一半）未做：它需要 authority 侧记住 `authorize` 决策（决策 ID/操作/授权引用），属 EX-42。
- 端到端：本项只到 `BoundedIamHttp` 单元层，未接真实 authority 进程；真实链路在 EX-40（人身份会话）与 EX-42（委派转发）齐备后一并验证。
- 5xx 响应体同样不读取，因此“上游 5xx 的具体原因”仍只体现在状态码；这是有意的边界（避免自由文本进入用户可见消息）。
