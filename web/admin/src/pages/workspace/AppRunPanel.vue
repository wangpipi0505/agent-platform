<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import PageError from "../../PageError.vue";
import SchemaInputs from "../../../../shared/SchemaInputs.vue";
import { useAuth } from "../../../../shared/api";
import { uploadArtifact as uploadArtifactFile } from "../../../../shared/artifact-upload";
import { usePageOperation } from "../../usePageOperation";
import { parseJsonInput } from "../../../../shared/json-input";

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
// 壳层先挂空详情再异步载入应用，只在 setup 读一次会永远拿不到当前版本。
watch(
  () => [props.app?.currentVersionNo, props.versions?.length] as const,
  ([versionNo, count]) => {
    if (!form.value.versionNo && versionNo) form.value.versionNo = versionNo;
    else if (!form.value.versionNo && count) form.value.versionNo = props.versions[0].versionNo;
  },
  { immediate: true },
);

async function uploadArtifact(file: File): Promise<string> {
  // R08：统一走共享上传函数（顶层回包/明确OK信封），不再各自猜包装形状。
  return uploadArtifactFile(auth.token, file);
}

async function formalRun() {
  await operation(async () => {
    const result = await api(`/admin/apps/${props.code}/runs`, "POST", {
      inputs:
        runDeclaration.value !== undefined
          ? schemaForm.value!.collect()
          : parseJsonInput(json.value, "高级输入 JSON"),
      versionNo: form.value.versionNo || props.app.currentVersionNo,
      requestId: (requestNo.value ||= crypto.randomUUID()),
    });
    // F2-1：正式运行提交后跳工作区语境的独立记录页（可面包屑锚回「运行记录」锚点），
    // 不再跳全局 /runs/:runId（返回语境是运行监控）。
    void router.push(
      `/apps/${encodeURIComponent(props.code)}/records/${encodeURIComponent(result.runId)}`,
    );
  });
}
</script>

<template>
  <div class="panel form">
    <h2>执行发布版本</h2>
    <p class="muted">
      正式运行 = 选定一个已发布版本，按该版本声明的输入执行一次；提交后会生成运行记录并跳到执行详情。
    </p>
    <PageError :error="error" :trace="trace" />
    <el-alert
      v-if="!app.currentVersionNo"
      title="此应用尚未发布，请先完成候选评测和发布。"
      type="info"
      :closable="false"
    />
    <p class="muted">
      业务软件集成调用同一契约：<code>POST /v1/apps/{{ code }}/runs</code>，版本
      {{ form.versionNo || app.currentVersionNo }}，请求体
      <code>{ versionNo, inputs, requestId }</code>。
      <template v-if="app.type === 'FLOW'">流程型应用按编排节点顺序执行，输入仍由版本声明约束。</template>
    </p>
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
