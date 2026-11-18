<script setup lang="ts">
/**
 * F4-3 任务进度卡：与 F3-1 任务工作台同源（M5-2 切真实 API，shared/api-task）——
 * 状态徽章+目标+步骤/子任务树+事件游标增量（taskEvents nextAfterSeq，不 +1）；
 * 终态出「带回会话草稿重新提交」（F4-2，前端稿 §5.3 终态行）。轮询拉真实端点
 * GET /v1/tasks/{id}/events 与 /v1/tasks/{id}（阶段二可切 SSE 事件流接口）。
 */
import { onBeforeUnmount, onMounted, ref } from "vue";
import { taskDetail, taskEvents, type TaskRow, type TaskState } from "../../../shared/api-task";

type TaskDetail = TaskRow;
/** 接口稿 §5.2 详情含 subtasks——有则渲染子任务树，占位"无子任务"。 */
type Subtask = { taskId: string; state: string; role?: string };
const subtasksOf = (task: TaskDetail | null): Subtask[] =>
  ((task as (TaskDetail & { subtasks?: Subtask[] }) | null)?.subtasks ?? []) as Subtask[];

const props = defineProps<{ taskId: string }>();
const emit = defineEmits<{ reissue: [task: TaskDetail] }>();

const detail = ref<TaskDetail | null>(null);
const received = ref(0);
const latestEvent = ref("");
const cursor = ref(0);
let timer: ReturnType<typeof setInterval> | null = null;

const STATE_LABEL: Record<TaskState, string> = {
  ACCEPTED: "已受理", PLANNING: "规划中", PLAN_READY: "计划就绪", NEEDS_INPUT: "待补输入",
  WAITING_DEPENDENCIES: "等待依赖", QUEUED: "排队中", RUNNING: "运行中", WAITING_APPROVAL: "待确认",
  WAITING_EXTERNAL: "等待外部", WAITING_RECONCILIATION: "待对账",
  SUCCEEDED: "成功", FAILED: "失败", CANCELLED: "已取消",
};
const stateLabel = (state: TaskState) => STATE_LABEL[state] ?? state;
const stateTone = (state: TaskState) =>
  state === "SUCCEEDED" ? "st-ok"
  : state === "FAILED" || state === "CANCELLED" ? "st-fail"
  : state === "RUNNING" || state === "PLANNING" ? "st-run"
  : "st-wait";
const terminal = (state: TaskState) =>
  state === "SUCCEEDED" || state === "FAILED" || state === "CANCELLED";

const refreshing = ref(false);
const refresh = async () => {
  if (refreshing.value) return;
  refreshing.value = true;
  try {
    const page = await taskEvents(props.taskId, cursor.value);
    cursor.value = page.nextAfterSeq;
    if (page.events.length) {
      received.value += page.events.length;
      latestEvent.value = page.events.at(-1)?.type ?? latestEvent.value;
    }
    detail.value = await taskDetail(props.taskId);
    if (detail.value && terminal(detail.value.state)) stopPolling();
  } catch {
    /* 单次轮询失败如实忽略（下一轮重试；错误不伪造终态）。 */
  } finally {
    refreshing.value = false;
  }
};
const startPolling = () => {
  stopPolling();
  timer = setInterval(() => void refresh(), 1500);
};
const stopPolling = () => {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
};
onMounted(async () => {
  await refresh();
  if (detail.value && !terminal(detail.value.state)) startPolling();
});
onBeforeUnmount(stopPolling);

const reissue = () => {
  if (detail.value) emit("reissue", detail.value);
};
const stepsOf = (task: TaskDetail | null) => task?.steps ?? [];
</script>
<template>
  <section v-if="detail" class="task-card" data-kind="task-card">
    <header class="task-head">
      <span class="font-mono task-id">{{ detail.taskId }}</span>
      <span :class="['badge', stateTone(detail.state)]">{{ stateLabel(detail.state) }}</span>
      <span class="badge st-wait">{{ detail.mode === "PLAN" ? "Plan 规划" : "Agent 执行" }}</span>
      <span class="muted font-mono event-cursor">事件 {{ received }}/{{ detail.eventWatermark }} · 游标 {{ cursor }}</span>
    </header>
    <p class="task-goal">{{ detail.goal }}</p>
    <div class="task-trees">
      <div class="tree">
        <small class="muted">步骤</small>
        <ul>
          <li v-for="step in stepsOf(detail)" :key="step.stepId" class="tree-row">
            <span :class="['dot', stateTone(step.state as TaskState)]" />
            <span class="font-mono">{{ step.stepId }}</span>
            <span class="muted">{{ step.state }}</span>
            <span v-if="step.resultDigest" class="font-mono digest">{{ step.resultDigest }}</span>
          </li>
          <li v-if="!stepsOf(detail).length" class="muted">暂无步骤</li>
        </ul>
      </div>
      <div class="tree">
        <small class="muted">子任务</small>
        <ul>
          <li v-for="sub in subtasksOf(detail)" :key="sub.taskId" class="tree-row">
            <span class="font-mono">{{ sub.taskId }}</span>
            <span class="muted">{{ sub.role ? `${sub.role} · ` : "" }}{{ sub.state }}</span>
          </li>
          <li v-if="!subtasksOf(detail).length" class="muted">无子任务</li>
        </ul>
      </div>
    </div>
    <p v-if="latestEvent" class="muted latest">最新事件 {{ latestEvent }}</p>
    <div v-if="terminal(detail.state)" class="task-terminal">
      <el-button size="small" type="primary" plain @click="reissue">带回会话草稿重新提交</el-button>
    </div>
  </section>
  <p v-else class="muted card-hint">任务不存在或不可见：{{ props.taskId }}</p>
</template>
<style scoped>
.task-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  padding: var(--sp-3);
  margin: var(--sp-2) 0;
  display: grid;
  gap: var(--sp-2);
}
.task-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.task-id {
  font-weight: 600;
}
.event-cursor {
  margin-left: auto;
  font-size: var(--text-meta);
}
.badge {
  padding: 0 var(--sp-2);
  border-radius: 999px;
  font-size: var(--text-meta);
}
.st-ok {
  color: var(--st-ok-text);
  background: color-mix(in srgb, var(--st-ok) 18%, transparent);
}
.st-run {
  color: var(--st-run-text);
  background: color-mix(in srgb, var(--st-run) 18%, transparent);
}
.st-wait {
  color: var(--st-wait-text);
  background: color-mix(in srgb, var(--st-wait) 18%, transparent);
}
.st-fail {
  color: var(--st-fail-text);
  background: color-mix(in srgb, var(--st-fail) 18%, transparent);
}
.task-goal {
  margin: 0;
  line-height: 1.6;
}
.task-trees {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-3);
  font-size: var(--text-meta);
}
.tree ul {
  list-style: none;
  margin: var(--sp-1) 0 0;
  padding: 0;
  display: grid;
  gap: var(--sp-1);
}
.tree-row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.dot {
  width: 0.5rem;
  height: 0.5rem;
  border-radius: 999px;
  display: inline-block;
}
.digest {
  color: var(--nav-muted);
}
.latest {
  margin: 0;
  font-size: var(--text-meta);
}
.task-terminal {
  display: flex;
}
.card-hint {
  margin: var(--sp-2) 0;
}
@media (max-width: 768px) {
  .task-trees {
    grid-template-columns: 1fr;
  }
}
</style>
