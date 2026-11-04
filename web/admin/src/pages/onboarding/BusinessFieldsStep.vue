<script setup lang="ts">
/** ONB-03：业务字段表单——按模板声明生成标签/说明/单位/枚举/文件控件；
 * false/0/null/数组根保持原值不改写；上传读取顶层 artifactId（坏回包不伪装成功）。 */
import { reactive, ref, watch } from "vue";
import { collectFieldValues, type BusinessFieldDef } from "../../../../shared/onboarding-fields";

const props = defineProps<{
  fields: BusinessFieldDef[];
  /** 服务端持久会话的原始业务值，恢复时保持false/0/null，不用空默认覆盖。 */
  initialValues?: Record<string, unknown>;
  disabled?: boolean;
  uploadArtifact?: (file: File) => Promise<string>;
}>();
const emit = defineEmits<{
  values: [values: Record<string, unknown>];
}>();
const values = reactive<Record<string, unknown>>({});
const uploadState = reactive<Record<string, boolean>>({});
const uploadIssue = ref("");

function collected() {
  const result = collectFieldValues(props.fields, values);
  // 接入会话恢复时显式null是原始业务输入，数值控件也不能把它省略为“未提供”。
  for (const field of props.fields) {
    if (Object.prototype.hasOwnProperty.call(values, field.param) && values[field.param] === null)
      result[field.param] = null;
  }
  return result;
}

watch(
  () => [props.fields, props.initialValues] as const,
  ([fields, initial]) => {
    for (const key of Object.keys(values)) if (!fields.some((field) => field.param === key)) delete values[key];
    for (const field of fields)
      if (initial && Object.prototype.hasOwnProperty.call(initial, field.param)) values[field.param] = initial[field.param];
      else if (!(field.param in values)) values[field.param] = field.kind === "boolean" ? null : "";
    emit("values", collected());
  },
  { immediate: true, deep: true },
);
watch(values, () => emit("values", collected()), { deep: true });

async function pickFile(param: string, event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file || props.disabled) return;
  if (!props.uploadArtifact) {
    uploadIssue.value = "当前页面未提供文件上传入口，请填写已有 artifactId";
    input.value = "";
    return;
  }
  uploadState[param] = true;
  uploadIssue.value = "";
  try {
    // R08：共享上传函数返回顶层 artifactId；坏回包直接报错，不伪装成功。
    values[param] = await props.uploadArtifact(file);
  } catch (e: any) {
    uploadIssue.value = e?.message || "文件上传失败";
  } finally {
    uploadState[param] = false;
    input.value = "";
  }
}
</script>
<template>
  <el-form label-position="top" class="business-fields" :disabled="props.disabled">
    <el-form-item
      v-for="field in fields"
      :key="field.param"
      :label="field.label + (field.unit ? `（${field.unit}）` : '')"
      :required="field.required"
    >
      <el-select v-if="field.kind === 'enum'" v-model="values[field.param]" clearable>
        <el-option v-for="item in field.enumValues ?? []" :key="item" :value="item" :label="item" />
      </el-select>
      <el-select v-else-if="field.kind === 'boolean'" v-model="values[field.param]" :empty-values="[undefined]">
        <el-option :value="true" label="是（true）" />
        <el-option :value="false" label="否（false）" />
        <el-option :value="null" label="未提供（null）" />
      </el-select>
      <el-input-number
        v-else-if="field.kind === 'number'"
        v-model="values[field.param]"
        :aria-label="field.param"
      />
      <div v-else-if="field.kind === 'file'" class="file-input">
        <input
          type="file"
          :aria-label="field.param"
          :disabled="props.disabled || !uploadArtifact || uploadState[field.param]"
          @change="pickFile(field.param, $event)"
        />
        <el-input
          v-model="values[field.param]"
          :aria-label="field.param + ' artifactId'"
          placeholder="上传后自动填入顶层 artifactId，也可粘贴已有 ID"
        />
      </div>
      <el-input v-else v-model="values[field.param]" :aria-label="field.param" />
      <p v-if="field.description" class="muted field-desc">{{ field.description }}</p>
    </el-form-item>
    <p v-if="uploadIssue" class="error-text" role="alert">{{ uploadIssue }}</p>
    <p class="muted">布尔/数值/null 与数组输入按原类型提交；空字符串且非必填的字段不会发送。</p>
  </el-form>
</template>
<style scoped>
.file-input { display: flex; flex-direction: column; gap: .4rem; width: 100%; }
.field-desc { margin: .2rem 0 0; }
</style>
