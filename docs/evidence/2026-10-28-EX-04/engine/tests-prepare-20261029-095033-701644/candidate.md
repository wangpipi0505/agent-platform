# EX-04 引擎测试候选（尚未编译/运行）

2026-10-29。依据主入口v2.12/v1.12/v1.12及既有mapping-review。用户不使用Superpower。只新增本目录证据与修改两获准Test，ToolOutputPolicy生产源未动；未读.env、未运行Java/Maven/PG/HTTP或产品脚本，未操作index或提交。

## 新覆盖与旧断言

ToolOutputPolicy保留原10方法全文（撤销六个新import和新块可精确还原原始字节）。新增OFFSET四键严格提示/原响应不改、PAGE显式与旧可选response（包括空对象）兼容、字节恰等阈值同节点三方法，以及21个畸形分页参数。新参数覆盖OFFSET缺键/混用/类型/正32位上限/禁止response，PAGE未知或null模式/数值参数/混用/负上限/坏响应/maxRows不支持。实际超限使用1024阈值和大于4KB输出，不沿用既有低于默认阈值的形状检查。

RunToolProfile保留原12方法全文，新一方法直接调用已有真实prepareSnapshot夹具，验证definition整体与exposure/pagination逐字段不丢，同时保留execution/guard/schema/digest基座，随后将真正冻结出的tool交给实际ToolOutputPolicy。

预期完整两类47报告=原22＋新25（ToolOutputPolicy34，RunToolProfile13）；这是静态身份清点，非实际运行结果。旧实现应在有效OFFSET提示和若干非法PAGE防御场景真实失败，准确红以ROOT唯一窗口的编译/XML为准，编译错误不能计作语义真红。

## PG衔接与范围边界

当前projection两字段是规范样例，不能称为真实PG读回。按ROOT确认，待技能侧实际PG绿色后导出已持久化exposure/pagination脱敏JSON、留来源/SHA，再将该两键作为当前Test固定fixture；不新增resource、业务JAR、外部文件依赖或条件跳过。实际prepareSnapshot仍仅客户端stub读取版本，不是实际跨服务平台运行/J4。

OFFSET固定mode/request(offset,size)/maxPageSize/maxRows四键全部必需，无response；PAGE仅mode/request/response/maxPageSize，缺mode不补、空response可兼容、null response非法。策略仅提示，不改HTTP参数、自动续拉或强制总行数。

## ROOT后续顺序

先在统一实际窗口运行手册函数`Test-AP engine-runtime 'ToolOutputPolicyTest,RunToolProfileTest'`，保存旧生产语义红和47准确身份；ROOT核准后才授生产ToolOutputPolicy单文件写窗。定向绿后完整engine方法/类对照保留原红/两个类事件/14未执行源Test。当前候选不改RunService、SkillDigest、POM、契约、迁移或服务配置。

精确源SHA、21参数/新方法列表及反撤原件检查见static-checks.json；三个before快照和两candidate快照保留原字节。
