# MNT-08 AP 纯规则子步：能力包取值规则从应用层抽到领域

- 卡片：MNT-08「代码结构收敛」，AP 纯规则子步（AP 侧，双线方案 §4.1 席位 A 首项）
- 分支：`upgrade/20261103`，基线提交 `5da6bd99`
- 日期：2026-11-03
- 结论：**功能完成待统一验收**（按总入口 §7 状态机；本子步不授权独立验收）

## 1. 基线与原责任

`app-management/src/main/java/cn/agentplatform/app/domain/CapabilityBinding.java` 是能力包"声明 vs 已发布契约"
的纯比较规则，但反向 `import cn.agentplatform.app.application.AppService`，借用其静态取值工具
`text/object/objects`（21 处调用：text×3、object×11、objects×7）。后果是纯规则无法脱离应用服务理解与测试，
领域层出现 application 依赖。审计命令（基线）：

```
grep -r "import cn\.agentplatform\.app\.\(application\|infra\)" app-management/src/main/java/cn/agentplatform/app/domain
→ CapabilityBinding.java:3: import cn.agentplatform.app.application.AppService;   （domain 下唯一一处）
```

这些静态工具的实际规则（基线 `AppService.java:155-173`）：

| 入口 | 规则 | 失败时 |
| --- | --- | --- |
| `text(body, field)` | `Objects.toString(body.get(field), "")`；空白或长度 >128 拒绝 | 400 `INVALID_INPUT` `缺少或无效字段: <field>` |
| `object(v)` | 必须是 `Map` | 400 `INVALID_INPUT` `必须提供JSON对象` |
| `objects(v)` | `null` → 空数组；必须是 `List` 且每个元素都是 `Map` | 400 `INVALID_INPUT` `必须提供对象数组` |

## 2. 新责任划分

| 位置 | 责任 |
| --- | --- |
| `app-management/src/main/java/cn/agentplatform/app/domain/CapabilityValues.java`（新增，41 行） | 以上三条取值/校验规则的唯一实现，只依赖 `admin-common` 的 `PlatformException`，不读存储、不碰应用服务 |
| `app-management/src/main/java/cn/agentplatform/app/domain/CapabilityBinding.java` | 21 处调用改指 `CapabilityValues`，删除 `import ...application.AppService` |
| `app-management/src/main/java/cn/agentplatform/app/application/AppService.java` | `text/object/objects` 保留为公开兼容入口，方法体改为委托 `CapabilityValues`；`error/number` 等其余工具未动 |

依赖方向由 `domain → application` 变为 `application → domain`，符合 api/application/domain/infra 分层。

## 3. 不变量（逐条对齐卡片）

- 错误码、状态码、提示文案、缺省值逐字未变：`INVALID_INPUT` / 400 / `缺少或无效字段: …` / `必须提供JSON对象` / `必须提供对象数组`；`null` 数组仍按空数组处理。
- 请求/响应形状、字段顺序、JSON 数值与 Unicode 编码未变：本子步不触及序列化、摘要计算与字段组装。
- 摘要语义未变：`candidateSha256` / `semanticDigest`（`AppDigest`）与本次改动无交集，未重新计算任何黄金向量。
- 既有公开兼容方法与 Spring 消费者仍可用：`AppService.text/object/objects` 签名与可见性保持 public static。
- 无 DB 迁移、无公开 API 新增、无框架升级、无新依赖。

## 4. 测试（两轮，均在同一份断言上）

第一轮——固定既有行为（改代码之前，基线 `5da6bd99`）：

```
JAVA_HOME=<jdk-21>
mvn -q -pl app-management -am -Dtest=CapabilityValuesTest -Dsurefire.failIfNoSpecifiedTests=false test
→ Tests run: 5, Failures: 0, Errors: 0, Skipped: 0（0.064s）
```

第二轮——改动后复跑同一断言，并补一条"领域入口与兼容入口同义"的用例，再跑整个 domain 包：

```
mvn -q -pl app-management -am -Dtest=CapabilityValuesTest ... test
→ Tests run: 6, Failures: 0, Errors: 0, Skipped: 0（0.062s）

mvn -q -pl app-management -am -Dtest='cn.agentplatform.app.domain.*Test' ... test
→ AppDigestTest 6 / AppRulesTest 7 / AppRulesUnionTypeTest 10 / CapabilityValuesTest 6 / IntegrationRulesTest 2
  合计 31，Failures 0，Errors 0
```

新增测试文件 `app-management/src/test/java/cn/agentplatform/app/domain/CapabilityValuesTest.java`（6 例）：
覆盖空白/超长文本、非对象、非数组与含非法元素数组、`plan()` 的字段白名单与数组校验、`select()` 的
environment 前置拒绝，以及"领域入口 == 兼容入口"。测试为纯 JUnit5，**不启动 Spring、不连数据库**
（满足卡片"纯规则测试不需启动应用服务/数据库"）。

## 5. 未覆盖 / 有意限制

- `CapabilityPackageBindingTest`（243 行）需要真实 app PG 与签名 HTTP 对端，本机无 `AGENT_DB_PASSWORD`，
  按 `@EnabledIfEnvironmentVariable` 跳过；其覆盖的发布/绑定主链路未在本地重跑，**留给统一验收与回归手册**。
- OR 侧同卡要求（`GlobalAssetSnapshots` 不再依赖 `AuthService`/infra codec）不在本子步，属 O 席位。
- 未做 `controller → service` 的 `Objects.toString` 全局替换：卡片只认领上述三个文件，扩大范围会触碰未经授权的调用点。
- MNT-06 / INT-03 / SEM-02.2 以本子步为前置，未验证其后续接线。

## 6. 提交与回退

- 单次提交（AP 仓），文件清单：`CapabilityValues.java`（新增）、`CapabilityBinding.java`、`AppService.java`、
  `CapabilityValuesTest.java`（新增）、本证据文件。未使用 `git add -A`，未推送、未打标签、未切分支。
- 回退：`git revert <commit>` 即可整体复原；`CapabilityValues` 为纯新增类，删除不影响其他文件。
