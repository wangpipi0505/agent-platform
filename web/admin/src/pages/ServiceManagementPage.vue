<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessageBox } from "element-plus";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import { usePageOperation, usePageTitle } from "../usePageOperation";
import {
  commandFromText,
  commandText,
  serviceActionPath,
  serviceStateClass,
  serviceStateLabel,
} from "./service-admin";

usePageTitle(() => "服务管理");
const { auth, api, busy, error, trace, operation, success, failure } = usePageOperation();
const rows = ref<any[]>([]);
const dialog = ref(false);
const saving = ref(false);
const editing = ref<any>();
const form = reactive({
  code: "",
  name: "",
  command: "",
  workDir: "",
  port: 8080,
  healthPath: "/",
  startupTimeoutMs: 30000,
  stopTimeoutMs: 5000,
  enabled: true,
});
const logs = reactive({ visible: false, code: "", text: "", available: true });
const health = reactive({ visible: false, code: "", result: null as any });

const canManage = () => auth.can("admin");

async function load() {
  if (!auth.token) return;
  await operation(async () => {
    rows.value = await api<any[]>("/admin/services");
  });
}

function reset() {
  Object.assign(form, {
    code: "",
    name: "",
    command: "",
    workDir: "",
    port: 8080,
    healthPath: "/",
    startupTimeoutMs: 30000,
    stopTimeoutMs: 5000,
    enabled: true,
  });
}

function create() {
  editing.value = undefined;
  reset();
  dialog.value = true;
}

function edit(row: any) {
  editing.value = row;
  Object.assign(form, {
    code: row.code,
    name: row.name,
    command: commandText(row.command),
    workDir: row.workDir,
    port: row.port,
    healthPath: row.healthPath,
    startupTimeoutMs: row.startupTimeoutMs,
    stopTimeoutMs: row.stopTimeoutMs,
    enabled: row.enabled,
  });
  dialog.value = true;
}

async function save() {
  saving.value = true;
  try {
    const body = { ...form, command: commandFromText(form.command) };
    if (editing.value) await api(`/admin/services/${encodeURIComponent(form.code)}`, "PUT", body);
    else await api("/admin/services", "POST", body);
    success(editing.value ? "服务配置已更新" : "服务已登记");
    dialog.value = false;
    await load();
  } catch (e) {
    failure(e);
  } finally {
    saving.value = false;
  }
}

async function start(row: any) {
  await operation(async () => {
    await api(`/admin/services/${encodeURIComponent(row.code)}/start`, "POST");
    success(`已启动 ${row.name}`);
    await load();
  });
}

async function confirmAction(row: any, action: "stop" | "restart" | "delete") {
  const label = action === "stop" ? "停止" : action === "restart" ? "重启" : "删除";
  try {
    await ElMessageBox.confirm(
      action === "delete"
        ? `删除服务 ${row.name}（${row.code}）的配置和运行态记录？运行中的服务必须先停止。`
        : `${label}服务 ${row.name}（${row.code}）？平台只会终止自己记录并核对过启动时间的进程。`,
      `${label}服务`,
      { type: action === "delete" ? "warning" : "info", confirmButtonText: label, cancelButtonText: "取消" },
    );
  } catch {
    return;
  }
  await operation(async () => {
    const method = action === "delete" ? "DELETE" : "POST";
    await api(serviceActionPath(row.code, action), method);
    success(`${label}完成`);
    await load();
  });
}

async function checkHealth(row: any) {
  await operation(async () => {
    health.result = await api(`/admin/services/${encodeURIComponent(row.code)}/health`);
    health.code = row.code;
    health.visible = true;
  });
}

async function showLogs(row: any) {
  await operation(async () => {
    const body = await api<any>(`/admin/services/${encodeURIComponent(row.code)}/logs?lines=200`);
    logs.code = row.code;
    logs.text = body.text || "";
    logs.available = body.available !== false;
    logs.visible = true;
  });
}

onMounted(load);
</script>

<template>
  <PageHeader
    title="服务管理"
    subtitle="登记本机外部服务的启动命令、端口和健康端点；生命周期动作由后端审计。"
    :busy="busy"
    @refresh="load"
  >
    <template #actions>
      <el-button v-if="canManage()" type="primary" @click="create">登记服务</el-button>
    </template>
  </PageHeader>
  <PageError :error="error" :trace="trace" />

  <div class="hint">
    命令是“一行一个参数”的 argv，不经过 shell；启动程序必须是绝对路径。停止、重启和删除需要二次确认。
  </div>

  <div class="panel">
    <el-skeleton v-if="busy && rows.length === 0" :rows="5" animated />
    <el-table v-else :data="rows" stripe>
      <el-table-column label="服务" min-width="190">
        <template #default="{ row }">
          <div class="name">{{ row.name }}</div>
          <code>{{ row.code }}</code>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <span :class="['state', serviceStateClass(row.state)]">
            {{ serviceStateLabel(row.state) }}
          </span>
          <div v-if="row.stale" class="muted small">记录属于其它实例</div>
          <div v-else-if="row.lastError" class="error small">{{ row.lastError }}</div>
        </template>
      </el-table-column>
      <el-table-column label="端点" min-width="230">
        <template #default="{ row }">
          <code>{{ row.healthUrl }}</code>
          <div class="muted small">PID {{ row.pid > 0 ? row.pid : "—" }}</div>
        </template>
      </el-table-column>
      <el-table-column label="超时" width="130">
        <template #default="{ row }">
          <div class="small">启动 {{ Math.round(row.startupTimeoutMs / 1000) }}s</div>
          <div class="small">停止 {{ Math.round(row.stopTimeoutMs / 1000) }}s</div>
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="300">
        <template #default="{ row }">
          <template v-if="canManage()">
            <el-button
              v-if="['READY', 'STARTING', 'UNHEALTHY'].includes(row.state)"
              text
              type="warning"
              @click="confirmAction(row, 'stop')"
              >停止</el-button
            >
            <el-button v-else text type="primary" @click="start(row)">启动</el-button>
            <el-button text @click="confirmAction(row, 'restart')">重启</el-button>
            <el-button text @click="checkHealth(row)">健康</el-button>
            <el-button text @click="showLogs(row)">日志</el-button>
            <el-button text @click="edit(row)">编辑</el-button>
            <el-button
              v-if="!['READY', 'STARTING', 'UNHEALTHY'].includes(row.state)"
              text
              type="danger"
              @click="confirmAction(row, 'delete')"
              >删除</el-button
            >
          </template>
          <span v-else class="muted small">只读</span>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="error ? '服务暂不可用，请刷新重试' : '还没有登记外部服务；登记后可从平台启动、停止并查看日志。'"
        />
      </template>
    </el-table>
  </div>

  <el-dialog v-model="dialog" :title="editing ? '编辑服务' : '登记服务'" width="660px">
    <el-form label-width="110px">
      <el-form-item label="服务编码" required>
        <el-input v-model="form.code" :disabled="!!editing" placeholder="小写字母开头，例如 space_demo" />
      </el-form-item>
      <el-form-item label="显示名称" required>
        <el-input v-model="form.name" placeholder="例如 空间演示服务" />
      </el-form-item>
      <el-form-item label="启动命令" required>
        <el-input
          v-model="form.command"
          type="textarea"
          :rows="4"
          placeholder="第一行是可执行文件绝对路径，后面每行一个参数"
        />
        <div class="muted small">不会经过 shell；参数中的换行会被拒绝。</div>
      </el-form-item>
      <el-form-item label="工作目录" required>
        <el-input v-model="form.workDir" placeholder="必须已存在的绝对路径" />
      </el-form-item>
      <el-form-item label="端口" required>
        <el-input-number v-model="form.port" :min="1" :max="65535" />
      </el-form-item>
      <el-form-item label="健康路径" required>
        <el-input v-model="form.healthPath" placeholder="/health" />
      </el-form-item>
      <el-form-item label="启动超时">
        <el-input-number v-model="form.startupTimeoutMs" :min="1000" :max="600000" :step="1000" />
        <span class="muted small">毫秒</span>
      </el-form-item>
      <el-form-item label="停止超时">
        <el-input-number v-model="form.stopTimeoutMs" :min="100" :max="60000" :step="500" />
        <span class="muted small">毫秒</span>
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="health.visible" :title="`健康检查 · ${health.code}`" width="560px">
    <div v-if="health.result">
      <div :class="['state', health.result.healthy ? 'ready' : 'failed']">
        {{ health.result.healthy ? "健康端点可达" : "健康端点不可达" }}
      </div>
      <p>{{ health.result.message || "无响应说明" }}</p>
      <p class="muted">
        {{ health.result.url }} · HTTP {{ health.result.httpStatus ?? "—" }} ·
        {{ health.result.latencyMs }} ms · 状态 {{ serviceStateLabel(health.result.state) }}
      </p>
      <p v-if="health.result.bodyPreview" class="preview">{{ health.result.bodyPreview }}</p>
    </div>
  </el-dialog>

  <el-dialog v-model="logs.visible" :title="`日志 · ${logs.code}`" width="760px">
    <div v-if="!logs.available" class="muted">还没有日志文件；服务启动后会写入。</div>
    <pre v-else class="logs">{{ logs.text || "日志为空" }}</pre>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 1rem;
  padding: 0.7rem 0.9rem;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
  color: var(--el-text-color-regular);
  font-size: 13px;
}
.name {
  font-weight: 600;
}
.state {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.state.ready {
  color: var(--el-color-success-dark-2);
  background: var(--el-color-success-light-9);
}
.state.starting {
  color: var(--el-color-primary-dark-2);
  background: var(--el-color-primary-light-9);
}
.state.failed {
  color: var(--el-color-danger-dark-2);
  background: var(--el-color-danger-light-9);
}
.state.stopped {
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
}
.small {
  font-size: 12px;
}
.error {
  color: var(--el-color-danger);
  max-width: 220px;
}
.preview,
.logs {
  max-height: 420px;
  overflow: auto;
  padding: 0.75rem;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
}
</style>
