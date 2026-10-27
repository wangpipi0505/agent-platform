<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import PageError from "../../PageError.vue";
import SchemaInputs from "../../../../shared/SchemaInputs.vue";
import { useAuth } from "../../../../shared/api";
import { usePageOperation } from "../../usePageOperation";

const props = defineProps<{
  app: any;
  code: string;
  versions: any[];
}>();
const router = useRouter();
const { api, busy, error, trace, operation, failure } = usePageOperation();
const auth = useAuth();
const json = ref("{}");
const form = ref<any>({ versionNo: props.app.currentVersionNo });
const publishedInput = ref<any>();
const schemaForm = ref<InstanceType<typeof SchemaInputs>>();
const requestNo = ref("");
const runDeclaration = computed(
  () => publishedInput.value?.inputSchema ?? publishedInput.value?.inputs,
);

async function loadPublishedInput() {
  publishedInput.value = undefined;
  if (!form.value.versionNo) return;
  const requestedCode = props.code;
  const requestedVersion = form.value.versionNo;
  try {
    const result = await api(
      `/v1/apps/${encodeURIComponent(props.code)}/input-schema?versionNo=${form.value.versionNo}`,
    );
    if (
      props.code === requestedCode &&
      form.value.versionNo === requestedVersion
    )
      publishedInput.value = result;
  } catch (e) {
    failure(e);
  }
}
watch(() => form.value.versionNo, () => void loadPublishedInput(), {
  immediate: true,
});

async function uploadArtifact(file: File): Promise<string> {
  // Multipart must keep the browser boundary; the JSON api helper cannot be reused here.
  const body = new FormData();
  body.append("file", file);
  const response = await fetch("/admin/artifacts", {
    method: "POST",
    headers: { Authorization: `Bearer ${auth.token}` },
    body,
  });
  const envelope = await response.json().catch(() => ({}));
  if (!response.ok || envelope.code !== "OK")
    throw new Error(envelope.message || `文件上传失败 (${response.status})`);
  return envelope.data.artifactId;
}

async function formalRun() {
  await operation(async () => {
    const result = await api(`/admin/apps/${props.code}/runs`, "POST", {
      inputs:
        runDeclaration.value !== undefined
          ? schemaForm.value!.collect()
          : JSON.parse(json.value),
      versionNo: form.value.versionNo || props.app.currentVersionNo,
      requestId: (requestNo.value ||= crypto.randomUUID()),
    });
    void router.push(`/runs/${result.runId}`);
  });
}
</script>

<template>
  <div class="panel form">
    <h2>执行发布版本</h2>
    <PageError :error="error" :trace="trace" />
    <el-alert
      v-if="!app.currentVersionNo"
      title="此应用尚未发布，请先完成候选评测和发布。"
      type="info"
      :closable="false"
    />
    <el-form label-position="top">
      <el-form-item label="运行版本">
        <el-select v-model="form.versionNo" placeholder="默认当前版本">
          <el-option
            v-for="version in versions"
            :key="version.versionNo"
            :value="version.versionNo"
            :label="String(version.versionNo)"
          />
        </el-select>
      </el-form-item>
      <SchemaInputs
        v-if="runDeclaration !== undefined"
        :key="form.versionNo"
        ref="schemaForm"
        :declaration="runDeclaration"
        :upload-artifact="uploadArtifact"
      />
      <el-form-item v-else label="高级输入 JSON（此版本未声明输入 Schema）">
        <el-input v-model="json" type="textarea" :rows="8" />
      </el-form-item>
    </el-form>
    <el-button
      type="primary"
      :disabled="
        !app.currentVersionNo ||
        ['DISABLED', 'ARCHIVED'].includes(app.status) ||
        !publishedInput
      "
      :loading="busy"
      @click="formalRun"
      >提交运行</el-button
    >
  </div>
</template>
