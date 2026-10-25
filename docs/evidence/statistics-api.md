# T26 统计接口交接（v1.2）

日期：2026-10-20。统一成功壳为 `ApiResponse.data`。时间均为 UTC ISO instant，窗口 `[from,to)`；缺省最近30天，最大366天。`groupBy` 仅允许 `day`。租户和主体仅来自已验签 Actor。查询参数里的 tenantId/principalId 不参与身份或授权。

## 技能统计

`GET /admin/skills/{code}/stats?from&to&groupBy=day&version=1`

- `version` 可选正整数；缺省全部版本合计。
- ADMIN 查看本租户，其他用户仅自己的执行事实，与 owner 运行记录的下钻边界一致。
- `total:{calls,formal,debug,evaluation}` 含全部逻辑调用；`mock:{calls}` 独立列。
- `byStatus:{succeeded,failed,unknown}` 统计真实调用，不把 Mock 计为真实成功。
- `failures:[{errorSemantic,count,sampleRunIds,sampleLinks:[{runId,href}]}]`：真实 FAILED，最多100种语义、每种最多5个运行样本。普通 owner 链接 `/admin/runs/{id}`；ADMIN 链接 `/admin/statistics/runs/{id}`。
- `byDay:[{date,calls,mockCalls,realFormalCalls,successRate,succRate,p95}]`：日期UTC；`successRate` 与冻结字段 `succRate` 同值，分母仅真实 FORMAL 调用。`p95` 为该分母的实际业务执行毫秒；不存在分母/时长为 null。
- `byMode:[{callKind,calls,mockCalls,successRate,averageDurationMs,p95DurationMs}]`：每种用途自己的真实调用成功率与实际耗时，调试/评测不会混入正式日成功率。
- `snapshotAt,consistency=REPEATABLE_READ,grain=LOGICAL_SKILL_CALL,successRateBasis=REAL_FORMAL_CALLS,from,to,groupBy,timezone=UTC` 明确口径。

`GET /internal/statistics?code=...&from&to&groupBy=day&version=` 返回相同契约，另需内部 SERVICE 和 ADMIN 权限；缺 code 拒绝400。旧的无界全租户数组导出不再暴露。`SkillExecution.statistics(String)` 只为既有内部测试调用保留，不再承接HTTP入口。

## 平台统计（全部 ADMIN）

`GET /admin/statistics?from&to&appCode&bizSystemCode&callKind&groupBy=day&page=1&pageSize=50`

`callKind` 为 FORMAL / DEBUG / EVALUATION；`pageSize` 为1至100，`page` 为1至2000。所有指标按运行开始时间选出运行 cohort；选中的运行关联调用取各来源当前已持久化事实，不是回溯某个历史时刻的状态。

返回：

| 字段 | 含义 |
| --- | --- |
| `groups[]` | 按 `appCode,bizSystemCode,callKind,date` 的运行聚合；包含 `groupKey,runs,succeeded,failed,cancelled,unfinished,successRate,averageDurationMs,p95DurationMs,confirmationCount,model` |
| `groups[].model` | `calls` 为实际模型发送数；`knownTokens` 为已取得usage的部分和；`usageMissingCalls` 为缺usage条数；任一缺usage时 `totalTokens=null`。没有模型发送才可合法为0 |
| `skillGroups[]` | 相同维度再分 `skillCode,skillVersionId`，包含逻辑 `calls,mockCalls,succeeded,failed,unknown,successRate,averageDurationMs,p95DurationMs`；Mock不进真实成功率和业务耗时 |
| `failures[]` | 最多50个失败运行样本：`runId,appCode,errorSemantic,href`。`href=/admin/statistics/runs/{id}` |
| `groupsTotal,skillGroupsTotal,page,pageSize` | 两张独立明细表的分组总数与当前页；不是调用量总数。不要对当前页求和后标为全量总计 |
| `filters,queryHash` | 服务器规范化筛选条件及摘要 |
| `sourceSnapshots` | `{engine,model}` 各自PG只读一致快照时刻 |
| `consistency` | `PER_SOURCE_SNAPSHOT`，不声称跨服务同一数据库快照 |
| `windowBasis,timezone` | `RUN_STARTED_AT,UTC` |
| `runGrain,skillGrain,modelGrain,confirmationGrain` | `RUN,LOGICAL_SKILL_CALL,MODEL_SEND,APPROVAL_DONE_EVENT` |

运行耗时为运行墙钟时长，可包含等待；技能耗时来自 `tool_call.duration_ms` 接受事实时累加的实际业务执行段，不含重复投递和对账等待。未结束运行没有完整运行耗时。正式 CHAT/TASK 映射 FORMAL；DEBUG、EVALUATION 独立行。确认量为持久 APPROVAL_DONE 事件次数，重试同确认请求不会重加。

业务系统只读 `run.guard_ctx.attributes.bizSystemCode` 中创建运行时冻结的已验签属性（单值数组或字符串）。缺失或含多值时保持 null，不能从 inputs/query/body 推断成某个业务系统。

规模闸：最多10000个运行、2000个运行维度分组、2000个技能分组；超限422，提示缩短时间或加应用/业务系统/用途筛选。来源HTTP读超时15秒、单来源结果最多8MiB。SQL仅固定语句与绑定参数，`groupBy` 不拼接到SQL。

## 持久重建和错误下钻

`POST /admin/statistics/rebuild`

```json
{"requestId":"stats-20261020-001","from":"2026-10-01T00:00:00Z","to":"2026-10-20T00:00:00Z","appCode":"my-app","bizSystemCode":"crm","callKind":"FORMAL","groupBy":"day"}
```

仅 requestId/from/to 必需；其余可选。明确拒绝客户端 total/tokens/tenant 等未知字段。`requestId` 为1至128位字母数字下划线横线。同租户同requestId同筛选条件返回已存结果；改筛选复用请求号409。新请求号会重新读权威来源生成新快照。返回平台报告第一页，加 `snapshotId`。

- `GET /admin/statistics/snapshots`：本租户最近100条 `{snapshotId,queryHash,filters,createdAt,active}`，`active` 表示该筛选摘要当前指针。
- `GET /admin/statistics/snapshots/{id}?page=1&pageSize=50`：读取持久快照，分页语义同查询；外租户404。
- `GET /admin/statistics/runs/{runId}`：本租户ADMIN专用最小故障元数据。返回runId/appCode/status/stopReason/errorSemantic/message/startedAt/endedAt、前100条失败或UNKNOWN技能调用元数据、`failedSkillCallsTotal,sampleLimit,snapshotAt`；不返回别的owner的输入、输出、模型请求或技能参数。外租户404，普通用户403。

重建先完成engine/model HTTP只读投影，再在monitor一个事务里保存完整报告并切换active指针。失败来源不会持久化部分报告或切换旧指针。服务崩溃不会丢失权威运行/调用事实；新请求可重算。同一请求号不重复建快照。`V501` 仅新增monitor快照/指针表，不引入跨schema查询或其他服务jar。

内部接口：engine `GET /internal/statistics`（相同筛选，无page，受10000/2000硬闸）返回groups/skillGroups/runRefs/failures；`GET /internal/statistics/runs/{id}` 返回故障元数据；model `POST /internal/statistics/query {runIds:[...]}` 按可信engine产生的runIds返回 `byRun[{runId,calls,succeeded,failed,knownTokens,totalTokens,usageMissingCalls,usagePendingCalls,averageDurationMs,p95DurationMs}]`。model侧仍独立以Actor租户过滤；重复runIds去重，最多10000个输入，不能通过客户端维度伪造关联。
