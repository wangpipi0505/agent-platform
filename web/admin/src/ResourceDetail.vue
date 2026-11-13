<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from "vue";
import SkillBusinessFields from "./SkillBusinessFields.vue";
import SchemaInputs from "../../shared/SchemaInputs.vue";
import McpToolsPanel from "./McpToolsPanel.vue";
import { completedControlledCall } from "../../shared/controlled-test";
import { probeView, serviceAccess } from "../../shared/service-access";
import { uploadArtifact as uploadArtifactFile } from "../../shared/artifact-upload";
import { useAuth, useApi } from "../../shared/api";
import { useUnsavedChanges } from "../../shared/useUnsavedChanges";
import { confirmDanger } from "../../shared/components/DangerConfirm";
import type { ResourceVersionInfo } from "../../shared/resource-types";
const props = defineProps<{ resource: any; kind: "skill" | "template" }>();
const api = useApi();
const auth = useAuth();
const value = ref(props.resource);
// MNT-03：资源版本行稳定外壳（definition/content 动态保持 unknown）。
const versions = ref<ResourceVersionInfo[]>([]);
const error = ref("");
const result = ref<any>();
const vars = ref("{}");
const content = ref(props.resource.content || "");
// MNT-02子步2：以最近一次服务端返回（选中版本 content）为基线计算 dirty；
// 保存成功后 load() 刷新基线自动复位；本地重渲染不影响。
const contentBaseline = ref(content.value);
const contentDirty = computed(() => content.value !== contentBaseline.value);
useUnsavedChanges({
  isDirty: () => contentDirty.value,
  securityKey: () => auth.token ?? undefined,
  onReset: () => {
    content.value = contentBaseline.value;
  },
});
const selected = ref<number>();
// UI-03：详情按任务分段组织（契约→连接调试→重检→选型→发布）；分段仅是呈现导航，
// 不改变任何请求时机。模板与技能的分段集合不同，初始段随 kind 确定。
const activeSection = ref(props.kind === "template" ? "content" : "contract");
const busy = ref(false);
const models = ref<Record<string, unknown>[]>([]);
const modelId = ref<number>();
const connectionInput = ref("{}");
const connectionForm = ref<InstanceType<typeof SchemaInputs>>();
const advancedInput = ref(false);
const service = computed(() => serviceAccess(selectedDefinition.value));
const probeResult = ref<Record<string, unknown> | null>(null);
const probing = ref(false);
const probeError = ref("");
const probeMeta = computed(() => probeView(probeResult.value));
watch(selected, () => {
  probeResult.value = null;
  probeError.value = "";
});
async function probeService() {
  if (!selected.value) return;
  probing.value = true;
  probeError.value = "";
  try {
    probeResult.value = await api(
      base() + "/versions/" + selected.value + "/service-probe",
      "POST",
    );
  } catch (e: any) {
    probeError.value = e.message;
  } finally {
    probing.value = false;
  }
}
// The resource's published pointer may differ from the selected draft. Version
// metadata owns status; a nested full definition owns the actual contract.
const selectedDefinition = computed(() => {
  const row = versions.value.find((v) => (v.versionNo || v.version) === selected.value);
  return row ? { ...row, ...(row.definition || {}), status: row.status } : undefined;
});
const connectionSchema = computed(() => selectedDefinition.value?.inputSchema);
const controlledCallId = ref("");
// SCN-009：ElInput产生字符串而后端只收正整数JSON number；提交边界统一做一次严格转换。
function controlledCallIdNumber(): number {
  const raw = String(controlledCallId.value ?? "").trim();
  if (!/^\d+$/.test(raw)) throw new Error("重检ID必须是正整数（不含符号或小数点）");
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 1)
    throw new Error("重检ID必须是不超过安全整数范围的正整数");
  return value;
}
const testRun = ref<any>();
let pollTimer: ReturnType<typeof setTimeout>;
let disposed = false;
let loadGeneration = 0, loadController = new AbortController();
async function checkTest() {
  if (!testRun.value?.runId) return;
  try {
    testRun.value = await api(
      "/admin/debug-runs/" + testRun.value.runId + "/status",
    );
    // The call ID is reserved before approval. Only a successful real run is an acceptance fact.
    if (completedControlledCall(testRun.value)) {
      controlledCallId.value = testRun.value.controlledCallId;
      result.value = await api(
        base() + "/drafts/" + selected.value + "/connection-test",
        "POST",
        { controlledCallId: controlledCallIdNumber() },
      );
      return;
    }
    if (
      !["FAILED", "CANCELLED", "SUCCEEDED"].includes(testRun.value.status) &&
      !disposed
    )
      pollTimer = setTimeout(checkTest, 1500);
  } catch (e: any) {
    error.value = e.message;
  }
}
onBeforeUnmount(() => {
  disposed = true;
  clearTimeout(pollTimer);
  loadGeneration++;
  loadController.abort();
});
const acceptanceCases = ref("[]");
const base = () =>
  "/admin/" +
  (props.kind === "skill" ? "skills" : "prompt-templates") +
  "/" +
  encodeURIComponent(props.resource.code);
async function load() {
  const generation = ++loadGeneration;
  loadController.abort();
  loadController = new AbortController();
  const signal = loadController.signal;
  versions.value = [];
  selected.value = undefined;
  value.value = props.resource;
  error.value = "";
  try {
    if (props.kind === "skill") {
      const resource = await api(base(), "GET", undefined, signal);
      if (disposed || generation !== loadGeneration) return;
      value.value = resource;
      versions.value = resource.versions || [];
    } else {
      const rows = await api(base() + "/versions", "GET", undefined, signal);
      if (disposed || generation !== loadGeneration) return;
      versions.value = rows;
    }
    // 服务端返回即基线：保存/载入后 dirty 复位，不误报。
    contentBaseline.value = content.value;
    selected.value = versions.value[0]?.versionNo || value.value.currentVersion;
    if (props.kind === "template")
      content.value =
        versions.value.find(
          (v) => (v.versionNo || v.version) === selected.value,
        )?.content || "";
    if (props.kind === "skill") {
      const entries = await api("/admin/model-entries", "GET", undefined, signal);
      if (!disposed && generation === loadGeneration) models.value = entries;
    }
  } catch (e: any) {
    if (!disposed && generation === loadGeneration && e.name !== "AbortError") error.value = e.message;
  }
}
async function saveBusiness() {
  busy.value=true;error.value='';try{await api(base(),'PUT',{...value.value});await load();}catch(problem){error.value=problem instanceof Error?problem.message:'保存失败';}finally{busy.value=false;}
}
async function uploadArtifact(file: File): Promise<string> {
  // R08：统一走共享上传函数（顶层回包/明确OK信封），不再各自猜包装形状。
  return uploadArtifactFile(auth.token, file);
}
async function execute(action: string) {
  busy.value = true;
  error.value = "";
  try {
    if (!selected.value && action !== "save") throw new Error("请选择真实版本");
    if (action === "publish") {
      // F1-3：原生 confirm 清零，发布改走 DangerConfirm（发布后内容不可修改）。
      if (
        !(await confirmDanger({
          title: "发布版本",
          message: "确认发布此版本？发布后内容不可修改。",
          confirmText: "发布",
        }))
      )
        return;
      result.value = await api(
        base() + "/versions/" + selected.value + "/publish",
        "POST",
      );
      await load();
    }
    if (action === "render")
      result.value = await api(
        base() + "/versions/" + selected.value + "/render",
        "POST",
        { variables: JSON.parse(vars.value) },
      );
    if (action === "connection" || action === "recheck") {
      clearTimeout(pollTimer);
      result.value = await api(
        base() + "/drafts/" + selected.value + "/connection-test",
        "POST",
        action === "recheck"
          ? { controlledCallId: controlledCallIdNumber() }
          : {
              inputs:
                connectionSchema.value && !advancedInput.value
                  ? connectionForm.value!.collect()
                  : JSON.parse(connectionInput.value),
              requestId: crypto.randomUUID(),
            },
      );
      if (result.value.runId) {
        testRun.value = { ...result.value };
        void checkTest();
      }
    }
    if (action === "acceptance") {
      const cases = JSON.parse(acceptanceCases.value);
      if (!modelId.value || !Array.isArray(cases) || !cases.length)
        throw new Error("请选择模型并填写非空验收用例数组");
      result.value = await api(
        base() + "/drafts/" + selected.value + "/acceptance",
        "POST",
        { modelEntryId: modelId.value, cases },
      );
    }
    if (action === "save") {
      result.value = await api(base(), "PUT", {
        ...versions.value.find(
          (v) => (v.versionNo || v.version) === selected.value,
        ),
        ...props.resource,
        content: content.value,
      });
      await load();
    }
  } catch (e: any) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
watch(selected, (version) => {
  if (props.kind === "template")
    content.value =
      versions.value.find((v) => (v.versionNo || v.version) === version)
        ?.content || "";
  contentBaseline.value = content.value;
});
watch(() => [props.resource.code, props.kind, auth.token, auth.user?.tenantId, auth.user?.principalId], () => {
  if (auth.token) void load();
  else { loadGeneration++; loadController.abort(); versions.value = []; selected.value = undefined; }
}, { immediate: true, flush: "sync" });
</script>
<template>
  <div class="panel resource-detail">
    <aside class="resource-aside" aria-label="资源概要与版本列表">
      <div class="aside-head">
        <h2>{{ resource.name || resource.code }}</h2>
        <span class="muted">{{ resource.code }}</span>
        <p v-if="resource.description" class="muted aside-desc">{{ resource.description }}</p>
      </div>
      <div class="version-list" role="listbox" aria-label="版本列表">
        <button
          v-for="v in versions"
          :key="v.versionNo || v.version"
          type="button"
          role="option"
          :aria-selected="(v.versionNo || v.version) === selected"
          :class="['version-item', { active: (v.versionNo || v.version) === selected }]"
          @click="selected = v.versionNo || v.version"
        >
          <span>版本 {{ v.versionNo || v.version }}</span>
          <el-tag
            size="small"
            effect="plain"
            :type="v.status === 'PUBLISHED' ? 'success' : v.status === 'DRAFT' ? 'info' : 'warning'"
            >{{ v.status }}</el-tag
          >
        </button>
        <p v-if="!versions.length" class="muted">尚无版本记录。</p>
      </div>
      <p class="muted aside-note">
        发布前需完成连接测试与选型验收；门禁由服务端校验。发布版本只读，编辑将创建新草稿。
      </p>
    </aside>
    <section class="resource-main">
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <template v-if="kind === 'template'">
        <el-tabs v-model="activeSection">
          <el-tab-pane label="模板内容" name="content">
            <el-form label-position="top"
              ><el-form-item label="模板正文（变量使用双花括号）"
                ><el-input
                  v-model="content"
                  type="textarea"
                  :rows="10" /></el-form-item></el-form
            ><el-button
              type="primary"
              :loading="busy"
              v-permission="'edit'"
              @click="execute('save')"
              >保存为草稿版本</el-button
            >
          </el-tab-pane>
          <el-tab-pane label="试渲染" name="render">
            <el-input
              v-model="vars"
              type="textarea"
              :rows="4"
              aria-label="变量 JSON"
              placeholder="变量 JSON"
            /><el-button
              style="margin-top: 0.75rem"
              :loading="busy"
              v-permission="'edit'"
              @click="execute('render')"
              >试渲染所选版本</el-button
            >
          </el-tab-pane>
          <el-tab-pane label="版本与发布" name="release">
            <div class="publish-row">
              <el-button
                type="primary"
                :loading="busy"
                :disabled="!selected"
                v-permission="'edit'"
                @click="execute('publish')"
                >发布所选版本</el-button
              >
              <span v-if="!selected" class="muted">请先在左侧选择版本。</span>
            </div>
            <h2>版本与验收记录</h2>
            <pre>{{ JSON.stringify(versions, null, 2) }}</pre>
          </el-tab-pane>
        </el-tabs>
      </template>
      <template v-else>
        <el-tabs v-model="activeSection">
          <el-tab-pane label="契约" name="contract"><details><summary>业务分类与访问权限</summary><SkillBusinessFields :value="value" /><el-button v-permission="'edit'" @click="saveBusiness">保存业务属性</el-button></details>
            <section v-if="service" class="service-access" aria-labelledby="service-access-title">
              <div class="service-head">
                <div>
                  <h2 id="service-access-title">服务接入</h2>
                  <p class="muted">
                    真实接入地址与只读探测；卡片不执行技能，也不代替连接测试和验收。
                  </p>
                </div>
                <el-tag :type="probeMeta.type" effect="light">{{ probeMeta.label }}</el-tag>
              </div>
              <code class="service-url">{{ service.endpoint }}</code>
              <dl class="service-meta">
                <div>
                  <dt>调用方式</dt>
                  <dd>{{ service.type }} · {{ service.method }}</dd>
                </div>
                <div>
                  <dt>鉴权 / 超时</dt>
                  <dd>{{ service.authConfigured ? "已配置凭据" : "未配置凭据" }} · {{ service.timeoutMs ? service.timeoutMs + " ms" : "默认超时" }}</dd>
                </div>
                <div>
                  <dt>最近检查</dt>
                  <dd>
                    {{ probeResult?.checkedAt || "尚未探测" }}
                    <span v-if="probeResult" class="muted"> · {{ probeMeta.detail }}</span>
                  </dd>
                </div>
              </dl>
              <div class="service-links">
                <a :href="service.endpoint" target="_blank" rel="noopener">打开接口地址</a>
                <template v-if="service.origin">
                  <a :href="service.origin" target="_blank" rel="noopener">服务根地址</a>
                  <a :href="service.origin + '/v3/api-docs'" target="_blank" rel="noopener">OpenAPI 文档</a>
                </template>
                <el-button size="small" :loading="probing" v-permission="'edit'" @click="probeService">
                  探测端点
                </el-button>
                <el-button size="small" disabled title="生命周期脚本属于 AP-S05，本页不直接杀进程">
                  重启服务（待配置）
                </el-button>
              </div>
              <p v-if="probeError" class="error" role="alert">{{ probeError }}</p>
              <p class="muted">
                探测只发 HEAD，不跟随重定向、不读取响应体；最近一次检查时间来自本次页面探测，不伪装成历史监控。
              </p>
            </section>
            <McpToolsPanel v-if="selectedDefinition?.type === 'MCP'" :code="resource.code" :version="selected" :definition="selectedDefinition" />
            <h2>执行属性与权限守卫</h2>
            <pre>{{
              JSON.stringify(
                {
                  execution: selectedDefinition?.execution,
                  guard: selectedDefinition?.guard,
                  inputSchema: selectedDefinition?.inputSchema,
                  outputSchema: selectedDefinition?.outputSchema,
                },
                null,
                2,
              )
            }}</pre>
          </el-tab-pane>
          <el-tab-pane label="连接调试" name="connection">
            <SchemaInputs
              v-if="connectionSchema && !advancedInput"
              ref="connectionForm"
              :declaration="connectionSchema"
              :upload-artifact="uploadArtifact"
            />
            <el-checkbox v-model="advancedInput">高级 JSON 输入</el-checkbox>
            <el-form
              v-if="!connectionSchema || advancedInput"
              label-position="top"
              ><el-form-item label="测试输入参数 JSON"
                ><el-input
                  v-model="connectionInput"
                  type="textarea"
                  :rows="4" /></el-form-item></el-form
            ><el-button
              :loading="busy"
              v-permission="'edit'"
              @click="execute('connection')"
              >发起受控测试</el-button
            >
            <div v-if="testRun" class="panel test-run">
              <strong>{{ testRun.runId }} · {{ testRun.status }}</strong>
              <p v-if="testRun.status === 'WAITING_APPROVAL'">
                请到确认中心核对参数快照并批准；完成后本页自动读取事实并重检。
              </p>
              <a href="#/approvals" target="_blank" rel="noopener"
                >打开确认中心</a
              >
              ·
              <a :href="'#/runs/' + testRun.runId" target="_blank" rel="noopener"
                >查看调试详情</a
              ><el-button @click="checkTest">刷新测试结果</el-button>
            </div>
          </el-tab-pane>
          <el-tab-pane label="重检" name="recheck">
            <p class="muted">
              已有受控调用标识来自确认通过后的真实运行；重检只读取该调用事实，不重复发起业务调用。
            </p>
            <div class="recheck-row">
              <el-input
                v-model="controlledCallId"
                aria-label="已有受控调用标识"
                placeholder="已有真实调用标识"
              /><el-button :loading="busy" v-permission="'edit'" @click="execute('recheck')">重检已有调用</el-button>
            </div>
          </el-tab-pane>
          <el-tab-pane label="选型验收" name="acceptance">
            <h2>模型选型验收</h2>
            <el-form label-position="top"
              ><el-form-item label="选型模型"
                ><el-select
                  v-model="modelId"
                  filterable
                  placeholder="选择真实模型条目"
                  ><el-option
                    v-for="m in models"
                    :key="m.id"
                    :value="m.id"
                    :label="
                      m.displayName || m.modelName
                    " /></el-select></el-form-item
              ><el-form-item
                label="验收用例数组（question / expectSelected / expectArgs）"
                ><el-input
                  v-model="acceptanceCases"
                  type="textarea"
                  :rows="6"
                  placeholder='无工具预期用空字符串：{"question":"你好","expectSelected":""}；仅空白或缺失会被拒绝'
              /></el-form-item></el-form
            ><el-button
              :loading="busy"
              v-permission="'edit'"
              @click="execute('acceptance')"
              >运行选型验收</el-button
            >
            <p class="muted">
              报告绑定当前版本摘要；模型不可用会显示真实失败，不能直接发布绕过门禁。
            </p>
          </el-tab-pane>
          <el-tab-pane label="版本与发布" name="release">
            <div class="publish-row">
              <el-button
                type="primary"
                :loading="busy"
                :disabled="!selected"
                v-permission="'edit'"
                @click="execute('publish')"
                >发布所选版本</el-button
              >
              <span v-if="!selected" class="muted">请先在左侧选择版本。</span>
              <span v-else class="muted"
                >发布由服务端校验连接测试、选型验收与确认角色门禁。</span
              >
            </div>
            <h2>版本与验收记录</h2>
            <pre>{{ JSON.stringify(versions, null, 2) }}</pre>
            <p class="muted">
              无验收报告或引用统计时不构造指标；发布门禁由技能服务执行。
            </p>
          </el-tab-pane>
        </el-tabs>
      </template>
      <pre v-if="result">{{ JSON.stringify(result, null, 2) }}</pre>
    </section>
  </div>
</template>
<style scoped>
/* UI-03 主从布局：左侧版本列表约 31%，右侧任务分段详情约 69%；详情优先。 */
.resource-detail { display: grid; grid-template-columns: minmax(13rem, 31fr) minmax(0, 69fr); gap: 1.25rem; align-items: start; }
.resource-aside { min-width: 0; }
.aside-head h2 { margin: 0 0 .25rem; }
.aside-desc { margin: .35rem 0 0; overflow-wrap: anywhere; }
.version-list { display: flex; flex-direction: column; gap: .4rem; margin: .9rem 0; }
.version-item { display: flex; align-items: center; justify-content: space-between; gap: .5rem; padding: .5rem .7rem; border: 1px solid var(--line, #e3e8ef); border-radius: .5rem; background: transparent; cursor: pointer; text-align: left; font-size: .9rem; }
.version-item:hover { border-color: var(--brand-primary, #0ea5e9); }
.version-item.active { border-color: var(--brand-primary, #0ea5e9); background: color-mix(in srgb, var(--brand-primary, #0ea5e9) 8%, transparent); }
.version-item:focus-visible { outline: 2px solid var(--brand-primary, #0ea5e9); outline-offset: 1px; }
.aside-note { font-size: .82rem; margin: 0; }
.resource-main { min-width: 0; }
.test-run, .service-access { margin: 1rem 0 1.25rem; padding: 1rem 1.1rem; border: 1px solid var(--line, #e3e8ef); border-radius: .75rem; background: var(--surface, #fff); }
.service-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; }
.service-head h2 { margin: 0 0 .25rem; }
.service-head .muted { margin: 0; }
.service-url { display: block; margin: .75rem 0; padding: .55rem .7rem; border-radius: .45rem; background: var(--surface-2, #f6f8fb); color: var(--ink, #1f2a44); font-size: .82rem; overflow-wrap: anywhere; }
.service-meta { display: grid; grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr)); gap: .65rem .9rem; margin: .75rem 0; }
.service-meta div { min-width: 0; }
.service-meta dt { color: var(--muted, #64748b); font-size: .74rem; margin-bottom: .15rem; }
.service-meta dd { margin: 0; font-size: .84rem; overflow-wrap: anywhere; }
.service-links { display: flex; align-items: center; flex-wrap: wrap; gap: .5rem .9rem; margin-top: .6rem; }
.service-links :deep(.el-button) { margin-left: 0; }
.publish-row, .recheck-row { display: flex; align-items: center; flex-wrap: wrap; gap: .75rem; margin: .75rem 0 1rem; }
.publish-row :deep(.el-button), .recheck-row :deep(.el-button) { margin-left: 0; }
.recheck-row { max-width: 34rem; }
.recheck-row :deep(.el-input) { flex: 1; }
@media (max-width: 900px) {
  .resource-detail { grid-template-columns: minmax(0, 1fr); }
  .service-head { flex-direction: column; }
  .service-links { align-items: flex-start; }
}
</style>
