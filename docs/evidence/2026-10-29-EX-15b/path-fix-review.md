# EX-15b 父目录段拒绝小步独立复核

2026-10-29；审阅者 `algorithm_interrupt_readonly`。只读当前ProcessAdapter差异、冻结Test、两个采集器及三轮原XML/JSON/日志，唯一新增为本报告。没有运行Java/Maven、Node、PG、HTTP、服务或采集器；没有改产品、测试、旧证据、index或三主入口，未使用Superpower。实际运行均由root执行，本次只重读、解析和比对。

**结论：接受本次父目录段拒绝的最小修复与同两例最终2P，无断言放宽；无此小步实质阻断。** 首轮green01的沙箱临时目录访问失败必须保留，不计产品路径红，也不被green02倒填为通过。本报告不关闭EX-15b，不声明完整files安全/持久链已交付。

## 源码与采集器

| 对象 | SHA256 |
| --- | --- |
| 原ProcessAdapter（red/HEAD中原实现） | `6898320e164be2a254dcf009cfc24ce00be0aa9b63954ffd7b6f9ba26d58a293` |
| 本次ProcessAdapter | `5a0c87fd88eece89c2b5a2f85c95b599c59124468830e5a500c734a935118e80` |
| 三轮同一ProcessFilesTest | `7b363256f026266c5a65d4c85e2beb79d4af3daddc497ac1831e30834868a7d7` |
| [原run-path-red.ps1](run-path-red.ps1) | `cf4850bcd43ee7de5b52d07210b4664eaa86ae6b653083ac3f0a7c96b8232830` |
| [run-path-green.ps1](run-path-green.ps1) | `01a8dbce5f0e49763e4d424d1f026af1a26bae800ba0e61aff578e6530d5e47d` |

实际Git产品差异为10插入/1删除，仅在success的原result/outputSchema校验之后保存原`arrayOrEmpty(files)`结果，逐项检查文本path；将反斜杠转为斜杠后按路径段分割，只有精确`..`段调用既有invalid，仍为502/PROCESS_OUTPUT_INVALID。含连续点的普通文件名如`a..b`不被字符串子串误拒绝。局部files随后用于原Output返回；仍是原arrayOrEmpty深复制出的数组，没有新文件打开、规范化、下载、重试或执行器。

协议/requestId/ok/result、既有outputSchema及warnings/metrics校验保留，无文件files=[]继续走原成功分支。拒绝位于成功Output返回之前，异常仍进入原runOnce finally移除调用/回收本callDir；没有借测试去改失败目录策略或取消机制。JsonNode遍历、get、字符replace及split均为当前依赖/Java21既有API，未引入编译新依赖。

本步只解决文本父目录段：缺失/非文本path仍沿现阶段未实现的字段处理，不因此声称它们合法；绝对/盘符路径、其它字段、符号链接/junction、TOCTOU和真实文件归档仍未实现。该限制与父目录段小步范围一致，不能作为后续文件I/O的完整安全守卫。

绿采集器与原CF485逐行对照，原74行变73行。差异仅为固定Adapter SHA及提示、缺新鲜XML时的红/绿提示，去掉“预期一项assertThrows失败”的分类并改为**同两身份、native0、2P/0F/0E/0S**，以及summary/collector的绿标签。原命令、Test SHA、两方法精确身份、新鲜XML条件、原生退出码先保存、源/HEAD/common/环境摘要前后门、finally环境恢复均未变；没有增加ignore/skip或改测试。对新采集器ParseFile静态解析0错误，本审阅未执行它。

## 实际三轮原件

以下计数和精确停点由本次直接XmlDocument.Load读取原testcase/failure/error/skipped后复算；XML SHA亦重新核对summary，不仅采信计数。

| 实际RunId | Maven/collector | 原两方法结果 | 原XML SHA256 |
| --- | --- | --- | --- |
| [path-red-20261029-01](path-red-20261029-01/summary.json) | 1 / 0 | empty PASS；父路径例FAIL，AssertionFailedError精确`Expected cn.agentplatform.common.PlatformException to be thrown, but nothing was thrown.` | `41d01fd9811accc34fe6011e443481878b23fbea51842cf3074eebf868dce890` |
| [path-green-20261029-01](path-green-20261029-01/summary.json) | 1 / 2 | 两项FAIL、0E/0S；empty为own worker未在期限内发布身份，父路径例为expected PlatformException但收到AssertionError | `027b56aa02fe95c61360bafc49544ec532849919975d41ce7b45e4b95a656a63` |
| [path-green-20261029-02](path-green-20261029-02/summary.json) | 0 / 0 | 同两身份2PASS，0F/0E/0S | `3c8827193c8df5d654b111ea9016d542d304a4ba944e68077334315b271e3214` |

green01的Maven日志实际包含worker在`process-files.identity.tmp → process-files.identity`移动时的AccessDeniedException及WindowsFileCopy.move栈；XML直接表明父测试未取得握手。它使用沙箱`Packages/sandbox…/AC/Temp`，没有到达本次success父目录段拒绝，不是产品修复导致路径行为失败。root保留该轮后，在允许该自有临时目录操作的上下文执行同一命令；green02的java.io.tmpdir回到与red相同的普通用户Temp。这个实际运行上下文差别明确记录，不声称两次green的OS临时目录/权限上下文完全相同，也不篡改green01结果。

三轮Test SHA均7B363，未加Assume、未删改502/PROCESS_OUTPUT_INVALID及finally后置；两次green均为同一5A0C产品/01A8采集器。三轮Maven参数逐项相同、environmentImported=false，JDK二进制/common主JAR/环境摘要相同、HEAD均为`1cc453388950ec90eba51a1484994208c78d67fd`；各轮含三POM在内的254项指纹before/after独立集合比对差异0。red的collector0只表示准确行为红采集成功；最终green02才是本小步测试实际通过。

green02两个原方法全部通过，因此既有own-worker PID/start/parent/java/callDir身份握手、真实stdin EOF/协议、线程/进程退出及本根目录0等断言均保留并走完。父路径例也实际走到异常类型、status=502及code=PROCESS_OUTPUT_INVALID检查；不能用只有某个异常或采集器退出0代替这组断言。

## 继续实施的边界

可以据此登记“父目录段拒绝小步：实红后同两例绿，原采集前提失败另留存”，后续仍须按EX-15b原规格冻结files输入映射、描述符/数量/字节/SHA、授权持久引用、完整路径/链接竞态、精确补偿及重启验收。当前没有真实输出文件或artifact存储，两个方法也不能证明未来一定在文件打开前拒绝所有危险形状。旧协议/Hardening、完整skill和真正files链的后续回归仍待，不把这一小步绿写成整卡或PLAT-21完成；X-1真实worker与EX-16生产取消接线另行处理。

本报告完成后停写，原red、green01、green02、日志和源快照原样保留。本次不批准额外产品修改、重跑抽样、索引或提交范围扩展。
