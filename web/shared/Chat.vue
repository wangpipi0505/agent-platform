<script setup lang="ts">
import { ref, watch, onBeforeUnmount, onMounted } from "vue";
import { useAuth, useApi } from "./api";
import { safeMarkdown, finalAnswer } from "./content";
import { streamEvents, terminal } from "../debug-panel/events";
import { decodeRunStatus, record, eventText } from './run-protocol';
import { observeRun } from "./run-observer";
import RunAuthorityPanel from './RunAuthorityPanel.vue';
import {emptyAuthority,readRunAuthority} from './server-authority';
import InlineApprovals from "./InlineApprovals.vue";
import Artifacts from "./Artifacts.vue";
import SchemaInputs from "./SchemaInputs.vue";
import { inputFields } from "./input-schema";
import { restoreSession, writeSessionUrl } from "./session";
import { confirmDanger } from "./components/DangerConfirm";
const sessionId = ref(
  new URLSearchParams(location.search).get("sessionId") || "",
);
const sessionInfo = ref<{ versionNo?: number; maxTurns?: number }>();
const declaration = ref<any>();
const inputsForm = ref<InstanceType<typeof SchemaInputs>>();
const ready = ref(false);
const files = ref<any[]>([]);
const approvalPanel = ref<InstanceType<typeof InlineApprovals>>();
const awaitingApproval = ref(false);
const props = defineProps<{
  appCode: string;
  endpoint?: string;
  ticket?: () => Promise<string>;
  context?: Record<string, unknown>;
}>();
const auth = useAuth();
const draft = ref("");
const messages = ref<{ role: string; text: string; warning?: string }[]>([]);
const busy = ref(false);
const observing = ref(false);
const error = ref("");
const runId = ref("");
const connection = ref('正在处理 ▍');
const authority=ref(emptyAuthority()),authorityStatus=ref('');
let authorityGeneration=0;
async function authorityFor(api:ReturnType<typeof useApi>,base:string,id:string,snapshot:unknown){
  const ticket=++authorityGeneration,session=auth.snapshot();
  authority.value=readRunAuthority(id,snapshot,undefined);authorityStatus.value=String(record(snapshot)?.status??'');
  try{const semantic=await api(base+'/v1/runs/'+encodeURIComponent(id)+'/semantic-results');
    if(!disposed&&session.current()&&runId.value===id&&ticket===authorityGeneration)authority.value=readRunAuthority(id,snapshot,semantic);
  }catch(problem){if(!disposed&&session.current()&&runId.value===id&&ticket===authorityGeneration){authority.value=emptyAuthority();authority.value.error=problem instanceof Error?problem.message:'语义回执读取失败';}}
}
function clearAuthority(){authorityGeneration++;authority.value=emptyAuthority();authorityStatus.value='';}
watch([()=>auth.generation,()=>props.appCode,()=>props.endpoint],()=>{
  controller?.abort();clearAuthority();messages.value=[];files.value=[];runId.value='';sessionId.value='';sessionInfo.value=undefined;ready.value=false;
  if(auth.token)void initialize();
},{flush:'sync'});
let disposed = false;
let controller: AbortController;
let frame = 0;
let buffer = "";
function sessionFacts(value: unknown) {
  const data = record(value);
  const number = (key: string) => {
    const candidate = data?.[key];
    return typeof candidate === 'number' && Number.isSafeInteger(candidate) && candidate > 0 ? candidate : undefined;
  };
  sessionInfo.value = { versionNo: number('versionNo'), maxTurns: number('maxTurns') };
}
function terminalOutput(snapshot: Record<string, unknown>) {
  if (snapshot.status !== 'FAILED' && snapshot.status !== 'CANCELLED') return snapshot.output;
  // PG默认空对象不能覆盖真实失败原因，也不能被当作取消后的最终回答。
  const available = (value: unknown) => value !== undefined && value !== null &&
    (typeof value !== 'object' || Object.keys(value).length > 0);
  return available(snapshot.error) ? snapshot.error : available(snapshot.output) ? snapshot.output
    : snapshot.status === 'FAILED' ? '运行失败' : '运行已取消';
}
function finishObservation() {
  cancelAnimationFrame(frame);
  frame = 0;
  if (buffer && messages.value.at(-1)?.role === "assistant") {
    messages.value[messages.value.length - 1].text += buffer;
    buffer = "";
  }
  busy.value = false;
}
async function initialize() {
  ready.value = false;
  busy.value = true;
  try {
    if (props.ticket) auth.token = await props.ticket();
    const api = useApi();
    const base = props.endpoint || "";
    let pendingId = "";
    if (sessionId.value) {
      const restored = await restoreSession(
        (p) => api(base + p),
        props.appCode,
        sessionId.value,
      );
      declaration.value = restored.declaration;
      sessionFacts(restored.session);
      const turns = restored.session.turns || [];
      for (let index = 0; index < turns.length; index++) {
        const turn = turns[index];
        if (typeof turn.run_public_id !== 'string' || !turn.run_public_id)
          throw Error('会话有已受理但尚未关联运行的轮次，请保留会话标识并核对原请求，不要重新发送。');
        const r = await api(
          base + "/v1/runs/" + encodeURIComponent(turn.run_public_id),
        );
        if (r.runId !== turn.run_public_id) throw Error('会话运行回执不一致，请在运行中心核对原记录。');
        if (r.inputs?.message)
          messages.value.push({ role: "user", text: String(r.inputs.message) });
        messages.value.push({
          role: "assistant",
          ...(terminal(r.status) ? finalAnswer(terminalOutput(r)) : { text: "" }),
        });
        runId.value = r.runId;
        await authorityFor(api,base,r.runId,r);
        files.value = r.fileRefs || [];
        awaitingApproval.value = r.status === "WAITING_APPROVAL";
        if (!terminal(r.status)) {
          if (index !== turns.length - 1) throw Error('会话历史中存在未结束轮次，请先在运行中心核对。');
          pendingId = r.runId;
        }
      }
    } else
      declaration.value = await api(
        base +
          "/v1/apps/" +
          encodeURIComponent(props.appCode) +
          "/input-schema",
      );
    if (!["CHAT", "FLOW"].includes(declaration.value.type))
      throw Error("发布输入契约未提供应用类型，请刷新后重试");
    ready.value = true;
    // 恢复只观察PG已受理的原run，不创建会话、不换requestId，也不重发工具或模型调用。
    if (pendingId) {
      connection.value = '正在恢复原运行的状态与输出 ▍';
      await followRun(api, base, pendingId);
    }
  } catch (e: any) {
    if (e.name !== 'AbortError') error.value = e.message;
  } finally {
    finishObservation();
  }
}
onMounted(initialize);
async function send() {
  if (!ready.value || busy.value) return;
  error.value = "";
  clearAuthority();
  files.value=[];awaitingApproval.value=false;buffer='';
  busy.value = true;
  connection.value = '正在处理 ▍';
  messages.value.push({ role: "user", text: draft.value });
  const input = draft.value;
  draft.value = "";
  try {
    if (props.ticket) auth.token = await props.ticket();
    const api = useApi();
    const base = props.endpoint || "";
    const declared =
      declaration.value?.inputSchema ?? declaration.value?.inputs;
    const fields = inputFields(declared);
    const inputValues = fields.length
      ? inputsForm.value!.collect()
      : { message: input, context: props.context || {} };
    messages.value[messages.value.length - 1].text = String(
      inputValues.message ?? JSON.stringify(inputValues),
    );
    if (declaration.value.type === "CHAT" && !sessionId.value) {
      const s = await api(
        base + "/v1/apps/" + encodeURIComponent(props.appCode) + "/sessions",
        "POST",
      );
      sessionId.value = s.sessionId;
      sessionFacts(s);
      writeSessionUrl(s.sessionId);
    }
    const run = await api(
      base +
        "/v1/apps/" +
        encodeURIComponent(props.appCode) +
        (declaration.value.type === "CHAT"
          ? "/sessions/" + encodeURIComponent(sessionId.value) + "/turns"
          : "/runs"),
      "POST",
      {
        inputs: inputValues,
        requestId: crypto.randomUUID(),
      },
    );
    if (disposed) throw new DOMException('观察已停止', 'AbortError');
    runId.value = run.runId;
    messages.value.push({ role: "assistant", text: "" });
    await followRun(api, base, run.runId);
  } catch (e: any) {
    if (e.name !== "AbortError")
      error.value = e.message + "；可在运行中心查看权威结果。";
  } finally {
    finishObservation();
  }
}
async function followRun(api: ReturnType<typeof useApi>, base: string, observedId: string) {
    let receivedFinal = false;
    // Cancel queued animation writes before accepting a terminal snapshot. Otherwise
    // chunks from an invalid output attempt can append after the corrected answer.
    const applyFinal = (output: unknown) => {
      receivedFinal = true;
      cancelAnimationFrame(frame);
      frame = 0;
      buffer = "";
      Object.assign(messages.value[messages.value.length - 1], finalAnswer(output));
    };
    controller = new AbortController();
    const signal = AbortSignal.any([controller.signal, auth.snapshot().signal]);
    const refreshTicket = async () => {
      if (props.ticket) {
        const token = await props.ticket();
        if (signal.aborted || disposed) throw new DOMException('观察已停止', 'AbortError');
        auth.token = token;
      }
    };
    observing.value = true;
    try {
    const result = await observeRun({
      signal,
      open: async (cursor, emit) => {
        await refreshTicket();
        return streamEvents(base + '/v1/runs/' + encodeURIComponent(observedId) + '/events',
          () => auth.token, cursor, emit, signal, () => { auth.invalidate(); });
      },
      readStatus: async () => {
        await refreshTicket();
        return api(base + '/v1/runs/' + encodeURIComponent(observedId), 'GET', undefined, signal, decodeRunStatus);
      },
      onConnection: state => { connection.value = state === 'reconnecting'
        ? '连接恢复中，正在核对运行状态；临时片段可能缺失。'
        : '连接已恢复；临时片段可能缺失，最终以完整结果为准。正在处理 ▍'; },
      onEvent: (e) => {
        const data = record(e.data);
        if ((e.type === "CHUNK" || e.type === "chunk") && !receivedFinal) {
          buffer += eventText(e.data) || "";
          if (!frame)
            frame = requestAnimationFrame(() => {
              messages.value[messages.value.length - 1].text += buffer;
              buffer = "";
              frame = 0;
            });
        }
        if (e.type === "WAITING_APPROVAL") {
          awaitingApproval.value = true;
          void approvalPanel.value?.refresh();
        }
        if (e.type === "RUN_FINISHED" && data?.output !== undefined)
          applyFinal(data?.output);
      },
      onStatus: snapshot => {
        if(runId.value===observedId)void authorityFor(api,base,observedId,snapshot);
        awaitingApproval.value = snapshot.status === 'WAITING_APPROVAL';
        if (awaitingApproval.value) void approvalPanel.value?.refresh();
        files.value = snapshot.fileRefs || snapshot.output?.fileRefs || [];
        // Nonterminal output may be the PG default {}; it cannot finalize or replace chunks.
        if (terminal(snapshot.status))
          applyFinal(terminalOutput(snapshot));
      },
    });
    if (result.status === "FAILED")
      error.value = result.error?.message || "本次运行失败，请查看运行中心记录";
    } finally {
      observing.value = false;
    }
}
async function stop() {
  if (!runId.value || !busy.value || !observing.value) return;
  // F1-3：原生 confirm 清零，停止运行改走 DangerConfirm。
  if (
    !(await confirmDanger({
      title: "停止运行",
      message: "确认停止当前运行？",
      confirmText: "停止",
    }))
  )
    return;
  const id = runId.value, owned = controller, message = messages.value.at(-1);
  try {
    const cancelled = await useApi()(
      (props.endpoint || "") + "/v1/runs/" + runId.value + "/cancel",
      "POST",
    );
    // 停止回执只影响发起时的原观察，不能让迟到回执中止用户之后接续的新一轮。
    if (disposed || controller !== owned || runId.value !== id || messages.value.at(-1) !== message) return;
    if (cancelled?.runId !== id) throw Error('停止回执与原运行不一致，请在运行中心核对。');
    error.value = '';
    if (terminal(cancelled.status)) {
      awaitingApproval.value = false;
      files.value = cancelled.fileRefs || [];
      if (messages.value.at(-1)?.role === 'assistant')
        Object.assign(messages.value[messages.value.length - 1], finalAnswer(terminalOutput(cancelled)));
    }
    owned.abort();
  } catch (e: any) {
    if (!disposed && controller === owned && runId.value === id && messages.value.at(-1) === message)
      error.value = e.message;
  }
}
async function newConversation() {
  // 显式切换续接入口，不删除旧会话、轮次或运行事实。
  if (busy.value || awaitingApproval.value) return;
  sessionId.value = "";
  sessionInfo.value = undefined;
  writeSessionUrl("");
  clearAuthority();
  messages.value = [];
  files.value = [];
  runId.value = "";
  draft.value = "";
  error.value = "";
  ready.value = false;
  await initialize();
}
onBeforeUnmount(() => {
  disposed = true;
  controller?.abort();
  cancelAnimationFrame(frame);
});
</script>
<template>
  <div class="chat">
    <p v-if="sessionId && sessionInfo" class="muted" role="status">
      当前会话 {{ sessionId }} · 固定发布版本 {{ sessionInfo.versionNo ?? '未记录' }} · 最多 {{ sessionInfo.maxTurns ?? '未记录' }} 轮
    </p>
    <div class="messages">
      <p v-if="!messages.length" class="muted">
        向智能体提问，运行过程可在工作台查看。
      </p>
      <article v-for="(m, i) in messages" :key="i" :class="['bubble', m.role]">
        <small>{{ m.role === "user" ? "你" : "智能体" }}</small>
        <p v-if="m.warning" class="output-warning" role="status">{{ m.warning }}</p>
        <div v-html="safeMarkdown(m.text)" />
      </article>
      <span v-if="busy" class="muted" role="status">{{ connection }}</span>
    </div>
    <div v-if="error" class="error" role="alert">{{ error }}</div>
    <InlineApprovals
      v-if="awaitingApproval && runId"
      ref="approvalPanel"
      :run-id="runId"
      :endpoint="endpoint"
    />
    <RunAuthorityPanel v-if="runId" :authority="authority" :run-status="authorityStatus" :model-text="messages.at(-1)?.text" />
    <Artifacts :files="files" :run-id="runId" :endpoint="endpoint" />
    <!-- UIF-A7：审批等待期 takeover——审批卡替换输入区，用户视线不移出对话流 -->
    <SchemaInputs
      v-if="
        !awaitingApproval &&
        ready &&
        inputFields(declaration.inputSchema ?? declaration.inputs).length
      "
      ref="inputsForm"
      :declaration="declaration.inputSchema ?? declaration.inputs"
    />
    <el-input
      v-else-if="!awaitingApproval"
      v-model="draft"
      type="textarea"
      :rows="3"
      placeholder="输入问题，Shift+Enter 换行"
      @keydown.enter.exact.prevent="send"
    />
    <p v-else class="muted" role="status">等待确认后继续运行；输入区已由确认卡接管。</p>
    <div class="row" style="margin-top: 0.75rem">
      <span class="muted">{{ runId }}</span
      ><el-button v-if="declaration?.type === 'CHAT' && sessionId" :disabled="busy || awaitingApproval" @click="newConversation">新建会话</el-button
      ><el-button v-if="busy && observing" @click="stop">停止运行</el-button
      ><el-button v-else type="primary" :disabled="!ready || busy" @click="send">{{
        declaration?.type === "FLOW" ? "提交运行" : "发送"
      }}</el-button>
    </div>
  </div>
</template>
<style scoped>
.chat {
  width: min(100%, clamp(45rem, 60vw, 65rem));
  margin: auto;
}
.messages {
  min-height: 45vh;
  max-height: 65vh;
  overflow: auto;
}
.bubble {
  padding: 1rem 1.5rem;
  margin: 1rem 0;
  border-radius: 0.75rem;
  background: var(--surface);
  line-height: 1.65;
  font-size: 0.9375rem;
}
.user {
  margin-left: 15%;
  background: var(--brand-primary-light);
}
.assistant {
  margin-right: 15%;
}
.bubble small {
  color: var(--muted);
}
</style>
