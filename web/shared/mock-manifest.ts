/**
 * F3-5 工作区 manifest 编辑 mock 数据源（仅 demo，不经 mock-transport 装载；
 * 先例 = F2-2 mock-skill-precheck.ts / F1-2 mock-confirmation-counts.ts）。
 *
 * 后端 A3-1 已交付真实端点（app-management WorkspaceManifestController，接口稿 §5.5）：
 *   GET  /admin/apps/{code}/manifest  → {appCode, revision, digest, validation:{ok,issues}, manifest, createdBy, createdAt}
 *   PUT  /admin/apps/{code}/manifest  {requestId, manifest, baseRevision}
 *                                     → {appCode, revision, digest, validation:{ok,issues}, deduplicated}
 *                                     （baseRevision 过期 → 409 MANIFEST_REVISION_CONFLICT；
 *                                       校验失败 → 400 MANIFEST_INVALID，issues 聚合）
 *   GET  /admin/apps/{code}/manifests → {appCode, revisions:[…]}
 *
 * 联调期切换（一行）：ManifestPanel.vue 里 demoMode 分支的 fetchManifest/putManifest
 * 改为直接调 api()（真实客户端），本文件仅被 tree-shaking 剔除，页面其余逻辑零改动。
 *
 * demo:true = 非服务端数据（mock 资产惯例，见 mock-task.ts）。形状逐字段对齐 A3-1 响应体，
 * 409/400 错误对象对齐 api() 抛出形状（{status, code, message}），面板对 mock 与真实客户端
 * 用同一套错误处理。校验规则镜像后端 WorkspaceManifests.validate（聚合 issues，不短路）；
 * 服务端仍是唯一权威——真实端点的 400/409 以服务端裁决为准。
 *
 * manifest 结构来源：冻结契约 contracts/manifest.schema.json（C0-2，分区字段以此为准）。
 */

export type ManifestObject = Record<string, any>;

/** 校验 issue（path/code/message），与后端 WorkspaceManifests.issue 同形状。 */
export interface ManifestIssue {
  path: string;
  code: string;
  message: string;
}

export interface ManifestGetView {
  /** demo:true = 非服务端数据 */
  demo: true;
  appCode: string;
  revision: number;
  /** 内容寻址 sha256（规范化 JSON），与库稿 §4.6 digest 口径一致 */
  digest: string;
  validation: { ok: boolean; issues: ManifestIssue[] };
  manifest: ManifestObject;
  createdBy: string;
  createdAt: string;
}

export interface ManifestSaveResult {
  /** demo:true = 非服务端数据 */
  demo: true;
  appCode: string;
  revision: number;
  digest: string;
  validation: { ok: boolean; issues: ManifestIssue[] };
  /** true=同内容命中既有修订（digest 去重），revision 不推进 */
  deduplicated: boolean;
}

/** api() 错误形状（{status, code, message}），mock 抛出与真实客户端统一处理。 */
export interface ManifestError extends Error {
  status: number;
  code: string;
  issues?: ManifestIssue[];
}

export const MANIFEST_PUT_KEYS = ["requestId", "manifest", "baseRevision"] as const;
/** manifest jsonb ≤1MB（库稿 §4.6，V122 库层 CHECK 同值兜底）。 */
export const MANIFEST_MAX_BYTES = 1048576;
const ROLE_NAMES = ["ADMIN", "BUILDER", "APPROVER", "OPERATOR", "USER"];
const REF_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]*@[0-9]+$/;

function manifestError(
  status: number,
  code: string,
  message: string,
  issues?: ManifestIssue[],
): ManifestError {
  const error = new Error(message) as ManifestError;
  error.status = status;
  error.code = code;
  if (issues) error.issues = issues;
  return error;
}

// ── 规范化 JSON 与内容寻址 digest（对齐后端 Json.canonical / Digest.sha256）──

function canonicalJson(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, v]) => v !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
      .map(([k, v]) => `${JSON.stringify(k)}:${canonicalJson(v)}`);
    return `{${entries.join(",")}}`;
  }
  return JSON.stringify(value) ?? "null";
}

async function sha256Hex(text: string): Promise<string> {
  const bytes = new TextEncoder().encode(text);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, "0"))
    .join("");
}

// ── 校验（镜像后端 WorkspaceManifests.validate：聚合全部 issues，不短路）──

function issue(path: string, code: string, message: string): ManifestIssue {
  return { path, code, message };
}

function requireText(
  owner: Record<string, unknown> | undefined,
  key: string,
  max: number,
  path: string,
  issues: ManifestIssue[],
) {
  const value = owner?.[key];
  if (typeof value !== "string" || !value.trim() || value.length > max) {
    issues.push(
      issue(
        path,
        "MANIFEST_FIELD_REQUIRED",
        `${key} 必须是不超过 ${max} 字的非空字符串`,
      ),
    );
  }
}

function requirePositiveInt(
  owner: Record<string, unknown> | undefined,
  key: string,
  path: string,
  issues: ManifestIssue[],
  code = "MANIFEST_FIELD_REQUIRED",
) {
  const value = owner?.[key];
  if (
    typeof value !== "number" ||
    !Number.isInteger(value) ||
    value < 1
  ) {
    issues.push(issue(path, code, `${key} 必须是不小于 1 的整数`));
  }
}

function patternRefs(refs: unknown, path: string, issues: ManifestIssue[]) {
  if (!Array.isArray(refs)) return;
  for (const ref of refs) {
    if (typeof ref !== "string" || !REF_PATTERN.test(ref)) {
      issues.push(
        issue(
          path,
          "MANIFEST_FIELD_INVALID",
          `引用必须是 code@version 形状（code 以字母数字开头）: ${ref}`,
        ),
      );
    }
  }
}

/**
 * 对齐冻结契约 contracts/manifest.schema.json 与后端 WorkspaceManifests.validate：
 * 五个必键 + 各键必填子字段 + 出现即校形；未知顶层键不拒绝（additionalProperties=true）。
 */
export function validateManifest(manifest: ManifestObject): ManifestIssue[] {
  const issues: ManifestIssue[] = [];
  for (const key of ["metadata", "model", "skills", "contextPolicy", "permission"]) {
    const value = manifest?.[key];
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      issues.push(issue(`/${key}`, "MANIFEST_KEY_REQUIRED", `缺少必填对象: ${key}`));
    }
  }
  const metadata = manifest?.metadata;
  if (metadata && typeof metadata === "object") {
    const name = metadata.name;
    if (typeof name !== "string" || !name.trim() || name.length > 128) {
      issues.push(
        issue(
          "/metadata/name",
          "MANIFEST_FIELD_REQUIRED",
          "metadata.name 必须是不超过 128 字的非空字符串",
        ),
      );
    }
    if (metadata.category !== undefined) {
      const category = metadata.category;
      if (typeof category !== "string" || !category.trim() || category.length > 64) {
        issues.push(
          issue(
            "/metadata/category",
            "MANIFEST_FIELD_INVALID",
            "metadata.category 必须是不超过 64 字的字符串",
          ),
        );
      }
    }
  }
  const model = manifest?.model;
  if (model && typeof model === "object") {
    const binding = model.defaultBinding;
    if (!binding || typeof binding !== "object" || Array.isArray(binding)) {
      issues.push(
        issue(
          "/model/defaultBinding",
          "MANIFEST_FIELD_REQUIRED",
          "model.defaultBinding 必须是对象",
        ),
      );
    } else {
      requireText(binding, "providerCode", 64, "/model/defaultBinding/providerCode", issues);
      requireText(binding, "modelName", 128, "/model/defaultBinding/modelName", issues);
      const ref = binding.promptTemplateRef;
      if (ref && typeof ref === "object") {
        requireText(ref, "code", 64, "/model/defaultBinding/promptTemplateRef/code", issues);
        const version = ref.version;
        if (typeof version !== "number" || !Number.isInteger(version) || version < 1) {
          issues.push(
            issue(
              "/model/defaultBinding/promptTemplateRef/version",
              "MANIFEST_FIELD_INVALID",
              "promptTemplateRef.version 必须是正整数",
            ),
          );
        }
      }
    }
    const policy = model.inferencePolicy;
    if (policy && typeof policy === "object") {
      if (policy.retry && typeof policy.retry === "object") {
        const max = policy.retry.max;
        if (typeof max !== "number" || !Number.isInteger(max) || max < 0) {
          issues.push(
            issue(
              "/model/inferencePolicy/retry/max",
              "MANIFEST_FIELD_INVALID",
              "retry.max 必须是不小于 0 的整数",
            ),
          );
        }
        const on = policy.retry.on;
        if (on !== undefined) {
          const allowed = ["TRANSPORT", "OUTPUT_SCHEMA_INVALID"];
          if (
            !Array.isArray(on) ||
            on.some((item: unknown) => !allowed.includes(String(item)))
          ) {
            issues.push(
              issue(
                "/model/inferencePolicy/retry/on",
                "MANIFEST_FIELD_INVALID",
                "retry.on 只允许 TRANSPORT / OUTPUT_SCHEMA_INVALID",
              ),
            );
          }
        }
      }
      if (policy.budget && typeof policy.budget === "object") {
        requirePositiveInt(policy.budget, "maxTurns", "/model/inferencePolicy/budget/maxTurns", issues, "MANIFEST_FIELD_INVALID");
        requirePositiveInt(policy.budget, "maxModelCalls", "/model/inferencePolicy/budget/maxModelCalls", issues, "MANIFEST_FIELD_INVALID");
      }
    }
  }
  const skills = manifest?.skills;
  if (skills && typeof skills === "object") {
    const guideRefs = skills.guideRefs;
    if (!Array.isArray(guideRefs) || guideRefs.length === 0) {
      issues.push(
        issue(
          "/skills/guideRefs",
          "MANIFEST_FIELD_REQUIRED",
          "skills.guideRefs 必须是非空数组（指导链有序引用 code@version）",
        ),
      );
    } else {
      patternRefs(guideRefs, "/skills/guideRefs", issues);
    }
    if (!Array.isArray(skills.toolGrants)) {
      issues.push(
        issue(
          "/skills/toolGrants",
          "MANIFEST_FIELD_REQUIRED",
          "skills.toolGrants 必须是数组",
        ),
      );
    } else {
      for (const grant of skills.toolGrants) {
        if (!grant || typeof grant !== "object" || Array.isArray(grant)) {
          issues.push(
            issue(
              "/skills/toolGrants",
              "MANIFEST_FIELD_INVALID",
              "toolGrants 条目必须是 {skillCode, version} 对象",
            ),
          );
          continue;
        }
        requireText(grant, "skillCode", 64, "/skills/toolGrants/skillCode", issues);
        const version = grant.version;
        if (typeof version !== "number" || !Number.isInteger(version) || version < 1) {
          issues.push(
            issue(
              "/skills/toolGrants/version",
              "MANIFEST_FIELD_INVALID",
              "toolGrants.version 必须是正整数",
            ),
          );
        }
      }
    }
  }
  if (Array.isArray(manifest?.plugins)) {
    for (const plugin of manifest.plugins) {
      if (!plugin || typeof plugin !== "object" || Array.isArray(plugin)) {
        issues.push(
          issue(
            "/plugins",
            "MANIFEST_FIELD_INVALID",
            "plugins 条目必须是 {pluginCode, version, capabilities} 对象",
          ),
        );
        continue;
      }
      requireText(plugin, "pluginCode", 64, "/plugins/pluginCode", issues);
      requireText(plugin, "version", 64, "/plugins/version", issues);
    }
  }
  patternRefs(manifest?.rules, "/rules", issues);
  const memory = manifest?.memoryPolicy;
  if (memory && typeof memory === "object") {
    const kinds = ["PREFERENCE", "EXPERIENCE"];
    for (const key of ["kinds", "readScopes", "writeScopes"] as const) {
      const value = memory[key];
      if (value === undefined) continue;
      const allowed = key === "kinds" ? kinds : ["PRIVATE", "SHARED"];
      if (!Array.isArray(value) || value.some((item: unknown) => !allowed.includes(String(item)))) {
        issues.push(
          issue(
            `/memoryPolicy/${key}`,
            "MANIFEST_FIELD_INVALID",
            `memoryPolicy.${key} 只允许 ${allowed.join(" / ")}`,
          ),
        );
      }
    }
    const topK = memory.topK;
    if (topK !== undefined && (typeof topK !== "number" || !Number.isInteger(topK) || topK < 1)) {
      issues.push(
        issue(
          "/memoryPolicy/topK",
          "MANIFEST_FIELD_INVALID",
          "memoryPolicy.topK 必须是不小于 1 的整数",
        ),
      );
    }
  }
  const context = manifest?.contextPolicy;
  if (context && typeof context === "object") {
    requirePositiveInt(context, "maxTurns", "/contextPolicy/maxTurns", issues);
    requirePositiveInt(context, "contextBudget", "/contextPolicy/contextBudget", issues);
    const threshold = context.compactionThreshold;
    if (
      threshold !== undefined &&
      (typeof threshold !== "number" || threshold <= 0 || threshold > 1)
    ) {
      issues.push(
        issue(
          "/contextPolicy/compactionThreshold",
          "MANIFEST_FIELD_INVALID",
          "compactionThreshold 必须在 (0,1] 区间",
        ),
      );
    }
    const historyWindow = context.historyWindow;
    if (
      historyWindow !== undefined &&
      (typeof historyWindow !== "number" || !Number.isInteger(historyWindow) || historyWindow < 1)
    ) {
      issues.push(
        issue(
          "/contextPolicy/historyWindow",
          "MANIFEST_FIELD_INVALID",
          "historyWindow 必须是不小于 1 的整数",
        ),
      );
    }
  }
  const permission = manifest?.permission;
  if (permission && typeof permission === "object") {
    const roles = permission.roles;
    if (!Array.isArray(roles) || roles.length === 0) {
      issues.push(
        issue(
          "/permission/roles",
          "MANIFEST_FIELD_REQUIRED",
          "permission.roles 必须是非空数组（发布授予角色）",
        ),
      );
    } else {
      for (const role of roles) {
        if (!ROLE_NAMES.includes(String(role))) {
          issues.push(
            issue(
              "/permission/roles",
              "MANIFEST_FIELD_INVALID",
              `permission.roles 条目只允许 ${ROLE_NAMES.join(", ")}: ${role}`,
            ),
          );
        }
      }
    }
  }
  const flowRef = manifest?.flowRef;
  if (
    flowRef !== undefined &&
    (typeof flowRef !== "string" || flowRef.length > 160)
  ) {
    issues.push(
      issue("/flowRef", "MANIFEST_FIELD_INVALID", "flowRef 必须是不超过 160 字的字符串"),
    );
  }
  return issues;
}

// ── 演示修订链（内存存储；按 appCode 各自一条不可变修订链）──

interface StoredRevision {
  revision: number;
  digest: string;
  manifestJson: string;
}

const stores = new Map<string, StoredRevision[]>();

/** 演示种子：完整覆盖冻结 Schema 七分区（ metadata 之外），形状照接口稿 §5.5 示例。 */
function demoSeed(): ManifestObject {
  return {
    metadata: { name: "卫星运行助手", category: "运行支持", tags: ["在轨管理", "演示"] },
    model: {
      defaultBinding: { providerCode: "openai-compatible", modelName: "glm-4.7" },
      inferencePolicy: {
        temperature: 0.3,
        maxOutput: 4096,
        timeoutMs: 60000,
        retry: { max: 2, on: ["TRANSPORT"] },
        budget: { maxTurns: 40, maxModelCalls: 120 },
      },
    },
    skills: {
      guideRefs: ["analyze-flow@2"],
      toolGrants: [{ skillCode: "detect-a", version: 3 }],
    },
    plugins: [{ pluginCode: "mcp-orbit", version: "1.4.0", capabilities: ["SKILL:detect-a"] }],
    rules: ["no-direct-delete@1"],
    memoryPolicy: {
      kinds: ["PREFERENCE"],
      readScopes: ["PRIVATE"],
      writeScopes: ["PRIVATE"],
      topK: 5,
    },
    contextPolicy: {
      maxTurns: 200,
      contextBudget: 60000,
      compactionThreshold: 0.85,
      historyWindow: 20,
    },
    permission: { roles: ["USER", "OPERATOR"] },
  };
}

function revisionsOf(appCode: string): StoredRevision[] {
  let list = stores.get(appCode);
  if (!list) {
    list = [];
    stores.set(appCode, list);
  }
  return list;
}

async function seedIfAbsent(appCode: string): Promise<StoredRevision[]> {
  const list = revisionsOf(appCode);
  if (list.length === 0) {
    const manifest = demoSeed();
    list.push({
      revision: 1,
      digest: await sha256Hex(canonicalJson(manifest)),
      manifestJson: JSON.stringify(manifest),
    });
  }
  return list;
}

/** GET /admin/apps/{code}/manifest 的演示实现（形状 = A3-1 响应体）。 */
export async function mockManifestGet(appCode: string): Promise<ManifestGetView> {
  const list = await seedIfAbsent(appCode);
  const head = list[list.length - 1];
  return {
    demo: true,
    appCode,
    revision: head.revision,
    digest: head.digest,
    validation: { ok: true, issues: [] },
    manifest: JSON.parse(head.manifestJson),
    createdBy: "u-demo",
    createdAt: "2026-11-13T08:00:00Z",
  };
}

/**
 * PUT /admin/apps/{code}/manifest 的演示实现（语义镜像 WorkspaceManifests.put）：
 * 键校验 → manifest 校验（400 聚合 issues）→ CAS baseRevision（409 MANIFEST_REVISION_CONFLICT）
 * → digest 去重（命中既有修订原样返回，revision 不推进）→ 追加新修订。
 * 修订链不可变：保存永远追加，不存在原地改写。
 */
export async function mockManifestPut(
  appCode: string,
  body: { requestId?: string; manifest?: ManifestObject; baseRevision?: number },
): Promise<ManifestSaveResult> {
  for (const key of Object.keys(body ?? {})) {
    if (!MANIFEST_PUT_KEYS.includes(key as (typeof MANIFEST_PUT_KEYS)[number])) {
      throw manifestError(400, "MANIFEST_INVALID", `manifest 保存请求包含未知字段: ${key}`);
    }
  }
  const manifest = body?.manifest;
  if (!manifest || typeof manifest !== "object" || Array.isArray(manifest)
    || Object.keys(manifest).length === 0) {
    throw manifestError(400, "MANIFEST_INVALID", "缺少或无效字段: manifest（必须是非空 JSON 对象）");
  }
  const baseRevision = body?.baseRevision;
  if (
    typeof baseRevision !== "number" ||
    !Number.isInteger(baseRevision) ||
    baseRevision < 0
  ) {
    throw manifestError(
      400,
      "MANIFEST_INVALID",
      "缺少或无效字段: baseRevision（必须为不小于 0 的整数，首次保存传 0）",
    );
  }
  // 校验在 CAS 之前聚合返回：面板同屏逐条 issues（path/code/message），修完再存。
  const issues = validateManifest(manifest);
  if (issues.length > 0) {
    throw manifestError(
      400,
      "MANIFEST_INVALID",
      `manifest 校验未通过: ${issues.map((i) => `${i.path} ${i.message}`).join("; ")}`,
      issues,
    );
  }
  const list = await seedIfAbsent(appCode);
  const headRevision = list[list.length - 1].revision;
  if (baseRevision !== headRevision) {
    throw manifestError(
      409,
      "MANIFEST_REVISION_CONFLICT",
      `baseRevision 已过期: 期望 ${headRevision}，请求携带 ${baseRevision}，请重新读取头修订后重试: ${appCode}`,
    );
  }
  const digest = await sha256Hex(canonicalJson(manifest));
  const existing = list.find((item) => item.digest === digest);
  if (existing) {
    // 同内容不重复出版本：内容寻址命中既有修订即原样返回（revision 不推进）。
    return {
      demo: true,
      appCode,
      revision: existing.revision,
      digest,
      validation: { ok: true, issues: [] },
      deduplicated: true,
    };
  }
  const revision = headRevision + 1;
  list.push({ revision, digest, manifestJson: JSON.stringify(manifest) });
  return {
    demo: true,
    appCode,
    revision,
    digest,
    validation: { ok: true, issues: [] },
    deduplicated: false,
  };
}

/** 测试隔离：清空演示修订链（生产代码不调用）。 */
export function resetMockManifests() {
  stores.clear();
}
