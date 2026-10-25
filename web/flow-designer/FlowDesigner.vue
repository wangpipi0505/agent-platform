<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from "vue";
import { History } from "@antv/x6-plugin-history";
import { Graph } from "@antv/x6";
import { mergeGraph, splitGraph, type Definition } from "./transform";
import { readDraft, writeDraft } from "../shared/drafts";
import NodeProperties from "./NodeProperties.vue";
import { nodeTypes, nodeIssues } from "./node-metadata";
const props = defineProps<{
  definition: Definition;
  layout: any;
  states?: Record<string, string>;
  /** UIF-A2：运行节点明细（output 等），供边数据量标签；与 states 同源自状态接口。 */
  runNodes?: any[];
  readOnly?: boolean;
  draftKey?: string;
  draftRev?: number;
  catalogs?: { models: any[]; skills: any[]; templates: any[] };
}>();
const emit = defineEmits<{ save: [value: any] }>();
const host = ref<HTMLElement>();
const graphHost = ref<HTMLElement>();
const selected = ref<any>();
const selectedData = ref<any>();
const propertyPanel = ref<InstanceType<typeof NodeProperties>>();
const metadata = ref<any>(JSON.parse(JSON.stringify(props.definition)));
const issues = ref<{ nodeId?: string; message: string }[]>([]);
const error = ref("");
const recoverable = ref(readDraft(props.draftKey, props.draftRev));
let draftTimer: ReturnType<typeof setTimeout>;
let graph: Graph;
let loading = false;
let observer: ResizeObserver;
function load() {
  loading = true;
  metadata.value = JSON.parse(JSON.stringify(props.definition));
  selected.value = null;
  selectedData.value = null;
  if (graph) graph.fromJSON(mergeGraph(props.definition, props.layout));
  loading = false;
}
function selectNode(node: any) {
  if (
    selected.value?.id !== node.id &&
    propertyPanel.value &&
    !propertyPanel.value.flush()
  )
    return;
  selected.value = node;
  selectedData.value = JSON.parse(JSON.stringify(node.getData()));
}
function add(type: string) {
  if (propertyPanel.value && !propertyPanel.value.flush()) return;
  const id = "n_" + crypto.randomUUID().slice(0, 8);
  const cell = mergeGraph(
    {
      ...props.definition,
      nodes: [
        {
          id,
          type,
          name: nodeTypes.find((n) => n.type === type)?.label || type,
        },
      ],
      edges: [],
    },
    {},
  ).cells[0];
  (cell as any).position = { x: 80 + graph.getNodes().length * 210, y: 100 };
  const node = graph.addNode(cell);
  selectNode(node);
  graph.zoomToFit({ maxScale: 1, padding: 40 });
}
function save() {
  if (propertyPanel.value && !propertyPanel.value.flush()) {
    error.value = "请先修正当前节点参数";
    return;
  }
  const value = splitGraph(graph.toJSON(), metadata.value);
  issues.value = nodeIssues(value.definition);
  error.value = "";
  if (!issues.value.length) emit("save", value);
}
function update(data: any) {
  if (!selected.value || data.id !== selected.value.id) return;
  selected.value.setData(data, { overwrite: true });
  selected.value.setAttrs({
    label: { text: data.name || data.type },
    body: { stroke: "var(--st-wait)", strokeWidth: 2 },
  });
  metadata.value.nodes = graph.getNodes().map((n) => n.getData());
}
function focusNode(id?: string) {
  if (!id) return;
  const node = graph.getCellById(id);
  if (node?.isNode()) {
    selectNode(node);
    graph.centerCell(node);
    node.setAttrs({ body: { stroke: "var(--st-fail)", strokeWidth: 3 } });
  }
}
function remember() {
  if (loading || props.readOnly || !props.draftKey || !graph) return;
  clearTimeout(draftTimer);
  draftTimer = setTimeout(() => {
    try {
      writeDraft(props.draftKey!, props.draftRev!, {
        graph: graph.toJSON(),
        definition: metadata.value,
      });
    } catch {
      error.value = "本地草稿空间不足，请及时保存";
    }
  }, 400);
}
function recover() {
  if (!recoverable.value) return;
  metadata.value = recoverable.value.definition || metadata.value;
  graph.fromJSON(recoverable.value.graph || recoverable.value);
  recoverable.value = null;
}
function key(e: KeyboardEvent) {
  if (props.readOnly) return;
  const input =
    e.target instanceof HTMLInputElement ||
    e.target instanceof HTMLTextAreaElement;
  if ((e.ctrlKey || e.metaKey) && e.key === "s") {
    e.preventDefault();
    save();
  }
  if (input) return;
  if ((e.ctrlKey || e.metaKey) && e.key === "z") {
    e.preventDefault();
    graph.undo();
    if (selected.value)
      selectedData.value = JSON.parse(JSON.stringify(selected.value.getData()));
  }
  if ((e.ctrlKey || e.metaKey) && e.key === "y") {
    e.preventDefault();
    graph.redo();
    if (selected.value)
      selectedData.value = JSON.parse(JSON.stringify(selected.value.getData()));
  }
  if (e.key === "Delete" && selected.value) {
    graph.removeCell(selected.value);
    selected.value = null;
    selectedData.value = null;
  }
}
onMounted(() => {
  graph = new Graph({
    container: graphHost.value!,
    width: host.value!.clientWidth,
    height: host.value!.clientHeight,
    background: { color: "var(--surface)" },
    grid: {
      // VIS-2：点阵网格（n8n/dify 画布同款 16px 规格），替代默认线网格
      type: "dot",
      size: 16,
      args: { color: "color-mix(in srgb, var(--muted, #64748b) 30%, transparent)", thickness: 1.5 },
    },
    interacting: props.readOnly ? false : undefined,
    panning: true,
    mousewheel: { enabled: true, modifiers: ["ctrl", "meta"] },
    connecting: { allowBlank: false, allowLoop: false, snap: true },
  });
  graph.use(new History({ enabled: true }));
  load();
  graph.on(
    "cell:added cell:removed cell:change:position cell:change:data edge:connected",
    () => {
      if (!loading)
        metadata.value.nodes = graph.getNodes().map((n) => n.getData());
      remember();
    },
  );
  graph.on("node:click", ({ node }) => selectNode(node));
  observer = new ResizeObserver(() => {
    graph.resize(host.value!.clientWidth, host.value!.clientHeight);
    graph.zoomToFit({ maxScale: 1, padding: 40 });
  });
  observer.observe(host.value!);
  window.addEventListener("keydown", key);
});
watch(metadata, remember, { deep: true });
watch(
  () => props.definition,
  () => {
    load();
    if (props.draftKey) localStorage.removeItem("draft:" + props.draftKey);
    recoverable.value = null;
  },
);
watch(
  () => props.states,
  (states) => {
    // UIF-A2 运行态着色（对齐 n8n 语义）：节点按状态描边（等待=虚线呼吸动画），
    // 边按两端节点状态取优先级 running>error>success>waiting 染色——
    // 用户不打开任何面板即可读出整条执行链路的状态。
    const statusOf = (id: string) => states?.[id];
    const colorOf = (id: string) =>
      getComputedStyle(document.documentElement).getPropertyValue(
        "--st-" +
          (statusOf(id) === "SUCCEEDED"
            ? "ok"
            : statusOf(id) === "FAILED"
              ? "fail"
              : statusOf(id)?.startsWith("WAITING")
                ? "suspend"
                : statusOf(id) === "RUNNING"
                  ? "run"
                  : "wait"),
      );
    for (const [id, status] of Object.entries(states || {})) {
      const cell = graph?.getCellById(id);
      const running = status === "RUNNING";
      const waiting = status?.startsWith("WAITING") ?? false;
      cell?.setAttrs({
        body: {
          stroke: colorOf(id),
          strokeWidth: running ? 2.5 : 2,
          // 等待态虚线+呼吸类；运行态实线+旋转流光类；其余静止。
          strokeDasharray: waiting ? "6 4" : null,
          class: waiting
            ? "node-waiting-breath"
            : running
              ? "node-running-spin"
              : null,
        },
      });
    }
    // 边状态 = 源/目标节点状态按优先级取色；源节点成功时标输出 item 数。
    const rank = (v?: string) =>
      v === "RUNNING" ? 4 : v === "FAILED" ? 3 : v === "SUCCEEDED" ? 2 : v?.startsWith("WAITING") ? 1 : 0;
    for (const edge of graph?.getEdges() || []) {
      const sourceId = String(edge.getSourceCellId() || "").replace(/^node_/, "");
      const targetId = String(edge.getTargetCellId() || "").replace(/^node_/, "");
      const winner = [statusOf(sourceId), statusOf(targetId)].sort(
        (a, b) => rank(b) - rank(a),
      )[0];
      if (!winner) {
        edge.setAttrs({ line: { stroke: null, strokeWidth: null }, label: { text: null } });
        continue;
      }
      // 取色跟随持有胜出状态的端点；两端同状态时任取。
      const holder = statusOf(sourceId) === winner ? sourceId : targetId;
      edge.setAttrs({
        line: { stroke: colorOf(holder), strokeWidth: rank(winner) >= 2 ? 2 : 1.5 },
        label: { text: winner === "SUCCEEDED" ? itemCountLabel(sourceId) : null },
      });
    }
  },
  { deep: true },
);
// 源节点成功时，把其输出 item 数标在出边上（运行状态 nodes[].output 的行数）。
function itemCountLabel(nodeDefId: string): string | null {
  const detail = (props.runNodes || []).find(
    (n: any) => (n.nodeDefId || n.id) === nodeDefId,
  );
  const output = detail?.output;
  if (Array.isArray(output)) return output.length + " items";
  if (output && typeof output === "object") {
    const firstArray = Object.values(output).find((v) => Array.isArray(v));
    if (Array.isArray(firstArray)) return firstArray.length + " items";
  }
  return null;
}
onBeforeUnmount(() => {
  clearTimeout(draftTimer);
  observer?.disconnect();
  graph?.dispose();
  window.removeEventListener("keydown", key);
});
defineExpose({ focusNode });
</script>
<template>
  <div v-if="recoverable && !readOnly" class="panel row">
    <span>发现同一修订的未提交草稿。</span
    ><el-button @click="recover">恢复未提交草稿</el-button>
  </div>
  <div v-if="!readOnly" class="row">
    <div class="actions">
      <el-dropdown @command="add"
        ><el-button>添加节点 ▾</el-button
        ><template #dropdown
          ><el-dropdown-menu
            ><el-dropdown-item
              v-for="t in nodeTypes"
              :key="t.type"
              :command="t.type"
              >{{ t.label }} · {{ t.type }}</el-dropdown-item
            ></el-dropdown-menu
          ></template
        ></el-dropdown
      ><el-button @click="graph.zoomToFit({ maxScale: 1, padding: 40 })"
        >适应画布</el-button
      ><span class="muted">拖动连接桩连线 · Ctrl+S 保存 · Delete 删除</span>
    </div>
    <el-button type="primary" @click="save">保存编排</el-button>
  </div>
  <pre v-if="error" class="error" role="alert">{{ error }}</pre>
  <div v-if="issues.length" class="error" role="alert">
    <strong>请修正以下配置后再保存</strong>
    <ul>
      <li v-for="(issue, i) in issues" :key="i">
        <button
          v-if="issue.nodeId"
          class="issue-link"
          @click="focusNode(issue.nodeId)"
        >
          {{ issue.nodeId }} · {{ issue.message }}</button
        ><span v-else>{{ issue.message }}</span>
      </li>
    </ul>
  </div>
  <div class="canvas-layout">
    <div ref="host" class="canvas"><div ref="graphHost" /></div>
    <div class="panel properties-panel">
      <NodeProperties
        v-if="selectedData"
        ref="propertyPanel"
        :key="selectedData.id"
        :value="selectedData"
        :definition="metadata"
        :catalogs="catalogs"
        :read-only="readOnly"
        @update="update"
        @inputs="metadata.inputs = $event"
        @outputs="metadata.outputs = $event"
      /><template v-else
        ><h2>节点属性</h2>
        <el-empty description="点击节点配置属性" :image-size="60"
      /></template>
    </div>
  </div>
</template>
<style scoped>
.properties-panel {
  max-height: 68vh;
  overflow: auto;
}
.issue-link {
  border: 0;
  background: transparent;
  text-decoration: underline;
  color: var(--st-fail-text);
  text-align: left;
  padding: 0.25rem;
}
.canvas-layout {
  grid-template-columns: minmax(0, 1fr) minmax(22rem, 35%);
}
/* UIF-A2：等待态虚线呼吸动画（X6 节点 body 上 class 由 states watcher 挂载）。
   stroke-dashoffset 周期位移产生"缓慢流动"观感，比 JS 动画便宜且可 disabled。 */
:deep(.node-waiting-breath) {
  animation: waiting-breath 1.6s ease-in-out infinite;
}
@keyframes waiting-breath {
  0%,
  100% {
    stroke-dashoffset: 0;
    opacity: 1;
  }
  50% {
    stroke-dashoffset: 10;
    opacity: 0.72;
  }
}
/* VIS-2：运行态 conic-gradient 旋转描边（RUNNING 专用，@property 驱动角度，
   纯 CSS 无 JS 动画循环；与呼吸动画互斥——等待用虚线呼吸，运行用旋转流光）。 */
:deep(.node-running-spin) {
  animation:
    running-spin-border 1.5s linear infinite,
    waiting-breath 1.6s ease-in-out infinite;
}
:deep(.node-running-spin) .x6-node > body,
:deep(.node-running-spin) rect {
  stroke-dasharray: none;
}
@property --spin-angle {
  syntax: "<angle>";
  initial-value: 0deg;
  inherits: false;
}
@keyframes running-spin-border {
  to {
    --spin-angle: 360deg;
  }
}
@media (prefers-reduced-motion: reduce) {
  :deep(.node-waiting-breath) {
    animation: none;
  }
}
</style>
