<script setup lang="ts">
import { computed } from 'vue';
import {
  MODE_META,
  SAFETY_HINT,
  assertionOptions,
  canUseAssertionMode,
  type AssertionDraft,
  type AssertionMode,
} from './assertion';
const props = defineProps<{
  draft: AssertionDraft;
  safety: boolean;
  disabled?: boolean;
  notice?: string;
}>();
const emit = defineEmits<{ 'update:draft': [draft: AssertionDraft] }>();
const options = computed(() => assertionOptions(props.safety));
const meta = computed(() => MODE_META[props.draft.mode]);
const safetyBlocked = computed(() => props.safety && !canUseAssertionMode(props.draft.mode, props.safety));
function patch(change: Partial<AssertionDraft>) {
  emit('update:draft', { ...props.draft, ...change });
}
function setMode(value: AssertionMode) {
  // 选项已在安全用例下禁用；这里再拦一次，保证程序化赋值同样不能绕过。
  if (props.safety && !canUseAssertionMode(value, props.safety)) return;
  patch({ mode: value });
}
function setExpected(value: string | number) {
  patch({ expectedText: String(value ?? '') });
}
function setTolerance(value: string | number) {
  patch({ tolerance: String(value ?? '') });
}
</script>
<template>
  <div class="assertion-editor">
    <el-form label-position="top">
      <el-form-item label="断言模式" required>
        <el-select :model-value="draft.mode" aria-label="断言模式" :disabled="disabled" @update:model-value="setMode">
          <el-option v-for="option in options" :key="option.value" :value="option.value" :label="option.label" :disabled="option.disabled" />
        </el-select>
      </el-form-item>
      <p v-if="safety" class="assertion-note" role="status">{{ SAFETY_HINT }}（当前仅 EXACT / SCHEMA 可选）。</p>
      <p v-if="notice" class="assertion-note" role="status">{{ notice }}</p>
      <p v-if="safetyBlocked" class="assertion-warning" role="alert">当前模式 {{ draft.mode }} 不能用于安全用例，请改选 EXACT 或 SCHEMA。</p>
      <p class="assertion-hint">{{ meta.hint }}</p>
      <el-form-item :label="meta.expectedLabel" required>
        <el-input
          v-if="meta.expectedKind === 'number'"
          :model-value="draft.expectedText"
          :disabled="disabled"
          type="number"
          inputmode="decimal"
          :aria-label="meta.expectedLabel"
          placeholder="例如 0.5"
          @update:model-value="setExpected"
        />
        <el-input
          v-else-if="meta.expectedKind === 'pattern'"
          :model-value="draft.expectedText"
          :disabled="disabled"
          aria-label="正则表达式（pattern）"
          placeholder="例如 ^[A-Z]{3}-\d{4}$（整串匹配）"
          @update:model-value="setExpected"
        />
        <el-input
          v-else-if="meta.expectedKind === 'text'"
          :model-value="draft.expectedText"
          type="textarea"
          :rows="3"
          :disabled="disabled"
          :aria-label="meta.expectedLabel"
          @update:model-value="setExpected"
        />
        <el-input
          v-else
          :model-value="draft.expectedText"
          type="textarea"
          :rows="6"
          :disabled="disabled"
          :aria-label="meta.expectedLabel"
          :placeholder="meta.expectedKind === 'schema' ? '例如：包含 type 与 required 的 JSON Schema' : '请独立填写期望值'"
          @update:model-value="setExpected"
        />
      </el-form-item>
      <el-form-item v-if="meta.expectedKind === 'number'" label="容差（tolerance，非负数）" required>
        <el-input
          :model-value="draft.tolerance"
          :disabled="disabled"
          type="number"
          min="0"
          inputmode="decimal"
          aria-label="容差"
          placeholder="例如 0.05"
          @update:model-value="setTolerance"
        />
      </el-form-item>
    </el-form>
  </div>
</template>
<style scoped>
.assertion-editor{display:block}
.assertion-hint{margin:-.25rem 0 .75rem;color:var(--el-text-color-secondary);font-size:12px;line-height:1.5}
.assertion-note{margin:.25rem 0;color:var(--el-color-primary);font-size:12px;line-height:1.5}
.assertion-warning{margin:.25rem 0;color:var(--el-color-danger);font-size:12px;line-height:1.5}
</style>
