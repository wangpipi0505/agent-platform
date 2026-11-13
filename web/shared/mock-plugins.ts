/**
 * F3-3 mock 契约：插件域——接口稿 §5.6 插件/MCP 端点形状（skill-registry，A13-1 验收对齐）：
 * POST/GET /admin/plugin-sources、POST /admin/plugin-sources/{id}/discover（202 {discoveryId,status:'QUEUED'}）、
 * GET /admin/plugin-sources/{id}/discoveries/{discoveryId}（QUEUED/RUNNING/SUCCEEDED/FAILED 状态机；
 * 失败返回 error:{code,message} 且保留上一次成功目录；成功返回 changes:{added,changed,removed}）、
 * GET /admin/plugins?status=&source=、POST /admin/plugins/{versionId}/enable|disable（disable 填 reason）、
 * POST /admin/plugins/{versionId}/grants、DELETE /admin/plugins/grants/{grantId}。仅 demo 装载。
 */

export type PluginSourceStatus = "ACTIVE" | "PAUSED"
export type PluginStatus = "ENABLED" | "DISABLED"
export type DiscoveryStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED"

export interface MockPluginSource {
  id: string
  name: string
  type: "MARKETPLACE" | "GIT" | "INTERNAL"
  url: string
  status: PluginSourceStatus
  lastDiscoveryAt?: string
  /** 演示开关：下一次发现走 FAILED 分支（A13-1 插件失败场景；失败保留上一次目录）。 */
  failNextDiscovery?: true
  demo: true
}

export interface PluginGrant {
  grantId: string
  granteeType: "ROLE" | "WORKSPACE" | "SERVICE"
  granteeCode: string
  capabilities: string[]
}

export interface MockPlugin {
  versionId: string
  pluginCode: string
  name: string
  version: string
  sourceId: string
  capabilities: string[]
  status: PluginStatus
  /** 停用原因（disable 必填，接口稿 §5.6：停用拦截后续调用）。 */
  disableReason?: string
  grants: PluginGrant[]
  demo: true
}

export interface DiscoveryChange {
  pluginCode: string
  version: string
  capabilities: string[]
}

export interface MockDiscovery {
  discoveryId: string
  sourceId: string
  status: DiscoveryStatus
  startedAt: string
  finishedAt?: string
  error?: { code: string; message: string }
  changes?: { added: DiscoveryChange[]; changed: DiscoveryChange[]; removed: DiscoveryChange[] }
}

const sources = new Map<string, MockPluginSource>()
const plugins = new Map<string, MockPlugin>()
const discoveries = new Map<string, MockDiscovery>()
let grantSeq = 0

const seedSources = (): MockPluginSource[] => [
  { id: "src-1", name: "官方插件市场", type: "MARKETPLACE", url: "https://marketplace.example.com/v1", status: "ACTIVE", demo: true },
  { id: "src-2", name: "内部镜像源", type: "INTERNAL", url: "https://nexus.example.com/plugins", status: "ACTIVE",
    lastDiscoveryAt: "2026-11-12T08:00:00Z", demo: true },
]
const seedPlugins = (): MockPlugin[] => [
  { versionId: "pv-1", pluginCode: "ocr-enhance", name: "识别增强", version: "1.4.0", sourceId: "src-1",
    capabilities: ["SKILL:detect-a", "SKILL:detect-b"], status: "ENABLED", grants: [
      { grantId: "grant-1", granteeType: "WORKSPACE", granteeCode: "recon-assistant", capabilities: ["SKILL:detect-a"] },
    ], demo: true },
  { versionId: "pv-2", pluginCode: "pdf-export", name: "报告导出", version: "2.0.1", sourceId: "src-1",
    capabilities: ["SKILL:export-pdf"], status: "DISABLED", disableReason: "升级窗口期统一停用", grants: [], demo: true },
]

function resetPluginDemoData(): void {
  sources.clear()
  plugins.clear()
  discoveries.clear()
  grantSeq = 0
  seedSources().forEach((s) => sources.set(s.id, s))
  seedPlugins().forEach((p) => plugins.set(p.versionId, p))
}
resetPluginDemoData()

/** GET /admin/plugin-sources。 */
export function listPluginSources(): MockPluginSource[] {
  return [...sources.values()]
}

/** POST /admin/plugin-sources：登记来源。 */
export function createPluginSource(req: { name: string; type: MockPluginSource["type"]; url: string }):
  MockPluginSource | { error: string; httpStatus: number; userMessage: string } {
  if (!req.name?.trim() || !req.url?.trim())
    return { error: "PARAM_SOURCE_INVALID", httpStatus: 400, userMessage: "名称与地址必填" }
  const row: MockPluginSource = {
    id: `src-${sources.size + 3}`, name: req.name.trim(), type: req.type, url: req.url.trim(),
    status: "ACTIVE", demo: true,
  }
  sources.set(row.id, row)
  return row
}

/**
 * POST /admin/plugin-sources/{id}/discover：触发发现，受理返回 202 形状
 * `{discoveryId, status: 'QUEUED'}`（接口稿 §5.6）。状态推进经 pollDiscovery 演示。
 */
export function triggerDiscovery(sourceId: string):
  { discoveryId: string; status: DiscoveryStatus } | { error: string; httpStatus: number; userMessage: string } {
  const source = sources.get(sourceId)
  if (!source) return { error: "SOURCE_NOT_FOUND", httpStatus: 404, userMessage: "插件来源不存在" }
  const pending = [...discoveries.values()].find((d) => d.sourceId === sourceId && (d.status === "QUEUED" || d.status === "RUNNING"))
  if (pending)
    return { error: "DISCOVERY_IN_PROGRESS", httpStatus: 409, userMessage: `已有发现进行中（${pending.discoveryId}）` }
  const discoveryId = `disc-${discoveries.size + 1}`
  discoveries.set(discoveryId, { discoveryId, sourceId, status: "QUEUED", startedAt: new Date().toISOString() })
  return { discoveryId, status: "QUEUED" }
}

/**
 * GET /admin/plugin-sources/{id}/discoveries/{discoveryId}：按来源查询发现结果。
 * demo 状态机：QUEUED→RUNNING→（按来源 failNextDiscovery 分支）SUCCEEDED|FAILED。
 * 失败返回可展示 error:{code,message} 且**不改动插件目录**（失败保留上一次成功目录）；
 * 成功返回 changes:{added,changed,removed} 并把新增/变更合入目录。
 */
export function pollDiscovery(sourceId: string, discoveryId: string):
  MockDiscovery | { error: string; httpStatus: number } {
  const d = discoveries.get(discoveryId)
  if (!d || d.sourceId !== sourceId) return { error: "DISCOVERY_NOT_FOUND", httpStatus: 404 }
  const source = sources.get(sourceId)!
  if (d.status === "QUEUED") {
    d.status = "RUNNING"
  } else if (d.status === "RUNNING") {
    d.finishedAt = new Date().toISOString()
    if (source.failNextDiscovery) {
      d.status = "FAILED"
      d.error = { code: "SOURCE_UNREACHABLE", message: "来源地址不可达：网关超时（演示失败：上一次目录保持不变）" }
      source.failNextDiscovery = undefined
    } else {
      d.status = "SUCCEEDED"
      const added: DiscoveryChange = { pluginCode: "geo-annotate", version: "0.9.0", capabilities: ["SKILL:annotate"] }
      const changed: DiscoveryChange = { pluginCode: "ocr-enhance", version: "1.5.0", capabilities: ["SKILL:detect-a", "SKILL:detect-b", "SKILL:detect-c"] }
      const removed: DiscoveryChange = { pluginCode: "legacy-export", version: "1.0.0", capabilities: [] }
      d.changes = { added: [added], changed: [changed], removed: [removed] }
      // 成功合入目录：新增插件 + 变更版本升级；removed 仅记录（目录中无该插件则无操作）。
      const newId = `pv-${plugins.size + 3}`
      plugins.set(newId, { versionId: newId, pluginCode: added.pluginCode, name: "地理标注", version: added.version,
        sourceId, capabilities: added.capabilities, status: "ENABLED", grants: [], demo: true })
      const upgraded = [...plugins.values()].find((p) => p.pluginCode === changed.pluginCode)
      if (upgraded) upgraded.version = changed.version
      source.lastDiscoveryAt = d.finishedAt
    }
  }
  return d
}

/** GET /admin/plugins?status=&source=：版本与能力清单。 */
export function listPlugins(query: { status?: PluginStatus; source?: string } = {}): MockPlugin[] {
  return [...plugins.values()]
    .filter((p) => !query.status || p.status === query.status)
    .filter((p) => !query.source || p.sourceId === query.source)
}

/** POST /admin/plugins/{versionId}/enable|disable：启停；disable 必填 reason（停用拦截后续调用）。 */
export function setPluginEnabled(versionId: string, enabled: boolean, reason?: string):
  MockPlugin | { error: string; httpStatus: number; userMessage: string } {
  const p = plugins.get(versionId)
  if (!p) return { error: "PLUGIN_NOT_FOUND", httpStatus: 404, userMessage: "插件版本不存在" }
  if (!enabled && !reason?.trim())
    return { error: "PARAM_REASON_REQUIRED", httpStatus: 400, userMessage: "停用必须填写原因" }
  p.status = enabled ? "ENABLED" : "DISABLED"
  if (enabled) delete p.disableReason
  else p.disableReason = reason!.trim()
  return p
}

/** POST /admin/plugins/{versionId}/grants：授权。 */
export function addPluginGrant(versionId: string, req: { granteeType: PluginGrant["granteeType"]; granteeCode: string;
  capabilities: string[] }): MockPlugin | { error: string; httpStatus: number; userMessage: string } {
  const p = plugins.get(versionId)
  if (!p) return { error: "PLUGIN_NOT_FOUND", httpStatus: 404, userMessage: "插件版本不存在" }
  if (!req.granteeCode?.trim() || !req.capabilities?.length)
    return { error: "PARAM_GRANT_INVALID", httpStatus: 400, userMessage: "被授权对象与能力清单必填" }
  p.grants = [...p.grants, { grantId: `grant-${++grantSeq}`, granteeType: req.granteeType,
    granteeCode: req.granteeCode.trim(), capabilities: [...req.capabilities] }]
  return p
}

/** DELETE /admin/plugins/grants/{grantId}：撤权（下次解析生效）。 */
export function removePluginGrant(grantId: string): { grantId: string; revoked: true } | { error: string; httpStatus: number } {
  const owner = [...plugins.values()].find((p) => p.grants.some((g) => g.grantId === grantId))
  if (!owner) return { error: "GRANT_NOT_FOUND", httpStatus: 404 }
  owner.grants = owner.grants.filter((g) => g.grantId !== grantId)
  return { grantId, revoked: true }
}

/** 演示种子复位（测试用）。 */
export function resetPluginsDemo(): void {
  resetPluginDemoData()
}
