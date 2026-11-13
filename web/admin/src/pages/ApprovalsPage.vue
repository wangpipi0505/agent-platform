<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import DataPage from "../../../shared/components/DataPage.vue";
import EmptyState from "../../../shared/components/EmptyState.vue";
import ErrorBanner from "../../../shared/components/ErrorBanner.vue";
import { confirmDanger } from "../../../shared/components/DangerConfirm";
import PageHeader from "../PageHeader.vue";
import ActionPlanPanel from "./ActionPlanPanel.vue";
import { usePageTitle } from "../usePageOperation";
import { notifyConfirmationCountsChanged } from "../../../shared/mock-confirmation-counts";
import {
  decideDraftMemory, decideHighRiskApproval, decidePublishRequest, listDraftMemoryTodos,
  listPendingHighRiskApprovals, listPendingPublishRequests, listPlanConfirmationTodos,
  type DemoHighRiskApproval, type DemoPlanConfirmationTodo, type DemoPublishRequest,
} from "../../../shared/mock-confirmation-matrix";
import type { MockMemoryDetail } from "../../../shared/mock-memories";

// F3-3（前端稿 §5.9 确认中心：四类待办矩阵）——按确认类型分组 Tab：
// ① 发布评审：GET /admin/apps/{code}/publish-requests?status=PENDING 形状；处理角色口径=
//    ADMIN 提交审批（ConfigRelease 现有动作），APPROVER 只读可见（authority 扩展前不放开）。
// ② 高危操作：现有 InlineApproval 待审（runId+nodeId 决策入口，双主体确认链 V224，
//    不经计划确认）→ 行内展开行动计划凭据摘要，批准/拒绝走 confirmDanger。
// ③ 计划确认：GET /v1/tasks?state=PLAN_READY&confirmableBy=me 以修订为单位（仅无有效凭据
//    的修订回流）；处理=跳计划详情（确认/执行本体在助手端计划卡，管理台指路）。
// ④ DRAFT 记忆：GET /v1/apps/{code}/memories?status=DRAFT；ADMIN（共享层）/来源用户（私有层）
//    经 PATCH {status: ACTIVE|REVOKED, expectedVersion} 审批。
// 角标与四类列表同源（GET /admin/confirmation-center/counts 口径：total=四类未分页总数之和），
// 处置后同刷：列表 reload + notifyConfirmationCountsChanged()（AppShell 立即重读角标）。
// mock 期数据源 mock-confirmation-matrix（demo:true），联调期按 §5.9 表格端点切换。
usePageTitle(() => "确认中心");

type TabKey = "publish" | "highRisk" | "plan" | "draftMemory";

const TABS: { key: TabKey; label: string }[] = [
  { key: "publish", label: "发布评审" },
  { key: "highRisk", label: "高危操作" },
  { key: "plan", label: "计划确认" },
  { key: "draftMemory", label: "DRAFT 记忆" },
];
const activeTab = ref<TabKey>("publish");

const busy = ref(false);
const error = ref("");
const publishRows = ref<DemoPublishRequest[]>([]);
const highRiskRows = ref<DemoHighRiskApproval[]>([]);
const planRows = ref<DemoPlanConfirmationTodo[]>([]);
const draftRows = ref<MockMemoryDetail[]>([]);

const tabCount = (key: TabKey) => {
  switch (key) {
    case "publish": return publishRows.value.length;
    case "highRisk": return highRiskRows.value.length;
    case "plan": return planRows.value.length;
    case "draftMemory": return draftRows.value.length;
  }
};
const totalCount = computed(() => TABS.reduce((sum, tab) => sum + tabCount(tab.key), 0));

async function load() {
  busy.value = true;
  error.value = "";
  try {
    publishRows.value = listPendingPublishRequests();
    highRiskRows.value = listPendingHighRiskApprovals();
    planRows.value = listPlanConfirmationTodos();
    draftRows.value = listDraftMemoryTodos();
  } catch (e: unknown) {
    error.value = e instanceof Error && e.message ? e.message : "待办矩阵读取失败，请刷新重试";
  } finally {
    busy.value = false;
  }
}
onMounted(load);
/** 处置后同刷：当前列表 + AppShell 角标（计数与四类列表同源推导）。 */
async function refreshAll() {
  await load();
  notifyConfirmationCountsChanged();
}

// —— ① 发布评审：APPROVER 只读可见不放开提交（§5.9 处理角色口径）——
async function decidePublish(row: DemoPublishRequest, decision: "APPROVE" | "REJECT") {
  if (!(await confirmDanger({
    title: decision === "APPROVE" ? "批准发布" : "拒绝发布",
    message: decision === "APPROVE"
      ? `批准 ${row.appCode} ${row.targetVersion}（manifest r${row.manifestRevision}，digest ${row.digest}）？批准后沿现有语义激活。`
      : `拒绝 ${row.appCode} ${row.targetVersion} 的发布申请？`,
    confirmText: decision === "APPROVE" ? "批准发布" : "拒绝",
  })))
    return;
  const done = decidePublishRequest(row.publishId, decision);
  if ("error" in done) {
    ElMessage.error(done.userMessage ?? "处置失败");
    return;
  }
  ElMessage.success(decision === "APPROVE" ? "已批准，激活沿现有语义执行（mock 演示）" : "已拒绝该发布申请（mock 演示）");
  await refreshAll();
}

// —— ② 高危操作：InlineApproval 双主体链，批准/拒绝走 confirmDanger ——
async function decideHighRisk(row: DemoHighRiskApproval, decision: "APPROVE" | "REJECT") {
  if (!(await confirmDanger({
    title: decision === "APPROVE" ? "批准执行" : "拒绝执行",
    message: decision === "APPROVE"
      ? `确认按上述行动计划执行？当前服务端快照 digest：${row.snapshotDigest}（双主体链：每人只计一次批准）。`
      : `确认拒绝 ${row.runId} / ${row.nodeId} 的高危操作？`,
    confirmText: decision === "APPROVE" ? "允许执行" : "拒绝",
  })))
    return;
  const done = decideHighRiskApproval(row.runId, row.nodeId, decision);
  if ("error" in done) {
    ElMessage.error(done.userMessage ?? "处置失败");
    return;
  }
  ElMessage.success(decision === "APPROVE" ? "已记录批准，服务端确认进度见运行详情（mock 演示）" : "已拒绝执行（mock 演示）");
  await refreshAll();
}

// —— ③ 计划确认：处理=跳计划详情（确认本体在助手端计划卡，管理台只读指路 §5.3）——
const CONFIRM_STATE_LABEL: Record<string, string> = {
  UNCONFIRMED: "未确认", CONFIRMED_VALID: "凭据有效", CONFIRMED_EXPIRED: "凭据已过期",
};
const router = useRouter();
function gotoPlan(row: DemoPlanConfirmationTodo) {
  void router.push(`/plans/${encodeURIComponent(row.planId)}`);
}

// —— ④ DRAFT 记忆：确认→ACTIVE、拒绝→REVOKED（PATCH status 字段语义，CAS）——
async function decideDraft(row: MockMemoryDetail, action: "ACTIVE" | "REVOKED") {
  if (!(await confirmDanger({
    title: action === "ACTIVE" ? "确认草稿生效" : "拒绝并撤销草稿",
    message: action === "ACTIVE"
      ? `确认将 DRAFT 记忆 ${row.memoryId}（${row.scope === "SHARED" ? "共享层" : "私有层"}）置为 ACTIVE？`
      : `拒绝 DRAFT 记忆 ${row.memoryId} 并置为 REVOKED？撤销后立即退出检索。`,
    confirmText: action === "ACTIVE" ? "确认生效" : "拒绝并撤销",
  })))
    return;
  const done = decideDraftMemory(row.memoryId, action, row.version);
  if ("error" in done) {
    if (done.error === "MEMORY_VERSION_CONFLICT") {
      ElMessage.error("版本冲突（409）：草稿已被处理，列表已刷新");
    } else {
      ElMessage.error("草稿不存在或已处理，列表已刷新");
    }
    await load();
    notifyConfirmationCountsChanged();
    return;
  }
  ElMessage.success(action === "ACTIVE" ? "草稿已生效（ACTIVE，mock 演示）" : "草稿已拒绝并撤销（REVOKED，mock 演示）");
  await refreshAll();
}
</script>

<template>
  <DataPage :total="null">
    <template #header>
      <PageHeader
        title="确认中心"
        subtitle="四类待办矩阵：发布评审 / 高危操作 / 计划确认 / DRAFT 记忆；角标与列表同源，处置后同刷（mock 演示数据）。"
        :busy="busy"
        @refresh="refreshAll"
      />
    </template>
    <template #error>
      <ErrorBanner :error="error" />
    </template>
    <template #filters>
      <!-- 四类待办矩阵 Tab（§5.9）：每类 chip 带未分页总数，与角标一致 -->
      <div class="pipeline" role="tablist" aria-label="待办类型">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          class="chip"
          :class="{ 'is-active': activeTab === tab.key }"
          type="button"
          role="tab"
          :aria-selected="activeTab === tab.key"
          :data-tab="tab.key"
          @click="activeTab = tab.key"
        >{{ tab.label }} <span class="count">{{ tabCount(tab.key) }}</span></button>
        <span class="muted">合计 {{ totalCount }}（=角标 total）</span>
      </div>
    </template>
    <div class="page-scroll">
      <el-skeleton v-if="busy && !publishRows.length" :rows="5" animated />

      <!-- ① 发布评审 -->
      <template v-else-if="activeTab === 'publish'">
        <el-table :data="publishRows" stripe height="100%">
          <el-table-column prop="publishId" label="发布单" width="110">
            <template #default="{ row }"><span class="mono">{{ row.publishId }}</span></template>
          </el-table-column>
          <el-table-column prop="appCode" label="智能体" min-width="170" />
          <el-table-column label="目标版本" width="100">
            <template #default="{ row }">{{ row.targetVersion }}</template>
          </el-table-column>
          <el-table-column label="manifest" min-width="140">
            <template #default="{ row }">
              <span class="mono">r{{ row.manifestRevision }} · {{ row.digest }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="precheckSummary" label="预检摘要" min-width="200" show-overflow-tooltip />
          <el-table-column prop="requestedBy" label="申请人" width="100" />
          <el-table-column label="处理角色" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.readonly" type="info" size="small" effect="plain">只读（APPROVER 视角）</el-tag>
              <el-tag v-else type="warning" size="small" effect="plain">ADMIN 审批</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" fixed="right" width="150">
            <template #default="{ row }">
              <template v-if="row.canApprove">
                <el-button text type="primary" @click="decidePublish(row, 'APPROVE')">批准</el-button>
                <el-button text type="danger" @click="decidePublish(row, 'REJECT')">拒绝</el-button>
              </template>
              <span v-else class="muted">只读可见</span>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState variant="filtered" description="暂无待审发布。" />
          </template>
        </el-table>
      </template>

      <!-- ② 高危操作（InlineApproval：行内展开行动计划凭据摘要） -->
      <template v-else-if="activeTab === 'highRisk'">
        <el-table :data="highRiskRows" stripe height="100%">
          <el-table-column type="expand">
            <template #default="{ row }">
              <ActionPlanPanel :row="row" />
            </template>
          </el-table-column>
          <el-table-column prop="runId" label="运行编码" min-width="200">
            <template #default="{ row }"><span class="mono">{{ row.runId }}</span></template>
          </el-table-column>
          <el-table-column prop="nodeDefId" label="待确认节点" min-width="140" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">{{ row.statusLabel }}</template>
          </el-table-column>
          <el-table-column label="确认链" width="130">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.confirmRoles.join("/") }}（双主体）</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" fixed="right" width="150">
            <template #default="{ row }">
              <el-button text type="primary" @click="decideHighRisk(row, 'APPROVE')">确认</el-button>
              <el-button text type="danger" @click="decideHighRisk(row, 'REJECT')">拒绝</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState variant="filtered" description="暂无待确认的高危操作。" />
          </template>
        </el-table>
      </template>

      <!-- ③ 计划确认（以修订为单位；处理=跳计划详情） -->
      <template v-else-if="activeTab === 'plan'">
        <el-table :data="planRows" stripe height="100%">
          <el-table-column prop="taskId" label="任务" min-width="140">
            <template #default="{ row }"><span class="mono">{{ row.taskId }}</span></template>
          </el-table-column>
          <el-table-column prop="goal" label="目标" min-width="220" show-overflow-tooltip />
          <el-table-column label="计划/修订" min-width="130">
            <template #default="{ row }">
              <span class="mono">{{ row.planId }} · r{{ row.revision }}</span>
            </template>
          </el-table-column>
          <el-table-column label="digest" min-width="100">
            <template #default="{ row }"><span class="mono">{{ row.digest }}</span></template>
          </el-table-column>
          <el-table-column label="确认状态" width="120">
            <template #default="{ row }">
              <el-tag :type="row.confirmState === 'CONFIRMED_EXPIRED' ? 'warning' : 'info'" size="small">
                {{ CONFIRM_STATE_LABEL[row.confirmState] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" fixed="right" width="110">
            <template #default="{ row }">
              <el-button text type="primary" @click="gotoPlan(row)">去确认</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState variant="filtered" description="暂无待确认计划（已确认且在有效期内的修订不回流）。" />
          </template>
        </el-table>
      </template>

      <!-- ④ DRAFT 记忆（行内展开正文摘要，不再 JSON.stringify 整包） -->
      <template v-else>
        <el-table :data="draftRows" stripe height="100%">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="draft-content">{{ row.content }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="memoryId" label="记忆 ID" min-width="150">
            <template #default="{ row }"><span class="mono">{{ row.memoryId }}</span></template>
          </el-table-column>
          <el-table-column label="层级" width="90">
            <template #default="{ row }">{{ row.scope === "SHARED" ? "共享层" : "私有层" }}</template>
          </el-table-column>
          <el-table-column prop="kind" label="类型" width="120" />
          <el-table-column label="标签" min-width="130">
            <template #default="{ row }">
              <el-tag v-for="tag in row.tags" :key="tag" size="small" effect="plain" class="tag-gap">{{ tag }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="处理角色" width="130">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.scope === "SHARED" ? "ADMIN" : "来源用户" }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" fixed="right" width="160">
            <template #default="{ row }">
              <el-button text type="primary" @click="decideDraft(row, 'ACTIVE')">通过</el-button>
              <el-button text type="danger" @click="decideDraft(row, 'REVOKED')">拒绝</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState variant="filtered" description="暂无 DRAFT 记忆待审批。" />
          </template>
        </el-table>
      </template>
    </div>
  </DataPage>
</template>

<style scoped>
.page-scroll {
  height: 100%;
  overflow-y: auto;
}
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
.count {
  font-weight: 700;
  margin-left: 0.2rem;
}
.mono {
  font-family: var(--font-mono, monospace);
}
.muted {
  color: var(--muted);
  font-size: var(--text-meta);
}
.tag-gap {
  margin-right: var(--sp-1);
}
.draft-content {
  white-space: pre-wrap;
  word-break: break-word;
  padding: var(--sp-2) var(--sp-4);
  color: var(--text-secondary);
}
</style>
