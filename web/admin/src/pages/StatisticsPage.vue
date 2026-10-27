<script setup lang="ts">
import { ref } from "vue";
import PageHeader from "../PageHeader.vue";
import StatisticsPanel from "../StatisticsPanel.vue";
import { usePageTitle } from "../usePageOperation";

usePageTitle(() => "统计分析");
const panel = ref<InstanceType<typeof StatisticsPanel>>();
</script>

<template>
  <PageHeader
    title="统计分析"
    subtitle="连接业务能力，持续验证每一次运行。"
    @refresh="panel?.refresh()"
  />
  <StatisticsPanel ref="panel" />
</template>
