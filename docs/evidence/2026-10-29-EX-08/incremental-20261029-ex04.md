# EX-08 第三批增量：EX-04收口

2026-10-29。本次将EX-04加入既有逐卡索引，累计9整卡＋EX-27的SSE/SDK/outbox三个子步；39卡为9完成/2进行中（EX-27、EX-08）/28待启动，48能力仍9完成/21进行中/12待验收/5待启动/1暂缓。其余30整卡及48/45完整覆盖仍待，EX-08自身不关闭。执行仍只读原三份主入口，不要求执行者逐份阅读本目录证据。

| 范围 | 实现 | 定向/原场景 | 完整回归 | 契约/浏览器 | 联合/未覆盖 | 提交与回滚 | 独立评审 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| EX-04限定投影/传输/分页消费 | AP四生产＋四Test；真实registry预检、首create前整批规范化、严格exposure/分页/风险、保存保留与OFFSET提示 | 原skill33=18P15F、engine47=36P11F；最终33P/47P/OR9P，无ignore/skip；首PG坏批0→2红保留，最终四批均0→0，真实PG137B精确衔接引擎 | skill689=573P6F106E4旧S；engine932=817P35F80E，930具名＋2原事件/14原source未执行；相对方法及原因门通过，不称全绿 | OR既有9契约测试复验，产品无改；没有新端点/前端，浏览器N/A；EX-05尚未开工 | 模型/engine协议替身与实际PG/HTTP分列；真实OR分页/J4仍EX-07；无非HTTP登记正例、自动续页或强制总行限额 | AP `72ee3428581ff2e25359c9b312a5754de1ac97bc`，parent d7880a6，301文件恰8源＋293证据；只反向八源准确差异，保留证据/已发布版本/专库，不整仓reset/清未知队列 | [实质终审](../2026-10-28-EX-04/final-review.md)、[索引审](../2026-10-28-EX-04/index-review.md)；最终301集合独立消息复核仅比300多报告/attrs一行 |

完整回归发现并修复过一次真实回归：首轮full-skill-20261029-1102新增8个旧手工保存ERROR，源于对缺importSource调用object(null)；仅Catalog Map分类修复，原四Test/全部断言不改。最终8/8恢复P，原1102门false保持。所有运行与命令均见[EX-04入口](../2026-10-28-EX-04/README.md)，不把编译、formatter或ignore退出0当验收。

skill原116原因保持，唯一旧迁移仅精确2UUID＋2timestamp有限表示差异；SDK5P/4outcome/0诊断。engine原115结构原因保持，92原文一致，23个Spring对象/测试connection标识严格限身份/位置/次数、message＋detail联合双射/重复关系与剩余全文等价；原raw比较false及原hash不改，有限派生证据门通过。没有改产品测试断言、skip、顺序、fork或超时以求绿。

引擎14既有自有库及原app HTTP夹具范围已核；原TestInputHttp9个Bean冲突ERROR、无schema专库与两事件/14未执行保持。原timeout夹具本次7→8恰1自有库，旧name/owner不变；不清历史。当前target的已知V222残留SHA419c753c…/校验701700386和PG历史一致且前后未改，src无此文件；不是clean构建验证，EX-27后续须先明确源码/构建/历史一致性范围，不擅clean/repair/补迁移。

[352源提交上下文](shared-projection-source-context.json)逐项核当前raw=最终两Full实际源，标准Git过滤blob=提交树，52源raw与提交字节存在换行差异，不能声称全部raw逐字相同。实际代码树`4c660eb5e45312a786f6bf3caba1ff261a591f49`；OR保持`da3345a123752c3fda06c5a7a8a1dc9d614b8918`，未改源码/index。

三份主入口为v2.14/v1.14/v1.14：[精确快照清单](main-entry-snapshot-20261029-ex04/manifest.json)记录原绝对路径、版本、字节数/SHA。原文件在AP/OR仓外，本检查点保存其逐字节归档，不声称实现提交包含原文件；快照不作为执行入口，内部相对链接仍属于原位置。EX-04关闭解除EX-05依赖，但本轮未启动下一卡；PLAT-14/O-02/O-03的真实联合等余项保留。没有使用Superpower，没有push/tag/外部发布。
