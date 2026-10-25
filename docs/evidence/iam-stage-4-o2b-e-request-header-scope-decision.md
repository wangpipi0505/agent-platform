# O2b E请求头容量与第9文件scope修订

真实隔离HTTP已证明默认Tomcat请求头容量挡住正常D child票。实际compact JWT为8495 ASCII字符（原JWT上限仍16384，不扩大），保守整headers估算10543B。原HTML400发生在Spring filter前，不是功能RED。新32KB配置下，真实重编译的baseline返回403/1F，恢复E实现并真实重编译后HTTP200/固定MIME/正常receipt与完整packet为1/0F/0E/0S。stale Maven class阶段仍INVALID，均保留原记录，不补称历史首次TDD。具体身份/实际epoch计数由作者封存报告提供。

据此root正式扩大唯一writer授权：除原8文件外，新增允许修改既有`engine-runtime/src/main/resources/application.yml`，仅在server下增加固定`max-http-request-header-size: 32KB`及必要简短注释，原EOL/其他配置保持。生产默认必须能接收该合法票，不能只在测试另配64KB后宣称可用；不改变JWT/decision cap、authority、POM、其他业务配置或反向代理部署。

作者继续是全部source/target唯一writer，root不直接写该resource。scope为9文件（4 Java生产+1既有resource+3新tests+1新support），新增文件仍5，因此source预期608、delta9。将该文件纳入source collector/before/after/delta、最终epoch、private raw patch/replay和有限resource overlay；原已冻结阶段/成功hash/测试/原始结果不重写、不重跑。

正常HTTP验收应移除仅测试显式设置的32KB覆盖，采用本工程实际application.yml（若隔离App排除自动配置，须明确加载该真实资源并证明effective property=32768）。最后新E-final7类同时验证此默认，不额外重跑已成功codec6或header-green。合法票通过和超32KB请求在容器前受限都要有明确验收；容器拒绝HTML不是IAM业务error，禁止伪装成其授权结果。

原8文件brief/packet-decisions/wire-clarifications保持冻结，本补充仅上述第9文件与真实接收容量义务优先。controller/private/public、16MiB/192MiB、wire/schema及complete-before-release不变。后续K8s/ingress/TLS反向代理还须匹配有界合法请求头预算，本轮不称已部署或已验其配置。
