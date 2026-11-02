# model-gateway 与 monitor 全量基线（2026-11-02）

## 1. 为什么跑

台账 §14.7 的模块级对照缺 `monitor` 与 `model-gateway` 两个模块的全量数据（这两项是
DoD ② 的缺口）。本批补齐基线数字，作为后续验收与回归的对照物，**不声称全绿**。

## 2. 命令

```bash
source .tools/env-load.sh
mvn -B -ntp -pl model-gateway,monitor test \
    -Dmaven.repo.local=$PWD/.tools/m2 \
    -DargLine="-Xmx1024m -Djdk.net.unixdomain.tmpdir=V:/.local/tmp" \
    -Dsurefire.timeout=900 -Dmaven.test.failure.ignore=true
```

## 3. 结果

| 模块 | tests | Failures | Errors | Skipped | 坏类 |
| --- | --- | --- | --- | --- | --- |
| model-gateway | 176 | 7 | 84 | 0 | 15 |
| monitor | 68 | 0 | 3 | 0 | 1 |

monitor 唯一的坏类是 `DatasetHttpTest`（3 例 `IllegalStateException: SECURITY_MODE_REQUIRED`），
与 2026-10-27 基线同因同数（Y-4 测试基座债务），本次未新增。

model-gateway 的 15 个坏类集中在“冻结输入/流式事实”一组（`Frozen*Test`、`Initial*Test`、
`EngineModelInputManifestClientHttpTest`），错误形态是 `NullPointerException` 与
`ApplicationContext` 起不来——属测试需要的外部夹具/环境变量缺失，与本次 EX-43 改动无关。

## 4. 与 iam 改动的关系

本批新增的 `ServiceTrustChainConfiguration` 带 `@ConditionalOnProperty(agent.security.mode=iam)`，
测试环境不设该属性 ⇒ 不激活；两个 `application.yml` 只多了 `agent.security` 的默认占位
（`${AGENT_SECURITY_MODE:}` 等），未改变既有默认。故上面两组失败**不是**本批引入。

## 5. 结论与局限

- 两模块的全量基线已补齐，但**都不绿**；后续改动只要不新增坏类名、不改变既有坏类计数即可，
  真正清零属 §14 统一验收与 G-8 测试基座债务的范围。
- 未做：失败逐条归桶（model 的 84 个 error 需要单独一轮按夹具分类）、与 10-27 基线逐类对照
  （monitor 已对照一致，model 无 10-27 全量数据可比）。
