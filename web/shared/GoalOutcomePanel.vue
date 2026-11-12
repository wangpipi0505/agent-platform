<script setup lang="ts">
/**
 * SEM-08/09 目标处置卡：区分执行终态、业务效果与目标处置。
 * ACHIEVED 必须带 call 回执；缺回执降级 UNKNOWN 并列出问题，不把“请求成功”当“目标达成”。
 */
import { computed } from "vue";
import { goalOutcomeRows, type GoalOutcomeLike } from "./semantic-runtime";

const props = defineProps<{
  goals?: readonly GoalOutcomeLike[];
  runStatus?: string;
  budget?: { maxGoals?: number; verifiedGoals?: number; truncated?: boolean } | null;
}>();
const rows = computed(() => goalOutcomeRows(props.goals));
const tone = (state: string) =>
  state === "ACHIEVED" ? "success" : state === "FAILED" ? "danger" : state === "NOT_ACHIEVED" ? "info" : "warning";
</script>

<template>
  <section class="goal-outcomes">
    <header>
      <strong>目标处置</strong>
      <span v-if="props.runStatus" class="muted">运行终态：{{ props.runStatus }}（不等于目标达成）</span>
    </header>
    <p v-if="!rows.length" class="muted">该运行未声明目标判据。</p>
    <el-table v-else :data="rows" size="small" row-key="goalId">
      <el-table-column prop="goalId" label="目标" min-width="140" />
      <el-table-column label="处置" width="180">
        <template #default="{ row }">
          <el-tag :type="tone(row.outcomeState)" size="small">{{ row.label }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="依据 / 问题" min-width="240">
        <template #default="{ row }">
          <ul class="reasons">
            <li v-for="ref in row.evidence" :key="ref.callId || ref.recordId || ref.artifactId">
              回执 <code>{{ ref.callId || ref.recordId || ref.artifactId }}</code>
            </li>
            <li v-for="issue in row.issues" :key="issue" class="bad">{{ issue }}</li>
            <li v-if="!row.evidence.length && !row.issues.length" class="muted">尚无业务回执</li>
          </ul>
        </template>
      </el-table-column>
      <el-table-column prop="nextStep" label="下一步" min-width="200" />
    </el-table>
    <p v-if="props.budget?.truncated" class="muted">
      预算上限 {{ props.budget.maxGoals }} 项；本次已核验 {{ props.budget.verifiedGoals }} 项，超出部分未纳入。
    </p>
  </section>
</template>

<style scoped>
.goal-outcomes { display: grid; gap: 10px; }
header { display: flex; gap: 12px; align-items: baseline; }
.reasons { margin: 0; padding-left: 16px; }
.reasons .bad { color: var(--el-color-danger); }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
</style>
