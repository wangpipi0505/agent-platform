<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import ResourceDetail from "../ResourceDetail.vue";
import SkillStatistics from "../SkillStatistics.vue";
import { usePageOperation, usePageTitle } from "../usePageOperation";

const route = useRoute();
const { api, busy, error, trace, operation } = usePageOperation();
const code = computed(() => String(route.params.code || ""));
const detail = ref<any>();
const key = ref(0);
usePageTitle(() => detail.value?.name || code.value || "技能详情");

async function load() {
  detail.value = undefined;
  if (!code.value) return;
  await operation(async () => {
    detail.value = await api(
      "/admin/skills/" + encodeURIComponent(code.value),
    );
  });
}
watch(code, () => void load(), { immediate: true });
</script>

<template>
  <PageHeader
    :title="detail?.name || code || '技能详情'"
    eyebrow="技能库"
    :subtitle="detail?.description || '查看技能定义、版本与执行统计。'"
    :busy="busy"
    @refresh="load"
  />
  <PageError :error="error" :trace="trace" />
  <template v-if="detail">
    <ResourceDetail :key="code + ':' + key" :resource="detail" kind="skill" />
    <SkillStatistics :code="detail.code" :versions="detail.versions" />
  </template>
</template>
