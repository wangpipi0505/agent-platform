<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import PageHeader from "../PageHeader.vue";
import PageError from "../PageError.vue";
import { usePageOperation, usePageTitle } from "../usePageOperation";
import {
  canReadCapabilityPackages, declarationOptions, ontologyLink, parseJsonObject,
  useCapabilityPackageClient, type CapabilityPackageDetail, type CapabilityPackageSummary,
} from "../../../shared/capability-package";

usePageTitle(() => "软件能力目录");
const route = useRoute(), router = useRouter();
const { auth, busy, error, trace, operation, success } = usePageOperation();
const client = useCapabilityPackageClient();
const rows = ref<CapabilityPackageSummary[]>([]), detail = ref<CapabilityPackageDetail>();
const query = ref(""), nextBeforeId = ref<number>(), declarationText = ref(""), registerOpen = ref(false);
const environment = ref(""), routeProfileId = ref(""), bindingsText = ref("[]"), applicationsText = ref("");
const canRead = computed(() => !!auth.token && canReadCapabilityPackages(auth.roles));
const options = computed(() => declarationOptions(detail.value?.declaration || {}));
const ontologyUrl = computed(() => ontologyLink(String(import.meta.env.VITE_ONTOLOGY_CONSOLE_URL || ""), detail.value?.declaration.ontology));
const reference = computed(() => ({
  ontologySpace: typeof route.query.ontologySpace === "string" ? route.query.ontologySpace : "",
  ontologyRelease: typeof route.query.ontologyRelease === "string" ? route.query.ontologyRelease : "",
}));

async function readList(append = false) {
  const page = await client.list({ q: query.value.trim(), ...reference.value, beforeId: append ? nextBeforeId.value : undefined });
  rows.value = append ? [...rows.value, ...page.items] : page.items;
  nextBeforeId.value = page.nextBeforeId;
}
async function readDetail(id: number) {
  detail.value = undefined;
  const result = await client.detail(id);
  detail.value = result;
  environment.value = result.resolvedMapping.environment || "";
  routeProfileId.value = result.resolvedMapping.routeProfileId || "";
  bindingsText.value = "[]"; applicationsText.value = "";
}
async function load() {
  if (!canRead.value || busy.value) return;
  await operation(async () => {
    await readList();
    const id = Number(route.query.packageVersionId);
    if (Number.isSafeInteger(id) && id > 0) await readDetail(id);
  });
}
async function select(id: number) {
  if (busy.value) return;
  await operation(async () => {
    await readDetail(id);
    await router.replace({ query: { ...route.query, packageVersionId: String(id) } });
  });
}
async function register() {
  if (!auth.can("edit") || busy.value) return;
  await operation(async () => {
    const result = await client.register(parseJsonObject(declarationText.value));
    registerOpen.value = false; declarationText.value = "";
    await readList(); await readDetail(result.packageVersionId);
    await router.replace({ query: { ...route.query, packageVersionId: String(result.packageVersionId) } });
    success("声明已登记；绑定、草稿和验证状态请查看目录详情");
  });
}
async function resolve() {
  const selected = detail.value;
  if (!selected || !auth.can("edit") || busy.value) return;
  await operation(async () => {
    if (!environment.value || !routeProfileId.value) throw new Error("请明确选择声明中的环境与路线");
    let bindings: unknown;
    try { bindings = JSON.parse(bindingsText.value); } catch { throw new Error("技能绑定必须是有效 JSON 数组"); }
    if (!Array.isArray(bindings)) throw new Error("技能绑定必须是 JSON 数组");
    await client.resolve(selected.packageVersionId, { environment: environment.value, routeProfileId: routeProfileId.value, bindings });
    await readDetail(selected.packageVersionId); await readList();
    success("选定路线的精确技能绑定已解析；尚未评测");
  });
}
async function createApplications() {
  const selected = detail.value;
  if (!selected || !auth.can("edit") || busy.value) return;
  await operation(async () => {
    await client.createApplications(selected.packageVersionId, parseJsonObject(applicationsText.value));
    await readDetail(selected.packageVersionId); await readList();
    success("应用草稿计划已保存；请进入应用继续配置与评测");
  });
}
watch(() => [route.query.ontologySpace, route.query.ontologyRelease], () => { detail.value = undefined; void load(); });
onMounted(load);
</script>

<template>
  <div class="capability-directory">
    <PageHeader title="软件能力目录" subtitle="按软件与能力包查看原声明、精确技能绑定和来源应用。" :busy="busy" @refresh="load">
      <template #actions><el-button v-if="auth.can('edit')" type="primary" :disabled="busy" @click="registerOpen = true">登记能力包</el-button></template>
    </PageHeader>
    <PageError :error="error" :trace="trace" />
    <el-empty v-if="!canRead" description="当前账号没有能力目录查看权限，请联系管理员。" />
    <template v-else>
      <p class="muted">声明、绑定与应用状态分别展示；NOT_EVALUATED 表示尚未取得验证结论。</p>
      <p v-if="reference.ontologySpace || reference.ontologyRelease" class="context" role="status">
        原声明引用筛选：空间 {{ reference.ontologySpace || '未指定' }} · 发布 {{ reference.ontologyRelease || '未指定' }}。本体权限与版本由本体工作台校验。
        <RouterLink :to="{ path: '/capability-packages', query: {} }">查看全部</RouterLink>
      </p>
      <form class="search" @submit.prevent="load">
        <el-input v-model="query" aria-label="检索软件或能力包" placeholder="软件标识或能力包标识" maxlength="128" clearable :disabled="busy" />
        <el-button native-type="submit" :disabled="busy">检索</el-button>
      </form>
      <div class="directory-grid">
        <section class="panel" aria-label="能力包版本列表">
          <el-skeleton v-if="busy && !rows.length" :rows="5" animated />
          <el-empty v-else-if="!rows.length" :description="error ? '目录读取失败，请刷新重试' : '没有匹配的能力包；编辑者可登记完整声明。'" />
          <article v-for="row in rows" :key="row.packageVersionId" class="package-card" :class="{ selected: detail?.packageVersionId === row.packageVersionId }">
            <h2>{{ row.softwareId }} <small>{{ row.softwareVersion || '软件版本未声明' }}</small></h2>
            <p>{{ row.packageId }} · {{ row.version }}</p>
            <p class="muted">{{ row.bindingStatus === 'RESOLVED' ? '选定路线已解析' : '声明已登记，待解析' }} · 来源应用 {{ row.applicationCount }}</p>
            <el-button :disabled="busy" @click="select(row.packageVersionId)">查看版本与下一步</el-button>
          </article>
          <el-button v-if="nextBeforeId" class="more" :disabled="busy" @click="operation(() => readList(true))">加载更多</el-button>
        </section>
        <section v-if="detail" class="panel detail" aria-label="能力包详情">
          <h2>{{ detail.packageId }} · {{ detail.version }}</h2>
          <p>软件：{{ detail.softwareId }} · {{ detail.softwareVersion || '版本未声明' }}</p>
          <p>绑定：{{ detail.bindingStatus }} · 验证：{{ detail.verificationStatus }}</p>
          <p class="muted">登记时间 {{ detail.createdAt }}</p>
          <details><summary>声明摘要与完整内容</summary><code>{{ detail.sha256 }}</code><pre>{{ JSON.stringify(detail.declaration, null, 2) }}</pre></details>
          <p v-if="ontologyUrl"><a :href="ontologyUrl" rel="noopener noreferrer" target="_blank">打开声明的本体空间与发布入口</a><br><small class="muted">由本体工作台确认访问权限和发布是否存在。</small></p>
          <p v-else-if="detail.declaration.ontology" class="muted">本体声明保留在完整内容中；尚未配置可用的本体工作台入口。</p>

          <section v-if="detail.bindingStatus === 'UNRESOLVED' && auth.can('edit')" class="next-step">
            <h3>解析精确技能绑定</h3>
            <el-form label-position="top" @submit.prevent="resolve">
              <el-form-item label="声明环境"><el-select v-model="environment" placeholder="明确选择环境" :disabled="busy"><el-option v-for="item in options.environments" :key="item" :value="item" /></el-select></el-form-item>
              <el-form-item label="路线配置"><el-select v-model="routeProfileId" placeholder="明确选择路线" :disabled="busy"><el-option v-for="item in options.routes" :key="item" :value="item" /></el-select></el-form-item>
              <el-form-item label="精确技能绑定（JSON 数组）"><el-input v-model="bindingsText" type="textarea" :rows="5" :disabled="busy" aria-label="精确技能绑定 JSON" /></el-form-item>
              <p class="muted">使用 toolId 与 skillRef.code/version；空数组使用原声明中的精确 skillRef。仅解析所选路线，不执行工具。</p>
              <el-button type="primary" :disabled="busy" @click="resolve">解析绑定</el-button>
            </el-form>
          </section>
          <section v-if="detail.bindingStatus === 'RESOLVED'">
            <h3>已解析的技能来源</h3>
            <p>{{ detail.resolvedMapping.environment }} · {{ detail.resolvedMapping.routeProfileId }}</p>
            <ul class="sources"><li v-for="binding in detail.resolvedMapping.bindings || []" :key="binding.toolId">
              {{ binding.toolId }} → <RouterLink :to="'/skills/' + encodeURIComponent(binding.skillCode)">{{ binding.skillCode }}</RouterLink> · 精确版本 {{ binding.version }}
              <details><summary>来源标识与摘要</summary><p>接口 {{ binding.interfaceId }} · 技能版本 ID {{ binding.skillVersionId }}</p><code>{{ binding.sha256 }}</code></details>
            </li></ul>
            <details v-if="auth.can('edit')" class="next-step"><summary>提供完整计划并创建应用草稿</summary>
              <p class="muted">提交 applications 对象数组；每项提供 appCode、name、type、routeProfileId、config。CHAT 使用真实模型条目；FLOW 提供完整图。系统不猜配置。</p>
              <el-input v-model="applicationsText" type="textarea" :rows="9" aria-label="完整应用草稿计划 JSON" placeholder="输入完整 applications 计划对象" :disabled="busy" />
              <p class="muted">首次计划固定；相同计划重试读取原草稿，不覆盖后续编辑。草稿仍需评测与发布。</p>
              <el-button type="primary" :disabled="busy" @click="createApplications">创建或读取应用草稿</el-button>
            </details>
          </section>
          <h3>来源应用（当前记录）</h3>
          <p v-if="!detail.applications.length" class="muted">当前没有关联应用；声明登记与绑定解析均不会自动生成应用。</p>
          <ul v-else class="sources"><li v-for="app in detail.applications" :key="app.appId"><RouterLink :to="'/apps/' + encodeURIComponent(app.appCode) + '/overview'">{{ app.name }} · {{ app.appCode }}</RouterLink><p>{{ app.type }} · {{ app.status }} · 草稿修订 {{ app.draftRev ?? '未记录' }} · 发布版本 {{ app.currentVersionNo ?? '无' }}</p></li></ul>
          <p v-if="detail.applicationsTruncated" class="muted">本页显示前 100 个来源应用，其余请在智能体管理中查询。</p>
        </section>
        <section v-else class="panel muted">选择一个能力包版本，查看原声明、技能来源与应用草稿。</section>
      </div>
    </template>
    <el-dialog v-model="registerOpen" class="capability-register-dialog" title="登记能力包声明" width="min(760px, calc(100vw - 2rem))" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
      <p>粘贴完整 CapabilityPackage 声明。登记仅保存声明，不代表绑定、运行或发布成功。</p>
      <PageError :error="error" :trace="trace" />
      <el-input v-model="declarationText" type="textarea" :rows="15" aria-label="完整能力包声明 JSON" :disabled="busy" />
      <template #footer><el-button :disabled="busy" @click="registerOpen = false">取消</el-button><el-button type="primary" :loading="busy" @click="register">登记声明</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.capability-directory { min-width: 0; }
:global(.capability-register-dialog.el-dialog) { width: min(760px, calc(100vw - 2rem)) !important; }
.search { display: flex; gap: .5rem; margin: 1rem 0; max-width: 42rem; }
.directory-grid { display: grid; grid-template-columns: minmax(15rem, .85fr) minmax(0, 1.5fr); gap: 1rem; align-items: start; }
.directory-grid > .panel { min-width: 0; }
.package-card { padding: 1rem 0; border-bottom: 1px solid var(--el-border-color); overflow-wrap: anywhere; }
.package-card:first-child { padding-top: 0; }
.package-card.selected { border-left: 3px solid var(--el-color-primary); padding-left: .7rem; }
h2 { font-size: 1.1rem; margin: 0 0 .5rem; }
h2 small { display: block; font-weight: 400; color: var(--el-text-color-secondary); margin-top: .35rem; }
.detail, .context, .sources { overflow-wrap: anywhere; }
.detail details, .next-step { margin: 1rem 0; }
summary { cursor: pointer; }
pre { max-height: 24rem; overflow: auto; white-space: pre-wrap; word-break: break-word; background: var(--el-fill-color-light); padding: .75rem; }
code { word-break: break-all; }
.sources { padding-left: 1.25rem; }
.sources li { margin: .75rem 0; }
.more { margin-top: 1rem; }
.context { padding: .75rem; background: var(--el-fill-color-light); }
.detail :deep(.el-select) { width: 100%; }
@media (max-width: 900px) { .directory-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 480px) { .capability-directory :deep(.pagehead), .capability-directory :deep(.pagehead .actions) { flex-wrap: wrap; gap: .5rem; } }
</style>
