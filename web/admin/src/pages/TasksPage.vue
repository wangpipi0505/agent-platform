<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import DataPage from "../../../shared/components/DataPage.vue";
import EmptyState from "../../../shared/components/EmptyState.vue";
import ErrorBanner from "../../../shared/components/ErrorBanner.vue";
import { confirmDanger } from "../../../shared/components/DangerConfirm";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import { useLiveList } from "../../../shared/hooks/useLiveList";
import { reasonCodeText } from "../../../shared/reason-code-text";
import {
  callActions, cancelTask, listTaskDetail, listTasks, reconcileTaskCall, resumeTask,
  type DemoPendingCall, type DemoTaskListRow, type DemoTaskRecovery,
} from "../../../shared/mock-task";

// F3-1（台账 §7.5 / 方案 §5.3 管理台侧=监控与运维）：任务工作台——
// 任务卡列表（useLiveList 轮询 mock-task demo 数据）+ 状态管道 chips 筛选（13 态管道，
// 替代下拉）+ 任务树内嵌展开（步骤/子任务，不跳页看进度）+ 状态×操作矩阵（按钮可用性
// 只读服务端 availableActions 字段，前端不自判——红线8）+ 恢复面板（recovery 角色投影：
// USER 三字段口径=来源/原因码/摘要；管理台 OPERATOR 另见 checkpoint/reusedSteps/blockedReasons，有则显示）。
// 处置入口：取消/恢复/对账处置（TRACK_EXTERNAL/ADOPT_EXTERNAL/RETRY/DISCLOSE，绑定 callId）
// 走 confirmDanger 确认后调用 mock-task 对应 demo 函数，全部如实标注 mock。
// 任务发起入口在助手端（F4-2），本页不含——只做监控与处置。
usePageTitle(() => "任务工作台");

type TaskRow = DemoTaskListRow;
type TaskDetail = NonNullable<ReturnType<typeof listTaskDetail>>;

// 数据面：mock-task 的 list 形状函数供 fetcher 闭包调用（demo:true 语义内置）；
// useLiveList 负责创建即拉取 + 固定间隔轮询 + 出错保留上次数据（F2-3 交付的 hook 原样复用）。
const { rows, loading: busy, error, refresh: reload } = useLiveList<TaskRow>(async () => listTasks());

// —— 状态管道 chips（方案 §5.3：状态管道即筛选）：13 态全量按任务生命周期排序，点击过滤。
const pipeline = [
  ["QUEUED", "排队"], ["RUNNING", "运行"], ["WAITING_APPROVAL", "等待确认"],
  ["WAITING_EXTERNAL", "等待外部"], ["WAITING_DEPENDENCIES", "等待上游"],
  ["WAITING_RECONCILIATION", "等待对账"], ["NEEDS_INPUT", "待补参"],
  ["PLAN_READY", "计划就绪"], ["PLANNING", "规划中"], ["ACCEPTED", "已受理"],
  ["SUCCEEDED", "成功"], ["FAILED", "失败"], ["CANCELLED", "已取消"],
] as const;
const activeState = ref<string>("");
const stateLabel = (state: string) => pipeline.find(([value]) => value === state)?.[1] || state;
function setFilter(state: string) {
  activeState.value = activeState.value === state || state === "" ? "" : state;
}
const filteredRows = computed(() =>
  activeState.value ? rows.value.filter((task) => task.state === activeState.value) : rows.value,
);

// —— 卡头展示辅助（wireframe：task-77 ●运行中 recon-assistant Plan v3 12m）。
function elapsedText(createdAt: string): string {
  const ms = Date.now() - new Date(createdAt).getTime();
  if (!Number.isFinite(ms) || ms < 0) return "—";
  const minutes = Math.floor(ms / 60000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}
const stepGlyph = (state: string) =>
  state === "SUCCEEDED" ? "✓" : state === "FAILED" ? "✕" : state === "RUNNING" ? "⟳" : "⏸";
// 计划确认入口不经 availableActions、由专用布尔呈现（task-states §3）：口径与 taskDetail.canConfirm 一致。
const canConfirmPlan = (task: TaskRow) =>
  !!task.plan && task.plan.confirmation.state !== "CONFIRMED_VALID";

// —— 状态×操作矩阵：按钮可用性只读服务端 availableActions，前端只做枚举→文案/危险态映射，不增删不自判。
const ACTION_LABELS: Record<string, string> = {
  VIEW_DEPENDENCIES: "查看依赖链", CANCEL: "取消", GOTO_CONFIRM: "去确认", VIEW_EXTERNAL: "查看外部引用",
  TRACK_EXTERNAL: "查证外部任务", ADOPT_EXTERNAL: "采纳外部结果", RETRY: "重发调用", DISCLOSE: "披露待对账",
  RESUME: "恢复", REISSUE: "重新发起", EXPORT: "导出结果",
};
const DANGEROUS_ACTIONS = new Set(["CANCEL", "RESUME", "RETRY", "ADOPT_EXTERNAL", "DISCLOSE"]);
function actionButtons(actions: readonly string[]) {
  return (actions ?? [])
    .filter((action) => ACTION_LABELS[action])
    .map((action) => ({
      action,
      label: ACTION_LABELS[action],
      danger: DANGEROUS_ACTIONS.has(action),
    }));
}

// —— 卡内展开：任务树（步骤/子任务）即时可渲染；对账处置与恢复面板来自 listTaskDetail（展开时拉取）。
const expanded = reactive(new Set<string>());
const details = reactive(new Map<string, TaskDetail>());
const detailOf = (taskId: string) => details.get(taskId) ?? null;
async function toggle(task: TaskRow) {
  if (expanded.has(task.taskId)) {
    expanded.delete(task.taskId);
    return;
  }
  expanded.add(task.taskId);
  if (!details.has(task.taskId)) {
    const detail = await listTaskDetail(task.taskId);
    if (detail) details.set(task.taskId, detail);
  }
}

// —— 恢复面板（方案 §5.3：同一 recovery 角色投影）。USER 三字段口径：来源/原因码/摘要；
// 授权 OPERATOR（管理台视角）另见 checkpoint/reusedSteps/blockedReasons——有则显示，无则不渲染。
function recoveryView(detail: { recovery?: DemoTaskRecovery | null } | null) {
  const recovery = detail?.recovery;
  if (!recovery) return null;
  return {
    source: recovery.recoverySource,
    sourceLabel: recovery.recoverySource === "MANUAL" ? "手动恢复" : "自动恢复",
    reasonCode: recovery.reasonCode,
    summary: recovery.reasonSummary,
    checkpoint: recovery.checkpoint ?? null,
    reusedSteps: recovery.reusedSteps ?? [],
    blockedReasons: recovery.blockedReasons ?? [],
  };
}
// resume 返回 pending:true 时显示"恢复中"，等待事件或重新读取详情（方案 §5.3）。
const resumePending = reactive(new Set<string>());

// —— 处置动作：危险动作（取消/恢复/对账处置）confirmDanger 确认后调 mock demo 函数；
// 只读/导航类动作联调期接真实跳转，mock 期如实提示。
async function onAction(task: TaskRow, action: string, call?: DemoPendingCall) {
  const callId = call?.callId ?? "";
  if (action === "CANCEL") {
    if (!(await confirmDanger({
      title: "取消任务",
      message: `取消任务 ${task.taskId}？运行/等待中的任务取消后进入终态，不可恢复（mock 演示数据）。`,
      confirmText: "取消任务",
    })))
      return;
    const done = cancelTask({ taskId: task.taskId });
    if ("error" in done) {
      ElMessage.error(done.userMessage);
      return;
    }
    ElMessage.success(`任务已取消（mock 演示）`);
    void reload();
    return;
  }
  if (action === "RESUME") {
    if (!(await confirmDanger({
      title: "恢复任务",
      message: `从最近检查点恢复任务 ${task.taskId}？与自动恢复共用同一入口、先到先得（mock 演示数据）。`,
      confirmText: "恢复",
    })))
      return;
    const done = resumeTask({ taskId: task.taskId });
    if ("error" in done) {
      ElMessage.error(done.userMessage);
      return;
    }
    if ("pending" in done) {
      resumePending.add(task.taskId);
      ElMessage.info(`${done.userMessage}（mock 演示）`);
      return;
    }
    ElMessage.success("任务已从检查点恢复运行，恢复投影见展开详情（mock 演示）");
    void reload();
    return;
  }
  if (action === "TRACK_EXTERNAL" || action === "ADOPT_EXTERNAL" || action === "RETRY" || action === "DISCLOSE") {
    const purposes: Record<string, string> = {
      TRACK_EXTERNAL: "查证外部任务的受理状态",
      ADOPT_EXTERNAL: "采纳外部已受理结果并继续执行",
      RETRY: "依据可查证的未受理证据重发该调用",
      DISCLOSE: "保留待对账记录并披露",
    };
    if (!(await confirmDanger({
      title: "对账处置",
      message: `对未决调用 ${callId} 执行「${ACTION_LABELS[action]}」：${purposes[action]}？提交走对账端点并按当时证据复核，证据不符将 409（mock 演示数据）。`,
      confirmText: ACTION_LABELS[action],
    })))
      return;
    const done = reconcileTaskCall({
      taskId: task.taskId,
      stepId: call?.stepId ?? "",
      callId,
      action,
    });
    if ("error" in done) {
      ElMessage.error(done.userMessage);
      return;
    }
    ElMessage.success(`对账处置已提交：${ACTION_LABELS[action]}（callId ${callId}，mock 演示）`);
    void reload();
    return;
  }
  // 只读/导航类（查看依赖链/去确认/查看外部引用/重新发起/导出结果）：mock 期不伪造结果。
  ElMessage.info(`${ACTION_LABELS[action] ?? action}：联调期接入真实入口，当前为 mock 演示`);
}
// 计划确认直达（canConfirm 驱动，task-states §3）：管理台不处理确认本体，指回助手端计划卡/确认中心。
function gotoConfirm(task: TaskRow) {
  ElMessage.info(`计划确认入口在助手端计划卡或确认中心（${task.plan?.planId ?? ""}，mock 演示）`);
}
</script>

<template>
  <!-- F3-1 样板页（方案 §7.1 四段高度模型）：页头/错误条/状态管道筛选/卡片列表滚动体；
       无分页行（监控页实时列表，mock 全量 ≤ 十余条）。 -->
  <DataPage>
    <template #header>
      <PageHeader
        title="任务工作台"
        subtitle="运行时已受理任务：状态管道、任务树、对账处置与恢复投影（mock 演示数据，联调期换真实端点）。"
        :busy="busy"
        @refresh="reload"
      />
    </template>
    <template #error>
      <ErrorBanner :error="error" />
    </template>
    <template #filters>
      <!-- 状态管道 chips 即筛选（替代下拉，方案 §5.3）：全部 + 13 态 -->
      <div class="pipeline" role="group" aria-label="状态管道筛选">
        <button
          class="chip"
          :class="{ 'is-active': activeState === '' }"
          type="button"
          @click="setFilter('')"
        >全部</button>
        <button
          v-for="[value, label] in pipeline"
          :key="value"
          class="chip"
          :class="[`is-${value}`, { 'is-active': activeState === value }]"
          type="button"
          :data-state="value"
          @click="setFilter(value)"
        >{{ label }}</button>
      </div>
    </template>
    <div class="page-scroll task-list">
      <!-- 任务卡（wireframe：taskId ●状态 应用 Plan v3 耗时 + 内嵌树 + 动作行） -->
      <article
        v-for="task in filteredRows"
        :key="task.taskId"
        class="task-card"
        :data-task-id="task.taskId"
      >
        <header class="task-head">
          <span class="mono">{{ task.taskId }}</span>
          <span :class="['card-state', `is-${task.state}`]">
            <i class="state-dot" aria-hidden="true" />{{ stateLabel(task.state) }}
          </span>
          <span v-if="resumePending.has(task.taskId)" class="resume-pending">恢复中</span>
          <span class="muted">{{ task.appCode }}</span>
          <span v-if="task.plan" class="muted">Plan v{{ task.plan.revision }}</span>
          <span class="muted">{{ task.mode }} · {{ elapsedText(task.createdAt) }}</span>
          <button class="link" type="button" @click="toggle(task)">
            {{ expanded.has(task.taskId) ? "收起" : "任务树与详情" }}
          </button>
        </header>
        <p class="task-goal">{{ task.goal }}</p>

        <!-- 任务树内嵌展开（步骤/子任务），不用跳页看进度（方案 §5.3） -->
        <div v-if="expanded.has(task.taskId)" class="task-detail">
          <div class="tree">
            <div v-for="step in task.steps" :key="step.stepId" class="tree-row">
              <span class="tree-glyph" aria-hidden="true">├─</span>
              <span class="mono">{{ step.stepId }}</span>
              <span class="glyph" aria-hidden="true">{{ stepGlyph(step.state) }}</span>
              <span class="muted">{{ step.state }}</span>
              <span v-if="step.resultDigest" class="muted mono">digest {{ step.resultDigest }}</span>
            </div>
            <div v-for="sub in task.subtasks" :key="sub.subTaskId" class="tree-row is-sub">
              <span class="tree-glyph" aria-hidden="true">└─</span>
              <span class="mono">{{ sub.subTaskId }}</span>
              <span class="glyph" aria-hidden="true">{{ stepGlyph(sub.state) }}</span>
              <span>{{ sub.goal }}</span>
              <span class="muted">{{ sub.state }}</span>
            </div>
            <p v-if="!task.steps.length && !task.subtasks.length" class="muted">
              尚无步骤（受理/规划/排队阶段如实呈现）
            </p>
          </div>

          <template v-if="detailOf(task.taskId)">
            <!-- 未决调用对账处置：仅渲染服务端按核对结果返回的动作，每个动作绑定具体 callId（前端稿 §5.3） -->
            <section
              v-for="call in detailOf(task.taskId)?.pendingCalls"
              :key="call.callId"
              class="pending-call"
              :data-call-id="call.callId"
            >
              <header class="pending-head">
                <span class="mono">{{ call.callId }}</span>
                <span class="muted">step {{ call.stepId }}</span>
                <span v-if="call.externalRef" class="muted mono">外部引用 {{ call.externalRef }}</span>
                <span class="verify" :class="`is-${call.verified}`">核对：{{ call.verified }}</span>
                <span v-if="call.disclosed" class="verify is-DISCLOSED">已披露待对账</span>
              </header>
              <el-button
                v-for="btn in actionButtons(callActions(call))"
                :key="btn.action"
                size="small"
                :type="btn.danger ? 'danger' : 'default'"
                plain
                @click="onAction(task, btn.action, call)"
              >{{ btn.label }}</el-button>
            </section>
            <p v-if="!detailOf(task.taskId)?.pendingCalls.length" class="muted">无未决调用</p>

            <!-- 恢复面板：同一 recovery 角色投影；USER 三字段口径 + OPERATOR 附加分区（有则显示） -->
            <section
              v-if="recoveryView(detailOf(task.taskId))"
              class="recovery-panel"
              data-test="recovery-panel"
            >
              <header class="panel-title">恢复面板（角色投影）</header>
              <dl class="recovery-fields">
                <div><dt>来源</dt><dd>{{ recoveryView(detailOf(task.taskId))?.sourceLabel }}（{{ recoveryView(detailOf(task.taskId))?.source }}）</dd></div>
                <div><dt>原因码</dt><dd class="mono">{{ recoveryView(detailOf(task.taskId))?.reasonCode }}<span v-if="reasonCodeText(recoveryView(detailOf(task.taskId))?.reasonCode)" class="muted">（{{ reasonCodeText(recoveryView(detailOf(task.taskId))?.reasonCode) }}）</span></dd></div>
                <div><dt>摘要</dt><dd>{{ recoveryView(detailOf(task.taskId))?.summary }}</dd></div>
              </dl>
              <div v-if="recoveryView(detailOf(task.taskId))?.checkpoint" class="recovery-extra">
                <span class="muted">检查点</span>
                <span class="mono">watermark {{ recoveryView(detailOf(task.taskId))?.checkpoint?.eventWatermark }}</span>
                <span
                  v-for="runRef in recoveryView(detailOf(task.taskId))?.checkpoint?.runRefs ?? []"
                  :key="runRef"
                  class="mono"
                >{{ runRef }}</span>
              </div>
              <div v-if="recoveryView(detailOf(task.taskId))?.reusedSteps.length" class="recovery-extra">
                <span class="muted">复用步骤</span>
                <span
                  v-for="reused in recoveryView(detailOf(task.taskId))?.reusedSteps"
                  :key="reused.stepId"
                  class="mono"
                >{{ reused.stepId }}（digest {{ reused.resultDigest }}）</span>
              </div>
              <div v-if="recoveryView(detailOf(task.taskId))?.blockedReasons.length" class="recovery-extra">
                <span class="muted">仍阻塞原因</span>
                <span
                  v-for="reason in recoveryView(detailOf(task.taskId))?.blockedReasons"
                  :key="reason"
                >{{ reason }}</span>
              </div>
            </section>
            <p v-else class="muted">无恢复事实（recovery 为空如实呈现）</p>
          </template>
          <p v-else class="muted">详情加载中（mock）…</p>
        </div>

        <!-- 状态×操作矩阵：卡内动作行 = 服务端 availableActions 的逐项映射（不自判） -->
        <footer class="task-actions">
          <el-button v-if="canConfirmPlan(task)" size="small" type="primary" plain @click="gotoConfirm(task)">确认计划</el-button>
          <el-button
            v-for="btn in actionButtons(task.availableActions)"
            :key="btn.action"
            size="small"
            :type="btn.danger ? 'danger' : 'default'"
            plain
            :data-action="btn.action"
            @click="onAction(task, btn.action)"
          >{{ btn.label }}</el-button>
        </footer>
      </article>
      <EmptyState
        v-if="!busy && !filteredRows.length"
        variant="filtered"
        description="没有匹配的任务，请调整状态管道筛选"
        action-text="清除筛选"
        @action="setFilter('')"
      />
    </div>
  </DataPage>
</template>

<style scoped>
.page-scroll {
  height: 100%;
  overflow-y: auto;
}
.task-list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.task-card {
  border: 1px solid color-mix(in srgb, var(--surface-3, #1e2b42) 80%, transparent);
  border-radius: var(--radius);
  background: var(--surface-2);
  padding: var(--sp-3) var(--sp-4);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.task-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-3);
}
.task-goal {
  margin: 0;
  color: var(--text-secondary);
}
.card-state {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  white-space: nowrap;
}
.state-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: currentColor;
  flex: none;
}
/* 状态语义色（tokens.css §7.3）：成功绿/失败红/运行蓝/等待悬置黄，绝不混同。 */
.card-state.is-SUCCEEDED { color: var(--st-ok-text); }
.card-state.is-FAILED { color: var(--st-fail-text); }
.card-state.is-CANCELLED { color: var(--text-tertiary); }
.card-state.is-RUNNING,
.card-state.is-PLANNING { color: var(--st-run-text); }
.card-state.is-WAITING_APPROVAL,
.card-state.is-WAITING_EXTERNAL,
.card-state.is-WAITING_DEPENDENCIES,
.card-state.is-WAITING_RECONCILIATION,
.card-state.is-NEEDS_INPUT { color: var(--st-suspend-text); font-weight: 600; }
.resume-pending {
  color: var(--st-suspend-text);
  font-weight: 600;
}
.mono {
  font-family: var(--font-mono, monospace);
}
.muted {
  color: var(--muted);
  font-size: var(--text-meta);
}
.link {
  background: none;
  border: none;
  color: var(--st-run-text);
  cursor: pointer;
  padding: 0;
  font-size: var(--text-meta);
}
.link:hover {
  text-decoration: underline;
}
/* 状态管道 chips（替代下拉）：选中态描边加重，管道顺序=生命周期。 */
.pipeline {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  align-items: center;
}
.chip {
  border: 1px solid color-mix(in srgb, var(--muted) 45%, transparent);
  border-radius: 999px;
  background: transparent;
  color: var(--text-secondary);
  font-size: var(--text-meta);
  padding: 0.1rem var(--sp-3);
  cursor: pointer;
}
.chip:hover {
  border-color: var(--st-run);
  color: var(--text-primary);
}
.chip.is-active {
  border-color: var(--st-run);
  color: var(--st-run-text);
  background: color-mix(in srgb, var(--st-run) 12%, transparent);
  font-weight: 600;
}
.task-detail {
  border-top: 1px dashed color-mix(in srgb, var(--muted) 35%, transparent);
  padding-top: var(--sp-2);
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.tree-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--text-meta);
}
.tree-row.is-sub {
  padding-left: var(--sp-4);
}
.tree-glyph,
.glyph {
  color: var(--muted);
}
.pending-call {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-2);
  border: 1px solid color-mix(in srgb, var(--st-suspend) 35%, transparent);
  border-radius: var(--radius);
}
.pending-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--sp-2);
  flex-basis: 100%;
}
.verify {
  font-size: var(--text-meta);
}
.verify.is-UNRECEIVED { color: var(--st-fail-text); }
.verify.is-RECEIVED { color: var(--st-ok-text); }
.verify.is-UNKNOWN,
.verify.is-DISCLOSED { color: var(--st-suspend-text); }
.recovery-panel {
  border: 1px solid color-mix(in srgb, var(--st-run) 35%, transparent);
  border-radius: var(--radius);
  padding: var(--sp-2) var(--sp-3);
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
}
.panel-title {
  font-weight: 600;
  color: var(--text-secondary);
  font-size: var(--text-meta);
}
.recovery-fields {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2) var(--sp-5);
}
.recovery-fields div {
  display: flex;
  gap: var(--sp-2);
  align-items: baseline;
}
.recovery-fields dt {
  color: var(--muted);
  font-size: var(--text-meta);
}
.recovery-fields dd {
  margin: 0;
}
.recovery-extra {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  align-items: baseline;
  font-size: var(--text-meta);
}
.task-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
}
@media (max-width: 600px) {
  .task-head {
    flex-direction: column;
    align-items: flex-start;
    gap: var(--sp-1);
  }
}
</style>
