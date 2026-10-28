# EX-27 唯一实际诊断记录与边界

对象：[in-flight-diagnostic-once-20261028-223443-168](runs/in-flight-diagnostic-once-20261028-223443-168/run.json)。实际运行22:34:43至22:34:59（+08），HEAD=`0cc967278007343bd5ab09faf9953d86cd573bf8`；测试SHA256=`4470434C459F900B9CC85AD96D4F7D8953A17D7BCC7B37C3EAE64FBBD9D5C53D`，采集器SHA256=`D7FADEBBF77BC37B706A924B8BB8F563099E9CA939C84C8992018E5BD6488F94`。编译完成后准确方法FAIL，不是编译失败。

## 命令、真实退出与采集

由root授唯一短窗，以独立PowerShell5.1进程执行`run-diagnostic.ps1 -Phase in-flight-diagnostic-once`，只选`PortalRunEventsSseLifecycleTest#forcedConnectorReleaseInvalidatesTokensWithoutInventingCompletion`，`-DreuseForks=false`，显式`-Dex27.forcedReleaseInFlight=true`，其余.env/argLine沿既有条件。精确命令保存在run.json；.env读取但不打印，SHA前后一致。脚本进程退出，不污染root环境。

Maven实际exit1立即落execution.json，子JVM实际exit1、pid49452，alive=false、collectorAlive=false。1 XML/1 FAIL、0 ERROR/skip；准确方法和内部mode均命中。XML采集使用`XmlDocument.Load(绝对路径)`，避免PS5默认ANSI解码UTF8中文；collectionErrors为空，native exit在XML采集前已保存。原日志及XML为忽略原件，未打印敏感异常消息或头。运行后明确将实际窗口归还root/EX-01，此后未追加Java/Maven/本地HTTP/PG。

## 阶段事实

事实来自同次安全JSON的事件序号/相对nano值及[安全汇总](runs/in-flight-diagnostic-once-20261028-223443-168/facts-summary.json)。各getter依次读取，不声称跨字段原子快照。

| 阶段 | 实际观测 |
| --- | --- |
| P1 | 两个不同socket（identity 1271105209、1019663371）；各callback持真实ReentrantLock，currentProcessor为空、closed=false。 |
| P2 | 两个外部closer不持socket锁，真实裸close均正常返回，closed=true，指针仍空；没有加锁close或手工recycle。 |
| P2b | 两close正常返回后先发resume，两个callback暂停均返回；随后取得两个processing-exit-fence，closed=true且processor已关联。人工暂停没有贯穿原5秒。 |
| 原5秒 | 等待约5005.1362ms，hook=0、实际recycles=0、waiting=2。第一个entered断言FAIL；外层child退出断言也FAIL，不是recycles值1。 |
| Tomcat.stop前 | hook=0、实际recycles=0、waiting=2；没有把清理事件算进原窗口。 |

两个安全phase序列均止于首次ready-false，没有recycle-invalidated、completion-entry、before-close-now或worker-body-exit，见[phase计数/顺序](runs/in-flight-diagnostic-once-20261028-223443-168/trace-phase-facts.json)。没有CLOSE_NOW只因后续旧Handle.close流程尚未执行，不能宣称迟到动作已验证。不到7秒的观察也未验证原15秒idle/55秒hard期限之后的实际退出。

## 解释与决策

捕获的是“处理中关联暂空，off-lock close已返回，处理退出后重新关联，原窗口没有进入回收”。EX-35历史新增FAIL没有同样的指针/closer事实，只能记与此解释相容，不能记唯一同因。也没有证明实际recycle已经发生却失效失败。

[fixes brief](../iam-stage-4-o2b-f2c2a-fixes-brief.md)规定回收前失效，以及无真实completion时保留两个Owner至JVM退出；[签收范围](../../open-source-references.md)未承诺全container/channel/TLS/native同步join。waiting=2是实际保留事实，不能单独推为不受费泄漏；Owner计费也不能豁免跨socket/epoch动作。具体边界已经[独立技术门](technical-review.md)核对。

root批准只修正例测试前提并新增默认恶劣关闭安全向量，所有src/main只读。原447诊断红和EX-35历史FAIL均保留。第二阶段FD34经静态确认的ping夹具修正后为E175，二者均无编译/测试事实；新增向量若失败，必须按实际缺口区分fixture/API使用错误与产品义务，不能改断言凑绿。最终仍须准确方法、默认完整38方法、gateway27类逐方法结果及独立验收闭环。

