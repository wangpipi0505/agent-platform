# O2b E独立修复轮次：I1错误正文与I2完成后释放

## 授权和基线

唯一项目/每条shell workdir：G:/207/项目/算法重组工具/代码/agent-platform。不访问相邻旧工程、不改v1.2/四契约/时间，不commit/deploy。用户已授权持续按既定设计开发并用隔离端点验证。遵循receiving-code-review、SDD fresh fixer、TDD、verification、Java及ponytail；本任务不需再问实现许可。

完整读本brief、`docs/evidence/review-iam-stage-4-o2b-e-engine-packet.md`（15070B/SHA3e53314023218e788d63c9aae9fda556e2f8a7d9b67e7f0b462ffa8fd240cf82，root已读1–102 EOF）、正式packet-decisions与wire/header两个补充。原E report只为相关证据/限制阅读，无需重复整套累计计划。

原E作者已交还全部写权。新fixer是唯一source/target writer，只有下列两个源码写权：

- engine-runtime/src/main/java/cn/agentplatform/engine/application/PortalRunEventsService.java
- engine-runtime/src/test/java/cn/agentplatform/engine/application/PortalChildEventsHttpTest.java

其他源码（包括Reader、PacketWriter、common、fixture、旧C tests、application.yml、POM/runner/frontend）全部0写；不得新增源码文件。确有最小scope不足先以具体接缝证据报root，不自行扩。

基线source608：`.local/iam-o2b-e-engine-packet/source-after.json`，116139B/SHA b89023b56cf78945a739ac953a0c4b8867bb92bc31ff72d46c1f96bdf893aca3；target历史逻辑2148：同目录target-after.json，447925B/SHA ce1a10735815197b171f17d439063241d186995b63bfe3b31b3ba8f8ac003adf。完整只读私有基线`.local/iam-o2b-e-engine-packet/replay/`。本任务source数量仍608，相对原E唯一delta2，无新增/删除。

原final45=新21+C24、9文件源码、source epoch、原90459B patch（SHAb49b4de0d973efadfe059292a17be2f0c0fc4d9f8ca10f7e3bdbf6c6875e8f90）、一次check/apply和完整608回放均冻结，不得改或重跑。root原有限审计`.local/iam-o2b-e-root-check.json`7366B/SHAdcd60795e8e3fab49ce1026eae5e3317f7917e29eb4e85a82f51e542ba8ab907，6阶段2851distinct PASS，只是artifact不消除review I1/I2。八索引仍D checkpoint5411B/SHA2b125131305a08bbdf6f3c734a744a3296d258da666cc4d25b5c9c724ba87dab，作者不写。

## 两项修复

I1（review P2）：Service旧head223 private首HELLO前容量失败仅413/application-json空body。真正queued opening preflight成功后关闭结果，writer首fresh RR容量失败且尚无packet/未committed时，写既有白名单固定ApiResponse/status，严格小JSON、无未经验证state/watermark/receipt/APEV；实际get/write/flush与complete均在本owner持额范围，必须物理complete返回后释放。已开始/部分packet或committed不可追加JSON/SSE；不走MVC二次派发、不降级普通分支。

I2（review P1）：Service旧head174 beforeRelease的private CapacityFailure分支直接capacityReset；226只有CAPACITY写成功后才complete。其get/write/flush/导出/current/live等任一异常会逃出callback，Reader254 finally先释放B、service内finally再释放frame，外层finally才complete。必须在仍处于beforeRelease、当前窗口/额度尚持有时，以覆盖全部容量尝试异常的真正finally保证actual complete返回，再允许cleanup/lease/frame释放。保留原异常/stop语义、无partial重试/额外COMMIT/游标推进；不能把complete调用、CAS标记、Future.cancel或AsyncListener通知当物理退出。

最小统一处理可以复用既有有界JSON/error/complete路径并在正确容量分支包finally；不要改Reader原B/C consume语义或引入新scheduler/回收框架。普通成功packet必须继续流，不得误把所有beforeRelease无条件complete。原16MiB/270437B frame、2 Reader permit、192MiB预算和32KB实际请求头不变；实际G/E都已有自动renew，无新续核scheduler。

## 新测试与验证范围

先新增真实会失败的focused用例并保存新RED；本轮原45不含这些路径，不能拿原green充RED。建议至少覆盖：

1. private真实queued首fresh RR容量失败，未HELLO、响应未committed：精确JSON status/code/envelope、零APEV/receipt、actual complete退出前frame/slot仍持有。参考旧public容量测试的排队控制，只窄读所需方法，不修改旧test。
2. 正常HELLO之后、真实WindowEmission.consume内在WINDOW prefix前触发CapacityFailure，随后CAPACITY write/flush或current/live失败，阻塞actual complete；两个active连接的B permit/frame仍占用到complete退出，第三准入受限；无额外COMMIT/重试/cursor推进。必须进入本次I2回调，不用一般输出失败冒充。至少两个不同失败出口验证同一个finally边界。

正例必须正常签名G/E、actual HTTP security frame与真实PG/writer，不用public DTO/VA/currentOnly/反射制造权限。负例可在测试已有executor/wrapper/seam里精确注入编码CapacityFailure/IO/live故障，以真实已mint borrow测试异常路径；明确这是故障注入，不叫真实BUFFER_LIMIT压力。不能为注入加production hook、改common/Reader/Writer或关闭gate。实际PG type为varchar32，勿再构造4097字符正常writer type；可使用真实writer超payload/现有排队控制。不可把测试私改header64KB当实际默认；继续actual yml32768。

修复后执行一个有限最终选择：新增所有用例＋原PortalChildEventsHttpTest中一个实际受Service修复影响、覆盖private idle到新terminal完整WINDOW的既有用例，证明正常流未被提前complete。该既有用例在新source epoch重验有明确理由；不跑整HTTP类/原7类45/C24/旧stage/PG suite/common/全后端。由fixer根据实际方法名设置一次`iam-stage-o2b-e-fixes-final`的engine-runtime逗号/#选择器，报告完整原命令、case名/数量、失败和修正。无需先把新增GREEN再重复同GREEN final；可在RED后直接跑新有限final集合。若新代码/具体失败需再focused，保留各epoch理由，不能重跑成功阶段补缺记录。

所有阶段source epoch608/command/cwd/exit/stdout/stderr/raw/actual Running对应XML/txt立即保存。编译/环境错误不计功能RED。源码从私有baseline恢复/复制时Windows mtime可能保留，必须证实际编译对应当前字节；不能再次用旧class冒充baseline。禁止删除全target/cache来掩盖证据；原E stale INVALID/Tomcat400/PG测试错误照旧保留。

## 证据、补丁及交还

自有`.local/iam-o2b-e-fixes/`、`docs/evidence/iam-stage-4-o2b-e-fixes/`、`.tools/iam-o2b-efix-*`，报告`docs/evidence/iam-stage-4-o2b-e-fixes-report.md`。helper刻意使用efix前缀，避免落入原冻结E的iam-o2b-e-*集合。原E所有local/evidence/helpers/report/inventory/journal/private Git及review文件均不改，八索引root最终联合收口。

建立一个新的完整608私有Git基线，从原E冻结replay复制；只以本次不同delta2 raw patch首次check/apply到最终608，保存新命令及名称/bytes/SHA对等，无apply后source copy/no commit。原90459B patch及原replay不动、不重跑。所有依赖子进程必须真实exit后再mutation，每条命令在断言/后继前持久化；成功hash/phase缓存journal，metadata错误只恢复未完部分，不重复测试/PG/replay。

有限target只实际common/engine compiler created/input/资源/common fixture JAR及本轮actual Running报告；raw lists直接copy字节，新dump/dumpstream另列，不扫描旧target。source before/after/delta2、两个改文件before/after、所有新stage/有限overlay/target逻辑、唯一608回放、所有自有文件和私有.git纳own-inventory，仅自身排除。handoff用清晰file/bytes/SHA、count/schema，标ownedRoots/sourceWrites与writeAuthority。不要打印完整manifest/Buffer数字数组/票或secret。

完成后冻结交还全部写权，回原reviewer `/root/review_iam_o2b_e_engine_packet` 复审新delta2与focused证据；root另做有限fix审计，原E45审计只继承不重复。原review追加复审需保留旧15070B原始prefix。Spec/Quality Approved C0I0与root audits通过后才可E Accepted；F仍待实现，不宣称全功能/生产部署/容量完成。
