<script setup lang="ts">
/** ONB-06：助手组装交接与使用端首跑——发布后引导至独立助手入口（webapp，
 * 与 AppPublishPanel 同源推导）；首跑验证清单逐项人工核验；内嵌交付标注“待集成”。 */
import { computed, ref, watch } from "vue";
import {
  assistantEntryUrl,
  ONBOARDING_LAUNCH_CHECKS,
  ONBOARDING_UNDELIVERED,
} from "../../../../shared/onboarding";

const props = defineProps<{
  /** 已发布/已登记的目标应用编码（来自组装登记回执或目录选择）。 */
  appCode: string
  /** 服务端实际应用发布号；模板修订号不能代替应用版本。 */
  appVersion?: number
  /** 服务端是否已确认实际应用发布；模板PUBLISHED本身不能作为应用交接事实。 */
  published: boolean
  /** 可选的 webapp 基址覆盖（测试/特殊部署注入）。 */
  webappBase?: string
}>();

const entryUrl = computed(() => assistantEntryUrl(props.appCode, props.webappBase));
const checked = ref<Record<string, boolean>>({});
watch(() => [props.appCode, props.appVersion], () => { checked.value = {}; });
const allChecked = computed(() =>
  ONBOARDING_LAUNCH_CHECKS.every((check) => checked.value[check.item]),
);
</script>
<template>
  <div class="launch">
    <p v-if="!published || !appCode" class="muted" role="note">
      尚无已发布的模板与应用：完成登记并通过预检发布后，此处才显示正式使用端入口（不能将草稿视为交付完成）。
    </p>
    <template v-else>
      <p>
        独立助手入口（正式使用端）：
        <a :href="entryUrl" target="_blank" rel="noopener">{{ entryUrl }}</a>
        · 应用编码 <code>{{ appCode }}</code><span v-if="appVersion"> · 本次发布回执版本 {{ appVersion }}</span>
      </p>
      <p class="muted">此入口默认打开应用当前发布版本；本次发布回执版本仅用于核对交接，链接未固定历史版本。</p>
      <p class="muted">业务内嵌交付（SDK 宿主按钮）：待集成——宿主 open/close/updateContext 接口已交付，业务侧按钮接线按各自系统集成。</p>
      <h3>使用端首跑验证清单</h3>
      <el-table :data="ONBOARDING_LAUNCH_CHECKS" size="small">
        <el-table-column label="核验" width="70">
          <template #default="{ row }">
            <el-checkbox v-model="checked[row.item]" :aria-label="row.item" />
          </template>
        </el-table-column>
        <el-table-column label="项目" width="140">
          <template #default="{ row }">{{ row.item }}</template>
        </el-table-column>
        <el-table-column label="预期" min-width="260">
          <template #default="{ row }">{{ row.expectation }}</template>
        </el-table-column>
        <el-table-column label="位置" width="120">
          <template #default="{ row }">{{ row.where }}</template>
        </el-table-column>
      </el-table>
      <p class="muted" role="status">
        清单进度：{{ Object.values(checked).filter(Boolean).length }} / {{ ONBOARDING_LAUNCH_CHECKS.length }}
        {{ allChecked ? "——首跑验证完成，可交付使用。" : "——全部通过后再行交付；只读调用应真实执行，失败原样展示。" }}
      </p>
      <details>
        <summary>未交付项登记（如实标注，不以占位代替）</summary>
        <ul>
          <li v-for="item in ONBOARDING_UNDELIVERED" :key="item">{{ item }}</li>
        </ul>
      </details>
    </template>
  </div>
</template>
<style scoped>
.launch { min-width: 0; }
</style>
