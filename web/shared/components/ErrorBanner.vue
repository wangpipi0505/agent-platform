<script setup lang="ts">
// F1-3（台账 §7.5 / 方案 §4 ErrorBanner 行）：页头下方吸顶错误条。
// 替代 PageError 在页面中的位置问题——sticky 吸顶，长页滚动仍可见；traceId 折叠保留。
defineProps<{ error?: string; trace?: string }>();
</script>

<template>
  <div v-if="error" class="error-banner" role="alert">
    <svg
      class="error-banner-icon"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.7"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5v5.5" />
      <circle cx="12" cy="16.4" r="0.6" fill="currentColor" stroke="none" />
    </svg>
    <div class="error-banner-body">
      <strong>{{ error }}</strong>
      <p class="error-banner-hint">请检查输入与服务状态后重试；业务数据以服务端为准。</p>
      <details v-if="trace">
        <summary>追踪信息</summary>
        {{ trace }}
      </details>
    </div>
    <!-- 可选动作区（如重试按钮） -->
    <div v-if="$slots.action" class="error-banner-action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
/* 吸顶（方案 §4）：sticky + 不透明表面底，长页滚动时错误仍可见；
   放入 DataPage 页头段时本就常驻，sticky 仅为独立长页用法生效 */
.error-banner {
  position: sticky;
  top: 0;
  z-index: 15;
  display: flex;
  align-items: flex-start;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid color-mix(in srgb, var(--st-fail) 45%, transparent);
  border-radius: var(--radius);
  background: color-mix(in srgb, var(--st-fail) 8%, var(--surface));
}
.error-banner-icon {
  width: 1.25rem;
  height: 1.25rem;
  flex-shrink: 0;
  margin-top: 0.1rem;
  color: var(--st-fail);
}
.error-banner-body {
  min-width: 0;
}
.error-banner-body strong {
  overflow-wrap: anywhere;
}
.error-banner-hint {
  margin: var(--sp-1) 0 0;
  color: var(--muted);
  font-size: var(--text-meta);
}
.error-banner-body details {
  margin-top: var(--sp-1);
  font-size: var(--text-meta);
  color: var(--muted);
}
.error-banner-body summary {
  cursor: pointer;
}
.error-banner-action {
  margin-left: auto;
  flex-shrink: 0;
}
</style>
