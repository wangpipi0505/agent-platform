# T24 / T26 前端接入记录

日期：2026-10-20。唯一工作根为本独立工程，依据冻结 v1.2、`statistics-api.md` 与 `application-lifecycle-completion.md`。沿用现有第二版 tokens、Element Plus、Vue 3.5；无新依赖，无浏览器持久化票据、无生产发布、无 Git 提交、无自行启动或重启后端。

## 已实现

- 应用列表六维筛选经服务端 AND 查询；默认排除归档，显式 `status=ARCHIVED` 查看归档。请求参数使用 URLSearchParams 编码，旧筛选的迟到响应不会替换新筛选结果。
- EDITOR / ADMIN 复制应用：小表单只收 code/name，POST body 由白名单重建；成功进入新应用概览。复制不在前端拼装配置、版本或凭据。
- 概览读取生命周期日志；选择两个已发布版本查看 ADD/REMOVE/REPLACE 结构化差异。缺失值与 null 分开显示，大值与摘要渐进展开。
- 平台 ADMIN 统计页：UTC 左闭右开日期、应用/业务系统/用途筛选、运行与技能分组表，各自 total，共用页码。没有把当前页求和当全量指标。未知 Token 显示“待计量”。权限不足显示拒绝态且不请求平台统计。
- 重建仅提交筛选及唯一 requestId，运行中禁点；失败重试保留相同请求号，修改筛选后生成新号。已保存快照列表与分页读取可用；重建响应的 pageSize 与服务端保持一致。
- 各来源时刻及 PER_SOURCE_SNAPSHOT 口径可展开，不声称跨服务同一快照。
- 技能资源详情展示日期/版本、各用途统计、独立 Mock、正式每日口径与失败语义。普通用户仅自身事实由后端限定。
- 故障样本按服务端 href 分流：owner 转现有运行详情；ADMIN `/admin/statistics/runs/{id}` 请求最小故障元数据弹窗。未把 API href 当 SPA 路由，不展示其他 owner 输入输出。
- 请求失败、422 容量限制、空页、加载、权限态均有可恢复提示，不生成假数据。
- 实际联通发现并修复原生命周期 POST 空 body 经 axios/网关导致 415，明确传 JSON `{}`；归档按钮按既有后端状态机仅对 DISABLED 可见。筛选为空时保留创建入口，筛选无匹配时引导调整筛选。
- 已保存快照读取独立于尚未提交的表单日期，并回填快照真实筛选，避免已存结果与页面范围错位。

## 红绿验证

1. 先新增 `web/tests/statistics-lifecycle.test.ts`，运行 `npm test -- --run tests/statistics-lifecycle.test.ts`：2026-10-20 04:54，因缺失实现模块失败（1 failed suite）。
2. 实现 helper 后同命令：7 tests passed。覆盖服务端六维筛选、复制 body 白名单/空字段、UTC 窗口/分页拒绝、重建过滤体、不完整 Token、两个独立 total、故障 href 边界、diff 缺失/null。未写逐句镜像测试。
3. `npm test`：22 files / 48 tests passed（原41 + 新7）。
4. `npm run build`：vue-tsc 与 admin / webapp / assistant.js 三构建通过；现有大块体积警告仍在，不影响构建。
5. `node tests/statistics-lifecycle-browser.mjs`：本机真实 Chrome 无头组件测试通过。组件页面 `http://127.0.0.1:15175/statistics-lifecycle.html`，仅此测试脚本拦截 HTTP 为 fixture。验证 ADMIN 弹窗、owner 路由、独立分页、复制字段白名单、服务器归档筛选、过滤体重建、持久快照读取、READER 零平台请求、技能版本参数、422 无假0。
6. 自审补充快照读取回归：先将当前表单窗口设为无效，再读取快照。修复前 Chrome 等待快照请求超时（红），修复后请求成功并回填已保存筛选（绿）。生命周期 JSON body 与 DRAFT 隐藏归档也有 Chrome 断言。

## 视觉检查

以下截图均通过 view_image 逐张查看：统计完整页、版本对照、归档列表、权限拒绝、技能统计。表格沿用既有灰白底/蓝色主操作/深蓝导航，详情折叠，无整块默认 JSON。测试截图只证明组件行为与布局，不能当真实后端统计结果。

- `frontend-statistics-lifecycle-statistics.png`
- `frontend-statistics-lifecycle-history.png`
- `frontend-statistics-lifecycle-archived.png`
- `frontend-statistics-lifecycle-permission.png`
- `frontend-statistics-lifecycle-skill.png`
- `frontend-statistics-lifecycle-mobile.png`（390px；发现既有全局 shell 最小宽度裁切后，只为统计页局部适配；重看后日期、查询、重建完整）

## 真实服务联通

主智能体确认六服务更新且健康后，启动本工程 admin Vite 15173（未停启后端），执行 `node tests/statistics-lifecycle-live.mjs`。使用真实开发登录票据，仅当前脚本内存保存，浏览器 localStorage 无 token。最终结果 `frontend-statistics-lifecycle-live.json`，所有记录路由 200。

- 真实复制后读取新应用验证 DRAFT / currentVersionNo=null / draftRev=1，真实 COPY 与 CREATE 日志可见。
- 显式 ARCHIVED 加新草稿 code 查询返回空，不泄漏 DRAFT；列表显示“没有匹配的应用，请调整筛选”。未为验证归档而改动既有正式应用。
- 真实平台查询、筛选、POST 重建、GET 快照列表及快照读取通过。首次真实选取 smoke_chat 的页面显示 FORMAL 4、EVALUATION 1，与主任务后端源表对账口径相同。
- 主任务提供通过真实候选评测/发布创建的 `smoke_versions_37bb9848b5`，Chrome v1→v2 展示 `/config/systemPrompt` 与 `/description` 两行修改及6条生命周期日志；不是 DB 手工造版本。
- 真实技能详情统计 200，DEBUG 独立一行，Mock 0，实际耗时及成功率可见。
- `frontend-statistics-lifecycle-live-{copy,archived,statistics,diff,skill}.png` 已逐张检查。fixture 中的未知 Token/失败下钻/分页/READER/422 另行标明，不把测试拦截结果当真实服务数据。

真实联调留下独立 `ui_lifecycle_*` 草稿及统计快照作为可检查证据。未删除其他数据、未将测试编码写入产品默认值。

## 文件范围

`web/admin/src/App.vue`（路由接线、列表筛选、复制小表单）、`StatisticsPanel.vue`、`ApplicationHistory.vue`、`SkillStatistics.vue`、`FailureSamples.vue`；`web/shared/statistics-lifecycle.ts`；新增 tests 文件与本报告/截图。未修改 shared Chat/content/tokens、assistant main、RunDetail 或其他协作者后端文件。
