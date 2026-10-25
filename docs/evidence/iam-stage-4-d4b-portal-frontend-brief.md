# D4b：管理端门户身份接入与会话隔离

状态：预备要求，尚未派发、尚未实现。依赖 D4a 私验 session 接口及其真实 handler／安装边界独立通过后再开工。用户已授权按计划持续开发，没有真实外部联调环境时使用隔离测试端点，不再次询问是否继续。

## 1. 依据与固定范围

唯一工程 `G:/207/项目/算法重组工具/代码/agent-platform`，每次 shell 显式 workdir；不访问相邻旧代码、配置、数据、缓存、依赖或进程。以 v1.2 和 `docs/evidence/iam-service-authority-implementation-plan.md` §2.7、§2.4 的门户观察行为为依据。原设计／四契约／后端／SQL／POM／依赖及 lockfile 不动，不排工期、不部署、不开放生产 IAM。

使用当前 Vue3.5/TS/Pinia/Element Plus，保留 P3 现有统一页面风格、布局、操作入口和 SDK `ticket:()=>Promise<string>` 的集成用途。工作 `.vue` 前用 vue-skilld，按现有 frontend/UI 技能保持简单可用；不采纳 3.6 beta，不新增认证框架、账号密码体系、刷新令牌存储、草稿管理或状态管理层。只有真实需要的测试／小入口可以新增，不大规模拆 App 或改 unrelated 页面。

D4a 约定 `GET /auth/session` 成功为 ApiResponse data 精确四项 tenantId/principalId/roles/expiresAt；expiresAt 已核现有 Json.mapper 为 ISO-8601 Instant 字符串。角色可空，UI 角色只控制可见性，后端每次动作仍独立授权；该页面会话不是缓存后端许可。401 清当前身份、403 显示无权、503 显示会话暂不可用和手动重试，不回用历史权限。

## 2. 入口与最小 provider

新增 `web/shared/portal-auth.ts`，采用计划接口：

```ts
interface PortalTokenProvider {
  getAccessToken(): Promise<string>;
  onSessionChanged(listener: () => void): () => void;
}
```

在 admin main mount 前取得并校验同源受信 `window.agentPlatformPortal`，保留方法正确的 this 绑定；初始化连接状态即可 mount，不能在 provider promise 未返回时白屏。默认 portal 模式，仅显式开发配置保留原 dev-token 登录；缺 provider 文案“请从门户打开工作台”，不显示或回退开发登录。模式配置的准确名称、默认值和开发启动方式实施前回报，不修改原启动进程或全局环境。

只允许宿主显式注入的 provider；不从 query/hash、storage、任意 postMessage、外部脚本或未验 JWT claims 取可信身份。token 仅内存。候选 token 必须先通过 session HTTP 与四字段／未来有效 expiresAt 校验，再一次性安装 token/user/roles。错误或过期响应不安装部分身份，角色空集合仍可成为已认证会话，不自行赋 ADMIN。

首次连接、宿主 onSessionChanged、手动重新连接按同一有限流程执行。旧 provider promise 也有 generation，不能在较新连接已完成后覆盖。手动重新连接只获取 token＋session；不自动重放之前发布、创建 run 或任何 POST。

## 3. generation 是真实请求和消费者边界

扩展既有 auth 状态的 generation、会话 AbortController、连接状态和统一 invalidate/install。换身份、重连、退出、401、到期都先递增 generation，abort 旧 HTTP／SSE／观察，清 token/user/roles。同 token 也能产生新 generation；不以 token 字符串比较代替归属。宿主变更／用户显式重连才启动新连接；退出、401、到期只失效并显示入口，等待用户重新连接，不立即自动登录。

createApi 保持调用兼容，用有限会话快照／signal 绑定：发送前、返回响应后、解析 body 后都检查归属。会话 signal 和调用者 signal 必须同时有效，使用标准能力并正确清理；旧请求／错误按 AbortError 或明确停止结果丢弃。401 只有当前 generation 可以使身份失效；不能让旧响应清新 session。useApi 和任何共享身份修改都走统一归属检查，不只清 token 而留下 roles/user。

App 实际消费者也必须闭环：failure 目前无条件清 token；load 的 allSettled 即使各请求拒绝仍继续写 catalogs=[]；copyApplication 导航、loadPublishedInput、providerOptions.then、drawChart 动态 import 以及 finally 的 busy 都有迟到写回。实施前列明这些异步入口的最小处理：可用小认证外壳＋generation keyed工作区统一卸载隔离旧本地状态；仍必须约束共享 auth、导航、消息、后续网络请求及timer，旧闭包不能拿到新generation的凭据继续发起操作。旧failure及两个SSE清身份入口改为invalidate(expectedGeneration)或同等机制。已卸载实例的纯本地refs不要求逐字段guard；若保留同一实例，则必须在异步写回／整批应用／finally前检查归属。仅在 API Promise 返回前拦截不能证明所有消费者安全。

身份变化立即隐藏、清除旧会话业务数据／选择／草稿，并取消观察；保留纯导航和显示偏好。允许优先小认证外壳＋generation keyed工作区，由卸载统一移除旧App数据／草稿／子组件，无需再逐字段reset；若保留App则采用明确业务reset＋认证子树key。实施前说明最小选择与实际取舍。两种方案都会丢弃当前本地未保存状态；不新增复杂草稿系统，也不因 UI 清理取消已受理后端 run。重挂载仍不能阻止旧闭包改 auth/location.hash，归属检查不能省。EvaluationPanel 当前只 watch token/主体/角色，若未由统一 generation key 卸载，则必须把 generation 纳入该 watch。

另一个真实全局副作用是 RunDetail 导入的 `web/shared/Artifacts.vue`→`artifact.ts`：helper直接fetch并在await blob后anchor.click，旧组件卸载并不会阻止下载。最小增加可选下载scope/signal及归属检查，组件传捕获的会话；read/blob后与创建下载／click前均核归属，失效后停止并正确释放ObjectURL，不把旧错误显示给新身份。SDK原五参调用兼容，不能把门户auth全局强塞给纯helper或扩大集成ticket契约。

expiresAt 使用本页定时器，到期清身份并显示重新连接；回调核 generation 与实际截止。对超长时间分段安排，避免 JS timeout 溢出或时钟变化后错误保持会话。退出／卸载释放 timer、provider 订阅、reader 和请求 signal；不把 token 写入 storage 做页面恢复，刷新仍从宿主获取。

## 4. SSE 停止与全部实际消费者

`web/debug-panel/events.ts` 在每次 reader.read 后及调用每个 onEvent 前核 signal／归属，finally 取消并释放 reader；buffer 中授权 reset 后的业务帧不再释放。plan §2.4 规定的三种 reason 为 `AUTHORITY_EXPIRED`、`AUTHORITY_DENIED`、`AUTHORITY_UNAVAILABLE`。它们是停止观察／恢复授权的控制事件，不能当普通 fact、不能自动 readStatus 后重开。

shared run-observer 收到这些原因或实际授权 401/403／IAM 503 停止受影响自动操作；不把显式授权失败当一般网络故障重试。保留原 CURSOR_EXPIRED/CURSOR_AHEAD、EVENT_LIMIT、事实游标与最终快照、瞬态网络断流的原有恢复语义；不凭空改变 SDK 受理／drain 契约。IAM 503 与普通网络错误的准确分类和事件传递方式实施前说明。

真实 admin `RunDetail.vue` 与共享 `DebugPanel.vue` 各自有 connect/finally 的非终态 timer，并不都走 observeRun；两者都要 stopped/generation 检查及取消 timer，不能只改 shared observer。401 只能清其所属当前身份；403／授权503/reset 显示原因，人工恢复前不重启。断流、换身份、失效或退出不发送 cancel-run；新的用户显式取消行为保持原功能。

## 5. Cookie 与真实浏览器

本有限 gateway 边界沿用 B2 严格拒绝 Cookie；门户页面可能有同源 ambient Cookie，Bearer API/session/SSE 必须实际省略它。已核本机 axios **1.20.0**：defaults 的 adapter 顺序 xhr/http/fetch；fetch adapter 把 withCredentials:false 映射为 credentials:omit，且删除 fetchOptions.credentials。候选使用现有 axiosTransport 的 `adapter:"fetch", withCredentials:false`；SSE fetch 明确 `credentials:"omit"`。不能只设置 XHR withCredentials=false 或 fetchOptions.credentials，也不升级／增加 axios。

读本机固定源码的实际范围并记录 SHA/版本；新增有限真实浏览器用例，先设置同源 Cookie，再从真实 transport 发 session、业务 HTTP、SSE及上述附件下载，由独立受控服务器原始请求断言 Cookie 缺失且 Authorization 正确。artifact直接fetch同样明确omit。仅 route mock／替换 axiosTransport 不能证明 Cookie 行为。

已有用户已允许 Playwright 驱动本机 Chromium 做视觉和交互检查，可继续使用；按 playwright 技能执行，浏览器只对本工程隔离页面／端点。保留现有主服务和开发端口，不终止未知 PID；新服务有限随机端口或独占已核端口、准确记录自己拥有的 PID/ready/exit。无真实 IAM 时可使用隔离受控 HTTP＋宿主 provider；明确每个用例是前端 transport／UI 边界还是 D4a 真实签名后端，不把分别验证拼成未执行的整条生产联通链。

## 6. 候选文件、验证及所有权

实施前回报准确文件/API/默认模式/重置边界/错误停止分类／测试 selectors。核心候选新增 portal-auth.ts，修改 shared/api.ts、admin main.ts/App.vue、debug-panel/events.ts、shared/run-observer.ts、admin RunDetail.vue、debug-panel/DebugPanel.vue、shared/Artifacts.vue及shared/artifact.ts；EvaluationPanel 仅上述 generation watch 必要时修改。不修改其他业务组件来顺便重构。小认证壳、类型声明和测试入口如确实必要先列明；不引第三方测试依赖。

新增 portal-auth.test.ts，扩展现有 web/tests/login.test.ts、stream-auth.test.ts、run-observer.test.ts；另需真实 App 的迟到消费者／连接页面测试，可复用已有有限 browser harness，不能只验证纯函数。先关键失败路径红，再仅新／受影响命名测试定向绿；原 127 测试不整套盲目重跑，三个应用的 shared API／events 受影响，最终完整 typecheck 和三应用构建各一次有必要，不以历史构建冒充本轮。

有限案例归并：missing provider/显式dev；session401/403/503与精确字段/空角色；同token新generation；迟到provider/session/API/body/旧allSettled及finally不能写新身份、数据、导航或busy，迟到附件blob不能click下载；到期/超长timer/退出释放；三授权reset后 status/open/cancel/POST 不增长；既有游标/瞬态网络恢复保持。真实浏览器检查页面简洁可操作、身份切换后旧数据不见、手动重连和错误可理解、桌面及窄屏无新增溢出；保存截图并实际看图，不仅生成截图文件。

派发后作者唯一拥有批准的前端source/对应dist及两own目录、新报告，root不读半成品、不同时改 source；后端冻结不动。自身目录 `.local/iam-d4b-portal-frontend/`、`docs/evidence/iam-stage-4-d4b-portal-frontend/`，报告 `docs/evidence/iam-stage-4-d4b-portal-frontend-report.md`。八执行索引、旧证据、review、runner由root保护。每次 shell仍从工程root，通过 --prefix 或命令参数指定web，不改变既有主进程配置。

按 D4a 正式交还的不可变 source/target＋当前前端基线保留 before SHA与原件、每轮命令前完整必要源码清单＋仅变动物理 overlay；记录实际 source/命令/cwd/exit/Running cases/截图及自有进程清理。复用历史封存谱系，不重复十余GB扫描／全量复制旧证据／后端JAR，不能伪造事前状态。交还提供准确delta/最终frozen/真实patch rootcwd check＋apply重放、own精确manifest/bytes/SHA及限制。setup／oracle／工具错误和所有实际失败保留，不以多轮累加冒充一次最终全绿。独立review与root有限核验后才更新全局覆盖；本任务不签收完整D/E/F、生产IAM或17/34/30全验收。
