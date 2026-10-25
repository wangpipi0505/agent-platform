<script setup lang="ts">
import { computed } from "vue";
import type { MappingRow } from "./node-metadata";
const props = defineProps<{
  modelValue: MappingRow[];
  definition: any;
  output?: boolean;
  disabled?: boolean;
}>();
const emit = defineEmits<{ "update:modelValue": [value: MappingRow[]] }>();
const variables = computed(() => [
  ...(props.definition.inputs || []).map((f: any) => ({
    label: "输入 · " + (f.desc || f.name),
    value: "${input." + f.name + "}",
  })),
  ...(props.definition.nodes || []).flatMap((n: any) => [
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
    {
      key: "",
      source: "literal",
      type: "STRING",
      value: props.output ? "$" : "",
    },
  ]);
}
</script>
<template>
  <div>
    <div v-for="(row, i) in modelValue" :key="i" class="mapping-row">
      <el-input
        v-model="row.key"
        :disabled="disabled"
        :aria-label="(output ? '输出' : '参数') + '名称 ' + (i + 1)"
        placeholder="名称"
      />
      <div class="mapping-value">
        <el-select
          v-if="!output"
          v-model="row.source"
          :disabled="disabled"
          :aria-label="'值来源 ' + (i + 1)"
          ><el-option label="固定值" value="literal" /><el-option
            label="变量引用"
            value="variable" /></el-select
        ><el-select
          v-if="!output && row.source === 'literal'"
          v-model="row.type"
          :disabled="disabled"
          :aria-label="'值类型 ' + (i + 1)"
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
        ><el-select
          v-if="row.source === 'variable' && !output"
          v-model="row.value"
          :disabled="disabled"
          filterable
          allow-create
          default-first-option
          :aria-label="'变量 ' + (i + 1)"
          placeholder="选择运行输入或节点输出"
          ><el-option
            v-for="v in variables"
            :key="v.value"
            :value="v.value"
            :label="v.label" /></el-select
        ><el-select
          v-else-if="row.type === 'BOOLEAN' && !output"
          v-model="row.value"
          :disabled="disabled"
          :aria-label="'布尔值 ' + (i + 1)"
          ><el-option label="是" value="true" /><el-option
            label="否"
            value="false" /></el-select
        ><el-input
          v-else-if="output || row.type !== 'NULL'"
          v-model="row.value"
          :disabled="disabled"
          :type="
            ['OBJECT', 'ARRAY'].includes(row.type) && !output
              ? 'textarea'
              : 'text'
          "
          :aria-label="(output ? 'JSONPath' : '参数值') + ' ' + (i + 1)"
          :placeholder="output ? '$ 或 $.text' : '填写固定值'"
        />
      </div>
      <el-button
        v-if="!disabled"
        text
        type="danger"
        :aria-label="'移除映射 ' + (i + 1)"
        @click="
          emit(
            'update:modelValue',
            modelValue.filter((_, index) => index !== i),
          )
        "
        >移除</el-button
      >
    </div>
    <el-button v-if="!disabled" @click="add">{{
      output ? "添加输出映射" : "添加参数"
    }}</el-button>
  </div>
</template>
<style scoped>
.mapping-row {
  display: grid;
  grid-template-columns: minmax(5rem, 0.7fr) minmax(0, 2fr) auto;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}
.mapping-value {
  display: flex;
  gap: 0.375rem;
  flex-wrap: wrap;
}
.mapping-value > * {
  flex: 1;
  min-width: 5rem;
}
.mapping-value > .el-input,
.mapping-value > .el-textarea {
  flex-basis: 100%;
}
</style>
