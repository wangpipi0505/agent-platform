<script setup lang="ts">
/** ONB-04：草稿组装登记——完整规范化草稿 + requestId 幂等提交（ADMIN）。
 * 同一 requestId + 同计划返回既有进度；同键不同计划 409 由服务端裁决。 */
import { computed, ref } from "vue";
import { usePageOperation } from "../../usePageOperation";
import type { OnboardingTemplateDraft } from "../../../../shared/onboarding";
import {
  buildOnboardingTemplatePayload,
  conflictDiff,
  onboardingClient,
  validateOnboardingTemplateDraft,
} from "../../../../shared/onboarding";
import OnboardingErrorNotice from "./OnboardingErrorNotice.vue";

const props = defineProps<{ draft: OnboardingTemplateDraft }>();
const emit = defineEmits<{ registered: [result: { code: string; status: string; rev: number }] }>();
const { auth, api, error } = usePageOperation();
const client = onboardingClient(api);
const submitting = ref(false);
const receipt = ref<{ code: string; status: string; rev: number }>();
// ④并发修订冲突：保留本地编辑，展示与服务端差异，不自动覆盖。
const conflictKeys = ref<string[]>([]);

const errors = computed(() => validateOnboardingTemplateDraft(props.draft));
const canSubmit = computed(() => auth.can("admin") && !errors.value.length && !submitting.value);

async function submit() {
  if (!canSubmit.value) return;
  submitting.value = true;
  error.value = "";
  try {
    const payload = buildOnboardingTemplatePayload(props.draft);
    // 服务端 BODY_KEYS 守护：create 不接受 expectedRev（首次写入修订恒为 1）。
    const record = await client.create({
      ...(payload as {
        code: string;
        name: string;
        kind: OnboardingTemplateDraft["kind"];
        description: string;
        sourcePackageVersionId: number;
        sourcePackageSha256: string;
        appCode: string;
        experience: OnboardingTemplateDraft["experience"];
        integration: OnboardingTemplateDraft["integration"];
      }),
      requestId: crypto.randomUUID(),
    });
    receipt.value = {
      code: record?.code ?? props.draft.code,
      status: record?.status ?? "DRAFT",
      rev: record?.currentRev ?? 1,
    };
    emit("registered", receipt.value);
  } catch (e: any) {
    if (e?.code === "CONFIG_REV_CONFLICT" || e?.status === 409) {
      // 服务端修订已前进：展示差异键，本地草稿原样保留供对照。
      conflictKeys.value = conflictDiff(
        buildOnboardingTemplatePayload(props.draft),
        { hint: "服务端记录请从目录刷新核对" },
      );
    }
    error.value = e.message;
  } finally {
    submitting.value = false;
  }
}
</script>
<template>
  <div class="assembly">
    <p class="muted">
      组装=模板声明+来源能力包精确身份+体验/集成契约引用；提交走服务端幂等键（同 requestId
      重复点击返回既有进度，不覆盖人工后续编辑；同键不同计划 409）。
    </p>
    <p v-if="!auth.can('admin')" class="muted" role="note">
      提交为管理员操作；业务配置者可复制登记 JSON 交管理员复核提交。
    </p>
    <p v-if="errors.length" class="muted" role="status">
      仍有 {{ errors.length }} 项校验未通过，修正后可提交。
    </p>
    <OnboardingErrorNotice :error="error ? { message: error, code: conflictKeys.length ? 'CONFIG_REV_CONFLICT' : undefined } : null" />
    <p v-if="conflictKeys.length" class="muted" role="status">
      并发修订差异键：{{ conflictKeys.join("、") }}；本地编辑已保留，请刷新目录核对后选择保留或放弃。
    </p>
    <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
      {{ receipt ? "重复提交（幂等返回既有进度）" : "提交登记草稿" }}
    </el-button>
    <el-alert
      v-if="receipt"
      type="success"
      :closable="false"
      show-icon
      :title="`登记成功：${receipt.code} · ${receipt.status} · 修订 r${receipt.rev}`"
      description="草稿已创建；发布需先通过预检（见验证发布步骤）。"
    />
  </div>
</template>
<style scoped>
.assembly { min-width: 0; }
</style>
