<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import ChatConfig from "../ChatConfig.vue";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import RunDetail from "../RunDetail.vue";
import { usePageOperation, usePageTitle } from "../usePageOperation";
import { useUnsavedChanges } from "../../../shared/useUnsavedChanges";
import { buildEnvironmentConfirmation, isGateRejection, type EnvironmentConfirmation, type GateRejection } from "../publish-gate";
import AppOverviewPanel from "./workspace/AppOverviewPanel.vue";
import AppPublishPanel from "./workspace/AppPublishPanel.vue";
import AppRunPanel from "./workspace/AppRunPanel.vue";
import AppRunsPanel from "./workspace/AppRunsPanel.vue";

const FlowDesigner = defineAsyncComponent(
  () => import("../../../flow-designer/FlowDesigner.vue"),
);
const DebugPanel = defineAsyncComponent(
  () => import("../../../debug-panel/DebugPanel.vue"),
);

const route = useRoute();
const { auth, api, busy, error, trace, success, failure, operation } =
  usePageOperation();
const code = computed(() => String(route.params.code || ""));
const tab = computed(() => {
  const parts = route.params.tabMatch;
  return (Array.isArray(parts) ? parts.join("/") : String(parts || "")) || "overview";
});

const app = ref<any>();
usePageTitle(() => app.value?.name || code.value || "应用工作区");
const flow = ref<any>();
const catalogs = ref({
  models: [] as any[],
  skills: [] as any[],
  templates: [] as any[],
});
const versions = ref<any[]>([]);
const config = ref("{}");
// MNT-02子步2：以最近一次服务端返回的 config 为基线计算 dirty（本地重渲染不算）；
// saveConfig/saveFlow/saveMetadata 成功后 app.value 更新，基线随之复位不误报。
const configBaseline = ref("{}");
const configDirty = computed(() => config.value !== configBaseline.value);
useUnsavedChanges({
  isDirty: () => configDirty.value,
  securityKey: () => auth.token ?? undefined,
  onReset: () => {
    config.value = configBaseline.value;
  },
});
const refreshKey = ref(0);
const precheck = ref<any>();
const precheckStale = ref(false);
const gateBlocked = ref<GateRejection | null>(null);
const published = ref<any>();
const evaluation = ref<any>();
const evaluationNeedsCheck = ref(false);
const evaluationWorking = ref(false);
const requestNo = ref("");
const environmentConfirmation = ref<EnvironmentConfirmation | null>(null);
const environmentConfirmed = computed(() => {
  if (!precheck.value || !environmentConfirmation.value) return false;
  try { return !!buildEnvironmentConfirmation(precheck.value, environmentConfirmation.value); }
  catch { return false; }
});

function confirmEnvironment(confirmed: boolean) {
  environmentConfirmation.value = null;
  if (!confirmed || busy.value || precheckStale.value || evaluationWorking.value || evaluationNeedsCheck.value) return;
  const current = precheck.value;
  try {
    if (!current) throw new Error("请先完成当前候选的发布预检");
    const selected = { precheckId: current.precheckId, evalReportId: current.evalReportId,
      evidenceSha256: current.environmentEvidenceSha256 };
    if (!buildEnvironmentConfirmation(current, selected)) throw new Error("本次预检无需演示发布确认");
    environmentConfirmation.value = selected;
  } catch (problem) { failure(problem); }
}

// 确认只属于这次预检与当前身份；重新预检、改稿、评测或身份切换均不能继承旧勾选。
watch([() => precheck.value, () => precheck.value?.precheckId, () => precheck.value?.evalReportId,
  () => precheck.value?.environmentEvidenceSha256, () => precheck.value?.publicationTarget,
  () => precheck.value?.evaluationEnvironment, () => precheck.value?.environmentConfirmationRequired,
  () => precheckStale.value, () => evaluationWorking.value, () => evaluationNeedsCheck.value,
  () => gateBlocked.value, () => auth.generation, () => auth.token,
  () => auth.user?.tenantId, () => auth.user?.principalId, () => auth.roles.join(",")],
  () => { environmentConfirmation.value = null; }, { flush: "sync" });
watch(error, (message) => { if (message) environmentConfirmation.value = null; }, { flush: "sync" });

async function load() {
  if (!code.value || !auth.token) return;
  await operation(async () => {
    detailReset();
    app.value = undefined;
    refreshKey.value = 0;
    const requested = route.fullPath;
    const next = await api<any>(
      `/admin/apps/${encodeURIComponent(code.value)}`,
    );
    if (route.fullPath !== requested) return;
    app.value = next;
    configBaseline.value = JSON.stringify(app.value?.config ?? {});
    refreshKey.value++;
    config.value = JSON.stringify(next.config || {}, null, 2);
    if (tab.value === "orchestration/flow") {
      flow.value = await api(
        `/admin/flows/${encodeURIComponent(code.value)}`,
      );
      const results = await Promise.allSettled(
        ["/admin/model-entries", "/admin/skills", "/admin/prompt-templates"].map(
          (path) => api<any[]>(path),
        ),
      );
      results.forEach((result, index) => {
        const key = (["models", "skills", "templates"] as const)[index];
        catalogs.value[key] =
          result.status === "fulfilled" ? result.value : [];
        if (result.status === "rejected") failure(result.reason);
      });
    }
    if (["overview", "run"].includes(tab.value))
      versions.value = await api(
        `/admin/apps/${encodeURIComponent(code.value)}/versions`,
      );
  });
}
function detailReset() {
  environmentConfirmation.value = null;
  precheck.value = null;
  precheckStale.value = false;
  gateBlocked.value = null;
  published.value = null;
  evaluation.value = null;
  evaluationNeedsCheck.value = false;
  evaluationWorking.value = false;
  flow.value = undefined;
  versions.value = [];
  requestNo.value = "";
}

function evaluationActivity(active: boolean) {
  evaluationWorking.value = active;
  if (active) evaluationNeedsCheck.value = true;
}

async function saveConfig(value?: any) {
  await operation(async () => {
    const result = await api(
      `/admin/apps/${code.value}/config`,
      "PUT",
      { draftRev: app.value.draftRev, config: value || JSON.parse(config.value) },
    );
    app.value = result;
    configBaseline.value = JSON.stringify(app.value?.config ?? {});
    precheckStale.value = true;
    success("配置已保存");
  });
}
async function saveFlow(data: any) {
  await operation(async () => {
    const result = await api(`/admin/flows/${code.value}`, "PUT", {
      draftRev: app.value.draftRev,
      ...data,
    });
    flow.value = result;
    app.value.draftRev = result.draftRev;
    precheckStale.value = true;
    success("编排已保存并校验");
  });
}
async function check() {
  if (busy.value) return;
  environmentConfirmation.value = null;
  const requestedPath = route.fullPath;
  const requestedCode = code.value;
  const requestedRev = app.value?.draftRev;
  await operation(async () => {
    let result;
    try {
      result = await api(
        `/admin/apps/${requestedCode}/publish/precheck`,
        "POST",
      );
    } catch (e) {
      if (isGateRejection(e)) {
        // 门禁 409 是发布面板"为何被拦"的同屏素材：在此消费，不再弹全局错误条。
        // 后端在抛 409 前已落完整检查快照，但响应体只带 code/message/traceId。
        if (
          route.fullPath === requestedPath &&
          code.value === requestedCode &&
          app.value?.draftRev === requestedRev
        )
          gateBlocked.value = {
            status: e.status,
            code: e.code,
            message: e.message,
            traceId: e.traceId,
          };
        return;
      }
      throw e;
    }
    if (
      route.fullPath !== requestedPath ||
      code.value !== requestedCode ||
      app.value?.draftRev !== requestedRev
    )
      return;
    if (
      evaluation.value &&
      (evaluation.value.candidateSha256 !== result.candidateSha256 ||
        evaluation.value.draftRev !== result.draftRev)
    )
      evaluation.value = null;
    precheck.value = result;
    gateBlocked.value = null;
    evaluationNeedsCheck.value = false;
    precheckStale.value = false;
    requestNo.value = crypto.randomUUID();
  });
}
function onEvaluationReport(value: any) {
  if (
    value.appCode !== code.value ||
    value.draftRev !== precheck.value?.draftRev ||
    value.candidateSha256 !== precheck.value?.candidateSha256
  )
    return;
  evaluation.value = value;
  evaluationNeedsCheck.value = true;
}
async function commit() {
  const demo = precheck.value?.publicationTarget === "NON_PRODUCTION" && precheck.value?.evaluationEnvironment === "DEMO";
  if (!confirm(demo ? "确认仅进行本次非生产演示发布？该演示评测不能作为生产来源证明。" : "确认将当前候选发布为正式版本？发布后可被业务系统调用。")) {
    environmentConfirmation.value = null;
    return;
  }
  await operation(async () => {
    const current = precheck.value;
    if (!current?.precheckId) throw new Error("请先重新预检并通过评测");
    if (!current.passed) throw new Error("当前预检尚未通过，请先处理检查结果");
    if (precheckStale.value) throw new Error("配置已变更，预检已过期，请重新预检");
    if (
      evaluationWorking.value ||
      evaluationNeedsCheck.value ||
      (evaluation.value &&
        (evaluation.value.status !== "ACCEPTED" ||
          evaluation.value.candidateSha256 !== current.candidateSha256 ||
          evaluation.value.draftRev !== current.draftRev))
    )
      throw Error("评测尚未完成或报告未重新绑定，请重新预检");
    const confirmation = buildEnvironmentConfirmation(current, environmentConfirmation.value);
    try {
      published.value = await api(
        `/admin/apps/${code.value}/publish/commit`,
        "POST",
        {
          precheckId: current.precheckId,
          draftRev: current.draftRev,
          candidateSha256: current.candidateSha256,
          idempotencyRequestNo: requestNo.value,
          ...(confirmation ? { evaluationEnvironmentConfirmation: confirmation } : {}),
        },
      );
    } catch (e) {
      if (isGateRejection(e)) {
        // commit 复核豁免（撤销/过期）返回同一 409：预检结果已不可用，交给面板说明。
        gateBlocked.value = {
          status: e.status,
          code: e.code,
          message: e.message,
          traceId: e.traceId,
        };
        precheckStale.value = true;
        return;
      }
      throw e;
    } finally {
      environmentConfirmation.value = null;
    }
    success(confirmation ? "非生产演示版本已发布" : "发布成功");
  });
}
async function lifecycle(action: string) {
  const label = (
    { disable: "停用", enable: "启用", archive: "归档" } as Record<string, string>
  )[action];
  if (!confirm(`确认${label}应用？停用或归档后外部调用将不可用。`)) return;
  await operation(async () => {
    await api(`/admin/apps/${code.value}/${action}`, "POST", {});
    await load();
  });
}
async function saveMetadata() {
  await operation(async () => {
    app.value = await api(`/admin/apps/${code.value}`, "PUT", {
      draftRev: app.value.draftRev,
      name: app.value.name,
      description: app.value.description,
      domain: app.value.domain,
      bizCategory: app.value.bizCategory,
      tags: app.value.tags || [],
    });
    configBaseline.value = JSON.stringify(app.value?.config ?? {});
    precheckStale.value = true;
    success("应用信息已保存");
  });
}

watch(() => route.fullPath, () => void load());
watch(code, () => void load());
onMounted(() => void load());
</script>

<template>
  <PageHeader
    :title="app?.name || code || '应用工作区'"
    eyebrow="应用工作区"
    :subtitle="app?.description || '配置、调试、评测并发布这个智能体。'"
    :busy="busy"
    @refresh="load"
  />
  <PageError :error="error" :trace="trace" />
  <div v-if="!app && busy" class="panel"><el-skeleton :rows="8" animated /></div>
  <template v-if="app">
    <nav class="tabs">
      <a
        v-for="[label, next] in [
          ['概览', ''],
          ['编排', app.type === 'FLOW' ? 'orchestration/flow' : 'orchestration/chat'],
          ['调试', 'orchestration/debug'],
          ['发布集成', 'orchestration/publish'],
          ['正式运行', 'run'],
          ['运行记录', 'runs'],
        ]"
        :key="next"
        :href="'#/apps/' + code + (next ? '/' + next : '')"
        :class="{ active: tab === (next || 'overview') }"
        >{{ label }}</a
      >
    </nav>
    <AppOverviewPanel
      v-if="tab === 'overview'"
      :app="app"
      :code="code"
      :versions="versions"
      :busy="busy"
      :refresh-key="refreshKey"
      @lifecycle="lifecycle"
      @save-metadata="saveMetadata"
    />
    <ChatConfig
      v-else-if="tab === 'orchestration/chat'"
      :config="app.config"
      :draft-rev="app.draftRev"
      :draft-key="code + ':chat'"
      @save="saveConfig"
    />
    <FlowDesigner
      v-else-if="tab === 'orchestration/flow' && flow?.definition"
      :read-only="!auth.can('edit')"
      :definition="flow.definition"
      :catalogs="catalogs"
      :layout="flow.layout || {}"
      :draft-key="code + ':flow'"
      :draft-rev="app.draftRev"
      @save="saveFlow"
    />
    <DebugPanel v-else-if="tab === 'orchestration/debug'" :key="code" :app-code="code" />
    <RunDetail v-else-if="tab.startsWith('records/')" :run-id="tab.split('/')[1]" />
    <AppPublishPanel
      v-else-if="tab === 'orchestration/publish'"
      :app="app"
      :code="code"
      :busy="busy"
      :precheck="precheck"
      :precheck-stale="precheckStale"
      :published="published"
      :evaluation="evaluation"
      :evaluation-working="evaluationWorking"
      :evaluation-needs-check="evaluationNeedsCheck"
      :gate-blocked="gateBlocked"
      :is-admin="auth.roles.includes('ADMIN')"
      :environment-confirmed="environmentConfirmed"
      @environment-confirm="confirmEnvironment"
      @check="check"
      @commit="commit"
      @report="onEvaluationReport"
      @activity="evaluationActivity"
      @bind="check"
    />
    <AppRunPanel
      v-else-if="tab === 'run'"
      :app="app"
      :code="code"
      :versions="versions"
    />
    <AppRunsPanel v-else-if="tab === 'runs'" :app-code="code" />
  </template>
</template>
