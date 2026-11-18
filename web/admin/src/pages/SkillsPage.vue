<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import BusinessCategorySelect from "../BusinessCategorySelect.vue";
import { businessCategories } from "../../../shared/skill-catalog";
import PageHeader from "../PageHeader.vue";
import SkillWizard from "../SkillWizard.vue";
import DataPage from "../../../shared/components/DataPage.vue";
import EmptyState from "../../../shared/components/EmptyState.vue";
import ErrorBanner from "../../../shared/components/ErrorBanner.vue";
import { usePageTitle } from "../usePageOperation";
import ResourceDetailPanel from "./resources/ResourceDetailPanel.vue";
import { displayTime, statusLabel } from "./resources/resource-format";
import { useResourceList } from "./resources/useResourceList";
import {
  matchesKindFilter,
  skillKindOf,
  SKILL_KIND_FILTERS,
  type SkillKind,
} from "../../../shared/skill-precheck";

const router = useRouter();
usePageTitle(() => "技能库");
const {
  api,
  busy,
  error,
  trace,
  operation,
  success,
  filter,
  page,
  pageSize,
  filtered: baseFiltered,
  load,
} = useResourceList("skills");

// F1-3：分页收敛到 20/50/100 单一规格（方案 §4）；本页为四段高度模型样板页②。
pageSize.value = 20;

const businessCategory = ref("");
// F2-2（方案 §5.5）：kind（TOOL/GUIDE）chips 筛选——客户端过滤既有列表数据，
// 分型判定与过滤语义统一走 shared/skill-precheck 助手（GUIDE=kind==='GUIDE'，其余皆 TOOL；M5-2 自 mock 迁出为纯投影模块）。
const kindFilter = ref<SkillKind | "">("");
const filtered = computed(
  () =>
    baseFiltered.value.filter(
      (row) =>
        matchesKindFilter(row, kindFilter.value) &&
        (!businessCategory.value ||
          (row.bizCategories || ["GENERAL"]).includes(businessCategory.value)),
    ),
);
const kindCount = (kind: SkillKind) =>
  baseFiltered.value.filter((row) => matchesKindFilter(row, kind)).length;
const paginated = computed(() =>
  filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value),
);
function categories(row: any) {
  return (row.bizCategories || ["GENERAL"])
    .map((code: string) => businessCategories.find((item) => item.code === code)?.name || code)
    .join("、");
}
const detail = ref<any>();
const createOpen = ref(false);
const form = ref<any>({});

// —— 已生效筛选 chip 回显（方案 §4）：本页为客户端过滤，chips 即实时筛选状态，可单个移除。
const hasFilter = computed(() =>
  Boolean(filter.keyword || filter.status || businessCategory.value || kindFilter.value),
);
const chips = computed(() => {
  const list: { key: string; label: string; clear: () => void }[] = [];
  if (filter.keyword)
    list.push({
      key: "keyword",
      label: `搜索：${filter.keyword}`,
      clear: () => (filter.keyword = ""),
    });
  if (filter.status)
    list.push({
      key: "status",
      label: `状态：${filter.status}`,
      clear: () => (filter.status = ""),
    });
  if (kindFilter.value)
    list.push({
      key: "kind",
      label: `类型：${kindFilter.value}`,
      clear: () => (kindFilter.value = ""),
    });
  if (businessCategory.value)
    list.push({
      key: "bizCategory",
      label: `业务分类：${businessCategory.value}`,
      clear: () => (businessCategory.value = ""),
    });
  return list;
});
function clearFilters() {
  filter.keyword = "";
  filter.status = "";
  businessCategory.value = "";
  kindFilter.value = "";
}

function create() {
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
  createOpen.value = true;
}

async function saveSkill(value: any) {
  await operation(async () => {
    await api("/admin/skills", "POST", value);
    createOpen.value = false;
    success("已保存");
    await load();
  });
}

function openImported(code: string) {
  createOpen.value = false;
  void router.push(`/skills/${encodeURIComponent(code)}`);
}
</script>

<template>
  <DataPage
    v-model:page="page"
    v-model:page-size="pageSize"
    :total="filtered.length"
  >
    <template #header>
      <PageHeader
        title="技能库"
        subtitle="注册、校验并复用可调用的业务能力。"
        :busy="busy"
        @refresh="load"
      >
        <!-- UI-03/UI-02遗留补迁移：主操作经PageHeader actions插槽迁入AppShell顶栏挂载点。 -->
        <template #actions>
          <el-button type="primary" v-permission="'edit'" @click="create()"
            >注册技能</el-button
          >
        </template>
      </PageHeader>
    </template>
    <template #error>
      <ErrorBanner :error="error" :trace="trace" />
    </template>
    <template #filters>
      <el-input
        v-model="filter.keyword"
        clearable
        placeholder="搜索技能名称或编码"
        style="width: 16rem"
      />
      <el-input
        v-model="filter.status"
        clearable
        placeholder="状态"
        style="width: 10rem"
      />
      <!-- F2-2（方案 §5.5）：kind（TOOL/GUIDE）chips 筛选，客户端过滤，计数随当前列表。 -->
      <el-check-tag
        v-for="option in SKILL_KIND_FILTERS"
        :key="option.value || 'all'"
        :checked="kindFilter === option.value"
        @change="kindFilter = option.value"
        >{{
          option.value
            ? `${option.label} ${kindCount(option.value)}`
            : option.label
        }}</el-check-tag
      >
      <BusinessCategorySelect v-model="businessCategory" />
      <el-button text @click="businessCategory = ''">全部分类</el-button>
    </template>
    <template #chips>
      <el-tag
        v-for="chip in chips"
        :key="chip.key"
        closable
        @close="chip.clear()"
        >{{ chip.label }}</el-tag
      >
      <el-button v-if="hasFilter" text size="small" @click="clearFilters"
        >清除筛选</el-button
      >
    </template>
    <el-skeleton v-if="busy" :rows="5" animated />
    <el-table v-else :data="paginated" stripe height="100%">
      <el-table-column label="名称 / 编码" min-width="200">
        <template #default="{ row }">
          <a :href="'#/skills/' + encodeURIComponent(row.code)">{{
            row.name || row.code
          }}</a>
          <!-- AP-S06：fixture 演示技能带可区分标识，不与“可用”状态混同。 -->
          <el-tag
            v-if="row.source === 'FIXTURE'"
            size="small"
            type="warning"
            effect="plain"
            >内置</el-tag
          >
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span :class="['status', row.status]">{{
            row.statusLabel || statusLabel(row.status)
          }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="type" label="类型" width="110">
        <template #default="{ row }">
          <!-- F2-2：kind 分型标识（TOOL/GUIDE）；TOOL 行仍显示技术类型（HTTP/MCP/…）。 -->
          <el-tag
            v-if="skillKindOf(row) === 'GUIDE'"
            size="small"
            effect="plain"
            >GUIDE</el-tag
          >
          <template v-else>{{ row.type || "TOOL" }}</template>
        </template>
      </el-table-column><el-table-column label="业务分类" min-width="140"><template #default="{row}">{{categories(row)}}</template></el-table-column><el-table-column prop="businessSystemCode" label="业务系统" min-width="120" /><el-table-column label="访问范围" min-width="110"><template #default="{row}">{{row.accessPolicy?.visibility==='PRIVATE'?'私有':row.accessPolicy?.visibility==='BUSINESS'?'业务系统':row.accessPolicy?.visibility==='RESTRICTED'?'角色可见':'公开'}}</template></el-table-column>
      <el-table-column
        prop="description"
        label="说明"
        min-width="220"
        show-overflow-tooltip
      />
      <el-table-column label="更新于" width="150">
        <template #default="{ row }">{{
          displayTime(row.updatedAt || row.createdAt)
        }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="100">
        <template #default="{ row }">
          <el-button text @click="detail = row">查看详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <EmptyState
          :variant="error ? 'error' : hasFilter ? 'filtered' : 'create'"
          :description="
            error ? error : hasFilter ? '' : '暂无技能；注册后可在流程中调用。'
          "
          :action-text="error ? '刷新重试' : hasFilter ? '清除筛选' : '注册技能'"
          @action="error ? load() : hasFilter ? clearFilters() : create()"
        />
      </template>
    </el-table>
  </DataPage>
  <ResourceDetailPanel :detail="detail" />
  <el-dialog
    v-model="createOpen"
    title="注册技能"
    destroy-on-close
    width="min(760px, 96vw)"
  >
    <SkillWizard
      v-if="createOpen"
      :initial="form"
      @save="saveSkill"
      @open-imported="openImported"
    />
  </el-dialog>
</template>
