<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import DataPage from "../../../shared/components/DataPage.vue";
import EmptyState from "../../../shared/components/EmptyState.vue";
import ErrorBanner from "../../../shared/components/ErrorBanner.vue";
import { confirmDanger } from "../../../shared/components/DangerConfirm";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import { useLiveList } from "../../../shared/hooks/useLiveList";
import {
  addPluginGrant, createPluginSource, listPluginSources, listPlugins, pollDiscovery,
  removePluginGrant, setPluginEnabled, triggerDiscovery,
  type DiscoveryStatus, type MockDiscovery, type MockPlugin, type MockPluginSource, type PluginGrant,
} from "../../../shared/mock-plugins";

// F3-3（台账 §7.5 / 前端稿 §3.1 集成与治理·插件与来源）：资产管理页——插件。
// 来源/插件/版本/授权列表 + 启用停用（disable 必填 reason，停用拦截后续调用）+
// 发现触发（202 {discoveryId,status:'QUEUED'} → 轮询 QUEUED/RUNNING/SUCCEEDED/FAILED；
// 失败返回可展示 error 且保留上一次成功目录，成功合入 changes:{added,changed,removed}）。
// 数据源 mock-plugins（接口稿 §5.6 形状，demo:true）。
usePageTitle(() => "插件管理");

const { rows, loading: busy, error, refresh: reload } = useLiveList<MockPlugin>(async () => listPlugins());
const sources = ref<MockPluginSource[]>([]);
async function loadSources() {
  sources.value = listPluginSources();
}
loadSources();
const sourceName = (sourceId: string) =>
  sources.value.find((s) => s.id === sourceId)?.name ?? sourceId;
const statusCount = (status: MockPlugin["status"]) =>
  rows.value.filter((p) => p.status === status).length;

// —— 发现状态机（A13-1：插件失败与成功两分支；失败保留上一次目录）——
const POLL_MS = 600;
const discoveries = reactive(new Map<string, MockDiscovery>());
const pollingTimers = new Map<string, ReturnType<typeof setInterval>>();
onUnmounted(() => pollingTimers.forEach((t) => clearInterval(t)));
const DISCOVERY_LABEL: Record<DiscoveryStatus, string> = {
  QUEUED: "已排队", RUNNING: "发现中", SUCCEEDED: "成功", FAILED: "失败",
};
function stopPolling(sourceId: string) {
  const timer = pollingTimers.get(sourceId);
  if (timer) {
    clearInterval(timer);
    pollingTimers.delete(sourceId);
  }
}
function startPolling(sourceId: string, discoveryId: string) {
  stopPolling(sourceId);
  const timer = setInterval(async () => {
    const done = pollDiscovery(sourceId, discoveryId);
    if ("error" in done) {
      stopPolling(sourceId);
      ElMessage.error("发现任务查询失败，请刷新");
      return;
    }
    discoveries.set(discoveryId, done);
    if (done.status === "SUCCEEDED") {
      stopPolling(sourceId);
      const changes = done.changes!;
      ElMessage.success(
        `发现完成：新增 ${changes.added.length}、变更 ${changes.changed.length}、移除 ${changes.removed.length}（mock 演示）`,
      );
      await Promise.all([reload(), loadSources()]);
    } else if (done.status === "FAILED") {
      // 失败：展示 error:{code,message}；目录不动（失败保留上一次成功目录）。
      stopPolling(sourceId);
      ElMessage.error(`发现失败：${done.error?.code}——${done.error?.message}`);
      await Promise.all([reload(), loadSources()]);
    }
  }, POLL_MS);
  pollingTimers.set(sourceId, timer);
}
async function discover(source: MockPluginSource) {
  const accepted = triggerDiscovery(source.id);
  if ("error" in accepted) {
    ElMessage.error(accepted.userMessage ?? "触发发现失败");
    return;
  }
  ElMessage.info(`发现已受理（202）：${accepted.discoveryId} 已排队`);
  discoveries.set(accepted.discoveryId, {
    discoveryId: accepted.discoveryId, sourceId: source.id, status: "QUEUED",
    startedAt: new Date().toISOString(),
  });
  startPolling(source.id, accepted.discoveryId);
}
const discoveryOfSource = (sourceId: string) =>
  [...discoveries.values()].filter((d) => d.sourceId === sourceId).at(-1) ?? null;

// —— 演示开关：制造下一次发现失败（失败分支走查用；目录保持不变）——
async function failNext(source: MockPluginSource) {
  if (!(await confirmDanger({
    title: "演示：下一次发现失败",
    message: `将来源「${source.name}」的下一次发现置为失败演示？失败保留上一次成功目录。`,
    confirmText: "制造失败",
  })))
    return;
  source.failNextDiscovery = true;
  ElMessage.info("已设置：下一次发现将走失败分支（mock 演示）");
  await discover(source);
}

// —— 启用/停用（disable 必填 reason）——
const disableOpen = ref(false);
const disableForm = ref({ versionId: "", plugin: "", reason: "" });
const disableSaving = ref(false);
function openDisable(row: MockPlugin) {
  disableForm.value = { versionId: row.versionId, plugin: `${row.name} ${row.version}`, reason: "" };
  disableOpen.value = true;
}
async function confirmDisable() {
  if (!disableForm.value.reason.trim()) {
    ElMessage.error("停用必须填写原因（reason）");
    return;
  }
  disableSaving.value = true;
  const done = setPluginEnabled(disableForm.value.versionId, false, disableForm.value.reason);
  disableSaving.value = false;
  if ("error" in done) {
    ElMessage.error(done.userMessage ?? "停用失败");
    return;
  }
  ElMessage.success("已停用：拦截后续调用（mock 演示）");
  disableOpen.value = false;
  void reload();
}
async function enable(row: MockPlugin) {
  const done = setPluginEnabled(row.versionId, true);
  if ("error" in done) {
    ElMessage.error(done.userMessage ?? "启用失败");
    return;
  }
  ElMessage.success("已启用（mock 演示）");
  void reload();
}

// —— 授权/撤权 ——
const grantsOpen = ref(false);
const grantsPlugin = ref<MockPlugin | null>(null);
const grantForm = ref({ granteeType: "WORKSPACE" as PluginGrant["granteeType"], granteeCode: "", capabilities: "" });
function openGrants(row: MockPlugin) {
  grantsPlugin.value = row;
  grantForm.value = { granteeType: "WORKSPACE", granteeCode: "", capabilities: "" };
  grantsOpen.value = true;
}
async function addGrant() {
  if (!grantsPlugin.value) return;
  const capabilities = grantForm.value.capabilities.split(/[,，、]/).map((s) => s.trim()).filter(Boolean);
  if (!grantForm.value.granteeCode.trim() || !capabilities.length) {
    ElMessage.error("被授权对象与能力清单必填");
    return;
  }
  const done = addPluginGrant(grantsPlugin.value.versionId, {
    granteeType: grantForm.value.granteeType, granteeCode: grantForm.value.granteeCode, capabilities,
  });
  if ("error" in done) {
    ElMessage.error(done.userMessage ?? "授权失败");
    return;
  }
  grantsPlugin.value = done;
  grantForm.value = { granteeType: grantForm.value.granteeType, granteeCode: "", capabilities: "" };
  ElMessage.success("已授权（撤权下次解析生效，mock 演示）");
  void reload();
}
async function revokeGrant(grantId: string) {
  if (!(await confirmDanger({
    title: "撤销授权",
    message: "撤销该授权？撤权在下次能力解析时生效。",
    confirmText: "撤销授权",
  })))
    return;
  const done = removePluginGrant(grantId);
  if ("error" in done) {
    ElMessage.error("授权不存在，请刷新");
    return;
  }
  if (grantsPlugin.value) {
    const fresh = listPlugins().find((p) => p.versionId === grantsPlugin.value!.versionId);
    grantsPlugin.value = fresh ?? null;
  }
  ElMessage.success("已撤销授权（mock 演示）");
  void reload();
}

// —— 登记来源 ——
const sourceOpen = ref(false);
const sourceForm = ref({ name: "", type: "MARKETPLACE" as MockPluginSource["type"], url: "" });
const sourceSaving = ref(false);
async function saveSource() {
  if (!sourceForm.value.name.trim() || !sourceForm.value.url.trim()) {
    ElMessage.error("名称与地址必填");
    return;
  }
  sourceSaving.value = true;
  const done = createPluginSource(sourceForm.value);
  sourceSaving.value = false;
  if ("error" in done) {
    ElMessage.error(done.userMessage ?? "登记失败");
    return;
  }
  ElMessage.success("来源已登记（mock 演示）");
  sourceOpen.value = false;
  await loadSources();
}

const grantsSummary = (row: MockPlugin) =>
  row.grants.length
    ? row.grants.map((g) => `${g.granteeType}:${g.granteeCode}`).join("、")
    : "未授权";

const filteredCount = computed(() => rows.value.length);
</script>

<template>
  <DataPage :total="null">
    <template #header>
      <PageHeader
        title="插件管理"
        subtitle="来源登记、发现（QUEUED/RUNNING/SUCCEEDED/FAILED）与版本/授权管理；失败保留上一次目录（mock 演示数据）。"
        :busy="busy"
        @refresh="reload"
      >
        <template #actions>
          <el-button type="primary" @click="sourceOpen = true">登记来源</el-button>
        </template>
      </PageHeader>
    </template>
    <template #error>
      <ErrorBanner :error="error" />
    </template>
    <template #filters>
      <div class="pipeline">
        <el-check-tag :checked="false" @change="reload()">插件 {{ filteredCount }}（启用 {{ statusCount("ENABLED") }} / 停用 {{ statusCount("DISABLED") }}）</el-check-tag>
      </div>
    </template>
    <div class="page-scroll">
      <!-- 来源与发现 -->
      <section class="panel" data-test="sources-panel">
        <header class="panel-title">插件来源与发现</header>
        <el-table :data="sources" size="small">
          <el-table-column prop="name" label="来源" min-width="140" />
          <el-table-column prop="type" label="类型" width="130" />
          <el-table-column prop="url" label="地址" min-width="220" show-overflow-tooltip />
          <el-table-column label="最近发现" min-width="180">
            <template #default="{ row }">
              <span v-if="discoveryOfSource(row.id)" :class="['discovery', `is-${discoveryOfSource(row.id)!.status}`]">
                {{ DISCOVERY_LABEL[discoveryOfSource(row.id)!.status] }}（{{ discoveryOfSource(row.id)!.discoveryId }}）
              </span>
              <span v-else-if="row.lastDiscoveryAt" class="muted mono">{{ row.lastDiscoveryAt }}</span>
              <span v-else class="muted">尚未发现</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button text type="primary" :disabled="discoveryOfSource(row.id)?.status === 'QUEUED' || discoveryOfSource(row.id)?.status === 'RUNNING'"
                @click="discover(row)">触发发现</el-button>
              <el-button text type="warning" @click="failNext(row)">失败演示</el-button>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 插件/版本/授权 -->
      <section class="panel" data-test="plugins-panel">
        <header class="panel-title">插件版本与授权</header>
        <el-skeleton v-if="busy && !rows.length" :rows="4" animated />
        <el-table v-else :data="rows" size="small">
          <el-table-column label="来源" width="140">
            <template #default="{ row }">{{ sourceName(row.sourceId) }}</template>
          </el-table-column>
          <el-table-column label="插件" min-width="160">
            <template #default="{ row }">
              <span>{{ row.name }}</span>
              <span class="mono muted"> {{ row.pluginCode }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="version" label="版本" width="90" />
          <el-table-column label="能力" min-width="180">
            <template #default="{ row }">
              <el-tag v-for="cap in row.capabilities" :key="cap" size="small" effect="plain" class="tag-gap">{{ cap }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="授权" min-width="150">
            <template #default="{ row }">
              <span class="muted">{{ grantsSummary(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <span :class="['status', row.status]">{{ row.status === "ENABLED" ? "启用" : "停用" }}</span>
            </template>
          </el-table-column>
          <el-table-column label="停用原因" min-width="140">
            <template #default="{ row }">
              <span v-if="row.disableReason" class="muted">{{ row.disableReason }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" fixed="right" width="190">
            <template #default="{ row }">
              <el-button v-if="row.status === 'ENABLED'" text type="danger" @click="openDisable(row)">停用</el-button>
              <el-button v-else text type="primary" @click="enable(row)">启用</el-button>
              <el-button text @click="openGrants(row)">授权管理</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <EmptyState
              :variant="error ? 'error' : 'create'"
              :description="error ? error : '暂无插件；触发来源发现后目录将展示。'"
              :action-text="error ? '刷新重试' : '登记来源'"
              @action="error ? reload() : (sourceOpen = true)"
            />
          </template>
        </el-table>
      </section>
    </div>

    <!-- 停用：reason 必填 -->
    <el-dialog v-model="disableOpen" title="停用插件" width="min(520px, 96vw)" destroy-on-close>
      <p>停用 <strong>{{ disableForm.plugin }}</strong>？停用后拦截后续调用。</p>
      <el-input v-model="disableForm.reason" placeholder="停用原因（必填）" />
      <footer class="dialog-actions">
        <el-button :disabled="disableSaving" type="danger" @click="confirmDisable">确认停用</el-button>
      </footer>
    </el-dialog>

    <!-- 授权管理 -->
    <el-dialog v-model="grantsOpen" :title="`授权管理：${grantsPlugin?.name ?? ''}`" width="min(680px, 96vw)" destroy-on-close>
      <el-table v-if="grantsPlugin" :data="grantsPlugin.grants" size="small">
        <el-table-column prop="granteeType" label="类型" width="120" />
        <el-table-column prop="granteeCode" label="被授权对象" min-width="140" />
        <el-table-column label="能力" min-width="180">
          <template #default="{ row }">{{ row.capabilities.join("、") }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button text type="danger" @click="revokeGrant(row.grantId)">撤权</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-divider />
      <el-form label-position="top">
        <el-form-item label="被授权对象">
          <el-select v-model="grantForm.granteeType" style="width: 10rem">
            <el-option value="WORKSPACE" label="WORKSPACE" />
            <el-option value="ROLE" label="ROLE" />
            <el-option value="SERVICE" label="SERVICE" />
          </el-select>
          <el-input v-model="grantForm.granteeCode" placeholder="编码，如 recon-assistant" class="grant-input" />
        </el-form-item>
        <el-form-item label="能力清单（逗号/顿号分隔）">
          <el-input v-model="grantForm.capabilities" placeholder="SKILL:detect-a,SKILL:detect-b" />
        </el-form-item>
      </el-form>
      <footer class="dialog-actions">
        <el-button type="primary" @click="addGrant">新增授权</el-button>
      </footer>
    </el-dialog>

    <!-- 登记来源 -->
    <el-dialog v-model="sourceOpen" title="登记插件来源" width="min(560px, 96vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="sourceForm.name" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="sourceForm.type" style="width: 14rem">
            <el-option value="MARKETPLACE" label="MARKETPLACE" />
            <el-option value="GIT" label="GIT" />
            <el-option value="INTERNAL" label="INTERNAL" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="sourceForm.url" />
        </el-form-item>
      </el-form>
      <footer class="dialog-actions">
        <el-button :disabled="sourceSaving" type="primary" @click="saveSource">登记</el-button>
      </footer>
    </el-dialog>
  </DataPage>
</template>

<style scoped>
.page-scroll {
  height: 100%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.panel {
  border: 1px solid color-mix(in srgb, var(--muted) 30%, transparent);
  border-radius: var(--radius);
  background: var(--surface-2);
  padding: var(--sp-3);
}
.panel-title {
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: var(--sp-2);
  font-size: var(--text-meta);
}
.pipeline {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  align-items: center;
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
.discovery.is-QUEUED,
.discovery.is-RUNNING {
  color: var(--st-run-text);
}
.discovery.is-SUCCEEDED {
  color: var(--st-ok-text);
}
.discovery.is-FAILED {
  color: var(--st-fail-text);
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--sp-3);
}
.grant-input {
  width: 16rem;
}
</style>
