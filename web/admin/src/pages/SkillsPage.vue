<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import BusinessCategorySelect from "../BusinessCategorySelect.vue";
import {businessCategories} from "../../../shared/skill-catalog";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import SkillWizard from "../SkillWizard.vue";
import { usePageTitle } from "../usePageOperation";
import ResourceDetailPanel from "./resources/ResourceDetailPanel.vue";
import { displayTime, statusLabel } from "./resources/resource-format";
import { useResourceList } from "./resources/useResourceList";

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
  filtered:baseFiltered,
  load,
} = useResourceList("skills");

const businessCategory=ref('');
const filtered=computed(()=>baseFiltered.value.filter(row=>!businessCategory.value||(row.bizCategories||['GENERAL']).includes(businessCategory.value)));
const paginated=computed(()=>filtered.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value));
function categories(row:any){return (row.bizCategories||['GENERAL']).map((code:string)=>businessCategories.find(item=>item.code===code)?.name||code).join('、')}
const detail = ref<any>();
const createOpen = ref(false);
const form = ref<any>({});

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
  <PageError :error="error" :trace="trace" />
  <div class="row">
    <div class="muted">共 {{ filtered.length }} 条记录</div>
  </div>
  <div class="panel">
    <div class="filters">
      <el-input v-model="filter.keyword" clearable placeholder="搜索技能名称或编码" />
      <el-input v-model="filter.status" clearable placeholder="状态" /><BusinessCategorySelect v-model="businessCategory" /><el-button text @click="businessCategory=''">全部分类</el-button>
    </div>
    <el-skeleton v-if="busy" :rows="5" animated />
    <el-table v-else :data="paginated" stripe>
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
      <el-table-column prop="type" label="类型" width="80" /><el-table-column label="业务分类" min-width="140"><template #default="{row}">{{categories(row)}}</template></el-table-column><el-table-column prop="businessSystemCode" label="业务系统" min-width="120" /><el-table-column label="访问范围" min-width="110"><template #default="{row}">{{row.accessPolicy?.visibility==='PRIVATE'?'私有':row.accessPolicy?.visibility==='BUSINESS'?'业务系统':row.accessPolicy?.visibility==='RESTRICTED'?'角色可见':'公开'}}</template></el-table-column>
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
        <el-empty
          :description="error ? '服务暂不可用，请刷新重试' : '暂无技能；注册后可在流程中调用。'"
        />
      </template>
    </el-table>
    <el-pagination
      v-model:current-page="page"
      v-model:page-size="pageSize"
      :page-sizes="[12, 24, 48]"
      :total="filtered.length"
      layout="sizes,prev,pager,next,total"
      style="margin-top: 1rem"
      @size-change="page = 1"
    />
  </div>
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
