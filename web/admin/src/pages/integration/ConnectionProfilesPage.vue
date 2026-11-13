<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import PageHeader from "../../PageHeader.vue";
import PageError from "../../PageError.vue";
import { usePageOperation, usePageTitle } from "../../usePageOperation";
import { integrationClient } from "../../../../shared/api";
import {
  actionError,
  nextRequestId,
  parseConnections,
  type ConnectionProfile,
} from "./integration-console";

// F3-4 集成控制台嵌入模式（前端稿 §5.7）：embedded 时页头由控制台承载（本页无智能体
// 上下文——连接档案全局共享）；缺省 standalone 行为不变。
const props = defineProps<{ embedded?: boolean }>();
if (!props.embedded) usePageTitle(() => "连接档案");
const { auth, api, busy, error, trace, operation, success } = usePageOperation();
const client = integrationClient(api);
const rows = ref<ConnectionProfile[]>([]);
const selected = ref("");
const detail = computed(() => rows.value.find((row) => row.profileId === selected.value) ?? null);
const draft = reactive({
  profileId: "",
  protocol: "",
  operationAllowlist: "[]",
  endpointPolicy: "{}",
  authScheme: "{}",
  identityPolicy: "{}",
  inputContractRefs: "[]",
  outputContractRefs: "[]",
  allowedUses: "[]",
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
    rows.value = parseConnections(await client.connectionProfiles());
  });
}

async function save() {
  if (busy.value) return;
  await operation(async () => {
    const body = {
      profileId: draft.profileId.trim(),
      protocol: draft.protocol.trim(),
      operationAllowlist: parseJson(draft.operationAllowlist, "操作白名单"),
      endpointPolicy: parseJson(draft.endpointPolicy, "端点策略"),
      authScheme: parseJson(draft.authScheme, "认证方式"),
      identityPolicy: parseJson(draft.identityPolicy, "身份策略"),
      inputContractRefs: parseJson(draft.inputContractRefs, "输入契约引用"),
      outputContractRefs: parseJson(draft.outputContractRefs, "输出契约引用"),
      allowedUses: parseJson(draft.allowedUses, "允许用途"),
      requestId: nextRequestId(),
    };
    try {
      await client.createConnectionProfile(body);
    } catch (failure) {
      throw new Error(actionError(failure));
    }
    success("连接档案草稿已创建；批准后才可用于环境绑定");
    draft.profileId = "";
    await load();
  });
}

async function transition(profile: ConnectionProfile, action: "approve" | "disable") {
  if (busy.value) return;
  await operation(async () => {
    const body = { expectedRev: profile.currentRev, requestId: nextRequestId() };
    try {
      if (action === "approve") await client.approveConnectionProfile(profile.profileId, body);
      else await client.disableConnectionProfile(profile.profileId, body);
    } catch (failure) {
      throw new Error(actionError(failure));
    }
    success(action === "approve" ? "连接档案已批准" : "连接档案已停用；引用它的配置发布激活时会被拒绝");
    await load();
  });
}

onMounted(load);
</script>

<template>
  <div class="integration-connections">
    <PageHeader v-if="!embedded" title="连接档案" subtitle="连接能力只登记协议、端点策略与认证方式引用；明文凭据不属于配置台。" :busy="busy" @refresh="load" />
    <PageError :error="error" :trace="trace" />
    <p class="muted">修订不可变：修改生成新修订，旧修订永久保留；批准后才能被环境绑定与配置发布引用。</p>
    <div class="grid">
      <section class="panel" aria-label="连接档案列表">
        <el-skeleton v-if="busy && !rows.length" :rows="4" animated />
        <el-empty v-else-if="!rows.length" description="没有连接档案。管理员可先创建，再由审批人批准。" />
        <article v-for="row in rows" :key="row.profileId" class="card" :class="{ selected: selected === row.profileId }">
          <h2>{{ row.profileId }}</h2>
          <p>
            <el-tag size="small" :type="row.status === 'APPROVED' ? 'success' : row.status === 'DISABLED' ? 'info' : 'warning'">{{ row.status }}</el-tag>
            当前修订 {{ row.currentRev }}
          </p>
          <el-button :disabled="busy" @click="selected = row.profileId">查看修订</el-button>
        </article>
      </section>
      <section v-if="detail" class="panel" aria-label="连接档案修订">
        <h2>{{ detail.profileId }}</h2>
        <p class="muted">最近更新 {{ detail.updatedAt || detail.createdAt }} · 创建者 {{ detail.createdBy || "未知" }}</p>
        <el-table :data="detail.revisions" size="small">
          <el-table-column prop="profileRev" label="修订" width="70" />
          <el-table-column prop="protocol" label="协议" width="120" />
          <el-table-column label="认证方式" min-width="180">
            <template #default="{ row }">{{ JSON.stringify(row.authScheme) }}</template>
          </el-table-column>
          <el-table-column label="摘要" width="140">
            <template #default="{ row }"><code>{{ String(row.declarationSha256).slice(0, 12) }}…</code></template>
          </el-table-column>
        </el-table>
        <div v-if="auth.can('admin')" class="actions">
          <el-button type="primary" :disabled="busy || detail.status === 'APPROVED'" @click="transition(detail, 'approve')">批准当前修订</el-button>
          <el-button :disabled="busy || detail.status === 'DISABLED'" @click="transition(detail, 'disable')">停用</el-button>
        </div>
      </section>
      <aside v-else class="panel">
        <h2>创建草稿</h2>
        <p class="muted">端点策略、认证方式与允许用途使用 JSON 表达，服务端会拒绝明文密钥字段。</p>
        <el-form label-position="top">
          <el-form-item label="档案 ID"><el-input v-model="draft.profileId" maxlength="64" /></el-form-item>
          <el-form-item label="协议"><el-input v-model="draft.protocol" maxlength="32" placeholder="HTTP_JSON" /></el-form-item>
          <el-form-item label="端点策略 JSON"><el-input v-model="draft.endpointPolicy" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="认证方式 JSON"><el-input v-model="draft.authScheme" type="textarea" :rows="3" placeholder='{"kind":"API_KEY","headerName":"X-Api-Key"}' /></el-form-item>
          <el-form-item label="身份策略 JSON"><el-input v-model="draft.identityPolicy" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="操作白名单 JSON"><el-input v-model="draft.operationAllowlist" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="允许用途 JSON"><el-input v-model="draft.allowedUses" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="输入契约引用 JSON"><el-input v-model="draft.inputContractRefs" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="输出契约引用 JSON"><el-input v-model="draft.outputContractRefs" type="textarea" :rows="2" /></el-form-item>
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
