<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage } from "element-plus";
import DataPage from "../../../shared/components/DataPage.vue";
import EmptyState from "../../../shared/components/EmptyState.vue";
import ErrorBanner from "../../../shared/components/ErrorBanner.vue";
import { confirmDanger } from "../../../shared/components/DangerConfirm";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import { useLiveList } from "../../../shared/hooks/useLiveList";
import {
  createRule, listRuleVersions, listRules, publishRule, setRuleEnabled, updateRuleDraft,
  type MockRule, type RuleContent, type RuleNature, type RuleScope, type RuleVersion,
} from "../../../shared/mock-rules";

// F3-3（台账 §7.5 / 前端稿 §3.1 构建资产·规则管理）：资产管理页——规则。
// 列表（nature GUIDANCE/ENFORCED 徽章 + status PUBLISHED/DRAFT）+ 草稿编辑
// （content{trigger,action,requirement}——SchemaForm 简化为三字段受控表单）+
// 发布（预检同 scope 互斥冲突 409 RULE_MUTEX_BLOCKED，展示规则对）+ 启停。
// 数据源 mock-rules（接口稿 §5.5 六条 CRUD 路径形状，demo:true）。
usePageTitle(() => "规则管理");

type RuleRow = MockRule;

const { rows, loading: busy, error, refresh: reload } = useLiveList<RuleRow>(async () => listRules().rules);

// —— chips 过滤：nature / status ——
const NATURE_CHIPS: { value: RuleNature | ""; label: string }[] = [
  { value: "", label: "全部类型" },
  { value: "ENFORCED", label: "ENFORCED（强制）" },
  { value: "GUIDANCE", label: "GUIDANCE（引导）" },
];
const STATUS_CHIPS: { value: "PUBLISHED" | "DRAFT" | ""; label: string }[] = [
  { value: "", label: "全部状态" },
  { value: "PUBLISHED", label: "已发布" },
  { value: "DRAFT", label: "仅草稿" },
];
const activeNature = ref<RuleNature | "">("");
const activeStatus = ref<"PUBLISHED" | "DRAFT" | "">("");
const filteredRows = computed(() =>
  rows.value
    .filter((row) => !activeNature.value || row.nature === activeNature.value)
    .filter((row) => !activeStatus.value || row.status === activeStatus.value),
);

// —— 草稿编辑：三字段受控表单（SchemaForm 简化级）——
const CONTENT_FIELDS: { key: keyof RuleContent; label: string; placeholder: string }[] = [
  { key: "trigger", label: "触发条件（trigger）", placeholder: "何时适用，如：任何删除类数据操作" },
  { key: "action", label: "动作（action）", placeholder: "做什么，如：拒绝执行并提示改用归档" },
  { key: "requirement", label: "要求（requirement）", placeholder: "约束/验收口径，如：审计保留操作者与时间戳" },
];
const draftOpen = ref(false);
const draftForm = ref({ code: "", name: "", content: { trigger: "", action: "", requirement: "" } as RuleContent });
const draftSaving = ref(false);
function openDraft(row: RuleRow) {
  draftForm.value = { code: row.code, name: row.name, content: { ...row.draft } };
  draftOpen.value = true;
}
async function saveDraft() {
  if (!CONTENT_FIELDS.every(({ key }) => draftForm.value.content[key].trim())) {
    ElMessage.error("三字段（trigger/action/requirement）均必填");
    return;
  }
  draftSaving.value = true;
  const done = updateRuleDraft(draftForm.value.code, { content: draftForm.value.content });
  draftSaving.value = false;
  if ("error" in done) {
    ElMessage.error(done.userMessage ?? "草稿保存失败");
    return;
  }
  ElMessage.success("草稿已保存（mock 演示）；发布需另行提交预检");
  draftOpen.value = false;
  void reload();
}

// —— 发布：预检互斥冲突 409 RULE_MUTEX_BLOCKED 展示规则对 ——
const mutexBlock = ref<{ codes: string[]; scope: string }[]>([]);
const mutexOpen = ref(false);
async function publish(row: RuleRow) {
  const done = publishRule(row.code, row.draftVersion);
  if ("error" in done) {
    if (done.error === "RULE_MUTEX_BLOCKED" && done.conflicts?.length) {
      mutexBlock.value = done.conflicts;
      mutexOpen.value = true;
      return;
    }
    ElMessage.error(done.userMessage ?? "发布失败，请刷新后重试");
    void reload();
    return;
  }
  ElMessage.success(`已发布 v${done.published.version}（预检通过，mock 演示）`);
  void reload();
}

// —— 启停（停用走 confirmDanger；ENFORCED 停用后不再参与装配校验）——
async function toggleEnabled(row: RuleRow) {
  const enable = !row.enabled;
  if (!enable && !(await confirmDanger({
    title: "停用规则",
    message: `停用 ${row.name}（${row.code}）？ENFORCED 规则停用后不再参与装配校验。`,
    confirmText: "停用",
  })))
    return;
  const done = setRuleEnabled(row.code, enable, enable ? undefined : "页面停用（mock 演示）");
  if ("error" in done) {
    ElMessage.error(done.userMessage ?? "操作失败");
    return;
  }
  ElMessage.success(enable ? "已启用（mock 演示）" : "已停用（mock 演示）");
  void reload();
}

// —— 版本历史 ——
const versionsOpen = ref(false);
const versions = ref<{ code: string; versions: RuleVersion[] } | null>(null);
function openVersions(row: RuleRow) {
  const done = listRuleVersions(row.code);
  if ("error" in done) {
    ElMessage.error("规则不存在，请刷新");
    void reload();
    return;
  }
  versions.value = done;
  versionsOpen.value = true;
}

// —— 新建规则（POST 路径）——
const createOpen = ref(false);
const createForm = ref({ code: "", name: "", nature: "GUIDANCE" as RuleNature, scope: "OUTPUT" as RuleScope,
  mutexKey: "", content: { trigger: "", action: "", requirement: "" } as RuleContent });
const createSaving = ref(false);
function openCreate() {
  createForm.value = { code: "", name: "", nature: "GUIDANCE", scope: "OUTPUT", mutexKey: "",
    content: { trigger: "", action: "", requirement: "" } };
  createOpen.value = true;
}
async function saveCreate() {
  if (!createForm.value.code.trim() || !createForm.value.name.trim()
    || !CONTENT_FIELDS.every(({ key }) => createForm.value.content[key].trim())) {
    ElMessage.error("编码/名称与正文三字段均必填");
    return;
  }
  createSaving.value = true;
  const done = createRule({
    appCode: "recon-assistant", code: createForm.value.code.trim(), name: createForm.value.name.trim(),
    nature: createForm.value.nature, scope: createForm.value.scope,
    mutexKey: createForm.value.mutexKey.trim() || undefined, content: createForm.value.content,
  });
  createSaving.value = false;
  if ("error" in done) {
    ElMessage.error(done.userMessage ?? "创建失败");
    return;
  }
  ElMessage.success("规则已登记（初始仅草稿，mock 演示）");
  createOpen.value = false;
  void reload();
}
</script>

<template>
  <DataPage :total="null">
    <template #header>
      <PageHeader
        title="规则管理"
        subtitle="GUIDANCE/ENFORCED 规则：草稿编辑、发布预检（同 scope 互斥阻断）与启停（mock 演示数据，联调期换真实端点）。"
        :busy="busy"
        @refresh="reload"
      >
        <template #actions>
          <el-button type="primary" @click="openCreate">新建规则</el-button>
        </template>
      </PageHeader>
    </template>
    <template #error>
      <ErrorBanner :error="error" />
    </template>
    <template #filters>
      <div class="pipeline" role="group" aria-label="类型筛选">
        <button
          v-for="chip in NATURE_CHIPS"
          :key="`nature-${chip.value}`"
          class="chip"
          :class="{ 'is-active': activeNature === chip.value }"
          type="button"
          @click="activeNature = chip.value"
        >{{ chip.label }}</button>
      </div>
      <div class="pipeline" role="group" aria-label="状态筛选">
        <button
          v-for="chip in STATUS_CHIPS"
          :key="`status-${chip.value}`"
          class="chip"
          :class="{ 'is-active': activeStatus === chip.value }"
          type="button"
          @click="activeStatus = chip.value"
        >{{ chip.label }}</button>
      </div>
    </template>
    <div class="page-scroll">
      <el-skeleton v-if="busy && !rows.length" :rows="5" animated />
      <el-table v-else :data="filteredRows" stripe height="100%">
        <el-table-column label="规则" min-width="200">
          <template #default="{ row }">
            <div>
              <span>{{ row.name }}</span>
              <span class="mono muted"> {{ row.code }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-tag :type="row.nature === 'ENFORCED' ? 'danger' : 'info'" size="small" effect="plain">
              {{ row.nature }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="scope" label="scope" width="110" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span :class="['status', row.status]">{{ row.status === "PUBLISHED" ? "已发布" : "仅草稿" }}</span>
            <el-tag v-if="row.status === 'PUBLISHED'" size="small" :type="row.enabled ? 'success' : 'warning'" effect="plain" class="tag-gap">
              {{ row.enabled ? "启用" : "停用" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="130">
          <template #default="{ row }">
            <span v-if="row.version > 0">v{{ row.version }}</span>
            <span v-else class="muted">未发布</span>
            <el-tag v-if="row.draftVersion > row.version" size="small" type="warning" effect="plain" class="tag-gap">
              草稿 v{{ row.draftVersion }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="250">
          <template #default="{ row }">
            <el-button text type="primary" @click="openDraft(row)">草稿编辑</el-button>
            <el-button
              v-if="row.draftVersion > row.version"
              text
              type="primary"
              @click="publish(row)"
            >发布</el-button>
            <el-button
              v-if="row.status === 'PUBLISHED'"
              text
              :type="row.enabled ? 'danger' : 'primary'"
              @click="toggleEnabled(row)"
            >{{ row.enabled ? "停用" : "启用" }}</el-button>
            <el-button text @click="openVersions(row)">版本</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            :variant="error ? 'error' : 'filtered'"
            :description="error ? error : '当前筛选下暂无规则。'"
            :action-text="error ? '刷新重试' : '清除筛选'"
            @action="error ? reload() : (activeNature = '', activeStatus = '')"
          />
        </template>
      </el-table>
    </div>

    <!-- 草稿编辑：三字段受控表单（SchemaForm 简化级） -->
    <el-dialog v-model="draftOpen" :title="`草稿编辑：${draftForm.name}`" width="min(680px, 96vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item v-for="field in CONTENT_FIELDS" :key="field.key" :label="field.label">
          <el-input
            v-model="draftForm.content[field.key]"
            type="textarea"
            :rows="2"
            :placeholder="field.placeholder"
          />
        </el-form-item>
      </el-form>
      <footer class="dialog-actions">
        <el-button :disabled="draftSaving" type="primary" @click="saveDraft">保存草稿</el-button>
      </footer>
    </el-dialog>

    <!-- 发布预检互斥冲突：409 RULE_MUTEX_BLOCKED 展示规则对 -->
    <el-dialog v-model="mutexOpen" title="发布预检阻断：同 scope 互斥冲突（409 RULE_MUTEX_BLOCKED）" width="min(560px, 96vw)">
      <el-alert type="error" :closable="false" show-icon
        title="发布被预检阻断" description="以下规则对在同一 scope 互斥；需先停用对方或调整互斥键。" />
      <div v-for="(conflict, index) in mutexBlock" :key="index" class="mutex-pair">
        <span class="mono">{{ conflict.codes.join(" ⟷ ") }}</span>
        <el-tag size="small" effect="plain">scope: {{ conflict.scope }}</el-tag>
      </div>
    </el-dialog>

    <!-- 版本历史 -->
    <el-dialog v-model="versionsOpen" :title="`版本历史：${versions?.code ?? ''}`" width="min(680px, 96vw)">
      <el-timeline v-if="versions?.versions.length">
        <el-timeline-item
          v-for="item in versions.versions"
          :key="item.version"
          :timestamp="`v${item.version} · ${item.publishedAt} · ${item.publishedBy}`"
        >
          <div class="version-content">
            <p>触发：{{ item.content.trigger }}</p>
            <p>动作：{{ item.content.action }}</p>
            <p>要求：{{ item.content.requirement }}</p>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="尚无已发布版本（仅草稿）" />
    </el-dialog>

    <!-- 新建规则 -->
    <el-dialog v-model="createOpen" title="新建规则" width="min(680px, 96vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="编码">
          <el-input v-model="createForm.code" placeholder="如 no-direct-delete" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="createForm.name" />
        </el-form-item>
        <el-form-item label="类型（nature）">
          <el-radio-group v-model="createForm.nature">
            <el-radio-button value="GUIDANCE">GUIDANCE</el-radio-button>
            <el-radio-button value="ENFORCED">ENFORCED</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="scope">
          <el-select v-model="createForm.scope" style="width: 12rem">
            <el-option value="DATA" label="DATA" />
            <el-option value="OPERATION" label="OPERATION" />
            <el-option value="OUTPUT" label="OUTPUT" />
          </el-select>
        </el-form-item>
        <el-form-item label="互斥键（ENFORCED 同 scope 同键互斥）">
          <el-input v-model="createForm.mutexKey" placeholder="留空则不参与互斥" />
        </el-form-item>
        <el-form-item v-for="field in CONTENT_FIELDS" :key="field.key" :label="field.label">
          <el-input v-model="createForm.content[field.key]" type="textarea" :rows="2" :placeholder="field.placeholder" />
        </el-form-item>
      </el-form>
      <footer class="dialog-actions">
        <el-button :disabled="createSaving" type="primary" @click="saveCreate">登记规则</el-button>
      </footer>
    </el-dialog>
  </DataPage>
</template>

<style scoped>
.page-scroll {
  height: 100%;
  overflow-y: auto;
}
.pipeline {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-2);
  align-items: center;
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
.mono {
  font-family: var(--font-mono, monospace);
}
.muted {
  color: var(--muted);
  font-size: var(--text-meta);
}
.tag-gap {
  margin-left: var(--sp-1);
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--sp-3);
}
.mutex-pair {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2) 0;
  border-bottom: 1px dashed color-mix(in srgb, var(--muted) 35%, transparent);
}
.version-content p {
  margin: 0 var(--sp-2);
}
</style>
