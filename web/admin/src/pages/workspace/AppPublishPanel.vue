<script setup lang="ts">
import EvaluationPanel from "../../EvaluationPanel.vue";

defineProps<{
  app: any;
  code: string;
  busy: boolean;
  precheck: any;
  precheckStale: boolean;
  published: any;
  evaluation: any;
  evaluationWorking: boolean;
  evaluationNeedsCheck: boolean;
}>();
const emit = defineEmits<{
  (e: "check"): void;
  (e: "commit"): void;
  (e: "report", value: any): void;
  (e: "activity", working: boolean): void;
  (e: "bind"): void;
}>();

const env = (import.meta as any).env || {};
const webappBase = (() => {
  const configured = env.VITE_WEBAPP_BASE_URL as string | undefined;
  if (configured) return configured.replace(/\/+$/, "");
  if (typeof window !== "undefined")
    return `${window.location.protocol}//${window.location.hostname}:15174`;
  return "http://127.0.0.1:15174";
})();
</script>

<template>
  <div>
    <el-steps
      :active="published ? 3 : precheck ? 1 : 0"
      finish-status="success"
      style="margin-bottom: 1.5rem"
    >
      <el-step title="候选预检" />
      <el-step title="候选评测" />
      <el-step title="确认发布" />
      <el-step title="集成调用" />
    </el-steps>
    <div class="panel">
      <h2>1. 检查当前草稿</h2>
      <p class="muted">预检不会产生版本。配置发生变化时，请重新预检并评测。</p>
      <el-button
        :type="!precheck ? 'primary' : 'default'"
        :loading="busy"
        v-permission="'edit'"
        @click="emit('check')"
        >运行发布预检</el-button
      >
      <div v-if="precheckStale && precheck" class="error" role="alert">
        配置已变更，当前预检结果已过期；请重新运行发布预检后再发布。
      </div>
      <el-table
        v-if="precheck"
        :data="precheck.report?.checks || []"
        style="margin-top: 1rem"
      >
        <el-table-column prop="group" label="检查组" />
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <span :class="['status', row.result]">{{ row.result }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="问题与建议" min-width="300" />
      </el-table>
      <el-collapse v-if="precheck">
        <el-collapse-item title="候选摘要与预检标识">
          <pre>{{
            JSON.stringify(
              {
                precheckId: precheck.precheckId,
                draftRev: precheck.draftRev,
                candidateSha256: precheck.candidateSha256,
                evalReportId: precheck.evalReportId,
              },
              null,
              2,
            )
          }}</pre>
        </el-collapse-item>
      </el-collapse>
    </div>
    <EvaluationPanel
      :key="code"
      :app-code="code"
      :draft-rev="app.draftRev"
      :candidate="precheck"
      @report="emit('report', $event)"
      @activity="emit('activity', $event)"
      @bind="emit('bind')"
    />
    <div v-if="precheck" class="panel">
      <h2>3. 确认发布</h2>
      <p class="muted">
        服务端检查评测报告与候选摘要一致性。错误或过期报告将阻断发布。
      </p>
      <el-button
        :type="precheck.passed ? 'primary' : 'default'"
        :loading="busy"
        :disabled="
          !precheck.passed ||
          evaluationWorking ||
          evaluationNeedsCheck ||
          (!!evaluation && evaluation.status !== 'ACCEPTED')
        "
        v-permission="'edit'"
        @click="emit('commit')"
        >确认发布版本</el-button
      >
    </div>
    <div v-if="published" class="panel">
      <h2>集成四件套</h2>
      <pre>{{ JSON.stringify(published, null, 2) }}</pre>
      <p>
        对话地址：<a :href="`${webappBase}/w/${encodeURIComponent(code)}`" target="_blank"
          >打开业务对话</a
        >
      </p>
      <p class="muted">
        嵌入票据必须由业务系统后端签发，禁止在页面配置服务密钥。
      </p>
    </div>
  </div>
</template>
