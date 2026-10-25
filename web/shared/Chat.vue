<script setup lang="ts">
import { ref, onBeforeUnmount, onMounted } from "vue";
import { useAuth, useApi } from "./api";
import { safeMarkdown, finalAnswer } from "./content";
import { streamEvents, terminal } from "../debug-panel/events";
import { decodeRunStatus, record, eventText } from './run-protocol';
import { observeRun } from "./run-observer";
import InlineApprovals from "./InlineApprovals.vue";
import Artifacts from "./Artifacts.vue";
import SchemaInputs from "./SchemaInputs.vue";
import { inputFields } from "./input-schema";
import { restoreSession, writeSessionUrl } from "./session";
const sessionId = ref(
  new URLSearchParams(location.search).get("sessionId") || "",
);
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
const error = ref("");
const runId = ref("");
const connection = ref('正在处理 ▍');
let disposed = false;
let controller: AbortController;
let frame = 0;
let buffer = "";
async function initialize() {
  try {
    if (props.ticket) auth.token = await props.ticket();
    const api = useApi();
    const base = props.endpoint || "";
    if (sessionId.value) {
      const restored = await restoreSession(
        (p) => api(base + p),
        props.appCode,
        sessionId.value,
      );
      declaration.value = restored.declaration;
      for (const turn of restored.session.turns || []) {
        const r = await api(
          base + "/v1/runs/" + encodeURIComponent(turn.run_public_id),
        );
        if (r.inputs?.message)
          messages.value.push({ role: "user", text: String(r.inputs.message) });
        messages.value.push({
          role: "assistant",
          ...finalAnswer(r.output??r.error),
        });
        runId.value = r.runId;
        files.value = r.fileRefs || [];
        awaitingApproval.value = r.status === "WAITING_APPROVAL";
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
  } catch (e: any) {
    error.value = e.message;
  }
}
onMounted(initialize);
async function send() {
  if (!ready.value || busy.value) return;
  error.value = "";
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
    const observedId = run.runId;
    const signal = AbortSignal.any([controller.signal, auth.snapshot().signal]);
    const refreshTicket = async () => {
      if (props.ticket) {
        const token = await props.ticket();
        if (signal.aborted || disposed) throw new DOMException('观察已停止', 'AbortError');
        auth.token = token;
      }
    };
    const result = await observeRun({
      signal,
      open: async (cursor, emit) => {
        await refreshTicket();
        return streamEvents(base + '/v1/runs/' + encodeURIComponent(observedId) + '/events',
          () => auth.token, cursor, emit, signal, () => { auth.token = ''; });
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
        awaitingApproval.value = snapshot.status === 'WAITING_APPROVAL';
        if (awaitingApproval.value) void approvalPanel.value?.refresh();
        files.value = snapshot.fileRefs || snapshot.output?.fileRefs || [];
        // Nonterminal output may be the PG default {}; it cannot finalize or replace chunks.
        if (terminal(snapshot.status))
          applyFinal(snapshot.status === 'FAILED' || snapshot.status === 'CANCELLED'
            ? snapshot.error ?? snapshot.output ?? (snapshot.status === 'FAILED' ? '运行失败' : '运行已取消')
            : snapshot.output);
      },
    });
    if (result.status === "FAILED")
      error.value = result.error?.message || "本次运行失败，请查看运行中心记录";
  } catch (e: any) {
    if (e.name !== "AbortError")
      error.value = e.message + "；可在运行中心查看权威结果。";
  } finally {
    cancelAnimationFrame(frame);
    frame = 0;
    if (buffer && messages.value.at(-1)?.role === "assistant") {
      messages.value[messages.value.length - 1].text += buffer;
      buffer = "";
    }
    busy.value = false;
  }
}
async function stop() {
  if (!confirm("确认停止当前运行？")) return;
  try {
    await useApi()(
      (props.endpoint || "") + "/v1/runs/" + runId.value + "/cancel",
      "POST",
    );
    controller?.abort();
  } catch (e: any) {
    error.value = e.message;
  }
}
onBeforeUnmount(() => {
  disposed = true;
  controller?.abort();
  cancelAnimationFrame(frame);
});
</script>
<template>
  <div class="chat">
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
      ><el-button v-if="busy" @click="stop">停止运行</el-button
      ><el-button v-else type="primary" :disabled="!ready" @click="send">{{
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
