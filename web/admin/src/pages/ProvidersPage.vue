<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import CreateProviderDialog from "./resources/CreateProviderDialog.vue";
import ResourceDetailPanel from "./resources/ResourceDetailPanel.vue";
import { displayTime, statusLabel } from "./resources/resource-format";
import { useResourceList } from "./resources/useResourceList";

const router = useRouter();
usePageTitle(() => "模型供应商");
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
} = useResourceList("providers");

const detail = ref<any>();
const createOpen = ref(false);
const form = ref<any>({});

function create() {
  form.value = {
    code: "",
    name: "",
    protocol: "OPENAI_COMPATIBLE",
    baseUrl: "",
    secretRef: "",
    trustLabels: [],
  };
  createOpen.value = true;
}
async function save() {
  await operation(async () => {
    if (!form.value.code?.trim()) throw new Error("请填写编码");
    await api("/admin/model-providers", "POST", form.value);
    createOpen.value = false;
    success("已保存");
    await load();
  });
}
async function testProvider(row: any) {
  await operation(async () => {
    detail.value = await api(`/admin/model-providers/${row.id}/test`, "POST");
    success("连接测试已返回");
  });
}
</script>

<template>
  <PageHeader
    title="模型供应商"
    subtitle="登记模型端点、凭据引用与数据分类。"
    :busy="busy"
    @refresh="load"
  >
    <!-- UI-03：主操作经PageHeader actions插槽迁入AppShell顶栏挂载点。 -->
    <template #actions>
      <el-button type="primary" v-permission="'admin'" @click="create()"
        >添加供应商</el-button
      >
    </template>
  </PageHeader>
  <PageError :error="error" :trace="trace" />
  <div class="row">
    <div class="muted">共 {{ filtered.length }} 条记录</div>
  </div>
  <div class="panel">
    <div class="filters">
      <el-input v-model="filter.keyword" clearable placeholder="搜索供应商名称或编码" />
      <el-input v-model="filter.status" clearable placeholder="状态" />
    </div>
    <el-skeleton v-if="busy" :rows="5" animated />
    <el-table v-else :data="paginated" stripe>
      <el-table-column label="名称 / 编码" min-width="200">
        <template #default="{ row }">
          <strong>{{ row.name }}</strong>
          <div class="muted">
            {{ row.code }}
            <!-- AP-S06：fixture 演示数据带可区分标识，不与“可用”状态混同。 -->
            <el-tag
              v-if="row.source === 'FIXTURE'"
              size="small"
              type="warning"
              effect="plain"
              >内置</el-tag
            >
          </div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span :class="['status', row.status]">{{
            row.statusLabel || statusLabel(row.status)
          }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="protocol" label="协议" width="100" />
      <el-table-column
        prop="description"
        label="说明"
        min-width="180"
        show-overflow-tooltip
      />
      <el-table-column label="更新于" width="150">
        <template #default="{ row }">{{
          displayTime(row.updatedAt || row.createdAt)
        }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="200">
        <template #default="{ row }">
          <el-button
            text
            @click="router.push(`/model/providers/${row.id}/entries`)"
            >模型条目</el-button
          >
          <el-button
            text
            v-permission="'admin'"
            @click="testProvider(row)"
            >测试连接</el-button
          >
          <el-button text @click="detail = row">查看详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="error ? '服务暂不可用，请刷新重试' : '暂无供应商；添加后配置模型条目。'"
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
  <CreateProviderDialog
    v-model="createOpen"
    :form="form"
    :busy="busy"
    @save="save"
  />
</template>
