<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute } from "vue-router";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import SkillStatistics from "../SkillStatistics.vue";
import { usePageOperation, usePageTitle } from "../usePageOperation";
import { confirmDanger } from "../../../shared/components/DangerConfirm";
import {
  fetchMockSkillPrecheck,
  skillKindOf,
  type SkillPrecheck,
  type SkillPrecheckItem,
} from "../../../shared/mock-skill-precheck";
import { displayTime, statusLabel } from "./resources/resource-format";

// F2-2（台账 §7.5 / 方案 §5.5）：技能详情 5 Tab → 单页分区 + 发布 checklist 卡。
// - 分区：契约摘要 /（TOOL）连接调试、选型验收 / 版本与发布时间线；GUIDE 无执行契约，
//   不渲染连接调试与选型验收分区。分区用锚点滚动导航（无二级 Tab、无第三级滚动容器）。
// - 发布 checklist 分型（TOOL 四项 / GUIDE 两项），各项展示服务端预检结果
//   （demo 由 mock-skill-precheck 提供，真实端点就绪后整行换 api 客户端），
//   前端不自算通过/阻断；全绿才激活发布按钮，未全绿 disabled+tooltip 列出未过项。
// - 副作用 TOOL 的意图字段阻断项点击直达契约编辑器 businessIntentInput 字段（锚点滚动）。
const route = useRoute();
const { api, busy, error, trace, operation, success } = usePageOperation();
const code = computed(() => String(route.params.code || ""));
const detail = ref<any>();
const kind = computed(() => skillKindOf(detail.value));
const selected = ref<number>();
usePageTitle(() => detail.value?.name || code.value || "技能详情");

const base = () => "/admin/skills/" + encodeURIComponent(code.value);

async function load() {
  detail.value = undefined;
  precheck.value = null;
  if (!code.value) return;
  await operation(async () => {
    detail.value = await api(base());
    selected.value =
      detail.value?.versions?.[0]?.versionNo || detail.value?.currentVersion;
    syncEditors();
    await runPrecheck();
    void loadReferencedBy();
  });
}

// —— 发布 checklist 卡：服务端预检结果驱动（demo mock）——
const precheck = ref<SkillPrecheck | null>(null);
const precheckBusy = ref(false);
async function runPrecheck() {
  if (!detail.value) {
    precheck.value = null;
    return;
  }
  precheckBusy.value = true;
  try {
    // 形状=未来 GET /admin/skills/{code}/prechecks?version={n}；demo 由按域 mock 推导。
    precheck.value = await fetchMockSkillPrecheck(detail.value, selected.value);
  } finally {
    precheckBusy.value = false;
  }
}
const blockedItems = computed(
  () =>
    precheck.value?.items.filter(
      (item) => item.required && item.status !== "PASS",
    ) ?? [],
);
const canPublish = computed(() => !!precheck.value?.passed);
const publishBlockedReason = computed(() =>
  blockedItems.value.length
    ? `发布被服务端预检阻断：${blockedItems.value.map((item) => item.label).join("、")}未通过`
    : "",
);
const precheckStatusIcon: Record<string, string> = {
  PASS: "✓",
  FAIL: "✗",
  PENDING: "…",
};

// checklist 阻断项点击：意图字段声明项直达契约编辑器对应字段（方案 §5.2 预检要求）。
function locateItem(item: SkillPrecheckItem) {
  if (item.fieldAnchor) {
    editing.value = true;
    requestAnimationFrame(() => {
      const target = document.getElementById("skill-field-" + item.fieldAnchor);
      target?.scrollIntoView({ behavior: "smooth", block: "center" });
      target?.classList.add("field-flash");
      setTimeout(() => target?.classList.remove("field-flash"), 1600);
    });
    return;
  }
  scrollToSection("skill-section-contract");
}

// —— 单页分区导航（锚点滚动；GUIDE 分型少两区）——
const sections = computed(() => [
  { id: "skill-section-contract", label: "契约摘要" },
  ...(kind.value === "TOOL"
    ? [
        { id: "skill-section-connection", label: "连接调试" },
        { id: "skill-section-acceptance", label: "选型验收" },
      ]
    : []),
  { id: "skill-section-versions", label: "版本与发布时间线" },
]);
function scrollToSection(id: string) {
  document
    .getElementById(id)
    ?.scrollIntoView({ behavior: "smooth", block: "start" });
}

// —— 契约摘要卡：只读摘要 + 编辑切换（编辑入口）；副作用 TOOL 的意图声明字段有稳定锚点 ——
const editing = ref(false);
const inputJson = ref("");
const outputJson = ref("");
const intentInput = ref("");
const guideName = ref("");
const guideDescription = ref("");
const guideContent = ref("");
function toggleEdit() {
  if (editing.value) syncEditors();
  editing.value = !editing.value;
}
function syncEditors() {
  inputJson.value = JSON.stringify(detail.value?.inputSchema ?? { type: "object" }, null, 2);
  outputJson.value = JSON.stringify(detail.value?.outputSchema ?? { type: "object" }, null, 2);
  intentInput.value = String(detail.value?.businessIntentInput ?? "");
  guideName.value = String(detail.value?.name ?? "");
  guideDescription.value = String(detail.value?.description ?? "");
  guideContent.value = String(detail.value?.content ?? "");
}
const contractError = ref("");
const contractBusy = ref(false);
async function saveContract() {
  if (!detail.value) return;
  contractError.value = "";
  let inputSchema: unknown;
  let outputSchema: unknown;
  if (kind.value === "TOOL") {
    try {
      inputSchema = JSON.parse(inputJson.value);
      outputSchema = JSON.parse(outputJson.value);
    } catch {
      contractError.value = "输入/输出 Schema 必须是合法 JSON";
      return;
    }
  }
  contractBusy.value = true;
  try {
    await api(base(), "PUT", {
      ...detail.value,
      ...(kind.value === "GUIDE"
        ? {
            name: guideName.value.trim(),
            description: guideDescription.value.trim(),
            content: guideContent.value,
          }
        : { inputSchema, outputSchema, businessIntentInput: intentInput.value.trim() }),
    });
    success("契约已保存为草稿");
    editing.value = false;
    await load();
  } catch (e: any) {
    contractError.value = e?.message || "保存失败";
  } finally {
    contractBusy.value = false;
  }
}

// —— 连接调试（仅 TOOL）：受控测试 + 结果 ——
const connectionInput = ref("{}");
const connectionBusy = ref(false);
const connectionError = ref("");
const connectionResult = ref<any>();
async function runConnectionTest() {
  if (!selected.value) return;
  connectionError.value = "";
  let inputs: unknown;
  try {
    inputs = JSON.parse(connectionInput.value || "{}");
  } catch {
    connectionError.value = "测试输入必须是合法 JSON";
    return;
  }
  connectionBusy.value = true;
  try {
    connectionResult.value = await api(
      `${base()}/drafts/${selected.value}/connection-test`,
      "POST",
      { inputs, requestId: crypto.randomUUID() },
    );
  } catch (e: any) {
    connectionError.value = e?.message || "受控测试失败";
  } finally {
    connectionBusy.value = false;
  }
}

// —— 选型验收（仅 TOOL）：用例表格化 + 结果 ——
const models = ref<any[]>([]);
const modelId = ref<number>();
const cases = ref<{ question: string; expectSelected: string; expectArgs: string }[]>([
  { question: "", expectSelected: "", expectArgs: "" },
]);
const acceptanceBusy = ref(false);
const acceptanceError = ref("");
const acceptanceResult = ref<any>();
watch(
  () => detail.value?.code,
  async () => {
    modelId.value = undefined;
    acceptanceResult.value = undefined;
    models.value = [];
    if (!detail.value) return;
    try {
      models.value = await api("/admin/model-entries");
    } catch {
      models.value = [];
    }
  },
);
function addCase() {
  cases.value.push({ question: "", expectSelected: "", expectArgs: "" });
}
function removeCase(index: number) {
  cases.value.splice(index, 1);
}
async function runAcceptance() {
  if (!selected.value) return;
  acceptanceError.value = "";
  if (!modelId.value) {
    acceptanceError.value = "请选择真实模型条目";
    return;
  }
  if (!cases.value.length)
    acceptanceError.value = "请填写至少一条验收用例";
  let parsedCases: unknown[];
  try {
    parsedCases = cases.value.map((row) => ({
      question: row.question,
      expectSelected: row.expectSelected.trim(),
      ...(row.expectArgs.trim()
        ? { expectArgs: JSON.parse(row.expectArgs) }
        : {}),
    }));
  } catch {
    acceptanceError.value = "expectArgs 必须是合法 JSON（可留空）";
    return;
  }
  acceptanceBusy.value = true;
  try {
    acceptanceResult.value = await api(
      `${base()}/drafts/${selected.value}/acceptance`,
      "POST",
      { modelEntryId: modelId.value, cases: parsedCases },
    );
  } catch (e: any) {
    acceptanceError.value = e?.message || "选型验收失败";
  } finally {
    acceptanceBusy.value = false;
  }
}

// —— 发布：checklist 全绿才激活；确认走 DangerConfirm（发布后内容不可修改）——
const publishBusy = ref(false);
async function publish() {
  if (!canPublish.value || !selected.value) return;
  if (
    !(await confirmDanger({
      title: "发布版本",
      message: "确认发布此版本？发布后内容不可修改。",
      confirmText: "发布",
    }))
  )
    return;
  await operation(async () => {
    await api(`${base()}/versions/${selected.value}/publish`, "POST");
    success("已发布");
    await load();
  });
}

// —— 版本与发布时间线 + 引用 ——
const referencedBy = ref<any[]>([]);
async function loadReferencedBy() {
  referencedBy.value = [];
  if (!code.value) return;
  try {
    const rows = await api(`${base()}/referenced-by`);
    if (Array.isArray(rows)) referencedBy.value = rows;
  } catch {
    referencedBy.value = [];
  }
}

// 首次加载放在声明尾部：immediate watch 同步执行 load()，避免引用未初始化的响应式状态。
watch(code, () => void load(), { immediate: true });
</script>

<template>
  <div class="skill-detail-page">
    <PageHeader
      :title="detail?.name || code || '技能详情'"
      eyebrow="技能库"
      :subtitle="detail?.description || '查看技能定义、版本与执行统计。'"
      :busy="busy"
      @refresh="load"
    />
    <PageError :error="error" :trace="trace" />
    <template v-if="detail">
      <!-- 发布 checklist 卡：分型 items 全部来自服务端预检（demo mock），前端不自算 -->
      <section class="panel precheck-card" aria-label="发布 checklist">
        <header class="precheck-head">
          <div>
            <h2>发布 checklist</h2>
            <p class="muted">
              {{ kind === "GUIDE" ? "GUIDE 分型：frontmatter 完整 → 内容规范" : "TOOL 分型：契约完整 → 连接测试 → 选型验收 →（副作用）意图字段声明" }}
              · 各项为服务端预检结果<span
                v-if="precheck?.demo"
                class="demo-flag"
                title="演示数据：预检结果由前端按域 mock 提供（demo:true），形状与真实端点一致"
                >demo</span
              >
            </p>
          </div>
          <div class="precheck-publish">
            <el-tooltip
              :content="publishBlockedReason"
              :disabled="canPublish"
              placement="top"
            >
              <span>
                <el-button
                  type="primary"
                  :loading="publishBusy"
                  :disabled="!canPublish || busy"
                  v-permission="'edit'"
                  @click="publish"
                  >发布 v{{ selected }}</el-button
                >
              </span>
            </el-tooltip>
            <p v-if="!canPublish" class="muted blocked-note">
              {{ publishBlockedReason || "预检加载中…" }}
            </p>
          </div>
        </header>
        <ol class="checklist" aria-label="分型预检项">
          <li
            v-for="item in precheck?.items ?? []"
            :key="item.key"
            :class="['check-item', item.status.toLowerCase()]"
          >
            <button
              type="button"
              class="check-label"
              :title="item.fieldAnchor ? `定位到契约编辑器 ${item.fieldAnchor} 字段` : item.detail"
              @click="locateItem(item)"
            >
              <span class="check-icon" aria-hidden="true">{{
                precheckStatusIcon[item.status] || "…"
              }}</span>
              {{ item.label
              }}<span v-if="!item.required" class="muted">（免检）</span>
            </button>
            <span class="check-detail">{{ item.detail }}</span>
          </li>
        </ol>
        <p v-if="precheckBusy" class="muted">预检中…</p>
      </section>

      <!-- 分区锚点导航（滚动定位，不是 Tab） -->
      <nav class="section-nav" aria-label="单页分区导航">
        <el-button
          v-for="section in sections"
          :key="section.id"
          text
          @click="scrollToSection(section.id)"
          >{{ section.label }}</el-button
        >
      </nav>

      <!-- 分区一：契约摘要（只读摘要 + 编辑切换；GUIDE 为 frontmatter + 正文） -->
      <section id="skill-section-contract" class="panel" aria-label="契约摘要">
        <header class="section-head">
          <h2>契约摘要</h2>
          <el-button
            v-permission="'edit'"
            text
            type="primary"
            @click="toggleEdit"
            >{{ editing ? "退出编辑" : "编辑契约" }}</el-button
          >
        </header>
        <template v-if="kind === 'GUIDE'">
          <dl class="summary-grid">
            <div><dt>name</dt><dd>{{ detail.name || "—" }}</dd></div>
            <div><dt>description</dt><dd>{{ detail.description || "—" }}</dd></div>
          </dl>
          <el-form label-position="top">
            <el-form-item label="frontmatter name">
              <el-input
                v-model="guideName"
                :disabled="!editing"
                aria-label="frontmatter name"
              />
            </el-form-item>
            <el-form-item label="frontmatter description">
              <el-input
                v-model="guideDescription"
                :disabled="!editing"
                aria-label="frontmatter description"
              />
            </el-form-item>
            <el-form-item label="SKILL.md 正文">
              <el-input
                v-model="guideContent"
                type="textarea"
                :rows="10"
                :disabled="!editing"
                aria-label="SKILL.md 正文"
              />
            </el-form-item>
          </el-form>
        </template>
        <template v-else>
          <dl class="summary-grid">
            <div><dt>执行属性</dt><dd><code>{{ JSON.stringify(detail.execution ?? {}) }}</code></dd></div>
            <div><dt>权限守卫</dt><dd><code>{{ JSON.stringify(detail.guard ?? {}) }}</code></dd></div>
            <div>
              <dt>副作用意图声明</dt>
              <dd>{{ detail.businessIntentInput || "未声明（副作用技能发布前必须声明）" }}</dd>
            </div>
          </dl>
          <el-form label-position="top">
            <el-form-item label="输入 Schema">
              <el-input
                v-model="inputJson"
                type="textarea"
                :rows="6"
                :disabled="!editing"
                aria-label="输入 Schema JSON"
              />
            </el-form-item>
            <el-form-item
              id="skill-field-businessIntentInput"
              label="副作用意图字段 businessIntentInput（副作用技能发布前必须声明）"
            >
              <el-input
                v-model="intentInput"
                :disabled="!editing"
                placeholder="声明该技能的业务意图字段，供预检与运行时意图键使用"
                aria-label="businessIntentInput"
              />
            </el-form-item>
            <el-form-item label="输出 Schema">
              <el-input
                v-model="outputJson"
                type="textarea"
                :rows="6"
                :disabled="!editing"
                aria-label="输出 Schema JSON"
              />
            </el-form-item>
          </el-form>
        </template>
        <p v-if="contractError" class="error" role="alert">{{ contractError }}</p>
        <el-button
          v-if="editing"
          type="primary"
          :loading="contractBusy"
          v-permission="'edit'"
          @click="saveContract"
          >保存为草稿</el-button
        >
      </section>

      <!-- 分区二：连接调试（仅 TOOL；GUIDE 无执行契约不渲染） -->
      <section
        v-if="kind === 'TOOL'"
        id="skill-section-connection"
        class="panel"
        aria-label="连接调试"
      >
        <header class="section-head">
          <h2>连接调试</h2>
          <p class="muted">受控测试不进入业务数据；结果由服务端返回。</p>
        </header>
        <el-form label-position="top">
          <el-form-item label="测试输入参数 JSON">
            <el-input
              v-model="connectionInput"
              type="textarea"
              :rows="4"
              aria-label="测试输入参数 JSON"
            />
          </el-form-item>
        </el-form>
        <el-button
          :loading="connectionBusy"
          v-permission="'edit'"
          @click="runConnectionTest"
          >发起受控测试</el-button
        >
        <p v-if="connectionError" class="error" role="alert">{{ connectionError }}</p>
        <div v-if="connectionResult" class="test-result">
          <p>
            <strong>{{ connectionResult.status || connectionResult.runId }}</strong>
            <span class="muted"> · {{ connectionResult.message }}</span>
          </p>
          <pre>{{
            JSON.stringify(connectionResult, null, 2)
          }}</pre>
        </div>
      </section>

      <!-- 分区三：选型验收（仅 TOOL）：用例表格化编辑 + 结果表 -->
      <section
        v-if="kind === 'TOOL'"
        id="skill-section-acceptance"
        class="panel"
        aria-label="选型验收"
      >
        <header class="section-head">
          <h2>选型验收</h2>
          <p class="muted">报告绑定当前版本；无报告不能通过发布预检。</p>
        </header>
        <el-form label-position="top" class="acceptance-form">
          <el-form-item label="选型模型">
            <el-select
              v-model="modelId"
              filterable
              placeholder="选择真实模型条目"
              aria-label="选型模型"
            >
              <el-option
                v-for="m in models"
                :key="m.id"
                :value="m.id"
                :label="m.displayName || m.modelName"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <el-table :data="cases" aria-label="验收用例">
          <el-table-column label="question" min-width="180">
            <template #default="{ row }">
              <el-input v-model="row.question" placeholder="用户问题" />
            </template>
          </el-table-column>
          <el-table-column label="expectSelected" min-width="140">
            <template #default="{ row }">
              <el-input v-model="row.expectSelected" placeholder="无工具预期留空" />
            </template>
          </el-table-column>
          <el-table-column label="expectArgs（JSON）" min-width="160">
            <template #default="{ row }">
              <el-input v-model="row.expectArgs" placeholder="可留空" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ $index }">
              <el-button
                text
                type="danger"
                :disabled="cases.length <= 1"
                @click="removeCase($index)"
                >删除</el-button
              >
            </template>
          </el-table-column>
        </el-table>
        <div class="acceptance-actions">
          <el-button text @click="addCase">添加用例</el-button>
          <el-button
            :loading="acceptanceBusy"
            v-permission="'edit'"
            @click="runAcceptance"
            >运行选型验收</el-button
          >
        </div>
        <p v-if="acceptanceError" class="error" role="alert">{{ acceptanceError }}</p>
        <el-table
          v-if="acceptanceResult?.results?.length"
          :data="acceptanceResult.results"
          aria-label="验收结果"
        >
          <el-table-column prop="index" label="#" width="60" />
          <el-table-column prop="question" label="question" min-width="160" />
          <el-table-column label="结果" width="110">
            <template #default="{ row }">
              <span :class="row.passed ? 'result-ok' : 'result-fail'">{{
                row.passed ? "通过" : "未通过"
              }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="说明" min-width="160" />
        </el-table>
      </section>

      <!-- 分区四：版本与发布时间线（版本列表 + 统计 + 引用） -->
      <section id="skill-section-versions" class="panel" aria-label="版本与发布时间线">
        <header class="section-head">
          <h2>版本与发布时间线</h2>
          <p class="muted">发布版本只读，编辑将创建新草稿；门禁由服务端校验。</p>
        </header>
        <ol class="timeline" aria-label="版本时间线">
          <li
            v-for="v in detail.versions ?? []"
            :key="v.versionNo || v.version"
            :class="['timeline-item', { active: (v.versionNo || v.version) === selected }]"
          >
            <button type="button" class="timeline-row" @click="selected = v.versionNo || v.version">
              <strong>版本 {{ v.versionNo || v.version }}</strong>
              <el-tag
                size="small"
                effect="plain"
                :type="v.status === 'PUBLISHED' ? 'success' : v.status === 'DRAFT' ? 'info' : 'warning'"
                >{{ statusLabel(v.status) }}</el-tag
              >
              <span class="muted">{{ displayTime(v.publishedAt) }}</span>
              <code v-if="v.sha256" class="muted sha">{{ String(v.sha256).slice(0, 12) }}…</code>
            </button>
          </li>
        </ol>
        <div v-if="referencedBy.length" class="refs">
          <h3>被引用</h3>
          <ul>
            <li v-for="ref in referencedBy" :key="ref.appCode || ref.code">
              <a :href="'#/apps/' + encodeURIComponent(ref.appCode || ref.code)">{{
                ref.appName || ref.name || ref.appCode || ref.code
              }}</a>
              <span v-if="ref.version" class="muted"> · v{{ ref.version }}</span>
            </li>
          </ul>
        </div>
        <SkillStatistics :code="detail.code" :versions="detail.versions" />
      </section>
    </template>
  </div>
</template>

<style scoped>
/* F2-2 单页分区：单列分区卡纵向流，滚动只有文档级一条（禁三级滚动）。只引用 tokens 变量。 */
.skill-detail-page {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
  min-width: 0;
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
.error {
  color: var(--st-fail-text);
}
.result-ok {
  color: var(--st-ok-text);
}
.result-fail {
  color: var(--st-fail-text);
}
/* 发布 checklist 卡 */
.precheck-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
}
.precheck-head h2 {
  margin: 0 0 var(--sp-1);
}
.precheck-head p {
  margin: 0;
}
.demo-flag {
  margin-left: var(--sp-2);
  padding: 0 var(--sp-2);
  border: 1px solid var(--st-suspend);
  border-radius: 999px;
  color: var(--st-suspend-text);
  font-size: var(--text-meta);
  cursor: help;
}
.precheck-publish {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--sp-1);
}
.blocked-note {
  max-width: 22rem;
  text-align: right;
  margin: 0;
}
.checklist {
  list-style: none;
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2) var(--sp-5);
  margin: var(--sp-3) 0 0;
  padding: 0;
}
.check-item {
  display: flex;
  flex-direction: column;
  gap: var(--sp-1);
  min-width: 0;
}
.check-label {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  border: 0;
  background: none;
  padding: 0;
  font-size: var(--text-table);
  color: var(--ink);
  cursor: pointer;
  text-align: left;
}
.check-detail {
  color: var(--muted);
  font-size: var(--text-meta);
}
.check-icon {
  font-weight: 700;
}
.check-item.pass .check-icon {
  color: var(--st-ok);
}
.check-item.fail .check-icon {
  color: var(--st-fail);
}
.check-item.pending .check-icon {
  color: var(--st-suspend);
}
.check-item.fail .check-label {
  color: var(--st-fail-text);
}
/* 分区导航与分区卡 */
.section-nav {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-1);
}
.section-nav :deep(.el-button) {
  margin-left: 0;
}
.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--sp-3);
  flex-wrap: wrap;
}
.section-head h2,
.refs h3 {
  margin: 0 0 var(--sp-2);
}
.section-head p {
  margin: 0;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
  gap: var(--sp-2) var(--sp-4);
  margin: 0 0 var(--sp-3);
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
.summary-grid code {
  font-family: var(--font-mono);
  font-size: var(--text-meta);
}
/* checklist 阻断项定位字段的闪烁高亮 */
:deep(.field-flash) {
  outline: 2px solid var(--brand-primary);
  outline-offset: 2px;
  transition: outline-color 0.4s ease;
}
.test-result pre,
.acceptance-form {
  margin-top: var(--sp-3);
}
.test-result pre {
  max-height: 16rem;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--surface-2);
  padding: var(--sp-3);
  border-radius: 0.45rem;
}
.acceptance-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin: var(--sp-2) 0;
}
.acceptance-actions :deep(.el-button) {
  margin-left: 0;
}
/* 版本时间线 */
.timeline {
  list-style: none;
  margin: 0 0 var(--sp-3);
  padding: 0;
  display: flex;
  flex-direction: column;
}
.timeline-item {
  border-left: 2px solid var(--line);
  padding: var(--sp-1) 0 var(--sp-1) var(--sp-4);
}
.timeline-item.active {
  border-left-color: var(--brand-primary);
}
.timeline-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
  border: 0;
  background: none;
  padding: 0;
  color: var(--ink);
  font-size: var(--text-table);
  cursor: pointer;
  text-align: left;
}
.sha {
  font-family: var(--font-mono);
}
.refs ul {
  margin: 0 0 var(--sp-3);
  padding-left: var(--sp-5);
}
@media (max-width: 900px) {
  .precheck-publish {
    align-items: flex-start;
  }
  .blocked-note {
    text-align: left;
  }
}
</style>
