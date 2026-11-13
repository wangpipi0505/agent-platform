<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import PageHeader from "../PageHeader.vue";
import EmptyState from "../../../shared/components/EmptyState.vue";
import {
  demoPlanDetail,
  diffPlanSteps,
  type PlanDemoStep,
} from "../../../shared/mock-plan";

// F3-2（台账 §7.5 / 方案 §5.3）：管理台计划详情——步骤/依赖/绑定/修订对比【只读】，
// 供运维查看对应计划与执行链路。红线：本页不含确认/执行操作入口——用户确认与
// 选定版本执行由助手端计划卡（F4-3）验收；确认状态仅以只读徽章呈现
// （UNCONFIRMED/CONFIRMED_VALID/CONFIRMED_EXPIRED，前端不自判权限，展示即事实投影）。
// 数据源：mock 契约 demo 种子（mock-plan.ts F3-2 追加导出，形状=task-states §5 +
// mock-task.ts plan 投影）；接口稿 §5.2 修订读端点就绪后整块替换。
const route = useRoute();
const planId = computed(() => String(route.params.planId || ""));
const detail = computed(() => demoPlanDetail(planId.value));
const revisions = computed(() => detail.value?.revisions ?? []);
const currentRevision = computed(() => revisions.value.at(-1));

// —— 只读徽章（三态枚举一一映射；未知值如实回显原文，不伪造状态）——
type TagType = "primary" | "success" | "info" | "warning" | "danger";
type BadgeSpec = { label: string; type: TagType };
const validationBadgeOf = (status: string): BadgeSpec =>
  ({
    VALID: { label: "校验通过", type: "success" },
    PENDING: { label: "校验中", type: "info" },
    INVALID: { label: "校验失败", type: "danger" },
  } as Record<string, BadgeSpec>)[status] ?? { label: status, type: "info" };
const confirmationBadgeOf = (state: string): BadgeSpec =>
  ({
    UNCONFIRMED: { label: "未确认", type: "info" },
    CONFIRMED_VALID: { label: "已确认有效", type: "success" },
    CONFIRMED_EXPIRED: { label: "已确认过期", type: "warning" },
  } as Record<string, BadgeSpec>)[state] ?? { label: state, type: "info" };
const stepStateBadgeOf = (state: string): BadgeSpec =>
  ({
    READY: { label: "就绪", type: "primary" },
    QUEUED: { label: "排队", type: "info" },
    RUNNING: { label: "运行中", type: "primary" },
    BLOCKED: { label: "阻塞", type: "warning" },
    WAITING_APPROVAL: { label: "等待确认", type: "warning" },
    WAITING_EXTERNAL: { label: "等待外部", type: "warning" },
    WAITING_RECONCILIATION: { label: "等待对账", type: "warning" },
    SUCCEEDED: { label: "成功", type: "success" },
    SKIPPED: { label: "跳过", type: "info" },
    FAILED: { label: "失败", type: "danger" },
    CANCELLED: { label: "已取消", type: "info" },
  } as Record<string, BadgeSpec>)[state] ?? { label: state, type: "info" };

// —— 查看修订（只读切换）：默认当前（末位）修订；切换仅改变展示，无任何写语义 ——
const viewRevision = ref<number>();
const compareBase = ref<number>();
const compareTarget = ref<number>();
watch(
  planId,
  () => {
    viewRevision.value = currentRevision.value?.revision;
    // 对比默认：上一修订 → 当前修订（上一修订不存在时与当前重合）
    compareBase.value = revisions.value.at(-2)?.revision ?? currentRevision.value?.revision;
    compareTarget.value = currentRevision.value?.revision;
  },
  { immediate: true },
);
const viewed = computed(
  () => revisions.value.find((r) => r.revision === viewRevision.value) ?? currentRevision.value,
);
const viewedRevisionLabel = computed(() => (viewed.value ? `修订 ${viewed.value.revision}` : "—"));
const viewedConfirmation = computed(() => viewed.value?.confirmation);
const compareBaseRevision = computed(
  () => revisions.value.find((r) => r.revision === compareBase.value),
);
const compareTargetRevision = computed(
  () => revisions.value.find((r) => r.revision === compareTarget.value),
);
/** 步骤差异（基准 → 目标，按 stepId 简单 diff）。 */
const stepDiff = computed(() =>
  compareBaseRevision.value && compareTargetRevision.value
    ? diffPlanSteps(compareBaseRevision.value, compareTargetRevision.value)
    : { added: [] as PlanDemoStep[], removed: [] as PlanDemoStep[] },
);

// —— 依赖视图：按 depends_on 拓扑分层（层级=最长依赖链），纯 CSS 缩进，不引入图库 ——
const dependencyLayers = computed<PlanDemoStep[][]>(() => {
  const steps = viewed.value?.steps ?? [];
  const byId = new Map(steps.map((s) => [s.stepId, s]));
  const depth = new Map<string, number>();
  const visiting = new Set<string>();
  const depthOf = (id: string): number => {
    if (depth.has(id)) return depth.get(id)!;
    if (visiting.has(id)) return 0; // 环防御：回边不计深度（demo 数据无环）
    visiting.add(id);
    const step = byId.get(id);
    const value = step?.dependsOn.length
      ? Math.max(...step.dependsOn.map((dep) => depthOf(dep) + 1))
      : 0;
    visiting.delete(id);
    depth.set(id, value);
    return value;
  };
  const layers: PlanDemoStep[][] = [];
  for (const step of steps) {
    const level = depthOf(step.stepId);
    if (!layers[level]) layers[level] = [];
    layers[level].push(step);
  }
  return layers;
});

// —— 输入绑定：查看修订各步 inputBindings 拍平成行（只读表格） ——
const bindingRows = computed(() =>
  (viewed.value?.steps ?? []).flatMap((step) =>
    step.inputBindings.map((binding) => ({ stepId: step.stepId, ...binding })),
  ),
);
</script>

<template>
  <div class="plan-detail-page">
    <el-breadcrumb class="plan-breadcrumb" separator="/">
      <el-breadcrumb-item>任务与运行</el-breadcrumb-item>
      <el-breadcrumb-item>计划详情</el-breadcrumb-item>
    </el-breadcrumb>
    <PageHeader
      title="计划详情"
      eyebrow="任务与运行"
      subtitle="计划步骤、依赖、输入绑定与修订对比（管理台只读）。"
    />
    <EmptyState
      v-if="!detail"
      variant="filtered"
      :description="`未找到计划 ${planId || '—'}（demo 种子仅含 plan-9）`"
      action-text=""
    />
    <template v-else>
      <!-- 计划概览：来源任务与执行链路锚点（对应任务/智能体随 F3 工作台就绪后接跳转） -->
      <section class="panel" aria-label="计划概览">
        <dl class="summary-grid">
          <div><dt>计划 planId</dt><dd><code>{{ detail.planId }}</code></dd></div>
          <div><dt>来源任务</dt><dd><code>{{ detail.taskId }}</code></dd></div>
          <div><dt>智能体</dt><dd>{{ detail.appCode }}</dd></div>
          <div><dt>目标</dt><dd>{{ detail.goal }}</dd></div>
          <div>
            <dt>当前修订</dt>
            <dd>
              修订 {{ currentRevision?.revision }}
              <code class="sha">{{ currentRevision?.digest }}</code>
              <el-tag
                size="small"
                class="badge"
                :type="validationBadgeOf(currentRevision?.validationStatus ?? '').type"
                >{{ validationBadgeOf(currentRevision?.validationStatus ?? "").label }}</el-tag
              >
              <el-tag
                size="small"
                class="badge"
                :type="confirmationBadgeOf(currentRevision?.confirmation.state ?? '').type"
                >{{ confirmationBadgeOf(currentRevision?.confirmation.state ?? "").label }}</el-tag
              >
            </dd>
          </div>
        </dl>
        <p class="muted readonly-note" role="note">
          管理台只读视图：用户确认与选定版本执行在助手端计划卡完成，本页不含任何操作入口。
        </p>
      </section>

      <!-- 步骤表：stepId / kind / target_ref / depends_on（chips） / 状态 -->
      <section class="panel" aria-label="计划步骤">
        <header class="section-head">
          <h2>步骤（{{ viewedRevisionLabel }}）</h2>
          <div class="view-picker">
            <span class="muted">查看修订</span>
            <el-select
              v-model="viewRevision"
              class="revision-select"
              aria-label="查看修订选择器"
            >
              <el-option
                v-for="r in revisions"
                :key="r.revision"
                :value="r.revision"
                :label="`修订 ${r.revision}`"
              />
            </el-select>
            <code class="sha">{{ viewed?.digest }}</code>
            <el-tag
              size="small"
              :type="validationBadgeOf(viewed?.validationStatus ?? '').type"
              >{{ validationBadgeOf(viewed?.validationStatus ?? "").label }}</el-tag
            >
            <el-tag
              size="small"
              :type="confirmationBadgeOf(viewed?.confirmation.state ?? '').type"
              >{{ confirmationBadgeOf(viewed?.confirmation.state ?? "").label }}</el-tag
            >
            <span
              v-if="viewedConfirmation?.confirmedBy"
              class="muted credential-note"
              >by {{ viewedConfirmation.confirmedBy }}
              <template v-if="viewedConfirmation.expiresAt">
                至 {{ viewedConfirmation.expiresAt }}</template
              ></span
            >
          </div>
        </header>
        <el-table :data="viewed?.steps ?? []" aria-label="步骤表">
          <el-table-column prop="stepId" label="stepId" min-width="120" />
          <el-table-column label="kind" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.kind }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="target_ref" min-width="220">
            <template #default="{ row }">
              <code class="target-ref">{{ row.targetRef }}</code>
            </template>
          </el-table-column>
          <el-table-column label="depends_on" min-width="170">
            <template #default="{ row }">
              <span v-if="!row.dependsOn.length" class="muted">—</span>
              <el-tag
                v-for="dep in row.dependsOn"
                :key="dep"
                size="small"
                type="info"
                class="dep-chip"
                >{{ dep }}</el-tag
              >
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="stepStateBadgeOf(row.state).type">{{
                stepStateBadgeOf(row.state).label
              }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 依赖视图：按 depends_on 分层缩进（纯 CSS，不引入图库） -->
      <section class="panel" aria-label="依赖视图">
        <header class="section-head">
          <h2>依赖视图（{{ viewedRevisionLabel }}）</h2>
          <p class="muted">按 depends_on 拓扑分层缩进，层级 = 最长上游依赖链。</p>
        </header>
        <ol class="dag" aria-label="依赖分层列表">
          <li
            v-for="(layer, depth) in dependencyLayers"
            :key="depth"
            class="dag-layer"
            :style="{ '--dag-depth': depth }"
          >
            <div class="dag-depth">第 {{ depth + 1 }} 层</div>
            <ul class="dag-steps">
              <li v-for="step in layer" :key="step.stepId" class="dag-step">
                <code class="dag-step-id">{{ step.stepId }}</code>
                <el-tag size="small" effect="plain">{{ step.kind }}</el-tag>
                <template v-if="step.dependsOn.length">
                  <span class="muted">←</span>
                  <el-tag
                    v-for="dep in step.dependsOn"
                    :key="dep"
                    size="small"
                    type="info"
                    class="dep-chip"
                    >{{ dep }}</el-tag
                  >
                </template>
                <span v-else class="muted">（无上游依赖）</span>
              </li>
            </ul>
          </li>
        </ol>
      </section>

      <!-- 输入绑定：每步 inputBindings 表格化只读 -->
      <section class="panel" aria-label="输入绑定">
        <header class="section-head">
          <h2>输入绑定（{{ viewedRevisionLabel }}）</h2>
          <p class="muted">每步 inputBindings 只读展示。</p>
        </header>
        <el-table :data="bindingRows" aria-label="输入绑定表">
          <el-table-column prop="stepId" label="步骤" min-width="120" />
          <el-table-column prop="input" label="input" min-width="120" />
          <el-table-column label="source" width="140">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" type="info">{{ row.source }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="value" min-width="240">
            <template #default="{ row }">
              <code class="target-ref">{{ row.value }}</code>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 修订对比：两个修订选择器 + digest/校验/确认 + 步骤差异（只读） -->
      <section class="panel" aria-label="修订对比">
        <header class="section-head">
          <h2>修订对比</h2>
          <p class="muted">digest / 校验状态 / 确认状态 / 步骤差异（新增·删除）。</p>
        </header>
        <div class="compare-pickers">
          <label class="picker">
            <span class="muted">基准修订</span>
            <el-select
              v-model="compareBase"
              class="revision-select"
              aria-label="基准修订选择器"
            >
              <el-option
                v-for="r in revisions"
                :key="r.revision"
                :value="r.revision"
                :label="`修订 ${r.revision}`"
              />
            </el-select>
          </label>
          <label class="picker">
            <span class="muted">目标修订</span>
            <el-select
              v-model="compareTarget"
              class="revision-select"
              aria-label="目标修订选择器"
            >
              <el-option
                v-for="r in revisions"
                :key="r.revision"
                :value="r.revision"
                :label="`修订 ${r.revision}`"
              />
            </el-select>
          </label>
        </div>
        <div class="compare-grid">
          <div class="compare-card">
            <h3>基准：{{ compareBaseRevision ? `修订 ${compareBaseRevision.revision}` : "—" }}</h3>
            <dl v-if="compareBaseRevision">
              <div><dt>digest</dt><dd><code class="sha">{{ compareBaseRevision.digest }}</code></dd></div>
              <div>
                <dt>校验状态</dt>
                <dd>
                  <el-tag size="small" :type="validationBadgeOf(compareBaseRevision.validationStatus).type">{{
                    validationBadgeOf(compareBaseRevision.validationStatus).label
                  }}</el-tag>
                </dd>
              </div>
              <div>
                <dt>确认状态</dt>
                <dd>
                  <el-tag size="small" :type="confirmationBadgeOf(compareBaseRevision.confirmation.state).type">{{
                    confirmationBadgeOf(compareBaseRevision.confirmation.state).label
                  }}</el-tag>
                  <span v-if="compareBaseRevision.confirmation.confirmedBy" class="muted credential-note">
                    by {{ compareBaseRevision.confirmation.confirmedBy }}
                    <template v-if="compareBaseRevision.confirmation.expiresAt">
                      至 {{ compareBaseRevision.confirmation.expiresAt }}</template>
                  </span>
                </dd>
              </div>
            </dl>
          </div>
          <div class="compare-card">
            <h3>目标：{{ compareTargetRevision ? `修订 ${compareTargetRevision.revision}` : "—" }}</h3>
            <dl v-if="compareTargetRevision">
              <div><dt>digest</dt><dd><code class="sha">{{ compareTargetRevision.digest }}</code></dd></div>
              <div>
                <dt>校验状态</dt>
                <dd>
                  <el-tag size="small" :type="validationBadgeOf(compareTargetRevision.validationStatus).type">{{
                    validationBadgeOf(compareTargetRevision.validationStatus).label
                  }}</el-tag>
                </dd>
              </div>
              <div>
                <dt>确认状态</dt>
                <dd>
                  <el-tag size="small" :type="confirmationBadgeOf(compareTargetRevision.confirmation.state).type">{{
                    confirmationBadgeOf(compareTargetRevision.confirmation.state).label
                  }}</el-tag>
                  <span v-if="compareTargetRevision.confirmation.confirmedBy" class="muted credential-note">
                    by {{ compareTargetRevision.confirmation.confirmedBy }}
                    <template v-if="compareTargetRevision.confirmation.expiresAt">
                      至 {{ compareTargetRevision.confirmation.expiresAt }}</template>
                  </span>
                </dd>
              </div>
            </dl>
          </div>
        </div>
        <div class="diff-block">
          <h3>新增步骤（基准 → 目标）</h3>
          <ul v-if="stepDiff.added.length" class="diff-list">
            <li v-for="step in stepDiff.added" :key="step.stepId">
              <code class="dag-step-id">{{ step.stepId }}</code>
              <el-tag size="small" effect="plain">{{ step.kind }}</el-tag>
              <code class="target-ref">{{ step.targetRef }}</code>
            </li>
          </ul>
          <p v-else class="muted">无新增步骤</p>
          <h3>删除步骤（基准 → 目标）</h3>
          <ul v-if="stepDiff.removed.length" class="diff-list">
            <li v-for="step in stepDiff.removed" :key="step.stepId">
              <code class="dag-step-id">{{ step.stepId }}</code>
              <el-tag size="small" effect="plain">{{ step.kind }}</el-tag>
              <code class="target-ref">{{ step.targetRef }}</code>
            </li>
          </ul>
          <p v-else class="muted">无删除步骤</p>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* F3-2 只读详情页：单列分区卡纵向流（同 F2-2 技能详情分区的既定模式），只引用 tokens 变量。 */
.plan-detail-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  min-width: 0;
}
.plan-breadcrumb {
  margin-bottom: 0;
}
.panel {
  border: 1px solid var(--line);
  border-radius: 0.75rem;
  background: var(--surface);
  padding: var(--sp-4) var(--sp-5);
  min-width: 0;
}
.muted {
  color: var(--muted);
  font-size: var(--text-meta);
}
.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.section-head h2,
.diff-block h3,
.compare-card h3 {
  margin: 0 0 var(--sp-2);
}
.section-head p {
  margin: 0;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
  gap: var(--sp-2) var(--sp-4);
  margin: 0;
}
.summary-grid dt,
.compare-card dt {
  color: var(--muted);
  font-size: var(--text-meta);
  margin-bottom: var(--sp-1);
}
.summary-grid dd,
.compare-card dd {
  margin: 0;
  font-size: var(--text-table);
  overflow-wrap: anywhere;
}
.sha,
.target-ref {
  font-family: var(--font-mono);
  font-size: var(--text-meta);
  overflow-wrap: anywhere;
}
.badge + .badge {
  margin-left: var(--sp-1);
}
.readonly-note {
  margin: var(--sp-3) 0 0;
}
.view-picker {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
}
.revision-select {
  width: 8.5rem;
}
.credential-note {
  margin-left: var(--sp-1);
}
.dep-chip + .dep-chip {
  margin-left: var(--sp-1);
}
/* 依赖分层视图：纯 CSS 缩进（--dag-depth 由模板按层级绑定），不引入图库 */
.dag,
.dag-steps {
  list-style: none;
  margin: 0;
  padding: 0;
}
.dag-layer {
  padding-left: calc(var(--dag-depth, 0) * var(--sp-5));
  border-left: 2px solid var(--line);
  padding-top: var(--sp-1);
  padding-bottom: var(--sp-1);
}
.dag-depth {
  color: var(--muted);
  font-size: var(--text-meta);
}
.dag-step {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-1) var(--sp-2);
  padding: var(--sp-1) 0;
  font-size: var(--text-table);
}
.dag-step-id {
  font-family: var(--font-mono);
  font-size: var(--text-meta);
}
/* 修订对比 */
.compare-pickers {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-4);
  margin-bottom: var(--sp-3);
}
.picker {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.compare-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
  gap: var(--sp-3);
}
.compare-card {
  border: 1px solid var(--line);
  border-radius: 0.45rem;
  background: var(--surface-2);
  padding: var(--sp-3) var(--sp-4);
  min-width: 0;
}
.compare-card dl {
  display: grid;
  gap: var(--sp-2);
  margin: 0;
}
.diff-block {
  margin-top: var(--sp-4);
}
.diff-list {
  list-style: none;
  margin: 0 0 var(--sp-3);
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.diff-list li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-1) var(--sp-2);
  font-size: var(--text-table);
}
</style>
