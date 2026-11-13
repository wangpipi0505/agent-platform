<script setup lang="ts">
import { computed } from "vue";

// F1-3（台账 §7.5 / 方案 §4 EmptyState 行）：三态空态 + 主 CTA 按钮。
// 替代无 CTA 的 el-empty；高度 ≤240px 居中，图标 48 + 文案 + CTA（方案 §7.2 空态行）。
const props = withDefaults(
  defineProps<{
    /** error=服务错误（刷新重试）/ filtered=有筛选无匹配（清除筛选）/ create=引导创建 */
    variant?: "error" | "filtered" | "create";
    description?: string;
    /** CTA 文案；传空串隐藏按钮，缺省按变体给默认动作文案 */
    actionText?: string;
  }>(),
  { variant: "create" },
);
const emit = defineEmits<{ (e: "action"): void }>();

const defaults = computed(
  () =>
    ({
      error: {
        text: "服务暂不可用，请刷新重试",
        action: "刷新重试",
      },
      filtered: {
        text: "没有匹配的记录，请调整筛选条件",
        action: "清除筛选",
      },
      create: {
        text: "暂无记录",
        action: "新建",
      },
    }) as Record<
      "error" | "filtered" | "create",
      { text: string; action: string }
    >,
);
const text = computed(() => props.description || defaults.value[props.variant].text);
const button = computed(() =>
  props.actionText === "" ? "" : props.actionText || defaults.value[props.variant].action,
);

// 内联 SVG（48px，stroke 走 currentColor），不引入新依赖
const icons = computed(
  () =>
    ({
      // 服务错误：断线警示
      error:
        '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5"/><circle cx="12" cy="16.4" r="0.6" fill="currentColor" stroke="none"/>',
      // 无匹配：漏斗
      filtered:
        '<path d="M4 5h16l-6.2 7.2v5.3L10.2 20v-7.8L4 5z"/>',
      // 引导创建：加号圆角框
      create:
        '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M12 8.5v7M8.5 12h7"/>',
    }) as Record<"error" | "filtered" | "create", string>,
);
</script>

<template>
  <div class="empty-state" :class="`is-${variant}`">
    <svg
      class="empty-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.6"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      v-html="icons[variant]"
    ></svg>
    <p class="empty-text">{{ text }}</p>
    <el-button
      v-if="button"
      :type="variant === 'filtered' ? 'default' : 'primary'"
      @click="emit('action')"
      >{{ button }}</el-button
    >
  </div>
</template>

<style scoped>
/* 方案 §7.2 空态：高度 ≤240px，居中，图标 48 + 文案 + CTA；间距只用 --sp-* */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sp-3);
  padding: var(--sp-5) var(--sp-4);
  max-height: 240px;
  text-align: center;
}
.empty-icon {
  width: 48px;
  height: 48px;
  color: var(--text-tertiary);
}
.is-error .empty-icon {
  color: var(--st-fail);
}
.empty-text {
  margin: 0;
  color: var(--text-secondary);
  font-size: var(--text-body);
}
</style>
