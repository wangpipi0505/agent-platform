<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import PageHeader from "../../PageHeader.vue";
import PageError from "../../PageError.vue";
import { usePageOperation, usePageTitle } from "../../usePageOperation";
import { integrationClient } from "../../../../shared/api";
import {
  ENVIRONMENTS,
  diagnoseStages,
  parseApplications,
  parseBindings,
  parseConnections,
  parseCredential,
  parseProfiles,
  parseReleases,
  type ApplicationCredential,
  type ApplicationOption,
  type ConnectionProfile,
  type DiagnosticStage,
  type EnvironmentBinding,
  type IntegrationProfileView,
  type ReleaseView,
} from "./integration-console";

usePageTitle(() => "接入诊断");
const { api, busy, error, trace, operation } = usePageOperation();
const client = integrationClient(api);
const applications = ref<ApplicationOption[]>([]);
const appCode = ref("");
const environment = ref("production");
const releases = ref<ReleaseView[]>([]);
const profiles = ref<IntegrationProfileView[]>([]);
const connections = ref<ConnectionProfile[]>([]);
const bindings = ref<EnvironmentBinding[]>([]);
const credential = ref<ApplicationCredential | null>(null);
const credentialError = ref("");

const stages = computed<DiagnosticStage[]>(() =>
  diagnoseStages({
    environment: environment.value,
    releases: releases.value,
    profiles: profiles.value,
    connections: connections.value,
    bindings: bindings.value,
    credential: credential.value,
  }),
);

async function load() {
  if (busy.value || !appCode.value) return;
  await operation(async () => {
    applications.value = applications.value.length ? applications.value : parseApplications(await client.applications());
    releases.value = parseReleases(await client.configReleases(appCode.value));
    profiles.value = parseProfiles(await client.integrationProfiles(appCode.value));
    connections.value = parseConnections(await client.connectionProfiles());
    bindings.value = parseBindings(await client.environmentBindings());
    credentialError.value = "";
    try {
      credential.value = parseCredential(await client.applicationCredential(appCode.value), appCode.value);
    } catch (failure) {
      credential.value = null;
      credentialError.value = failure instanceof Error ? failure.message : "应用接入凭据不可读";
    }
  });
}

function tagType(status: DiagnosticStage["status"]): "success" | "warning" | "danger" | "info" {
  if (status === "OK") return "success";
  if (status === "WARN") return "warning";
  if (status === "BLOCKED") return "danger";
  return "info";
}

watch([appCode, environment], load);
onMounted(async () => {
  await operation(async () => {
    applications.value = parseApplications(await client.applications());
    if (!appCode.value && applications.value.length) appCode.value = applications.value[0].code;
  });
  await load();
});
</script>

<template>
  <div class="integration-diagnostics">
    <PageHeader title="接入诊断" subtitle="按接入应用与环境组合只读投影，逐阶段显示安全状态；不发起业务运行，不显示凭据。" :busy="busy" @refresh="load" />
    <PageError :error="error" :trace="trace" />
    <div class="toolbar">
      <el-select v-model="appCode" placeholder="选择智能体" :disabled="busy" aria-label="选择智能体">
        <el-option v-for="application in applications" :key="application.code" :label="`${application.name}（${application.code}）`" :value="application.code" />
      </el-select>
      <el-select v-model="environment" :disabled="busy" aria-label="选择环境">
        <el-option v-for="item in ENVIRONMENTS" :key="item" :label="item" :value="item" />
      </el-select>
      <span class="muted">只读：本页不会创建会话、运行或审批。</span>
    </div>
    <p v-if="credentialError" class="muted">应用接入凭据：{{ credentialError }}（其余阶段仍按只读投影判断）</p>
    <section class="panel" aria-label="分阶段状态">
      <h2>接入 ID：{{ appCode || "未选择" }} · {{ environment }}</h2>
      <el-table :data="stages" size="small">
        <el-table-column prop="label" label="阶段" width="120" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag :type="tagType(row.status)">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="detail" label="说明" min-width="320" />
        <el-table-column prop="evidence" label="依据" width="180" />
      </el-table>
      <p class="muted">“UNKNOWN” 表示本控制台没有该阶段的只读投影，不能据此推断成功；敏感原值（token、SQL、堆栈）不会出现在本页。</p>
    </section>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; }
.panel { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 16px; }
.muted { color: var(--el-text-color-secondary); }
</style>
