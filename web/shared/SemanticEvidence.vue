<script setup lang="ts">
/**
 * SEM-09 权威证据卡：完成/失败只按服务端回执呈现。
 * 模型文本仅作说明；若它声称成功而服务端未确认，页面必须显式标注“已忽略”。
 */
import { computed } from "vue";
import { authoritativeView, type ServerOutcome } from "./semantic-runtime";

const props = defineProps<{
  server?: ServerOutcome | null;
  modelText?: string;
  title?: string;
}>();
const view = computed(() => authoritativeView(props.server, props.modelText));
const tone = computed(() =>
  view.value.state === "SUCCEEDED"
    ? "success"
    : view.value.state === "FAILED" || view.value.state === "UNVERIFIED"
      ? "danger"
      : "warning",
);
</script>

<template>
  <section class="semantic-evidence" aria-live="polite">
    <header>
      <strong>{{ props.title || "权威结果" }}</strong>
      <el-tag :type="tone" size="small">{{ view.state }}</el-tag>
    </header>
    <p v-if="view.note" class="note">{{ view.note }}</p>
    <el-alert
      v-if="view.modelTextIgnored"
      type="warning"
      :closable="false"
      show-icon
      title="模型文本与权威结果不一致，已忽略"
      description="完成与否只认服务端事实与回执；模型声称的成功不会覆盖此卡片。"
    />
    <div v-if="view.evidence.length" class="evidence">
      <span class="muted">依据</span>
      <ul>
        <li v-for="(ref, index) in view.evidence" :key="index">
          <template v-if="ref.evidenceKind === 'call'">
            调用回执 <code>{{ ref.callId }}</code>
            <span v-if="ref.runId">（run {{ ref.runId }}）</span>
          </template>
          <template v-else-if="ref.evidenceKind === 'artifact'">
            产物 <code>{{ ref.artifactId }}</code>
            <span v-if="ref.digest"> 摘要 {{ ref.digest }}</span>
          </template>
          <template v-else>
            业务记录 {{ ref.recordSource }} / <code>{{ ref.recordId }}</code>
          </template>
        </li>
      </ul>
    </div>
    <p v-else class="muted">没有可核验的回执；这不是“已完成”。</p>
  </section>
</template>

<style scoped>
.semantic-evidence { display: grid; gap: 10px; padding: 14px; border: 1px solid var(--el-border-color); border-radius: 8px; }
header { display: flex; justify-content: space-between; align-items: center; }
.note { margin: 0; }
.evidence ul { margin: 6px 0 0; padding-left: 18px; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
.el-alert { margin: 0; }
</style>
