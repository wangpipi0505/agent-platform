import { onScopeDispose } from "vue";
import { axiosTransport, combinedSignal, createApi, useAuth } from "./api";

export type JsonObject = Record<string, unknown>;
export type CapabilityPackageSummary = {
  packageVersionId: number;
  packageId: string;
  version: string;
  sha256: string;
  softwareId: string;
  softwareVersion?: string | null;
  bindingStatus: "UNRESOLVED" | "RESOLVED";
  verificationStatus: "NOT_EVALUATED";
  applicationCount: number;
  createdAt: string;
  resolvedAt?: string;
};
export type CapabilitySourceApplication = {
  appId: number; appCode: string; name: string; type: string; status: string;
  draftRev: number | null; currentVersionNo: number | null;
};
export type CapabilityResolvedBinding = {
  routeProfileId: string; toolId: string; interfaceId: string;
  skillCode: string; skillVersionId: number; version: number; sha256: string;
};
export type CapabilityPackageDetail = CapabilityPackageSummary & {
  declaration: JsonObject;
  resolvedMapping: { environment?: string; routeProfileId?: string; bindings?: CapabilityResolvedBinding[] };
  applications: CapabilitySourceApplication[];
  applicationsTruncated: boolean;
};
export type CapabilityPackagePage = { items: CapabilityPackageSummary[]; nextBeforeId?: number };
export type CapabilityPackageFilters = { q?: string; beforeId?: number; ontologySpace?: string; ontologyRelease?: string };

export const canReadCapabilityPackages = (roles: readonly string[]) => roles.some((role) => ["VIEWER", "EDITOR", "ADMIN"].includes(role));

export function parseJsonObject(text: string): JsonObject {
  let value: unknown;
  try { value = JSON.parse(text); } catch { throw new Error("请输入有效的 JSON 对象"); }
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("请输入 JSON 对象");
  return value as JsonObject;
}
const object = (value: unknown): JsonObject => value && typeof value === "object" && !Array.isArray(value) ? value as JsonObject : {};
export function declarationOptions(declaration: JsonObject) {
  const profiles = declaration.routeProfiles;
  const source = Array.isArray(profiles) && profiles.length ? profiles : declaration.interfaces;
  return {
    environments: Object.keys(object(object(declaration.software).environments)),
    routes: Array.isArray(source) ? source.map((item: unknown) => object(item).id).filter((id): id is string => typeof id === "string") : [],
  };
}

/** 配置只给本体站点 origin；原声明引用放入已有消费页，不携带认证或宣称已定位版本。 */
export function ontologyLink(base: string, reference: unknown) {
  const ontology = object(reference);
  if (!["REFERENCE", "OWNED"].includes(String(ontology.mode)) || typeof ontology.space !== "string" || !ontology.space || typeof ontology.release !== "string" || !ontology.release) return "";
  try {
    const url = new URL(base);
    if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== "/") return "";
    url.pathname = `/spaces/${encodeURIComponent(ontology.space)}/releases`;
    url.search = new URLSearchParams({ tab: "consumption", releaseId: ontology.release }).toString();
    return url.href;
  } catch { return ""; }
}

export function capabilityPackageClient(api: ReturnType<typeof createApi>) {
  const root = "/admin/capability-packages";
  return {
    list(filters: CapabilityPackageFilters = {}) {
      const query = new URLSearchParams({ limit: "30" });
      for (const [key, value] of Object.entries(filters)) if (value !== undefined && value !== "") query.set(key, String(value));
      return api<CapabilityPackagePage>(`${root}?${query}`);
    },
    detail: (id: number) => api<CapabilityPackageDetail>(`${root}/${encodeURIComponent(id)}`),
    register: (declaration: JsonObject) => api<{ packageVersionId: number }>(`${root}/import`, "POST", { package: declaration }),
    resolve: (id: number, plan: JsonObject) => api<unknown>(`${root}/${encodeURIComponent(id)}/bindings/resolve`, "POST", plan),
    createApplications: (id: number, plan: JsonObject) => api<unknown>(`${root}/${encodeURIComponent(id)}/applications`, "POST", plan),
  };
}

/** 能力目录始终请求真实 API；继承当前会话失效和组件卸载的取消边界。 */
export function useCapabilityPackageClient() {
  const auth = useAuth(), session = auth.snapshot(), disposed = new AbortController();
  onScopeDispose(() => disposed.abort());
  return capabilityPackageClient(createApi(() => auth.token, axiosTransport,
    () => auth.invalidate(session.generation), { signal: combinedSignal(session.signal, disposed.signal)!, current: session.current }));
}
