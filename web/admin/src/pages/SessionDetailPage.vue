<script setup lang="ts">
import { computed, ref } from "vue";
import { useRoute } from "vue-router";
import PageHeader from "../PageHeader.vue";
import EmptyState from "../../../shared/components/EmptyState.vue";
import ErrorBanner from "../../../shared/components/ErrorBanner.vue";
import { usePageTitle } from "../usePageOperation";
import {
  callAnchor,
  sessionCalls,
  sessionEvents,
  sessionSnapshot,
  sessionSubagents,
  taskRecovery,
  type CallAnchorView,
  type CompactionRange,
  type DemoCallRow,
  type DemoReject,
  type DemoRole,
  type DemoSessionEvent,
  type DemoSnapshot,
  type DemoSubagentRow,
} from "../../../shared/mock-session-detail";
import { reasonCodeText } from "../../../shared/reason-code-text";

// F3-6（台账 §7.5 / 前端稿 §5.4）：会话详情与步骤调用下钻——管理台侧参数页（深链
// /sessions/:sessionId；管理台入口=/tasks 或 /runs 下钻，USER 助手端入口属 F4 域）。
// 双视角切换（mock 期演示两个真实角色的投影差异）：
//   USER 视角  = 普通视图（接口稿 §5.1 surfaceOnly=true 白名单）：消息流、压缩标记
//               （区间+token 计数，无 summary 正文与模型调用细节）、本人恢复三字段、
//               子代理停因展示摘要；被压缩覆盖的锚点隐藏跳转。
//   OPERATOR 视角 = 诊断视图（surfaceOnly=false，仅 OPERATOR/ADMIN）：另见诊断事件、
//               压缩正文、调用链 reconciliation、恢复全量（checkpoint/reusedSteps/
//               blockedReasons）、终态原因码；可定位被压缩覆盖的事件。
// 锚点定位（§5.7）：调用行 eventSeq → 跳到消息流对应 seq；无锚点（appCode/sessionId 空
// 或 eventSeq=null）→ 保留错误摘要、隐藏跳转。刷新重新查锚点：refresh 按
// taskId/stepId/callId 重新读取列表项并校验当前锚点（同输入同输出）。
usePageTitle(() => "会话详情");

const route = useRoute();
const sessionId = computed(() => String(route.params.sessionId || ""));

const perspective = ref<DemoRole>("OPERATOR");
const snap = ref<DemoSnapshot | null>(null);
const reject = ref<DemoReject | null>(null);
const stream = ref<DemoSessionEvent[]>([]);
const calls = ref<DemoCallRow[]>([]);
const children = ref<DemoSubagentRow[]>([]);
const linkedTaskId = ref("");
const callIdFilter = ref("");
const anchoredCallId = ref("");
const anchored = ref<{ callId: string; taskId: string; stepId: string; seq: number } | null>(null);
const anchorNotice = ref("");

// 视角→视图门：USER=普通视图；OPERATOR=诊断视图（压缩正文/原始材料/被覆盖事件仅此门可见）。
const surfaceOnly = computed(() => perspective.value === "USER");
const diagnosticVisible = computed(() => perspective.value === "OPERATOR");

const PAGE_VIEW = { surfaceOnly: false };
const USER_VIEW = { surfaceOnly: true };

/** 当前视角下每个 seq 的可见集合（USER 普通视图用于压缩遮蔽判定）。 */
function visibleSeqSet(events: DemoSessionEvent[]): Set<number> {
  return new Set(events.map((event) => event.seq));
}

async function refresh() {
  reject.value = null;
  const id = sessionId.value;
  if (!id) {
    snap.value = null;
    return;
  }
  const snapshot = sessionSnapshot("", id, perspective.value);
  if ("httpStatus" in snapshot) {
    reject.value = snapshot;
    snap.value = null;
    return;
  }
  snap.value = snapshot;
  linkedTaskId.value = snapshot.linkedTaskId;
  const appCode = snapshot.appCode;
  // 事件流：恢复顺序=先快照后增量（接口稿 §5.1）；mock 全量一次取齐（watermark 内全部）。
  const page = sessionEvents(appCode, id, { surfaceOnly: surfaceOnly.value }, perspective.value);
  if (!("nextAfterSeq" in page)) {
    reject.value = page;
    return;
  }
  stream.value = page.events;
  const callPage = sessionCalls(id, perspective.value);
  calls.value = callPage.calls;
  children.value = sessionSubagents(id, perspective.value).children;
  // 刷新重查锚点（§5.7）：按 callId 重新读取列表项并校验当前锚点——同输入同输出。
  revalidateAnchor();
}

/** 锚点复核：已定位调用在最新列表项与当前视角下重新解析；不可再跳则降级为提示。 */
function revalidateAnchor() {
  if (!anchoredCallId.value) return;
  const row = calls.value.find((call) => call.callId === anchoredCallId.value);
  if (!row) {
    anchorNotice.value = `调用 ${anchoredCallId.value} 已不在当前列表，锚点已解除`;
    anchored.value = null;
    anchoredCallId.value = "";
    return;
  }
  const anchor = callAnchor(row, surfaceOnly.value ? USER_VIEW : PAGE_VIEW, visibleSeqSet(stream.value));
  if (anchor.mode === "jump") {
    anchored.value = { callId: row.callId, taskId: row.taskId, stepId: row.stepId, seq: anchor.seq };
    anchorNotice.value = "";
  } else {
    anchorNotice.value = `调用 ${row.callId} 当前视角不可定位（${anchorReasonText(anchor)}），已解除定位`;
    anchored.value = null;
    anchoredCallId.value = "";
  }
}

/** 调用行锚点投影：jump=可定位；hidden 三态=保留错误摘要、隐藏跳转（§5.7）。 */
function callAnchorView(call: DemoCallRow): CallAnchorView {
  return callAnchor(
    call,
    surfaceOnly.value ? USER_VIEW : PAGE_VIEW,
    visibleSeqSet(stream.value),
  );
}
function anchorReasonText(anchor: CallAnchorView): string {
  return anchor.mode === "jump" ? "" : ANCHOR_REASON_TEXT[anchor.reason];
}
/** 模板辅助：jump 态的目标 seq（hidden 态返回 0，按钮不渲染）。 */
function anchorSeq(call: DemoCallRow): number {
  const anchor = callAnchorView(call);
  return anchor.mode === "jump" ? anchor.seq : 0;
}
/** 模板辅助：hidden 态的隐藏原因文案（jump 态返回空串）。 */
function anchorReason(call: DemoCallRow): string {
  return anchorReasonText(callAnchorView(call));
}
/** 模板辅助：hidden 态的隐藏原因键（data-reason；jump 态返回空串）。 */
function anchorReasonKey(call: DemoCallRow): string {
  const anchor = callAnchorView(call);
  return anchor.mode === "jump" ? "" : anchor.reason;
}
const ANCHOR_REASON_TEXT: Record<string, string> = {
  "no-session": "无会话关联，事件锚点不可用",
  "no-visible-event": "会话可读但无可见事件",
  shadowed: "锚点事件被压缩覆盖（当前视角仅见标记）",
};

/** 定位调用：仅 jump 态可点；设置锚点并把消息流滚到该 seq。 */
function locateCall(call: DemoCallRow) {
  const anchor = callAnchorView(call);
  if (anchor.mode !== "jump") return;
  anchoredCallId.value = call.callId;
  anchored.value = { callId: call.callId, taskId: call.taskId, stepId: call.stepId, seq: anchor.seq };
  anchorNotice.value = "";
}

function clearAnchor() {
  anchoredCallId.value = "";
  anchored.value = null;
  anchorNotice.value = "";
}

/** 双视角切换：锚点置空待复核，保留 anchoredCallId 供刷新重查（两视角投影与可见集合不同，
 *  不可再跳的锚点由 revalidateAnchor 如实降级提示，不静默丢弃）。 */
function switchPerspective(role: DemoRole) {
  if (perspective.value === role) return;
  perspective.value = role;
  anchored.value = null;
  anchorNotice.value = "";
  void refresh();
}

// —— 调用列表过滤（§5.7 callId 直接定位语义的页面侧投影）。 ——
const filteredCalls = computed(() =>
  callIdFilter.value
    ? calls.value.filter((call) => call.callId.includes(callIdFilter.value.trim()))
    : calls.value,
);

// —— 消息流渲染投影：事件序列 + 压缩标记条（区间+token 计数；OPERATOR 诊断视角另见正文）。 ——
interface StreamRow {
  seq: number;
  type: string;
  kind: "turn" | "user" | "assistant" | "tool" | "compaction" | "subagent" | "meta" | "diag";
  occurredAt: string;
  title: string;
  text: string;
  data: Record<string, unknown>;
}
const EVENT_KIND: Record<string, StreamRow["kind"]> = {
  "turn/start": "turn",
  "turn/end": "turn",
  "step/start": "meta",
  "step/end": "meta",
  "user/message": "user",
  "assistant/message": "assistant",
  "tool/call": "tool",
  "tool/result": "tool",
  "compaction/start": "compaction",
  "compaction/summary": "compaction",
  "compaction/end": "compaction",
  "subagent/registered": "subagent",
  "subagent/settled": "subagent",
};
const DIAGNOSTIC_TYPES = new Set(["system/message", "request/header", "request/context", "assistant/attempt"]);
const DIAGNOSTIC_TITLES: Record<string, string> = {
  "system/message": "系统消息（诊断）",
  "request/header": "模型请求材料（诊断）",
  "request/context": "请求上下文（诊断）",
  "assistant/attempt": "尝试记录（诊断）",
};
function streamRows(events: DemoSessionEvent[]): StreamRow[] {
  return events.map((event) => {
    const kind = EVENT_KIND[event.type] ?? (DIAGNOSTIC_TYPES.has(event.type) ? "diag" : "meta");
    const data = event.data;
    let title = event.type;
    let text = "";
    if (event.type === "user/message") {
      title = "用户消息";
      text = String(data.text ?? "");
    } else if (event.type === "assistant/message") {
      title = "助手消息";
      text = String(data.text ?? "");
    } else if (event.type === "system/message") {
      title = DIAGNOSTIC_TITLES[event.type]!;
      text = String(data.text ?? "");
    } else if (event.type === "tool/call") {
      title = `调用 ${data.callId} · ${data.name}`;
      text = String(data.argsSummary ?? "");
    } else if (event.type === "tool/result") {
      const meta = (data.meta ?? {}) as Record<string, unknown>;
      title = `回执 ${data.callId} · ${meta.status ?? ""}`;
      text = String(data.error ?? data.resultSummary ?? "");
    } else if (event.type === "turn/start" || event.type === "turn/end") {
      title = `轮次 ${data.turn ?? "—"} ${event.type === "turn/start" ? "开始" : `结束（${data.reason ?? "—"}）`}`;
    } else if (event.type === "step/start" || event.type === "step/end") {
      title = `步骤 ${data.stepId} ${event.type === "step/start" ? `开始（${data.kind ?? "—"}）` : `结束（${data.state ?? "—"}）`}`;
    } else if (event.type === "compaction/start") {
      title = "压缩开始";
      text = `覆盖事件 ${data.fromSeq}–${data.toSeq}`;
    } else if (event.type === "compaction/summary") {
      title = "此处已压缩";
      text =
        diagnosticVisible.value
          ? String(data.summary ?? "")
          : `事件 ${data.fromSeq}–${data.toSeq}（tokens ${data.tokensBefore}→${data.tokensAfter}）`;
    } else if (event.type === "compaction/end") {
      title = "压缩完成";
      text = `tokens ${data.tokensBefore}→${data.tokensAfter}`;
    } else if (event.type.startsWith("subagent/")) {
      title = `子代理 ${data.childSessionId ?? ""} ${event.type === "subagent/registered" ? "注册" : "结算"}`;
    } else if (DIAGNOSTIC_TYPES.has(event.type)) {
      title = DIAGNOSTIC_TITLES[event.type] ?? event.type;
    }
    return { seq: event.seq, type: event.type, kind, occurredAt: event.occurredAt, title, text, data };
  });
}
const rows = computed(() => streamRows(stream.value));

// 压缩标记（区间+token 计数，无正文）——两视角都渲染标记条；OPERATOR 诊断视角附正文行。
const compactions = computed<CompactionRange[]>(() => {
  const start = stream.value.find((event) => event.type === "compaction/start");
  const end = stream.value.find((event) => event.type === "compaction/end");
  if (!start || !end) return [];
  return [
    {
      fromSeq: Number(start.data.fromSeq),
      toSeq: Number(start.data.toSeq),
      tokensBefore: Number(start.data.tokensBefore),
      tokensAfter: Number(end.data.tokensAfter),
    },
  ];
});
const compactionSummaries = computed(() =>
  stream.value
    .filter((event) => event.type === "compaction/summary" && diagnosticVisible.value)
    .map((event) => String(event.data.summary ?? "")),
);

// —— 恢复事实卡（contracts §3 角色投影）：USER 三字段；OPERATOR 另见附加分区（有则显示，缺省空集不伪造）。 ——
const recovery = computed(() => {
  const projection = taskRecovery(linkedTaskId.value, perspective.value);
  if (!projection) return null;
  return {
    recoverySource: projection.recoverySource,
    reasonCode: projection.reasonCode,
    reasonSummary: projection.reasonSummary,
    checkpoint: projection.checkpoint ?? null,
    reusedSteps: projection.reusedSteps ?? [],
    blockedReasons: projection.blockedReasons ?? [],
  };
});

// —— 状态徽章（展示即事实投影，不自判）。 ——
const callStateClass = (status: string) =>
  status === "SUCCEEDED" ? "is-ok" : status === "FAILED" ? "is-fail" : "is-suspend";

void refresh();
</script>

<template>
  <div class="session-detail-page">
    <el-breadcrumb class="page-breadcrumb" separator="/">
      <el-breadcrumb-item :to="{ path: '/tasks' }">任务工作台</el-breadcrumb-item>
      <el-breadcrumb-item :to="{ path: '/runs' }">运行监控</el-breadcrumb-item>
      <el-breadcrumb-item>会话详情</el-breadcrumb-item>
    </el-breadcrumb>
    <PageHeader
      title="会话详情"
      subtitle="消息流、压缩标记、步骤调用下钻、恢复事实与子代理目录（mock 演示数据，联调期换 H1-9/§5.7 真实端点）。"
      @refresh="refresh"
    />

    <!-- 双视角切换：USER=普通视图白名单；OPERATOR=诊断视图+调用链/压缩与恢复详情。 -->
    <div class="perspective-bar" role="group" aria-label="视角切换">
      <button
        class="chip"
        :class="{ 'is-active': perspective === 'USER' }"
        type="button"
        data-role="USER"
        @click="switchPerspective('USER')"
      >USER 视角（普通视图）</button>
      <button
        class="chip"
        :class="{ 'is-active': perspective === 'OPERATOR' }"
        type="button"
        data-role="OPERATOR"
        @click="switchPerspective('OPERATOR')"
      >OPERATOR 视角（诊断视图）</button>
      <span class="muted view-note">
        {{
          perspective === "USER"
            ? "本人口径：消息、压缩标记（无正文）、恢复三字段、停因摘要；无原始材料与压缩正文。"
            : "授权口径：另见诊断事件、压缩正文、调用链 reconciliation、恢复全量与终态原因码。"
        }}
      </span>
    </div>

    <!-- 直链拒绝（403+审计）/资源缺失（404）：如实呈现拒绝原因与审计说明。 -->
    <ErrorBanner v-if="reject && !snap" :error="reject.userMessage" />
    <section v-if="reject" class="panel reject-panel" data-test="reject-panel" aria-label="访问拒绝">
      <header class="section-head">
        <h2>访问被拒绝</h2>
        <span class="reject-code mono">{{ reject.error }} · HTTP {{ reject.httpStatus }}</span>
      </header>
      <p>{{ reject.userMessage }}</p>
      <p class="muted">
        已记录拒绝审计：principal <code class="mono">{{ reject.audit.principal }}</code> 对
        <code class="mono">{{ reject.audit.appCode || "—" }}/{{ reject.audit.sessionId }}</code>
        的访问（原因：{{ reject.audit.reason }}）。
      </p>
    </section>

    <EmptyState
      v-if="!reject && !snap"
      variant="filtered"
      :description="`未找到会话 ${sessionId || '—'}`"
      action-text=""
    />

    <template v-if="snap">
      <!-- 会话概览（H1-9 快照元数据）：水位衔接说明=客户端以 seq>eventWatermark 续接。 -->
      <section class="panel" aria-label="会话概览">
        <dl class="summary-grid">
          <div><dt>会话</dt><dd><code class="mono">{{ snap.sessionId }}</code></dd></div>
          <div><dt>智能体</dt><dd>{{ snap.appCode }}</dd></div>
          <div><dt>标题</dt><dd>{{ snap.title }}</dd></div>
          <div><dt>状态</dt><dd>{{ snap.state }}</dd></div>
          <div><dt>事件水位</dt><dd class="mono">{{ snap.eventWatermark }}</dd></div>
          <div><dt>关联任务</dt><dd><code class="mono">{{ snap.linkedTaskId }}</code></dd></div>
          <div><dt>视图</dt><dd>{{ perspective === "USER" ? "普通视图（surfaceOnly=true）" : "诊断视图（surfaceOnly=false）" }}</dd></div>
        </dl>
        <p class="muted demo-note" role="note">mock 演示数据（demo:true），授权与投影以服务端为准。</p>
      </section>

      <!-- 锚点定位提示：定位成功与降级解除都如实提示。 -->
      <p v-if="anchorNotice" class="anchor-notice" role="status">{{ anchorNotice }}</p>

      <!-- 消息流（普通视图投影序列；压缩标记条两视角可见；诊断事件仅 OPERATOR 视角）。 -->
      <section class="panel" aria-label="消息流" data-test="message-stream">
        <header class="section-head">
          <h2>消息流</h2>
          <p v-if="anchored" class="muted anchored-note">
            已定位调用 <code class="mono">{{ anchored.callId }}</code> → 事件
            <code class="mono">#{{ anchored.seq }}</code>
            <button class="link" type="button" @click="clearAnchor">取消定位</button>
          </p>
        </header>
        <ol class="stream">
          <li
            v-for="row in rows"
            :key="row.seq"
            class="stream-row"
            :class="[`is-${row.kind}`, { 'is-anchored': anchored && anchored.seq === row.seq }]"
            :data-seq="row.seq"
            :data-type="row.type"
          >
            <span class="seq mono">{{ row.seq }}</span>
            <div class="stream-body">
              <header class="stream-title">
                <strong>{{ row.title }}</strong>
                <span v-if="row.type !== row.title" class="muted mono">{{ row.type }}</span>
                <time class="muted">{{ row.occurredAt }}</time>
              </header>
              <p v-if="row.text" :class="['stream-text', { 'is-diag': row.kind === 'diag' }]">{{ row.text }}</p>
            </div>
          </li>
        </ol>
        <!-- 压缩标记条：区间+token 计数，无正文；OPERATOR 诊断视角另列正文（授权压缩详情）。 -->
        <div v-for="range in compactions" :key="range.fromSeq" class="compaction-marker" data-test="compaction-marker">
          <strong>此处已压缩</strong>
          <span class="mono">事件 {{ range.fromSeq }}–{{ range.toSeq }}</span>
          <span class="mono">tokens {{ range.tokensBefore }}→{{ range.tokensAfter }}</span>
          <span class="muted">被覆盖的原始消息不参与普通投影</span>
        </div>
        <div v-for="(text, index) in compactionSummaries" :key="`summary-${index}`" class="compaction-summary" data-test="compaction-summary">
          <span class="muted">压缩正文（诊断授权）</span>
          <p>{{ text }}</p>
        </div>
      </section>

      <!-- 步骤调用下钻表（§5.7 calls 形状）：锚点列=定位按钮或隐藏原因；错误摘要恒保留。 -->
      <section class="panel" aria-label="步骤调用下钻" data-test="calls-panel">
        <header class="section-head">
          <h2>步骤调用（{{ calls.length }}）</h2>
          <input
            v-model="callIdFilter"
            class="call-filter"
            type="search"
            placeholder="按 callId 过滤"
            aria-label="按 callId 过滤调用列表"
          />
        </header>
        <el-table :data="filteredCalls" size="small" aria-label="步骤调用表">
          <el-table-column prop="callId" label="callId" min-width="110">
            <template #default="{ row }"><code class="mono">{{ row.callId }}</code></template>
          </el-table-column>
          <el-table-column label="技能" min-width="130">
            <template #default="{ row }">{{ row.skillCode }}@{{ row.skillVersion }}</template>
          </el-table-column>
          <el-table-column label="状态" width="170">
            <template #default="{ row }">
              <span :class="['call-state', callStateClass(row.status)]">{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column label="错误摘要" min-width="220">
            <template #default="{ row }">
              <span v-if="row.errorSummary" class="error-text">{{ row.errorSummary }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="appCode / sessionId" min-width="200">
            <template #default="{ row }">
              <code v-if="row.appCode" class="mono">{{ row.appCode }}</code>
              <span v-else class="muted">—</span>
              <template v-if="row.sessionId"> / <code class="mono">{{ row.sessionId }}</code></template>
              <template v-else><span class="muted"> / —</span></template>
            </template>
          </el-table-column>
          <el-table-column label="eventSeq" width="90">
            <template #default="{ row }">
              <span v-if="row.eventSeq != null" class="mono">{{ row.eventSeq }}</span>
              <span v-else class="muted">null</span>
            </template>
          </el-table-column>
          <el-table-column label="锚点定位" width="210">
            <template #default="{ row }">
              <el-button
                v-if="callAnchorView(row).mode === 'jump'"
                size="small"
                type="primary"
                plain
                data-action="locate"
                @click="locateCall(row)"
              >定位事件 #{{ anchorSeq(row) }}</el-button>
              <span v-else class="muted anchor-hidden" :data-reason="anchorReasonKey(row)">
                — {{ anchorReason(row) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column v-if="diagnosticVisible" label="对账（诊断）" min-width="190">
            <template #default="{ row }">
              <span v-if="row.reconciliation" class="mono">
                {{ row.reconciliation.state }} · {{ row.reconciliation.reasonCode }}<template v-if="reasonCodeText(row.reconciliation.reasonCode)">（{{ reasonCodeText(row.reconciliation.reasonCode) }}）</template>
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
        <p class="muted">无锚点调用保留错误摘要并隐藏跳转（§5.7）；刷新按 taskId/stepId/callId 重新查锚点。</p>
      </section>

      <!-- 恢复事实卡（contracts §3 角色投影）：USER 三字段；OPERATOR 另见附加分区（有则显示）。 -->
      <section class="panel" aria-label="恢复事实" data-test="recovery-card">
        <header class="section-head">
          <h2>恢复事实</h2>
          <span class="muted">来源任务 <code class="mono">{{ linkedTaskId || "—" }}</code></span>
        </header>
        <template v-if="recovery">
          <dl class="recovery-fields">
            <div>
              <dt>来源</dt>
              <dd>{{ recovery.recoverySource === "MANUAL" ? "手动恢复" : "自动恢复" }}（{{ recovery.recoverySource }}）</dd>
            </div>
            <div><dt>原因码</dt><dd class="mono">{{ recovery.reasonCode }}<span v-if="reasonCodeText(recovery.reasonCode)" class="muted">（{{ reasonCodeText(recovery.reasonCode) }}）</span></dd></div>
            <div><dt>摘要</dt><dd>{{ recovery.reasonSummary }}</dd></div>
          </dl>
          <div v-if="recovery.checkpoint" class="recovery-extra">
            <span class="muted">检查点</span>
            <span class="mono">watermark {{ recovery.checkpoint.eventWatermark }}</span>
            <span v-for="runRef in recovery.checkpoint.runRefs" :key="runRef" class="mono">{{ runRef }}</span>
          </div>
          <div v-if="recovery.reusedSteps.length" class="recovery-extra">
            <span class="muted">复用步骤</span>
            <span v-for="reused in recovery.reusedSteps" :key="reused.stepId" class="mono">
              {{ reused.stepId }}（digest {{ reused.resultDigest }}）
            </span>
          </div>
          <div v-if="recovery.blockedReasons.length" class="recovery-extra">
            <span class="muted">仍阻塞原因</span>
            <span v-for="reason in recovery.blockedReasons" :key="reason">{{ reason }}</span>
          </div>
        </template>
        <p v-else class="muted">无恢复事实（任务从未恢复，recovery=null 如实呈现）。</p>
        <p class="muted">
          {{ perspective === "USER" ? "USER 口径：仅恢复来源/原因码/摘要三字段。" : "OPERATOR 口径：另见检查点、复用步骤与阻塞原因。" }}
        </p>
      </section>

      <!-- 子代理目录（§5.3）：不返回提示词/参数/正文；停因按角色投影。 -->
      <section class="panel" aria-label="子代理目录" data-test="subagents-panel">
        <header class="section-head">
          <h2>子代理目录（{{ children.length }}）</h2>
          <span class="muted">{{ perspective === "USER" ? "停因为展示摘要" : "停因为终态原因码" }}</span>
        </header>
        <el-table v-if="children.length" :data="children" size="small" aria-label="子代理表">
          <el-table-column label="子会话" min-width="170">
            <template #default="{ row }"><code class="mono">{{ row.childSessionId }}</code></template>
          </el-table-column>
          <el-table-column prop="label" label="标签" min-width="140" />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column label="停因" min-width="140">
            <template #default="{ row }">
              <span v-if="perspective === 'OPERATOR'" class="mono">{{ row.stopReason }}</span>
              <span v-else>{{ row.stopReason }}</span>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="90">
            <template #default="{ row }">{{ row.durationMs >= 1000 ? Math.round(row.durationMs / 100) / 10 + "s" : row.durationMs + "ms" }}</template>
          </el-table-column>
        </el-table>
        <p v-else class="muted">本会话没有子代理记录。</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* F3-6 详情页：单列分区卡纵向流（同 F3-2 计划详情既定模式），只引用 tokens 变量。 */
.session-detail-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  min-width: 0;
}
.page-breadcrumb {
  margin-bottom: 0;
}
.panel {
  border: 1px solid var(--line);
  border-radius: 0.75rem;
  background: var(--surface);
  padding: var(--sp-4) var(--sp-5);
  min-width: 0;
}
.muted {
  color: var(--muted);
  font-size: var(--text-meta);
}
.mono {
  font-family: var(--font-mono);
  font-size: var(--text-meta);
  overflow-wrap: anywhere;
}
.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
  margin-bottom: var(--sp-2);
}
.section-head h2 {
  margin: 0;
}
.section-head p {
  margin: 0;
}
/* 双视角切换 chips（同任务工作台管道 chips 视觉）。 */
.perspective-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
}
.chip {
  border: 1px solid color-mix(in srgb, var(--muted) 45%, transparent);
  border-radius: 999px;
  background: transparent;
  color: var(--text-secondary);
  font-size: var(--text-meta);
  padding: 0.1rem var(--sp-3);
  cursor: pointer;
}
.chip:hover {
  border-color: var(--st-run);
  color: var(--text-primary);
}
.chip.is-active {
  border-color: var(--st-run);
  color: var(--st-run-text);
  background: color-mix(in srgb, var(--st-run) 12%, transparent);
  font-weight: 600;
}
.view-note {
  flex-basis: 100%;
}
.demo-note {
  margin: var(--sp-2) 0 0;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(13rem, 1fr));
  gap: var(--sp-2) var(--sp-4);
  margin: 0;
}
.summary-grid dt {
  color: var(--muted);
  font-size: var(--text-meta);
  margin-bottom: var(--sp-1);
}
.summary-grid dd {
  margin: 0;
  font-size: var(--text-table);
  overflow-wrap: anywhere;
}
.reject-panel {
  border-color: color-mix(in srgb, var(--st-fail) 45%, transparent);
  background: color-mix(in srgb, var(--st-fail) 8%, var(--surface));
}
.reject-code {
  color: var(--st-fail-text);
}
.reject-panel p {
  margin: var(--sp-1) 0;
}
.anchor-notice {
  margin: 0;
  padding: var(--sp-2) var(--sp-3);
  border: 1px solid color-mix(in srgb, var(--st-suspend) 40%, transparent);
  border-radius: var(--radius);
  color: var(--st-suspend-text);
  font-size: var(--text-meta);
}
.link {
  background: none;
  border: none;
  color: var(--st-run-text);
  cursor: pointer;
  padding: 0;
  font-size: var(--text-meta);
}
.link:hover {
  text-decoration: underline;
}
/* 消息流：事件序列行；诊断行弱化；锚定行高亮。 */
.stream {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}
.stream-row {
  display: flex;
  gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-1);
  border-bottom: 1px dashed color-mix(in srgb, var(--muted) 30%, transparent);
}
.stream-row.is-anchored {
  background: color-mix(in srgb, var(--st-run) 12%, transparent);
  outline: 1px solid color-mix(in srgb, var(--st-run) 55%, transparent);
  border-radius: var(--radius);
}
.stream-row.is-diag {
  opacity: 0.75;
}
.seq {
  color: var(--muted);
  min-width: 2rem;
  text-align: right;
}
.stream-body {
  min-width: 0;
  flex: 1;
}
.stream-title {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  flex-wrap: wrap;
}
.stream-title time {
  margin-left: auto;
  font-size: var(--text-meta);
}
.stream-text {
  margin: var(--sp-1) 0 0;
  color: var(--text-secondary);
  font-size: var(--text-table);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.stream-text.is-diag {
  color: var(--muted);
}
/* 压缩标记条：区间+token 计数，无正文（正文仅诊断授权行）。 */
.compaction-marker {
  margin-top: var(--sp-3);
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
  border: 1px dashed color-mix(in srgb, var(--st-suspend) 55%, transparent);
  background: color-mix(in srgb, var(--st-suspend) 10%, transparent);
  border-radius: var(--radius);
  padding: var(--sp-2) var(--sp-3);
}
.compaction-marker strong {
  color: var(--st-suspend-text);
}
.compaction-summary {
  margin-top: var(--sp-2);
  border-left: 2px solid color-mix(in srgb, var(--st-suspend) 55%, transparent);
  padding: var(--sp-1) var(--sp-3);
}
.compaction-summary p {
  margin: var(--sp-1) 0 0;
  color: var(--text-secondary);
  font-size: var(--text-table);
}
/* 调用下钻表辅助。 */
.call-filter {
  border: 1px solid color-mix(in srgb, var(--muted) 45%, transparent);
  border-radius: var(--radius);
  background: transparent;
  color: var(--text-primary);
  font-size: var(--text-meta);
  padding: 0.2rem var(--sp-2);
  min-width: 12rem;
}
.call-state.is-ok {
  color: var(--st-ok-text);
}
.call-state.is-fail {
  color: var(--st-fail-text);
}
.call-state.is-suspend {
  color: var(--st-suspend-text);
}
.error-text {
  color: var(--st-fail-text);
}
.anchor-hidden {
  overflow-wrap: anywhere;
}
/* 恢复事实卡（同任务工作台恢复面板字段布局）。 */
.recovery-fields {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2) var(--sp-5);
}
.recovery-fields div {
  display: flex;
  gap: var(--sp-2);
  align-items: baseline;
}
.recovery-fields dt {
  color: var(--muted);
  font-size: var(--text-meta);
}
.recovery-fields dd {
  margin: 0;
}
.recovery-extra {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  align-items: baseline;
  font-size: var(--text-meta);
  border-top: 1px dashed color-mix(in srgb, var(--muted) 30%, transparent);
  padding-top: var(--sp-2);
  margin-top: var(--sp-2);
}
@media (max-width: 600px) {
  .stream-title time {
    margin-left: 0;
  }
}
</style>
