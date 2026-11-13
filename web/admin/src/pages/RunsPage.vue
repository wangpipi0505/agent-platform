<script setup lang="ts">
import { ref } from "vue";
import RunList from "../RunList.vue";
import RunUsage from "../RunUsage.vue";
import { usePageTitle } from "../usePageOperation";

usePageTitle(() => "运行监控");
const key = ref(0);
</script>

<template>
  <!-- F1-3 样板页①（方案 §7.1 表格页四段高度模型）：
       页头/筛选/表格滚动体/分页四段收敛在 RunList 内的 DataPage；
       Token 用量汇总为页底折叠区（RunUsage details 默认收起，展开时限高 45% 独立滚动——
       同侧栏菜单超长独立滚动的既定模式），不与表体滚动叠加成三级滚动。 -->
  <div class="runs-page">
    <RunList
      :key="key"
      class="runs-list"
      title="运行监控"
      subtitle="连接业务能力，持续验证每一次运行。"
      @refresh="key++"
    />
    <RunUsage :key="`usage-${key}`" class="runs-usage" />
  </div>
</template>

<style scoped>
.runs-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
}
.runs-list {
  flex: 1;
  min-height: 0;
}
.runs-usage {
  flex-shrink: 0;
}
.runs-usage[open] {
  max-height: 45%;
  min-height: 0;
  overflow-y: auto;
}
</style>
