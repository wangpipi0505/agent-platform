<script setup lang="ts">
// F3-4 模型管理（前端稿 §5.8）：供应商+条目两级跳转合并为 master-detail 一页——
// 左供应商列表（连通状态点：由既有 status/credentialStatus 字段推导，不新增 mock 场景），
// 右该供应商条目表格+连通测试（复用既有 /test 调用）。供应商选择经路由 query（?provider=）
// 持久；旧 /model/providers/:providerId/entries 深链 redirect 映射为 ?provider=<id>。
// 旧 ProvidersPage/ModelEntriesPage 文件保留为回归锚，仅路由与菜单收敛。
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import { usePageOperation, usePageTitle } from "../usePageOperation";
import CreateModelEntryDialog from "./resources/CreateModelEntryDialog.vue";
import CreateProviderDialog from "./resources/CreateProviderDialog.vue";
import ResourceDetailPanel from "./resources/ResourceDetailPanel.vue";
import { displayTime, statusLabel } from "./resources/resource-format";

usePageTitle(() => "模型管理");
const { auth, api, busy, error, trace, operation, success } = usePageOperation();
const route = useRoute();
const router = useRouter();

const providers = ref<any[]>([]);
const entries = ref<any[]>([]);
const detail = ref<any>();
const createProviderOpen = ref(false);
const providerForm = ref<any>({});
const createEntryOpen = ref(false);
const entryForm = ref<any>({});
const demoMode = typeof import.meta.env.VITE_MOCK === "string" && import.meta.env.VITE_MOCK !== "false";

/** master-detail 选中态：query ?provider= 是唯一事实源（持久+可深链）。 */
const selectedId = computed(() => (typeof route.query.provider === "string" ? route.query.provider : ""));
const selectedProvider = computed(
  () => providers.value.find((provider) => String(provider.id) === selectedId.value) ?? null,
);
const providerEntries = computed(() =>
  entries.value.filter((row) => String(row.providerId) === String(selectedProvider.value?.id)),
);
function entriesOf(provider: any) {
  return entries.value.filter((row) => String(row.providerId) === String(provider.id)).length;
}
function chooseProvider(id: string | number) {
  void router.replace({ query: { ...route.query, provider: String(id) } });
}

/** 连通状态点：只用既有字段推导——ACTIVE+凭据引用已配置=绿，ACTIVE 缺引用=黄，其余=灰。 */
type Connectivity = "ok" | "warn" | "off";
function connectivityOf(provider: any): Connectivity {
  if (provider.status !== "ACTIVE") return "off";
  return provider.credentialStatus === "SET" ? "ok" : "warn";
}
const connectivityText: Record<Connectivity, string> = {
  ok: "启用中，凭据引用已配置",
  warn: "启用中，凭据引用未配置（连通测试可能失败）",
  off: "非启用状态",
};

async function load() {
  if (!auth.token) return;
  await operation(async () => {
    const [providerBody, entryBody] = await Promise.all([
      api<any>("/admin/model-providers"),
      api<any>("/admin/model-entries"),
    ]);
    providers.value = Array.isArray(providerBody) ? providerBody : Array.isArray(providerBody?.items) ? providerBody.items : [];
    entries.value = Array.isArray(entryBody) ? entryBody : Array.isArray(entryBody?.items) ? entryBody.items : [];
    // 深链/初始无有效选择时回落到第一个供应商
    if ((!selectedId.value || !providers.value.some((provider) => String(provider.id) === selectedId.value))
      && providers.value.length) {
      chooseProvider(providers.value[0].id);
    }
  });
}

/** 连通测试：复用既有供应商测试调用；结果与旧页一致经 ResourceDetailPanel 呈现。 */
async function testProvider(row: any) {
  await operation(async () => {
    detail.value = await api(`/admin/model-providers/${row.id}/test`, "POST");
    success("连接测试已返回");
  });
}
/** 条目自检：复用既有条目自检调用与判定口径。 */
async function testEntry(row: any) {
  await operation(async () => {
    const result = await api<any>(`/admin/model-entries/${row.id}/test`, "POST");
    if (!result?.ok)
      throw new Error(
        [result?.stage, result?.reason].filter(Boolean).join("：") || "模型条目自检未通过",
      );
    const checks = (result.checks || []) as any[];
    const passed = checks.filter((check) => check.status === "PASS").length;
    success(`自检通过：${result.model || "模型"}，${result.latencyMs}ms，${passed}/${checks.length} 项检查通过`);
  });
}

function createProvider() {
  providerForm.value = {
    code: "",
    name: "",
    protocol: "OPENAI_COMPATIBLE",
    baseUrl: "",
    secretRef: "",
    trustLabels: [],
  };
  createProviderOpen.value = true;
}
async function saveProvider() {
  await operation(async () => {
    if (!providerForm.value.code?.trim()) throw new Error("请填写编码");
    await api("/admin/model-providers", "POST", providerForm.value);
    createProviderOpen.value = false;
    success("已保存");
    await load();
  });
}

async function createEntry() {
  await operation(async () => {
    if (!providers.value.length) providers.value = await api<any[]>("/admin/model-providers");
    entryForm.value = {
      providerId: selectedProvider.value
        ? (demoMode ? selectedProvider.value.id : Number(selectedProvider.value.id))
        : null,
      modelName: "",
      displayName: "",
      contextWindow: 8192,
      maxOutputTokens: 2048,
      capabilities: ["chat"],
      tokenizerUrl: "",
    };
    createEntryOpen.value = true;
  });
}
async function saveEntry() {
  await operation(async () => {
    if (demoMode && !providers.value.some((provider) => String(provider.id) === String(entryForm.value.providerId)))
      throw new Error("请选择已登记的供应商");
    for (const key of demoMode ? ["contextWindow", "maxOutputTokens"] : ["providerId", "contextWindow", "maxOutputTokens"]) {
      entryForm.value[key] = Number(entryForm.value[key]);
      if (!Number.isFinite(entryForm.value[key]) || entryForm.value[key] <= 0)
        throw new Error(`${key} 必须是正数`);
    }
    if (!entryForm.value.modelName?.trim()) throw new Error("请填写模型调用名称");
    await api("/admin/model-entries", "POST", entryForm.value);
    createEntryOpen.value = false;
    success("已保存");
    await load();
  });
}

// 选中态失效时由 selectedProvider 计算属性自然回落为 null，模板空态兜底，无需额外清理。
onMounted(load);
</script>

<template>
  <PageHeader
    title="模型管理"
    subtitle="供应商与模型条目合并管理：左侧选供应商，右侧维护条目并测试连通，不再两级跳转。"
    :busy="busy"
    @refresh="load"
  >
    <!-- UI-03：主操作经PageHeader actions插槽迁入AppShell顶栏挂载点。 -->
    <template #actions>
      <el-button type="primary" v-permission="'admin'" @click="createEntry()">添加模型条目</el-button>
      <el-button v-permission="'admin'" @click="createProvider()">添加供应商</el-button>
    </template>
  </PageHeader>
  <PageError :error="error" :trace="trace" />

  <div class="master-detail">
    <aside class="master panel" aria-label="供应商列表">
      <div class="row"><span class="muted">供应商 {{ providers.length }}</span></div>
      <el-skeleton v-if="busy && !providers.length" :rows="4" animated />
      <el-empty
        v-else-if="!providers.length"
        :description="error ? '服务暂不可用，请刷新重试' : '暂无供应商；添加后配置模型条目。'"
      />
      <button
        v-for="provider in providers"
        :key="provider.id"
        type="button"
        class="provider-item"
        :class="{ selected: String(provider.id) === selectedId }"
        :aria-current="String(provider.id) === selectedId ? 'true' : undefined"
        @click="chooseProvider(provider.id)"
      >
        <span
          class="dot"
          :class="connectivityOf(provider)"
          :title="connectivityText[connectivityOf(provider)]"
          aria-hidden="true"
        ></span>
        <span class="provider-main">
          <strong>{{ provider.name }}</strong>
          <span class="muted">
            {{ provider.code }}
            <!-- AP-S06：fixture 演示数据带可区分标识，不与“可用”状态混同。 -->
            <el-tag v-if="provider.source === 'FIXTURE'" size="small" type="warning" effect="plain">内置</el-tag>
          </span>
          <span class="muted">{{ entriesOf(provider) }} 条目 · {{ provider.protocol }}</span>
        </span>
      </button>
    </aside>

    <section class="detail panel" aria-label="供应商条目">
      <template v-if="selectedProvider">
        <div class="detail-head">
          <div>
            <h2>
              <span
                class="dot"
                :class="connectivityOf(selectedProvider)"
                :title="connectivityText[connectivityOf(selectedProvider)]"
              ></span>
              {{ selectedProvider.name }}
              <span class="muted">{{ selectedProvider.code }} · {{ selectedProvider.protocol }}</span>
            </h2>
            <p class="muted">{{ selectedProvider.baseUrl }} · 条目 {{ providerEntries.length }}</p>
          </div>
          <div class="actions">
            <el-button :disabled="busy" v-permission="'admin'" @click="testProvider(selectedProvider)">连通测试</el-button>
            <el-button :disabled="busy" @click="detail = selectedProvider">供应商详情</el-button>
          </div>
        </div>
        <el-skeleton v-if="busy && !entries.length" :rows="4" animated />
        <el-empty
          v-else-if="!providerEntries.length"
          description="该供应商暂无模型条目；从顶栏「添加模型条目」配置。"
        />
        <el-table v-else :data="providerEntries" stripe>
          <el-table-column label="名称 / 模型" min-width="220">
            <template #default="{ row }">
              <strong>{{ row.displayName || row.modelName }}</strong>
              <div class="muted">{{ row.modelName }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <span :class="['status', row.status]">{{ row.statusLabel || statusLabel(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="能力" min-width="160">
            <template #default="{ row }">{{ Array.isArray(row.capabilities) ? row.capabilities.join(", ") : "—" }}</template>
          </el-table-column>
          <el-table-column label="上下文 / 输出" width="150">
            <template #default="{ row }">{{ row.contextWindow }} / {{ row.maxOutputTokens }}</template>
          </el-table-column>
          <el-table-column label="更新于" width="150">
            <template #default="{ row }">{{ displayTime(row.updatedAt || row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" fixed="right" width="150">
            <template #default="{ row }">
              <el-button text v-permission="'admin'" @click="testEntry(row)">自检</el-button>
              <el-button text @click="detail = row">查看详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <el-empty v-else-if="providers.length" description="请选择左侧供应商查看条目。" />
    </section>
  </div>

  <ResourceDetailPanel :detail="detail" />
  <CreateProviderDialog v-model="createProviderOpen" :form="providerForm" :busy="busy" @save="saveProvider" />
  <CreateModelEntryDialog v-model="createEntryOpen" :form="entryForm" :providers="providers" :busy="busy" @save="saveEntry" />
</template>

<style scoped>
.master-detail {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: var(--sp-4);
  align-items: start;
}
.panel { border: 1px solid var(--line); border-radius: 8px; padding: var(--sp-4); }
.master { display: flex; flex-direction: column; gap: var(--sp-2); }
.provider-item {
  display: flex;
  gap: var(--sp-3);
  align-items: flex-start;
  padding: var(--sp-2) var(--sp-3);
  text-align: left;
  font: inherit;
  color: var(--ink);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: 8px;
  cursor: pointer;
}
.provider-item:hover { border-color: var(--brand-primary); }
.provider-item.selected {
  background: var(--nav-active-bg);
  border-color: var(--brand-primary);
  color: var(--nav-text-active);
}
.provider-main { display: flex; flex-direction: column; gap: var(--sp-1); font-size: var(--text-body); }
.dot {
  flex: none;
  width: 0.5rem;
  height: 0.5rem;
  margin-top: 0.375rem;
  border-radius: 50%;
  background: var(--st-wait);
}
.dot.ok { background: var(--st-ok); }
.dot.warn { background: var(--st-suspend); }
.dot.off { background: var(--st-wait); }
.detail-head { display: flex; justify-content: space-between; align-items: flex-start; gap: var(--sp-3); margin-bottom: var(--sp-3); }
.detail-head h2 { display: flex; align-items: center; gap: var(--sp-2); font-size: var(--text-module); margin: 0 0 var(--sp-1); }
.detail-head .dot { margin-top: 0; }
.actions { display: flex; gap: var(--sp-2); flex-wrap: wrap; }
.muted { color: var(--muted); }
@media (max-width: 900px) {
  .master-detail { grid-template-columns: minmax(0, 1fr); }
}
</style>
