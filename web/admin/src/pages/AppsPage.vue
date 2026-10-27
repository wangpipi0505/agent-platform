<script setup lang="ts">
import { ref, watch } from "vue";
import { useRouter } from "vue-router";
import {
  copyApplicationBody,
} from "../../../shared/statistics-lifecycle";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import CopyApplicationDialog from "./resources/CopyApplicationDialog.vue";
import CreateApplicationDialog from "./resources/CreateApplicationDialog.vue";
import ResourceDetailPanel from "./resources/ResourceDetailPanel.vue";
import { displayTime, statusLabel } from "./resources/resource-format";
import { useResourceList } from "./resources/useResourceList";

const router = useRouter();
usePageTitle(() => "智能体管理");
const {
  auth,
  api,
  busy,
  error,
  trace,
  operation,
  success,
  filter,
  page,
  pageSize,
  filtered,
  paginated,
  load,
} = useResourceList("apps", { serverFiltered: true });

const detail = ref<any>();
const createOpen = ref(false);
const form = ref<any>({});
const copySource = ref("");
const copyForm = ref({ code: "", name: "" });
const copyError = ref("");
const copying = ref(false);

function create() {
  form.value = {
    code: "",
    name: "",
    type: "CHAT",
    description: "",
    domain: "",
    bizCategory: "",
    tags: [],
  };
  createOpen.value = true;
}

async function save() {
  await operation(async () => {
    if (!form.value.name?.trim()) throw new Error("请填写应用名称");
    if (!form.value.code?.trim()) throw new Error("请填写编码");
    await api("/admin/apps", "POST", form.value);
    createOpen.value = false;
    success("已保存");
    await load();
  });
}

function startCopy(row: any) {
  copySource.value = row.code;
  copyForm.value = { code: "", name: `${row.name} 副本` };
  copyError.value = "";
}
async function copyApplication() {
  if (copying.value || !auth.can("edit")) return;
  let body: { code: string; name: string };
  try {
    body = copyApplicationBody(copyForm.value);
  } catch (e: any) {
    copyError.value = e.message;
    return;
  }
  copying.value = true;
  copyError.value = "";
  try {
    const result = await api<any>(
      `/admin/apps/${encodeURIComponent(copySource.value)}/copy`,
      "POST",
      body,
    );
    copySource.value = "";
    void router.push(`/apps/${encodeURIComponent(result.code)}`);
  } catch (e: any) {
    copyError.value =
      e.status === 403 ? "当前账号没有复制应用的权限" : e.message;
  } finally {
    copying.value = false;
  }
}
watch(filter, () => (detail.value = undefined), { deep: true });
</script>

<template>
  <PageHeader
    title="智能体管理"
    subtitle="连接业务能力，持续验证每一次运行。"
    :busy="busy"
    @refresh="load"
  />
  <PageError :error="error" :trace="trace" />
  <div class="row">
    <div class="muted">共 {{ filtered.length }} 条记录</div>
    <div class="actions">
      <el-button type="primary" v-permission="'edit'" @click="create()"
        >创建智能体</el-button
      >
    </div>
  </div>
  <div class="panel">
    <div class="filters">
      <el-input v-model="filter.keyword" clearable placeholder="搜索名称或运行编码" />
      <el-input v-model="filter.code" clearable placeholder="应用编码" />
      <el-input v-model="filter.domain" clearable placeholder="专业领域" />
      <el-input v-model="filter.bizCategory" clearable placeholder="业务分类" />
      <el-input v-model="filter.tags" clearable placeholder="标签" />
      <el-select
        v-model="filter.status"
        clearable
        placeholder="状态（默认不含归档）"
        aria-label="应用状态"
      >
        <el-option
          v-for="state in ['DRAFT', 'PUBLISHED', 'DISABLED', 'ARCHIVED']"
          :key="state"
          :value="state"
          :label="statusLabel(state)"
        />
      </el-select>
    </div>
    <el-skeleton v-if="busy" :rows="5" animated />
    <el-table v-else :data="paginated" stripe>
      <el-table-column label="名称 / 编码" min-width="200">
        <template #default="{ row }">
          <a :href="'#/apps/' + encodeURIComponent(row.code)">
            <strong>{{ row.name }}</strong>
            <div class="muted">{{ row.code }}</div>
          </a>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span :class="['status', row.status]">{{
            row.statusLabel || statusLabel(row.status)
          }}</span>
        </template>
      </el-table-column>
      <el-table-column label="当前版本" width="80">
        <template #default="{ row }">{{
          row.currentVersionNo ? "v" + row.currentVersionNo : "—"
        }}</template>
      </el-table-column>
      <el-table-column prop="type" label="类型" width="70" />
      <el-table-column prop="domain" label="专业领域" width="90" />
      <el-table-column prop="bizCategory" label="业务分类" width="90" />
      <el-table-column
        prop="description"
        label="说明"
        min-width="180"
        show-overflow-tooltip
      />
      <el-table-column label="更新于" width="150">
        <template #default="{ row }">{{
          displayTime(row.updatedAt || row.startedAt || row.createdAt)
        }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="150">
        <template #default="{ row }">
          <el-button text @click="router.push(`/apps/${row.code}/run`)"
            >运行</el-button
          >
          <el-button text v-permission="'edit'" @click="startCopy(row)"
            >复制</el-button
          >
          <el-button text @click="detail = row">查看详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="
            error
              ? '服务暂不可用，请刷新重试'
              : Object.values(filter).some((value) => String(value).trim())
                ? '没有匹配的应用，请调整筛选'
                : '还没有智能体，创建第一个'
          "
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
  <CreateApplicationDialog
    v-model="createOpen"
    :form="form"
    :busy="busy"
    @save="save"
  />
  <CopyApplicationDialog
    :source="copySource"
    :code="copyForm.code"
    :name="copyForm.name"
    :busy="copying"
    :error="copyError"
    @update:source="copySource = $event"
    @update:code="copyForm.code = $event"
    @update:name="copyForm.name = $event"
    @copy="copyApplication"
  />
</template>
