<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import PageHeader from "../../PageHeader.vue";
import PageError from "../../PageError.vue";
import { usePageOperation, usePageTitle } from "../../usePageOperation";
import { integrationClient } from "../../../../shared/api";
import {
  ENVIRONMENTS,
  actionError,
  activeRelease,
  activationBlockers,
  copyAsDraft,
  diffRelease,
  nextRequestId,
  parseApplications,
  parseConnections,
  parseBindings,
  parseExperiences,
  parseProfiles,
  parseReleases,
  redactedExport,
  releaseImpact,
  validateDraft,
  type ApplicationOption,
  type ConnectionProfile,
  type ConnectionRef,
  type EnvironmentBinding,
  type ExperienceView,
  type IntegrationProfileView,
  type ReleaseDraft,
  type ReleaseView,
} from "./integration-console";

usePageTitle(() => "配置发布");
const { auth, api, busy, error, trace, operation, success } = usePageOperation();
const client = integrationClient(api);
const applications = ref<ApplicationOption[]>([]);
const appCode = ref("");
const environment = ref("production");
const releases = ref<ReleaseView[]>([]);
const experiences = ref<ExperienceView | null>(null);
const profiles = ref<IntegrationProfileView[]>([]);
const connections = ref<ConnectionProfile[]>([]);
const bindings = ref<EnvironmentBinding[]>([]);
const selectedId = ref("");
const draft = ref<ReleaseDraft | null>(null);
const draftConnectionKeys = ref<string[]>([]);
const exportText = ref("");
const active = computed(() => activeRelease(releases.value, environment.value));
const selected = computed(() => releases.value.find((release) => release.releaseId === selectedId.value) ?? null);
const differences = computed(() => (draft.value ? diffRelease(active.value, draft.value) : []));
const draftProblems = computed(() => (draft.value ? validateDraft(draft.value) : []));
const blockers = computed(() =>
  selected.value
    ? activationBlockers({
        release: selected.value,
        environment: environment.value,
        experience: experiences.value ?? undefined,
        profile: profiles.value.find((profile) => profile.profileId === selected.value?.integrationProfileId),
        connections: connections.value,
      })
    : [],
);
const approvedProfiles = computed(() => profiles.value.filter((profile) => profile.status === "APPROVED"
  && profile.revisions.some((revision) => revision.profileRev === profile.profileRev && revision.environment === environment.value)));

async function readConfiguration(code: string) {
  const [releaseRows, profileRows, connectionRows, bindingRows] = await Promise.all([
    client.configReleases(code), client.integrationProfiles(code), client.connectionProfiles(), client.environmentBindings(),
  ]);
  if (code !== appCode.value) return;
  releases.value = parseReleases(releaseRows);
  profiles.value = parseProfiles(profileRows);
  connections.value = parseConnections(connectionRows);
  bindings.value = parseBindings(bindingRows);
  try {
    const view = parseExperiences(await client.experience(code), code);
    if (code !== appCode.value) return;
    experiences.value = view;
  } catch (failure) {
    const e = failure as { status?: number; code?: string };
    if (e.status !== 404 || e.code !== "CONFIG_REF_NOT_FOUND") throw failure;
    experiences.value = null;
  }
  if (!selectedId.value) selectedId.value = active.value?.releaseId ?? releases.value[0]?.releaseId ?? "";
}
async function load() {
  if (busy.value || !appCode.value) return;
  await operation(() => readConfiguration(appCode.value));
}

function startNewDraft() {
  if (!appCode.value || busy.value) return;
  const experience = experiences.value;
  const profile = approvedProfiles.value[0];
  if (experience?.status !== "APPROVED" || !profile) {
    error.value = "请先批准该智能体的体验和当前环境的接入档案，再新建配置发布。";
    return;
  }
  draft.value = { appCode: appCode.value,
    appVersion: applications.value.find((a) => a.code === appCode.value)?.currentVersionNo ?? 1,
    experienceRev: experience.experienceRev, integrationProfileId: profile.profileId,
    integrationProfileRev: profile.profileRev, connectionProfileRefs: [], activationScope: environment.value };
  draftConnectionKeys.value = []; exportText.value = "";
}

function startDraft() {
  if (!active.value) {
    draft.value = null;
    success("当前环境没有激活版本，无法复制；请从已批准发布中选择或新建");
    return;
  }
  const copied = copyAsDraft(active.value, appCode.value, environment.value);
  draft.value = copied;
  draftConnectionKeys.value = copied.connectionProfileRefs.map((ref) => `${ref.profileId}#${ref.profileRev}`);
  exportText.value = "";
}

function setConnections(keys: string[]) {
  draftConnectionKeys.value = keys;
  if (!draft.value) return;
  draft.value.connectionProfileRefs = keys.map((item) => {
    const [profileId, revision] = item.split("#");
    return draft.value!.connectionProfileRefs.find((ref) => ref.profileId === profileId && ref.profileRev === Number(revision))
      ?? { profileId, profileRev: Number(revision) };
  });
}

function availableBindings(ref: ConnectionRef) {
  return bindings.value.filter((binding) => binding.status === "APPROVED" && binding.revisions.some((revision) =>
    revision.bindingRev === binding.currentRev && revision.connectionProfileId === ref.profileId
    && revision.connectionProfileRev === ref.profileRev && revision.environment === environment.value));
}
function chooseBinding(ref: ConnectionRef, id: string) {
  const binding = availableBindings(ref).find((b) => b.bindingId === id);
  if (!binding) return;
  ref.bindingId = id; ref.bindingRev = binding.currentRev; ref.environment = environment.value;
}
function operations(ref: ConnectionRef) {
  return connections.value.find((connection) => connection.profileId === ref.profileId)?.revisions
    .find((revision) => revision.profileRev === ref.profileRev)?.operationAllowlist ?? [];
}

function pickProfile(profileId: string) {
  if (!draft.value) return;
  const profile = profiles.value.find((item) => item.profileId === profileId);
  draft.value.integrationProfileId = profileId;
  if (profile) draft.value.integrationProfileRev = profile.profileRev;
}

async function createDraft() {
  if (busy.value || !draft.value) return;
  await operation(async () => {
    const problems = validateDraft(draft.value!);
    if (problems.length) throw new Error(problems.join("；"));
    const releaseId = `rel_${nextRequestId().replace(/[^a-zA-Z0-9_]/g, "").slice(0, 24)}`;
    try {
      await client.createConfigRelease({ ...draft.value, releaseId, requestId: nextRequestId() });
    } catch (failure) {
      throw new Error(actionError(failure));
    }
    success("配置发布草稿已创建；需重新校验并批准后才能激活");
    draft.value = null;
    await readConfiguration(appCode.value);
    selectedId.value = releaseId;
  });
}

async function step(action: "validate" | "approve" | "activate" | "disable") {
  if (busy.value || !selected.value) return;
  await operation(async () => {
    const body = { requestId: nextRequestId() };
    try {
      if (action === "validate") await client.validateConfigRelease(selected.value!.releaseId, body);
      else if (action === "approve") await client.approveConfigRelease(selected.value!.releaseId, body);
      else if (action === "activate") await client.activateConfigRelease(selected.value!.releaseId, body);
      else await client.disableConfigRelease(selected.value!.releaseId, body);
    } catch (failure) {
      throw new Error(actionError(failure));
    }
    success(action === "activate" ? "配置已激活；仅影响此后的新会话与新运行" : `已执行 ${action}`);
    await readConfiguration(appCode.value);
  });
}

function showExport() {
  if (!selected.value) return;
  exportText.value = JSON.stringify(redactedExport(selected.value, environment.value), null, 2);
}

watch([appCode, environment], () => { draft.value = null; selectedId.value = ""; exportText.value = ""; void load(); });
onMounted(async () => {
  await operation(async () => {
    applications.value = parseApplications(await client.applications());
    if (!appCode.value && applications.value.length) appCode.value = applications.value[0].code;
  });
  await load();
});
</script>

<template>
  <div class="config-releases">
    <PageHeader title="配置发布" subtitle="从激活版本复制草稿，校验与批准后再以 CAS 激活；停用与回退先说明影响。" :busy="busy" @refresh="load" />
    <PageError :error="error" :trace="trace" />
    <div class="toolbar">
      <el-select v-model="appCode" placeholder="选择智能体" :disabled="busy" aria-label="选择智能体">
        <el-option v-for="application in applications" :key="application.code" :label="`${application.name}（${application.code}）`" :value="application.code" />
      </el-select>
      <el-select v-model="environment" :disabled="busy" aria-label="选择环境">
        <el-option v-for="item in ENVIRONMENTS" :key="item" :label="item" :value="item" />
      </el-select>
      <el-button :disabled="busy || !active" @click="startDraft">复制激活版本为草稿</el-button>
      <el-button type="primary" :disabled="busy || !appCode || !auth.can('admin')" @click="startNewDraft">新建配置发布</el-button>
    </div>

    <section class="panel active" aria-label="当前激活版本">
      <h2>当前激活（{{ environment }}）</h2>
      <el-empty v-if="!active" description="该环境没有激活的配置版本；新会话将回退为无配置引用。" />
      <template v-else>
        <p><strong>{{ active.releaseId }}</strong> · 智能体版本 {{ active.appVersion }} · 体验修订 {{ active.experienceRev }}</p>
        <p class="muted">接入档案 {{ active.integrationProfileId }}@{{ active.integrationProfileRev }} · 摘要 {{ active.digest.slice(0, 16) }}… · 批准者 {{ active.createdBy || "未记录" }}</p>
      </template>
    </section>

    <div class="grid">
      <section class="panel" aria-label="发布列表">
        <h2>发布记录</h2>
        <el-empty v-if="!releases.length" description="暂无发布记录" />
        <el-table v-else :data="releases" size="small" highlight-current-row @row-click="(row: ReleaseView) => (selectedId = row.releaseId)">
          <el-table-column prop="releaseId" label="发布" min-width="150" />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column prop="appVersion" label="智能体版本" width="90" />
          <el-table-column prop="activationScope" label="环境" width="100" />
        </el-table>
      </section>

      <section v-if="draft" class="panel" aria-label="草稿与差异">
        <h2>配置发布草稿</h2>
        <el-form label-position="top">
          <el-form-item label="智能体版本"><el-input-number v-model="draft.appVersion" :min="1" /></el-form-item>
          <el-form-item label="体验修订"><el-input-number v-model="draft.experienceRev" :min="1" /></el-form-item>
          <el-form-item label="接入档案">
            <el-select :model-value="draft.integrationProfileId" @update:model-value="pickProfile">
              <el-option v-for="profile in approvedProfiles" :key="profile.profileId" :label="`${profile.profileId}@${profile.profileRev}`" :value="profile.profileId" />
            </el-select>
          </el-form-item>
          <template v-for="ref in draft.connectionProfileRefs" :key="ref.profileId">
            <el-form-item :label="`${ref.profileId} · 当前环境固定绑定`">
              <el-select :model-value="ref.bindingId" @update:model-value="(id: string) => chooseBinding(ref, id)">
                <el-option v-for="binding in availableBindings(ref)" :key="binding.bindingId" :value="binding.bindingId" :label="`${binding.bindingId}@${binding.currentRev}`" />
              </el-select>
            </el-form-item>
            <el-form-item :label="`${ref.profileId} · 批准操作`">
              <el-select v-model="ref.operation"><el-option v-for="op in operations(ref)" :key="op" :value="op" :label="op" /></el-select>
            </el-form-item>
          </template>
          <el-form-item label="接入档案修订"><el-input-number v-model="draft.integrationProfileRev" :min="1" /></el-form-item>
          <el-form-item label="连接引用（仅已批准）">
            <el-checkbox-group :model-value="draftConnectionKeys" @update:model-value="setConnections">
              <el-checkbox v-for="connection in connections.filter((item) => item.status === 'APPROVED')" :key="`${connection.profileId}#${connection.currentRev}`" :value="`${connection.profileId}#${connection.currentRev}`">
                {{ connection.profileId }}@{{ connection.currentRev }}
              </el-checkbox>
            </el-checkbox-group>
            <p v-if="!connections.some((item) => item.status === 'APPROVED')" class="muted">暂无已批准连接档案；可先在连接档案页批准。</p>
          </el-form-item>
        </el-form>
        <h3>差异</h3>
        <el-empty v-if="!differences.length" description="与激活版本一致" :image-size="60" />
        <el-table v-else :data="differences" size="small">
          <el-table-column prop="label" label="字段" width="110" />
          <el-table-column prop="before" label="激活版本" />
          <el-table-column prop="after" label="草稿" />
        </el-table>
        <h3>权限与完整性检查</h3>
        <p v-if="!draftProblems.length" class="ok">本地检查通过；保存后仍需服务端校验与批准。</p>
        <ul v-else class="problems"><li v-for="problem in draftProblems" :key="problem">{{ problem }}</li></ul>
        <el-button type="primary" :disabled="busy || !!draftProblems.length" @click="createDraft">保存草稿</el-button>
      </section>

      <section v-else-if="selected" class="panel" aria-label="发布详情">
        <h2>{{ selected.releaseId }}</h2>
        <p>
          <el-tag :type="selected.status === 'ACTIVE' ? 'success' : selected.status === 'APPROVED' ? 'warning' : 'info'">{{ selected.status }}</el-tag>
          智能体版本 {{ selected.appVersion }} · 体验 {{ selected.experienceRev }} · 环境 {{ selected.activationScope }}
        </p>
        <p class="muted">摘要 {{ selected.digest }} · 批准者 {{ selected.createdBy || "未记录" }} · 更新 {{ selected.updatedAt || selected.createdAt }}</p>
        <h3>生效范围</h3>
        <p class="muted">激活只影响该环境的新会话与新运行；既有运行使用各自创建时的快照。停用后该环境新会话返回 CONFIG_RELEASE_NOT_ACTIVE，不回退到其他环境的配置。</p>
        <h3>激活前置条件</h3>
        <p v-if="!blockers.length" class="ok">未发现阻断；激活时服务端仍会再复核一次引用。</p>
        <ul v-else class="problems"><li v-for="blocker in blockers" :key="blocker">{{ blocker }}</li></ul>
        <div v-if="auth.can('admin')" class="actions">
          <el-button :disabled="busy || selected.status === 'DISABLED'" @click="step('validate')">重新校验</el-button>
          <el-button :disabled="busy || selected.status === 'DISABLED'" @click="step('approve')">批准</el-button>
          <el-button type="primary" :disabled="busy || selected.status !== 'APPROVED' || !!blockers.length" @click="step('activate')">激活（CAS）</el-button>
          <el-button :disabled="busy || selected.status === 'DISABLED'" @click="step('disable')">停用</el-button>
        </div>
        <details>
          <summary>执行影响</summary>
          <ul><li v-for="line in releaseImpact('activate')" :key="line">{{ line }}</li></ul>
        </details>
        <el-button :disabled="busy" @click="showExport">导出脱敏引用</el-button>
        <pre v-if="exportText" class="export" aria-label="脱敏导出">{{ exportText }}</pre>
      </section>
    </div>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; }
.panel { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 16px; margin-bottom: 16px; }
.grid { display: grid; grid-template-columns: minmax(260px, 1fr) minmax(360px, 1.4fr); gap: 16px; }
.ok { color: var(--el-color-success); }
.problems { color: var(--el-color-danger); padding-left: 18px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; margin: 12px 0; }
.export { background: var(--el-fill-color-light); padding: 10px; border-radius: 6px; max-height: 240px; overflow: auto; }
.muted { color: var(--el-text-color-secondary); }
</style>
