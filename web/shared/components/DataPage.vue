<script setup lang="ts">
import { computed } from "vue";

// F1-3（台账 §7.5 / 方案 §7.1 表格页四段高度模型）：
//   DataPage = flex column, height:100%
//   ├ 页头     auto（#header 插槽，样板页放 PageHeader——保持顶栏 actions 迁移与壳层联动）
//   ├ 筛选区   auto（#filters 基础筛选常显 + #chips 已生效筛选 chip 回显 + #error 吸顶错误条）
//   ├ 表格区   flex:1; min-height:0（唯一滚动点——页面放 el-table height="100%" 表体滚动表头固定；
//   │          非表格内容页面自行挂 .page-scroll，本组件不加第二层滚动，遵守"禁三级滚动容器"）
//   └ 分页/汇总 auto（钉底，20/50/100 单一规格，方案 §4 DataPage 行）
// 列显隐配置/工具栏导出随后续页面卡按需扩展，本卡只交付高度模型+筛选 chips+分页规格。
const props = withDefaults(
  defineProps<{
    /** 分页总数；null/undefined 隐藏分页行（详情/非列表用法） */
    total?: number | null;
    page?: number;
    pageSize?: number;
    /** 方案 §4：服务端分页 20/50/100 单一规格 */
    pageSizes?: number[];
    paginationLayout?: string;
  }>(),
  {
    total: null,
    page: 1,
    pageSize: 20,
    pageSizes: () => [20, 50, 100],
    paginationLayout: "total, sizes, prev, pager, next",
  },
);
const emit = defineEmits<{
  (e: "update:page", value: number): void;
  (e: "update:pageSize", value: number): void;
}>();
const showPagination = computed(
  () => typeof props.total === "number" && Number.isFinite(props.total),
);
function onPageChange(value: number) {
  emit("update:page", value);
}
function onSizeChange(value: number) {
  // 规格变更回到第一页（与旧页 @size-change="page = 1" 语义一致）
  emit("update:pageSize", value);
  emit("update:page", 1);
}
</script>

<template>
  <section class="data-page">
    <div class="data-page-head">
      <!-- 页头段：样板页放 PageHeader（title/subtitle/actions/refresh），本组件不复制其壳层联动逻辑 -->
      <slot name="header" />
      <!-- 吸顶错误条（方案 §4 ErrorBanner：页头下方 sticky，长页滚动仍可见） -->
      <slot name="error" />
    </div>
    <div v-if="$slots.filters || $slots.chips" class="data-page-filters">
      <div v-if="$slots.filters" class="data-page-filter-row">
        <slot name="filters" />
      </div>
      <!-- 已生效筛选 chip 回显：折叠的高级筛选生效后仍可见、可单个移除 -->
      <div v-if="$slots.chips" class="data-page-chips">
        <slot name="chips" />
      </div>
    </div>
    <!-- 表格段：唯一滚动点。el-table height="100%"（表体滚动/表头固定）；
         页面放非表格内容时自行挂 .page-scroll。本层只保证 flex:1 + min-height:0。 -->
    <div class="data-page-body">
      <slot />
    </div>
    <div v-if="showPagination" class="data-page-footer">
      <el-pagination
        :current-page="page"
        :page-size="pageSize"
        :page-sizes="pageSizes"
        :total="total ?? 0"
        :layout="paginationLayout"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </section>
</template>

<style scoped>
/* 四段高度模型（方案 §7.1 高度铁律④）：只引用 tokens 变量（§7.2 只用 --sp-*） */
.data-page {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.data-page-head {
  flex-shrink: 0;
}
.data-page-filters {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  margin-bottom: var(--sp-3);
}
.data-page-filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
  align-items: center;
}
.data-page-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-1);
  align-items: center;
}
.data-page-chips:empty {
  display: none;
}
/* 滚动体铁律：flex 子项必须 min-height:0（方案 §7.1 高度铁律③）；
   滚动交给表格自身（height="100%"），本层不产生第二条滚动条 */
.data-page-body {
  flex: 1;
  min-height: 0;
}
.data-page-footer {
  flex-shrink: 0;
  display: flex;
  justify-content: flex-end;
  padding-top: var(--sp-3);
}
</style>
