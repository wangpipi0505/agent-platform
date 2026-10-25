<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useApi, useAuth } from "../../shared/api";
import { buildOpenApiImport, documentProblem, newOperationForm, type OpenApiOperation, type OperationForm } from "../../shared/openapi-import";

const emit = defineEmits<{ "open-imported": [code: string] }>();
const auth = useAuth();
const api = useApi();
const isAdmin = computed(() => auth.roles.includes("ADMIN"));
const step = ref(0), document = ref(""), baseUrl = ref(""), busy = ref(false), error = ref("");
const preview = ref<{operations: OpenApiOperation[]; warnings: unknown[]; documentSha256: string}>();
const selected = ref<string[]>([]), forms = ref<OperationForm[]>([]), expanded = ref<string[]>([]);
const result = ref<{drafts: any[]; warnings: unknown[]}>();
const operations = computed(() => preview.value?.operations || []);
const flags = [{key: "readonly", label: "只读操作"}, {key: "sideEffect", label: "存在副作用"}, {key: "idempotent", label: "支持幂等"}, {key: "cancellable", label: "支持取消"}];
watch(document, () => {preview.value = undefined; selected.value = []; forms.value = []; error.value = "";});
const diagnostic = (value: unknown) => typeof value === "string" ? value : JSON.stringify(value);
const operation = (id: string) => operations.value.find(op => op.operationId === id)!;
function failure(e: any) {
  const message = !e.message || e.message === "Network Error" ? "网络请求失败，请检查网关连接后重试" : e.message;
  error.value = `${message}${e.traceId ? `（追踪号：${e.traceId}）` : ""}`;
}
async function readFile(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0];
  if (!file) return;
  if (file.size > 1048576) {error.value = "文件超过 1 MiB，请缩小后重试"; input.value = ""; return;}
  try { document.value = await file.text(); } catch { error.value = "无法读取文件，请重新选择或粘贴文本"; }
  input.value = "";
}
async function inspect() {
  error.value = documentProblem(document.value); if (error.value) return;
  const source = document.value;
  busy.value = true;
  try {
    const response = await api("/admin/skills/import-openapi/preview", "POST", {document: source});
    // A changed document must never retain an old operation selection.
    if (document.value === source) preview.value = response;
  } catch (e) { failure(e); } finally { busy.value = false; }
}
function next() {
  if (!selected.value.length) {error.value = "请至少选择一个支持的操作"; return;}
  forms.value = operations.value.filter(op => op.supported && selected.value.includes(op.operationId)).map(newOperationForm);
  expanded.value = forms.value.map(form => form.operationId);
  error.value = ""; step.value = 1;
}
async function createDrafts() {
  error.value = "";
  try {
    const body = buildOpenApiImport(document.value, baseUrl.value, operations.value, forms.value, isAdmin.value);
    busy.value = true;
    result.value = await api("/admin/skills/import-openapi", "POST", body);
    // Credentials are never retained on the success screen.
    forms.value.forEach(form => form.authRef = "");
  } catch (e) {failure(e);} finally {busy.value = false;}
}
</script>

<template>
  <section class="openapi-import" aria-label="OpenAPI 导入">
    <template v-if="result">
      <el-alert title="导入成功，已创建草稿（DRAFT）" type="success" :closable="false" show-icon />
      <p>草稿尚未发布。进入详情，继续完成真实连接测试与模型选型验收。</p>
      <p v-for="(warning, index) in result.warnings" :key="index" class="muted">{{ diagnostic(warning) }}</p>
      <div v-for="draft in result.drafts" :key="draft.code" class="import-result">
        <div><strong>{{ draft.name }}</strong><p class="muted">{{ draft.code }} · {{ draft.status }}</p></div>
        <el-button type="primary" @click="emit('open-imported', draft.code)">进入详情继续验收</el-button>
      </div>
    </template>
    <template v-else>
      <el-steps :active="step" finish-status="success" simple>
        <el-step title="上传与选择操作" /><el-step title="补充合同并创建草稿" />
      </el-steps>
      <div v-if="error" class="error" role="alert" aria-live="polite">{{ error }}</div>
      <template v-if="step === 0">
        <p class="muted">支持 OpenAPI 3.0 的 JSON / YAML 安全子集，最大 1 MiB。服务地址与凭据由你明确配置。</p>
        <el-form label-position="top">
          <el-form-item label="选择本地 JSON / YAML 文件">
            <input aria-label="选择本地 JSON / YAML 文件" type="file" accept=".json,.yaml,.yml,application/json,application/yaml,text/yaml" :disabled="busy" @change="readFile" />
          </el-form-item>
          <el-form-item label="OpenAPI 文档文本" required>
            <el-input v-model="document" aria-label="OpenAPI 文档文本" type="textarea" :rows="8" :disabled="busy" placeholder="粘贴 OpenAPI 3.0 JSON 或 YAML 文档" />
          </el-form-item>
        </el-form>
        <el-button type="primary" :loading="busy" @click="inspect">{{ preview ? '重新预览' : '预览操作' }}</el-button>
        <template v-if="preview">
          <p v-for="(warning, index) in preview.warnings" :key="index" class="muted">{{ diagnostic(warning) }}</p>
          <h3>选择要导入的操作 <small class="muted">已选 {{ selected.length }} / {{ operations.length }}</small></h3>
          <p v-if="!operations.length" class="muted">未找到可导入操作，请检查文档后重新预览。</p>
          <el-checkbox-group v-model="selected" aria-label="可导入操作">
            <article v-for="op in operations" :key="op.operationId" class="operation-preview">
              <el-checkbox :value="op.operationId" :disabled="!op.supported">
                <strong>{{ op.method }} {{ op.path }}</strong> · {{ op.operationId }}
              </el-checkbox>
              <p class="muted">{{ op.supported ? '支持导入' : '不支持导入' }}{{ op.requiresAuth ? ' · 需要 Bearer 认证' : '' }}</p>
              <p v-for="(item, index) in op.diagnostics" :key="index" class="diagnostic">{{ diagnostic(item) }}</p>
              <details v-if="op.supported"><summary>查看输入 / 输出 Schema（只读）</summary><pre>{{ JSON.stringify({inputSchema: op.inputSchema, outputSchema: op.outputSchema}, null, 2) }}</pre></details>
            </article>
          </el-checkbox-group>
          <div class="import-actions"><el-button type="primary" :disabled="!selected.length || busy" @click="next">补充所选操作</el-button></div>
        </template>
      </template>
      <template v-else>
        <p class="muted">每个操作生成一个 HTTP 技能草稿。输入 / 输出与映射由文档确定，其余合同需逐项确认。</p>
        <el-form label-position="top" @submit.prevent="createDrafts">
          <el-form-item label="服务基础地址（baseUrl）" required><el-input v-model="baseUrl" aria-label="服务基础地址" placeholder="填写实际 HTTP(S) 基础地址，不含操作路径" :disabled="busy" /></el-form-item>
          <el-collapse v-model="expanded">
            <el-collapse-item v-for="form in forms" :key="form.operationId" :name="form.operationId" :title="`${operation(form.operationId).method} ${operation(form.operationId).path} · ${form.operationId}`">
              <fieldset :disabled="busy" class="contract-fields">
                <h3>基本信息与职责</h3>
                <div class="field-grid">
                  <el-form-item label="技能编码" required><el-input v-model="form.code" aria-label="技能编码" placeholder="小写字母开头，2–64 位" /></el-form-item>
                  <el-form-item label="技能名称" required><el-input v-model="form.name" aria-label="技能名称" /></el-form-item>
                </div>
                <el-form-item label="能力职责" required><el-input v-model="form.capability" aria-label="能力职责" type="textarea" :rows="2" /></el-form-item>
                <div class="field-grid">
                  <el-form-item label="适用范围" required><el-input v-model="form.applicable" aria-label="适用范围" /></el-form-item>
                  <el-form-item label="不适用范围" required><el-input v-model="form.excluded" aria-label="不适用范围" /></el-form-item>
                </div>
                <el-form-item label="参数示例" required><el-input v-model="form.example" aria-label="参数示例" placeholder="用简短文字说明参数及取值" /></el-form-item>
                <details><summary>查看输入 / 输出 Schema（只读）</summary><pre>{{ JSON.stringify({inputSchema: operation(form.operationId).inputSchema, outputSchema: operation(form.operationId).outputSchema}, null, 2) }}</pre></details>
                <h3>执行属性</h3>
                <div class="field-grid">
                  <el-form-item v-for="flag in flags" :key="flag.key" :label="flag.label" required>
                    <el-select v-model="form.execution[flag.key]" :aria-label="flag.label" placeholder="请选择并确认"><el-option label="是" :value="true" /><el-option label="否" :value="false" /></el-select>
                  </el-form-item>
                </div>
                <el-form-item v-if="form.execution.readonly === false" label="写操作确认角色（逗号分隔）" required><el-input v-model="form.confirmRoles" aria-label="写操作确认角色" /></el-form-item>
                <el-form-item label="超时（毫秒）"><el-input-number v-model="form.timeoutMs" :min="100" :max="600000" :step="100" aria-label="超时毫秒" /></el-form-item>
                <h3>权限守卫</h3>
                <el-form-item label="所需角色（逗号分隔）"><el-input v-model="form.roles" aria-label="所需角色" placeholder="填写平台已登记角色；多个角色需同时满足" /></el-form-item>
                <p class="muted">查询技能需声明输入参数对应的守卫属性。范围来自已验证身份，静态值仅进一步收窄；留空表示使用该身份的全部已授权值。</p>
                <div v-for="(attribute, index) in form.attributes" :key="index" class="guard-row">
                  <el-form-item label="守卫属性参数" required><el-select v-model="attribute.name" aria-label="守卫属性参数" placeholder="选择输入参数"><el-option v-for="key in Object.keys(operation(form.operationId).inputSchema?.properties || {})" :key="key" :label="key" :value="key" /></el-select></el-form-item>
                  <el-form-item label="静态收窄范围（逗号分隔，可留空）"><el-input v-model="attribute.values" aria-label="静态收窄范围" /></el-form-item>
                  <el-button @click="form.attributes.splice(index, 1)">移除属性</el-button>
                </div>
                <el-button @click="form.attributes.push({name: '', values: ''})">添加守卫属性</el-button>
                <h3>返回规范</h3>
                <el-form-item label="返回摘要模板" required><el-input v-model="form.summaryTemplate" aria-label="返回摘要模板" /></el-form-item>
                <el-form-item label="结构化结果路径" required><el-input v-model="form.structuredPath" aria-label="结构化结果路径" placeholder="$ 表示整个结果" /></el-form-item>
                <el-form-item label="文件引用路径（每行一个，可留空）"><el-input v-model="form.fileRefPaths" aria-label="文件引用路径" type="textarea" :rows="2" /></el-form-item>
                <h3>失败语义</h3>
                <div v-for="(item, index) in form.errors" :key="index" class="business-error">
                  <el-form-item label="业务错误码" required><el-input v-model="item.code" aria-label="业务错误码" /></el-form-item>
                  <el-form-item label="含义与处理说明" required><el-input v-model="item.message" aria-label="含义与处理说明" /></el-form-item>
                  <el-checkbox v-model="item.retryable">允许重试</el-checkbox><el-button @click="form.errors.splice(index, 1)">移除错误</el-button>
                </div>
                <el-button @click="form.errors.push({code: '', message: '', retryable: false})">添加业务错误</el-button>
                <div class="confirm-errors"><el-checkbox v-model="form.errorsConfirmed">已确认失败语义（无额外业务错误时可直接确认）</el-checkbox></div>
                <h3>认证配置</h3>
                <p v-if="operation(form.operationId).requiresAuth" class="diagnostic">此操作要求 Bearer 认证。管理员配置已登记引用后才能创建草稿。</p>
                <el-form-item v-if="isAdmin" label="已登记的认证引用（secret_ref）" :required="operation(form.operationId).requiresAuth"><el-input v-model="form.authRef" aria-label="已登记的认证引用" autocomplete="off" placeholder="仅填写管理员已配置的引用，不填写密钥明文" /></el-form-item>
                <p v-else class="muted">认证引用仅管理员可配置；需要认证时请管理员配置后再导入。</p>
              </fieldset>
            </el-collapse-item>
          </el-collapse>
          <p class="muted">创建后状态为 DRAFT。连接测试和选型验收通过后，才能另行发布。</p>
          <div class="import-actions"><el-button :disabled="busy" @click="step = 0">返回选择操作</el-button><el-button type="primary" :loading="busy" @click="createDrafts">创建 {{ forms.length }} 个草稿</el-button></div>
        </el-form>
      </template>
    </template>
  </section>
</template>

<style scoped>
.openapi-import { min-width: 0; }
.openapi-import .el-steps { margin-bottom: 1rem; }
.operation-preview, .import-result { border: 1px solid var(--line); border-radius: var(--radius); padding: 1rem; margin: .75rem 0; }
.operation-preview { font-size: var(--el-font-size-base); line-height: 1.5; }
.operation-preview p { margin: .35rem 0; }
.operation-preview :deep(.el-checkbox) { max-width: 100%; height: auto; min-height: 2rem; }
.operation-preview :deep(.el-checkbox__label) { white-space: normal; overflow-wrap: anywhere; }
.diagnostic { color: var(--st-suspend-text); overflow-wrap: anywhere; }
details { margin: .75rem 0; } summary { cursor: pointer; color: var(--brand-primary); }
.contract-fields { border: 0; padding: 0; margin: 0; min-width: 0; }
.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 1rem; }
.guard-row { display: grid; grid-template-columns: 1fr 1.5fr auto; align-items: center; gap: .5rem; }
.business-error { border-left: 2px solid var(--line); padding-left: .75rem; margin: 1rem 0; }
.confirm-errors { margin-top: .75rem; }
.import-actions { display: flex; justify-content: flex-end; gap: .5rem; margin-top: 1rem; }
.import-result { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
.import-result p { margin: .35rem 0 0; }
@media (max-width: 600px) { .field-grid, .guard-row { grid-template-columns: minmax(0, 1fr); } .import-result { align-items: flex-start; flex-direction: column; } }
</style>
