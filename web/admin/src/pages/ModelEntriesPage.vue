<script setup lang="ts">
import { ref } from "vue";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import CreateModelEntryDialog from "./resources/CreateModelEntryDialog.vue";
import ResourceDetailPanel from "./resources/ResourceDetailPanel.vue";
import { displayTime, statusLabel } from "./resources/resource-format";
import { useResourceList } from "./resources/useResourceList";

usePageTitle(() => "模型条目");
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
  providerId,
  filtered,
  paginated,
  load,
} = useResourceList("entries", { providerScoped: true });

const detail = ref<any>();
const createOpen = ref(false);
const form = ref<any>({});
const providers = ref<any[]>([]);

async function create() {
  await operation(async () => {
    providers.value = await api<any[]>("/admin/model-providers");
    form.value = {
      providerId: providerId.value ? Number(providerId.value) : null,
      modelName: "",
      displayName: "",
      contextWindow: 8192,
      maxOutputTokens: 2048,
      capabilities: ["chat"],
      tokenizerUrl: "",
    };
    createOpen.value = true;
  });
}
async function save() {
  await operation(async () => {
    for (const key of ["providerId", "contextWindow", "maxOutputTokens"]) {
      form.value[key] = Number(form.value[key]);
      if (!Number.isFinite(form.value[key]) || form.value[key] <= 0)
        throw new Error(`${key} 必须是正数`);
    }
    if (!form.value.modelName?.trim()) throw new Error("请填写模型调用名称");
    await api("/admin/model-entries", "POST", form.value);
    createOpen.value = false;
    success("已保存");
    await load();
  });
}
async function testEntry(row: any) {
  await operation(async () => {
    const result = await api<any>(`/admin/model-entries/${row.id}/test`, "POST");
    if (!result?.ok)
      throw new Error(
        [result?.stage, result?.reason].filter(Boolean).join("：") ||
          "模型条目自检未通过",
      );
    const checks = (result.checks || []) as any[];
    const passed = checks.filter((check) => check.status === "PASS").length;
    success(
      `自检通过：${result.model || "模型"}，${result.latencyMs}ms，${passed}/${checks.length} 项检查通过`,
    );
  });
}
</script>

<template>
  <PageHeader
    title="模型条目"
    subtitle="绑定供应商模型、能力、上下文与 Token 计数方式。"
    :busy="busy"
    @refresh="load"
  />
  <PageError :error="error" :trace="trace" />
  <div class="row">
    <div class="muted">共 {{ filtered.length }} 条记录</div>
    <div class="actions">
      <el-button type="primary" v-permission="'admin'" @click="create()"
        >添加模型条目</el-button
      >
    </div>
  </div>
  <div class="panel">
    <div class="filters">
      <el-input v-model="filter.keyword" clearable placeholder="搜索模型名称或编码" />
      <el-input v-model="filter.status" clearable placeholder="状态" />
    </div>
    <el-skeleton v-if="busy" :rows="5" animated />
    <el-table v-else :data="paginated" stripe>
      <el-table-column label="名称 / 模型" min-width="220">
        <template #default="{ row }">
          <strong>{{ row.displayName || row.modelName }}</strong>
          <div class="muted">
            {{ row.modelName }}
            <!-- AP-S06：fixture 供应商名下的条目随供应商带来源标识。 -->
            <el-tag
              v-if="row.provider && row.provider.source === 'FIXTURE'"
              size="small"
              type="warning"
              effect="plain"
              >演示 fixture</el-tag
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
      <el-table-column prop="providerId" label="供应商" width="90" />
      <el-table-column label="能力" min-width="160">
        <template #default="{ row }">{{
          Array.isArray(row.capabilities) ? row.capabilities.join(", ") : "—"
        }}</template>
      </el-table-column>
      <el-table-column label="更新于" width="150">
        <template #default="{ row }">{{
          displayTime(row.updatedAt || row.createdAt)
        }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="150">
        <template #default="{ row }">
          <el-button text v-permission="'admin'" @click="testEntry(row)"
            >自检</el-button
          >
          <el-button text @click="detail = row">查看详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="error ? '服务暂不可用，请刷新重试' : '暂无模型条目；先添加供应商并配置模型。'"
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
  <CreateModelEntryDialog
    v-model="createOpen"
    :form="form"
    :providers="providers"
    :busy="busy"
    @save="save"
  />
</template>
