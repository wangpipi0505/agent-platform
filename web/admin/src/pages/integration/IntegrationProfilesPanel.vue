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
  parseApplications,
  parseExperiences,
  parseProfiles,
  type ApplicationOption,
  type IntegrationProfileView,
} from "./integration-console";

// F3-4 集成控制台嵌入模式（前端稿 §5.7）：embedded 时 appCode 取控制台页头的共享上下文
// （路由 query 持久），页头与智能体选择器由控制台承载；缺省 standalone 行为不变。
const props = defineProps<{ embedded?: boolean; appCode?: string }>();
if (!props.embedded) usePageTitle(() => "接入档案");
const { auth, api, busy, error, trace, operation, success } = usePageOperation();
const client = integrationClient(api);
const applications = ref<ApplicationOption[]>([]);
const localAppCode = ref("");
const appCode = computed({
  get: () => (props.embedded ? props.appCode || "" : localAppCode.value),
  set: (value) => { localAppCode.value = value; },
});
const rows = ref<IntegrationProfileView[]>([]);
const experienceRev = ref(0);
const selected = ref("");
const detail = computed(() => rows.value.find((row) => row.profileId === selected.value) ?? null);
const draft = reactive({
  profileId: "",
  bizSystemCode: "",
  environment: "production",
  mode: "L2",
  allowedOrigins: "",
  contextSchema: "{}",
  credentialPolicyRef: "",
  experienceRev: 1,
});

function parseJson(value: string, label: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    throw new Error(`${label} 必须是有效 JSON`);
  }
}

async function load() {
  if (busy.value || !appCode.value) return;
  await operation(async () => {
    if (!props.embedded && !applications.value.length) applications.value = parseApplications(await client.applications());
    rows.value = parseProfiles(await client.integrationProfiles(appCode.value));
    try {
      experienceRev.value = parseExperiences(await client.experience(appCode.value), appCode.value).experienceRev;
    } catch {
      experienceRev.value = 0;
    }
  });
}

async function save(revise: boolean) {
  if (busy.value) return;
  await operation(async () => {
    if (!appCode.value) throw new Error("请先选择智能体");
    const origins = draft.allowedOrigins.split(/[\s,]+/).map((item) => item.trim()).filter(Boolean);
    const body = {
      profileId: draft.profileId.trim(),
      appCode: appCode.value,
      bizSystemCode: draft.bizSystemCode.trim(),
      environment: draft.environment,
      mode: draft.mode,
      allowedOrigins: origins,
      contextSchema: parseJson(draft.contextSchema, "上下文声明"),
      credentialPolicyRef: draft.credentialPolicyRef.trim(),
      experienceRef: { appCode: appCode.value, experienceRev: Number(draft.experienceRev) },
      requestId: nextRequestId(),
    };
    try {
      if (revise) {
        const profile = detail.value;
        if (!profile) throw new Error("请先选择要修订的接入档案");
        await client.reviseIntegrationProfile(profile.profileId, { ...body, profileId: undefined, appCode: undefined, expectedRev: profile.profileRev });
      } else {
        await client.createIntegrationProfile(body);
      }
    } catch (failure) {
      throw new Error(actionError(failure));
    }
    success(revise ? "已生成新修订草稿；批准后才可被配置发布引用" : "接入档案草稿已创建");
    await load();
  });
}

async function transition(profile: IntegrationProfileView, action: "approve" | "disable") {
  if (busy.value) return;
  await operation(async () => {
    const body = { expectedRev: profile.profileRev, requestId: nextRequestId() };
    try {
      if (action === "approve") await client.approveIntegrationProfile(profile.profileId, body);
      else await client.disableIntegrationProfile(profile.profileId, body);
    } catch (failure) {
      throw new Error(actionError(failure));
    }
    success(action === "approve" ? "接入档案已批准" : "接入档案已停用；引用它的配置发布激活会被拒绝");
    await load();
  });
}

function edit(profile: IntegrationProfileView) {
  const revision = profile.revisions.at(-1);
  if (!revision) return;
  draft.profileId = profile.profileId;
  draft.bizSystemCode = revision.bizSystemCode;
  draft.environment = revision.environment;
  draft.mode = revision.mode;
  draft.allowedOrigins = revision.allowedOrigins.join("\n");
  draft.experienceRev = revision.experienceRef?.experienceRev ?? 1;
}

watch(appCode, () => { selected.value = ""; void load(); });
onMounted(async () => {
  if (props.embedded) { await load(); return; }
  await operation(async () => {
    applications.value = parseApplications(await client.applications());
    if (!appCode.value && applications.value.length) appCode.value = applications.value[0].code;
  });
  await load();
});
</script>

<template>
  <div class="integration-profiles">
    <PageHeader v-if="!embedded" title="接入档案" subtitle="登记业务系统、环境、接入等级、来源白名单与体验引用；不承载连接与凭据。" :busy="busy" @refresh="load" />
    <PageError :error="error" :trace="trace" />
    <div class="toolbar">
      <el-select v-if="!embedded" v-model="appCode" placeholder="选择智能体" :disabled="busy" aria-label="选择智能体">
        <el-option v-for="application in applications" :key="application.code" :label="`${application.name}（${application.code}）`" :value="application.code" />
      </el-select>
      <span class="muted">体验当前修订：{{ experienceRev || "无" }}</span>
    </div>
    <div class="grid">
      <section class="panel" aria-label="接入档案列表">
        <el-empty v-if="!rows.length" :description="appCode ? '该智能体还没有接入档案' : '请选择智能体'" />
        <article v-for="row in rows" :key="row.profileId" class="card" :class="{ selected: selected === row.profileId }">
          <h2>{{ row.profileId }}</h2>
          <p>
            <el-tag size="small" :type="row.status === 'APPROVED' ? 'success' : row.status === 'DISABLED' ? 'info' : 'warning'">{{ row.status }}</el-tag>
            修订 {{ row.profileRev }} · {{ row.revisions.at(-1)?.mode || "—" }} · {{ row.revisions.at(-1)?.environment || "—" }}
          </p>
          <el-button :disabled="busy" @click="selected = row.profileId">查看</el-button>
          <el-button v-if="auth.can('edit')" :disabled="busy" @click="edit(row)">复制为草稿</el-button>
        </article>
      </section>
      <section v-if="detail" class="panel" aria-label="接入档案详情">
        <h2>{{ detail.profileId }}</h2>
        <p class="muted">创建者 {{ detail.createdBy || "未知" }} · 更新 {{ detail.updatedAt || detail.createdAt }}</p>
        <el-table :data="detail.revisions" size="small">
          <el-table-column prop="profileRev" label="修订" width="70" />
          <el-table-column prop="bizSystemCode" label="业务系统" min-width="130" />
          <el-table-column prop="environment" label="环境" width="110" />
          <el-table-column prop="mode" label="等级" width="70" />
          <el-table-column label="来源白名单" min-width="200">
            <template #default="{ row }">{{ row.allowedOrigins.join("、") || "未声明" }}</template>
          </el-table-column>
        </el-table>
        <div v-if="auth.can('admin')" class="actions">
          <el-button type="primary" :disabled="busy || detail.status === 'APPROVED'" @click="transition(detail, 'approve')">批准当前修订</el-button>
          <el-button :disabled="busy || detail.status === 'DISABLED'" @click="transition(detail, 'disable')">停用</el-button>
        </div>
      </section>
      <aside class="panel">
        <h2>{{ detail ? "修订草稿" : "新建草稿" }}</h2>
        <el-form label-position="top">
          <el-form-item label="档案 ID"><el-input v-model="draft.profileId" maxlength="64" :disabled="!!detail" /></el-form-item>
          <el-form-item label="业务系统编码"><el-input v-model="draft.bizSystemCode" maxlength="64" /></el-form-item>
          <el-form-item label="环境">
            <el-select v-model="draft.environment">
              <el-option v-for="environment in ENVIRONMENTS" :key="environment" :label="environment" :value="environment" />
            </el-select>
          </el-form-item>
          <el-form-item label="接入等级">
            <el-select v-model="draft.mode">
              <el-option v-for="mode in ['L0', 'L1', 'L2', 'L3']" :key="mode" :label="mode" :value="mode" />
            </el-select>
          </el-form-item>
          <el-form-item label="来源白名单（每行一个精确 origin）"><el-input v-model="draft.allowedOrigins" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="上下文声明 JSON"><el-input v-model="draft.contextSchema" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="凭据策略引用"><el-input v-model="draft.credentialPolicyRef" maxlength="512" placeholder="policy:partner-default" /></el-form-item>
          <el-form-item label="体验修订"><el-input-number v-model="draft.experienceRev" :min="1" /></el-form-item>
          <el-button type="primary" :disabled="busy" @click="save(!!detail)">{{ detail ? "保存为新修订" : "保存草稿" }}</el-button>
        </el-form>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; }
.grid { display: grid; grid-template-columns: minmax(260px, 1fr) minmax(320px, 1.3fr) minmax(280px, 1fr); gap: 16px; }
.panel { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 16px; }
.card { padding: 12px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.card.selected { background: var(--el-fill-color-light); }
.actions { display: flex; gap: 8px; margin-top: 12px; }
.muted { color: var(--el-text-color-secondary); }
</style>
