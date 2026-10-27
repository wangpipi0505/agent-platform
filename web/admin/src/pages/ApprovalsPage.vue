<script setup lang="ts">
import { ref } from "vue";
import PageError from "../PageError.vue";
import PageHeader from "../PageHeader.vue";
import { usePageTitle } from "../usePageOperation";
import ResourceDetailPanel from "./resources/ResourceDetailPanel.vue";
import ActionPlanPanel from "./ActionPlanPanel.vue";
import { approvePayload } from "../../../shared/approval";
import { statusLabel } from "./resources/resource-format";
import { useResourceList } from "./resources/useResourceList";

usePageTitle(() => "确认中心");
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
} = useResourceList("approvals");

const detail = ref<any>();
// PLAT-15：高危计划（risk=HIGH 且确认角色不足两个）需要二次确认；按节点实例记录勾选状态。
const doubleConfirmedBy = ref<Record<string, boolean>>({});

function confirmKey(row: any): string {
  return `${row.runId}/${row.nodeInstanceId || row.nodeId}`;
}

async function approval(row: any, action: string) {
  await operation(async () => {
    const payload = approvePayload(
      row,
      action === "APPROVE",
      crypto.randomUUID(),
      doubleConfirmedBy.value[confirmKey(row)] === true,
    );
    if (
      !confirm(
        action === "APPROVE"
          ? `确认按上述行动计划执行？当前服务端快照：\n${JSON.stringify(row, null, 2)}`
          : "确认拒绝此次执行？",
      )
    )
      return;
    await api(
      `/admin/approvals/${row.runId}/${row.nodeInstanceId || row.nodeId}`,
      "POST",
      payload,
    );
    success(action === "APPROVE" ? "已确认执行" : "已拒绝执行");
    await load();
  });
}
</script>

<template>
  <PageHeader
    title="确认中心"
    subtitle="核对服务端行动计划与快照后确认或拒绝等待中的执行。"
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
      <el-input v-model="filter.status" clearable placeholder="状态" />
    </div>
    <el-skeleton v-if="busy" :rows="5" animated />
    <el-table v-else :data="paginated" stripe>
      <el-table-column type="expand">
        <template #default="{ row }">
          <ActionPlanPanel
            :row="row"
            @update:doubleConfirmed="
              (value: boolean) => (doubleConfirmedBy[confirmKey(row)] = value)
            "
          />
        </template>
      </el-table-column>
      <el-table-column prop="runId" label="运行编码" min-width="300" />
      <el-table-column prop="nodeDefId" label="待确认节点" min-width="130" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">{{
          row.statusLabel || statusLabel(row.status || "PENDING")
        }}</template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="190">
        <template #default="{ row }">
          <el-button
            text
            v-permission="'approve'"
            @click="approval(row, 'APPROVE')"
            >确认</el-button
          >
          <el-button
            text
            type="danger"
            v-permission="'approve'"
            @click="approval(row, 'REJECT')"
            >拒绝</el-button
          >
          <el-button text @click="detail = row">查看详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty
          :description="error ? '服务暂不可用，请刷新重试' : '暂无待确认事项。'"
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
