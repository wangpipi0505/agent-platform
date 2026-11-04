<script setup lang="ts">
/** 业务使用模板的持久会话；目录、资源、计划摘要和每一步结果均以服务端回执为准。 */
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { usePageOperation } from "../../usePageOperation";
import PageError from "../../PageError.vue";
import BusinessFieldsStep from "./BusinessFieldsStep.vue";
import FieldMappingStep from "./FieldMappingStep.vue";
import ReturnPresentationStep from "./ReturnPresentationStep.vue";
import SemanticConfigurationStep from "./SemanticConfigurationStep.vue";
import AssistantLaunchStep from "./AssistantLaunchStep.vue";
import {
  onboardingSessionClient,
  onboardingSessionTerminal,
  type OnboardingSession,
  type OnboardingSessionPlan,
  type OnboardingPreview,
  type OnboardingTemplateChoice,
} from "../../../../shared/onboarding-session";
import { validateFieldsBundle, type FieldsBundle } from "../../../../shared/onboarding-fields";

// 可接既有宿主的初始引用；可用目录仍独立向业务读口核验，不依赖ADMIN父页加载。
defineProps<{ templates?: readonly OnboardingTemplateChoice[] }>();
const { api, auth, busy, error, trace, operation, current } = usePageOperation();
const client = onboardingSessionClient(api);
const route = useRoute();
const router = useRouter();
const templates = ref<OnboardingTemplateChoice[]>([]);
const sessions = ref<OnboardingSession[]>([]);
const session = ref<OnboardingSession>();
const plan = ref<OnboardingSessionPlan>();
const preview = ref<OnboardingPreview>();
const templateCode = ref("");
const appCode = ref("");
const appName = ref("");
const cancelReason = ref("");
const createKey = ref(crypto.randomUUID());
const dirty = ref(false);
const stepKeys = new Map<string, string>();
let sequence = 0;

const published = computed(() => templates.value.filter(row => row.status === "PUBLISHED" && row.kind === "QUERY"));
const fieldProblems = computed(() => plan.value ? validateFieldsBundle(plan.value.fieldsBundle) : []);
const assembled = computed(() => !!session.value?.application);
const verified = computed(() => session.value?.steps.some(step => step.step === "VERIFY" && step.status === "SUCCEEDED") ?? false);
const terminal = computed(() => !!session.value && onboardingSessionTerminal(session.value.status));
const locked = computed(() => busy.value || terminal.value || !auth.can("edit"));
const operations = computed(() => preview.value?.operations ?? session.value?.choices?.operations ?? []);

watch(published, rows => {
  if (!rows.some(row => row.code === templateCode.value)) templateCode.value = rows[0]?.code ?? "";
}, { immediate: true });

function adopt(value: OnboardingSession) {
  if (!current()) return;
  session.value = value;
  // HTTP契约是JSON；复制本地编辑副本，保留false/0/null，不让表单改写服务端基线。
  plan.value = JSON.parse(JSON.stringify(value.plan)) as OnboardingSessionPlan;
  dirty.value = false;
  cancelReason.value = "";
}
function key(step: string) {
  const id = `${session.value?.sessionId}:${session.value?.rev}:${step}`;
  if (!stepKeys.has(id)) stepKeys.set(id, crypto.randomUUID());
  return stepKeys.get(id)!;
}
function mayReplacePlan() {
  if (busy.value) return false;
  if (!dirty.value) return true;
  error.value = "有未保存的接入计划，请先保存；继续或刷新不会丢弃当前输入。";
  return false;
}
async function refresh() {
  if (!mayReplacePlan()) return;
  const ticket = ++sequence;
  const id = typeof route.query.onboardingSession === "string" ? route.query.onboardingSession : session.value?.sessionId;
  await operation(async () => {
    const [available, rows] = await Promise.all([client.templates(), client.list()]);
    if (!current() || ticket !== sequence) return;
    templates.value = available;
    sessions.value = rows;
    if (id) {
      const value = await client.get(id);
      if (current() && ticket === sequence && !dirty.value) adopt(value);
    }
  });
}
async function resume(id: string) {
  if (!mayReplacePlan()) return;
  const ticket = ++sequence;
  await operation(async () => {
    const value = await client.get(id);
    if (!current() || ticket !== sequence || dirty.value) return;
    adopt(value);
    preview.value = undefined;
    await router.replace({ query: { ...route.query, onboardingSession: id } });
  });
}
async function create() {
  const template = published.value.find(row => row.code === templateCode.value);
  if (!template || !auth.can("edit") || !mayReplacePlan()) return;
  const ticket = ++sequence;
  await operation(async () => {
    const value = await client.create({
      templateCode: template.code,
      templateRev: template.currentRev,
      appCode: appCode.value.trim(),
      appName: appName.value.trim(),
      requestId: createKey.value,
    });
    if (!current() || ticket !== sequence) return;
    adopt(value);
    preview.value = undefined;
    await router.replace({ query: { ...route.query, onboardingSession: value.sessionId } });
    createKey.value = crypto.randomUUID();
    const rows = await client.list();
    if (current() && ticket === sequence) sessions.value = rows;
  });
}
async function save() {
  const target = session.value;
  if (!target || !plan.value || locked.value || !dirty.value || fieldProblems.value.length) return;
  const savedPlan = JSON.parse(JSON.stringify(plan.value)) as OnboardingSessionPlan;
  await operation(async () => {
    const value = await client.save(target, savedPlan, key("SAVE"));
    if (current() && session.value?.sessionId === target.sessionId) {
      adopt(value);
      preview.value = undefined;
    }
  });
}
async function inspect() {
  const target = session.value;
  if (!target || locked.value || dirty.value) return;
  await operation(async () => {
    const value = await client.preview(target, key("PREVIEW"));
    if (current() && session.value?.sessionId === target.sessionId) preview.value = value;
  });
}
async function execute(step: "ASSEMBLE" | "VERIFY" | "PUBLISH") {
  const target = session.value;
  if (!target || locked.value || dirty.value) return;
  if (step === "ASSEMBLE" && !preview.value?.ready) return;
  if (step === "VERIFY" && !assembled.value) return;
  if (step === "PUBLISH" && !verified.value) return;
  await operation(async () => {
    try {
      const value = await client.execute(target, step, key(step));
      if (current() && session.value?.sessionId === target.sessionId) adopt(value);
    } catch (failure) {
      // 部分成功/UNKNOWN只回读原会话；不能用重建会话或覆盖本地计划掩盖未知结果。
      const persisted = await client.get(target.sessionId);
      if (current() && session.value?.sessionId === target.sessionId) session.value = persisted;
      throw failure;
    }
  });
}
async function cancel() {
  const target = session.value;
  if (!target || locked.value || dirty.value || !cancelReason.value.trim()) return;
  const reason = cancelReason.value.trim();
  await operation(async () => {
    const value = await client.cancel(target, reason, key("CANCEL"));
    if (current() && session.value?.sessionId === target.sessionId) {
      adopt(value);
      preview.value = undefined;
    }
  });
}
function changed() {
  if (locked.value) return;
  dirty.value = true;
  preview.value = undefined;
}
function setValues(values: Record<string, unknown>) {
  if (!plan.value || locked.value) return;
  const keys = Object.keys({ ...plan.value.values, ...values });
  if (keys.every(name => JSON.stringify(plan.value!.values[name]) === JSON.stringify(values[name]))) return;
  plan.value.values = values;
  changed();
}
function setBundle(bundle: FieldsBundle) {
  if (!plan.value || locked.value) return;
  plan.value.fieldsBundle = bundle;
  changed();
}
function selectOperation(operationId: string, checked: boolean) {
  if (!plan.value || locked.value || !operations.value.some(item => item.operationId === operationId && item.supported)) return;
  plan.value.selectedOperations = checked
    ? [...new Set([...plan.value.selectedOperations, operationId])]
    : plan.value.selectedOperations.filter(id => id !== operationId);
  changed();
}
function selectConnection(id: string) {
  if (!plan.value || locked.value) return;
  plan.value.connectionRef = session.value?.choices?.connections?.find(item => item.ref.bindingId === id)?.ref;
  changed();
}
function receiptMissing(receipt?: Record<string, unknown>) {
  if (!Array.isArray(receipt?.missing)) return [];
  return receipt.missing.flatMap((item: unknown) => {
    if (!item || typeof item !== "object" || Array.isArray(item)) return [];
    const row = item as Record<string, unknown>;
    return typeof row.code === "string" && typeof row.message === "string"
      ? [{ code: row.code, message: row.message }] : [];
  });
}
async function semanticSaved() {
  const target = session.value;
  if (!target || terminal.value) return;
  // 子组件已PUT应用草稿；这里只读回持久会话，不再次PUT应用或接入计划。
  await operation(async () => {
    const value = await client.get(target.sessionId);
    if (current() && session.value?.sessionId === target.sessionId) {
      session.value = value;
      preview.value = undefined;
    }
  });
}
watch(() => route.query.onboardingSession, value => {
  if (typeof value === "string" && value !== session.value?.sessionId) void resume(value);
});
watch(() => auth.generation, () => {
  sequence++;
  templates.value = [];
  sessions.value = [];
  session.value = undefined;
  plan.value = undefined;
  preview.value = undefined;
  dirty.value = false;
  stepKeys.clear();
}, { flush: "sync" });
onMounted(refresh);
</script>

<template>
  <section class="panel onboarding-session">
    <h2>使用模板接入助手 / 应用</h2>
    <p class="muted">选择已批准的读取模板，填写业务字段，逐步组装、验证和发布助手 / 应用。会话与每一步回执保存到服务端。</p>
    <PageError :error="error" :trace="trace" />
    <el-alert v-if="!published.length" type="info" :closable="false" title="暂无已发布的读取模板，请管理员先完成模板与连接准备。" />
    <el-form v-else inline>
      <el-form-item label="读取模板">
        <el-select v-model="templateCode" :disabled="busy || dirty"><el-option v-for="row in published" :key="row.code" :value="row.code" :label="`${row.name} · r${row.currentRev}`" /></el-select>
      </el-form-item>
      <el-form-item label="新助手编码"><el-input v-model="appCode" :disabled="busy || dirty" placeholder="与模板编码分开，例如 orders_helper" /></el-form-item>
      <el-form-item label="新助手名称"><el-input v-model="appName" :disabled="busy || dirty" /></el-form-item>
      <el-button type="primary" :disabled="busy || dirty || !auth.can('edit') || !appCode.trim() || !appName.trim()" @click="create">创建接入会话</el-button>
    </el-form>
    <div class="session-list">
      <el-button v-for="row in sessions" :key="row.sessionId" :disabled="busy || dirty" @click="resume(row.sessionId)">
        {{ row.appCode }} · {{ row.status }} · {{ onboardingSessionTerminal(row.status) ? '查看回执' : '继续' }}
      </el-button>
      <el-button :disabled="busy || dirty" @click="refresh">刷新会话</el-button>
    </div>
    <template v-if="session && plan">
      <p>会话 <code>{{ session.sessionId }}</code> · 修订 {{ session.rev }} · {{ session.status }} · 目标应用 {{ session.appCode }}</p>
      <el-alert v-if="dirty" type="warning" :closable="false" title="输入已修改，请先保存计划；继续或刷新不会覆盖本地内容。" />
      <fieldset :disabled="locked" class="plan-fields">
        <el-form label-position="top" :disabled="locked">
          <el-form-item v-if="session.choices?.models?.length" label="已批准模型">
            <el-select v-model="plan.modelEntryId" @change="changed"><el-option v-for="model in session.choices.models" :key="model.id" :value="model.id" :label="model.label" /></el-select>
          </el-form-item>
          <el-form-item v-if="session.choices?.connections?.length" label="当前授权的连接与环境绑定">
            <el-select :model-value="plan.connectionRef?.bindingId" @update:model-value="selectConnection">
              <el-option v-for="connection in session.choices.connections" :key="connection.ref.bindingId" :value="connection.ref.bindingId" :label="connection.label" />
            </el-select>
          </el-form-item>
        </el-form>
        <div v-if="operations.length" class="operation-options">
          <h3>选择本次接入操作</h3>
          <div v-for="item in operations" :key="item.operationId">
            <el-checkbox
              :label="item.operationId"
              :model-value="plan.selectedOperations.includes(item.operationId)"
              :disabled="locked || !item.supported"
              @update:model-value="(checked: boolean) => selectOperation(item.operationId, checked)"
            >{{ item.operationId }}{{ item.supported ? '' : '（不支持）' }}</el-checkbox>
            <p v-if="item.reasons?.length" class="muted">{{ item.reasons.join('；') }}</p>
          </div>
        </div>
        <p v-if="plan.connectionRef" class="muted">连接 {{ plan.connectionRef.profileId }}@{{ plan.connectionRef.profileRev }} · 绑定 {{ plan.connectionRef.bindingId }}@{{ plan.connectionRef.bindingRev }} · 操作 {{ plan.connectionRef.operation }}</p>
        <BusinessFieldsStep :fields="plan.fieldsBundle.fields" :initial-values="plan.values" :disabled="locked" @values="setValues" />
        <FieldMappingStep :bundle="plan.fieldsBundle" @update="setBundle" />
        <ReturnPresentationStep :bundle="plan.fieldsBundle" @update="setBundle" />
      </fieldset>
      <ul v-if="fieldProblems.length" class="error"><li v-for="item in fieldProblems" :key="item.field + item.message">{{ item.field }}：{{ item.message }}</li></ul>
      <div class="actions">
        <el-button :disabled="locked || !dirty || !!fieldProblems.length" @click="save">保存接入计划</el-button>
        <el-button :disabled="locked || dirty" @click="inspect">预检计划</el-button>
        <el-button type="primary" :disabled="locked || dirty || !preview?.ready" @click="execute('ASSEMBLE')">组装真实应用草稿</el-button>
        <el-button :disabled="locked || dirty || !assembled" @click="execute('VERIFY')">验证与评测</el-button>
        <el-button type="primary" :disabled="locked || dirty || !verified" @click="execute('PUBLISH')">按门禁发布助手</el-button>
      </div>
      <el-form label-position="top">
        <el-form-item label="取消理由"><el-input v-model="cancelReason" :disabled="locked" placeholder="说明为什么不再继续接入；不会撤销已产生的外部效果" /></el-form-item>
        <el-button type="danger" :disabled="locked || dirty || !cancelReason.trim()" @click="cancel">取消接入会话</el-button>
      </el-form>
      <ul v-if="preview?.missing.length" class="error"><li v-for="item in preview.missing" :key="item.code">{{ item.code }}：{{ item.message }}</li></ul>
      <details v-if="preview?.resourcePlan">
        <summary>只读资源组装计划</summary><pre>{{ JSON.stringify(preview.resourcePlan, null, 2) }}</pre>
      </details>
      <p v-if="session.application?.appCode" class="muted">
        <a :href="'#/apps/' + encodeURIComponent(session.application.appCode) + '/orchestration/publish'">进入实际应用工作区完成评测与发布门禁</a>
        · 缺少已接受的评测报告时，不能自动编造预期值或视为发布通过。
      </p>
      <div v-for="row in session.steps" :key="row.step" class="step-receipt">
        <strong>{{ row.step }} · {{ row.status }}</strong>
        <p v-if="row.error">{{ row.error.code }}：{{ row.error.message }}</p>
        <ul v-if="receiptMissing(row.receipt).length" class="error">
          <li v-for="item in receiptMissing(row.receipt)" :key="item.code">{{ item.code }}：{{ item.message }}</li>
        </ul>
        <details v-if="row.receipt"><summary>服务端实际回执</summary><pre>{{ row.receipt }}</pre></details>
      </div>
      <SemanticConfigurationStep v-if="session.application?.appCode" :app-code="session.application.appCode" :disabled="locked || dirty" @saved="semanticSaved" />
      <AssistantLaunchStep
        v-if="session.handover && session.status === 'PUBLISHED'"
        :app-code="session.handover.appCode"
        :app-version="session.handover.appVersion"
        :published="true"
      />
    </template>
  </section>
</template>

<style scoped>
.onboarding-session { display: grid; gap: 12px; }
.actions, .session-list { display: flex; flex-wrap: wrap; gap: 8px; }
.step-receipt { padding: 8px 0; border-top: 1px solid var(--el-border-color); }
.plan-fields { border: 0; padding: 0; margin: 0; min-width: 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
