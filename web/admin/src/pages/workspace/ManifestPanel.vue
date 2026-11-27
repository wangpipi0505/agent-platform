<script setup lang="ts">
/**
 * F3-5 工作区 manifest 分区卡编辑（台账 §7.5，消费 A3-1 已交付端点 + F2-1 工作区壳层）。
 *
 * 七分区（字段以冻结契约 contracts/manifest.schema.json 为准，C0-2）：
 *   1 模型=model.defaultBinding(providerCode/modelName/promptTemplateRef)+inferencePolicy
 *     (temperature/maxOutput/timeoutMs/retry.on/budget)；
 *   2 技能链=skills.guideRefs（有序 code@version）+toolGrants 表格；
 *   3 插件=pluginCode/version/capabilities；4 规则=code@version chips；
 *   5 记忆=kinds/readScopes/writeScopes/topK；6 上下文=maxTurns/contextBudget/
 *     compactionThreshold/historyWindow；7 权限=roles chips（发布授予角色）。
 *
 * 全部为受控字段编辑（无裸 JSON textarea）；保存=PUT /admin/apps/{code}/manifest
 * {requestId, manifest, baseRevision}：baseRevision 过期 409 MANIFEST_REVISION_CONFLICT
 * → 同屏提示重载；校验 issues（path/code/message）逐条同屏（本地镜像服务端聚合规则预校验，
 * 服务端仍是唯一权威）。发布预检同屏：precheck.report.checks 中 manifest 引用核验条目
 * （规则 PASS/BLOCKED、技能/插件 N/A 留痕）+ 校验 issues 合并展示；校验状态经
 * validation-change 上抛，AppWorkspacePage 据此联动智能体头 [提交发布]。
 */
import { computed, ref, watch } from "vue";
import {
  fetchManifest,
  putManifest,
  validateManifest,
  type ManifestIssue,
  type ManifestObject,
} from "../../../../shared/api-manifest";
import type { PublishPrecheck } from "../../publish-gate";

const props = defineProps<{
  code: string;
  busy: boolean;
  /** 页面级发布预检结果（智能体头 [预检] 的返回），manifest 引用核验条目在此同屏。 */
  precheck?: PublishPrecheck | null;
}>();

const emit = defineEmits<{
  (e: "validation-change", state: {
    loaded: boolean;
    /** 服务端是否存在 manifest 头修订（联动口径：存在且校验不过才禁提交发布） */
    exists: boolean;
    ok: boolean;
    issues: ManifestIssue[];
    revision: number | null;
    dirty: boolean;
  }): void;
  (e: "saved", result: unknown): void;
  (e: "check"): void;
}>();

// ── 传输层（M5-2 切真实 API）：shared/api-manifest——真实端点
// GET/PUT /admin/apps/{code}/manifest（A3-1 WorkspaceManifestController，经网关）；
// VITE_MOCK 演示模式在 api-manifest 内部切 mock 演示端点，面板逻辑（CAS/409/issues 同屏）零改动。

// ── 草稿归一化：深克隆 + 只补 UI 需要绑定的分支骨架（原值 null 才补，非法形状留给校验）──

function ensurePath(root: any, path: string) {
  let cursor = root;
  for (const part of path.split(".")) {
    if (cursor == null || typeof cursor !== "object") return;
    if (cursor[part] == null) cursor[part] = {};
    cursor = cursor[part];
  }
}

function getPath(root: any, path: string): any {
  return path.split(".").reduce((acc: any, key: string) => (acc == null ? undefined : acc[key]), root);
}

/** UI 绑定所需的对象分支骨架；保存前对「原 manifest 没有且编辑后为空」的分支做剔除。 */
const BRANCH_PATHS = [
  "metadata",
  "model",
  "model.defaultBinding",
  "model.defaultBinding.promptTemplateRef",
  "model.inferencePolicy",
  "model.inferencePolicy.retry",
  "model.inferencePolicy.budget",
  "skills",
  "memoryPolicy",
  "contextPolicy",
  "permission",
];
const ARRAY_PATHS = [
  "skills.guideRefs",
  "skills.toolGrants",
  "plugins",
  "rules",
  "memoryPolicy.kinds",
  "memoryPolicy.readScopes",
  "memoryPolicy.writeScopes",
  "permission.roles",
];
/** 保存前剔除候选（父分支排在其子分支之后，先子后父）；metadata/model 等必键不剔除。 */
const PRUNE_CANDIDATES = [
  "model.defaultBinding.promptTemplateRef",
  "model.inferencePolicy.retry",
  "model.inferencePolicy.budget",
  "model.inferencePolicy",
  "plugins",
  "rules",
  "memoryPolicy.kinds",
  "memoryPolicy.readScopes",
  "memoryPolicy.writeScopes",
  "memoryPolicy",
];

function normalizeDraft(source: ManifestObject | undefined | null): ManifestObject {
  const draft = JSON.parse(JSON.stringify(source ?? {}));
  for (const path of BRANCH_PATHS) ensurePath(draft, path);
  for (const path of ARRAY_PATHS) {
    const keys = path.split(".");
    const parent = keys.slice(0, -1).reduce((acc: any, key: string) => acc?.[key], draft);
    const key = keys[keys.length - 1];
    if (parent && parent[key] == null) parent[key] = [];
  }
  if (draft.metadata && typeof draft.metadata === "object" && draft.metadata.name == null)
    draft.metadata.name = "";
  return draft;
}

/** 组装保存载荷：深克隆草稿，剔除「原 manifest 没有 + 编辑后为空」的可选分支（不污染 digest）。 */
function buildPayload(): ManifestObject {
  const payload = JSON.parse(JSON.stringify(draft.value)) as ManifestObject;
  for (const path of PRUNE_CANDIDATES) {
    const keys = path.split(".");
    const parent = keys.slice(0, -1).reduce((acc: any, key: string) => acc?.[key], payload);
    const key = keys[keys.length - 1];
    if (!parent || typeof parent !== "object") continue;
    const value = parent[key];
    const isEmpty =
      value == null ||
      (typeof value === "object" && Object.keys(value).length === 0) ||
      value === "";
    if (isEmpty && getPath(originalManifest.value, path) == null) delete parent[key];
  }
  return payload;
}

// ── 状态 ──

const loaded = ref(false);
const loading = ref(false);
const loadError = ref("");
/** 服务端是否存在 manifest 头修订（GET 404 MANIFEST_NOT_FOUND → false） */
const exists = ref(false);
const head = ref<{ revision: number; digest: string } | null>(null);
const originalManifest = ref<ManifestObject>({});
const draft = ref<ManifestObject>(normalizeDraft(undefined));
const draftBaseline = ref("");
const saving = ref(false);
const conflict = ref<{ message: string } | null>(null);
const serverIssues = ref<ManifestIssue[]>([]);
const saveError = ref("");
const savedNotice = ref("");

const manifestDirty = computed(
  () => loaded.value && draftBaseline.value !== "" && JSON.stringify(draft.value) !== draftBaseline.value,
);
const shortDigest = computed(() => {
  const digest = head.value?.digest || "";
  return digest.length > 16 ? `${digest.slice(0, 12)}…` : digest || "—";
});

// 校验 issues 同屏：本地预校验（镜像服务端聚合规则）优先；服务端 400 返回的 issues 覆盖展示。
const liveIssues = computed<ManifestIssue[]>(() => {
  try {
    return validateManifest(buildPayload());
  } catch {
    return [];
  }
});
const combinedIssues = computed<ManifestIssue[]>(() =>
  serverIssues.value.length > 0 ? serverIssues.value : liveIssues.value,
);
const validationOk = computed(() => combinedIssues.value.length === 0);

// 任何草稿修改都使上一次保存的服务端裁决失效（回到本地预校验口径）。
watch(
  draft,
  () => {
    serverIssues.value = [];
    saveError.value = "";
    savedNotice.value = "";
  },
  { deep: true },
);

// 校验状态上抛：AppWorkspacePage 据此联动智能体头 [提交发布]（存在 manifest 且校验不过才禁）。
const panelState = computed(() => ({
  loaded: loaded.value,
  exists: exists.value,
  ok: validationOk.value,
  issues: combinedIssues.value.map((item) => ({ ...item })),
  revision: head.value?.revision ?? null,
  dirty: manifestDirty.value,
}));
watch(
  panelState,
  (state) => {
    emit("validation-change", JSON.parse(JSON.stringify(state)));
  },
  { immediate: true, deep: true },
);

// ── 读取 / 保存 / 冲突重载 ──

function resetMessages() {
  conflict.value = null;
  serverIssues.value = [];
  saveError.value = "";
  savedNotice.value = "";
}

async function loadManifest() {
  loading.value = true;
  resetMessages();
  loadError.value = "";
  try {
    const view = await fetchManifest(props.code).catch((problem: unknown) => {
      // A server built before the optional read still returns MANIFEST_NOT_FOUND.
      if (problem && typeof problem === "object" && "code" in problem && problem.code === "MANIFEST_NOT_FOUND")
        return null;
      throw problem;
    });
    originalManifest.value = view?.manifest ?? {};
    draft.value = normalizeDraft(view?.manifest);
    draftBaseline.value = JSON.stringify(draft.value);
    head.value = { revision: view?.revision ?? 0, digest: view?.digest ?? "" };
    exists.value = Boolean(view);
  } catch (e: any) {
    loadError.value = e?.message || "manifest 读取失败，请稍后重试";
    head.value = null;
    exists.value = false;
  } finally {
    loading.value = false;
    loaded.value = true;
  }
}

async function save() {
  if (saving.value || !loaded.value) return;
  saving.value = true;
  resetMessages();
  try {
    const result = await putManifest(props.code, {
      requestId: crypto.randomUUID(),
      manifest: buildPayload(),
      // CAS 凭据：调用方所见头修订（尚无 manifest 时为 0）；过期 → 409 MANIFEST_REVISION_CONFLICT。
      baseRevision: head.value?.revision ?? 0,
    });
    head.value = { revision: result.revision, digest: result.digest || "" };
    exists.value = true;
    draftBaseline.value = JSON.stringify(draft.value);
    savedNotice.value = result.deduplicated
      ? `内容未变化：命中既有修订 ${result.revision}（digest 去重），未新增版本`
      : `manifest 已保存为修订 ${result.revision}`;
    emit("saved", result);
  } catch (e: any) {
    if (e?.status === 409 && e?.code === "MANIFEST_REVISION_CONFLICT") {
      conflict.value = { message: e?.message || "baseRevision 已过期" };
    } else if (e?.status === 400 && e?.code === "MANIFEST_INVALID") {
      serverIssues.value = Array.isArray(e?.issues) ? e.issues : [];
      saveError.value = e?.message || "manifest 校验未通过，请按 issues 修复后重试";
    } else {
      saveError.value = e?.message || "manifest 保存失败，请稍后重试";
    }
  } finally {
    saving.value = false;
  }
}

/** 409 冲突处置：重读服务端头修订并覆盖本地草稿（更新丢失由客户端重读解决）。 */
async function reloadHead() {
  await loadManifest();
}

/** 丢弃本地未保存修改（工作区未保存离开保护 onReset 回调；回到最近一次载入的基线）。 */
function resetDraft() {
  draft.value = normalizeDraft(originalManifest.value);
  draftBaseline.value = JSON.stringify(draft.value);
  resetMessages();
}
defineExpose({ resetDraft });

// ── 七分区编辑动作 ──

function addGuideRef() {
  draft.value.skills.guideRefs.push("");
}
function removeGuideRef(index: number) {
  draft.value.skills.guideRefs.splice(index, 1);
}
function moveGuideRef(index: number, delta: number) {
  const list = draft.value.skills.guideRefs;
  const target = index + delta;
  if (target < 0 || target >= list.length) return;
  list.splice(target, 0, list.splice(index, 1)[0]);
}
function addToolGrant() {
  draft.value.skills.toolGrants.push({ skillCode: "", version: 1 });
}
function removeToolGrant(index: number) {
  draft.value.skills.toolGrants.splice(index, 1);
}
function addPlugin() {
  draft.value.plugins.push({ pluginCode: "", version: "", capabilities: [] });
}
function removePlugin(index: number) {
  draft.value.plugins.splice(index, 1);
}

// ── 发布预检同屏（A3-1 checks 结构：规则 PASS/BLOCKED、技能/插件 N/A、无 manifest SKIPPED）──

const manifestChecks = computed(() =>
  (props.precheck?.report?.checks ?? []).filter((check: any) =>
    String(check?.group || "").startsWith("manifest"),
  ),
);

// ── 选项枚举（契约冻结枚举原样取值，label 仅辅助阅读）──

const ROLE_OPTIONS = [
  { value: "ADMIN", label: "ADMIN · 管理员" },
  { value: "BUILDER", label: "BUILDER · 构建者" },
  { value: "APPROVER", label: "APPROVER · 审批人" },
  { value: "OPERATOR", label: "OPERATOR · 运维" },
  { value: "USER", label: "USER · 普通用户" },
];
const MEMORY_KIND_OPTIONS = [
  { value: "PREFERENCE", label: "PREFERENCE · 偏好" },
  { value: "EXPERIENCE", label: "EXPERIENCE · 经验" },
];
const SCOPE_OPTIONS = [
  { value: "PRIVATE", label: "PRIVATE · 私有" },
  { value: "SHARED", label: "SHARED · 共享" },
];
const RETRY_ON_OPTIONS = [
  { value: "TRANSPORT", label: "TRANSPORT · 传输错误重试" },
  { value: "OUTPUT_SCHEMA_INVALID", label: "OUTPUT_SCHEMA_INVALID · 输出 Schema 校验失败重试" },
];

loadManifest();
watch(
  () => props.code,
  () => loadManifest(),
);
</script>

<template>
  <div class="manifest-panel">
    <!-- 头部：修订摘要 + CAS 保存 + 409 冲突处置 -->
    <div class="panel">
      <div class="row">
        <div>
          <h2>装配清单（manifest）</h2>
          <p class="muted">
            七分区受控编辑，保存即 PUT /admin/apps/{{ "{code}" }}/manifest（CAS
            baseRevision）；修订链只追加、内容寻址 digest，同内容不重复出版本。
          </p>
        </div>
        <div class="actions">
          <span class="muted revision-meta">
            修订 {{ head?.revision ?? "—" }} · digest {{ shortDigest }}
          </span>
          <el-button :loading="loading" @click="reloadHead">重载</el-button>
          <el-button
            type="primary"
            :loading="saving"
            :disabled="!loaded || !validationOk"
            v-permission="'edit'"
            @click="save"
            >保存 manifest</el-button
          >
        </div>
      </div>
      <p v-if="loaded && !exists && !loadError" class="muted">
        应用还没有工作区 manifest：以下分区为空白草稿，保存后创建修订 1（发布预检对无
        manifest 的应用跳过引用核验，存量发布口径不变）。
      </p>
      <p v-if="loadError" class="error" role="alert">
        {{ loadError }}
        <el-button text type="primary" @click="loadManifest">重试</el-button>
      </p>
      <div v-if="conflict" class="error conflict-box" role="alert">
        保存冲突（409 MANIFEST_REVISION_CONFLICT）：{{ conflict.message }}
        <div class="conflict-actions">
          <el-button @click="reloadHead">重载最新修订（覆盖本地未保存修改）</el-button>
        </div>
      </div>
      <p v-if="saveError && !conflict" class="error" role="alert">{{ saveError }}</p>
      <p v-if="savedNotice" class="muted saved-notice">{{ savedNotice }}</p>
      <p v-if="manifestDirty" class="muted">
        有未保存的修改：保存后既有预检结果过期，需重新预检再提交发布。
      </p>
    </div>

    <!-- 分区 1：模型 -->
    <div class="panel" data-partition="model">
      <h2>模型与推理策略</h2>
      <el-form label-position="left" label-width="11rem" class="partition-form">
        <el-form-item label="供应商编码">
          <el-input
            v-model="draft.model.defaultBinding.providerCode"
            placeholder="如 openai-compatible"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="模型名">
          <el-input
            v-model="draft.model.defaultBinding.modelName"
            placeholder="模型条目名称"
            maxlength="128"
          />
        </el-form-item>
        <el-form-item label="提示词模板 code（可选）">
          <el-input
            v-model="draft.model.defaultBinding.promptTemplateRef.code"
            placeholder="留空沿用既有发布绑定"
            maxlength="64"
          />
        </el-form-item>
        <el-form-item label="模板版本（可选）">
          <el-input-number
            v-model="draft.model.defaultBinding.promptTemplateRef.version"
            :min="1"
            :step="1"
            step-strictly
          />
        </el-form-item>
        <el-form-item label="温度 temperature">
          <el-input-number v-model="draft.model.inferencePolicy.temperature" :step="0.1" />
        </el-form-item>
        <el-form-item label="最大输出 maxOutput">
          <el-input-number
            v-model="draft.model.inferencePolicy.maxOutput"
            :min="1"
            :step="1"
            step-strictly
          />
        </el-form-item>
        <el-form-item label="超时 timeoutMs">
          <el-input-number
            v-model="draft.model.inferencePolicy.timeoutMs"
            :min="1"
            :step="1000"
            step-strictly
          />
        </el-form-item>
        <el-form-item label="重试上限 retry.max">
          <el-input-number
            v-model="draft.model.inferencePolicy.retry.max"
            :min="0"
            :step="1"
            step-strictly
          />
        </el-form-item>
        <el-form-item label="重试条件 retry.on">
          <el-checkbox-group v-model="draft.model.inferencePolicy.retry.on">
            <el-checkbox v-for="option in RETRY_ON_OPTIONS" :key="option.value" :value="option.value"
              >{{ option.label }}</el-checkbox
            >
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="轮次预算 budget.maxTurns">
          <el-input-number
            v-model="draft.model.inferencePolicy.budget.maxTurns"
            :min="1"
            :step="1"
            step-strictly
          />
        </el-form-item>
        <el-form-item label="调用预算 budget.maxModelCalls">
          <el-input-number
            v-model="draft.model.inferencePolicy.budget.maxModelCalls"
            :min="1"
            :step="1"
            step-strictly
          />
        </el-form-item>
      </el-form>
    </div>

    <!-- 分区 2：技能链 -->
    <div class="panel" data-partition="skills">
      <h2>技能链（GUIDE 指导链 + TOOL 工具链）</h2>
      <p class="muted">
        指导链为有序引用（code@version，同名按层遮蔽：工作区绑定 &gt; 项目目录 &gt;
        平台内置）；工具链为授权列表。
      </p>
      <div class="ref-list">
        <div
          v-for="(ref, index) in draft.skills.guideRefs"
          :key="index"
          class="ref-row"
        >
          <el-input
            v-model="draft.skills.guideRefs[index]"
            placeholder="code@version，如 analyze-flow@2"
            :aria-label="'指导链引用 ' + (index + 1)"
          />
          <el-button text :disabled="index === 0" @click="moveGuideRef(index, -1)">上移</el-button>
          <el-button text :disabled="index === draft.skills.guideRefs.length - 1" @click="moveGuideRef(index, 1)"
            >下移</el-button
          >
          <el-button text type="danger" @click="removeGuideRef(index)">移除</el-button>
        </div>
        <el-button text type="primary" @click="addGuideRef">+ 添加指导链引用</el-button>
      </div>
      <el-table :data="draft.skills.toolGrants" style="margin-top: 0.75rem">
        <el-table-column label="工具技能编码 skillCode" min-width="220">
          <template #default="{ row }">
            <el-input v-model="row.skillCode" maxlength="64" placeholder="如 detect-a" />
          </template>
        </el-table-column>
        <el-table-column label="版本 version" width="180">
          <template #default="{ row }">
            <el-input-number v-model="row.version" :min="1" :step="1" step-strictly />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ $index }">
            <el-button text type="danger" @click="removeToolGrant($index)">移除</el-button>
          </template>
        </el-table-column>
        <template #empty>尚无工具链授权</template>
      </el-table>
      <el-button text type="primary" style="margin-top: 0.5rem" @click="addToolGrant"
        >+ 添加工具授权</el-button
      >
    </div>

    <!-- 分区 3：插件 -->
    <div class="panel" data-partition="plugins">
      <h2>插件与授权</h2>
      <el-table :data="draft.plugins">
        <el-table-column label="插件编码 pluginCode" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.pluginCode" maxlength="64" placeholder="如 mcp-orbit" />
          </template>
        </el-table-column>
        <el-table-column label="版本 version" min-width="140">
          <template #default="{ row }">
            <el-input v-model="row.version" maxlength="64" placeholder="如 1.4.0" />
          </template>
        </el-table-column>
        <el-table-column label="能力清单 capabilities" min-width="260">
          <template #default="{ row }">
            <el-select
              v-model="row.capabilities"
              multiple
              filterable
              allow-create
              default-first-option
              placeholder="如 SKILL:detect-a / MCP_CONNECTION:<id>"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ $index }">
            <el-button text type="danger" @click="removePlugin($index)">移除</el-button>
          </template>
        </el-table-column>
        <template #empty>尚未授权插件（可选分区）</template>
      </el-table>
      <el-button text type="primary" style="margin-top: 0.5rem" @click="addPlugin"
        >+ 添加插件授权</el-button
      >
    </div>

    <!-- 分区 4：规则 -->
    <div class="panel" data-partition="rules">
      <h2>规则引用</h2>
      <p class="muted">code@version 引用；发布预检在本服务库内核验（主档 ENABLED 且版本 PUBLISHED）。</p>
      <el-select
        v-model="draft.rules"
        multiple
        filterable
        allow-create
        default-first-option
        placeholder="输入 code@version 回车添加，如 no-direct-delete@1"
        class="rules-select"
      />
    </div>

    <!-- 分区 5：记忆 -->
    <div class="panel" data-partition="memory">
      <h2>记忆策略</h2>
      <el-form label-position="left" label-width="11rem" class="partition-form">
        <el-form-item label="记忆类型 kinds">
          <el-checkbox-group v-model="draft.memoryPolicy.kinds">
            <el-checkbox v-for="option in MEMORY_KIND_OPTIONS" :key="option.value" :value="option.value"
              >{{ option.label }}</el-checkbox
            >
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="读范围 readScopes">
          <el-checkbox-group v-model="draft.memoryPolicy.readScopes">
            <el-checkbox v-for="option in SCOPE_OPTIONS" :key="option.value" :value="option.value"
              >{{ option.label }}</el-checkbox
            >
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="写范围 writeScopes">
          <el-checkbox-group v-model="draft.memoryPolicy.writeScopes">
            <el-checkbox v-for="option in SCOPE_OPTIONS" :key="option.value" :value="option.value"
              >{{ option.label }}</el-checkbox
            >
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="检索条数 topK">
          <el-input-number v-model="draft.memoryPolicy.topK" :min="1" :step="1" step-strictly />
        </el-form-item>
      </el-form>
    </div>

    <!-- 分区 6：上下文 -->
    <div class="panel" data-partition="context">
      <h2>上下文策略</h2>
      <el-form label-position="left" label-width="11rem" class="partition-form">
        <el-form-item label="会话长度 maxTurns">
          <el-input-number v-model="draft.contextPolicy.maxTurns" :min="1" :step="1" step-strictly />
        </el-form-item>
        <el-form-item label="上下文预算 contextBudget">
          <el-input-number
            v-model="draft.contextPolicy.contextBudget"
            :min="1"
            :step="1000"
            step-strictly
          />
        </el-form-item>
        <el-form-item label="压缩阈值 compactionThreshold">
          <el-input-number
            v-model="draft.contextPolicy.compactionThreshold"
            :min="0.01"
            :max="1"
            :step="0.05"
          />
        </el-form-item>
        <el-form-item label="历史窗口 historyWindow">
          <el-input-number
            v-model="draft.contextPolicy.historyWindow"
            :min="1"
            :step="1"
            step-strictly
          />
        </el-form-item>
      </el-form>
    </div>

    <!-- 分区 7：权限 -->
    <div class="panel" data-partition="permission">
      <h2>权限（发布授予角色）</h2>
      <p class="muted">授予后对应角色可在助手端见到并使用该智能体；至少一个角色。</p>
      <el-select v-model="draft.permission.roles" multiple class="roles-select" placeholder="选择发布授予角色">
        <el-option v-for="option in ROLE_OPTIONS" :key="option.value" :value="option.value" :label="option.label" />
      </el-select>
    </div>

    <!-- 发布预检与校验 issues 同屏（合并展示） -->
    <div class="panel" data-partition="precheck">
      <h2>发布预检与校验（同屏）</h2>
      <p class="muted">
        manifest 引用核验随发布预检返回（A3-1）：规则引用 PASS/BLOCKED；技能/插件目录在
        skill-registry，跨服务核验记 N/A 留痕不阻断；应用没有 manifest 时整组 SKIPPED。
      </p>
      <div class="row">
        <el-button :loading="busy" v-permission="'edit'" @click="emit('check')">运行发布预检</el-button>
        <span v-if="precheck" class="muted">
          预检 {{ precheck.precheckId }} · 草稿 rev {{ precheck.draftRev }} · manifest 修订
          {{ head?.revision ?? "—" }}
        </span>
      </div>
      <el-table v-if="manifestChecks.length" :data="manifestChecks" style="margin-top: 0.75rem">
        <el-table-column prop="group" label="检查组" width="190" />
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <span :class="['status', row.result]">{{ row.result }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="说明" min-width="320" />
      </el-table>
      <p v-else class="muted">
        尚无预检结果：保存 manifest 后点上方「运行发布预检」或智能体头「预检」，结果在此同屏。
      </p>
      <h3>manifest 校验 issues（path / code / message）</h3>
      <el-table v-if="combinedIssues.length" :data="combinedIssues" class="issues-table">
        <el-table-column prop="path" label="path" width="300" />
        <el-table-column prop="code" label="code" width="240" />
        <el-table-column prop="message" label="message" min-width="320" />
      </el-table>
      <p v-else class="muted">
        当前草稿未发现校验问题（本地预校验镜像服务端聚合规则；保存仍以服务端裁决为准）。
      </p>
    </div>
  </div>
</template>

<style scoped>
/* F3-5 分区卡样式：只接 tokens 变量（红线），不新增裸色值；布局沿用全局 .panel/.row/.actions 词汇。 */
.partition-form {
  max-width: 40rem;
}
.ref-list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  max-width: 40rem;
}
.ref-row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.ref-row .el-input {
  flex: 1;
}
.rules-select,
.roles-select {
  width: 100%;
  max-width: 40rem;
}
.revision-meta {
  font-size: var(--text-meta);
}
.conflict-box {
  margin-top: var(--sp-2);
}
.conflict-actions {
  margin-top: var(--sp-2);
}
.saved-notice {
  margin-top: var(--sp-2);
}
</style>
