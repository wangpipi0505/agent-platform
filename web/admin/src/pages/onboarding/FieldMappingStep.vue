<script setup lang="ts">
/** ONB-03：受限映射——用户字段→实际参数只允许直接引用/常量，无任意表达式执行；
 * 映射缺失/未知字段/类型错误阻断并定位。 */
import { computed } from "vue";
import { validateFieldsBundle, type FieldsBundle } from "../../../../shared/onboarding-fields";

const props = defineProps<{ bundle: FieldsBundle }>();
const emit = defineEmits<{ update: [bundle: FieldsBundle] }>();

function setMapping(param: string, kind: "direct" | "constant", value: string) {
  const mappings = { ...props.bundle.mappings };
  if (kind === "direct") mappings[param] = { kind: "direct", from: value };
  else {
    // 常量保持类型：true/false/null/数字按字面收窄，其余为字符串。
    const literal =
      value === "true" ? true : value === "false" ? false : value === "null" ? null : /^-?\d+(\.\d+)?$/.test(value) ? Number(value) : value;
    mappings[param] = { kind: "constant", value: literal };
  }
  emit("update", { ...props.bundle, mappings });
}
function removeMapping(param: string) {
  const mappings = { ...props.bundle.mappings };
  delete mappings[param];
  emit("update", { ...props.bundle, mappings });
}
// 模板内不做联合收窄（不用 as）：辅助函数按 kind 收窄后返回展示值。
function mappingKind(param: string): string {
  const mapping = props.bundle.mappings[param];
  return mapping ? mapping.kind : "none";
}
function directFrom(param: string): string {
  const mapping = props.bundle.mappings[param];
  return mapping && mapping.kind === "direct" ? mapping.from : "";
}
function constantText(param: string): string {
  const mapping = props.bundle.mappings[param];
  return mapping && mapping.kind === "constant" ? String(mapping.value ?? "") : "";
}
const errors = computed(() => validateFieldsBundle(props.bundle));
const errorFor = (param: string) => errors.value.filter((item) => item.field === param);
</script>
<template>
  <div class="mapping-step">
    <p class="muted">
      每个实际参数可选“直接引用”另一字段或填常量；不支持表达式与脚本。必填字段缺少映射时阻断登记。
    </p>
    <el-table :data="bundle.fields" size="small">
      <el-table-column label="用户字段 → 实际参数" min-width="180">
        <template #default="{ row }">
          {{ row.label }}
          <code class="param">{{ row.param }}</code>
          <el-tag v-if="row.required" size="small" type="info" effect="plain">必填</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="映射方式" width="110">
        <template #default="{ row }">
          {{ mappingKind(row.param) === "constant" ? "常量" : mappingKind(row.param) === "direct" ? "直接引用" : "未映射" }}
        </template>
      </el-table-column>
      <el-table-column label="映射目标 / 常量" min-width="200">
        <template #default="{ row }">
          <el-select
            v-if="mappingKind(row.param) === 'direct'"
            :model-value="directFrom(row.param)"
            aria-label="映射来源"
            @update:model-value="(value: string) => setMapping(row.param, 'direct', value)"
          >
            <el-option v-for="field in bundle.fields" :key="field.param" :value="field.param" :label="field.label" />
          </el-select>
          <el-input
            v-else-if="mappingKind(row.param) === 'constant'"
            :model-value="constantText(row.param)"
            aria-label="常量值"
            placeholder="true / false / null / 数字 / 文本"
            @update:model-value="(value: string) => setMapping(row.param, 'constant', value)"
          />
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button text size="small" @click="setMapping(row.param, 'direct', row.param)">引用自身</el-button>
          <el-button text size="small" @click="setMapping(row.param, 'constant', '')">设常量</el-button>
          <el-button text size="small" type="danger" @click="removeMapping(row.param)">移除</el-button>
          <p v-for="item in errorFor(row.param)" :key="item.message" class="error-text" role="alert">
            {{ item.message }}
          </p>
        </template>
      </el-table-column>
    </el-table>
    <el-alert
      v-if="errors.length"
      type="warning"
      :closable="false"
      show-icon
      title="存在阻断问题"
      :description="`${errors.length} 项待修正（缺失/未知字段/类型错误逐条定位如上）；修正前无法进入下一步。`"
    />
  </div>
</template>
<style scoped>
.param { margin-left: .4rem; }
</style>
