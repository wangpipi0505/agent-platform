<script setup lang="ts">
/**
 * F4-3 计划卡：步骤+依赖+确认状态徽章；确认/执行按钮按服务端字段 canConfirm/canExecute
 * 渲染（前端不自判，红线8；前端稿 §5.4 按钮状态机矩阵由 plan-buttons 纯函数承载）。
 * 确认→confirmPlan、执行→executePlan（mock-plan T2-14 契约）；PLAN_NOT_CONFIRMED
 * 引导=重新确认所选版本（评审八轮 P1-1）。历史修订切换待修订读接口（§5.2）联调接入。
 */
import { computed, onMounted, ref } from "vue";
import { confirmPlan, executePlan } from "../../../shared/mock-plan";
import { taskDetail } from "../../../shared/mock-task";
import { planButtons } from "../plan-buttons";

type PlanDetail = NonNullable<ReturnType<typeof taskDetail>>;

const props = defineProps<{ taskId: string }>();
const emit = defineEmits<{ executed: [executionTaskId: string, sourceTaskId: string] }>();

const detail = ref<PlanDetail | null>(null);
const error = ref("");
const notice = ref("");
const busy = ref(false);

const load = () => {
  detail.value = taskDetail(props.taskId);
};
onMounted(load);

const plan = computed(() => detail.value?.plan);
const buttons = computed(() => {
  const d = detail.value;
  if (!d?.plan) return null;
  return planButtons({
    confirmState: d.plan.confirmation.state,
    canConfirm: d.canConfirm,
    canExecute: d.canExecute,
    validationStatus: d.plan.validationStatus,
    confirmedBy: d.plan.confirmation.confirmedBy,
    expiresAt: d.plan.confirmation.expiresAt,
  });
});
const badgeTone = computed(() => {
  const state = plan.value?.confirmation.state;
  return state === "CONFIRMED_VALID"
    ? "st-ok"
    : state === "CONFIRMED_EXPIRED"
      ? "st-suspend"
      : "st-wait";
});
const badgeLabel = computed(() => {
  const state = plan.value?.confirmation.state;
  return state === "CONFIRMED_VALID"
    ? "已确认"
    : state === "CONFIRMED_EXPIRED"
      ? "凭据已过期"
      : "未确认";
});
const validationLabel = computed(
  () => `校验 ${plan.value?.validationStatus ?? "-"}`,
);

const stateTone = (state: string) =>
  state === "SUCCEEDED" ? "st-ok" : state === "FAILED" ? "st-fail" : "st-run";

async function confirmSelected() {
  const p = plan.value;
  if (!p || busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const res = confirmPlan(props.taskId, {
      requestId: crypto.randomUUID(),
      digest: p.digest,
      confirmedBy: "u-demo",
    });
    if ("error" in res) error.value = `确认失败：${res.error}`;
    else notice.value = `已确认（凭据 #${res.confirmSeq}，有效期至 ${res.expiresAt}）`;
  } finally {
    busy.value = false;
    load();
  }
}

async function execute() {
  const p = plan.value;
  const d = detail.value;
  if (!p || !d || busy.value || !d.canExecute) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const res = executePlan(props.taskId, {
      requestId: crypto.randomUUID(),
      planId: p.planId,
      revision: p.revision,
      digest: p.digest,
    });
    if ("error" in res) {
      // PLAN_NOT_CONFIRMED 引导（评审八轮 P1-1）：有确认权→重新确认所选版本；无→等待确认提示。
      error.value =
        "userMessage" in res && res.userMessage
          ? res.userMessage
          : `执行失败：${res.error}`;
      return;
    }
    notice.value = `已受理执行：${res.executionTaskId}（钉住修订 v${res.pinnedRevision}）`;
    emit("executed", res.executionTaskId, props.taskId);
  } finally {
    busy.value = false;
    load();
  }
}
</script>
<template>
  <section v-if="detail && plan" class="plan-card" data-kind="plan-card">
    <header class="plan-head">
      <span class="plan-title">计划 {{ plan.planId }}</span>
      <span class="font-mono plan-meta">v{{ plan.revision }} · {{ plan.digest }}</span>
      <span :class="['badge', badgeTone]">{{ badgeLabel }}</span>
      <span class="badge st-wait">{{ validationLabel }}</span>
    </header>
    <ul class="plan-steps">
      <li v-for="(step, index) in detail.steps" :key="step.stepId" class="plan-step">
        <span :class="['dot', stateTone(step.state)]" />
        <span class="font-mono">{{ step.stepId }}</span>
        <span class="muted">{{ step.state }}</span>
        <span v-if="index > 0" class="dep">依赖 {{ detail.steps[index - 1]!.stepId }}</span>
      </li>
      <li v-if="!detail.steps.length" class="muted">暂无步骤</li>
    </ul>
    <p v-if="buttons?.waitingNote" class="waiting" role="status">
      {{ buttons.waitingNote }}
    </p>
    <p v-if="buttons?.confirm.note" class="muted note" role="status">
      {{ buttons.confirm.note }}
    </p>
    <div class="plan-actions">
      <el-tooltip
        :content="buttons?.confirm.tooltip"
        :disabled="!buttons?.confirm.tooltip"
        placement="top"
      >
        <span v-if="buttons?.confirm.visible">
          <el-button
            size="small"
            :type="buttons.confirm.disabled ? 'default' : 'primary'"
            :disabled="buttons.confirm.disabled || busy"
            @click="confirmSelected"
          >
            {{ buttons.confirm.label }}
          </el-button>
        </span>
      </el-tooltip>
      <el-tooltip
        :content="buttons?.execute.tooltip"
        :disabled="!buttons?.execute.tooltip"
        placement="top"
      >
        <span>
          <el-button
            size="small"
            type="success"
            :disabled="buttons?.execute.disabled || busy"
            @click="execute"
          >
            执行所选版本
          </el-button>
        </span>
      </el-tooltip>
    </div>
    <p v-if="notice" class="ok-note" role="status">{{ notice }}</p>
    <p v-if="error" class="err" role="alert">
      {{ error }}
      <el-button v-if="buttons?.confirm.visible && !buttons.confirm.disabled" size="small" link type="primary" @click="confirmSelected">
        重新确认所选版本
      </el-button>
    </p>
  </section>
  <p v-else class="muted card-hint">计划加载中或该任务无计划。</p>
</template>
<style scoped>
.plan-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  padding: var(--sp-3);
  margin: var(--sp-2) 0;
  display: grid;
  gap: var(--sp-2);
}
.plan-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.plan-title {
  font-weight: 600;
}
.plan-meta {
  color: var(--muted);
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
.st-suspend {
  color: var(--st-suspend-text);
  background: color-mix(in srgb, var(--st-suspend) 18%, transparent);
}
.st-fail {
  color: var(--st-fail-text);
  background: color-mix(in srgb, var(--st-fail) 18%, transparent);
}
.plan-steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--sp-1);
  font-size: var(--text-meta);
}
.plan-step {
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
.dep {
  color: var(--nav-muted);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 0 var(--sp-2);
}
.waiting {
  margin: 0;
  color: var(--st-suspend-text);
  font-size: var(--text-meta);
}
.note {
  margin: 0;
  font-size: var(--text-meta);
}
.plan-actions {
  display: flex;
  gap: var(--sp-2);
}
.ok-note {
  margin: 0;
  color: var(--st-ok-text);
  font-size: var(--text-meta);
}
.err {
  margin: 0;
  color: var(--st-fail-text);
  font-size: var(--text-meta);
}
.card-hint {
  margin: var(--sp-2) 0;
}
</style>
