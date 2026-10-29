# EX-04 ToolOutputPolicy最小生产候选

在ROOT核实真实红（red-engine-20261029-102649-693，47=36P11F，native1/collector0，精确11预期红吻合、0E/0S）并明确授权后，仅修改生产ToolOutputPolicy.java。两Test仍冻结0989/4018；本代理未运行Java/Maven/PG/HTTP、未读取.env、未操作index或commit。此处只有静态候选，不称绿色。

声明读取只返回合法原节点、不修改数据：缺mode沿PAGE；显式PAGE/OFFSET按已定字段集检查；request需该模式两文本字段且无extra；正32位整数上限不接受小数/字符串/溢出。PAGE response空对象或两可选非空文本路径兼容，显式null/其它类型/未知键拒绝。OFFSET四键全部必需，不接response或PAGE参数，maxRows与maxPageSize各自校验，不加相对大小限制。非法旧数据防御性视为未声明，不抛新运行错误或回退另一模式。

提示只为OFFSET追加mode/maxRows，首参数读取offset；旧PAGE提示不新增mode，原request/response深拷贝及观察路径不变。OFFSET无response，经原resolve产生空observed，不猜路径/总数/nextOffset，不自动改写请求、续拉或强制累计maxRows。

enforce、统一错误信封envelope、resolve以及阈值构造函数方法体逐字未改，静态检查见static-checks.json；实际阈值内同节点及47报告仍须ROOT统一窗口绿色验证。原始输出无切片或状态写入，未改RunService/SkillDigest/POM/跨服务依赖/迁移。

PG衔接仍待技能侧真实PG绿色后提供脱敏exposure/pagination两键。本候选无新执行框架或绑定目录。
