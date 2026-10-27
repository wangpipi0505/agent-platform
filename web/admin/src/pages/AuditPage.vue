<script setup lang="ts">
import { ref } from "vue";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import ResourceDetailPanel from "./resources/ResourceDetailPanel.vue";
import { displayTime } from "./resources/resource-format";
import { useResourceList } from "./resources/useResourceList";

usePageTitle(() => "审计记录");
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
} = useResourceList("audit");

const detail = ref<any>();
</script>

<template>
  <PageHeader
    title="审计记录"
    subtitle="按运行追踪事件、操作者与发生时间。"
    :busy="busy"
    @refresh="load"
  />
  <PageError :error="error" :trace="trace" />
  <div class="row">
    <div class="muted">共 {{ filtered.length }} 条记录</div>
  </div>
  <div class="panel">
    <div class="filters">
      <el-input v-model="filter.keyword" clearable placeholder="搜索运行编码" />
      <el-input v-model="filter.type" clearable placeholder="事件类型，例如 RUN_FINISHED" />
    </div>
    <el-skeleton v-if="busy" :rows="5" animated />
    <el-table v-else :data="paginated" stripe>
      <el-table-column prop="runId" label="运行编码" min-width="260">
        <template #default="{ row }">
          <a :href="'#/runs/' + encodeURIComponent(row.runId)">{{
            row.runId
          }}</a>
        </template>
      </el-table-column>
      <el-table-column prop="type" label="事件" width="170" />
      <el-table-column prop="principalId" label="操作者" width="130" />
      <el-table-column prop="seq" label="序号" width="70" />
      <el-table-column label="发生时间" width="150">
        <template #default="{ row }">{{ displayTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="100">
        <template #default="{ row }">
          <el-button text @click="detail = row">查看详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="error ? '服务暂不可用，请刷新重试' : '暂无审计记录。'"
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
