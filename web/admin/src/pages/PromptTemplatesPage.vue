<script setup lang="ts">
import { ref } from "vue";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import CreatePromptTemplateDialog from "./resources/CreatePromptTemplateDialog.vue";
import ResourceDetailPanel from "./resources/ResourceDetailPanel.vue";
import { displayTime, statusLabel } from "./resources/resource-format";
import { useResourceList } from "./resources/useResourceList";

usePageTitle(() => "提示词模板");
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
  filtered,
  paginated,
  load,
} = useResourceList("templates");

const detail = ref<any>();
const createOpen = ref(false);
const form = ref<any>({});

function create() {
  form.value = { code: "", name: "", content: "", variables: [] };
  createOpen.value = true;
}
async function save() {
  await operation(async () => {
    if (!form.value.code?.trim()) throw new Error("请填写模板编码");
    await api("/admin/prompt-templates", "POST", form.value);
    createOpen.value = false;
    success("已保存");
    await load();
  });
}
</script>

<template>
  <PageHeader
    title="提示词模板"
    subtitle="沉淀可复用的提示词与变量声明。"
    :busy="busy"
    @refresh="load"
  />
  <PageError :error="error" :trace="trace" />
  <div class="row">
    <div class="muted">共 {{ filtered.length }} 条记录</div>
    <div class="actions">
      <el-button type="primary" v-permission="'edit'" @click="create()"
        >创建提示词模板</el-button
      >
    </div>
  </div>
  <div class="panel">
    <div class="filters">
      <el-input v-model="filter.keyword" clearable placeholder="搜索模板名称或编码" />
      <el-input v-model="filter.status" clearable placeholder="状态" />
    </div>
    <el-skeleton v-if="busy" :rows="5" animated />
    <el-table v-else :data="paginated" stripe>
      <el-table-column label="名称 / 编码" min-width="220">
        <template #default="{ row }">
          <strong>{{ row.name || row.code }}</strong>
          <div class="muted">{{ row.code }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span :class="['status', row.status]">{{
            row.statusLabel || statusLabel(row.status)
          }}</span>
        </template>
      </el-table-column>
      <el-table-column label="变量" width="90">
        <template #default="{ row }">{{
          Array.isArray(row.variables) ? row.variables.length : "—"
        }}</template>
      </el-table-column>
      <el-table-column
        prop="content"
        label="模板内容"
        min-width="260"
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
          :description="error ? '服务暂不可用，请刷新重试' : '暂无模板；创建后可被应用引用。'"
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
  <CreatePromptTemplateDialog
    v-model="createOpen"
    :form="form"
    :busy="busy"
    @save="save"
  />
</template>
