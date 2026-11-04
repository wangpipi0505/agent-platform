<script setup lang="ts">
/** 集成目录沿用现有应用配置组件；不再要求手填发布UUID、摘要或稳定JSON。 */
import { ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import PageHeader from '../../PageHeader.vue';
import PageError from '../../PageError.vue';
import { usePageOperation, usePageTitle } from '../../usePageOperation';
import SemanticConfigurationStep from '../onboarding/SemanticConfigurationStep.vue';
const route = useRoute();
const { api, auth, busy, error, trace, operation } = usePageOperation();
const apps = ref<{
  code: string;
  name: string;
}[]>([]);
const appCode = ref('');
usePageTitle(() => '语义与目标');
watch(() => auth.generation, () => {
  apps.value = [];
  appCode.value = '';
  if (auth.token)
    void operation(async () => {
      apps.value = await api('/admin/apps');
      const requested = typeof route.query.appCode === 'string' ? route.query.appCode : '';
      appCode.value = apps.value.find(item => item.code === requested)?.code ?? apps.value[0]?.code ?? '';
    });
}, {
  immediate: true,
  flush: 'sync'
});
</script>
<template>
  <PageHeader title="语义与目标" subtitle="为当前应用选择正式对象、只读依据和业务成功判据" :busy="busy" />
  <PageError :error="error" :trace="trace" />
  <el-select v-model="appCode" placeholder="选择应用" aria-label="语义配置应用"><el-option v-for="item in apps" :key="item.code" :value="item.code" :label="`${item.name}（${item.code}）`" /></el-select>
  <SemanticConfigurationStep v-if="appCode" :key="appCode" :app-code="appCode" />
</template>
