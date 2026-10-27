<script setup lang="ts">
import { ref } from "vue";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import ResourceDetailPanel from "./resources/ResourceDetailPanel.vue";
import { displayTime, evalPassText, statusLabel } from "./resources/resource-format";
import { useResourceList } from "./resources/useResourceList";

usePageTitle(() => "评测中心");
const {
  busy,
  error,
  trace,
  filter,
  page,
  pageSize,
  filtered,
  paginated,
  load,
} = useResourceList("evaluations");

const detail = ref<any>();
</script>

<template>
  <PageHeader
    title="评测中心"
    subtitle="查看候选评测结果与门禁结论。"
    :busy="busy"
    @refresh="load"
  />
  <PageError :error="error" :trace="trace" />
  <div class="row">
    <div class="muted">共 {{ filtered.length }} 条记录</div>
  </div>
  <div class="panel">
    <div class="filters">
      <el-input v-model="filter.keyword" clearable placeholder="搜索应用编码" />
      <el-input v-model="filter.status" clearable placeholder="状态" />
    </div>
    <el-skeleton v-if="busy" :rows="5" animated />
    <el-table v-else :data="paginated" stripe>
      <el-table-column label="应用" min-width="220">
        <template #default="{ row }">
          <a :href="'#/apps/' + encodeURIComponent(row.appCode)">{{
            row.appCode
          }}</a>
        </template>
      </el-table-column>
      <el-table-column label="草稿版本" width="90">
        <template #default="{ row }">{{
          row.draftRev ? "v" + row.draftRev : "—"
        }}</template>
      </el-table-column>
      <el-table-column label="通过用例" width="100">
        <template #default="{ row }">{{ evalPassText(row) }}</template>
      </el-table-column>
      <el-table-column label="评测时间" width="150">
        <template #default="{ row }">{{ displayTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span :class="['status', row.status]">{{
            row.statusLabel || statusLabel(row.status)
          }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="100">
        <template #default="{ row }">
          <el-button text @click="detail = row">查看详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="error ? '服务暂不可用，请刷新重试' : '暂无评测记录；发布前先完成候选评测。'"
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
</template>
