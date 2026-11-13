<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import PageError from "../../PageError.vue";
import { usePageOperation } from "../../usePageOperation";
import { displayTime, statusLabel } from "../resources/resource-format";

const props = defineProps<{ appCode: string }>();
const { api, busy, error, trace, operation } = usePageOperation();
const items = ref<any[]>([]);

async function load() {
  if (!props.appCode) return;
  await operation(async () => {
    items.value = (
      await api<any[]>(
        `/admin/runs?appCode=${encodeURIComponent(props.appCode)}`,
      )
    ).filter(
      (run) =>
        run.appCode === props.appCode &&
        !["DEBUG", "EVALUATION"].includes(run.triggerType),
    );
  });
}
watch(() => props.appCode, () => void load());
onMounted(() => void load());
</script>

<template>
  <div class="panel">
    <PageError :error="error" :trace="trace" />
    <el-table v-loading="busy" :data="items">
      <el-table-column label="运行编码" min-width="260">
        <template #default="{ row }">
          <!-- F2-1：跳工作区语境的独立记录页（app-run-record），面包屑可锚回本面板；
               不再跳全局 /runs/:runId（其返回语境是运行监控）。 -->
          <a :href="'#/apps/' + encodeURIComponent(props.appCode) + '/records/' + encodeURIComponent(row.runId)">{{ row.runId }}</a>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          {{ row.statusLabel || statusLabel(row.status) }}
        </template>
      </el-table-column>
      <el-table-column label="开始时间" width="190">
        <template #default="{ row }">{{ displayTime(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column label="结束时间" width="190">
        <template #default="{ row }">{{ displayTime(row.endedAt) }}</template>
      </el-table-column>
      <template #empty>暂无正式运行记录。</template>
    </el-table>
  </div>
</template>
