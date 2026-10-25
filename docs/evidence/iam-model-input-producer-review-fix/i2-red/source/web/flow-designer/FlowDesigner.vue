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
    grid: true,
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
    for (const [id, status] of Object.entries(states || {})) {
      const cell = graph?.getCellById(id);
      const token =
        status === "SUCCEEDED"
          ? "ok"
          : status === "FAILED"
            ? "fail"
            : status.startsWith("WAITING")
              ? "suspend"
              : status === "RUNNING"
                ? "run"
                : "wait";
      cell?.setAttrs({
        body: {
          stroke: getComputedStyle(document.documentElement).getPropertyValue(
            "--st-" + token,
          ),
          strokeWidth: 2,
        },
      });
    }
  },
  { deep: true },
);
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
</style>
