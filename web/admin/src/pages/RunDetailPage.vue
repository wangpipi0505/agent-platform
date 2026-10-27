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

<template>
  <PageHeader
    title="运行详情"
    subtitle="运行状态、节点、制品与时间线。"
    @refresh="key++"
  />
  <RunDetail :key="runId + ':' + key" :run-id="runId" />
</template>
