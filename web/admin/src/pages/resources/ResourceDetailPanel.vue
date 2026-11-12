<script setup lang="ts">
import { computed } from "vue";
import { displayTime, statusLabel } from "./resource-format";

const props = defineProps<{ detail: any }>();

// 详情面板展示服务端原始字段，界面统一用中文标签；未登记的字段保留原键名，避免藏信息。
const LABELS: Record<string, string> = {
  code: "编码",
  name: "名称",
  status: "状态",
  currentVersion: "当前版本",
  updatedAt: "更新时间",
  createdAt: "创建时间",
  publishedAt: "发布时间",
  publishedBy: "发布人",
  description: "说明",
  kind: "类型",
  version: "版本",
  content: "模板内容",
  variables: "变量",
  sha256: "摘要",
  inputSchema: "输入结构",
  outputSchema: "输出结构",
  method: "方法",
  path: "路径",
  baseUrl: "基地址",
  timeoutMs: "超时（毫秒）",
  executionCount: "调用次数",
  successRate: "成功率",
  averageDurationMs: "平均耗时（毫秒）",
};
const labelOf = (key: string) => LABELS[key] ?? key;

const entries = computed(() => {
  const value = props.detail;
  if (!value || typeof value !== "object" || Array.isArray(value)) return [];
  return Object.entries(value)
    .filter(([key, item]) => key !== "content" && key !== "versions")
    .filter(
      ([, item]) =>
        item == null || ["string", "number", "boolean"].includes(typeof item),
    )
    .slice(0, 16);
});
const versions = computed(() =>
  Array.isArray(props.detail?.versions) ? props.detail.versions : [],
);
const nested = computed(
  () =>
    props.detail &&
    typeof props.detail === "object" &&
    Object.values(props.detail).some(
      (item) => item != null && typeof item === "object",
    ),
);
function valueText(value: any, key = "") {
  if (value == null || value === "") return "—";
  if (typeof value === "boolean") return value ? "是" : "否";
  if (key === "status") return statusLabel(String(value));
  if (/(At|Time)$/.test(key)) return displayTime(value);
  const text = String(value);
  return text.length > 300 ? `${text.slice(0, 300)}…` : text;
}
</script>

<template>
  <div v-if="detail" class="panel">
    <h2>详情</h2>
    <p class="muted">
      展示服务端返回的可读字段；完整原始响应保留在下方折叠区，便于核对事实。
    </p>
    <el-descriptions v-if="entries.length" :column="2" border>
      <el-descriptions-item
        v-for="[key, value] in entries"
        :key="key"
        :label="labelOf(key)"
        >{{ valueText(value, key) }}</el-descriptions-item
      >
    </el-descriptions>
    <el-empty v-else description="没有可展示的字段" />

    <template v-if="detail.content">
      <h3>模板内容</h3>
      <pre class="template-content">{{ detail.content }}</pre>
    </template>

    <template v-if="versions.length">
      <h3>版本记录</h3>
      <el-table :data="versions" size="small" stripe>
        <el-table-column prop="version" label="版本" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ statusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column prop="content" label="内容" show-overflow-tooltip />
        <el-table-column prop="publishedAt" label="发布时间" width="180" />
      </el-table>
    </template>

    <details v-if="nested">
      <summary>原始响应</summary>
      <pre>{{ JSON.stringify(detail, null, 2) }}</pre>
    </details>
  </div>
</template>

<style scoped>
h3 {
  margin: 1.25rem 0 0.5rem;
  font-size: var(--text-body);
  font-weight: 600;
}
.template-content {
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface-2);
  color: var(--text);
  font-family: inherit;
  font-size: var(--text-table);
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
