<script setup lang="ts">
/** ONB-04：验证发布——预检（规格+连接+requestId）通过→草稿有效→发布可用；
 * 预检失败展示缺项可回改；发布走 expectedRev 修订基线 + requestId 幂等（ADMIN）。 */
import { computed, onMounted, ref } from "vue";
import { usePageOperation } from "../../usePageOperation";
import { normalizePreview, onboardingClient } from "../../../../shared/onboarding";
import OnboardingErrorNotice from "./OnboardingErrorNotice.vue";

const { auth, api, error } = usePageOperation();
const client = onboardingClient(api);

const templates = ref<{ code: string; status: string; currentRev: number }[]>([]);
const code = ref("");
const document = ref("");
const connection = ref("");
const checking = ref(false);
const preflightResult = ref<ReturnType<typeof normalizePreview>>();
const preflightDone = ref(false);
const publishReceipt = ref("");
const publishing = ref(false);
const emit = defineEmits<{ published: [code: string] }>();

function setErr(message: string) {
  error.value = message;
}
const canManage = computed(() => auth.can("admin"));
const draftTemplates = computed(() => templates.value.filter((row) => row.status === "DRAFT"));
const current = computed(() => templates.value.find((row) => row.code === code.value));
const preflightOk = computed(
  () =>
    preflightDone.value &&
    !!preflightResult.value &&
    preflightResult.value.operations.length > 0 &&
    preflightResult.value.operations.every((op) => op.supported),
);
const canPublish = computed(
  () =>
    canManage.value &&
    current.value?.status === "DRAFT" &&
    preflightOk.value &&
    !publishing.value,
);

async function load() {
  if (!canManage.value) return;
  try {
    templates.value = await client.list();
    if (!code.value && draftTemplates.value.length) code.value = draftTemplates.value[0].code;
  } catch (e: any) {
    setErr(e.message);
  }
}
onMounted(load);

async function runPreflight() {
  if (!code.value || !current.value) {
    setErr("请选择 DRAFT 模板。");
    return;
  }
  if (!document.value.trim() || !connection.value.trim()) {
    setErr("预检需要原始规格与连接引用。");
    return;
  }
  checking.value = true;
  setErr("");
  preflightDone.value = false;
  try {
    preflightResult.value = normalizePreview(
      await client.preflight(code.value, current.value.currentRev, {
        document: document.value,
        connection: connection.value,
        requestId: crypto.randomUUID(),
      }),
    );
    preflightDone.value = true;
  } catch (e: any) {
    setErr(e.message);
  } finally {
    checking.value = false;
  }
}

async function publish() {
  if (!canPublish.value || !current.value) return;
  publishing.value = true;
  setErr("");
  try {
    await client.publish(code.value, {
      expectedRev: current.value.currentRev,
      requestId: crypto.randomUUID(),
    });
    publishReceipt.value = `${code.value} 已发布（r${current.value.currentRev}）`;
    emit("published", code.value);
    await load();
  } catch (e: any) {
    setErr(e.message);
  } finally {
    publishing.value = false;
  }
}
</script>
<template>
  <div class="verify">
    <p class="muted">
      验证发布顺序：预检（只读解析规格+连接）→ 通过后草稿有效 → 发布动作可用；预检失败展示缺项可回改，不创建任何资源。
    </p>
    <p v-if="!canManage" class="muted" role="note">
      预检与发布为管理员操作；业务配置者可在登记向导中完成组装并交管理员复核。
    </p>
    <template v-else>
      <div class="row">
        <el-select v-model="code" placeholder="选择 DRAFT 模板" aria-label="选择模板" style="min-width: 220px">
          <el-option
            v-for="row in draftTemplates"
            :key="row.code"
            :value="row.code"
            :label="`${row.code} · r${row.currentRev} · ${row.status}`"
          />
        </el-select>
        <el-button :loading="checking" :disabled="!code" @click="runPreflight">
          {{ preflightDone ? "重新预检" : "开始预检" }}
        </el-button>
      </div>
      <!-- ②预检/发布失败后重试：document/connection 由 v-model 保留，不丢已填内容。 -->
      <el-input
        v-model="document"
        type="textarea"
        :rows="4"
        aria-label="原始规格"
        placeholder="原始规格（OpenAPI 文本；预检按此内容 SHA 追溯）"
      />
      <el-input
        v-model="connection"
        aria-label="连接引用"
        placeholder="连接引用（已批准连接档案标识）"
      />
      <OnboardingErrorNotice :error="error || null" />
      <template v-if="preflightDone && preflightResult">
        <el-alert
          v-if="preflightOk"
          type="success"
          :closable="false"
          show-icon
          title="预检通过：草稿有效，可发布"
          :description="`规格摘要 ${preflightResult.documentSha256.slice(0, 16)}…；支持子集 ${preflightResult.operations.filter((op) => op.supported).length}/${preflightResult.operations.length}。`"
        />
        <el-alert
          v-else
          type="warning"
          :closable="false"
          show-icon
          title="预检未通过：缺项如下，可回改后重新预检"
          :description="`不支持操作 ${preflightResult.operations.filter((op) => !op.supported).length} 个；${preflightResult.warnings[0] ?? '详见逐操作诊断。'}`"
        />
        <el-button
          v-if="preflightOk"
          type="primary"
          :loading="publishing"
          :disabled="!canPublish"
          @click="publish"
        >
          发布草稿（expectedRev={{ current?.currentRev }}）
        </el-button>
      </template>
      <p v-if="publishReceipt" class="muted" role="status">{{ publishReceipt }}</p>
    </template>
  </div>
</template>
<style scoped>
.verify { min-width: 0; display: flex; flex-direction: column; gap: .6rem; }
</style>
