<script setup lang="ts">
/** ONB-04：草稿组装登记——完整规范化草稿 + requestId 幂等提交（ADMIN）。
 * 同一 requestId + 同计划返回既有进度；同键不同计划 409 由服务端裁决。 */
import { computed, ref } from "vue";
import { usePageOperation } from "../../usePageOperation";
import type { OnboardingTemplateDraft } from "../../../../shared/onboarding";
import {
  buildOnboardingTemplatePayload,
  onboardingClient,
  validateOnboardingTemplateDraft,
} from "../../../../shared/onboarding";

const props = defineProps<{ draft: OnboardingTemplateDraft }>();
const emit = defineEmits<{ registered: [result: { code: string; status: string; rev: number }] }>();
const { auth, api, error } = usePageOperation();
const client = onboardingClient(api);
const submitting = ref(false);
const receipt = ref<{ code: string; status: string; rev: number }>();

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
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
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
