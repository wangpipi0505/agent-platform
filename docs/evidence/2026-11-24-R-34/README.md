# R-34 合入复核

2026-11-24 在升级工作树用合并提交 `dd182bcb` 将 `r34-gateway-v1@934e1476` 合入 `upgrade/20261103`。

使用 JDK 21 执行：

```text
mvn -B -ntp -pl platform-gateway -am -Dtest=RoutesTest,GatewayIamPortalHttpTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`RoutesTest` 21 例，`GatewayIamPortalHttpTest` 17 例，零失败、零错误、零跳过。两处 `addUrlPatterns` 与 `portalSurface` 均覆盖 `/v1/apps/`。门户票 403 基线仍可复现，200 翻转由 R-35 接续。

原始 Surefire 文本及 Maven 测试汇总见同目录文件。
