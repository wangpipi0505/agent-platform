<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useApi } from "../../../../shared/api";
import EvaluationPanel from "../../EvaluationPanel.vue";
const frontendDemoMode=import.meta.env.VITE_MOCK==='true';
import type {
  AppDetail,
  EvaluationSnapshot,
  PublishedPointer,
} from "../../../../shared/resource-types";
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
  app: AppDetail;
  code: string;
  busy: boolean;
  precheck?: PublishPrecheck | null;
  precheckStale: boolean;
  published: PublishedPointer | null;
  evaluation: EvaluationSnapshot | null;
  evaluationWorking: boolean;
  evaluationNeedsCheck: boolean;
  /** 预检 409 EVAL_GATE_BLOCKED 时由页面捕获并传入；body 不含 checks 明细（只落审计行）。 */
  gateBlocked?: GateRejection | null;
  /** 仅控制前端可见性；豁免申请与撤销最终由后端 requireRole("ADMIN") 校验。 */
  isAdmin?: boolean;
  environmentConfirmed?: boolean;
}>();
const emit = defineEmits<{
  (e: "check"): void;
  (e: "commit"): void;
  (e: "report", value: unknown): void;
  (e: "activity", working: boolean): void;
  (e: "bind"): void;
  (e: "environment-confirm", confirmed: boolean): void;
}>();

const api = useApi();
// INT-06：交接区只展示服务端已激活的配置版本；浏览器提议的 configReleaseId 由引擎复核，
// 页面既不缓存密钥也不生成明文凭据。
const activeRelease = ref<Record<string, unknown> | null>(null);
const releaseError = ref("");
const releaseBusy = ref(false);

async function loadActiveRelease() {
  // 交接区只在已发布版本存在时出现；未发布应用不探测配置版本，也不产生额外管理端调用。
  if (!props.published) {
    activeRelease.value = null;
    releaseError.value = "";
    releaseBusy.value = false;
    return;
  }
  releaseBusy.value = true;
  releaseError.value = "";
  try {
    const list = await api<any[]>(
      `/admin/config-releases?appCode=${encodeURIComponent(props.code)}`,
    );
    activeRelease.value = (Array.isArray(list) ? list : []).find(
      (row: any) => row?.status === "ACTIVE",
    ) ?? null;
  } catch (e) {
    activeRelease.value = null;
    releaseError.value = e instanceof Error ? e.message : "读取激活配置版本失败";
  } finally {
    releaseBusy.value = false;
  }
}

onMounted(loadActiveRelease);
watch(() => [props.code, props.published], loadActiveRelease);

const handoff = computed(() => {
  const release = activeRelease.value;
  if (!release) return "";
  const scope = release.activationScope || "production";
  return [
    "// 1) 业务后端用自身凭据换取 300 秒运行短票（凭据只保存在后端）",
    "POST {业务后端}/embed/ticket",
    "Authorization: ApiKey <业务系统专属凭据>",
    "Origin: https://<业务系统域名>",
    "Content-Type: application/json",
    '{"appCode":"' + props.code + '","userId":"<已登录用户>"}',
    "",
    "// 2) 会话创建时提议配置版本；引擎按激活版本复核后才固定到运行事实",
    "POST /v1/apps/" + props.code + "/sessions",
    "Authorization: Bearer <短票>",
    '{"configReleaseId":"' + release.releaseId + '"}',
    "",
    "// 3) 轮次沿用会话固定版本，不得再换版本",
    "POST /v1/apps/" + props.code + "/sessions/{sessionId}/turns",
    '{"message":"…","requestId":"<一次性请求号>"}',
    "",
    "// 激活配置版本：" + release.releaseId,
    "// 配置摘要：" + (release.digest || "未知"),
    "// 环境作用域：" + scope,
  ].join("\n");
});

// INT-07：标准结果事件示例与契约 §4 一致；示例只展示数据面，不生成回调地址或密钥。
const hostEventExample = computed(() => {
  return [
    "const handle = window.AgentPlatformAssistant.init({",
    "  endpoint, appCode, ticket,",
    '  integrationId: "int_' + props.code + '",',
    "  onHostEvent(event) {",
    '    // event.schemaVersion === "1.0"；SDK 已校验白名单字段与大小',
    '    if (event.event === "result") {',
    "      // 只把验证后的业务记录引用交给宿主自己的路由反查",
    "      for (const ref of event.businessRecordRefs ?? []) {",
    "        routeToBusiness(ref.source, ref.recordId);",
    "      }",
    "    }",
    "  },",
    "});",
    "",
    "// 重复终态、未知事件、伪造 origin/nonce 均被忽略，不得据此重复刷新业务页。",
  ].join("\n");
});

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

const demoConfirmationAvailable = computed(() => props.precheck?.publicationTarget === "NON_PRODUCTION"
  && props.precheck.evaluationEnvironment === "DEMO" && props.precheck.environmentConfirmationRequired === true
  && !!props.precheck.precheckId?.trim() && !!props.precheck.evalReportId?.trim()
  && /^[0-9a-f]{64}$/.test(props.precheck.environmentEvidenceSha256 || ""));
const environmentBlocked = computed(() => !!props.precheck
  && (props.precheck.evaluationEnvironment === "UNKNOWN"
    || (props.precheck.environmentConfirmationRequired === true || props.precheck.evaluationEnvironment === "DEMO")
    && !demoConfirmationAvailable.value));

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
                publicationTarget: precheck.publicationTarget,
                evaluationEnvironment: precheck.evaluationEnvironment,
                environmentEvidenceSha256: precheck.environmentEvidenceSha256,
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
      <div v-if="demoConfirmationAvailable" class="panel" role="group" aria-label="非生产演示发布确认">
        <el-alert :title="frontendDemoMode?'发布后固定当前配置、技能与本体版本':'仅允许非生产演示发布；演示评测不能作为生产来源证明'" :type="frontendDemoMode?'info':'warning'" :closable="false" show-icon />
        <p class="muted">本次预检 {{ precheck.precheckId }} · 报告 {{ precheck.evalReportId }} · 来源摘要 {{ shortSha(precheck.environmentEvidenceSha256 || '') }}</p>
        <el-checkbox :model-value="environmentConfirmed === true" :disabled="busy || precheckStale || evaluationWorking || evaluationNeedsCheck || !precheck.passed"
          @update:model-value="emit('environment-confirm', $event === true)">我确认仅按上述预检、报告与来源摘要进行本次非生产演示发布</el-checkbox>
      </div>
      <p v-if="environmentBlocked" class="error" role="alert">服务端预检未允许本次非生产演示确认，请重新评测和预检；普通 ADMIN 豁免不能替代来源检查。</p>
      <el-button
        :type="precheck.passed ? 'primary' : 'default'"
        :loading="busy"
        :disabled="
          !precheck.passed ||
          precheckStale || environmentBlocked ||
          (demoConfirmationAvailable && environmentConfirmed !== true) ||
          evaluationWorking ||
          evaluationNeedsCheck ||
          (!!evaluation && evaluation.status !== 'ACCEPTED')
        "
        v-permission="'edit'"
        @click="emit('commit')"
        >{{ demoConfirmationAvailable ? '确认非生产演示发布' : '确认发布版本' }}</el-button
      >
    </div>
    <div class="panel" aria-label="宿主接入交接">
      <h2>集成交接</h2>
      <p class="muted">
        配置版本引用由服务端解析并固定；浏览器只能提议 configReleaseId，不能覆盖引擎权威绑定。
        示例不含任何密钥。
      </p>
      <p v-if="releaseBusy" class="muted">正在读取激活配置版本…</p>
      <p v-else-if="releaseError" class="error" role="alert">{{ releaseError }}</p>
      <template v-else-if="activeRelease">
        <dl style="margin: 0.75rem 0">
          <div>
            <dt>激活配置版本</dt>
            <dd><code>{{ activeRelease.releaseId }}</code></dd>
          </div>
          <div>
            <dt>配置摘要</dt>
            <dd :title="String(activeRelease.digest ?? '')">
              <code>{{ shortSha(String(activeRelease.digest ?? '')) || "未知" }}</code>
            </dd>
          </div>
          <div>
            <dt>激活作用域</dt>
            <dd>{{ activeRelease.activationScope || "production" }}</dd>
          </div>
          <div>
            <dt>智能体版本</dt>
            <dd>{{ activeRelease.appVersion ?? "未知" }}</dd>
          </div>
          <div>
            <dt>接入档案修订</dt>
            <dd>
              {{ activeRelease.integrationProfileId || "未绑定" }}
              <span v-if="activeRelease.integrationProfileRev">
                · rev {{ activeRelease.integrationProfileRev }}
              </span>
            </dd>
          </div>
        </dl>
        <h3>接入示例（不含密钥）</h3>
        <pre>{{ handoff }}</pre>
        <h3>宿主事件（assistant-integration §4）</h3>
        <dl style="margin: 0.75rem 0">
          <div>
            <dt><code>ready</code></dt>
            <dd>SDK 挂载完成；携带 schemaVersion、integrationId、appCode。</dd>
          </div>
          <div>
            <dt><code>opened</code> / <code>closed</code></dt>
            <dd>助手打开/关闭；只含数据字段，不含 DOM 或函数。</dd>
          </div>
          <div>
            <dt><code>run-started</code></dt>
            <dd>运行已受理；携带 runId、sessionId、configReleaseId。</dd>
          </div>
          <div>
            <dt><code>approval-required</code></dt>
            <dd>等待人工确认；携带 runId、status 与 structured.nodeId。</dd>
          </div>
          <div>
            <dt><code>result</code></dt>
            <dd>
              每个 run 的权威终态只发一次；携带 runId、status、structured、fileRefs、
              businessRecordRefs。重连或重复 SSE 不会重复刷新。
            </dd>
          </div>
          <div>
            <dt><code>error</code></dt>
            <dd>可解释错误；携带 error.code、error.message、error.retryable。</dd>
          </div>
        </dl>
        <pre>{{ hostEventExample }}</pre>
        <p class="muted">
          structured.businessEffect 恒为 UNKNOWN：助手消息与模型输出不是业务已提交的证据。
          服务器不会下发回调函数、宿主 JS 或导航 URL；宿主只把验证后的 businessRecordRefs
          交给自己的路由反查。iframe 通道要求精确 origin、一次性 nonce 与实例 ID，未知
          schemaVersion 一律拒绝。
        </p>
        <p class="muted">
          会话创建后配置引用写入运行事实；轮次与恢复沿用同一版本。伪造或过期的
          configReleaseId 由引擎以 CONFIG_RELEASE_NOT_ACTIVE / CONFIG_REV_CONFLICT 拒绝。
        </p>
      </template>
      <p v-else class="muted">
        当前应用还没有激活的配置版本；激活配置发布后，此处才显示可核验的版本引用与接入示例。
      </p>
    </div>
    <div v-if="published" class="panel">
      <h2>集成四件套</h2>
      <pre>{{ JSON.stringify(published, null, 2) }}</pre>
      <p>
        对话地址：<a :href="frontendDemoMode ? `#/apps/${encodeURIComponent(code)}/run` : `${webappBase}/w/${encodeURIComponent(code)}`" target="_blank"
          >打开业务对话</a
        >
      </p>
      <p class="muted">
        嵌入票据必须由业务系统后端签发，禁止在页面配置服务密钥。
      </p>
    </div>
  </div>
</template>
