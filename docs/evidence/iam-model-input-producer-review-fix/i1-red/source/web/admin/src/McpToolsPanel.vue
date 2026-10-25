<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useApi, useAuth } from '../../shared/api';
const props = defineProps<{ code: string; version?: number; definition?: any }>();
const api = useApi(), auth = useAuth();
const discovery = ref<any>(), checking = ref(false), error = ref('');
const copied = ref('');
const blockReason = computed(() => {
  if (props.definition?.type !== 'MCP' || props.definition?.status !== 'DRAFT' || !props.version) return '仅可检查已保存的 MCP 草稿，请选择草稿版本。';
  if (!auth.can('edit')) return '检查远端工具需要 EDITOR 权限，请联系管理员。';
  if ((props.definition.authConfigured === true || String(props.definition.authRef || '').trim()) && !auth.can('admin')) return '此草稿使用凭据引用，检查远端工具另需 ADMIN 权限。';
  return '';
});
const boundExists = computed(() => discovery.value?.tools.some((tool: any) => tool.name === props.definition?.toolName) || false);
// Discovery is read-only, but both the saved draft and authenticated reader own
// its result. Abort plus generation handles transports that ignore cancellation.
const identity = computed(() => JSON.stringify([props.code, props.version, props.definition, auth.token, auth.user?.tenantId, auth.user?.principalId, auth.roles, auth.can('edit'), auth.can('admin')]));
let generation = 0, alive = true, controller = new AbortController();
function reset() { generation++; controller.abort(); controller = new AbortController(); discovery.value = undefined; checking.value = false; error.value = ''; copied.value = ''; }
watch(identity, reset, { flush: 'sync' });
async function check() {
  if (checking.value || blockReason.value) return;
  const ticket = generation, key = identity.value, signal = controller.signal;
  const current = () => alive && ticket === generation && key === identity.value && !signal.aborted;
  checking.value = true; error.value = ''; copied.value = ''; discovery.value = undefined;
  try {
    const result = await api('/admin/skills/' + encodeURIComponent(props.code) + '/drafts/' + props.version + '/mcp-tools', 'POST', undefined, signal);
    if (!current()) return;
    if (typeof result?.protocolVersion !== 'string' || !Array.isArray(result.tools) || result.tools.some((tool: any) => typeof tool?.name !== 'string' || !tool.name || tool.inputSchema == null || !['object', 'boolean'].includes(typeof tool.inputSchema))) throw Error('远端工具响应不完整，请检查服务后重试。');
    discovery.value = result;
  } catch (e: any) {
    if (current() && e.name !== 'AbortError') error.value = e.status === 403 ? '服务端拒绝检查。请确认 EDITOR 权限；使用凭据引用时还需 ADMIN 权限。' : e.message;
  } finally { if (current()) checking.value = false; }
}
async function copy(text: string, label: string) {
  const ticket = generation;
  try { await navigator.clipboard.writeText(text); if (alive && ticket === generation) copied.value = label + '已复制；未保存草稿。'; }
  catch { if (alive && ticket === generation) copied.value = '无法访问剪贴板，请在展开内容中选择并复制文本。'; }
}
onBeforeUnmount(() => { alive = false; reset(); });
</script>
<template>
  <section class="mcp-tools" aria-label="MCP 草稿工具检查">
    <h2>检查远端工具</h2>
    <p>所选版本 {{ version }} · 绑定工具 <code>{{ definition?.toolName || '未填写' }}</code></p>
    <p class="muted">读取已保存草稿的 SSE 服务并列取工具，不执行工具调用。发现结果仅供核对，不代表连接测试、验收或发布通过。</p>
    <el-button type="primary" :loading="checking" :disabled="checking || !!blockReason" @click="check">{{ error ? '重试检查远端工具' : '检查远端工具' }}</el-button>
    <p v-if="blockReason" class="muted" role="status">{{ blockReason }}</p>
    <p v-if="checking" class="muted" role="status">正在读取远端工具，请稍候…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="discovery">
      <p :class="boundExists ? 'muted' : 'error'" role="status">{{ boundExists ? '已找到准确绑定名称。请继续核对输入 Schema。' : '远端未找到准确绑定名称，请核对大小写与远端配置。' }}</p>
      <p class="muted">协议 {{ discovery.protocolVersion }} · 共 {{ discovery.tools.length }} 个工具</p>
      <article v-for="(tool, index) in discovery.tools" :key="index" class="mcp-tool">
        <div class="row"><strong><code>{{ tool.name }}</code></strong><el-tag v-if="tool.name === definition?.toolName" type="success">当前绑定</el-tag><el-button @click="copy(tool.name, '工具名')">复制工具名</el-button></div>
        <p v-if="tool.description">{{ tool.description }}</p>
        <details><summary>展开完整原始 inputSchema</summary><pre tabindex="0">{{ JSON.stringify(tool.inputSchema, null, 2) }}</pre><el-button @click="copy(JSON.stringify(tool.inputSchema, null, 2), 'Schema')">复制 Schema</el-button></details>
      </article>
      <details class="mcp-metadata"><summary>服务信息、能力与限制</summary><pre tabindex="0">{{ JSON.stringify({ serverInfo: discovery.serverInfo, capabilities: discovery.capabilities, limits: discovery.limits }, null, 2) }}</pre></details>
      <p class="muted">复制仅取得文本，不保存或应用到草稿。修改草稿需先具备服务端并发保护。</p>
    </template>
    <p v-if="copied" class="muted" role="status">{{ copied }}</p>
  </section>
</template>
<style scoped>
.mcp-tools { min-width: 0; padding-block: 1rem; border-block: 1px solid var(--line, #e5e7eb); overflow-wrap: anywhere; }
.mcp-tools h2 { margin-top: 0; }
.mcp-tool { min-width: 0; margin-block: 1rem; padding: 1rem; border: 1px solid var(--line, #e5e7eb); border-radius: var(--radius, .5rem); }
.mcp-tools .row { display: flex; flex-wrap: wrap; gap: .75rem; }
.mcp-tools pre { max-width: 100%; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
.mcp-tools summary { cursor: pointer; padding-block: .75rem; }
.mcp-tools :deep(.el-button) { min-height: 2.75rem; margin-left: 0; white-space: normal; }
</style>
