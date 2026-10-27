<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { useApi } from "../../../../shared/api";
import EvaluationPanel from "../../EvaluationPanel.vue";
import {
  WAIVER_MAX_HOURS,
  buildWaiverPayload,
  shortSha,
  summarizeGateState,
  type GateRejection,
  type PublishPrecheck,
  type WaiverView,
} from "../../publish-gate";

const props = defineProps<{
  app: any;
  code: string;
  busy: boolean;
  precheck?: PublishPrecheck | null;
  precheckStale: boolean;
  published: any;
  evaluation: any;
  evaluationWorking: boolean;
  evaluationNeedsCheck: boolean;
  /** 预检 409 EVAL_GATE_BLOCKED 时由页面捕获并传入；body 不含 checks 明细（只落审计行）。 */
  gateBlocked?: GateRejection | null;
  /** 仅控制前端可见性；豁免申请与撤销最终由后端 requireRole("ADMIN") 校验。 */
  isAdmin?: boolean;
}>();
const emit = defineEmits<{
  (e: "check"): void;
  (e: "commit"): void;
  (e: "report", value: any): void;
  (e: "activity", working: boolean): void;
  (e: "bind"): void;
}>();

const api = useApi();
// 豁免无查询端点（GET 列表未实现）：会话内授予/撤销的豁免留在本地展示，
// 历史豁免只能经预检检查项带回的 waiverId/expiresAt 显示。
const sessionWaiver = ref<WaiverView | null>(null);
const waiverForm = reactive({ reason: "", hours: WAIVER_MAX_HOURS });
const waiverBusy = ref(false);
const waiverError = ref("");

const gate = computed(() =>
  summarizeGateState({
    precheck: props.precheck,
    rejection: props.gateBlocked,
    sessionWaiver: sessionWaiver.value,
    candidate: {
      draftRev: props.app?.draftRev,
      candidateSha256: props.app?.candidateSha256,
    },
    isAdmin: props.isAdmin,
  }),
);

const waivedSummary = computed(() => {
  const check = gate.value.gateCheck;
  if (!check || check.result !== "WAIVED") return "";
  const parts = [check.detail || "ADMIN 显式豁免生效中"];
  if (gate.value.waiver) parts.push(`${gate.value.waiver.remainingText}（到期 ${gate.value.waiver.expiresAt}）`);
  return parts.join(" · ");
});

/** 危险操作：豁免是带审计的风险接受，先确认再请求；失败保留表单便于修复重试。 */
async function applyWaiver() {
  if (waiverBusy.value) return;
  if (!props.isAdmin) {
    waiverError.value = "仅 ADMIN 可申请豁免；当前账号无权操作，请联系管理员处理。";
    return;
  }
  if (!gate.value.blocked) {
    waiverError.value = "当前没有评测门禁拦截，无需申请豁免。";
    return;
  }
  if (
    !confirm(
      "确认豁免发布门禁？豁免最长 24 小时、仅覆盖评测门禁，绑定当前候选摘要，撤销或到期立即失效。",
    )
  )
    return;
  waiverBusy.value = true;
  waiverError.value = "";
  try {
    const payload = buildWaiverPayload(
      {
        reason: waiverForm.reason,
        hours: waiverForm.hours,
        draftRev: props.app?.draftRev,
        candidateSha256: props.app?.candidateSha256,
      },
      Date.now(),
    );
    sessionWaiver.value = await api<WaiverView>(
      `/admin/apps/${encodeURIComponent(props.code)}/publish-waivers`,
      "POST",
      payload,
    );
    waiverForm.reason = "";
    emit("check");
  } catch (e) {
    waiverError.value = e instanceof Error ? e.message : "豁免申请失败，请稍后重试";
  } finally {
    waiverBusy.value = false;
  }
}

async function revokeWaiver() {
  const target = sessionWaiver.value?.id ?? gate.value.waiver?.id;
  if (!target || waiverBusy.value) return;
  if (!confirm("确认撤销该豁免？撤销后评测门禁立即恢复约束。")) return;
  waiverBusy.value = true;
  waiverError.value = "";
  try {
    sessionWaiver.value = await api<WaiverView>(
      `/admin/apps/${encodeURIComponent(props.code)}/publish-waivers/${encodeURIComponent(target)}`,
      "DELETE",
    );
    emit("check");
  } catch (e) {
    waiverError.value = e instanceof Error ? e.message : "撤销失败，请稍后重试";
  } finally {
    waiverBusy.value = false;
  }
}

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
      <div v-if="gate.modeInfo" style="margin-top: 0.75rem">
        <el-tag :type="gate.modeInfo.tone" effect="dark">评测门禁：{{ gate.modeInfo.label }}</el-tag>
        <span class="muted" style="margin-left: 0.5rem">{{ gate.modeInfo.description }}</span>
      </div>
      <el-alert
        v-for="(warning, index) in gate.warnings"
        :key="index"
        :title="warning"
        type="warning"
        :closable="false"
        show-icon
        style="margin-top: 0.75rem"
      />
      <el-alert
        v-if="gate.gateCheck?.result === 'WAIVED'"
        type="warning"
        :closable="false"
        show-icon
        title="评测门禁已豁免：这是豁免，不是通过"
        :description="waivedSummary"
        style="margin-top: 0.75rem"
      />
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
      <div v-if="gate.failing.length && !gate.blocked" class="error" role="alert">
        {{ gate.applyHint }}
      </div>
      <div
        v-if="gate.blocked"
        class="panel"
        role="alert"
        style="margin-top: 1rem; border: 1px solid var(--el-color-danger)"
      >
        <h2>发布被评测门禁拦截</h2>
        <div class="error">
          {{ gate.blocked.code }}：{{ gate.blocked.message
          }}<span v-if="gate.blocked.traceId"> · traceId {{ gate.blocked.traceId }}</span>
        </div>
        <dl style="margin: 0.75rem 0">
          <div>
            <dt>为何被拦</dt>
            <dd>
              评测门禁要求当前候选存在满足安全100%、通过率及无新增回归的评测报告；断言级“期望与实际”明细请在下方“2.
              运行候选评测”的报告区查看，预检 409 响应只返回错误码与摘要。
            </dd>
          </div>
          <div>
            <dt>谁能放行</dt>
            <dd>{{ gate.applyHint }}</dd>
          </div>
          <div>
            <dt>放行多久</dt>
            <dd>
              豁免有效期最长 {{ WAIVER_MAX_HOURS }} 小时，仅绑定当前候选：rev
              {{ app?.draftRev ?? "未知" }} · sha
              <span :title="app?.candidateSha256">{{ shortSha(app?.candidateSha256 || "") || "未知" }}</span
              >；到期或撤销后立即恢复阻断，提交发布时会再次复核豁免。
            </dd>
          </div>
        </dl>
        <el-button :loading="busy" @click="emit('check')">重新运行发布预检</el-button>
        <div v-if="gate.canApplyWaiver" style="margin-top: 1rem">
          <h3>申请豁免（仅 ADMIN）</h3>
          <el-input
            v-model="waiverForm.reason"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            placeholder="豁免理由（10-512 字，将计入审计日志）"
            aria-label="豁免理由"
          />
          <div style="margin-top: 0.5rem">
            有效期（小时，最长 {{ WAIVER_MAX_HOURS }}）：
            <el-input-number
              v-model="waiverForm.hours"
              :min="1"
              :max="WAIVER_MAX_HOURS"
              :step="1"
              step-strictly
              aria-label="豁免有效期小时数"
            />
          </div>
          <div v-if="waiverError" class="error" role="alert">{{ waiverError }}</div>
          <el-button type="warning" :loading="waiverBusy" style="margin-top: 0.5rem" @click="applyWaiver"
            >确认豁免并重新预检</el-button
          >
          <p class="muted">
            豁免是带审计的风险接受，不改变评测报告状态；提交后自动重新预检以生效。
          </p>
        </div>
      </div>
      <div v-if="gate.waiver" style="margin-top: 1rem">
        <h3>当前豁免</h3>
        <p>
          豁免 {{ gate.waiver.id }} ·
          {{
            gate.waiver.status === "ACTIVE"
              ? "生效中"
              : gate.waiver.status === "REVOKED"
                ? "已撤销"
                : "已到期"
          }}
          · {{ gate.waiver.remainingText }}
        </p>
        <p class="muted">{{ gate.waiver.reason }}</p>
        <p class="muted">到期时间：{{ gate.waiver.expiresAt }}</p>
        <div v-if="gate.waiver.bindingMismatch" class="error" role="alert">
          该豁免绑定的草稿修订/候选摘要与当前候选不一致，重新预检不会命中，请重新申请豁免。
        </div>
        <el-button
          v-if="isAdmin && gate.waiver.status === 'ACTIVE'"
          :loading="waiverBusy"
          @click="revokeWaiver"
          >撤销豁免</el-button
        >
      </div>
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
