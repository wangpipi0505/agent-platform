<script setup lang="ts">
/**
 * F2-1：智能体运行记录独立页（/apps/:code/records/:runId，访问键 app-run-record）。
 * 运行详情从工作区 tab 内嵌跳出为独立路由：详情内容仍复用 RunDetail 单一实现（不复制页面），
 * 页头带「返回工作区运行记录锚点」面包屑——排查失败运行的返回路径不再绕道全局运行监控。
 * 设计稿 §5.2 面包屑口径：智能体 / code / 运行记录 / runId。
 */
import { computed, ref } from "vue";
import { useRoute } from "vue-router";
import RunDetail from "../RunDetail.vue";
import { usePageTitle } from "../usePageOperation";

const route = useRoute();
const code = computed(() => String(route.params.code || ""));
const runId = computed(() => String(route.params.runId || ""));
usePageTitle(() => `运行记录 ${runId.value}`);
// 刷新由 RunDetail 自带数据重载承接；key 保留 runId 维度，避免跨 run 残留状态。
const key = ref(0);
</script>

<template>
  <nav class="record-crumb" aria-label="面包屑">
    <a :href="'#/apps/' + code">智能体工作区</a>
    <span class="crumb-sep" aria-hidden="true">/</span>
    <a :href="'#/apps/' + code + '/runs'">运行记录</a>
    <span class="crumb-sep" aria-hidden="true">/</span>
    <strong class="font-mono">{{ runId }}</strong>
  </nav>
  <RunDetail :key="runId + ':' + key" :run-id="runId" />
</template>

<style scoped>
/* 面包屑只用 tokens 变量；不新增页面级滚动容器（详情内容滚动由 RunDetail 自身区块承担）。 */
.record-crumb {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-bottom: var(--sp-4);
  font-size: var(--text-meta);
  color: var(--muted);
}
.crumb-sep {
  color: var(--muted);
}
</style>
