<script setup lang="ts">
/**
 * F4-2 执行请求（用户发起唯一入口，mock 期驱动）：输入区[本次任务模式 Plan 规划|Agent 执行]
 * （默认 AGENT）→submitTask(requestId/mode/goal/inputs/sessionId/sessionCursor)→消息流任务卡
 * （TaskCard 消费 taskEvents 游标增量）→终态「带回会话草稿重新提交」回填输入区。
 * PLAN 路径：demoAdvance 推进到 PLAN_READY→PlanCard 确认→执行（mock-plan 闭环）。
 * 消息流示意（三类卡片）见 demo-flow.ts；阶段二换真实 turns/事件流。
 */
import { ref, watch } from "vue";
import { submitTask, taskDetail, demoAdvanceTask, demoCreateExecTask } from "../../shared/mock-task";
import { seedPlanTask } from "../../shared/mock-plan";
import { demoFlowFor, nextKey, type FlowMessage, type SessionView } from "./demo-flow";
import ToolCallCard from "./cards/ToolCallCard.vue";
import PlanCard from "./cards/PlanCard.vue";
import TaskCard from "./cards/TaskCard.vue";

type TaskDetail = NonNullable<ReturnType<typeof taskDetail>>;

const props = defineProps<{ session: SessionView | null }>();
const emit = defineEmits<{
  "save-memory": [text: string]
  "session-created": [sessionId: string, title: string]
}>();

const mode = ref<"PLAN" | "AGENT">("AGENT");
const draft = ref("");
const busy = ref(false);
const error = ref("");
const messages = ref<FlowMessage[]>([]);
/** sessionCursor：本次会话已提交轮次位置（demo 计数；真实=会话事件水位，随请求提交）。 */
const cursor = ref(0);

const loadSession = (next: SessionView | null, prevId: string) => {
  const nextId = next?.sessionId ?? "";
  if (nextId === prevId) return;
  if (!prevId && nextId) {
    // 草稿会话首次发送后定型：保留当前消息流，仅推进游标。
    cursor.value = messages.value.length;
    return;
  }
  messages.value = demoFlowFor(nextId);
  cursor.value = 0;
  // 演示驱动：RUNNING 任务推进到终态（任务卡经 taskEvents 游标拾取增量）；
  // PLAN_READY 任务登记计划（PlanCard 确认/执行走 mock-plan 契约）。
  for (const m of messages.value) {
    if (m.kind !== "task" && m.kind !== "plan") continue;
    const detail = taskDetail(m.taskId);
    if (!detail) continue;
    if (detail.plan) seedPlanTask(structuredClone(taskDetail(m.taskId)!) as never);
    if (detail.state !== "PLAN_READY" && !terminalOf(detail.state)) startDemoAdvance(m.taskId);
  }
};
watch(() => props.session, (next, prev) => loadSession(next, prev?.sessionId ?? ""), { flush: "post" });
loadSession(props.session, "__init__");

const terminalOf = (state: TaskDetail["state"]) =>
  state === "SUCCEEDED" || state === "FAILED" || state === "CANCELLED";

/** demo 驱动：定时推进演示任务一批事件（真实增量由服务端事件流/SSE 驱动，阶段二接线）。 */
const advancing = new Set<string>();
function startDemoAdvance(taskId: string) {
  if (advancing.has(taskId)) return;
  advancing.add(taskId);
  const timer = setInterval(() => {
    const before = taskDetail(taskId)?.state;
    const after = demoAdvanceTask(taskId);
    if (after?.plan && before !== "PLAN_READY")
      seedPlanTask(structuredClone(taskDetail(taskId)!) as never);
    const state = taskDetail(taskId)?.state;
    if (!state || state === "PLAN_READY" || terminalOf(state)) {
      // PLAN 路径（前端稿 §5.4）：计划就绪即在消息流出计划卡（确认/执行入口）。
      if (state === "PLAN_READY" && !messages.value.some((m) => m.kind === "plan" && m.taskId === taskId))
        messages.value.push({ key: nextKey("p"), kind: "plan", taskId });
      clearInterval(timer);
      advancing.delete(taskId);
    }
  }, 1200);
}

function send() {
  const goal = draft.value.trim();
  if (!goal || busy.value) return;
  busy.value = true;
  error.value = "";
  const sessionId = props.session?.sessionId || "";
  try {
    const res = submitTask({
      requestId: crypto.randomUUID(),
      mode: mode.value,
      goal,
      inputs: { message: goal },
      sessionId: sessionId || undefined,
      sessionCursor: cursor.value,
    });
    if ("error" in res) {
      error.value = `${res.userMessage}（${res.error}）`;
      return;
    }
    messages.value.push({ key: nextKey("u"), kind: "text", role: "user", text: goal });
    messages.value.push({ key: nextKey("k"), kind: "task", taskId: res.taskId });
    draft.value = "";
    cursor.value += 1;
    if (!sessionId)
      emit("session-created", `sess-demo-local-${Date.now()}`, goal);
    startDemoAdvance(res.taskId);
  } finally {
    busy.value = false;
  }
}

/** PLAN 执行受理：注册关联执行任务并追加任务卡（确认→执行闭环，同源 mock-task）。 */
function onExecuted(executionTaskId: string, sourceTaskId: string) {
  demoCreateExecTask(executionTaskId, sourceTaskId);
  messages.value.push({ key: nextKey("k"), kind: "task", taskId: executionTaskId });
  startDemoAdvance(executionTaskId);
}

/** 终态「带回会话草稿重新提交」：原目标回填输入区，模式随原任务。 */
function onReissue(task: TaskDetail) {
  draft.value = task.goal;
  mode.value = task.mode;
  error.value = "";
}
</script>
<template>
  <div class="assistant-chat">
    <header class="chat-head">
      <span class="chat-title">{{ props.session?.title || "选择左侧会话或新建" }}</span>
      <span class="muted font-mono chat-session">{{
        props.session?.sessionId || "草稿（首次发送后生成）"
      }}</span>
    </header>
    <div class="flow">
      <p v-if="!messages.length" class="muted flow-hint">
        输入目标发送执行请求：Plan 规划先出计划卡（确认后执行），Agent 执行直接展示任务进度。
      </p>
      <template v-for="m in messages" :key="m.key">
        <article v-if="m.kind === 'text'" :class="['bubble', m.role]">
          <small class="muted">{{ m.role === "user" ? "你" : "智能体" }}</small>
          <p class="bubble-text">{{ m.text }}</p>
          <span v-if="m.role === 'assistant'" class="msg-ops">
            <el-button size="small" link type="primary" @click="emit('save-memory', m.text)">
              保存为记忆
            </el-button>
          </span>
        </article>
        <ToolCallCard v-else-if="m.kind === 'tool'" :view="m.view" />
        <PlanCard v-else-if="m.kind === 'plan'" :task-id="m.taskId" @executed="onExecuted" />
        <TaskCard v-else :task-id="m.taskId" @reissue="onReissue" />
      </template>
    </div>
    <div class="composer">
      <el-alert
        v-if="error"
        class="composer-error"
        :title="error"
        type="error"
        show-icon
        @close="error = ''"
      />
      <el-input
        v-model="draft"
        type="textarea"
        :rows="3"
        placeholder="描述本次目标（执行请求）…"
        @keydown.enter.exact.prevent="send"
      />
      <div class="composer-bar">
        <el-radio-group v-model="mode" size="small" aria-label="本次任务模式">
          <el-radio-button value="PLAN">Plan 规划</el-radio-button>
          <el-radio-button value="AGENT">Agent 执行</el-radio-button>
        </el-radio-group>
        <span class="muted font-mono cursor-hint">游标 {{ cursor }}</span>
        <el-button type="primary" :disabled="!draft.trim() || busy" @click="send">发送</el-button>
      </div>
    </div>
  </div>
</template>
<style scoped>
.assistant-chat {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: var(--sp-4);
  gap: var(--sp-3);
}
.chat-head {
  display: flex;
  align-items: baseline;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.chat-title {
  font-weight: 600;
}
.chat-session {
  font-size: var(--text-meta);
}
.flow {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
.flow-hint {
  margin: 0;
}
.bubble {
  padding: var(--sp-3) var(--sp-4);
  margin: var(--sp-3) 0;
  border-radius: 0.75rem;
  background: var(--surface);
  line-height: 1.65;
  font-size: var(--text-body);
  max-width: 100%;
}
.bubble.user {
  margin-left: 15%;
  background: var(--brand-primary-light);
}
.bubble.assistant {
  margin-right: 15%;
}
.bubble small {
  display: block;
}
.bubble-text {
  margin: var(--sp-1) 0 0;
  white-space: pre-wrap;
}
.msg-ops {
  display: inline-flex;
  margin-top: var(--sp-1);
}
.composer {
  flex-shrink: 0;
  display: grid;
  gap: var(--sp-2);
}
.composer-error {
  margin: 0;
}
.composer-bar {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
}
.cursor-hint {
  font-size: var(--text-meta);
}
.composer-bar .el-button {
  margin-left: auto;
}
</style>
