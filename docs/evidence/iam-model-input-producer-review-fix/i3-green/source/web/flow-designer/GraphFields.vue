<script setup lang="ts">
import { computed } from "vue";
const props = defineProps<{
  modelValue: any[];
  kind: "inputs" | "outputs";
  definition?: any;
  disabled?: boolean;
}>();
const emit = defineEmits<{ "update:modelValue": [value: any[]] }>();
const options = computed(() => [
  ...(props.definition?.inputs || []).map((f: any) => ({
    label: "输入 · " + f.name,
    value: "${input." + f.name + "}",
  })),
  ...(props.definition?.nodes || []).flatMap((n: any) => [
    {
      label: (n.name || n.id) + " · 全部输出",
      value: "${" + n.id + ".output}",
    },
    ...Object.keys(n.output || {}).map((k) => ({
      label: (n.name || n.id) + " · " + k,
      value: "${" + n.id + ".output." + k + "}",
    })),
  ]),
]);
function add() {
  emit("update:modelValue", [
    ...props.modelValue,
    props.kind === "inputs"
      ? { name: "", type: "STRING", required: true, desc: "" }
      : { name: "", from: "" },
  ]);
}
</script>
<template>
  <div>
    <div v-for="(field, i) in modelValue" :key="i" class="field-card">
      <div class="row">
        <el-input
          v-model="field.name"
          :disabled="disabled"
          :aria-label="
            (kind === 'inputs' ? '输入字段名称 ' : '输出字段名称 ') + (i + 1)
          "
          placeholder="字段名称"
        /><el-button
          v-if="!disabled"
          text
          type="danger"
          @click="
            emit(
              'update:modelValue',
              modelValue.filter((_, index) => index !== i),
            )
          "
          >移除字段</el-button
        >
      </div>
      <template v-if="kind === 'inputs'"
        ><div class="row">
          <el-select
            v-model="field.type"
            :disabled="disabled"
            :aria-label="'输入字段类型 ' + (i + 1)"
            ><el-option
              v-for="[v, label] in [
                ['STRING', '文本'],
                ['NUMBER', '数字'],
                ['BOOLEAN', '布尔'],
                ['OBJECT', '对象'],
                ['ARRAY', '数组'],
                ['NULL', '空值'],
              ]"
              :key="v"
              :value="v"
              :label="label" /></el-select
          ><el-checkbox v-model="field.required" :disabled="disabled"
            >必填</el-checkbox
          >
        </div>
        <el-input
          v-model="field.desc"
          :disabled="disabled"
          :aria-label="'字段说明 ' + (i + 1)"
          placeholder="给使用者的字段说明" /></template
      ><el-select
        v-else
        v-model="field.from"
        :disabled="disabled"
        filterable
        allow-create
        default-first-option
        :aria-label="'输出来源 ' + (i + 1)"
        placeholder="选择变量或填写表达式"
        ><el-option
          v-for="option in options"
          :key="option.value"
          :value="option.value"
          :label="option.label"
      /></el-select>
    </div>
    <el-button v-if="!disabled" @click="add">{{
      kind === "inputs" ? "添加输入字段" : "添加流程输出"
    }}</el-button>
  </div>
</template>
<style scoped>
.field-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 0.75rem;
  margin-bottom: 0.75rem;
}
.field-card .row {
  margin-bottom: 0.5rem;
}
</style>
