<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute } from "vue-router";
import PageHeader from "../PageHeader.vue";
import RunDetail from "../RunDetail.vue";
import { usePageTitle } from "../usePageOperation";

const route = useRoute();
const runId = computed(() => String(route.params.runId || ""));
usePageTitle(() => "运行详情");
const key = ref(0);
</script>

<style scoped>
.run-breadcrumb {
  margin-bottom: var(--sp-2);
}
</style>

<template>
  <!-- F2-3（方案 §5.6）：面包屑返回智能体工作区运行记录——本卡先指向既有运行列表
       /runs；智能体工作区 records 路由（/apps/{code}/records）属 F2-1 范围，与其解耦，
       该路由就绪后仅需把首项 to 换成运行记录路径。 -->
  <el-breadcrumb class="run-breadcrumb" separator="/">
    <el-breadcrumb-item :to="{ path: '/runs' }">运行中心</el-breadcrumb-item>
    <el-breadcrumb-item>运行详情</el-breadcrumb-item>
  </el-breadcrumb>
  <PageHeader
    title="运行详情"
    subtitle="运行状态、节点、制品与时间线。"
    @refresh="key++"
  />
  <RunDetail :key="runId + ':' + key" :run-id="runId" />
</template>
