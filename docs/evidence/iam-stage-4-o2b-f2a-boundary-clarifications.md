# F2a实现期间的限定澄清

正式任务仍为 `iam-stage-4-o2b-f2a-tls-brief.md` 与 root evidence contract；新增6文件、原612不变、EVENTS关闭、生产装配未验和独立review/audit门槛不变。以下记录root在实际实现中作出的技术裁定，不是实现通过证明。

- 有效受信Certificate H/H+1及chain4/5：接受实际测试KM所选链、session确认链与同tag TLS1.3 producer/encoding路径推导消息body字节/条目；无需另建解密抓包设施。必须准确标为生产路径与实际链推导，不声称解密观测网络明文。其它信任/SAN/签名条件有效，实际失败触达目标限制。
- H32768成功但随后大NST33021触发H的失败历史保留。仅H/H+1隔离测试两端关闭tickets以排除该干扰；NST10与KeyUpdate另用真实端点独立验证，不能据此宣称无限ticket洪泛全部实测。
- NST10握手debug的两次128KiB收集cap失败保留；仅该测试的有界日志上限可提高到1MiB，组件C/W与正常日志策略不变。其它已成功traffic cases不为包装额外重复，最终两类按计划唯一final。
- 测试进程user.dir在module时生成的本轮peer证据，只可在全部活动子JVM退出后按实际清单同盘原字节移动到root owned peers目录。先核绝对源/目标边界、同一PowerShell原生Move-Item；保留原命令/日志路径，另记relocation map，不抹历史。不扫删module .local父树；空目录可保留。代码以校验AGENTS的绝对新工程root存后续测试产物，final覆盖修正。
- PKIX显式revocation=false；构造拒绝显式 `com.sun.security.enableAIAcaIssuers=true`，真实新测试JVM在启动时显式设false，fixture没有AIA。与H/chain相同，当前property值不能证明已有JVM中早先初始化的内部静态值。生产launcher/全进程启动契约仍待后续装配；本轮不新增自制trustmanager/crypto、运行中setProperty或全局安全属性变更，也不宣称任意已有JVM里绝对无AIA出站已证。
- hash无循环顺序沿F1：先冻结report/其它控制身份，再handoff（ownInventory仅path/count/schema与最终返回identity策略），再ownInventory包含handoff/report/全部owned/privateGit且仅排自身。最终作者消息给handoff和inventory真实bytes/SHA；handoff不反向嵌入inventory自身SHA。

所有真实失败、编译/脚手架无效尝试、overflow缺陷及最小修复阶段如实保留。成功阶段及hash缓存，元数据包装失败只恢复未完成部分，禁止为了日志整洁重复成功测试、Git检查/应用或重放。
