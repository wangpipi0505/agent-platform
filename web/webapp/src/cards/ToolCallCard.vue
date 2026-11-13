<script setup lang="ts">
/** F4-3 工具调用卡：默认折叠（名称/参数摘要/状态），点击展开参数与结果（前端稿 §5.4）。 */
import { ref } from "vue";
import type { ToolCallView } from "../demo-flow";

const props = defineProps<{ view: ToolCallView }>();
const open = ref(false);
const toggle = () => {
  open.value = !open.value;
};
const pretty = (value: unknown) => JSON.stringify(value, null, 2);
const tone = (state: string) =>
  state === "SUCCEEDED" ? "st-ok" : state === "FAILED" ? "st-fail" : "st-run";
</script>
<template>
  <section class="tool-card" data-kind="tool-call">
    <button
      class="tool-head"
      type="button"
      :aria-expanded="open"
      @click="toggle"
    >
      <span class="caret">{{ open ? "▾" : "▸" }}</span>
      <span class="tool-name font-mono">{{ props.view.name }}</span>
      <span class="tool-summary">{{ props.view.paramsSummary }}</span>
      <span :class="['tool-state', tone(props.view.state)]">{{
        props.view.state
      }}</span>
    </button>
    <div v-if="open" class="tool-body">
      <div class="tool-block">
        <small>参数</small>
        <pre class="font-mono">{{ pretty(props.view.params) }}</pre>
      </div>
      <div v-if="props.view.result" class="tool-block">
        <small>结果</small>
        <pre class="font-mono">{{ props.view.result }}</pre>
      </div>
    </div>
  </section>
</template>
<style scoped>
.tool-card {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  margin: var(--sp-2) 0;
  overflow: hidden;
}
.tool-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  background: var(--surface-2);
  border: none;
  color: var(--ink);
  cursor: pointer;
  text-align: left;
  font-size: var(--text-meta);
}
.tool-head .caret {
  color: var(--muted);
}
.tool-name {
  color: var(--ink);
}
.tool-summary {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--muted);
}
.tool-state {
  flex-shrink: 0;
  padding: 0 var(--sp-2);
  border-radius: 999px;
  font-size: var(--text-meta);
}
.st-ok {
  color: var(--st-ok-text);
  background: color-mix(in srgb, var(--st-ok) 18%, transparent);
}
.st-run {
  color: var(--st-run-text);
  background: color-mix(in srgb, var(--st-run) 18%, transparent);
}
.st-fail {
  color: var(--st-fail-text);
  background: color-mix(in srgb, var(--st-fail) 18%, transparent);
}
.tool-body {
  padding: var(--sp-2) var(--sp-3);
  border-top: 1px solid var(--line);
  display: grid;
  gap: var(--sp-2);
}
.tool-block small {
  color: var(--muted);
}
pre {
  margin: var(--sp-1) 0 0;
  font-size: var(--text-meta);
  color: var(--text-secondary, var(--muted));
  overflow-x: auto;
}
</style>
