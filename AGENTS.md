# 新工程约束

- 本工程是全新、独立的实现，仅在本目录及明确授权的设计执行文档中工作。不得读取、引用、导入、链接、复用或修改任何相邻旧工程代码、配置、数据库、存储、进程和依赖目录。
- 唯一设计依据：文档/设计/详细设计/智能体构建平台详细设计-整合版.md v1.2。阅读仓库 docs/implementation-brief.md 与 contracts/README.md 后开发。
- Java21 / Spring Boot3.5.0 / Spring AI1.0.0；Vue3.5 / TS / Element Plus / AntV X6。不升级为beta、不替换框架。PG是唯一业务权威源；禁止用H2/内存Map/浏览器存储冒充生产实现。
- 服务布局与详设相同；后端在 api/application/domain/infra 分层，包前缀 cn.agentplatform。跨服务HTTP，不引入其他业务服务jar。
- 公共服务约定在admin-common：ApiResponse、PlatformException、Actor、Json、Digest、ServiceTokens。禁止自行另造不兼容响应。
- 用户输入/外部响应做校验；凭据仅secret_ref；默认拒绝；tenant与principal必须来自已验JWT，禁止信任请求参数或未验签身份头。
- 写注释解释设计不变量、事务边界、恢复窗口、开源机制对照和有意限制；不写逐句翻译式废注释。出处在 docs/open-source-references.md，未读源码不能声称已对照。
- 前端业务文案简明、单屏一个主操作、必要的渐进展开；保留设计要求的确认与安全检查。无后端时显示可恢复的错误，不静默用假数据代替真实数据。
- 先写关键失败路径测试，再实现；不得跳过失败测试或伪造通过。将测试命令与结果写入 docs/evidence/ 下自己的报告。
- 仅提交自己负责的文件；禁止git add -A、禁止重置其他协作者文件、禁止操作旧工程git。没有明确指令不做外部发布。
