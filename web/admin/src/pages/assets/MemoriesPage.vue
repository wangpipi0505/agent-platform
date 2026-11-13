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
  demoApproveMemory, demoGetDraftMemory, getMemory, listMemories, updateMemory,
  type MockMemoryDetail, type MockMemoryRow, type MemoryScope, type MemoryStatus,
} from "../../../shared/mock-memories";

// F3-3（台账 §7.5 / 前端稿 §5.9 记忆管理·共享层）：资产管理页——记忆。
// scope 私有/共享 chips + status 过滤（ACTIVE/DRAFT/REVOKED，含 DRAFT 提取草稿）；
// DRAFT 审批动作（通过→ACTIVE / 拒绝→REVOKED，接口稿 §5.4 PATCH status 字段语义，
// 确认中心同源待办）；单条正文查看/编辑走版本 CAS（409 MEMORY_VERSION_CONFLICT
// 提示冲突并就地重载）。数据源 mock-memories（F4 已交付形状 + F3-3 DRAFT 增补）。
usePageTitle(() => "记忆管理");

type MemoryListRow = Omit<MockMemoryRow, "demo">;

// —— scope / status chips 过滤（chips 即筛选，先例=任务工作台状态管道）——
const SCOPE_CHIPS: { value: MemoryScope | ""; label: string }[] = [
  { value: "", label: "全部范围" },
  { value: "PRIVATE", label: "私有" },
  { value: "SHARED", label: "共享" },
];
const STATUS_CHIPS: { value: MemoryStatus | ""; label: string }[] = [
  { value: "", label: "全部" },
  { value: "ACTIVE", label: "生效" },
  { value: "DRAFT", label: "待审批草稿" },
  { value: "REVOKED", label: "已撤销" },
];
const activeScope = ref<MemoryScope | "">("");
const activeStatus = ref<MemoryStatus | "">("");

// 列表不含正文（接口稿 §5.4）；「全部」按三状态合并检索（REVOKED 不参与默认检索，
// 显式选择时可见——先权限过滤再检索语义由 mock 保持）。
const { rows, loading: busy, error, refresh: reload } = useLiveList<MemoryListRow>(async () => {
  const wanted = activeStatus.value ? [activeStatus.value] : (["ACTIVE", "DRAFT", "REVOKED"] as MemoryStatus[]);
  const merged = wanted.flatMap((status) => listMemories({ status, scope: activeScope.value || undefined }).memories);
  return merged.sort((a, b) => a.memoryId.localeCompare(b.memoryId));
});
const scopeLabel = (scope: string) => (scope === "SHARED" ? "共享" : "私有");
const statusLabel = (status: string) =>
  status === "DRAFT" ? "待审批草稿" : status === "REVOKED" ? "已撤销" : "生效";

// —— 单条正文查看（列表项无正文：ACTIVE 走单条读取，DRAFT 走管理台草稿预览）——
const viewing = ref<MockMemoryDetail | null>(null);
async function view(row: MemoryListRow) {
  if (row.status === "DRAFT") {
    const detail = demoGetDraftMemory(row.memoryId);
    if ("error" in detail) {
      ElMessage.error("草稿已处理或不存在，请刷新列表");
      void reload();
      return;
    }
    viewing.value = detail;
    return;
  }
  const detail = getMemory(row.memoryId);
  if ("error" in detail) {
    ElMessage.error("该条目已撤销或不可见（REVOKED 立即退出检索）");
    return;
  }
  viewing.value = detail;
}

// —— 编辑（受控表单：正文+标签；version CAS，409 冲突提示重载）——
const editOpen = ref(false);
const editForm = ref({ memoryId: "", content: "", tags: "", expectedVersion: 0 });
const editConflict = ref(false);
const editSaving = ref(false);
function startEdit(detail: MockMemoryDetail) {
  editForm.value = {
    memoryId: detail.memoryId,
    content: detail.content,
    tags: detail.tags.join("、"),
    expectedVersion: detail.version,
  };
  editConflict.value = false;
  viewing.value = null;
  editOpen.value = true;
}
/** 409 MEMORY_VERSION_CONFLICT：冲突提示 + 就地重载正文（重读最新版本再编辑）。 */
async function reloadEditContent() {
  const detail = getMemory(editForm.value.memoryId);
  if ("error" in detail) {
    ElMessage.error("条目已不可见，请刷新列表");
    editOpen.value = false;
    void reload();
    return;
  }
  editForm.value = {
    memoryId: detail.memoryId,
    content: detail.content,
    tags: detail.tags.join("、"),
    expectedVersion: detail.version,
  };
  editConflict.value = false;
  ElMessage.success("已重载最新版本，请在此基础上修改");
}
async function saveEdit() {
  if (!editForm.value.content.trim()) {
    ElMessage.error("正文不能为空");
    return;
  }
  editSaving.value = true;
  const done = updateMemory(editForm.value.memoryId, {
    content: editForm.value.content,
    tags: editForm.value.tags.split(/、|,/).map((t) => t.trim()).filter(Boolean),
    expectedVersion: editForm.value.expectedVersion,
  });
  editSaving.value = false;
  if ("error" in done) {
    if (done.error === "MEMORY_VERSION_CONFLICT") {
      editConflict.value = true;
      ElMessage.error("版本冲突（409）：该记忆已被他人修改，请重载最新版本后再提交");
      return;
    }
    ElMessage.error("保存失败，请刷新后重试");
    return;
  }
  ElMessage.success("已保存（mock 演示，version CAS 提交）");
  editOpen.value = false;
  void reload();
}

// —— DRAFT 审批（确认→ACTIVE / 拒绝→REVOKED；confirmDanger 确认通道）——
const draftCount = computed(() => rows.value.filter((row) => row.status === "DRAFT").length);
async function decideDraft(row: MemoryListRow, action: "ACTIVE" | "REVOKED") {
  if (!(await confirmDanger({
    title: action === "ACTIVE" ? "确认草稿生效" : "拒绝并撤销草稿",
    message: action === "ACTIVE"
      ? `确认将草稿 ${row.memoryId}（${scopeLabel(row.scope)}层）置为 ACTIVE？生效后进入默认检索。`
      : `拒绝草稿 ${row.memoryId} 并置为 REVOKED？撤销后立即退出检索，不可恢复。`,
    confirmText: action === "ACTIVE" ? "确认生效" : "拒绝并撤销",
  })))
    return;
  const done = demoApproveMemory(row.memoryId, action, row.version);
  if ("error" in done) {
    if (done.error === "MEMORY_VERSION_CONFLICT") {
      ElMessage.error("版本冲突（409）：草稿已被处理，列表已刷新");
      void reload();
      return;
    }
    ElMessage.error("草稿不存在或已处理，列表已刷新");
    void reload();
    return;
  }
  ElMessage.success(action === "ACTIVE" ? "草稿已生效（ACTIVE，mock 演示）" : "草稿已拒绝并撤销（REVOKED，mock 演示）");
  void reload();
}
</script>

<template>
  <DataPage :total="null">
    <template #header>
      <PageHeader
        title="记忆管理"
        subtitle="私有/共享记忆与 DRAFT 提取草稿审批（共享层 ADMIN / 私有层来源用户；mock 演示数据，联调期换真实端点）。"
        :busy="busy"
        @refresh="reload"
      />
    </template>
    <template #error>
      <ErrorBanner :error="error" />
    </template>
    <template #filters>
      <div class="pipeline" role="group" aria-label="范围筛选">
        <button
          v-for="chip in SCOPE_CHIPS"
          :key="`scope-${chip.value}`"
          class="chip"
          :class="{ 'is-active': activeScope === chip.value }"
          type="button"
          @click="activeScope = chip.value; reload()"
        >{{ chip.label }}</button>
      </div>
      <div class="pipeline" role="group" aria-label="状态筛选">
        <button
          v-for="chip in STATUS_CHIPS"
          :key="`status-${chip.value}`"
          class="chip"
          :class="{ 'is-active': activeStatus === chip.value }"
          type="button"
          @click="activeStatus = chip.value; reload()"
        >{{ chip.label }}</button>
      </div>
    </template>
    <div class="page-scroll">
      <el-skeleton v-if="busy && !rows.length" :rows="5" animated />
      <el-table v-else :data="rows" stripe height="100%">
        <el-table-column prop="memoryId" label="记忆 ID" min-width="150">
          <template #default="{ row }">
            <span class="mono">{{ row.memoryId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="kind" label="类型" width="120" />
        <el-table-column label="范围" width="90">
          <template #default="{ row }">{{ scopeLabel(row.scope) }}</template>
        </el-table-column>
        <el-table-column label="标签" min-width="140">
          <template #default="{ row }">
            <el-tag v-for="tag in row.tags" :key="tag" size="small" effect="plain" class="tag-gap">{{ tag }}</el-tag>
            <span v-if="!row.tags.length" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span :class="['status', row.status]">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="sourceTaskId" label="来源任务" min-width="120">
          <template #default="{ row }">
            <span v-if="row.sourceTaskId" class="mono">{{ row.sourceTaskId }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="230">
          <template #default="{ row }">
            <template v-if="row.status === 'DRAFT'">
              <el-button text type="primary" @click="view(row)">查看草稿</el-button>
              <el-button text type="primary" @click="decideDraft(row, 'ACTIVE')">通过</el-button>
              <el-button text type="danger" @click="decideDraft(row, 'REVOKED')">拒绝</el-button>
            </template>
            <template v-else-if="row.status === 'ACTIVE'">
              <el-button text @click="view(row)">查看正文</el-button>
            </template>
            <span v-else class="muted">已撤销，仅留档</span>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            :variant="error ? 'error' : 'filtered'"
            :description="error ? error : '当前筛选下暂无记忆条目。'"
            :action-text="error ? '刷新重试' : '查看生效条目'"
            @action="error ? reload() : (activeStatus = 'ACTIVE', reload())"
          />
        </template>
      </el-table>
    </div>

    <!-- 正文/草稿预览：列表项无正文，正文经单条读取（接口稿 §5.4） -->
    <el-dialog
      :model-value="!!viewing"
      title="记忆正文"
      width="min(680px, 96vw)"
      @update:model-value="viewing = null"
    >
      <template v-if="viewing">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="ID"><span class="mono">{{ viewing.memoryId }}</span></el-descriptions-item>
          <el-descriptions-item label="版本">v{{ viewing.version }}</el-descriptions-item>
          <el-descriptions-item label="范围">{{ scopeLabel(viewing.scope) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(viewing.status) }}</el-descriptions-item>
        </el-descriptions>
        <p class="content-body">{{ viewing.content }}</p>
        <p v-if="viewing.status === 'DRAFT'" class="muted">DRAFT 草稿：通过后进入默认检索；拒绝后撤销。</p>
        <footer class="dialog-actions">
          <el-button
            v-if="viewing.status === 'ACTIVE'"
            type="primary"
            @click="startEdit(viewing)"
          >编辑（version CAS）</el-button>
        </footer>
      </template>
    </el-dialog>

    <!-- 编辑：version CAS；409 冲突提示重载 -->
    <el-dialog v-model="editOpen" title="编辑记忆" width="min(680px, 96vw)" destroy-on-close>
      <el-alert
        v-if="editConflict"
        type="warning"
        :closable="false"
        show-icon
        title="版本冲突（409 MEMORY_VERSION_CONFLICT）"
        description="该记忆已被他人修改，当前编辑基于过期版本。"
      >
        <el-button size="small" type="primary" @click="reloadEditContent">重载最新版本</el-button>
      </el-alert>
      <el-form label-position="top">
        <el-form-item label="正文">
          <el-input v-model="editForm.content" type="textarea" :rows="4" />
        </el-form-item>
        <el-form-item label="标签（顿号分隔）">
          <el-input v-model="editForm.tags" />
        </el-form-item>
      </el-form>
      <p class="muted">提交携带 expectedVersion={{ editForm.expectedVersion }}（CAS）。</p>
      <footer class="dialog-actions">
        <el-button :disabled="editSaving" type="primary" @click="saveEdit">保存</el-button>
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
  margin-right: var(--sp-1);
}
.content-body {
  white-space: pre-wrap;
  word-break: break-word;
  border: 1px solid color-mix(in srgb, var(--muted) 30%, transparent);
  border-radius: var(--radius);
  padding: var(--sp-3);
  background: var(--surface-2);
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
}
</style>
