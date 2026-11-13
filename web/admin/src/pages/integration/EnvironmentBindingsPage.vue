<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import PageHeader from "../../PageHeader.vue";
import PageError from "../../PageError.vue";
import { usePageOperation, usePageTitle } from "../../usePageOperation";
import { integrationClient } from "../../../../shared/api";
import {
  ENVIRONMENTS,
  actionError,
  nextRequestId,
  parseBindings,
  type EnvironmentBinding,
} from "./integration-console";

// F3-4 集成控制台嵌入模式（前端稿 §5.7）：embedded 时页头由控制台承载（本页无智能体
// 上下文——环境绑定全局共享）；缺省 standalone 行为不变。
const props = defineProps<{ embedded?: boolean }>();
if (!props.embedded) usePageTitle(() => "环境绑定");
const { auth, api, busy, error, trace, operation, success } = usePageOperation();
const client = integrationClient(api);
const rows = ref<EnvironmentBinding[]>([]);
const selected = ref("");
const detail = computed(() => rows.value.find((row) => row.bindingId === selected.value) ?? null);
const draft = reactive({
  bindingId: "",
  environment: "production",
  connectionProfileId: "",
  connectionProfileRev: 1,
  baseEndpoint: "",
  secretRef: "",
  runtimeRef: "{}",
  originPolicy: "[]",
  healthPolicy: "{}",
});

function parseJson(value: string, label: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    throw new Error(`${label} 必须是有效 JSON`);
  }
}

async function load() {
  if (busy.value) return;
  await operation(async () => {
    rows.value = parseBindings(await client.environmentBindings());
  });
}

async function save() {
  if (busy.value) return;
  await operation(async () => {
    try {
      await client.createEnvironmentBinding({
        bindingId: draft.bindingId.trim(),
        environment: draft.environment,
        connectionProfileId: draft.connectionProfileId.trim(),
        connectionProfileRev: Number(draft.connectionProfileRev),
        baseEndpoint: draft.baseEndpoint.trim(),
        secretRef: draft.secretRef.trim(),
        runtimeRef: parseJson(draft.runtimeRef, "运行引用"),
        originPolicy: parseJson(draft.originPolicy, "来源策略"),
        healthPolicy: parseJson(draft.healthPolicy, "健康检查"),
        requestId: nextRequestId(),
      });
    } catch (failure) {
      throw new Error(actionError(failure));
    }
    success("环境绑定草稿已创建；批准后才参与运行解析");
    draft.bindingId = "";
    draft.secretRef = "";
    await load();
  });
}

async function transition(binding: EnvironmentBinding, action: "approve" | "disable") {
  if (busy.value) return;
  await operation(async () => {
    const body = { expectedRev: binding.currentRev, requestId: nextRequestId() };
    try {
      if (action === "approve") await client.approveEnvironmentBinding(binding.bindingId, body);
      else await client.disableEnvironmentBinding(binding.bindingId, body);
    } catch (failure) {
      throw new Error(actionError(failure));
    }
    success(action === "approve" ? "环境绑定已批准" : "环境绑定已停用；激活引用时会复核失败");
    await load();
  });
}

watch(selected, () => { draft.secretRef = ""; });
onMounted(load);
</script>

<template>
  <div class="integration-bindings">
    <PageHeader v-if="!embedded" title="环境绑定" subtitle="把已批准连接档案钉到环境与物理端点；密钥只以引用保存，界面不回显。" :busy="busy" @refresh="load" />
    <PageError :error="error" :trace="trace" />
    <p class="muted">NONE 鉴权须将密钥引用留空；其它鉴权只填引用（如 <code>env:ERP_PROD_TOKEN</code>）。服务端按批准的连接档案校验，不保存凭据明文。</p>
    <div class="grid">
      <section class="panel" aria-label="环境绑定列表">
        <el-skeleton v-if="busy && !rows.length" :rows="4" animated />
        <el-empty v-else-if="!rows.length" description="没有环境绑定。请先创建连接档案并批准。" />
        <article v-for="row in rows" :key="row.bindingId" class="card" :class="{ selected: selected === row.bindingId }">
          <h2>{{ row.bindingId }}</h2>
          <p>
            <el-tag size="small" :type="row.status === 'APPROVED' ? 'success' : row.status === 'DISABLED' ? 'info' : 'warning'">{{ row.status }}</el-tag>
            当前修订 {{ row.currentRev }}
          </p>
          <p class="muted">{{ row.revisions[0]?.environment || "未声明环境" }} · 连接 {{ row.revisions[0]?.connectionProfileId || "—" }}</p>
          <el-button :disabled="busy" @click="selected = row.bindingId">查看修订</el-button>
        </article>
      </section>
      <section v-if="detail" class="panel" aria-label="环境绑定修订">
        <h2>{{ detail.bindingId }}</h2>
        <p class="muted">更新 {{ detail.updatedAt || detail.createdAt }} · 创建者 {{ detail.createdBy || "未知" }}</p>
        <el-table :data="detail.revisions" size="small">
          <el-table-column prop="bindingRev" label="修订" width="70" />
          <el-table-column prop="environment" label="环境" width="110" />
          <el-table-column prop="connectionProfileId" label="连接档案" min-width="150" />
          <el-table-column prop="connectionProfileRev" label="档案修订" width="90" />
          <el-table-column label="密钥" width="100">
            <template #default="{ row }">{{ row.hasSecret ? "已配置引用" : "未配置" }}</template>
          </el-table-column>
        </el-table>
        <div v-if="auth.can('admin')" class="actions">
          <el-button type="primary" :disabled="busy || detail.status === 'APPROVED'" @click="transition(detail, 'approve')">批准当前修订</el-button>
          <el-button :disabled="busy || detail.status === 'DISABLED'" @click="transition(detail, 'disable')">停用</el-button>
        </div>
      </section>
      <aside v-else class="panel">
        <h2>创建绑定草稿</h2>
        <el-form label-position="top">
          <el-form-item label="绑定 ID"><el-input v-model="draft.bindingId" maxlength="64" /></el-form-item>
          <el-form-item label="环境">
            <el-select v-model="draft.environment">
              <el-option v-for="environment in ENVIRONMENTS" :key="environment" :label="environment" :value="environment" />
            </el-select>
          </el-form-item>
          <el-form-item label="连接档案 ID"><el-input v-model="draft.connectionProfileId" maxlength="64" /></el-form-item>
          <el-form-item label="连接档案修订"><el-input-number v-model="draft.connectionProfileRev" :min="1" /></el-form-item>
          <el-form-item label="物理端点"><el-input v-model="draft.baseEndpoint" placeholder="https://erp.example.com" /></el-form-item>
          <el-form-item label="密钥引用"><el-input v-model="draft.secretRef" placeholder="NONE 鉴权留空；其它如 env:ERP_PROD_TOKEN" /></el-form-item>
          <el-form-item label="运行引用 JSON"><el-input v-model="draft.runtimeRef" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="来源策略 JSON"><el-input v-model="draft.originPolicy" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="健康检查 JSON"><el-input v-model="draft.healthPolicy" type="textarea" :rows="2" /></el-form-item>
          <el-button type="primary" :disabled="busy" @click="save">保存草稿</el-button>
        </el-form>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.grid { display: grid; grid-template-columns: minmax(260px, 1fr) minmax(320px, 1.4fr); gap: 16px; }
.panel { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 16px; }
.card { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.card.selected { background: var(--el-fill-color-light); }
.actions { display: flex; gap: 8px; margin-top: 12px; }
.muted { color: var(--el-text-color-secondary); }
</style>
