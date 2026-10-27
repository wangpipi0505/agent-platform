<script setup lang="ts">
import { computed, ref } from "vue";
import { actionPlanView } from "../../../shared/approval";

/**
 * PLAT-15（契约 §14.5）：确认面板的行动计划卡。展示引擎盖章 actionPlan 的
 * summary/target/diff/risk/过期时间，让确认人看到"将要做什么"；高危（risk=HIGH）
 * 且确认角色不足两个时，勾选二次确认才能批准（执行期引擎还会复查，这里是前端闸）。
 */
const props = defineProps<{ row: any }>();
const emit = defineEmits<{ (e: "update:doubleConfirmed", value: boolean): void }>();
const doubleConfirmed = ref(false);
const view = computed(() => actionPlanView(props.row));
function confirmChanged(value: any) {
  doubleConfirmed.value = value === true;
  emit("update:doubleConfirmed", doubleConfirmed.value);
}
defineExpose({ doubleConfirmed, view });
</script>

<template>
  <div v-if="view" class="action-plan" data-test="action-plan">
    <el-alert
      v-if="view.highRisk"
      type="warning"
      :closable="false"
      show-icon
      title="高危写操作（删除/改状态/发 Key 类）：需要强确认后才会执行"
    />
    <el-descriptions :column="1" border size="small" class="action-plan-fields">
      <el-descriptions-item label="操作摘要">{{ view.summary }}</el-descriptions-item>
      <el-descriptions-item label="目标对象">{{
        view.target ?? "—"
      }}</el-descriptions-item>
      <el-descriptions-item label="变更内容">
        <pre class="action-plan-diff">{{ view.diffText }}</pre>
      </el-descriptions-item>
      <el-descriptions-item label="风险级别">
        <el-tag :type="view.highRisk ? 'danger' : 'info'" size="small">{{
          view.risk
        }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="执行授权过期">{{
        view.expiresAt ?? "—"
      }}</el-descriptions-item>
      <el-descriptions-item label="幂等键">{{
        view.idempotencyKey ?? "—"
      }}</el-descriptions-item>
    </el-descriptions>
    <el-checkbox
      v-if="view.secondConfirmRequired"
      :model-value="doubleConfirmed"
      data-test="double-confirm"
      @change="confirmChanged"
    >
      二次确认：我已知晓这是高危操作，批准后将按上述计划执行
    </el-checkbox>
  </div>
</template>

<style scoped>
.action-plan {
  display: grid;
  gap: 0.5rem;
  padding: 0.25rem 0;
}
.action-plan-diff {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
}
</style>
