<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from "vue";
import { useRoute } from "vue-router";
import FlowDesigner from "../../flow-designer/FlowDesigner.vue";
import { useAuth, useApi } from "../../shared/api";
import { terminal, streamEvents,authorityFailure } from "../../debug-panel/events";
import Artifacts from '../../shared/Artifacts.vue';
import { outputWarning, safeMarkdown } from '../../shared/content';
import { decodeRunStatus, protocolFailure, record } from '../../shared/run-protocol';
import RunObservation from './RunObservation.vue';
const props = defineProps<{ runId: string; nodeId?: string }>();
// 无router的既有嵌入用法可显式传 nodeId；缺失时回退到已解析路由query。
const api = useApi();
import {
  asRecordField,
  asUnknownRecord,
  emptyRunStatus,
  type RunNodeStatusRow,
  type RunStatusSnapshot,
} from "../../shared/resource-types";

function toArrayOrEmpty(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}
const auth=useAuth(),session=auth.snapshot();
const blocked=ref(false);
const current=()=>!stopped&&session.current();
// MNT-03：运行状态稳定外壳（decodeRunStatus 经 parseRunStatusEnvelope 收窄的 data）。
const value = ref<RunStatusSnapshot>(emptyRunStatus());
const error = ref("");
const active = ref("summary");
const selected = ref<RunNodeStatusRow>();
const replay = ref(0);
// UIF-A1 深链：?nodeId=<nodeDefId> 由日志/告警/审批通知构造，进入即过滤定位
// 该节点（表格过滤 + 自动选中检查器 + 事件回放跳到该节点首个事件）。
// MNT-01：Hash路由下参数位于路由query而非 location.search——统一从已解析路由读取；
// 显式 nodeId 输入优先（覆盖无router嵌入），同run query变化时定位跟随。
let route: ReturnType<typeof useRoute> | undefined;
try {
  route = useRoute();
} catch {
  route = undefined;
}
const deepNode = computed(() => {
  if (props.nodeId != null && props.nodeId !== "") return props.nodeId;
  const q = route?.query?.nodeId;
  return typeof q === "string" ? q : "";
});
const nodeFilter = ref(deepNode.value);
let deepApplied = false;
watch(deepNode, (value) => {
  nodeFilter.value = value;
  deepApplied = false;
  applyDeepLink();
});
// UIF-A5：画布 states 映射（nodeDefId→status），与状态接口 nodes 同源。
const nodeStates = computed(() =>
  Object.fromEntries(
    (value.value.nodes || []).map((n: RunNodeStatusRow) => [n.nodeDefId || n.id, n.status]),
  ),
);
// UIF-A5：执行时间线行（nodeInstanceId 顺序 = 执行顺序；耗时仅用持久起止值，
// 缺失显示 —，绝不推算）；点行联动画布选中与节点检查器。
const timeline = computed(() =>
  (value.value.nodes || []).map((n: RunNodeStatusRow) => {
    const started = n.started_at ? Date.parse(n.started_at) : null;
    const ended = n.ended_at ? Date.parse(n.ended_at) : null;
    const duration =
      started && ended
        ? Math.max(ended - started, 0) >= 1000
          ? Math.round((ended - started) / 100) / 10 + "s"
          : ended - started + "ms"
        : "—";
    return {
      label: n.nodeDefId,
      status: n.status,
      duration,
      errorMessage: n.error && n.error !== "{}" ? String(n.error).slice(0, 200) : "",
      raw: n,
    };
  }),
);
function timelineSelect(row: any) {
  selected.value = row.raw;
  if (value.value.graph) nodeFilter.value = "";
}
function timelineRowClass({ row }: { row: any }) {
  return row.status === "FAILED" ? "timeline-failed" : "";
}
const visibleNodes = computed(() =>
  nodeFilter.value
    ? (value.value.nodes || []).filter((n: RunNodeStatusRow) => n.nodeDefId === nodeFilter.value)
    : value.value.nodes || [],
);
// 纯对话应用没有可绘制的节点画布，避免留下一大块空白区域。
const hasGraph = computed(() => {
  // 动态 graph 保持 unknown；仅确认可绘制形状，不声明固定业务结构。
  const graph = asUnknownRecord(value.value.graph);
  return Object.keys(graph).length > 0 && Array.isArray(graph.nodes) && graph.nodes.length > 0;
});
const outputText = computed(() => {
  const text = asRecordField(value.value.output, "text");
  return typeof text === "string" ? text : "";
});
// AP-S07：诊断与工具调用来自后端持久化记录，不在前端二次推断。
const diag = computed(() => asUnknownRecord(value.value.diagnostics));
const diagError = computed(() => diag.value.error ?? null);
const diagCalls = computed(() => toArrayOrEmpty(asRecordField(diag.value, "toolCalls")));
const diagAttempts = computed(() => toArrayOrEmpty(asRecordField(diag.value, "attempts")));
const diagTimeline = computed(() => toArrayOrEmpty(asRecordField(diag.value, "timeline")));
const diagCounts = computed(() => asUnknownRecord(asRecordField(diag.value, "counts")));
// ONB/INT 深面字段统一收窄为展示视图（模板不再对 unknown 取属性）。
const diagErrorView = computed(() => {
  const record = asUnknownRecord(diag.value.error);
  if (!Object.keys(record).length) return null;
  const text = (key: string) => (typeof record[key] === "string" ? record[key] : "");
  return {
    retryable: record.retryable === true,
    code: text("code"),
    semantic: text("semantic"),
    stage: text("stage"),
    message: text("message"),
    hint: text("hint"),
    at: text("at"),
    nodeInstanceId: text("nodeInstanceId"),
    toolCallId: text("toolCallId"),
    path: text("path"),
    raw: record,
  };
});
// 画布定义结构级守卫（nodes 数组存在即视为可绘制；具体字段由 FlowDesigner 运行时处理）。
function isFlowDefinition(candidate: unknown): candidate is import("../../flow-designer/transform").Definition {
  return Array.isArray(asRecordField(candidate, "nodes"));
}
const graphDefinition = computed(() =>
  isFlowDefinition(value.value.graph) ? value.value.graph : undefined,
);
const graphLayout = computed(() => asUnknownRecord(asRecordField(graphDefinition.value, "layout")));
const fileRefsList = computed(() => {
  const direct = value.value.fileRefs;
  if (Array.isArray(direct)) return direct;
  const nested = asRecordField(value.value.output, "fileRefs");
  return Array.isArray(nested) ? nested : [];
});
const versionRefs = computed(() => asRecordField(value.value, "versionRefs"));
const hasDiagnostics = computed(
  () => !!(diagError.value || diagCalls.value.length || diagAttempts.value.length),
);
function json(value: any) {
  return JSON.stringify(value ?? null, null, 2);
}
function millis(value: any) {
  const n = Number(value);
  if (!Number.isFinite(n)) return "—";
  return n >= 1000 ? `${Math.round(n / 100) / 10}s` : `${n}ms`;
}
function diagnosticSeverity(type?: string) {
  const t = type || "";
  return t.includes("FAILED") || t === "RUN_FINISHED" ? "error" : t.includes("RETRY") ? "warning" : "info";
}
function applyDeepLink() {
  const target = deepNode.value;
  if (!target || deepApplied || !(value.value.nodes || []).length) return;
  deepApplied = true;
  const row = (value.value.nodes || []).find((n: RunNodeStatusRow) => n.nodeDefId === target);
  if (row) selected.value = row;
  const index = (value.value.events || []).findIndex(
    (e: any) => {
      const data = record?.(e?.data) || e?.data || {};
      return data.nodeDefId === target || data.nodeId === target;
    },
  );
  if (index >= 0) {
    active.value = "replay";
    replay.value = index;
  }
}
let stopped = false;
let controller: AbortController;
let timer: ReturnType<typeof setTimeout>;
async function refresh() {
  const result = await api("/admin/runs/" + encodeURIComponent(props.runId), 'GET', undefined, undefined, decodeRunStatus);
  if (current()) value.value = result;
  applyDeepLink();
}
async function connect() {
  if(!current()||blocked.value)return;
  try {
    await refresh();
    if (!current()||blocked.value||terminal(value.value.status)) return;
    controller = new AbortController();
    await streamEvents(
      "/admin/runs/" + encodeURIComponent(props.runId) + "/events",
      () => useAuth().token,
      value.value.eventsWatermark,
      () => {if(current()&&!blocked.value)void refresh().catch(observationError);},
      controller.signal,
      () => {
        auth.invalidate(session.generation);
      },
      session,
    );
  } catch (e: any) {
    observationError(e);
  } finally {
    if (current()&&!blocked.value && !terminal(value.value.status))
      timer = setTimeout(connect, 2000);
  }
}
function observationError(e:any){
  if(!current()||e.name==='AbortError')return;
  if(authorityFailure(e)||protocolFailure(e)){blocked.value=true;controller?.abort();clearTimeout(timer);}
  error.value=e.message;
}
function reconnect(){if(!current())return;blocked.value=false;error.value='';void connect();}
async function cancel() {
  if (!confirm("确认取消本次正式运行？已产生的外部效果不会自动撤销。")) return;
  try {
    await api(
      "/admin/runs/" + encodeURIComponent(props.runId) + "/cancel",
      "POST",
    );
    await refresh();
  } catch (e: any) {
    error.value = e.message;
  }
}
onMounted(connect);
onBeforeUnmount(() => {
  stopped = true;
  controller?.abort();
  clearTimeout(timer);
});
</script>
<template>
  <div v-if="error" class="error" role="alert">{{ error }}</div>
  <el-button v-if="blocked" @click="reconnect">重新连接观察</el-button>
  <div class="panel">
    <div class="row">
      <div>
        <h2>{{ runId }}</h2>
        <span :class="['status', value.status]">{{ value.status }}</span>
      </div>
      <el-button
        v-if="value.status && !terminal(value.status)"
        type="danger"
        plain
        @click="cancel"
        >取消运行</el-button
      >
    </div>
    <el-tabs v-model="active"
      ><el-tab-pane label="节点摘要" name="summary"
        ><div v-if="diagErrorView" class="diag-banner" :class="{ retryable: diagErrorView.retryable }">
          <div>
            <strong>{{ diagErrorView.code || diagErrorView.semantic || "运行失败" }}</strong>
            <span class="diag-stage">{{ diagErrorView.stage }}</span>
            <span v-if="diagErrorView.retryable" class="pill">可重试</span>
          </div>
          <p>{{ diagErrorView.message }}</p>
          <p v-if="diagErrorView.hint" class="muted">{{ diagErrorView.hint }}</p>
          <el-button link type="primary" @click="active = 'diagnostics'">查看完整诊断与工具调用</el-button>
        </div>
        <!-- UIF-A5：只读画布复用主设计器组件（provide 隔离由组件自身 readOnly 保证），
             节点状态来自本次运行快照；点击节点行联动选中。 -->
        <FlowDesigner
          v-if="hasGraph && graphDefinition"
          :definition="graphDefinition"
          :layout="graphLayout"
          :states="nodeStates"
          :run-nodes="value.nodes || []"
          read-only
        />
        <!-- 深链/过滤条：来自 ?nodeId= 或手动筛选，提示当前只看单节点并可一键清除 -->
        <p v-if="nodeFilter" class="muted" role="status">
          只看节点 <code>{{ nodeFilter }}</code>（来自链接定位）
          <el-button link type="primary" @click="nodeFilter = ''">查看全部节点</el-button>
        </p>
        <el-table :data="visibleNodes" highlight-current-row @row-click="selected = $event"
          ><el-table-column prop="nodeDefId" label="节点" /><el-table-column
            prop="node_type"
            label="类型" /><el-table-column label="状态"
            ><template #default="{ row }"
              ><span :class="['status', row.status]">{{
                row.status
              }}</span></template
            ></el-table-column
          ><el-table-column
            prop="started_at"
            label="开始时间" /><el-table-column
            prop="ended_at"
            label="结束时间"
        /></el-table>
        <h2>运行输出</h2>
        <p v-if="outputWarning(value.output)" class="output-warning" role="status">{{ outputWarning(value.output) }}</p>
        <Artifacts :files="fileRefsList" :run-id="runId" admin/>
        <div v-if="outputText" class="output-text" data-testid="run-output-text" v-html="safeMarkdown(outputText)"></div>
        <details v-if="outputText" class="output-raw">
          <summary>查看原始输出 JSON</summary>
          <pre>{{ JSON.stringify(value.output, null, 2) }}</pre>
        </details>
        <pre v-else>{{
          JSON.stringify(
            value.output ??
              (terminal(value.status)
                ? "运行完成，未返回输出"
                : "运行尚无输出"),
            null,
            2,
          )
        }}</pre>
        <details
          v-if="
            value.error &&
            (typeof value.error === 'string' || Object.keys(asUnknownRecord(value.error)).length)
          "
          class="output-raw"
        >
          <summary>查看原始错误 JSON</summary>
          <pre class="error">{{ json(value.error) }}</pre>
        </details></el-tab-pane
      ><el-tab-pane label="诊断与工具" name="diagnostics">
        <template v-if="hasDiagnostics">
          <div v-if="diagErrorView" class="diag-card" :class="{ retryable: diagErrorView.retryable }">
            <div class="diag-card-head">
              <strong>{{ diagErrorView.code || diagErrorView.semantic || "运行失败" }}</strong>
              <span class="diag-stage">{{ diagErrorView.stage }}</span>
              <span class="pill">{{ diagErrorView.retryable ? "可重试" : "不可重试" }}</span>
              <small class="muted">{{ diagErrorView.at || "—" }}</small>
            </div>
            <p>{{ diagErrorView.message }}</p>
            <p v-if="diagErrorView.hint" class="muted">{{ diagErrorView.hint }}</p>
            <dl class="diag-meta">
              <template v-if="diagErrorView.nodeInstanceId">
                <dt>节点实例</dt><dd>{{ diagErrorView.nodeInstanceId }}</dd>
              </template>
              <template v-if="diagErrorView.toolCallId">
                <dt>工具调用</dt><dd>{{ diagErrorView.toolCallId }}</dd>
              </template>
              <template v-if="diagErrorView.path">
                <dt>错误路径</dt><dd>{{ diagErrorView.path }}</dd>
              </template>
            </dl>
            <details class="output-raw">
              <summary>查看原始错误 JSON</summary>
              <pre>{{ json(diagErrorView.raw) }}</pre>
            </details>
          </div>
          <p class="diag-counts">
            尝试 {{ diagCounts.attempts ?? 0 }} 次（失败 {{ diagCounts.failedAttempts ?? 0 }}）·
            工具调用 {{ diagCounts.toolCalls ?? 0 }} 次（失败 {{ diagCounts.failedToolCalls ?? 0 }}，重试
            {{ diagCounts.toolRetries ?? 0 }} 次）· 事件 {{ diagCounts.events ?? 0 }} 条
          </p>
          <h3>尝试记录</h3>
          <el-table v-if="diagAttempts.length" :data="diagAttempts" size="small">
            <el-table-column prop="nodeInstanceId" label="节点实例" width="110" />
            <el-table-column prop="attemptNo" label="次数" width="70" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column label="耗时" width="90">
              <template #default="{ row }">{{ millis(row.durationMs) }}</template>
            </el-table-column>
            <el-table-column label="模型/错误">
              <template #default="{ row }">
                <span v-if="row.error && Object.keys(row.error).length" class="error-text">{{
                  row.error.message || row.error.semantic || json(row.error)
                }}</span>
                <span v-else class="muted">{{ row.routedModel?.model || row.routedModel?.modelName || "—" }}</span>
              </template>
            </el-table-column>
          </el-table>
          <p v-else class="muted">没有尝试记录。</p>
          <h3>工具调用</h3>
          <el-table v-if="diagCalls.length" :data="diagCalls" size="small">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="tool-detail">
                  <strong>参数</strong>
                  <pre>{{ json(row.args) }}</pre>
                  <strong>结果</strong>
                  <pre>{{ json(row.result) }}</pre>
                  <template v-if="row.recoveryError">
                    <strong>恢复错误</strong>
                    <pre class="error">{{ json(row.recoveryError) }}</pre>
                  </template>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="skillCode" label="技能" min-width="150" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column label="耗时" width="90">
              <template #default="{ row }">{{ millis(row.durationMs) }}</template>
            </el-table-column>
            <el-table-column label="重试" width="70">
              <template #default="{ row }">{{ row.retryCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="错误语义" min-width="160">
              <template #default="{ row }">
                <span v-if="row.errorSemantic" class="error-text">{{ row.errorSemantic }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>
          <p v-else class="muted">本次运行没有工具调用。</p>
          <h3>事件时间线</h3>
          <el-table :data="diagTimeline" size="small" max-height="360">
            <el-table-column prop="seq" label="#" width="60" />
            <el-table-column label="时间" width="180">
              <template #default="{ row }">{{ row.at || "—" }}</template>
            </el-table-column>
            <el-table-column label="事件" min-width="180">
              <template #default="{ row }">
                <span :class="['severity', 'severity-' + (row.severity || diagnosticSeverity(row.type))]">
                  {{ row.label || row.type }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="摘要" min-width="240">
              <template #default="{ row }">
                <span class="muted">{{ row.type }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else description="本次运行没有错误、尝试或工具调用诊断记录" /></el-tab-pane
      ><el-tab-pane label="版本与依赖" name="versions">
        <h3>本次运行冻结来源</h3>
        <RunObservation :observation="value.observation" />
        <details class="output-raw"><summary>查看原始版本与依赖记录</summary><pre>{{ JSON.stringify(versionRefs ?? null, null, 2) }}</pre></details>
        </el-tab-pane
      ><el-tab-pane label="事件回放" name="replay"
        ><p class="muted">历史事件来自持久化执行记录，不会重新执行技能。</p>
        <el-slider
          v-model="replay"
          :max="Math.max((value.events?.length || 1) - 1, 0)"
          :show-tooltip="false"
        />
        <pre>{{
          JSON.stringify(value.events?.[replay] || "暂无事件", null, 2)
        }}</pre>
      </el-tab-pane
      ><el-tab-pane label="执行时间线" name="timeline"
        ><!-- UIF-A5：树形时间线——行=节点（状态/耗时），点行联动画布选中与检查器 -->
        <el-table
          :data="timeline"
          highlight-current-row
          :row-class-name="timelineRowClass"
          @row-click="timelineSelect"
          ><el-table-column prop="label" label="节点"
            ><template #default="{ row }"
              ><span :class="['status', row.status]" style="margin-right: 0.5rem"
                >{{ row.status }}</span
              >{{ row.label }}</template
            ></el-table-column
          ><el-table-column label="耗时"
            ><template #default="{ row }">{{ row.duration }}</template></el-table-column
          ><el-table-column label="错误"
            ><template #default="{ row }"
              ><span v-if="row.errorMessage" class="error-text">{{ row.errorMessage }}</span
              ><span v-else class="muted">—</span></template
            ></el-table-column
          ></el-table
        ></el-tab-pane></el-tabs
    ><el-collapse v-if="selected"
      ><el-collapse-item
        :title="'节点检查器 · ' + selected.nodeDefId"
        name="node"
      >
        <pre>{{ JSON.stringify(selected, null, 2) }}</pre>
      </el-collapse-item></el-collapse
    >
  </div>
</template>
<style scoped>
:deep(.timeline-failed) {
  --el-table-tr-bg-color: color-mix(in srgb, var(--st-fail) 6%, white);
}
.output-text {
  padding: 1rem 1.25rem;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.8;
}
.output-text :deep(h1),
.output-text :deep(h2),
.output-text :deep(h3) {
  margin: 1rem 0 0.5rem;
  font-size: 1rem;
}
.output-text :deep(p) {
  margin: 0.5rem 0;
}
.output-text :deep(ul),
.output-text :deep(ol) {
  margin: 0.5rem 0;
  padding-left: 1.5rem;
}
.output-text :deep(table) {
  border-collapse: collapse;
  margin: 0.75rem 0;
  width: 100%;
  font-size: 13px;
}
.output-text :deep(th),
.output-text :deep(td) {
  border: 1px solid var(--el-border-color);
  padding: 6px 10px;
  text-align: left;
}
.output-text :deep(th) {
  background: var(--el-fill-color);
}
.output-text :deep(code) {
  background: var(--el-fill-color-dark);
  border-radius: 4px;
  padding: 1px 5px;
  font-size: 0.9em;
}
.output-text :deep(pre) {
  background: var(--el-fill-color-dark);
  border-radius: 6px;
  padding: 0.75rem;
  overflow-x: auto;
}
.output-raw {
  margin-top: 0.75rem;
}
.output-raw summary {
  cursor: pointer;
  color: var(--el-color-primary);
  font-size: 13px;
}
/* AP-S07：失败诊断先给结论，再给原始载荷；成功运行不重复占用首屏。 */
.diag-banner,
.diag-card {
  margin: 0 0 1rem;
  border: 1px solid #f3c2c2;
  background: #fff8f8;
  border-radius: 8px;
  padding: 0.75rem 0.9rem;
}
.diag-banner.retryable,
.diag-card.retryable {
  border-color: #f3d19e;
  background: #fdf8ef;
}
.diag-banner p,
.diag-card p {
  margin: 0.45rem 0;
  line-height: 1.65;
}
.diag-stage {
  display: inline-block;
  margin-left: 0.45rem;
  font-size: 0.75rem;
  color: #606266;
}
.diag-card-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}
.diag-card-head small {
  margin-left: auto;
}
.diag-card-head .pill {
  background: #fef0f0;
  color: #c45656;
  border-radius: 999px;
  padding: 0.08rem 0.45rem;
  font-size: 0.75rem;
}
.diag-card.retryable .pill {
  background: #fdf6ec;
  color: #b88230;
}
.diag-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 1rem;
  margin: 0.5rem 0;
  font-size: 0.8rem;
}
.diag-meta dt {
  color: #909399;
}
.diag-meta dd {
  margin: 0;
  color: #303133;
}
.diag-counts {
  margin: 0.25rem 0 0.75rem;
  color: #606266;
  font-size: 0.85rem;
}
.severity-error {
  color: #c45656;
}
.severity-warning {
  color: #b88230;
}
.severity-info {
  color: #606266;
}
.tool-detail pre {
  max-height: 18rem;
  overflow: auto;
}
</style>
