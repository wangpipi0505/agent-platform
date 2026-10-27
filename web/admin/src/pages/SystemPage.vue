<script setup lang="ts">
import { ref } from "vue";
import PageHeader from "../PageHeader.vue";
import SystemStatus from "../SystemStatus.vue";
import { usePageTitle } from "../usePageOperation";

usePageTitle(() => "系统管理");
const key = ref(0);
</script>

<template>
  <PageHeader
    title="系统管理"
    subtitle="连接业务能力，持续验证每一次运行。"
    @refresh="key++"
  />
  <SystemStatus :key="key" />
</template>
