<script setup lang="ts">
import CodeViewer from "../shared/CodeViewer.vue";
import FlowDesigner from "../flow-designer/FlowDesigner.vue";
import NamedInputs from './NamedInputs.vue';
import { ref, watch, onBeforeUnmount } from "vue";
import { resumePayload } from "./control";
import { mockRows, mockRules, type MockRow } from "./mock-rules";
import { streamEvents, applyEvent, terminal, authorityFailure, type EventState } from "./events";
import { useAuth, useApi } from "../shared/api";
const props = defineProps<{ appCode: string }>();
const auth = useAuth();
const api = useApi();
const session=auth.snapshot();
const observationStopped=ref(false);
const inputForm = ref<InstanceType<typeof NamedInputs>>();
const draft = ref<any>();
const draftLoading = ref(false);
let draftSequence = 0;
let draftRequest = new AbortController();
const flow = ref<any>();
const inspector = ref<any>();
const mode = ref("FULL");
const breakpoints = ref<string[]>([]);
const mocks = ref<MockRow[]>([]);
const controlsBase = ref<any>({});
const controlsRevision = ref<number>();
async function controls(save = false) {
  const sequence = draftSequence;
  busy.value = true;
  error.value = "";
  try {
    if (save && controlsRevision.value === undefined)
      throw Error("请先载入当前控制版本");
    const data = await api(
      "/admin/debug-runs/" + runId.value + "/controls",
      save ? "PUT" : "GET",
      save
        ? {
            ...controlsBase.value,
            expectedRevision: controlsRevision.value,
            breakpoints: breakpoints.value,
            mocks: mockRules(mocks.value),
          }
        : undefined,
    );
    if (disposed || sequence !== draftSequence) return;
    controlsRevision.value = data.revision;
    controlsBase.value = data.controls;
    breakpoints.value = data.controls.breakpoints || [];
    mocks.value = mockRows(data.controls.mocks || []);
  } catch (e: any) {
    if (disposed || sequence !== draftSequence) return;
    controlsRevision.value = undefined;
    error.value = e.message;
  } finally {
    if (!disposed && sequence === draftSequence) busy.value = false;
  }
}
const runId = ref("");
const snapshot = ref<any>({});
const state = ref<EventState>({ watermark: 0, events: [], nodes: {} });
const error = ref("");
const busy = ref(false);
const connected = ref(false);
let controller: AbortController;
let timer: ReturnType<typeof setTimeout>;
let disposed = false;
let retry = 0;
async function restore() {
  const id = runId.value, sequence = draftSequence;
  const result = await api(`/admin/debug-runs/${id}/status`);
  if (disposed || sequence !== draftSequence || id !== runId.value) return;
  snapshot.value = result;
  state.value = {
    watermark: snapshot.value.eventsWatermark || 0,
    events: snapshot.value.events || [],
    nodes: Object.fromEntries(
      (snapshot.value.nodes || []).map((n: any) => [
        n.nodeDefId || n.id,
        n.status,
      ]),
    ),
  };
}
async function connect() {
  if (disposed || !session.current()||observationStopped.value||!runId.value) return;
  const id = runId.value, sequence = draftSequence;
  const current = () => !disposed && session.current()&&sequence === draftSequence && id === runId.value;
  try {
    await restore();
    if (!current() || terminal(snapshot.value.status)) return;
    controller = new AbortController();
    connected.value = true;
    await streamEvents(
      `/admin/runs/${runId.value}/events`,
      () => auth.token,
      state.value.watermark,
      (e) => {
        if (!current()) return;
        const node = snapshot.value.nodes?.find(
          (n: any) => String(n.nodeInstanceId) === String(e.nodeInstanceId),
        );
        state.value = applyEvent(state.value, {
          ...e,
          nodeDefId: e.nodeDefId || node?.nodeDefId,
        });
        retry = 0;
        if (
          e.type === "WAITING_STEP" ||
          e.type === "WAITING_APPROVAL" ||
          e.type === "RUN_FINISHED"
        )
          void restore().catch(observationError);
      },
      controller.signal,
      () => {
        if (current()) auth.invalidate(session.generation);
      },
      session,
    );
  } catch (e: any) {
    if(current())observationError(e);
  } finally {
    if (current()) connected.value = false;
    if (current() && !observationStopped.value&&!terminal(snapshot.value.status))
      timer = setTimeout(connect, Math.min(30000, 1000 * 2 ** retry++));
  }
}
function observationError(e:any){
  if(disposed||!session.current()||e.name==='AbortError')return;
  if(authorityFailure(e)){observationStopped.value=true;controller?.abort();clearTimeout(timer);}
  error.value=e.message+(observationStopped.value?'；请手动重新连接观察。':'；正在恢复权威状态。');
}
async function refreshStatus(){try{await restore();}catch(e){observationError(e);}}
function reconnect(){if(disposed||!session.current())return;observationStopped.value=false;error.value='';void connect();}
async function start() {
  if (busy.value || !draft.value || !inputForm.value) return;
  const code = props.appCode, sequence = draftSequence;
  busy.value = true;
  error.value = "";
  try {
    controller?.abort();
    clearTimeout(timer);
    const result = await api("/admin/debug-runs", "POST", {
      appCode: code,
      mode: mode.value,
      inputs: inputForm.value.collect(),
      breakpoints: breakpoints.value,
      mocks: mockRules(mocks.value),
    });
    if (disposed || sequence !== draftSequence) return;
    controlsRevision.value=undefined;controlsBase.value={};
    runId.value = result.runId;
    observationStopped.value=false;
    void connect();
  } catch (e: any) {
    if (!disposed && sequence === draftSequence) error.value = e.message;
  } finally {
    if (!disposed && sequence === draftSequence) busy.value = false;
  }
}
async function action(action: string) {
  const sequence = draftSequence;
  busy.value = true;
  try {
    const p = snapshot.value.paused;
    if (!p) throw new Error("当前没有可放行的挂起节点，请刷新状态");
    await api(
      `/admin/debug-runs/${runId.value}/resume`,
      "POST",
      resumePayload(p, action, crypto.randomUUID()),
    );
    if (!disposed && sequence === draftSequence) await restore();
  } catch (e: any) {
    if (!disposed && sequence === draftSequence) error.value = e.message;
  } finally {
    if (!disposed && sequence === draftSequence) busy.value = false;
  }
}
async function cancel() {
  if (!confirm("终止后本次调试不能继续，确认终止？")) return;
  const sequence = draftSequence;
  try {
    await api(`/admin/debug-runs/${runId.value}/cancel`, "POST");
    if (!disposed && sequence === draftSequence) await restore();
  } catch (e: any) {
    if (!disposed && sequence === draftSequence) error.value = e.message;
  }
}
async function inspect(kind: string) {
  const sequence = draftSequence;
  try {
    const p = snapshot.value.paused;
    const result = await api(
      kind === "prompt"
        ? "/admin/debug-runs/" +
            runId.value +
            "/node/" +
            p.nodeInstanceId +
            "/prompt"
        : "/admin/debug-runs/" + runId.value + "/variables",
    );
    if (!disposed && sequence === draftSequence) inspector.value = result;
  } catch (e: any) {
    if (!disposed && sequence === draftSequence) error.value = e.message;
  }
}
function loadDraft() {
  const sequence = ++draftSequence;
  draftRequest.abort(); draftRequest = new AbortController();
  controller?.abort(); clearTimeout(timer);
  connected.value = false; retry = 0; inspector.value = undefined; controlsRevision.value = undefined; controlsBase.value = {};
  breakpoints.value = []; mocks.value = [];
  draft.value = undefined; flow.value = undefined; draftLoading.value = true; busy.value = false; error.value = '';
  runId.value = ''; snapshot.value = {}; state.value = {watermark:0,events:[],nodes:{}};
  void api("/admin/apps/" + encodeURIComponent(props.appCode), 'GET', undefined, draftRequest.signal)
    .then((a) => {
      if (disposed || sequence !== draftSequence) return;
      if (!a.draftId) throw Error('草稿信息缺少标识，请刷新应用后重试');
      draft.value = a;
      if (a.type === "FLOW")
        flow.value = { definition: a.definition, layout: a.layout };
    })
    .catch((e) => { if (!disposed && sequence === draftSequence && e.name !== 'AbortError') error.value = e.message; })
    .finally(() => { if (!disposed && sequence === draftSequence) draftLoading.value = false; });
  // A new workspace never restores the previous identity's selected run.
  observationStopped.value=false;
}
watch(() => props.appCode, loadDraft, {immediate:true});
onBeforeUnmount(() => {
  disposed = true;
  draftSequence++; draftRequest.abort();
  controller?.abort();
  clearTimeout(timer);
});
</script>
<template>
  <div class="panel">
    <div class="row debug-header">
      <div>
        <h2>调试预览</h2>
        <span class="muted"
          >草稿运行与正式运行隔离，离开页面不会终止任务。</span
        >
      </div>
      <div class="actions">
        <el-radio-group v-model="mode"
          ><el-radio-button value="FULL">全量</el-radio-button
          ><el-radio-button value="STEP">单步</el-radio-button></el-radio-group
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="!draft || (!!runId && !terminal(snapshot.status))"
          v-permission="'edit'"
          @click="start"
          >开始调试</el-button
        >
      </div>
    </div>
    <p v-if="draftLoading" class="muted" role="status">正在载入草稿输入…</p>
    <NamedInputs v-if="draft" :key="appCode + ':' + draft.draftId" ref="inputForm" :app-code="appCode" :draft-id="draft.draftId" :declaration="draft.type === 'FLOW' ? draft.definition?.inputs : (draft.config?.inputSchema ?? draft.config?.inputs ?? draft.inputSchema)" />
    <el-button v-else-if="!draftLoading" @click="loadDraft">重新载入草稿</el-button>
    <details>
      <summary>断点与技能 Mock</summary>
      <p class="muted">
        规则仅影响本次调试。运行中修改须先载入控制版本，再保存后续节点规则。
      </p>
      <el-select
        v-model="breakpoints"
        multiple
        placeholder="选择断点节点"
        aria-label="断点节点"
        ><el-option
          v-for="n in flow?.definition?.nodes || []"
          :key="n.id"
          :value="n.id"
          :label="n.name || n.id"
      /></el-select>
      <div v-for="(m, i) in mocks" :key="i" class="row">
        <el-input
          v-model="m.skillCode"
          placeholder="技能编码"
          aria-label="Mock 技能编码"
        /><el-select v-model="m.mode" aria-label="Mock 模式"
          ><el-option value="FIXED" label="固定响应" /><el-option
            value="LAST_REAL"
            label="复用最近真实结果" /></el-select
        ><el-input
          v-if="m.mode === 'FIXED'"
          v-model="m.response"
          type="textarea"
          placeholder="固定响应 JSON"
          aria-label="Mock 固定响应"
        />
        <div v-if="m.mode === 'LAST_REAL'">
          <p class="muted">
            由服务端匹配当前身份、参数、技能版本及合同。无匹配结果会报错，不发起真实业务调用。
          </p>
          <details>
            <summary>高级：指定来源调用（可省略）</summary>
            <el-input-number
              v-model="m.sourceCallId"
              :min="1"
              :precision="0"
              aria-label="来源调用标识"
              placeholder="自动匹配"
            />
          </details>
        </div>
        <el-button @click="mocks.splice(i, 1)">移除</el-button>
      </div>
      <el-button
        @click="mocks.push({ skillCode: '', response: '{}', mode: 'FIXED' })"
        >添加技能 Mock</el-button
      >
      <template v-if="runId && !terminal(snapshot.status)"
        ><el-button :loading="busy" @click="controls()">载入当前控制</el-button
        ><el-button
          :loading="busy"
          v-permission="'edit'"
          @click="controls(true)"
          >保存调试控制</el-button
        ><span class="muted"
          >版本 {{ controlsRevision ?? "未载入" }}</span
        ></template
      >
    </details>
  </div>
  <div v-if="error" class="error" role="alert">{{ error }}</div>
  <el-button v-if="observationStopped" @click="reconnect">重新连接观察</el-button>
  <div v-if="snapshot.status === 'FAILED'" class="error" role="alert">
    运行失败：{{
      snapshot.error?.message ||
      snapshot.error?.code ||
      snapshot.nodes?.find((n: any) => n.status === "FAILED")?.error?.message ||
      "请查看节点错误详情"
    }}。检查输入、来源权限和技能版本后可重新开始调试。
  </div>
  <div v-if="runId" class="panel">
    <div class="row">
      <div>
        <strong>{{ runId }}</strong>
        <span :class="['status', snapshot.status]">{{ snapshot.status }}</span>
        <span class="muted"
          >{{ connected ? "事件已连接" : "事件未连接" }} · 游标
          {{ state.watermark }}</span
        >
      </div>
      <div class="actions">
        <el-button @click="refreshStatus">刷新状态</el-button
        ><el-button
          v-for="a in snapshot.paused?.allowedActions?.filter(
            (x: string) => x !== 'CANCEL',
          )"
          :key="a"
          :disabled="busy"
          v-permission="'edit'"
          @click="action(a)"
          >{{
            a === "NEXT_NODE"
              ? "执行本节点"
              : a === "RUN_TO_BREAKPOINT"
                ? "运行到断点"
                : "运行到结束"
          }}</el-button
        ><el-button type="danger" plain v-permission="'edit'" @click="cancel"
          >终止</el-button
        >
      </div>
    </div>
    <FlowDesigner
      v-if="flow?.definition"
      :definition="flow.definition"
      :layout="flow.layout || {}"
      :states="state.nodes"
      read-only
    />
    <div v-if="snapshot.paused">
      <h2>挂起检查器</h2>
      <el-button @click="inspect('prompt')">查看完整请求</el-button
      ><el-button @click="inspect('variables')">查看变量</el-button>
      <CodeViewer
        v-if="inspector"
        :content="JSON.stringify(inspector, null, 2)"
      />
      <pre>{{ JSON.stringify(snapshot.paused, null, 2) }}</pre>
    </div>
    <el-table
      :data="
        Object.entries(state.nodes).map(([node, status]) => ({ node, status }))
      "
      ><el-table-column prop="node" label="节点" /><el-table-column label="状态"
        ><template #default="{ row }"
          ><span :class="['status', row.status]">{{
            row.status
          }}</span></template
        ></el-table-column
      ></el-table
    ><el-collapse
      ><el-collapse-item title="事件与输出" name="events">
        <pre>{{
          JSON.stringify(
            {
              events: state.events,
              output: snapshot.output,
              error: snapshot.error,
              versionRefs: snapshot.versionRefs,
            },
            null,
            2,
          )
        }}</pre>
      </el-collapse-item></el-collapse
    >
  </div>
  <el-empty v-else description="输入数据并开始调试" />
</template>
<style scoped>
@media(max-width:600px){.debug-header{flex-direction:column;align-items:stretch;gap:1rem}.debug-header .actions{justify-content:space-between;flex-wrap:wrap;gap:.75rem}.debug-header .el-radio-group{display:inline-flex;flex-wrap:nowrap}.debug-header .el-button{min-height:44px}}
@media(max-width:600px){
  .panel > .row:not(.debug-header){flex-direction:column;align-items:stretch}
  .panel > .row:not(.debug-header) > div{min-width:0;overflow-wrap:anywhere}
  .panel > .row:not(.debug-header) .actions{flex-wrap:wrap}
  /* The read-only graph needs real canvas width before X6 can fit the nodes. */
  .panel :deep(.canvas-layout){grid-template-columns:minmax(0,1fr);min-width:0}
  .panel :deep(.canvas),.panel :deep(.properties-panel){min-width:0;max-width:100%}
}
</style>
