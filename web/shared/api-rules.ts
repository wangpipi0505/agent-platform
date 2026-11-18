/**
 * M5-2：规则域真实 API 客户端（经网关）——F3-3 资产页·规则管理数据面。
 *
 * 端点（app-management RuleController/AppRuleController，A3-3，网关/authority 已登记）：
 *   GET  /admin/apps/{code}/rules                     应用规则目录（ADMIN）
 *   POST /admin/apps/{code}/rules                     新建规则草稿
 *   PUT  /admin/rules/{code}/draft                    草稿编辑
 *   POST /admin/rules/{code}/versions/{versionNo}/publish  发布（预检互斥 409 RULE_MUTEX_BLOCKED）
 *   GET  /admin/rules/{code}/versions                 版本链
 *   POST /admin/rules/{code}/enable|disable           启停
 *
 * 诚实边界：规则主档按应用维护——管理台跨应用列表经 /admin/apps 目录逐应用聚合（N=应用数）。
 */
import { useApi } from "./api";
import { guard, type ApiFailure } from "./api-task";

export type RuleNature = "GUIDANCE" | "ENFORCED";
export type RuleStatus = "DRAFT" | "PUBLISHED";
export type RuleScope = "DATA" | "OPERATION" | "OUTPUT";
export interface RuleContent {
  trigger: string;
  action: string;
  requirement: string;
}
export interface RuleVersion {
  version: number;
  publishedAt?: string;
  publishedBy?: string;
  content: RuleContent;
}
export interface MockRule {
  code: string;
  name: string;
  nature: RuleNature;
  scope: RuleScope | string;
  status: RuleStatus;
  version: number;
  draftVersion: number;
  draft: RuleContent;
  enabled: boolean;
  versions: RuleVersion[];
  updatedAt?: string;
  appCode?: string;
}

function normalizeContent(raw: any): RuleContent {
  const source = raw && typeof raw === "object" ? raw : {};
  return {
    trigger: String(source.trigger ?? ""),
    action: String(source.action ?? ""),
    requirement: String(source.requirement ?? ""),
  };
}

function normalizeRule(row: any): MockRule {
  const versions = Array.isArray(row?.versions) ? row.versions : [];
  const scopeRaw = row?.scope;
  const scope =
    typeof scopeRaw === "string" ? scopeRaw : String(scopeRaw?.kind ?? scopeRaw?.scope ?? "DATA");
  const publishedVersions: RuleVersion[] = versions.map((version: any) => ({
    version: Number(version.version ?? 0),
    publishedAt: version.publishedAt ?? version.published_at,
    publishedBy: version.publishedBy ?? version.published_by,
    content: normalizeContent(version.content ?? version),
  }));
  const currentVersion = Number(
    row?.version ?? row?.currentVersionNo ?? row?.current_version_no ?? publishedVersions.at(-1)?.version ?? 0,
  );
  return {
    code: String(row?.code ?? ""),
    name: String(row?.name ?? ""),
    nature: (row?.nature ?? "GUIDANCE") as RuleNature,
    scope,
    status: (row?.status ?? "DRAFT") as RuleStatus,
    version: currentVersion,
    draftVersion: Number(row?.draftVersion ?? row?.draft_version ?? currentVersion),
    draft: normalizeContent(row?.draft ?? row?.draftContent ?? publishedVersions.at(-1)?.content),
    enabled: row?.enabled ?? String(row?.status ?? "").toUpperCase() === "ENABLED",
    versions: publishedVersions,
    updatedAt: row?.updatedAt ?? row?.updated_at,
    appCode: row?.appCode ?? row?.app_code,
  };
}

/** 应用目录（GET /admin/apps）：跨应用规则聚合用。 */
async function appCodes(): Promise<string[]> {
  const api = useApi();
  try {
    const apps = await api<any>("/admin/apps");
    const rows: any[] = Array.isArray(apps) ? apps : Array.isArray(apps?.apps) ? apps.apps : [];
    return rows.map((row) => String(row?.code ?? "")).filter(Boolean);
  } catch {
    return [];
  }
}

/** 规则列表（真实端点，跨应用聚合）：单应用失败不阻断其余（各自如实留空）。 */
export async function listRules(appCode?: string): Promise<{ rules: MockRule[] }> {
  const api = useApi();
  const codes = appCode ? [appCode] : await appCodes();
  const aggregated = await Promise.all(
    codes.map(async (code) => {
      try {
        const rows = await api<any>(`/admin/apps/${encodeURIComponent(code)}/rules`);
        const list: any[] = Array.isArray(rows) ? rows : Array.isArray(rows?.rules) ? rows.rules : [];
        return list.map((row) => normalizeRule({ ...row, appCode: code }));
      } catch {
        return [];
      }
    }),
  );
  return { rules: aggregated.flat() };
}

/** 新建规则草稿（真实端点）。服务端 scope 为对象（键 app/stage/toolClass）——页面 scope 档位
 *  （DATA/OPERATION/OUTPUT，mock 时代口径）在真实模型中无对应键，登记 scope={app} 并如实注明。 */
export async function createRule(req: {
  appCode: string;
  code: string;
  name: string;
  nature: RuleNature;
  scope?: RuleScope | string;
  content?: RuleContent;
  /** 同 scope 互斥键（服务端发布预检用；透传）。 */
  mutexKey?: string;
}): Promise<MockRule | ApiFailure> {
  const api = useApi();
  return guard(async () =>
    normalizeRule(
      await api<any>(`/admin/apps/${encodeURIComponent(req.appCode)}/rules`, "POST", {
        requestId: crypto.randomUUID(),
        code: req.code,
        name: req.name,
        nature: req.nature,
        scope: { app: req.appCode },
        content: req.content,
        ...(req.mutexKey ? { mutexKey: req.mutexKey } : {}),
      }),
    ),
  );
}

/** 草稿编辑（真实端点）：content 三字段受控表单。 */
export async function updateRuleDraft(
  code: string,
  req: { content: RuleContent },
): Promise<MockRule | ApiFailure> {
  const api = useApi();
  return guard(async () =>
    normalizeRule(await api<any>(`/admin/rules/${encodeURIComponent(code)}/draft`, "PUT", req)),
  );
}

/** 发布（真实端点）：发布预检同 scope 互斥 409 RULE_MUTEX_BLOCKED。 */
export async function publishRule(code: string, version: number) {
  const api = useApi();
  return guard(async () =>
    api<any>(`/admin/rules/${encodeURIComponent(code)}/versions/${encodeURIComponent(String(version))}/publish`, "POST", {}),
  );
}

/** 版本链（真实端点）。 */
export async function listRuleVersions(
  code: string,
): Promise<{ code: string; versions: RuleVersion[] } | ApiFailure> {
  const api = useApi();
  return guard(async () => {
    const rows = await api<any>(`/admin/rules/${encodeURIComponent(code)}/versions`);
    const list: any[] = Array.isArray(rows) ? rows : Array.isArray(rows?.versions) ? rows.versions : [];
    return {
      code,
      versions: list.map((version) => ({
        version: Number(version.version ?? 0),
        publishedAt: version.publishedAt ?? version.published_at,
        publishedBy: version.publishedBy ?? version.published_by,
        content: normalizeContent(version.content ?? version),
      })),
    };
  });
}

/** 启停（真实端点）：ENFORCED 停用后不参与装配校验。 */
export async function setRuleEnabled(code: string, enabled: boolean, reason?: string) {
  const api = useApi();
  return guard(async () =>
    api<any>(
      `/admin/rules/${encodeURIComponent(code)}/${enabled ? "enable" : "disable"}`,
      "POST",
      reason ? { reason } : {},
    ),
  );
}
