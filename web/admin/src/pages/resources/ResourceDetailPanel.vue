<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{ detail: any }>();

const entries = computed(() => {
  const value = props.detail;
  if (!value || typeof value !== "object" || Array.isArray(value)) return [];
  return Object.entries(value)
    .filter(
      ([, item]) =>
        item == null || ["string", "number", "boolean"].includes(typeof item),
    )
    .slice(0, 16);
});
const nested = computed(
  () =>
    props.detail &&
    typeof props.detail === "object" &&
    Object.values(props.detail).some(
      (item) => item != null && typeof item === "object",
    ),
);
function valueText(value: any) {
  if (value == null || value === "") return "—";
  if (typeof value === "boolean") return value ? "是" : "否";
  const text = String(value);
  return text.length > 300 ? `${text.slice(0, 300)}…` : text;
}
</script>

<template>
  <div v-if="detail" class="panel">
    <h2>详情</h2>
    <p class="muted">
      先展示可直接阅读的字段；嵌套结构保留在原始响应中，便于核对服务端事实。
    </p>
    <el-descriptions v-if="entries.length" :column="2" border>
      <el-descriptions-item
        v-for="[key, value] in entries"
        :key="key"
        :label="key"
        >{{ valueText(value) }}</el-descriptions-item
      >
    </el-descriptions>
    <el-empty v-else description="没有可展示的字段" />
    <details v-if="nested">
      <summary>原始响应</summary>
      <pre>{{ JSON.stringify(detail, null, 2) }}</pre>
    </details>
  </div>
</template>
