# EX-03 submitted协议替身最小修正（未重跑）

主代理在核实首轮Target唯一异常后，明确授权仅修测试 `executeFixture`，产品两文件继续冻结。此次Java最小delta只有一条准确 `/submitted` stub及一条解释不变量的注释：

```java
// 提交权确认先于真实HTTP；仅替身准确协议响应，生产执行链仍检查submitted标志。
when(clients.post(eq(endpoint + "/submitted"), anyMap()))
        .thenReturn(Json.tree(Map.of("submitted", true)));
```

依据既有 `skill-registry/src/test/java/cn/agentplatform/skill/application/SkillExecutionTest.java:162–164` 的同一 `submitted=true` 标准响应。只读检查真实只读SYNC路径：registration/get、claim、submitted、fact分别为独立准确URL；无副作用不进入approval分支。本卡继续使用真实SkillExecution与HTTP适配器，新增stub不替代默认所有请求成功，也不删除生产 `submitted` 判断、放宽claim或发布门。

测试SHA从首轮Target实跑的 `4D77010C0AEC2137C6500FBA62227B0C23FAD8413C224E8018F8CA80C6477CDF` 变为 `97D0A6B1B48ECD833568C9E3F584303FEEF6D2A094BDAE6979AEC36353432051`。18方法身份、已有所有断言保持。`git diff --check`静态通过；**未编译或重跑，不能宣称PG/header链通过**。

采集器仅更新冻结测试SHA，语义与拒绝门保持，当前 [run-verify.ps1](run-verify.ps1) SHA为 `953A539B3CDDD831077C013AE74DAA87ED47D475E7C1D861D4F309BC29D3DE5A`。PS7与实际系统PS5.1静态ParseFile仍0错误，分别见 [PS7](collector-parse-ack-current.json)、[PS5.1](collector-parse-ack-windows51.json)；原始D204版静态检查与首轮Target档案不覆写。

产品SHA保持：Importer `41450FAAC9141E0E3393D7A9ED45B5E5F67E7D09DA787F448DDBE86565F24CA5`，Catalog `7C98CAE7587DA4F64BF70E93E9540A16F097BFF885DE7EA158E147FE8D491B03`。未运行Java/Maven/PG/HTTP，未改其它源码、主入口/index，未add/commit。旧 [Target 29P/1E](target-first-attempt.md) 保留，仍不放行；下一实际窗由主代理另授。
