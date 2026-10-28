# EX-02 发布 pin 空间字段修复

状态：候选验证已齐，待独立审阅与本地提交；正式进度只看统一台账§14。作者：`/root`；未使用superpower。

## 范围、版本与改动

开工起点AP `20333f35bc9f97dc11df296c04c8028e27fd42c8`、OR `097165072eaa4123f40ecb8f4487490dec830771`。本轮真实OR运行/定向时为 `70b278ff28abbe60dca9c3b78880e785d3cf33cd`，中间EX-28仅文档/证据提交，本体产品源码没有变化。原有未跟踪材料保留，EX-01脚本与EX-35网关改动另属各卡，不加入本候选。

产品仅在`app-management/src/main/java/cn/agentplatform/app/infra/ReleasePins.java`的原请求body新增`spaceId=space`，沿用已从发布候选`config.ontology.space`取得并校验的UUID。原四字段请求在真实本体targetSpace校验处返回400；不会从release反查空间/身份。

测试仅改`app-management/src/test/java/cn/agentplatform/app/infra/ReleasePinsTest.java`与`application/PublishPinTest.java`：两处既有真实HTTP请求记录断言加入spaceId，增加缺空间不发HTTP的边界。不修改鉴权、SecretVault、超时、fail-open/fail-closed、冻结快照或迁移。原Authorization不出站、X-API-Key及已提交版本重放断言保留。

## 实际验证

| 阶段 | 当次目录/记录 | 结果 |
| --- | --- | --- |
| 原实现定向红 | `runs/red-unit-20261028-203651-613/` | Maven1，19项17通过/2失败；两条既有请求载荷断言失败 |
| 一行修复后定向 | `runs/green-unit-20261028-204423-924/` | Maven0，19项全通过 |
| app完整模块 | `runs/after-module-20261028-204614-594/` | 23XML/178项，160通过/18错误/0失败/0跳过；不是模块全绿 |
| 原真实客户端 | `runs/live-red-20261028-204407-372/` | 原四字段请求真实OR返回HTTP400，客户端FAILED；RED探针按预期退出0 |
| 修复后真实客户端 | `runs/live-green-20261028-204612-721/` | 首次PINNED/changed=true，重复同bindingId/changed=false；跨空间HTTP403 |
| 可复跑重复客户端 | `runs/live-repeat-20261028-205425-039/` | REPEAT两次changed=false、同ID，跨空间HTTP403，退出0；中文输出已显式UTF-8 |
| OR定向 | [本体证据](../../../../ontorepo/docs/evidence/2026-10-28-EX-02/README.md) | BASE/onto_test，两类11项全通过，原对应11方法/状态集合一致 |
| 真实接口与行数后验 | [本体后验](../../../../ontorepo/docs/evidence/2026-10-28-EX-02/live-protocol-verification.json) | 重复POST实际HTTP200/changed=false；本轮自有绑定1、foreign空间绑定0 |

模块逐方法比较见`runs/after-module-20261028-204614-594/method-summary/comparison.json`：基线为EX-00环境齐备后的完整app模块177项；仅多新增缺空间边界1项，原方法缺失0、新非通过0，原18错误逐身份保留。汇总只选app-management，不外推其它模块或整仓。

`PublishPinTest`连接真实PG、经过AppService commit并记录真实HTTP，但合格publish_report由测试预置、OR为桩；它证明事务后出站与冻结快照。真实OR探针则直接运行实际ReleasePins类验证协议。两层均不等于平台真实五门禁/发布入口或J9，不关闭EX-07/PLAT-18其它残项。

## 复跑入口与环境

- [run-app-tests.ps1](run-app-tests.ps1)：Windows PowerShell5.1实际执行；`-Phase red-unit/green-unit -Selector 'ReleasePinsTest,PublishPinTest'`为定向，`-Phase after-module -Collect`为完整app收集。Java21.0.12.8、无-am、原.env标志与argLine不变。Collect的Maven0只表示收集，必须解析XML。
- [summarize-app.py](summarize-app.py)：`python summarize-app.py <本卡runs目录>`；复用已冻结EX-00纯解析函数。新建method-summary且拒绝覆盖，定向检查所选类齐备，模块检查原方法与非通过多重集合，不输出XML properties/原始失败正文。
- [本体夹具](../../../../ontorepo/docs/evidence/2026-10-28-EX-02/prepare-binding-fixture.mjs)：每次新建独立纯模型READY release及两个不同主体账号，按正式validate/tests/review/prepare/commit，不复用/弃用旧版本。先为本仓`.runtime/ex02-private`设置仅当前账户ACL，再运行；明文只留忽略目录，禁止打印。
- [run-live-client.ps1](run-live-client.ps1)：`-Phase RED/GREEN/REPEAT -PrivateFixture <安全交接的本卡私有JSON绝对路径>`。只把binder key及对应tenant绑定传入Java子进程，finally恢复父环境；实际业务类仍经SecretVault解析。新夹具先运行GREEN，已有绑定用REPEAT；RED须使用原产品版本，不能对修复后版本期待400。

Java类路径只从本次Maven归档XML的`java.class.path`单属性提取到AP忽略`.local/ex02-live-classpath.txt`，不导出其他属性/环境；来源、类路径摘要和实际ReleasePins.class/源码摘要保存在run.json。原始日志/XML仅本地忽略留存，公开证据只有方法/退出码/指纹。

首次直接Java启动缺项目NIO临时路径，HttpClient构造前置失败，见`setup-live-client.json`，未发HTTP；原件保留。补与本工程相同的`java.io.tmpdir`/`jdk.net.unixdomain.tmpdir`后才得到RED/GREEN协议结果。前两份诊断stdout对中文reason存在本机码页解码乱码，HTTP数字及JVM内断言有效；仅为探针增加显式UTF-8输出，后续REPEAT已保留完整中文，未修改产品错误内容。

本体PG/API恢复记录见其`runtime-readiness.json`，真实配置指纹保持，使用原数据，无新集群初始化或清库。测试窗口串行；root keepers2170（AP依赖）、85345（OR PG/API）保留。运行状态不属于Git提交。

## 验收与回滚

独立审阅：[AP review.md](review.md)、[OR review.md](../../../../ontorepo/docs/evidence/2026-10-28-EX-02/review.md)；候选SHA与准确文件见`candidate-manifest.json`。提交号由统一台账§14回填后才转完成，不虚构人工签字。

代码回滚仅恢复本卡ReleasePins及两测试的提交，不重置EX-01/35/其它协作者文件；证据保留。`agent.pin.enabled=false`只临时停止写入，不算验收成功。新夹具资源与凭据保留在本轮隔离命名下，不自动删除/撤销，不改旧release；后续若需撤销，只按本卡记录ID处理。未推送、打tag或对外发布。
