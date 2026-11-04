<script setup lang="ts">
/** ONB-01：业务接入模板目录与登记向导。包装既有能力包目录与 INT-01/03 契约引用；
 * 模板服务端未交付前，目录区不显示任何数据，提交入口禁用并说明——不伪造已可用。 */
import { computed, onMounted, ref } from "vue";
import type { Ref } from "vue";
import ConnectionCheckStep from "./ConnectionCheckStep.vue";
import PageError from "../../PageError.vue";
import PageHeader from "../../PageHeader.vue";
import { usePageOperation, usePageTitle } from "../../usePageOperation";
import type { CapabilityPackageSummary } from "../../../../shared/capability-package";
import {
  applyPackageSelection,
  buildOnboardingTemplatePayload,
  emptyOnboardingTemplateDraft,
  ONBOARDING_TEMPLATE_KINDS,
  selectablePackages,
  TEMPLATE_SERVICE_UNAVAILABLE,
  validateOnboardingTemplateDraft,
  useCapabilityPackageClient,
  type OnboardingTemplateDraft,
} from "../../../../shared/onboarding";

usePageTitle(() => "接入模板");
const { auth, busy, error, trace, operation } = usePageOperation();
const client = useCapabilityPackageClient();
const packages: Ref<CapabilityPackageSummary[]> = ref([]);
const query = ref("");
const wizardOpen = ref(false);
const step = ref(0);
const draft = ref<OnboardingTemplateDraft>(emptyOnboardingTemplateDraft());
const payloadPreview = ref("");
const submitBlocked = TEMPLATE_SERVICE_UNAVAILABLE;

// ②目录由服务端按租户/权限过滤——业务配置者只会看到被授予的能力包版本。
async function load() {
  if (!auth.token) return;
  await operation(async () => {
    const page = await client.list({ q: query.value.trim() || undefined });
    packages.value = selectablePackages(page.items);
  });
}
onMounted(load);

const selectedRow = computed(() =>
  packages.value.find(
    (row) => row.packageVersionId === draft.value.sourcePackageVersionId,
  ),
);
const errors = computed(() => validateOnboardingTemplateDraft(draft.value));
const steps = ["选择来源能力包", "登记契约字段", "核对并导出登记内容"];

function openWizard() {
  draft.value = emptyOnboardingTemplateDraft();
  payloadPreview.value = "";
  step.value = 0;
  wizardOpen.value = true;
}
function choosePackage(packageVersionId: number) {
  draft.value = applyPackageSelection(draft.value, {
    ...packages.value.find((row) => row.packageVersionId === packageVersionId)!,
  });
}
function next() {
  if (step.value === 0 && draft.value.sourcePackageVersionId === null) return;
  if (step.value === 1 && validateOnboardingTemplateDraft(draft.value).length) return;
  if (step.value === 1)
    payloadPreview.value = JSON.stringify(
      buildOnboardingTemplatePayload(draft.value),
      null,
      2,
    );
  step.value = Math.min(step.value + 1, steps.length - 1);
}
async function copyPayload() {
  try {
    await navigator.clipboard.writeText(payloadPreview.value);
  } catch {
    /* 剪贴板不可用时用户可直接从预览区选择复制 */
  }
}
</script>
<template>
  <PageHeader
    title="接入模板"
    subtitle="面向业务配置者的可复用接入模板：引用既有能力包与集成契约，不重复建设连接。"
    :busy="busy"
    @refresh="load"
  >
    <template #actions>
      <el-button type="primary" :disabled="busy" @click="openWizard">登记模板</el-button>
    </template>
  </PageHeader>
  <PageError :error="error" :trace="trace" />
  <el-alert
    type="info"
    :closable="false"
    show-icon
    title="模板服务端尚未交付"
    :description="submitBlocked"
  />
  <div class="panel">
    <div class="filters">
      <el-input
        v-model="query"
        clearable
        placeholder="按软件或能力包标识筛选被授予的版本"
        aria-label="能力包筛选"
        @keyup.enter="load"
      />
      <el-button :loading="busy" @click="load">查询</el-button>
    </div>
    <el-skeleton v-if="busy && !packages.length" :rows="5" animated />
    <el-empty
      v-else-if="!packages.length"
      description="当前账号没有被授予的能力包版本；目录由管理员授予后在此显示。"
    />
    <el-table v-else :data="packages" stripe>
      <el-table-column label="软件 / 能力包" min-width="220">
        <template #default="{ row }">
          <strong>{{ row.softwareId }}</strong>
          <div class="muted">{{ row.packageId }} · {{ row.version }}</div>
        </template>
      </el-table-column>
      <el-table-column label="绑定状态" width="120">
        <template #default="{ row }">{{ row.bindingStatus === "RESOLVED" ? "已解析" : "待解析" }}</template>
      </el-table-column>
      <el-table-column label="来源应用" width="100">
        <template #default="{ row }">{{ row.applicationCount }}</template>
      </el-table-column>
      <el-table-column label="登记时间" width="170">
        <template #default="{ row }">{{ row.createdAt }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button text @click="() => { openWizard(); choosePackage(row.packageVersionId); }"
            >以此登记模板</el-button
          >
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="没有匹配的被授予版本。" />
      </template>
    </el-table>
    <p class="muted">
      模板复用能力包精确身份（版本与 SHA256）；目录按租户与权限过滤，撤回模板不影响已发布应用来源。
    </p>
  </div>
  <!-- ONB-02：前置连接与规格诊断先于模板落库（禁止诊断失败后继续创建草稿）。 -->
  <ConnectionCheckStep />
  <el-dialog
    v-model="wizardOpen"
    title="登记接入模板"
    width="min(760px, 96vw)"
    destroy-on-close
  >
    <el-steps :active="step" finish-status="success" simple>
      <el-step v-for="label in steps" :key="label" :title="label" />
    </el-steps>
    <template v-if="step === 0">
      <p class="muted">只能选择当前账号被授予的能力包版本；来源身份在后续步骤不可改写。</p>
      <el-table :data="packages" stripe max-height="360">
        <el-table-column label="软件 / 能力包" min-width="200">
          <template #default="{ row }">{{ row.softwareId }} · {{ row.packageId }} · {{ row.version }}</template>
        </el-table-column>
        <el-table-column label="SHA256" min-width="150">
          <template #default="{ row }"><code class="sha">{{ String(row.sha256).slice(0, 12) }}…</code></template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button
              :type="draft.sourcePackageVersionId === row.packageVersionId ? 'primary' : 'default'"
              text
              @click="choosePackage(row.packageVersionId)"
              >{{ draft.sourcePackageVersionId === row.packageVersionId ? "已选择" : "选择" }}</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </template>
    <template v-else-if="step === 1">
      <el-form label-position="top">
        <div class="field-grid">
          <el-form-item label="模板编码" required>
            <el-input v-model="draft.code" placeholder="小写字母开头，2–64 位" />
          </el-form-item>
          <el-form-item label="模板名称" required>
            <el-input v-model="draft.name" />
          </el-form-item>
          <el-form-item label="模板种类" required>
            <el-select v-model="draft.kind">
              <el-option
                v-for="item in ONBOARDING_TEMPLATE_KINDS"
                :key="item.kind"
                :value="item.kind"
                :label="item.label"
                :disabled="!item.selectable"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="目标应用编码" required>
            <el-input v-model="draft.appCode" placeholder="已发布应用的编码" />
          </el-form-item>
          <el-form-item label="体验引用 appCode" required>
            <el-input v-model="draft.experience.appCode" placeholder="AssistantExperience 所属应用" />
          </el-form-item>
          <el-form-item label="体验修订号" required>
            <el-input-number v-model="draft.experience.experienceRev" :min="1" />
          </el-form-item>
          <el-form-item label="业务系统编码" required>
            <el-input v-model="draft.integration.bizSystemCode" />
          </el-form-item>
          <el-form-item label="目标环境" required>
            <el-input v-model="draft.integration.environment" placeholder="例如 PROD / STAGING" />
          </el-form-item>
          <el-form-item label="挂载模式" required>
            <el-input v-model="draft.integration.mode" placeholder="例如 EMBED / LINK" />
          </el-form-item>
        </div>
        <el-form-item label="模板说明">
          <el-input v-model="draft.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <el-alert
        v-for="message in errors"
        :key="message"
        :title="message"
        type="warning"
        :closable="false"
        show-icon
      />
    </template>
    <template v-else>
      <p class="muted">
        登记内容引用契约字段（体验/集成档案），来源版本 {{ draft.sourcePackageVersionId }} · SHA
        <code class="sha">{{ draft.sourcePackageSha256.slice(0, 12) }}…</code>；不含凭据与可执行脚本字段。
      </p>
      <pre class="payload">{{ payloadPreview }}</pre>
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="提交入口暂未启用"
        :description="submitBlocked + ' 可复制以下登记内容，待接口启用后提交联调。'"
      />
      <div class="preview-actions">
        <el-button :disabled="!payloadPreview" @click="copyPayload">复制登记 JSON</el-button>
        <el-button type="primary" disabled>{{ "提交登记" }}</el-button>
      </div>
    </template>
    <template #footer>
      <el-button v-if="step > 0" @click="step--">上一步</el-button>
      <el-button
        v-if="step < steps.length - 1"
        type="primary"
        :disabled="step === 0 ? draft.sourcePackageVersionId === null : errors.length > 0"
        @click="next"
        >下一步</el-button
      >
      <el-button v-else @click="wizardOpen = false">完成</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 1rem; }
.sha { word-break: break-all; }
.payload { max-height: 30vh; overflow: auto; white-space: pre-wrap; word-break: break-word; background: var(--el-fill-color-light, #f5f7fa); padding: .75rem; border-radius: .45rem; }
.preview-actions { display: flex; justify-content: flex-end; gap: .5rem; margin-top: .75rem; }
@media (max-width: 640px) { .field-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
