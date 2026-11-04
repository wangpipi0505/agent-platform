<script setup lang="ts">
/** ONB-02：前置连接档案与规格诊断步骤。只读探测真实解析（复用技能导入预览接口），
 * 逐操作展示支持子集与拒绝原因（来自服务端，不写死统计）；失败/部分失败可恢复不静默；
 * 模板提交入口属 ONB 服务端后续子步，沿用硬禁用+登记策略。 */
import { computed, onMounted, ref } from "vue";
import { useApi, useAuth } from "../../../../shared/api";
import { documentProblem } from "../../../../shared/openapi-import";
import {
  buildPreflightSummary,
  canUseOperation,
  grantedConnections,
  normalizePreview,
  PREFLIGHT_SERVICE_NOTE,
  type ConnectionProfileView,
  type PreflightPreview,
} from "../../../../shared/onboarding";

const api = useApi();
const auth = useAuth();

/* 区A：连接档案（只列被授予/已批准档案；ADMIN 可读目录，业务配置者看到说明）。 */
const connections = ref<ConnectionProfileView[]>([]);
const connectionsBusy = ref(false);
const connectionsError = ref("");
const canListConnections = computed(() => auth.can("admin"));
async function loadConnections() {
  if (!canListConnections.value) return;
  connectionsBusy.value = true;
  connectionsError.value = "";
  try {
    const rows = await api<ConnectionProfileView[]>("/admin/connection-profiles");
    connections.value = grantedConnections(Array.isArray(rows) ? rows : []);
  } catch (e: any) {
    connectionsError.value = e.message;
  } finally {
    connectionsBusy.value = false;
  }
}
onMounted(loadConnections);

/* 区B：规格诊断（粘贴原始规格→只读预览解析；失败保留原文可重试）。 */
const document = ref("");
const preview = ref<PreflightPreview>();
const diagnosing = ref(false);
const diagnoseError = ref("");
const diagnosisDone = ref(false);
async function diagnose() {
  const problem = documentProblem(document.value);
  if (problem) {
    diagnoseError.value = problem;
    return;
  }
  diagnosing.value = true;
  diagnoseError.value = "";
  const source = document.value;
  try {
    const response = await api("/admin/skills/import-openapi/preview", "POST", {
      document: source,
    });
    // 文档已变化时丢弃旧响应，避免错配诊断。
    if (document.value === source) {
      preview.value = normalizePreview(response);
      diagnosisDone.value = true;
    }
  } catch (e: any) {
    diagnoseError.value = e.message;
    diagnosisDone.value = false;
  } finally {
    diagnosing.value = false;
  }
}
function resetDiagnosis() {
  document.value = "";
  preview.value = undefined;
  diagnoseError.value = "";
  diagnosisDone.value = false;
}
const summary = computed(() =>
  preview.value ? buildPreflightSummary(preview.value) : undefined,
);
/* ③模板目标只能从支持子集勾选；不支持操作在表格中明确标禁。 */
const selectedOperations = ref<string[]>([]);
function toggleOperation(operationId: string, usable: boolean) {
  if (!usable) return;
  selectedOperations.value = selectedOperations.value.includes(operationId)
    ? selectedOperations.value.filter((id) => id !== operationId)
    : [...selectedOperations.value, operationId];
}
const selectedUsable = computed(() =>
  (preview.value?.operations ?? []).filter(
    (op) => canUseOperation(op) && selectedOperations.value.includes(op.operationId),
  ),
);
</script>
<template>
  <section class="preflight" aria-label="前置连接与规格诊断">
    <h2>前置连接与规格诊断</h2>
    <p class="muted">
      先核对连接档案与规格可支持的操作子集，再进入模板登记；诊断为只读解析，不创建任何资源，失败不会生成技能或应用。
    </p>

    <section class="block" aria-label="连接档案">
      <h3>连接档案（只列已批准）</h3>
      <p v-if="!canListConnections" class="muted" role="note">
        连接档案目录需 ADMIN 查看；业务配置者由管理员授予连接后，在此流程中选择对应环境。
      </p>
      <template v-else>
        <el-button size="small" :loading="connectionsBusy" @click="loadConnections">
          刷新档案
        </el-button>
        <p v-if="connectionsError" class="error-text" role="alert">
          连接档案读取失败：{{ connectionsError }}
          <el-button text @click="loadConnections">重试</el-button>
        </p>
        <el-empty
          v-else-if="!connections.length && !connectionsBusy"
          description="暂无已批准的连接档案；未批准连接不会出现在模板可选范围。"
        />
        <el-table v-else :data="connections" size="small">
          <el-table-column label="名称" min-width="140">
            <template #default="{ row }">{{ row.name ?? row.profileId }}</template>
          </el-table-column>
          <el-table-column label="业务系统" min-width="110">
            <template #default="{ row }">{{ row.bizSystemCode ?? "—" }}</template>
          </el-table-column>
          <el-table-column label="环境" width="110">
            <template #default="{ row }">{{ row.environment ?? "—" }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">{{ row.status ?? "—" }}</template>
          </el-table-column>
        </el-table>
      </template>
    </section>

    <section class="block" aria-label="规格诊断">
      <h3>原始规格只读诊断</h3>
      <el-input
        v-model="document"
        type="textarea"
        :rows="6"
        aria-label="OpenAPI 规格文本"
        placeholder="粘贴 OpenAPI 3.0 JSON / YAML 文档（≤1 MiB），或退回专业导入入口"
      />
      <div class="row">
        <el-button type="primary" :loading="diagnosing" @click="diagnose">
          {{ preview ? "重新诊断" : "开始只读诊断" }}
        </el-button>
        <el-button v-if="document || preview" :disabled="diagnosing" @click="resetDiagnosis">
          清空重来
        </el-button>
        <span class="muted">诊断失败或部分失败可随时重试；原文保留在本页，不自动提交。</span>
      </div>
      <p v-if="diagnoseError" class="error-text" role="alert">
        诊断失败：{{ diagnoseError }}（原文已保留，修正后重试；也可退回 OpenAPI 专业导入入口。）
      </p>
      <template v-if="preview">
        <p class="muted" role="status">
          规格摘要 <code>{{ preview.documentSha256.slice(0, 16) }}…</code> ·
          支持子集 {{ summary?.supportedCount }} / 共 {{ summary?.total }} 个操作
          <el-tag v-if="summary?.partial" type="warning" size="small" effect="plain">部分支持</el-tag>
          <el-tag v-else-if="summary?.allBlocked" type="danger" size="small" effect="plain">全部不可支持</el-tag>
        </p>
        <el-alert
          v-if="summary?.partial || summary?.allBlocked"
          type="warning"
          :closable="false"
          show-icon
          title="存在不支持的操作"
          description="不支持项不会被模板标成可用；拒绝原因逐条来自服务端诊断，按责任角色处理后可重新诊断。"
        />
        <p v-for="(warning, index) in preview.warnings" :key="index" class="muted warning-line">
          服务端提示：{{ warning }}
        </p>
        <el-table :data="preview.operations" size="small">
          <el-table-column label="选择" width="70">
            <template #default="{ row }">
              <el-checkbox
                :model-value="selectedOperations.includes(row.operationId)"
                :disabled="!canUseOperation(row)"
                :aria-label="'选择操作 ' + row.operationId"
                @change="() => toggleOperation(row.operationId, canUseOperation(row))"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="200">
            <template #default="{ row }">
              <code>{{ row.method }} {{ row.path }}</code>
              <div class="muted op-id">{{ row.operationId }}</div>
            </template>
          </el-table-column>
          <el-table-column label="支持" width="120">
            <template #default="{ row }">
              <el-tag :type="row.supported ? 'success' : 'danger'" size="small" effect="plain">
                {{ row.supported ? "可支持" : "不支持" }}
              </el-tag>
              <el-tag v-if="row.requiresAuth" type="info" size="small" effect="plain">需认证</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="拒绝原因（服务端逐条）" min-width="260">
            <template #default="{ row }">
              <p v-for="(reason, index) in row.reasons" :key="index" class="muted reason-line">
                {{ reason }}
              </p>
              <span v-if="!row.reasons.length && row.supported" class="muted">—</span>
            </template>
          </el-table-column>
        </el-table>
        <p class="muted">
          已选可支持操作 {{ selectedUsable.length }} 个；不支持项不可勾选。拒绝原因定位到
          operation/字段/关键字，处理入口见各原因描述；本页不改写业务 Schema，也不把业务限制转成改接口要求。
        </p>
      </template>
    </section>

    <section class="block" aria-label="模板目标">
      <h3>模板目标登记（服务端未启用）</h3>
      <p class="muted">{{ PREFLIGHT_SERVICE_NOTE }}</p>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="提交入口暂未启用"
        description="ONB 服务端（前置诊断与模板落库）交付前，本步骤结果不创建任何资源；已选操作与诊断摘要在登记 JSON 中随模板一并登记。"
      />
      <pre class="summary">{{ summary ? JSON.stringify({ ...summary, selectedOperations: selectedUsable.map((op) => op.operationId) }, null, 2) : "尚未诊断。" }}</pre>
    </section>
  </section>
</template>
<style scoped>
.preflight { min-width: 0; }
.block { margin: 1rem 0; }
.block h3 { margin: 0 0 .5rem; }
.warning-line, .reason-line { margin: .15rem 0; overflow-wrap: anywhere; }
.op-id { font-size: var(--text-meta, .8125rem); overflow-wrap: anywhere; }
.summary { max-height: 24vh; overflow: auto; white-space: pre-wrap; word-break: break-word; background: var(--el-fill-color-light, #f5f7fa); padding: .75rem; border-radius: .45rem; }
</style>
