<script setup lang="ts">
import {
  computed,
  ref,
  watch,
  onMounted,
  onBeforeUnmount,
  nextTick,
  defineAsyncComponent,
} from "vue";
import { ElMessage } from "element-plus";
import { apiRoutes, projectionRoute } from "../../shared/routes";
import type { EChartsType } from "echarts/core";
import { useAuth, createApi, useApi, login } from "../../shared/api";
const RunDetail = defineAsyncComponent(() => import("./RunDetail.vue"));
import ResourceDetail from "./ResourceDetail.vue";
import ChatConfig from "./ChatConfig.vue";
import SkillWizard from "./SkillWizard.vue";
import SchemaInputs from "../../shared/SchemaInputs.vue";
import EvaluationPanel from './EvaluationPanel.vue';
import StatisticsPanel from "./StatisticsPanel.vue";
import ApplicationHistory from "./ApplicationHistory.vue";
import SkillStatistics from "./SkillStatistics.vue";
import { applicationQuery, copyApplicationBody } from "../../shared/statistics-lifecycle";
const statisticsPanel = ref<InstanceType<typeof StatisticsPanel>>();
const copySource = ref("");
const copyForm = ref({code:"",name:""});
const copyError = ref("");
const copying = ref(false);
const refreshKey = ref(0);
function startCopy(row:any) { copySource.value=row.code;copyForm.value={code:"",name:row.name+" 副本"};copyError.value=""; }
function closeCopy(value:boolean) {if(!value&&!copying.value)copySource.value='';}
async function copyApplication() {
  if(copying.value||!auth.can('edit'))return;
  copying.value=true;copyError.value="";
  try {const result=await api('/admin/apps/'+encodeURIComponent(copySource.value)+'/copy','POST',copyApplicationBody(copyForm.value));copySource.value="";go('/apps/'+encodeURIComponent(result.code));}
  catch(e:any){copyError.value=e.status===403?'当前账号没有复制应用的权限':e.message;}
  finally{copying.value=false;}
}
const schemaForm = ref<InstanceType<typeof SchemaInputs>>();
const publishedInput = ref<any>();
const runDeclaration = computed(
  () => publishedInput.value?.inputSchema ?? publishedInput.value?.inputs,
);
async function loadPublishedInput() {
  publishedInput.value = undefined;
  if (!auth.token || tab.value !== "run" || !form.value.versionNo) return;
  const requestedCode = code.value;
  const requestedVersion = form.value.versionNo;
  try {
    const result = await api(
      "/v1/apps/" +
        encodeURIComponent(code.value) +
        "/input-schema?versionNo=" +
        form.value.versionNo,
    );
    // A slow response from a previously selected version must never replace the active contract.
    if (
      code.value === requestedCode &&
      form.value.versionNo === requestedVersion
    )
      publishedInput.value = result;
  } catch (e) {
    failure(e);
  }
}
const FlowDesigner = defineAsyncComponent(
  () => import("../../flow-designer/FlowDesigner.vue"),
);
const DebugPanel = defineAsyncComponent(
  () => import("../../debug-panel/DebugPanel.vue"),
);
const auth = useAuth();
const api = useApi();
const path = ref(location.hash.slice(1) || "/apps");
const busy = ref(false);
const error = ref("");
const trace = ref("");
const items = ref<any[]>([]);
const app = ref<any>();
const flow = ref<any>();
const detail = ref<any>();
const versions = ref<any[]>([]);
const dialog = ref("");
const form = ref<any>({});
watch(
  () => form.value.versionNo,
  () => void loadPublishedInput(),
);
const providerOptions = ref<any[]>([]);
const catalogs = ref({
  models: [] as any[],
  skills: [] as any[],
  templates: [] as any[],
});
const json = ref("{}");
const config = ref("{}");
const filter = ref({
  keyword: "",
  code: "",
  domain: "",
  bizCategory: "",
  tags: "",
  status: "",
});
const page = ref(1);
const precheck = ref<any>();
const published = ref<any>();
const evaluation = ref<any>();
const evaluationNeedsCheck = ref(false);
const evaluationWorking = ref(false);
function evaluationActivity(active:boolean){evaluationWorking.value=active;if(active)evaluationNeedsCheck.value=true;}
const requestNo = ref("");
const chartHost = ref<HTMLElement>();
let chart: EChartsType;
let chartObserver: ResizeObserver;
const density = ref(localStorage.getItem("density") || "标准");
const code = computed(() =>
  path.value.startsWith("/apps/")
    ? decodeURIComponent(path.value.split("/")[2])
    : "",
);
const tab = computed(
  () => path.value.split("/").slice(3).join("/") || "overview",
);
const menus = [
  ["智能体管理", "/apps"],
  ["模型供应商", "/model/providers"],
  ["模型条目", "/model/entries"],
  ["提示词模板", "/model/prompt-templates"],
  ["技能管理", "/skills"],
  ["运行监控", "/runs"],
  ["确认中心", "/approvals"],
  ["评测中心", "/evaluations"],
  ["统计分析", "/statistics"],
  ["审计", "/audit"],
  ["系统管理", "/system"],
];
const dialogOpen = computed({
  get: () => !!dialog.value,
  set: (v: boolean) => {
    if (!v) dialog.value = "";
  },
});
const titles: Record<string, string> = {
  "/apps": "智能体管理",
  "/model/providers": "模型供应商",
  "/model/entries": "模型条目",
  "/model/prompt-templates": "提示词模板",
  "/skills": "技能库",
  "/runs": "运行监控",
  "/approvals": "确认中心",
  "/evaluations": "评测中心",
  "/statistics": "统计分析",
  "/audit": "审计记录",
  "/system": "系统管理",
};
const title = computed(() =>
  code.value
    ? app.value?.name || code.value
    : /^\/model\/providers\/[^/]+\/entries$/.test(path.value)
      ? "模型条目"
      : titles[path.value] || "详情",
);
const filtered = computed(() =>
  path.value === '/apps' ? items.value : items.value.filter((x) =>
    Object.entries(filter.value).every(
      ([k, v]) =>
        !v ||
        String(
          k === "keyword" ? x.name || x.appCode || x.runId || "" : x[k] || "",
        )
          .toLowerCase()
          .includes(v.toLowerCase()),
    ),
  ),
);
function displayTime(value: unknown) {
  if (!value) return "—";
  const date = new Date(String(value));
  return Number.isNaN(date.getTime())
    ? "—"
    : new Intl.DateTimeFormat("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
        timeZone: "Asia/Shanghai",
      }).format(date);
}
const paginated = computed(() =>
  filtered.value.slice((page.value - 1) * 12, page.value * 12),
);
function go(p: string) {
  location.hash = p;
}
function hash() {
  path.value = location.hash.slice(1) || "/apps";
}
function failure(e: any) {
  error.value = e.message;
  trace.value = e.traceId || "";
  if (e.status === 401) {
    auth.token = "";
  }
  if (e.status === 403) error.value = "当前账号没有此操作权限，请联系管理员。";
}
async function operation(fn: () => Promise<void>) {
  busy.value = true;
  error.value = "";
  try {
    await fn();
  } catch (e) {
    failure(e);
  } finally {
    busy.value = false;
  }
}
async function signIn() {
  await operation(async () => {
    auth.token = await login();
    await load();
  });
}
function endpoint() {
  if (/^\/model\/providers\/[^/]+\/entries$/.test(path.value))
    return "/admin/model-entries";
  return (
    {
      "/apps": apiRoutes.apps,
      "/skills": apiRoutes.skills,
      "/model/providers": apiRoutes.providers,
      "/model/entries": apiRoutes.entries,
      "/model/prompt-templates": apiRoutes.templates,
      "/runs": apiRoutes.runs,
      "/approvals": projectionRoute("approvals"),
      "/evaluations": apiRoutes.evaluations,
      "/audit": projectionRoute("audit"),
      "/system": "/admin/system",
    } as Record<string, string>
  )[path.value];
}
async function load() {
  if (!auth.token) return;
  if(path.value === '/statistics') { statisticsPanel.value?.refresh(); return; }
  await operation(async () => {
    page.value = 1;
    detail.value = null;
    items.value = [];
    precheck.value = null;
    published.value = null;
    evaluation.value = null;
    evaluationNeedsCheck.value = false;
    evaluationWorking.value = false;
    chartObserver?.disconnect();
    if (chart && !chart.isDisposed()) chart.dispose();
    if (code.value) {
      app.value = await api("/admin/apps/" + encodeURIComponent(code.value));
      refreshKey.value++;
      config.value = JSON.stringify(app.value.config || {}, null, 2);
      if (tab.value === "orchestration/flow") {
        flow.value = await api(
          "/admin/flows/" + encodeURIComponent(code.value),
        );
        const results = await Promise.allSettled(
          [
            "/admin/model-entries",
            "/admin/skills",
            "/admin/prompt-templates",
          ].map((p) => api<any[]>(p)),
        );
        results.forEach((r, i) => {
          const key = (["models", "skills", "templates"] as const)[i];
          catalogs.value[key] = r.status === "fulfilled" ? r.value : [];
          if (r.status === "rejected") failure(r.reason);
        });
      }
      if (["overview", "run"].includes(tab.value))
        versions.value = await api(
          "/admin/apps/" + encodeURIComponent(code.value) + "/versions",
        );
      if (tab.value === "run") {
        json.value = "{}";
        form.value = { versionNo: app.value.currentVersionNo };
        requestNo.value = crypto.randomUUID();
      }
      if (tab.value === "orchestration/publish") {
        json.value = "[]";
        form.value = { datasetVersion: "" };
      }
      if (tab.value === "runs")
        items.value = (
          await api<any[]>(
            "/admin/runs?appCode=" + encodeURIComponent(code.value),
          )
        ).filter(
          (r) =>
            r.appCode === code.value &&
            !["DEBUG", "EVALUATION"].includes(r.triggerType),
        );
      return;
    }
    if (path.value.startsWith("/runs/")) {
      detail.value = await api(
        "/admin/runs/" + encodeURIComponent(path.value.split("/")[2]),
      );
      return;
    }
    if (path.value.startsWith("/skills/") && path.value !== "/skills/new") {
      detail.value = await api(
        "/admin/skills/" + encodeURIComponent(path.value.split("/")[2]),
      );
      return;
    }
    const url = endpoint();
    if (url) {
      const requestedPath=path.value, requestedFilters=applicationQuery(filter.value);
      const data = await api(path.value === '/apps' ? url+'?'+requestedFilters : url);
      if(path.value!==requestedPath || (requestedPath==='/apps' && applicationQuery(filter.value)!==requestedFilters)) return;
      if (!Array.isArray(data) && !Array.isArray(data?.items))
        throw new Error("服务返回的列表格式不正确，请检查接口契约后重试");
      items.value = Array.isArray(data) ? data : data.items;
      if (path.value === "/runs")
        items.value = items.value.filter(
          (r) => !["DEBUG", "EVALUATION"].includes(r.triggerType),
        );
      if (/^\/model\/providers\/[^/]+\/entries$/.test(path.value))
        items.value = items.value.filter(
          (r) => String(r.providerId) === path.value.split("/")[3],
        );
      if (path.value === "/runs") await drawChart();
    }
  });
}
async function drawChart() {
  await nextTick();
  const module = await import("../../shared/chart");
  if (!chartHost.value) return;
  chart = module.init(chartHost.value);
  const counts = Object.entries(
    items.value.reduce((a: any, r: any) => {
      a[r.status] = (a[r.status] || 0) + 1;
      return a;
    }, {}),
  );
  chart.setOption({
    tooltip: { trigger: "axis", renderMode: "richText" },
    grid: { left: 50, right: 24, bottom: 24, top: 12 },
    xAxis: { type: "category", data: counts.map((x) => x[0]) },
    yAxis: { type: "value", minInterval: 1 },
    series: [
      {
        type: "bar",
        data: counts.map((x) => x[1]),
        itemStyle: {
          color: getComputedStyle(document.documentElement).getPropertyValue(
            "--brand-primary",
          ),
        },
        barMaxWidth: 36,
      },
    ],
  });
  chartObserver = new ResizeObserver(() => {
    if (chart && !chart.isDisposed()) chart.resize();
  });
  chartObserver.observe(chartHost.value);
}
function create(kind: string) {
  error.value = "";
  dialog.value = kind;
  if (kind === "app")
    form.value = {
      code: "",
      name: "",
      type: "CHAT",
      description: "",
      domain: "",
      bizCategory: "",
      tags: [],
    };
  if (kind === "skill")
    form.value = {
      code: "",
      name: "",
      type: "HTTP",
      description: "",
      endpoint: "",
      authRef: "",
      inputSchema: { type: "object" },
      outputSchema: { type: "object" },
      execution: {
        readonly: true,
        sideEffect: false,
        idempotent: true,
        cancellable: false,
      },
      guard: { roles: [], attributes: {} },
      timeoutMs: 30000,
    };
  if (kind === "provider")
    form.value = {
      code: "",
      name: "",
      protocol: "OPENAI",
      baseUrl: "",
      secretRef: "",
      trustLabels: [],
    };
  if (kind === "entry")
    void api<any[]>("/admin/model-providers")
      .then((rows) => (providerOptions.value = rows))
      .catch(failure);
  if (kind === "entry")
    form.value = {
      providerId: /^\/model\/providers\/[^/]+\/entries$/.test(path.value)
        ? Number(path.value.split("/")[3])
        : null,
      modelName: "",
      displayName: "",
      contextWindow: 8192,
      maxOutputTokens: 2048,
      capabilities: ["CHAT"],
    };
  if (kind === "template")
    form.value = { code: "", name: "", content: "", variables: [] };
  json.value = JSON.stringify(form.value, null, 2);
}
async function registerSkill(value: any) {
  json.value = JSON.stringify(value);
  await saveCreate();
}
async function saveCreate() {
  await operation(async () => {
    const body = ["app", "provider", "entry", "template"].includes(dialog.value)
      ? form.value
      : JSON.parse(json.value);
    if (dialog.value === "entry")
      for (const k of ["providerId", "contextWindow", "maxOutputTokens"]) {
        body[k] = Number(body[k]);
        if (!Number.isFinite(body[k]) || body[k] <= 0)
          throw new Error(k + " 必须是正数");
      }
    if (!body.code?.trim() && dialog.value !== "entry")
      throw new Error("请填写编码");
    if (dialog.value === "app" && !body.name?.trim())
      throw new Error("请填写应用名称");
    const url = (
      {
        app: "/admin/apps",
        skill: "/admin/skills",
        provider: "/admin/model-providers",
        entry: "/admin/model-entries",
        template: "/admin/prompt-templates",
      } as any
    )[dialog.value];
    await api(url, "POST", body);
    dialog.value = "";
    ElMessage.success("已保存");
    await load();
  });
}
async function saveConfig(value?: any) {
  await operation(async () => {
    const result = await api("/admin/apps/" + code.value + "/config", "PUT", {
      draftRev: app.value.draftRev,
      config: value || JSON.parse(config.value),
    });
    app.value = result;
    ElMessage.success("配置已保存");
  });
}
async function saveFlow(data: any) {
  await operation(async () => {
    const result = await api("/admin/flows/" + code.value, "PUT", {
      draftRev: app.value.draftRev,
      ...data,
    });
    flow.value = result;
    app.value.draftRev = result.draftRev;
    ElMessage.success("编排已保存并校验");
  });
}
async function check() {
  if(busy.value)return;
  const requestedPath=path.value, requestedCode=code.value, requestedRev=app.value?.draftRev;
  await operation(async () => {
    const result = await api(
      "/admin/apps/" + requestedCode + "/publish/precheck",
      "POST",
    );
    if(path.value!==requestedPath||code.value!==requestedCode||app.value?.draftRev!==requestedRev)return;
    if(evaluation.value&&(evaluation.value.candidateSha256!==result.candidateSha256||evaluation.value.draftRev!==result.draftRev))evaluation.value=null;
    precheck.value=result;evaluationNeedsCheck.value=false;
    requestNo.value = crypto.randomUUID();
  });
}
function onEvaluationReport(value:any) {
  if(value.appCode!==code.value||value.draftRev!==precheck.value?.draftRev||value.candidateSha256!==precheck.value?.candidateSha256)return;
  evaluation.value=value;evaluationNeedsCheck.value=true;
}
async function commit() {
  if (!confirm("确认将当前候选发布为正式版本？发布后可被业务系统调用。"))
    return;
  await operation(async () => {
    const p = precheck.value;
    if (!p?.precheckId) throw new Error("请先重新预检并通过评测");
    if(evaluationWorking.value||evaluationNeedsCheck.value||(evaluation.value&&(evaluation.value.status!=='ACCEPTED'||evaluation.value.candidateSha256!==p.candidateSha256||evaluation.value.draftRev!==p.draftRev)))throw Error('评测尚未完成或报告未重新绑定，请重新预检');
    published.value = await api(
      "/admin/apps/" + code.value + "/publish/commit",
      "POST",
      {
        precheckId: p.precheckId,
        draftRev: p.draftRev,
        candidateSha256: p.candidateSha256,
        idempotencyRequestNo: requestNo.value,
      },
    );
    ElMessage.success("发布成功");
  });
}
async function lifecycle(action: string) {
  const label = (
    { disable: "停用", enable: "启用", archive: "归档" } as Record<
      string,
      string
    >
  )[action];
  if (!confirm(`确认${label}应用？停用或归档后外部调用将不可用。`)) return;
  await operation(async () => {
    await api("/admin/apps/" + code.value + "/" + action, "POST", {});
    await load();
  });
}
async function saveMetadata() {
  await operation(async () => {
    app.value = await api("/admin/apps/" + code.value, "PUT", {
      draftRev: app.value.draftRev,
      name: app.value.name,
      description: app.value.description,
      domain: app.value.domain,
      bizCategory: app.value.bizCategory,
      tags: app.value.tags || [],
    });
    ElMessage.success("应用信息已保存");
  });
}
async function formalRun() {
  await operation(async () => {
    const result = await api("/admin/apps/" + code.value + "/runs", "POST", {
      inputs:
        runDeclaration.value !== undefined
          ? schemaForm.value!.collect()
          : JSON.parse(json.value),
      versionNo: form.value.versionNo || app.value.currentVersionNo,
      requestId: (requestNo.value ||= crypto.randomUUID()),
    });
    go("/runs/" + result.runId);
  });
}
async function testProvider(row: any) {
  await operation(async () => {
    detail.value = await api(
      "/admin/model-providers/" + row.id + "/test",
      "POST",
    );
    ElMessage.success("连接测试已返回");
  });
}
async function approval(row: any, action: string) {
  if (!row.snapshotDigest) {
    failure(
      new Error("待办缺少服务端参数摘要，请刷新后重试；未提交确认决定。"),
    );
    return;
  }
  if (
    !confirm(
      action === "APPROVE"
        ? "确认执行此待办？当前服务端快照：\n" + JSON.stringify(row, null, 2)
        : "确认拒绝此次执行？",
    )
  )
    return;
  await operation(async () => {
    await api(
      "/admin/approvals/" +
        row.runId +
        "/" +
        (row.nodeInstanceId || row.nodeId),
      "POST",
      {
        approved: action === "APPROVE",
        requestId: crypto.randomUUID(),
        snapshotDigest: row.snapshotDigest,
      },
    );
    await load();
  });
}
watch(path, () => void load());
watch(
  filter,
  () => {
    page.value = 1;
  },
  { deep: true },
);
let filterTimer: ReturnType<typeof setTimeout>;
watch(filter,()=>{if(path.value!=='/apps')return;clearTimeout(filterTimer);filterTimer=setTimeout(()=>void load(),300);},{deep:true});
watch(density, (d) => {
  localStorage.setItem("density", d);
  document.documentElement.style.fontSize = (
    { 紧凑: "15px", 标准: "16px", 宽松: "17px" } as any
  )[d];
});
onMounted(() => {
  window.addEventListener("hashchange", hash);
  if (auth.token) void load();
});
onBeforeUnmount(() => {
  clearTimeout(filterTimer);
  window.removeEventListener("hashchange", hash);
  if (chart && !chart.isDisposed()) chart.dispose();
  chartObserver?.disconnect();
});
</script>
<template>
  <div v-if="!auth.token" class="login">
    <section class="panel">
      <div class="muted">AGENT PLATFORM · V1.2</div>
      <h1 style="margin-top: 1rem">智能体构建工作台</h1>
      <p class="muted">
        从模型与技能出发，将业务流程编排为可调试、可评测、可发布的智能体。
      </p>
      <el-button type="primary" :loading="busy" @click="signIn"
        >进入开发工作台</el-button
      >
      <p class="muted">开发登录由网关签发身份票据，仅保存在当前页面内存。</p>
      <div v-if="error" class="error" role="alert">{{ error }}</div>
    </section>
  </div>
  <div
    v-else
    class="shell"
    :class="{ 'canvas-mode': code && tab === 'orchestration/flow', 'statistics-mode': path === '/statistics', 'test-input-mode': code && ['orchestration/debug','orchestration/publish'].includes(tab) }"
  >
    <aside class="nav">
      <div class="logo">◈ 智能体平台</div>
      <small>构建与管理</small
      ><template v-for="(menu, i) in menus" :key="menu[1]"
        ><small v-if="i === 5">运行与支撑</small
        ><a
          :href="'#' + menu[1]"
          :data-short="menu[0].slice(0, 1)"
          :aria-label="menu[0]"
          :title="menu[0]"
          :class="{
            active: path === menu[1] || (menu[1] === '/apps' && !!code),
          }"
          >{{ menu[0] }}</a
        ></template
      ><small style="margin-top: auto">独立环境 · v1.2</small>
    </aside>
    <main class="main">
      <header class="topbar">
        <span>工作台 / {{ title }}</span>
        <div class="actions">
          <span class="muted">显示密度</span
          ><el-select
            v-model="density"
            style="width: 6rem"
            aria-label="显示密度"
            ><el-option
              v-for="d in ['紧凑', '标准', '宽松']"
              :key="d"
              :value="d" /></el-select
          ><el-button text @click="auth.token = ''">退出</el-button>
        </div>
      </header>
      <div class="content">
        <div class="pagehead">
          <div>
            <div class="muted">{{ code ? "应用工作区" : "平台管理" }}</div>
            <h1>{{ title }}</h1>
            <span class="muted">{{
              code ? app?.description : "连接业务能力，持续验证每一次运行。"
            }}</span>
          </div>
          <el-button :loading="busy" @click="load">刷新</el-button>
        </div>
        <div v-if="error" class="error" role="alert">
          {{ error }}
          <p class="muted">
            请检查输入与服务状态后重试；业务数据以服务端为准。
          </p>
          <details v-if="trace">
            <summary>追踪信息</summary>
            {{ trace }}
          </details>
        </div>
        <StatisticsPanel v-if="path === '/statistics'" ref="statisticsPanel" />
        <template v-else-if="code && app"
          ><nav class="tabs">
            <a
              v-for="[label, t] in [
                ['概览', ''],
                [
                  '编排',
                  app.type === 'FLOW'
                    ? 'orchestration/flow'
                    : 'orchestration/chat',
                ],
                ['调试', 'orchestration/debug'],
                ['发布集成', 'orchestration/publish'],
                ['正式运行', 'run'],
                ['运行记录', 'runs'],
              ]"
              :key="t"
              :href="'#/apps/' + code + (t ? '/' + t : '')"
              :class="{ active: tab === (t || 'overview') }"
              >{{ label }}</a
            >
          </nav>
          <div v-if="tab === 'overview'">
            <div class="row">
              <span class="muted">应用信息与生命周期</span>
              <div class="actions">
                <el-button
                  v-if="
                    app.currentVersionNo &&
                    !['DISABLED', 'ARCHIVED'].includes(app.status)
                  "
                  v-permission="'edit'"
                  @click="lifecycle('disable')"
                  >停用应用</el-button
                >
                <el-button
                  v-if="app.status === 'DISABLED'"
                  v-permission="'edit'"
                  @click="lifecycle('enable')"
                  >启用应用</el-button
                >
                <el-button
                  v-if="app.status === 'DISABLED'"
                  type="danger"
                  plain
                  v-permission="'edit'"
                  @click="lifecycle('archive')"
                  >归档应用</el-button
                >
              </div>
            </div>
            <div class="grid">
              <div class="panel">
                <div class="muted">生命周期</div>
                <h2>
                  <span :class="['status', app.status]">{{ app.status }}</span>
                  <small v-if="app.currentVersionNo && app.status === 'DRAFT'"
                    >已发布 v{{ app.currentVersionNo }}，新草稿待发布</small
                  >
                </h2>
              </div>
              <div class="panel">
                <div class="muted">草稿修订</div>
                <h2>{{ app.draftRev }}</h2>
              </div>
              <div class="panel">
                <div class="muted">当前发布版本</div>
                <h2>{{ app.currentVersionNo || "尚未发布" }}</h2>
              </div>
            </div>
            <div class="panel">
              <el-collapse
                ><el-collapse-item title="编辑应用名称与分类">
                  <el-form label-position="left" label-width="6rem">
                    <el-form-item label="名称"
                      ><el-input v-model="app.name"
                    /></el-form-item>
                    <el-form-item label="说明"
                      ><el-input v-model="app.description" type="textarea"
                    /></el-form-item>
                    <el-form-item label="专业领域"
                      ><el-input v-model="app.domain"
                    /></el-form-item>
                    <el-form-item label="业务分类"
                      ><el-input v-model="app.bizCategory"
                    /></el-form-item>
                    <el-form-item label="标签"
                      ><el-select
                        v-model="app.tags"
                        multiple
                        filterable
                        allow-create
                        ><el-option
                          v-for="t in app.tags || []"
                          :key="t"
                          :value="t" /></el-select
                    ></el-form-item> </el-form
                  ><el-button
                    type="primary"
                    :loading="busy"
                    v-permission="'edit'"
                    @click="saveMetadata"
                    >保存应用信息</el-button
                  >
                </el-collapse-item></el-collapse
              >
              <h2>版本记录</h2>
              <el-table :data="versions"
                ><el-table-column
                  prop="versionNo"
                  label="版本"
                /><el-table-column
                  prop="candidateSha256"
                  label="候选摘要"
                /><el-table-column prop="createdAt" label="发布时间" /><template
                  #empty
                  >尚无发布版本，请先完成配置、调试与评测。</template
                ></el-table
              >
            </div>
            <ApplicationHistory :app-code="code" :versions="versions" :revision="refreshKey" />
          </div>
          <ChatConfig
            v-else-if="tab === 'orchestration/chat'"
            :config="app.config"
            :draft-key="code + ':chat'"
            :draft-rev="app.draftRev"
            @save="saveConfig"
          /><FlowDesigner
            v-else-if="tab === 'orchestration/flow' && flow?.definition"
            :read-only="!auth.can('edit')"
            :definition="flow.definition"
            :catalogs="catalogs"
            :layout="flow.layout || {}"
            :draft-key="code + ':flow'"
            :draft-rev="app.draftRev"
            @save="saveFlow"
          />
          <DebugPanel
            v-else-if="tab === 'orchestration/debug'"
            :key="code"
            :app-code="code"
          />
          <RunDetail
            v-else-if="tab.startsWith('records/')"
            :run-id="tab.split('/')[1]"
          />
          <div v-else-if="tab === 'orchestration/publish'">
            <el-steps
              :active="published ? 3 : precheck ? 1 : 0"
              finish-status="success"
              style="margin-bottom: 1.5rem"
              ><el-step title="候选预检" /><el-step title="候选评测" /><el-step
                title="确认发布" /><el-step title="集成调用"
            /></el-steps>
            <div class="panel">
              <h2>1. 检查当前草稿</h2>
              <p class="muted">
                预检不会产生版本。配置发生变化时，请重新预检并评测。
              </p>
              <el-button
                :type="!precheck ? 'primary' : 'default'"
                :loading="busy"
                v-permission="'edit'"
                @click="check"
                >运行发布预检</el-button
              >
              <el-table
                v-if="precheck"
                :data="precheck.report?.checks || []"
                style="margin-top: 1rem"
                ><el-table-column prop="group" label="检查组" /><el-table-column
                  label="结果"
                  width="110"
                  ><template #default="{ row }"
                    ><span :class="['status', row.result]">{{
                      row.result
                    }}</span></template
                  ></el-table-column
                ><el-table-column
                  prop="detail"
                  label="问题与建议"
                  min-width="300"
              /></el-table>
              <el-collapse v-if="precheck"
                ><el-collapse-item title="候选摘要与预检标识">
                  <pre>{{
                    JSON.stringify(
                      {
                        precheckId: precheck.precheckId,
                        draftRev: precheck.draftRev,
                        candidateSha256: precheck.candidateSha256,
                        evalReportId: precheck.evalReportId,
                      },
                      null,
                      2,
                    )
                  }}</pre>
                </el-collapse-item></el-collapse
              >
            </div>
            <EvaluationPanel :key="code" :app-code="code" :draft-rev="app.draftRev" :candidate="precheck" @report="onEvaluationReport" @activity="evaluationActivity" @bind="check" />
            <div v-if="precheck" class="panel">
              <h2>3. 确认发布</h2>
              <p class="muted">
                服务端检查评测报告与候选摘要一致性。错误或过期报告将阻断发布。
              </p>
              <el-button
                :type="precheck.passed ? 'primary' : 'default'"
                :loading="busy"
                :disabled="!precheck.passed || evaluationWorking || evaluationNeedsCheck || (!!evaluation && evaluation.status!=='ACCEPTED')"
                v-permission="'edit'"
                @click="commit"
                >确认发布版本</el-button
              >
            </div>
            <div v-if="published" class="panel">
              <h2>集成四件套</h2>
              <pre>{{ JSON.stringify(published, null, 2) }}</pre>
              <p>
                对话地址：<a
                  :href="'http://127.0.0.1:15174/w/' + code"
                  target="_blank"
                  >打开业务对话</a
                >
              </p>
              <p class="muted">
                嵌入票据必须由业务系统后端签发，禁止在页面配置服务密钥。
              </p>
            </div>
          </div>
          <div v-else-if="tab === 'run'" class="panel form">
            <h2>执行发布版本</h2>
            <el-alert
              v-if="!app.currentVersionNo"
              title="此应用尚未发布，请先完成候选评测和发布。"
              type="info"
              :closable="false"
            /><el-form label-position="top"
              ><el-form-item label="运行版本"
                ><el-select v-model="form.versionNo" placeholder="默认当前版本"
                  ><el-option
                    v-for="v in versions"
                    :key="v.versionNo"
                    :value="v.versionNo"
                    :label="String(v.versionNo)" /></el-select></el-form-item
              ><SchemaInputs
                v-if="runDeclaration !== undefined"
                :key="form.versionNo"
                ref="schemaForm"
                :declaration="runDeclaration" /><el-form-item
                v-else
                label="高级输入 JSON（此版本未声明输入 Schema）"
                ><el-input
                  v-model="json"
                  type="textarea"
                  :rows="8" /></el-form-item></el-form
            ><el-button
              type="primary"
              :disabled="
                !app.currentVersionNo ||
                ['DISABLED', 'ARCHIVED'].includes(app.status) ||
                !publishedInput
              "
              :loading="busy"
              @click="formalRun"
              >提交运行</el-button
            >
          </div>
        </template>
        <RunDetail
          v-else-if="path.startsWith('/runs/')"
          :key="path"
          :run-id="path.split('/')[2]"
        /><template v-else-if="
            detail &&
            (path.startsWith('/skills/') || path === '/model/prompt-templates')
          "><ResourceDetail
          :resource="detail"
          :kind="path.startsWith('/skills/') ? 'skill' : 'template'"
        /><SkillStatistics v-if="path.startsWith('/skills/')" :code="detail.code" :versions="detail.versions" /></template><template v-else-if="detail"
          ><div class="panel">
            <h2>详情与回放</h2>
            <p class="muted">
              保留服务端版本链、节点摘要与守卫记录；无本体调用时不构造版本。
            </p>
            <pre>{{ JSON.stringify(detail, null, 2) }}</pre>
          </div></template
        >
        <template v-else
          ><div class="row">
            <div class="muted">共 {{ filtered.length }} 条记录</div>
            <div class="actions">
              <el-button
                v-if="path === '/apps'"
                type="primary"
                v-permission="'edit'"
                @click="create('app')"
                >创建智能体</el-button
              ><el-button
                v-if="path === '/skills'"
                type="primary"
                v-permission="'edit'"
                @click="create('skill')"
                >注册技能</el-button
              ><el-button
                v-if="path === '/model/providers'"
                type="primary"
                v-permission="'admin'"
                @click="create('provider')"
                >添加供应商</el-button
              ><el-button
                v-if="
                  path === '/model/entries' ||
                  /^\/model\/providers\/[^/]+\/entries$/.test(path)
                "
                type="primary"
                v-permission="'admin'"
                @click="create('entry')"
                >添加模型条目</el-button
              ><el-button
                v-if="path === '/model/prompt-templates'"
                type="primary"
                v-permission="'edit'"
                @click="create('template')"
                >创建提示词模板</el-button
              >
            </div>
          </div>
          <div v-if="path === '/runs'" class="panel">
            <h2>当前运行状态分布</h2>
            <div ref="chartHost" class="chart" />
          </div>
          <div class="panel">
            <div class="filters">
              <el-input
                v-model="filter.keyword"
                clearable
                placeholder="搜索名称或运行编码"
                @input="page = 1"
              /><template v-if="path === '/apps'"
                ><el-input
                  v-model="filter.code"
                  clearable
                  placeholder="应用编码" /><el-input
                  v-model="filter.domain"
                  clearable
                  placeholder="专业领域" /><el-input
                  v-model="filter.bizCategory"
                  clearable
                  placeholder="业务分类" /><el-input
                  v-model="filter.tags"
                  clearable
                  placeholder="标签" /></template
              ><el-select v-if="path === '/apps'" v-model="filter.status" clearable placeholder="状态（默认不含归档）" aria-label="应用状态"><el-option v-for="state in ['DRAFT','PUBLISHED','DISABLED','ARCHIVED']" :key="state" :value="state" :label="state" /></el-select><el-input v-else v-model="filter.status" clearable placeholder="状态" />
            </div>
            <el-skeleton v-if="busy" :rows="5" animated /><el-table
              v-else
              :data="paginated"
              stripe
              ><el-table-column
                v-if="path === '/approvals'"
                prop="runId"
                label="运行编码"
                min-width="300" /><el-table-column
                v-if="path === '/approvals'"
                prop="nodeDefId"
                label="待确认节点"
                min-width="130" /><el-table-column
                label="名称 / 编码"
                min-width="200"
                ><template #default="{ row }"
                  ><a
                    v-if="path === '/apps'"
                    :href="'#/apps/' + encodeURIComponent(row.code)"
                    ><strong>{{ row.name }}</strong>
                    <div class="muted">{{ row.code }}</div></a
                  ><a
                    v-else-if="path === '/skills'"
                    :href="'#/skills/' + encodeURIComponent(row.code)"
                    >{{ row.name || row.code }}</a
                  ><a
                    v-else-if="path === '/runs'"
                    :href="'#/runs/' + encodeURIComponent(row.runId || row.id)"
                    >{{ row.runId || row.id }}</a
                  ><span v-else>{{
                    row.name ||
                    row.displayName ||
                    row.code ||
                    row.appCode ||
                    row.id
                  }}</span></template
                ></el-table-column
              ><el-table-column label="状态" min-width="120"
                ><template #default="{ row }"
                  ><span :class="['status', row.status]">{{
                    row.status || "—"
                  }}</span></template
                ></el-table-column
              ><el-table-column prop="type" label="类型" /><el-table-column
                v-if="path === '/apps'"
                prop="domain"
                label="专业领域" /><el-table-column
                v-if="path === '/apps'"
                prop="bizCategory"
                label="业务分类" /><el-table-column
                prop="description"
                label="说明"
                min-width="180"
                show-overflow-tooltip /><el-table-column
                prop="updatedAt"
                label="更新于"
                min-width="170"
                ><template #default="{ row }">{{
                  displayTime(row.updatedAt || row.startedAt || row.createdAt)
                }}</template></el-table-column
              ><el-table-column label="操作" fixed="right" min-width="160"
                ><template #default="{ row }"
                  ><el-button
                    v-if="path === '/model/providers'"
                    text
                    @click="go('/model/providers/' + row.id + '/entries')"
                    >模型条目</el-button
                  ><el-button
                    v-if="path === '/apps'"
                    text
                    @click="go('/apps/' + row.code + '/run')"
                    >运行</el-button
                  ><el-button v-if="path === '/apps'" v-permission="'edit'" text @click="startCopy(row)">复制</el-button
                  ><el-button
                    v-if="path === '/model/providers'"
                    text
                    v-permission="'admin'"
                    @click="testProvider(row)"
                    >测试连接</el-button
                  ><el-button
                    v-if="path === '/approvals'"
                    text
                    v-permission="'approve'"
                    @click="approval(row, 'APPROVE')"
                    >确认</el-button
                  ><el-button
                    v-if="path === '/approvals'"
                    text
                    type="danger"
                    v-permission="'approve'"
                    @click="approval(row, 'REJECT')"
                    >拒绝</el-button
                  ><el-button text @click="detail = row"
                    >查看详情</el-button
                  ></template
                ></el-table-column
              ><template #empty
                ><el-empty
                  :description="
                    error
                      ? '服务暂不可用，请刷新重试'
                      : path === '/apps'
                        ? Object.values(filter).some(value=>value.trim()) ? '没有匹配的应用，请调整筛选' : '还没有智能体，创建第一个'
                        : '暂无记录；完成相关配置或运行后刷新查看。'
                  " /></template></el-table
            ><el-pagination
              v-model:current-page="page"
              :page-size="12"
              :total="filtered.length"
              layout="prev,pager,next,total"
              style="margin-top: 1rem"
            /></div
        ></template>
        <div v-if="code && tab === 'runs'" class="panel">
          <el-table :data="items"
            ><el-table-column label="运行编码"
              ><template #default="{ row }"
                ><a :href="'#/runs/' + row.runId">{{ row.runId }}</a></template
              ></el-table-column
            ><el-table-column prop="status" label="状态"
          /></el-table>
        </div>
      </div>
    </main>
  </div>
  <el-dialog :model-value="!!copySource" class="application-copy-dialog" title="复制为新草稿" width="min(480px, 94vw)" @update:model-value="closeCopy" :close-on-click-modal="!copying" :show-close="!copying">
    <p class="muted">复制 {{copySource}} 的当前草稿配置，创建独立的新应用。</p><div v-if="copyError" class="error" role="alert">{{copyError}}</div><el-form label-position="top" @submit.prevent="copyApplication"><el-form-item label="新应用编码" required><el-input v-model="copyForm.code" placeholder="小写字母、数字与下划线"/></el-form-item><el-form-item label="新应用名称" required><el-input v-model="copyForm.name" maxlength="100"/></el-form-item></el-form><template #footer><el-button :disabled="copying" @click="copySource=''">取消</el-button><el-button type="primary" :loading="copying" @click="copyApplication">创建新草稿</el-button></template>
  </el-dialog>
  <el-dialog
    v-model="dialogOpen"
    :title="
      (
        {
          app: '创建智能体',
          skill: '注册技能',
          provider: '添加供应商',
          entry: '添加模型条目',
          template: '创建提示词模板',
        } as any
      )[dialog]
    "
    destroy-on-close
    ><div v-if="error" class="error" role="alert">{{ error }}</div>
    <SkillWizard
      v-if="dialog === 'skill'"
      :initial="form"
      @save="registerSkill"
      @open-imported="dialog='';go('/skills/'+encodeURIComponent($event))"
    /><el-form v-else label-position="top"
      ><template v-if="dialog === 'app'"
        ><el-form-item label="应用类型"
          ><el-radio-group v-model="form.type"
            ><el-radio-button value="CHAT">对话智能体</el-radio-button
            ><el-radio-button value="FLOW"
              >流程智能体</el-radio-button
            ></el-radio-group
          ></el-form-item
        >
        <p class="muted">
          对话型面向问答与工具调用；流程型通过画布确定步骤与分支。
        </p>
        <el-form-item label="应用名称" required
          ><el-input v-model="form.name" maxlength="100" /></el-form-item
        ><el-form-item label="唯一编码" required
          ><el-input
            v-model="form.code"
            placeholder="小写字母、数字与下划线" /></el-form-item
        ><el-form-item label="应用说明"
          ><el-input v-model="form.description" type="textarea" /></el-form-item
        ><el-form-item label="专业领域"
          ><el-input v-model="form.domain" /></el-form-item
        ><el-form-item label="业务分类"
          ><el-input v-model="form.bizCategory" /></el-form-item></template
      ><template v-else-if="dialog === 'provider'"
        ><el-form-item
          v-for="k in ['code', 'name', 'protocol', 'baseUrl', 'secretRef']"
          :key="k"
          :label="
            (
              {
                code: '编码',
                name: '名称',
                protocol: '协议',
                baseUrl: '服务地址',
                secretRef: '凭据引用（仅引用，不填密钥）',
              } as any
            )[k]
          "
          :required="k !== 'secretRef'"
          ><el-input v-model="form[k]"
        /></el-form-item>
        <el-form-item v-if="auth.can('admin')" label="端点信任标签" required
          ><el-select
            v-model="form.trustLabels"
            multiple
            filterable
            allow-create
            placeholder="由管理员确认端点允许处理的数据分类"
            ><el-option
              v-for="label in ['LOCAL', 'INTERNAL', 'PUBLIC']"
              :key="label"
              :value="label" /></el-select
        ></el-form-item>
        <p class="muted">
          缺少数据分类时服务端仅允许具备 LOCAL
          信任标签的端点；请按真实部署声明。
        </p></template
      ><template v-else-if="dialog === 'entry'"
        ><el-form-item label="所属供应商" required
          ><el-select
            v-model="form.providerId"
            filterable
            placeholder="选择已登记的供应商"
            ><el-option
              v-for="p in providerOptions"
              :key="p.id"
              :value="p.id"
              :label="p.name" /></el-select
        ></el-form-item>
        <el-form-item label="模型调用名称" required
          ><el-input
            v-model="form.modelName"
            placeholder="供应商接口使用的模型名称" /></el-form-item
        ><el-form-item label="显示名称"
          ><el-input v-model="form.displayName" /></el-form-item
        ><el-form-item label="上下文窗口"
          ><el-input-number
            v-model="form.contextWindow"
            :min="1" /></el-form-item
        ><el-form-item label="最大输出 Token"
          ><el-input-number
            v-model="form.maxOutputTokens"
            :min="1" /></el-form-item></template
      ><template v-else-if="dialog === 'template'"
        ><el-form-item label="模板编码"
          ><el-input v-model="form.code" /></el-form-item
        ><el-form-item label="模板名称"
          ><el-input v-model="form.name" /></el-form-item
        ><el-form-item label="模板内容"
          ><el-input v-model="form.content" type="textarea" :rows="10"
        /></el-form-item>
        <el-form-item label="模板变量"
          ><div style="width: 100%">
            <div v-for="(v, i) in form.variables" :key="i" class="row">
              <el-input v-model="v.name" placeholder="变量名称" /><el-select
                v-model="v.type"
                ><el-option
                  v-for="t in [
                    'string',
                    'integer',
                    'number',
                    'boolean',
                    'object',
                    'array',
                  ]"
                  :key="t"
                  :value="t" /></el-select
              ><el-checkbox v-model="v.required">必填</el-checkbox
              ><el-button text @click="form.variables.splice(i, 1)"
                >移除</el-button
              >
            </div>
            <el-button
              @click="
                form.variables.push({
                  name: '',
                  type: 'string',
                  required: true,
                })
              "
              >添加变量</el-button
            >
          </div></el-form-item
        ></template
      ><template v-else
        ><el-alert
          title="请声明真实端点、输入输出 Schema、执行属性与权限守卫；凭据只允许引用。"
          :closable="false" /><el-input
          v-model="json"
          type="textarea"
          :rows="20" /></template></el-form
    ><template v-if="dialog !== 'skill'" #footer
      ><el-button @click="dialog = ''">取消</el-button
      ><el-button type="primary" :loading="busy" @click="saveCreate"
        >保存</el-button
      ></template
    ></el-dialog
  >
</template>
<style scoped>
:global(.application-copy-dialog){width:min(480px,94vw)!important}
@media(max-width:900px){.statistics-mode{min-width:0}.statistics-mode .topbar{height:auto;min-height:3.5rem;flex-wrap:wrap;gap:.5rem;padding:.75rem 1rem}.statistics-mode .topbar .actions{flex-wrap:wrap}}
@media(max-width:900px){.test-input-mode{min-width:0}.test-input-mode .topbar{height:auto;min-height:3.5rem;flex-wrap:wrap;gap:.5rem;padding:.75rem 1rem}.test-input-mode .topbar .actions{flex-wrap:wrap}.test-input-mode .tabs{flex-wrap:wrap;gap:.75rem}.test-input-mode .pagehead{align-items:flex-start}.test-input-mode .pagehead h1{overflow-wrap:anywhere}}
</style>
