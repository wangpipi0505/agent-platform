<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import PageHeader from "../../PageHeader.vue";
import PageError from "../../PageError.vue";
import { usePageOperation, usePageTitle } from "../../usePageOperation";
import { integrationClient } from "../../../../shared/api";
import {
  actionError,
  nextRequestId,
  parseApplications,
  parseExperiences,
  type ApplicationOption,
  type ExperienceView,
} from "./integration-console";

usePageTitle(() => "体验编辑");
const { auth, api, busy, error, trace, operation, success } = usePageOperation();
const client = integrationClient(api);
const applications = ref<ApplicationOption[]>([]);
const appCode = ref("");
const experience = ref<ExperienceView | null>(null);
const draft = reactive({
  title: "",
  welcomeText: "",
  capabilityDescriptions: "[]",
  promptRefs: "[]",
  inputPresentation: "{}",
  resultPresentation: "{}",
  helpRefs: "[]",
});
const isDraft = computed(() => experience.value?.status === "DRAFT");

function parseJson(value: string, label: string): unknown {
  try {
    return JSON.parse(value);
  } catch {
    throw new Error(`${label} 必须是有效 JSON`);
  }
}

function fill(view: ExperienceView) {
  const revision = view.revisions.at(-1);
  if (!revision) return;
  draft.title = revision.title;
  draft.welcomeText = revision.welcomeText;
  draft.capabilityDescriptions = JSON.stringify(revision.capabilityDescriptions, null, 2);
  draft.promptRefs = JSON.stringify(revision.promptRefs, null, 2);
  draft.inputPresentation = JSON.stringify(revision.inputPresentation, null, 2);
  draft.resultPresentation = JSON.stringify(revision.resultPresentation, null, 2);
  draft.helpRefs = JSON.stringify(revision.helpRefs, null, 2);
}

async function load() {
  if (busy.value || !appCode.value) return;
  await operation(async () => {
    experience.value = parseExperiences(await client.experience(appCode.value), appCode.value);
    fill(experience.value);
  });
}

async function save(revise: boolean) {
  if (busy.value) return;
  await operation(async () => {
    if (!appCode.value) throw new Error("请先选择应用");
    const body: Record<string, unknown> = {
      title: draft.title.trim(),
      welcomeText: draft.welcomeText,
      capabilityDescriptions: parseJson(draft.capabilityDescriptions, "能力说明"),
      promptRefs: parseJson(draft.promptRefs, "提示词引用"),
      inputPresentation: parseJson(draft.inputPresentation, "输入呈现"),
      resultPresentation: parseJson(draft.resultPresentation, "结果呈现"),
      helpRefs: parseJson(draft.helpRefs, "帮助引用"),
      requestId: nextRequestId(),
    };
    try {
      if (revise) {
        if (!experience.value) throw new Error("请先加载体验草稿");
        await client.reviseExperience(appCode.value, { ...body, expectedRev: experience.value.experienceRev });
      } else {
        await client.createExperience(appCode.value, body);
      }
    } catch (failure) {
      throw new Error(actionError(failure));
    }
    success(revise ? "已生成新体验修订；批准后配置发布才能引用" : "体验草稿已创建");
    draft.welcomeText = "";
    await load();
  });
}

async function transition(action: "approve" | "disable") {
  if (busy.value || !experience.value) return;
  await operation(async () => {
    const body = { expectedRev: experience.value!.experienceRev, requestId: nextRequestId() };
    try {
      if (action === "approve") await client.approveExperience(appCode.value, body);
      else await client.disableExperience(appCode.value, body);
    } catch (failure) {
      throw new Error(actionError(failure));
    }
    success(action === "approve" ? "体验已批准" : "体验已停用；已激活发布仍按快照运行，新激活会失败");
    await load();
  });
}

watch(appCode, () => { experience.value = null; void load(); });
onMounted(async () => {
  await operation(async () => {
    applications.value = parseApplications(await client.applications());
    if (!appCode.value && applications.value.length) appCode.value = applications.value[0].code;
  });
  await load();
});
</script>

<template>
  <div class="experience-editor">
    <PageHeader title="体验编辑" subtitle="编辑欢迎语、能力说明、推荐问法与呈现方式；选择模板只生成可编辑输入，不自动执行。" :busy="busy" @refresh="load" />
    <PageError :error="error" :trace="trace" />
    <div class="toolbar">
      <el-select v-model="appCode" placeholder="选择应用" :disabled="busy" aria-label="选择应用">
        <el-option v-for="application in applications" :key="application.code" :label="`${application.name}（${application.code}）`" :value="application.code" />
      </el-select>
      <template v-if="experience">
        <el-tag :type="experience.status === 'APPROVED' ? 'success' : experience.status === 'DISABLED' ? 'info' : 'warning'">{{ experience.status }}</el-tag>
        <span class="muted">修订 {{ experience.experienceRev }} · 批准者 {{ experience.revisions.at(-1)?.createdBy || "未记录" }} · 更新 {{ experience.updatedAt || experience.createdAt }}</span>
      </template>
    </div>
    <el-empty v-if="!experience" :description="appCode ? '该应用尚未配置体验，可在右侧创建草稿' : '请选择应用'" />
    <div v-else class="grid">
      <section class="panel" aria-label="当前体验">
        <h2>当前修订 {{ experience.experienceRev }}</h2>
        <p class="muted">校验摘要 {{ experience.revisions.at(-1)?.declarationSha256 || "—" }}</p>
        <h3>{{ experience.revisions.at(-1)?.title }}</h3>
        <p class="welcome">{{ experience.revisions.at(-1)?.welcomeText }}</p>
        <div v-if="auth.can('admin')" class="actions">
          <el-button type="primary" :disabled="busy || experience.status === 'APPROVED'" @click="transition('approve')">批准</el-button>
          <el-button :disabled="busy || experience.status === 'DISABLED'" @click="transition('disable')">停用</el-button>
        </div>
      </section>
      <section class="panel" aria-label="体验草稿">
        <h2>{{ isDraft ? "继续编辑草稿" : "复制当前修订为新草稿" }}</h2>
        <el-form label-position="top">
          <el-form-item label="标题"><el-input v-model="draft.title" maxlength="128" /></el-form-item>
          <el-form-item label="欢迎语"><el-input v-model="draft.welcomeText" type="textarea" :rows="3" maxlength="2048" show-word-limit /></el-form-item>
          <el-form-item label="能力说明 JSON"><el-input v-model="draft.capabilityDescriptions" type="textarea" :rows="4" /></el-form-item>
          <el-form-item label="推荐问法 JSON"><el-input v-model="draft.promptRefs" type="textarea" :rows="4" /></el-form-item>
          <el-form-item label="输入呈现 JSON"><el-input v-model="draft.inputPresentation" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="结果呈现 JSON"><el-input v-model="draft.resultPresentation" type="textarea" :rows="3" /></el-form-item>
          <el-form-item label="帮助引用 JSON"><el-input v-model="draft.helpRefs" type="textarea" :rows="2" /></el-form-item>
          <el-button type="primary" :disabled="busy" @click="save(!isDraft)">{{ isDraft ? "保存修订" : "保存为新草稿" }}</el-button>
        </el-form>
      </section>
    </div>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; }
.grid { display: grid; grid-template-columns: minmax(280px, 1fr) minmax(360px, 1.4fr); gap: 16px; }
.panel { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 16px; }
.welcome { white-space: pre-wrap; }
.actions { display: flex; gap: 8px; margin-top: 12px; }
.muted { color: var(--el-text-color-secondary); }
</style>
