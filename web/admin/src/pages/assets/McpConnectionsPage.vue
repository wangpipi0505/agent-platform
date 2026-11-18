<script setup lang="ts">
import { reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import DataPage from "../../../../shared/components/DataPage.vue";
import EmptyState from "../../../../shared/components/EmptyState.vue";
import ErrorBanner from "../../../../shared/components/ErrorBanner.vue";
import { confirmDanger } from "../../../../shared/components/DangerConfirm";
import PageHeader from "../../PageHeader.vue";
import { usePageTitle } from "../../usePageOperation";
import { useLiveList } from "../../../../shared/hooks/useLiveList";
import {
  createMcpConnection, discoverMcpTools, getMcpConnection, listMcpConnections, listMcpTools,
  reviewMcpTools, testMcpConnection, updateMcpConnection,
  type ApiFailure, type McpFailureCategory, type McpTool, type McpTransport, type MockMcpConnection,
} from "../../../../shared/api-mcp";

// F3-3（台账 §7.5 / 前端稿 §3.1 集成与治理·MCP 连接）：资产管理页——MCP 连接。
// 连接列表 + 连通测试（返回具体失败类别）+ 发现（批次串行 batch_no）+
// 快照差异（ADDED/CHANGED/REMOVED）+ 审核生成技能草稿（skillDraftRef）。
// test/discover 绑定当前连接修订号：配置变更 revision+1（评审 12）。
// 数据面（M5-2 切真实 API）：shared/api-mcp——/admin/mcp-connections 七条路径（经网关）。
// 诚实边界：服务端无 GET 列表端点——列表以本地已知 id 索引逐条 GET 聚合（均为服务端真实行）。
usePageTitle(() => "MCP 连接");

const { rows, loading: busy, error, refresh: reload } = useLiveList<MockMcpConnection>(async () => listMcpConnections());

const FAILURE_LABEL: Record<McpFailureCategory, string> = {
  AUTH: "鉴权失败", TIMEOUT: "网关超时", PROTOCOL: "协议不兼容", ENDPOINT_UNREACHABLE: "端点不可达",
};
const transportLabel = (t: McpTransport) => ({ STDIO: "STDIO", HTTP: "HTTP", SSE: "SSE" })[t] ?? t;

function rowOf(id: string): MockMcpConnection | undefined {
  return rows.value.find((c) => c.id === id);
}

// —— 连通测试：成功 OK / 失败展示具体失败类别 ——
const testing = reactive(new Set<string>());
async function testConnection(row: MockMcpConnection) {
  testing.add(row.id);
  const done = await testMcpConnection(row.id);
  testing.delete(row.id);
  if ("error" in done) {
    ElMessage.error("连接不存在，请刷新");
    void reload();
    return;
  }
  if (done.result === "OK") {
    ElMessage.success(`连通正常（revision ${done.revision}）`);
  } else {
    ElMessage.error(`连通失败：${FAILURE_LABEL[done.failureCategory as McpFailureCategory] ?? done.failureCategory}（${done.failureCategory}）`);
  }
  void reload();
}
const lastTestText = (row: MockMcpConnection) => {
  if (!row.lastTest) return "未测试";
  if (row.lastTest.result === "OK") return `通过（r${row.lastTest.revision}）`;
  return `失败：${FAILURE_LABEL[row.lastTest.failureCategory as McpFailureCategory] ?? row.lastTest.failureCategory}`;
};

// —— 发现（批次串行，batch_no）：发现按钮置忙，完成后提示目录版本 ——
const discovering = reactive(new Set<string>());
async function discover(row: MockMcpConnection) {
  discovering.add(row.id);
  const accepted = await discoverMcpTools(row.id);
  if ("error" in accepted) {
    discovering.delete(row.id);
    ElMessage.error((accepted as ApiFailure).userMessage ?? "触发发现失败");
    return;
  }
  ElMessage.info(`发现批次 ${accepted.batchNo ?? ""} 已受理（串行执行）`.trim());
  // 读取快照差异（服务端批次串行；diff 视图即当前目录差异）。
  const result = await listMcpTools(row.id, { diff: true });
  discovering.delete(row.id);
  if ("error" in result) {
    ElMessage.error("目录读取失败，请刷新");
    void reload();
    return;
  }
  const diff = (result.diff ?? { added: [], changed: [], removed: [] }) as {
    added: McpTool[]; changed: McpTool[]; removed: McpTool[];
  };
  ElMessage.success(
    `批次 ${result.batchNo ?? ""} 完成：新增 ${diff.added.length}、变更 ${diff.changed.length}、移除 ${diff.removed.length}；目录版本 batch_no=${result.batchNo ?? "-"}`,
  );
  void reload();
}

// —— 快照差异（ADDED/CHANGED/REMOVED 徽章）——
const diffOpen = ref(false);
const diffConnection = ref<MockMcpConnection | null>(null);
const diffTools = ref<McpTool[]>([]);
const diffResult = ref<{ added: McpTool[]; changed: McpTool[]; removed: McpTool[] } | null>(null);
const CHANGE_LABEL: Record<string, string> = { ADDED: "新增", CHANGED: "变更", REMOVED: "移除" };
async function openDiff(row: MockMcpConnection) {
  const detail = await getMcpConnection(row.id);
  if ("error" in detail) {
    ElMessage.error("连接不存在，请刷新");
    void reload();
    return;
  }
  const result = await listMcpTools(row.id, { diff: true });
  if ("error" in result) {
    ElMessage.error("目录读取失败，请刷新");
    return;
  }
  diffConnection.value = detail;
  diffTools.value = (result.tools ?? []) as McpTool[];
  diffResult.value = (result.diff ?? null) as { added: McpTool[]; changed: McpTool[]; removed: McpTool[] } | null;
  diffOpen.value = true;
}
const changeTypeOf = (tool: McpTool) => tool.changeType ?? "";

// —— 审核生成技能草稿（skillDraftRef）——
const draftRefs = reactive(new Map<string, { draftId: string; code: string; name: string }>());
async function reviewTool(tool: McpTool) {
  if (!diffConnection.value) return;
  const done = await reviewMcpTools(diffConnection.value.id, { toolName: tool.toolName });
  if ("error" in done) {
    ElMessage.error((done as ApiFailure).userMessage ?? "审核失败");
    return;
  }
  const ref = (done as { skillDraftRef?: { draftId: string; code: string; name: string } }).skillDraftRef;
  if (ref) draftRefs.set(tool.toolName, ref);
  ElMessage.success(`技能草稿已生成：${ref?.code ?? tool.toolName}`);
}
const draftRefOf = (toolName: string) => draftRefs.get(toolName) ?? null;

// —— 配置编辑（revision+1：旧修订测试/发现结果不入目录，评审 12）——
const editOpen = ref(false);
const editForm = ref({ id: "", name: "", endpoint: "", transport: "HTTP" as McpTransport });
const editSaving = ref(false);
function openEdit(row: MockMcpConnection) {
  editForm.value = { id: row.id, name: row.name, endpoint: row.endpoint, transport: row.transport };
  editOpen.value = true;
}
async function saveEdit() {
  if (!editForm.value.name.trim() || !editForm.value.endpoint.trim()) {
    ElMessage.error("名称与端点必填");
    return;
  }
  editSaving.value = true;
  const done = await updateMcpConnection(editForm.value.id, {
    name: editForm.value.name, endpoint: editForm.value.endpoint, transport: editForm.value.transport,
  });
  editSaving.value = false;
  if ("error" in done) {
    ElMessage.error("连接不存在，请刷新");
    void reload();
    return;
  }
  ElMessage.success(`配置已更新：revision 升至 ${done.revision ?? ""}（旧修订测试/发现结果不入目录）`.trim());
  editOpen.value = false;
  void reload();
}

// —— 登记连接 ——
const createOpen = ref(false);
const createForm = ref({ name: "", endpoint: "", transport: "HTTP" as McpTransport });
const createSaving = ref(false);
async function saveCreate() {
  if (!createForm.value.name.trim() || !createForm.value.endpoint.trim()) {
    ElMessage.error("名称与端点必填");
    return;
  }
  createSaving.value = true;
  const done = await createMcpConnection(createForm.value);
  createSaving.value = false;
  if ("error" in done) {
    ElMessage.error(done.userMessage ?? "登记失败");
    return;
  }
  ElMessage.success("连接档案已登记");
  createOpen.value = false;
  void reload();
}
</script>

<template>
  <DataPage :total="null">
    <template #header>
      <PageHeader
        title="MCP 连接"
        subtitle="连接档案登记、连通测试（具体失败类别）、工具发现（批次串行）与快照差异审核（真实端点 /admin/mcp-connections）。"
        :busy="busy"
        @refresh="reload"
      >
        <template #actions>
          <el-button type="primary" @click="createOpen = true">登记连接</el-button>
        </template>
      </PageHeader>
    </template>
    <template #error>
      <ErrorBanner :error="error" />
    </template>
    <div class="page-scroll">
      <el-skeleton v-if="busy && !rows.length" :rows="5" animated />
      <el-table v-else :data="rows" stripe height="100%">
        <el-table-column label="连接" min-width="170">
          <template #default="{ row }">
            <span>{{ row.name }}</span>
            <span class="mono muted"> {{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="endpoint" label="端点" min-width="210" show-overflow-tooltip />
        <el-table-column label="传输" width="90">
          <template #default="{ row }">{{ transportLabel(row.transport) }}</template>
        </el-table-column>
        <el-table-column prop="revision" label="修订号" width="80" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span :class="['status', row.status]">{{
              row.status === "CONNECTED" ? "已连通" : row.status === "FAILED" ? "连接失败" : "未测试"
            }}</span>
          </template>
        </el-table-column>
        <el-table-column label="目录版本" width="90">
          <template #default="{ row }">
            <span v-if="row.catalogBatchNo" class="mono">b{{ row.catalogBatchNo }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="最近测试" min-width="140">
          <template #default="{ row }">{{ lastTestText(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="270">
          <template #default="{ row }">
            <el-button text type="primary" :disabled="testing.has(row.id)" @click="testConnection(row)">连通测试</el-button>
            <el-button text type="primary" :disabled="discovering.has(row.id)" @click="discover(row)">发现</el-button>
            <el-button text @click="openDiff(row)">快照差异</el-button>
            <el-button text @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            :variant="error ? 'error' : 'create'"
            :description="error ? error : '暂无 MCP 连接；登记后可测试与发现工具。'"
            :action-text="error ? '刷新重试' : '登记连接'"
            @action="error ? reload() : (createOpen = true)"
          />
        </template>
      </el-table>
    </div>

    <!-- 快照差异：ADDED/CHANGED/REMOVED + 审核生成技能草稿 -->
    <el-dialog
      :model-value="diffOpen"
      :title="`快照差异：${diffConnection?.name ?? ''}（目录版本 b${diffConnection?.catalogBatchNo ?? '—'}）`"
      width="min(760px, 96vw)"
      @update:model-value="diffOpen = false"
    >
      <div v-if="diffResult" class="diff-summary">
        <el-tag type="success" effect="plain">新增 {{ diffResult.added.length }}</el-tag>
        <el-tag type="warning" effect="plain">变更 {{ diffResult.changed.length }}</el-tag>
        <el-tag type="danger" effect="plain">移除 {{ diffResult.removed.length }}</el-tag>
      </div>
      <el-table :data="diffTools" size="small" height="320">
        <el-table-column prop="toolName" label="工具" min-width="150">
          <template #default="{ row }"><span class="mono">{{ row.toolName }}</span></template>
        </el-table-column>
        <el-table-column prop="description" label="描述" min-width="180" show-overflow-tooltip />
        <el-table-column label="差异" width="90">
          <template #default="{ row }">
            <el-tag
              v-if="changeTypeOf(row)"
              :type="row.changeType === 'ADDED' ? 'success' : row.changeType === 'CHANGED' ? 'warning' : 'danger'"
              size="small"
            >{{ CHANGE_LABEL[row.changeType] }}</el-tag>
            <span v-else class="muted">无变化</span>
          </template>
        </el-table-column>
        <el-table-column label="审核" width="180">
          <template #default="{ row }">
            <el-button v-if="!draftRefOf(row.toolName)" text type="primary" @click="reviewTool(row)">
              生成技能草稿
            </el-button>
            <span v-else class="mono draft-ref">{{ draftRefOf(row.toolName)!.code }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 配置编辑：revision+1 -->
    <el-dialog v-model="editOpen" title="编辑连接（revision+1）" width="min(560px, 96vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="端点">
          <el-input v-model="editForm.endpoint" />
        </el-form-item>
        <el-form-item label="传输">
          <el-select v-model="editForm.transport" style="width: 12rem">
            <el-option value="HTTP" label="HTTP" />
            <el-option value="SSE" label="SSE" />
            <el-option value="STDIO" label="STDIO" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="muted">配置变更 revision+1；旧修订的测试/发现结果拦截不入目录（评审 12）。</p>
      <footer class="dialog-actions">
        <el-button :disabled="editSaving" type="primary" @click="saveEdit">保存</el-button>
      </footer>
    </el-dialog>

    <!-- 登记连接 -->
    <el-dialog v-model="createOpen" title="登记 MCP 连接" width="min(560px, 96vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="名称">
          <el-input v-model="createForm.name" />
        </el-form-item>
        <el-form-item label="端点">
          <el-input v-model="createForm.endpoint" />
        </el-form-item>
        <el-form-item label="传输">
          <el-select v-model="createForm.transport" style="width: 12rem">
            <el-option value="HTTP" label="HTTP" />
            <el-option value="SSE" label="SSE" />
            <el-option value="STDIO" label="STDIO" />
          </el-select>
        </el-form-item>
      </el-form>
      <footer class="dialog-actions">
        <el-button :disabled="createSaving" type="primary" @click="saveCreate">登记</el-button>
      </footer>
    </el-dialog>
  </DataPage>
</template>

<style scoped>
.page-scroll {
  height: 100%;
  overflow-y: auto;
}
.mono {
  font-family: var(--font-mono, monospace);
}
.muted {
  color: var(--muted);
  font-size: var(--text-meta);
}
.diff-summary {
  display: flex;
  gap: var(--sp-2);
  margin-bottom: var(--sp-2);
}
.draft-ref {
  color: var(--st-ok-text);
  font-size: var(--text-meta);
}
.dialog-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--sp-3);
}
</style>
