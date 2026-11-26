<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from "vue";
import { useRoute } from "vue-router";
import ChatConfig from "../ChatConfig.vue";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
// F2-1：records/:runId 跳出为独立路由（app-run-record，见 router.ts），
// 工作区内嵌 RunDetail 分支随之移除；智能体头复用 resource-format 的状态文案。
import { statusLabel } from "./resources/resource-format";
import { usePageOperation, usePageTitle } from "../usePageOperation";
import { useUnsavedChanges } from "../../../shared/useUnsavedChanges";
import { confirmDanger } from "../../../shared/components/DangerConfirm";
import { buildEnvironmentConfirmation, isGateRejection, type EnvironmentConfirmation, type GateRejection } from "../publish-gate";
import AppOverviewPanel from "./workspace/AppOverviewPanel.vue";
// F3-5：概览 manifest 分区卡编辑（七分区受控编辑/CAS 保存/预检同屏），校验状态联动智能体头。
import ManifestPanel from "./workspace/ManifestPanel.vue";
import AppPublishPanel from "./workspace/AppPublishPanel.vue";
import AppRunPanel from "./workspace/AppRunPanel.vue";
import AppChatPanel from "./workspace/AppChatPanel.vue";
import PortalConversation from "../../../shared/PortalConversation.vue";
import AppRunsPanel from "./workspace/AppRunsPanel.vue";
import {
  emptyAppDetail,
  parseAppDetail,
  type AppDetail,
  type EvaluationSnapshot,
  type PublishedPointer,
} from "../../../shared/resource-types";
import type { PublishPrecheck } from "../publish-gate";


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

// MNT-03：应用详情/预检/评测/发布回执稳定外壳（预检复用 publish-gate 的 PublishPrecheck）。
const app = ref<AppDetail>(emptyAppDetail());
usePageTitle(() => app.value?.name || code.value || "智能体工作区");
const flow = ref<any>();
const livePublishedChat = computed(() =>
  import.meta.env.VITE_MOCK !== 'true' && app.value.type === 'CHAT' && Number(app.value.currentVersionNo) > 0);
const supportsChat = computed(() => app.value.type !== 'FLOW' || code.value === 'user-governance-assistant' || flow.value?.definition?.trigger === 'CHAT');
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
// F3-5：manifest 分区卡校验状态（ManifestPanel 上抛）——智能体头 [提交发布] checklist 联动口径：
// 仅当服务端存在 manifest 头修订（exists）且当前草稿校验不过时禁用提交；
// 无 manifest 的应用预检整组 SKIPPED（存量发布口径），联动不生效。
const manifestPanelRef = ref<InstanceType<typeof ManifestPanel> | null>(null);
const manifestState = ref({
  loaded: false,
  exists: false,
  ok: true,
  issues: [] as { path: string; code: string; message: string }[],
  revision: null as number | null,
  dirty: false,
});
const manifestBlocked = computed(
  () => manifestState.value.loaded && manifestState.value.exists && !manifestState.value.ok,
);
const manifestBlockHint = computed(() =>
  manifestBlocked.value
    ? `manifest 校验未通过（${manifestState.value.issues.length} 项）：` +
      `${manifestState.value.issues[0]?.path ?? ""} ${manifestState.value.issues[0]?.message ?? ""}` +
      "——请在概览 manifest 分区卡修复 issues 后保存，再提交发布"
    : "",
);
function onManifestState(next: typeof manifestState.value) {
  manifestState.value = next;
}
function onManifestSaved() {
  // manifest 引用已变更：既有预检结果过期，发布链必须重新预检（与配置保存同纪律）。
  precheckStale.value = true;
}
useUnsavedChanges({
  isDirty: () => configDirty.value || manifestState.value.dirty,
  securityKey: () => auth.token ?? undefined,
  onReset: () => {
    config.value = configBaseline.value;
    manifestPanelRef.value?.resetDraft();
  },
});
const refreshKey = ref(0);
const precheck = ref<PublishPrecheck | null>(null);
const precheckStale = ref(false);
const gateBlocked = ref<GateRejection | null>(null);
const published = ref<PublishedPointer | null>(null);
const evaluation = ref<EvaluationSnapshot | null>(null);
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
    const selected = {
      precheckId: current.precheckId,
      evalReportId: current.evalReportId ?? "",
      evidenceSha256: current.environmentEvidenceSha256 ?? "",
    };
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
    app.value = emptyAppDetail();
    refreshKey.value = 0;
    const requested = route.fullPath;
    const next = await api<any>(
      `/admin/apps/${encodeURIComponent(code.value)}`,
    );
    if (route.fullPath !== requested) return;
    app.value = next;
    refreshKey.value++;
    config.value = JSON.stringify(next.config || {}, null, 2);
    // 基线必须与编辑器里的文本完全一致（含缩进），否则 compare 永远 dirty，
    // 离开页面时会弹出「有未保存的更改」并拦住正常导航。
    configBaseline.value = config.value;
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
  manifestState.value = {
    loaded: false,
    exists: false,
    ok: true,
    issues: [],
    revision: null,
    dirty: false,
  };
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
  // F1-3：原生 confirm 清零，候选发布改走 DangerConfirm（文案按目标环境区分）。
  if (
    !(await confirmDanger({
      title: "发布候选版本",
      message:
        import.meta.env.VITE_MOCK === 'true'
          ? "确认发布当前候选版本？发布后将保留独立的配置快照。"
          : demo
            ? "确认仅进行本次非生产演示发布？该演示评测不能作为生产来源证明。"
            : "确认将当前候选发布为正式版本？发布后可被业务系统调用。",
      confirmText: "发布",
    }))
  ) {
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
  // F1-3：原生 confirm 清零，停用/启用/归档改走 DangerConfirm（启用非危险态）。
  if (
    !(await confirmDanger({
      title: `${label}应用`,
      message: `确认${label}应用？停用或归档后外部调用将不可用。`,
      confirmText: label,
      danger: action !== "enable",
    }))
  )
    return;
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
    :title="app?.name || code || '智能体工作区'"
    eyebrow="智能体工作区"
    :subtitle="app?.description || '配置、调试、评测并发布这个智能体。'"
    :busy="busy"
    @refresh="load"
  />
  <PageError :error="error" :trace="trace" />
  <div v-if="!app && busy" class="panel"><el-skeleton :rows="8" animated /></div>
  <!-- F2-1 工作区壳层（前端稿 §5.2/§7.1）：顶部智能体头（名称/编码/状态/版本摘要/主要动作）
       + 左锚点导航 + 右内容区。锚点=既有面板分区（复用现有 API 与组件，不重写面板内部），
       key 即路由 tabMatch，点击切换定位、当前锚点高亮。 -->
  <div v-if="app" class="workspace">
    <header class="agent-head">
      <div class="agent-id">
        <span class="agent-name">{{ app.name }}</span>
        <span class="agent-code font-mono">{{ code }}</span>
        <span :class="['status', app.status]">{{ statusLabel(app.status) }}</span>
      </div>
      <div class="agent-versions muted">
        <span>草稿 {{ app.draftRev || "—" }}</span>
        <span aria-hidden="true">·</span>
        <span>{{
          app.currentVersionNo ? "已发布 v" + app.currentVersionNo : "尚未发布"
        }}</span>
      </div>
      <div class="actions">
        <!-- 主要动作入口沿用发布链路的既有 check/commit（预检与提交发布），不新造 API。 -->
        <el-button :loading="busy" v-permission="'edit'" @click="check">预检</el-button>
        <!-- F3-5 checklist 联动：manifest 校验不过 → 提交发布禁用 + tooltip 指明首条 issue；
             禁用按钮不派发鼠标事件，hover 说明包一层 span 承接。 -->
        <el-tooltip
          :disabled="!manifestBlocked"
          :content="manifestBlockHint || '提交发布'"
          placement="top"
        >
          <span class="commit-anchor">
            <el-button
              type="primary"
              :loading="busy"
              :disabled="manifestBlocked"
              v-permission="'edit'"
              @click="commit"
              >提交发布</el-button
            >
          </span>
        </el-tooltip>
      </div>
    </header>
    <div class="workspace-body">
      <nav class="anchor-rail" aria-label="工作区锚点">
        <a
          v-for="[label, next] in [
            ['概览', ''],
            ...(supportsChat ? [['对话', 'chat'] as [string, string]] : []),
            ...(supportsChat && app.type === 'FLOW' ? [['对话配置', 'orchestration/chat'] as [string, string]] : []),
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
      <section class="workspace-content page-scroll">
      <AppOverviewPanel
        v-if="tab === 'overview'"
        :app="app"
        :code="code"
        :versions="versions"
        :busy="busy"
        :refresh-key="refreshKey"
        @lifecycle="lifecycle"
        @save-metadata="saveMetadata"
        @reload="load"
      />
      <!-- F3-5：概览 manifest 分区卡（卡面口径「概览 manifest 分区卡编辑」——紧跟概览分区同屏；
           :key=code 切换智能体时重挂载重读；校验状态经 validation-change 联动智能体头）。 -->
      <ManifestPanel
        v-if="tab === 'overview'"
        ref="manifestPanelRef"
        :key="code"
        :code="code"
        :busy="busy"
        :precheck="precheck"
        @validation-change="onManifestState"
        @saved="onManifestSaved"
        @check="check"
      />
      <PortalConversation
        v-else-if="livePublishedChat && (tab === 'run' || (tab === 'chat' && app.status === 'PUBLISHED'))"
        :key="code"
        :app-code="code"
        session-id=""
      />
      <AppChatPanel
        v-else-if="tab === 'chat'"
        :app-code="code"
        :app-name="app.name"
        :current-version-no="app.currentVersionNo"
        :mode="app.status === 'PUBLISHED' ? 'published' : 'draft'"
      />
      <ChatConfig
        v-else-if="tab === 'orchestration/chat'"
        :config="app.config"
        :app-code="code"
        :biz-category="app.bizCategory"
        :draft-rev="app.draftRev"
        :draft-key="code + ':chat'"
        @save="saveConfig"
      />
      <FlowDesigner
        v-else-if="tab === 'orchestration/flow' && flow?.definition"
        :read-only="!auth.can('edit')"
        :definition="flow.definition"
        :catalogs="catalogs"
        :app-code="code"
        :biz-category="app.bizCategory"
        :layout="flow.layout || {}"
        :draft-key="code + ':flow'"
        :draft-rev="app.draftRev"
        @save="saveFlow"
      />
      <DebugPanel v-else-if="tab === 'orchestration/debug'" :key="code" :app-code="code" />
      <!-- F2-1：records/:runId 改独立路由 app-run-record（/apps/:code/records/:runId），
           不再内嵌在工作区 tab 里；运行记录锚点内的行链接跳该独立页并带面包屑返回。 -->
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
      <AppChatPanel
        v-else-if="tab === 'run' && supportsChat"
        :app-code="code"
        :app-name="app.name"
        :current-version-no="app.currentVersionNo"
        mode="published"
      />
      <AppRunPanel
        v-else-if="tab === 'run'"
        :app="app"
        :code="code"
        :versions="versions"
      />
      <AppRunsPanel v-else-if="tab === 'runs'" :app-code="code" />
      </section>
    </div>
  </div>
</template>

<style scoped>
/* F2-1 工作区壳层高度模型（前端稿 §7.1）：智能体头 auto + body flex row
   （锚点列 auto 自身可滚 + 内容 .page-scroll 唯一滚动容器，min-height:0 贯穿高度链）。
   色板/间距/圆角只用 tokens 变量，不新增裸色值（红线）。 */
.workspace {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.agent-head {
  flex-shrink: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-3) var(--sp-4);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  margin-bottom: var(--sp-4);
}
.agent-id {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  min-width: 0;
}
.agent-name {
  font-size: var(--text-module);
  font-weight: 650;
}
.agent-code {
  color: var(--muted);
  font-size: var(--text-meta);
}
.agent-versions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--text-meta);
}
/* F3-5：tooltip 承接层——禁用的提交发布按钮不派发鼠标事件，hover 说明由 span 接住。 */
.commit-anchor {
  display: inline-flex;
}
.workspace-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  gap: var(--sp-4);
}
/* 锚点导航列（§5.2：页内分区定位，不再平级 Tab）：自身可滚（§7.1 锚点列 auto），
   当前锚点高亮。 */
.anchor-rail {
  flex-shrink: 0;
  width: 9rem;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
}
.anchor-rail a {
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--radius);
  color: var(--muted);
  white-space: nowrap;
}
.anchor-rail a.active {
  background: var(--surface-2);
  color: var(--ink);
  font-weight: 650;
}
/* 面板内容区=工作区内唯一滚动容器（.page-scroll 语义，min-height:0）；
   画布类面板（FlowDesigner 58vh/DebugPanel）不依赖容器滚动，布局不参与三级滚动。 */
.workspace-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
</style>
