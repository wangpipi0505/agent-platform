<script setup lang="ts">
/** ONB-01：业务接入模板目录与登记向导。包装既有能力包目录与 INT-01/03 契约引用；
 * 模板服务端未交付前，目录区不显示任何数据，提交入口禁用并说明——不伪造已可用。 */
import { computed, onMounted, ref, watch } from "vue";
import type { Ref } from "vue";
import OnboardingSessionWorkspace from "./OnboardingSessionWorkspace.vue";
import DraftAssemblyStep from "./DraftAssemblyStep.vue";
import AssistantLaunchStep from "./AssistantLaunchStep.vue";
import VerifyPublishStep from "./VerifyPublishStep.vue";
import PageError from "../../PageError.vue";
import PageHeader from "../../PageHeader.vue";
import { usePageOperation, usePageTitle } from "../../usePageOperation";
import type { CapabilityPackageSummary } from "../../../../shared/capability-package";
import {
  applyPackageSelection,
  buildOnboardingTemplatePayload,
  emptyOnboardingTemplateDraft,
  onboardingClient,
  ONBOARDING_TEMPLATE_KINDS,
  selectablePackages,
  TEMPLATE_WRITE_SCOPE,
  validateOnboardingTemplateDraft,
  useCapabilityPackageClient,
  type OnboardingTemplateDraft,
  resumeEntryFor,
  type OnboardingTemplateRecord,
  publishedTemplateApplication,
  type PublishedOnboardingApplication,
} from "../../../../shared/onboarding";

usePageTitle(() => "接入模板");
const { auth, api, busy, error, trace, operation } = usePageOperation();
const client = useCapabilityPackageClient();
const canMaintain = computed(() => auth.can("admin"));
const packages: Ref<CapabilityPackageSummary[]> = ref([]);
const query = ref("");
const wizardOpen = ref(false);
const step = ref(0);
const draft = ref<OnboardingTemplateDraft>(emptyOnboardingTemplateDraft());
const payloadPreview = ref("");
// ONB-04：模板服务端已交付——目录读真实接口；提交/预检/发布为 ADMIN 操作。
const templates = ref<OnboardingTemplateRecord[]>([]);
const registered = ref<{ code: string; status: string; rev: number }>();
const publishedApplication = ref<PublishedOnboardingApplication>();
// ①会话恢复：DRAFT 草稿持久化于服务端，刷新/重进后从这里继续（验证发布步骤）。
const resumeTarget = ref<{ code: string; status: string }>();
const showResume = computed(() => !!resumeTarget.value);
// ONB-03：业务字段/受限映射/返回呈现步骤共享的声明式包（编辑后经校验进入登记预览）。

// ②目录由服务端按租户/权限过滤——业务配置者只会看到被授予的能力包版本。
const templateClient = onboardingClient(api);
async function load() {
  if (!auth.token || !canMaintain.value) return;
  await operation(async () => {
    // 业务工作区独立读已发布目录；管理员来源包/草稿加载失败不影响业务会话。
    const page = await client.list({ q: query.value.trim() || undefined });
    packages.value = selectablePackages(page.items);
    templates.value = await templateClient.list({ q: query.value.trim() || undefined });
    const draftRow = templates.value.find((row) => resumeEntryFor(row) === "verify");
    resumeTarget.value = draftRow ? { code: draftRow.code, status: draftRow.status } : undefined;
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
  if (!canMaintain.value || busy.value) return;
  publishedApplication.value = undefined;
  draft.value = emptyOnboardingTemplateDraft();
  payloadPreview.value = "";
  step.value = 0;
  wizardOpen.value = true;
}
async function verifyPublishedApplication(templateCode: string) {
  if (!canMaintain.value) return;
  publishedApplication.value = undefined;
  await operation(async () => {
    publishedApplication.value = await publishedTemplateApplication(api, templateCode);
  });
}
function choosePackage(packageVersionId: number) {
  if (!canMaintain.value) return;
  draft.value = applyPackageSelection(draft.value, {
    ...packages.value.find((row) => row.packageVersionId === packageVersionId)!,
  });
}
function next() {
  if (!canMaintain.value) return;
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
  if (!canMaintain.value) return;
  try {
    await navigator.clipboard.writeText(payloadPreview.value);
  } catch {
    /* 剪贴板不可用时用户可直接从预览区选择复制 */
  }
}
// 身份/角色失效立即清掉管理区与未确认交接，不保留上一个账号的模板草稿。
watch([() => auth.generation, () => canMaintain.value], () => {
  templates.value = [];
  packages.value = [];
  resumeTarget.value = undefined;
  registered.value = undefined;
  publishedApplication.value = undefined;
  wizardOpen.value = false;
  draft.value = emptyOnboardingTemplateDraft();
}, { flush: "sync" });
</script>
<template>
  <PageHeader
    title="接入模板"
    subtitle="使用已发布模板接入助手；模板登记与发布由管理员维护。"
    :busy="busy"
    @refresh="load"
  >
    <template #actions>
      <el-button v-if="canMaintain" type="primary" :disabled="busy" @click="openWizard">登记模板</el-button>
    </template>
  </PageHeader>
  <PageError :error="error" :trace="trace" />
  <OnboardingSessionWorkspace />
  <template v-if="canMaintain">
  <el-alert
    type="info"
    :closable="false"
    show-icon
    title="登记与发布为管理员操作"
    :description="TEMPLATE_WRITE_SCOPE"
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
  <el-alert
    v-if="showResume && resumeTarget"
    type="info"
    :closable="true"
    show-icon
    title="发现未完成的登记草稿"
    :description="`模板 ${resumeTarget.code} 处于 DRAFT 状态（服务端已持久化）；在下方「组装登记与验证发布」中选择该模板继续预检与发布，已填内容不因刷新丢失。`"
  />
  <!-- ONB-04：服务端模板目录（真实接口）+ 组装登记 + 验证发布。 -->
  <div class="panel">
    <div class="row spread">
      <h2>模板目录</h2>
      <span class="muted">{{ templates.length }} 个模板</span>
    </div>
    <el-skeleton v-if="busy && !templates.length" :rows="3" animated />
    <el-empty
      v-else-if="!templates.length"
      description="尚无接入模板；管理员可通过下方登记向导创建。"
    />
    <el-table v-else :data="templates" size="small">
      <el-table-column label="编码 / 名称" min-width="180">
        <template #default="{ row }">{{ row.code }} · {{ row.name }}</template>
      </el-table-column>
      <el-table-column label="种类" width="90">
        <template #default="{ row }">{{ row.kind }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">{{ row.status }}</template>
      </el-table-column>
      <el-table-column label="修订" width="80">
        <template #default="{ row }">r{{ row.currentRev }}</template>
      </el-table-column>
    </el-table>
  </div>
  <div class="panel">
    <h2>组装登记与验证发布</h2>
    <DraftAssemblyStep :draft="draft" @registered="registered = $event" />
    <VerifyPublishStep @published="registered = { code: $event, status: 'PUBLISHED', rev: registered?.rev ?? 1 }; verifyPublishedApplication($event)" />
    <p v-if="registered" class="muted" role="status">
      本会话已登记：{{ registered.code }} · {{ registered.status }} · r{{ registered.rev }}。
    </p>
  </div>
  <!-- ONB-06：发布后交接——独立助手入口与使用端首跑验证清单。 -->
  <div class="panel">
    <h2>使用端交接与首跑验证</h2>
    <AssistantLaunchStep
      :app-code="publishedApplication?.appCode || ''"
      :app-version="publishedApplication?.appVersion"
      :published="registered?.status === 'PUBLISHED'"
    />
    <p class="muted">
      交接内容：应用/模板精确版本、体验引用、使用范围与首跑清单；正式运行与再次消费使用当前授权身份，不复用接入人的高权身份。
    </p>
  </div>
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
      <DraftAssemblyStep :draft="draft" @registered="registered = $event" />
      <div class="preview-actions">
        <el-button :disabled="!payloadPreview" @click="copyPayload">复制登记 JSON</el-button>
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
</template>
<style scoped>
.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 1rem; }
.sha { word-break: break-all; }
.payload { max-height: 30vh; overflow: auto; white-space: pre-wrap; word-break: break-word; background: var(--el-fill-color-light, #f5f7fa); padding: .75rem; border-radius: .45rem; }
.preview-actions { display: flex; justify-content: flex-end; gap: .5rem; margin-top: .75rem; }
.fields-block { margin-top: 1rem; }
@media (max-width: 640px) { .field-grid { grid-template-columns: minmax(0, 1fr); } }
</style>
