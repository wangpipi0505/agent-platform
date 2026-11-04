import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { applicationQuery } from "../../../../shared/statistics-lifecycle";
import { apiRoutes, projectionRoute } from "../../../../shared/routes";
import { usePageOperation } from "../../usePageOperation";

export type ResourceKind =
  | "apps"
  | "skills"
  | "providers"
  | "entries"
  | "templates"
  | "approvals"
  | "evaluations"
  | "audit";

function endpointFor(kind: ResourceKind) {
  switch (kind) {
    case "apps":
      return apiRoutes.apps;
    case "skills":
      return apiRoutes.skills;
    case "providers":
      return apiRoutes.providers;
    case "entries":
      return apiRoutes.entries;
    case "templates":
      return apiRoutes.templates;
    case "approvals":
      return projectionRoute("approvals");
    case "evaluations":
      return apiRoutes.evaluations;
    case "audit":
      return projectionRoute("audit");
  }
}

/**
 * Shared list state for the resource pages. Each page owns its own instance,
 * so a route change cannot leak errors or rows into a neighbouring page.
 */
export function useResourceList(
  kind: ResourceKind,
  options: { serverFiltered?: boolean; providerScoped?: boolean } = {},
) {
  const route = useRoute();
  const { auth, api, busy, error, trace, operation, success } =
    usePageOperation();
  const items = ref<any[]>([]);
  const filter = reactive({
    keyword: "",
    code: "",
    domain: "",
    bizCategory: "",
    tags: "",
    status: "",
    type: "",
  });
  const page = ref(1);
  const pageSize = ref(12);
  const providerId = computed(() =>
    options.providerScoped && route.name === "provider-entries"
      ? String(route.params.providerId || "")
      : "",
  );
  const providerScoped = computed(
    () => options.providerScoped && route.name === "provider-entries",
  );

  const filtered = computed(() => {
    // Application search is executed by the server; the other projections are
    // still returned as a single collection by their controllers.
    if (kind === "apps") return items.value;
    return items.value.filter((row) =>
      Object.entries(filter).every(([key, value]) => {
        if (!value) return true;
        const field =
          key === "keyword"
            ? row.name || row.appCode || row.runId || row.code || ""
            : row[key] || "";
        return String(field).toLowerCase().includes(String(value).toLowerCase());
      }),
    );
  });
  const paginated = computed(() =>
    filtered.value.slice(
      (page.value - 1) * pageSize.value,
      page.value * pageSize.value,
    ),
  );

  async function load() {
    if (!auth.token) return;
    await operation(async () => {
      page.value = 1;
      items.value = [];
      const query = options.serverFiltered
        ? applicationQuery(filter)
        : "";
      const body = await api<any>(
        `${endpointFor(kind)}${query ? `?${query}` : ""}`,
      );
      if (!Array.isArray(body) && !Array.isArray(body?.items))
        throw new Error("服务返回的列表格式不正确，请检查接口契约后重试");
      let rows = Array.isArray(body) ? body : body.items;
      if (options.providerScoped)
        rows = rows.filter(
          (row: Record<string, unknown>) =>
            !providerScoped.value ||
            String(row.providerId) === providerId.value,
        );
      items.value = rows;
    });
  }

  let filterTimer: ReturnType<typeof setTimeout>;
  watch(
    filter,
    () => {
      page.value = 1;
      if (!options.serverFiltered) return;
      clearTimeout(filterTimer);
      filterTimer = setTimeout(() => void load(), 300);
    },
    { deep: true },
  );
  watch(() => route.fullPath, () => void load());
  onMounted(load);

  return {
    auth,
    api,
    busy,
    error,
    trace,
    operation,
    success,
    items,
    filter,
    page,
    pageSize,
    providerId,
    filtered,
    paginated,
    load,
  };
}
