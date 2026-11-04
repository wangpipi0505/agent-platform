<script setup lang="ts">
/** ONB-03：返回呈现——modelSummary.tpl 摘要 + JSON 容器 cardTemplate + 受控产物路径；
 * 占位符只能引用已声明返回字段；高级区只读展示生成契约（专业编辑走原授权入口）。 */
import { computed } from "vue";
import {
  buildGeneratedContract,
  declaredReturnFields,
  validateFieldsBundle,
  type FieldsBundle,
} from "../../../../shared/onboarding-fields";

const props = defineProps<{ bundle: FieldsBundle }>();
const emit = defineEmits<{ update: [bundle: FieldsBundle] }>();

const returnFields = computed(() => declaredReturnFields(props.bundle.fields));
const errors = computed(() =>
  validateFieldsBundle(props.bundle).filter((item) =>
    ["summaryTemplate", "cardTemplate", "fileRefPaths"].includes(item.field),
  ),
);
function update(partial: Partial<FieldsBundle["returns"]>) {
  emit("update", { ...props.bundle, returns: { ...props.bundle.returns, ...partial } });
}
function addCard() {
  update({ cardFields: [...props.bundle.returns.cardFields, { label: "", from: returnFields.value[0] ?? "" }] });
}
function removeCard(index: number) {
  update({ cardFields: props.bundle.returns.cardFields.filter((_, i) => i !== index) });
}
</script>
<template>
  <div class="return-step">
    <el-form label-position="top">
      <el-form-item label="结果摘要模板（modelSummary.tpl，{{ 字段 }} 插值）" required>
        <el-input
          :model-value="bundle.returns.summaryTemplate"
          type="textarea"
          :rows="3"
          aria-label="结果摘要模板"
          placeholder="例如：查询完成，共 {{ total }} 条结果。"
          @update:model-value="(value: string) => update({ summaryTemplate: value })"
        />
        <p class="muted">
          可用返回字段：{{ returnFields.join("、") || "（先在业务字段中声明非文件字段）" }}。
        </p>
      </el-form-item>
      <el-form-item label="JSON 容器卡片">
        <el-checkbox
          :model-value="bundle.returns.cardTemplateEnabled"
          @update:model-value="(value: boolean) => update({ cardTemplateEnabled: value })"
          >启用结构化卡片</el-checkbox
        >
      </el-form-item>
      <template v-if="bundle.returns.cardTemplateEnabled">
        <div v-for="(card, index) in bundle.returns.cardFields" :key="index" class="card-row">
          <el-input
            :model-value="card.label"
            aria-label="展示名"
            placeholder="展示名"
            @update:model-value="(value: string) => { const next = [...bundle.returns.cardFields]; next[index] = { ...card, label: value }; update({ cardFields: next }); }"
          />
          <el-select
            :model-value="card.from"
            aria-label="返回字段引用"
            @update:model-value="(value: string) => { const next = [...bundle.returns.cardFields]; next[index] = { ...card, from: value }; update({ cardFields: next }); }"
          >
            <el-option v-for="name in returnFields" :key="name" :value="name" :label="name" />
          </el-select>
          <el-button text type="danger" @click="removeCard(index)">移除</el-button>
        </div>
        <el-button text @click="addCard">添加卡片字段</el-button>
      </template>
      <el-form-item label="受控产物引用路径（fileRefPaths，每行一个，只能引用返回字段）">
        <el-input
          :model-value="bundle.returns.fileRefPaths.join('\n')"
          type="textarea"
          :rows="2"
          aria-label="产物引用路径"
          @update:model-value="(value: string) => update({ fileRefPaths: value.split('\n') })"
        />
      </el-form-item>
    </el-form>
    <el-alert
      v-for="item in errors"
      :key="item.field + item.message"
      :title="item.message"
      type="warning"
      :closable="false"
      show-icon
    />
    <details class="contract-preview">
      <summary>高级：只读生成契约（专业编辑须进入原授权入口）</summary>
      <pre>{{ JSON.stringify(buildGeneratedContract(bundle), null, 2) }}</pre>
      <p class="muted">
        生成契约按现行权威方言（modelSummary.tpl + JSON 容器 cardTemplate + fileRefPaths）只读预览；
        本向导不提交 authRef、任意 endpoint 或超出模板字段的执行属性。
      </p>
    </details>
  </div>
</template>
<style scoped>
.card-row { display: grid; grid-template-columns: 1fr 1fr auto; gap: .5rem; margin-bottom: .5rem; }
.contract-preview { margin-top: 1rem; }
.contract-preview summary { cursor: pointer; color: var(--brand-primary, #0ea5e9); }
.contract-preview pre { max-height: 28vh; overflow: auto; white-space: pre-wrap; word-break: break-word; background: var(--el-fill-color-light, #f5f7fa); padding: .75rem; border-radius: .45rem; }
</style>
