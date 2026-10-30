<script setup lang="ts">
import { computed } from "vue";
import { actionPlanView } from "../../../shared/approval";

/**
 * PLAT-15（契约 §14.5）：确认面板的行动计划卡。展示引擎盖章 actionPlan 的
 * summary/target/diff/risk/过期时间，让确认人看到"将要做什么"。
 * 新 HIGH 展示服务端双主体进度；页面不收集第二人名字或以勾选代替另一份审批事实。
 */
const props = defineProps<{ row: any }>();
const view = computed(() => actionPlanView(props.row));
defineExpose({ view });
</script>

<template>
  <div v-if="view" class="action-plan" data-test="action-plan">
    <el-alert
      v-if="view.highRisk"
      type="warning"
      :closable="false"
      show-icon
      :title="view.requiresReprepare ? '旧高危计划需要重新发起调用与 PREPARE' : '高危写操作需要两名不同确认人分别批准'"
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
      <el-descriptions-item v-if="view.highRisk" label="确认政策">{{ view.policy }}</el-descriptions-item>
      <el-descriptions-item v-if="view.twoPrincipalsRequired" label="已确认人数">{{ view.confirmedCount === null ? '未记录' : `${view.confirmedCount} / 2` }}</el-descriptions-item>
      <el-descriptions-item label="执行授权过期">{{
        view.expiresAt ?? "—"
      }}</el-descriptions-item>
      <el-descriptions-item label="幂等键">{{
        view.idempotencyKey ?? "—"
      }}</el-descriptions-item>
    </el-descriptions>
    <p v-if="view.twoPrincipalsRequired" class="muted">每人只能计一次批准。第二人批准后，执行入口仍需重新核对两人的当前权限与计划有效期。</p>
    <p v-if="view.requiresReprepare" class="muted">原摘要、幂等键和历史结果保留；此计划不能使用客户端二次确认继续执行。</p>
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
