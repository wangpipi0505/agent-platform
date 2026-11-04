<script setup lang="ts">
/** ONB-05：接入流程错误提示——服务端错误码映射可读文案+建议动作；
 * 阻塞类（撤权/停用/过期会话）可解释，不诱导无效重试。 */
import { computed } from "vue";
import { mapOnboardingError } from "../../../../shared/onboarding";

const props = defineProps<{ error: unknown }>();
const view = computed(() => (props.error ? mapOnboardingError(props.error) : undefined));
</script>
<template>
  <el-alert
    v-if="view"
    :type="view.blocking ? 'warning' : 'error'"
    :closable="false"
    show-icon
    :title="`${view.message}${view.code !== 'UNKNOWN' ? `（${view.code}）` : ''}`"
  >
    <p class="action">{{ view.action }}</p>
    <p v-if="view.blocking" class="muted blocking-note">
      该问题属于身份或状态阻塞，重试不会解决；请先完成上述动作。
    </p>
    <p v-else-if="view.recoverable" class="muted">可直接重试；已填内容已保留。</p>
  </el-alert>
</template>
<style scoped>
.action { margin: .2rem 0; }
.blocking-note { margin: .15rem 0 0; }
</style>
