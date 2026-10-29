# EX-04 旧手工技能保存回归与最小修复独立复核

2026-10-29（Asia/Shanghai），审阅者 `/root/ex04_test_gate`。只读本次 Full XML、旧方法、保存的修复前原件与 Catalog 修复差异；本轮仅新增本文件，未改源码、测试、index 或旧证据，未执行产品/仓库入口、Maven/Java/PG/HTTP，未读取环境/凭据，未使用 Superpower。

**结论：8 个新增 ERROR 由同一 Catalog 兼容缺陷造成，当前 Map guard 修复范围准确，未放宽输入或断言。准入重新冻结后的 green-skill33 与随后完整 skill689 各一次；这不是绿色或整卡签收。原 `full-skill-20261029-1102` 红完整保留。**

## 实际失败与根因

原 Full 收集71 XML、689身份齐，结果565P/6F/114E/4S；8个原 passed 方法变为 error，methodRegressionGatePassed=false。新增分布为 SkillEvidenceRebindPostgresTest 五项、SkillExecutionTest 两项、SkillPublishDigestPostgresTest 一项，均为同一 `PlatformException: 字段必须为JSON对象`，栈一致到 `SkillCatalog.save:365 → object:156`。

该轮 Catalog 原始 SHA 为 `f4557381e94ab15b94e42bccee7a1f7065da71d639ffc3da2a028e5c6241754e`。新增 projected 判断在 previous 不含 exposure 时，无条件执行 `object(previous.get("importSource"))`；旧手工定义没有 importSource，严格 object(null) 因而在保存真正 normalize/写入之前抛400。八个方法均调用这种旧保存路径，尚未到各自原验证目标，不能称审批/摘要/证据重绑发生八种独立缺陷。三旧 Test 源未改变，不应修改它们来绕开保存。

此实际 Full 红纠正了先前窄审对旧保存兼容的无阻断判断；我当时漏核了严格 object helper 对缺键的要求。原候选审阅保留，不能用定向33绿色替代旧手工保存的完整回归。

## 修复 delta 与身份

当前 Catalog SHA 为 **`c9c3a0b6e69a76cd49939f639ac0b7cf014e67fedb9dd86311c807199abc47fd`**。保存于 `legacy-save-repair/SkillCatalog.before-repair.java.txt` 的旧源与上述 Full 原 pin 一致。相对它的唯一行为修复为：

```java
previous.containsKey("exposure")
        || (previous.get("importSource") instanceof Map<?, ?> source
                && "ONTOLOGY".equals(source.get("kind")))
```

缺来源时只得出“未声明投影来源”，不再把缺键交给严格对象解析；已带 exposure 或 ONTOLOGY 的旧值仍保回省略的 CONFIRM_REQUIRED。旧无来源、无 exposure 的手工风险推导没有被改成统一保回高风险。

这不是放宽来源输入：previous 有 importSource 时，前段仍禁止编辑替换并在省略时补回；显式 null/非对象在后续完整 normalize 的来源对象入口仍拒绝，且 normalize 仍先于数据库 update/insert。新 body 的 exposure/pagination 显式 null、来源风险降低、分页类型/未知键也继续走原纯规则拒绝。全局 object()、normalize() 和共享投影规则未放宽。

逐 hunk 阅读确认其余差异仅撤回 formatter 引起的换行、缩进及字符串常量分段；SQL 拼接内容保持原值，审批、超时、重绑等逻辑不变。当前 Catalog 相对 HEAD 只净新增27行、0删除，对应保存声明保护、实际 registry 委托与 normalize 共用入口，不留无关格式修改。

四个授权 Test 当前 SHA 仍分别为 `4b2573fa159e250b00e6dd19aaf990299abb6c204f1e880285108b9b9f533bc4`、`ef0d3b60c0846d79744b5b18c9b57538d2c0c348aac6599e15cdcb3e2bf99b52`、`666c61bb5e46290be05921a6f6bdc1ff171bb509fe206adf247b0ea20b8a1b51`、`53d4c07f97011f0f4a364044419fdd0375622353751755f67b8ff2db854c6afc`。未新增/删除方法，预期33/689身份数不变，不扩三旧 Test。

## 后置验收

重新冻结 c9c3a0b6 的模块源与当窗准入后，先验证同一33完整断言仍绿，再运行完整689。必须实际确认八个原方法恢复其原状态、原116非通过原因保留、新增非通过/缺失/状态差异为零，SDK五方法/四安全 outcome/零诊断与原4旧skip仍在；不能据已定位单因而预先宣称八项恢复。

`full-skill-20261029-1102` 的 native0/failure.ignore=true 只是采集返回，原失败门不变；不得覆盖该轮或把它记为通过。后续 engine932 与全卡终审仍另行按唯一实际窗口和原因门执行。
