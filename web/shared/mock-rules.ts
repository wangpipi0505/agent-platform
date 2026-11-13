/**
 * F3-3 mock 契约：规则域——接口稿 §5.5 规则 CRUD 六路径（A3-3 后端同形状，mock 期先行）：
 * GET/POST /admin/apps/{code}/rules、PUT /admin/rules/{code}/draft、
 * POST /admin/rules/{code}/versions/{v}/publish、GET /admin/rules/{code}/versions、
 * POST /admin/rules/{code}/enable|disable。
 * 发布预检输出同 scope 互斥冲突（RULE_MUTEX_BLOCKED）并指明规则对。仅 demo 装载。
 */

export type RuleNature = "GUIDANCE" | "ENFORCED"
export type RuleStatus = "DRAFT" | "PUBLISHED"
export type RuleScope = "DATA" | "OPERATION" | "OUTPUT"

/** 规则正文三字段（SchemaForm 简化为受控表单，接口稿 §5.5 + 方案 §5.9 草稿编辑）。 */
export interface RuleContent {
  /** 触发条件（何时适用）。 */
  trigger: string
  /** 动作（做什么）。 */
  action: string
  /** 要求（约束/验收口径）。 */
  requirement: string
}

export interface RuleVersion {
  version: number
  content: RuleContent
  publishedAt: string
  publishedBy: string
}

export interface MockRule {
  code: string
  name: string
  nature: RuleNature
  scope: RuleScope
  /** 当前生效版本状态：仅草稿（未发布过）=DRAFT，有已发布版本=PUBLISHED。 */
  status: RuleStatus
  /** 已发布版本号（无发布=0）。 */
  version: number
  /** 草稿版本号（草稿编辑递增，发布成功后并入 versions）。 */
  draftVersion: number
  /** 当前草稿正文（PUT draft 的编辑对象；展示「草稿待发布」）。 */
  draft: RuleContent
  /** 启停：ENFORCED 停用后不再参与装配校验（接口稿 enable|disable）。 */
  enabled: boolean
  versions: RuleVersion[]
  updatedAt: string
  demo: true
}

interface RuleStore extends MockRule {
  /** 同 scope 互斥键：发布预检发现同 scope 同键的另一条 ENFORCED 已启用规则则阻断。 */
  mutexKey: string
  appCode: string
}

const rules = new Map<string, RuleStore>()

const seedRules = (): RuleStore[] => [
  { code: "no-direct-delete", name: "禁止直删业务数据", nature: "ENFORCED", scope: "DATA",
    status: "PUBLISHED", version: 1, draftVersion: 1, enabled: true,
    draft: { trigger: "任何删除类数据操作", action: "拒绝执行并提示改用归档", requirement: "审计保留操作者与时间戳" },
    versions: [{ version: 1, publishedAt: "2026-11-10T09:00:00Z", publishedBy: "u-admin",
      content: { trigger: "任何删除类数据操作", action: "拒绝执行并提示改用归档", requirement: "审计保留操作者与时间戳" } }],
    updatedAt: "2026-11-10T09:00:00Z", demo: true, mutexKey: "delete-guard", appCode: "recon-assistant" },
  { code: "report-tone", name: "报告口径（GUIDANCE）", nature: "GUIDANCE", scope: "OUTPUT",
    status: "PUBLISHED", version: 2, draftVersion: 3, enabled: true,
    draft: { trigger: "生成对外报告", action: "先摘要后明细", requirement: "中文段落摘要，明细表仅列前 10 项" },
    versions: [
      { version: 1, publishedAt: "2026-11-08T09:00:00Z", publishedBy: "u-admin",
        content: { trigger: "生成对外报告", action: "先摘要后明细", requirement: "摘要不超过 200 字" } },
      { version: 2, publishedAt: "2026-11-11T14:00:00Z", publishedBy: "u-admin",
        content: { trigger: "生成对外报告", action: "先摘要后明细", requirement: "明细表仅列前 10 项" } },
    ],
    updatedAt: "2026-11-11T14:00:00Z", demo: true, mutexKey: "", appCode: "satellite-planning-assistant" },
  { code: "batch-delete-guard", name: "批量删除二次确认", nature: "ENFORCED", scope: "DATA",
    status: "DRAFT", version: 0, draftVersion: 1, enabled: false,
    draft: { trigger: "单次删除超过 10 条", action: "挂起并要求人工确认", requirement: "确认记录含条目清单摘要" },
    versions: [], updatedAt: "2026-11-12T10:00:00Z", demo: true, mutexKey: "delete-guard",
    appCode: "recon-assistant" },
]
seedRules().forEach((r) => rules.set(r.code, r))

const nowIso = () => new Date().toISOString()
const toRow = (r: RuleStore): MockRule => {
  const { mutexKey: _m, appCode: _a, ...rest } = r
  return rest
}
const contentOk = (c: RuleContent | undefined): c is RuleContent =>
  !!c && [c.trigger, c.action, c.requirement].every((f) => typeof f === "string" && f.trim().length > 0)

/** GET /admin/apps/{code}/rules：全量规则（nature/scope/status 过滤由查询参数承担，demo 直接返回）。 */
export function listRules(appCode?: string): { rules: MockRule[] } {
  const rows = [...rules.values()].filter((r) => !appCode || r.appCode === appCode)
  return { rules: rows.map(toRow) }
}

/** POST /admin/apps/{code}/rules：登记新规则（初始仅草稿）。编码重复 409。 */
export function createRule(req: { appCode: string; code: string; name: string; nature: RuleNature;
  scope: RuleScope; mutexKey?: string; content: RuleContent }):
  MockRule | { error: string; httpStatus: number; userMessage: string } {
  if (!req.code?.trim() || !req.name?.trim())
    return { error: "PARAM_CODE_REQUIRED", httpStatus: 400, userMessage: "编码与名称必填" }
  if (!contentOk(req.content))
    return { error: "PARAM_CONTENT_INVALID", httpStatus: 400, userMessage: "正文三字段（trigger/action/requirement）均必填" }
  if (rules.has(req.code))
    return { error: "RULE_CODE_EXISTS", httpStatus: 409, userMessage: `规则编码已存在：${req.code}` }
  const row: RuleStore = {
    code: req.code, name: req.name, nature: req.nature, scope: req.scope,
    status: "DRAFT", version: 0, draftVersion: 1, enabled: false, draft: req.content,
    versions: [], updatedAt: nowIso(), demo: true, mutexKey: req.mutexKey ?? "", appCode: req.appCode,
  }
  rules.set(row.code, row)
  return toRow(row)
}

/** PUT /admin/rules/{code}/draft：草稿编辑（content 三字段）。未知规则 404。 */
export function updateRuleDraft(code: string, req: { content: RuleContent }):
  MockRule | { error: string; httpStatus: number; userMessage: string } {
  const r = rules.get(code)
  if (!r) return { error: "RULE_NOT_FOUND", httpStatus: 404, userMessage: "规则不存在" }
  if (!contentOk(req.content))
    return { error: "PARAM_CONTENT_INVALID", httpStatus: 400, userMessage: "正文三字段均必填" }
  r.draft = { trigger: req.content.trigger.trim(), action: req.content.action.trim(), requirement: req.content.requirement.trim() }
  r.draftVersion += 1
  r.updatedAt = nowIso()
  return toRow(r)
}

/**
 * POST /admin/rules/{code}/versions/{v}/publish：草稿发布。
 * 发布预检（接口稿 §5.5）：同 scope 互斥——同 scope 下存在另一条**已启用 ENFORCED** 规则
 * 持相同互斥键则阻断（409 RULE_MUTEX_BLOCKED），conflicts 指明规则对；GUIDANCE 不参与阻断。
 * 仅草稿版本号与 draftVersion 一致时受理（防过期发布）。
 */
export function publishRule(code: string, version: number):
  { rule: MockRule; published: RuleVersion }
  | { error: string; httpStatus: number; userMessage?: string; conflicts?: { codes: string[]; scope: RuleScope }[] } {
  const r = rules.get(code)
  if (!r) return { error: "RULE_NOT_FOUND", httpStatus: 404 }
  if (version !== r.draftVersion) return { error: "RULE_DRAFT_STALE", httpStatus: 409, userMessage: "草稿已过期，请重新读取后再发布" }
  const conflicts: { codes: string[]; scope: RuleScope }[] = []
  if (r.nature === "ENFORCED" && r.mutexKey) {
    const clash = [...rules.values()].find((other) =>
      other.code !== code && other.nature === "ENFORCED" && other.enabled
      && other.scope === r.scope && other.mutexKey === r.mutexKey)
    if (clash) conflicts.push({ codes: [code, clash.code], scope: r.scope })
  }
  if (conflicts.length)
    return { error: "RULE_MUTEX_BLOCKED", httpStatus: 409, conflicts,
      userMessage: "同 scope 互斥冲突：与已启用规则冲突，需先停用对方或改互斥键" }
  const published: RuleVersion = { version: ++r.version, content: { ...r.draft }, publishedAt: nowIso(), publishedBy: "u-admin" }
  r.status = "PUBLISHED"
  r.enabled = true
  r.versions = [...r.versions, published]
  r.updatedAt = published.publishedAt
  return { rule: toRow(r), published }
}

/** GET /admin/rules/{code}/versions：版本历史（新→旧）。 */
export function listRuleVersions(code: string): { code: string; versions: RuleVersion[] } | { error: string; httpStatus: number } {
  const r = rules.get(code)
  if (!r) return { error: "RULE_NOT_FOUND", httpStatus: 404 }
  return { code, versions: [...r.versions].reverse() }
}

/** POST /admin/rules/{code}/enable|disable：启停（demo：reason 由页面经 confirmDanger 附带）。 */
export function setRuleEnabled(code: string, enabled: boolean, _reason?: string):
  MockRule | { error: string; httpStatus: number; userMessage: string } {
  const r = rules.get(code)
  if (!r) return { error: "RULE_NOT_FOUND", httpStatus: 404, userMessage: "规则不存在" }
  if (r.status !== "PUBLISHED")
    return { error: "RULE_NOT_PUBLISHED", httpStatus: 409, userMessage: "仅已发布规则可启停" }
  r.enabled = enabled
  r.updatedAt = nowIso()
  return toRow(r)
}

/** 演示种子复位（测试用）。 */
export function resetRulesDemo(): void {
  rules.clear()
  seedRules().forEach((r) => rules.set(r.code, r))
}
