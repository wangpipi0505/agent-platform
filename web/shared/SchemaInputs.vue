<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { inputFields, buildDeclaredInputs } from "./input-schema";
const props = defineProps<{
  declaration: any;
  uploadArtifact?: (file: File) => Promise<string>;
}>();
const emit = defineEmits<{ change: [] }>();
const values = ref<Record<string, any>>({});
const issue = ref("");
const uploading = ref<Record<string, boolean>>({});
const fields = computed(() => inputFields(props.declaration));
watch(
  () => props.declaration,
  () => {
    values.value = Object.fromEntries(
      fields.value
        .filter((f) => f.default !== undefined)
        .map((f) => [f.name, f.default]),
    );
    issue.value = "";
  },
  { deep: true, immediate: true },
);
async function pickFile(name: string, event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (!props.uploadArtifact) {
    issue.value = "当前页面未提供文件上传入口，请填写已有 artifactId";
    input.value = "";
    return;
  }
  if (file.size > 64 * 1024 * 1024) {
    issue.value = "文件超过 64MB 上限";
    input.value = "";
    return;
  }
  uploading.value = { ...uploading.value, [name]: true };
  issue.value = "";
  try {
    values.value[name] = await props.uploadArtifact(file);
    ElMessage.success(`已上传 ${file.name}`);
  } catch (e: any) {
    issue.value = e?.message || "文件上传失败";
  } finally {
    uploading.value = { ...uploading.value, [name]: false };
    input.value = "";
  }
}
function collect() {
  try {
    const result = buildDeclaredInputs(props.declaration, values.value);
    issue.value = "";
    return result;
  } catch (e: any) {
    issue.value = e.message;
    throw e;
  }
}
function replace(input: Record<string, any>) {
  // Keep complex values editable as JSON while preserving the existing collect contract.
  values.value = Object.fromEntries(Object.entries(input).map(([key, value]) =>
    [key, value !== null && typeof value === 'object' ? JSON.stringify(value, null, 2) : value]));
  issue.value = '';
}
watch(values, () => emit('change'), { deep: true, flush: 'sync' });
defineExpose({ collect, replace });
</script>
<template>
  <el-form label-position="top"
    ><el-form-item
      v-for="f in fields"
      :key="f.name"
      :label="f.title || f.description || f.name"
      :required="f.required"
      ><el-select
        v-if="f.enum"
        v-model="values[f.name]"
        :aria-label="f.name"
        clearable
        ><el-option
          v-for="v in f.enum"
          :key="String(v)"
          :value="v"
          :label="String(v)" /></el-select
      ><el-input-number
        v-else-if="['number', 'integer'].includes(f.type)"
        v-model="values[f.name]"
        :precision="f.type === 'integer' ? 0 : undefined"
        :min="f.minimum"
        :max="f.maximum"
        :aria-label="f.name"
      /><el-select
        v-else-if="f.type === 'boolean'"
        v-model="values[f.name]"
        :aria-label="f.name"
        clearable
        ><el-option label="是" :value="true" /><el-option
          label="否"
          :value="false" /></el-select
      ><div v-else-if="f.format === 'binary'" class="artifact-input"
        ><input
          type="file"
          :aria-label="f.name"
          :disabled="!uploadArtifact || uploading[f.name]"
          @change="pickFile(f.name, $event)"
        /><el-input
          v-model="values[f.name]"
          :aria-label="f.name"
          :placeholder="uploadArtifact ? '上传后自动填入 artifactId，也可粘贴已有 ID' : '填写已有 artifactId'"
        /><span v-if="uploading[f.name]" class="muted">上传中…</span
        ><span v-else-if="!uploadArtifact" class="muted"
          >此入口只接受已有产物标识；请在运行页上传文件。</span
        ></div
      ><el-input
        v-else-if="['object', 'array'].includes(f.type)"
        v-model="values[f.name]"
        :aria-label="f.name"
        type="textarea"
        :rows="4"
        :placeholder="
          f.type === 'array' ? '数组 JSON，例如 [1,2]' : '对象 JSON'
        "
      /><span v-else-if="f.type === 'null'" class="muted">固定空值 null</span
      ><el-input
        v-else
        v-model="values[f.name]"
        :aria-label="f.name"
        :maxlength="f.maxLength"
        :placeholder="f.name"
      /><span class="muted">{{ f.name }} · {{ f.type }}</span></el-form-item
    ></el-form
  >
  <pre v-if="issue" class="error" role="alert">{{ issue }}</pre>
</template>
