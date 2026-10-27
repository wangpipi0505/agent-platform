<script setup lang="ts">
import { ref } from "vue";
import PageHeader from "../PageHeader.vue";
import RunList from "../RunList.vue";
import { usePageTitle } from "../usePageOperation";

usePageTitle(() => "运行监控");
const key = ref(0);
</script>

<template>
  <PageHeader
    title="运行监控"
    subtitle="连接业务能力，持续验证每一次运行。"
    @refresh="key++"
  />
  <RunList :key="key" />
</template>
