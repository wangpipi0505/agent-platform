<script setup lang="ts">
/**
 * F4-1 会话列表（M5-2 切真实 API，消费 shared/api-session）：本人会话（mine=true 固定）
 * 只读+切换+新建；重命名/删除占位 disabled+tooltip「后续批次」（接口稿 §5.1 无该契约，
 * 待删除保留策略契约补齐后交付——前端稿 §5.4 收敛口径）。lastActiveAt 倒序+before 游标分页。
 */
import { computed, onMounted, ref } from "vue";
import { listMySessions } from "../../shared/api-session";
import type { SessionView } from "./demo-flow";

const emit = defineEmits<{ select: [row: SessionView]; create: [] }>();
const props = withDefaults(
  defineProps<{ activeId: string; localRows?: SessionView[] }>(),
  { localRows: () => [] },
);

/** mock 契约行（mine=true 游标分页）+ 新建会话首次发送后回填的本地行（置顶）。 */
const rows = ref<SessionView[]>([]);
const allRows = computed(() => [...props.localRows, ...rows.value]);
const nextBefore = ref<string | undefined>();
const loading = ref(false);
const error = ref("");

const timeLabel = (iso: string) => iso.replace("T", " ").slice(0, 16);

async function loadMore() {
  loading.value = true;
  error.value = "";
  try {
    const page = await listMySessions({ mine: true, before: nextBefore.value });
    rows.value.push(...page.sessions);
    nextBefore.value = page.nextBefore ?? undefined;
    // 首次装载且未选中会话时自动选中最近会话（前端稿 §5.4 消息流示意默认有内容）。
    if (!bootstrapped && !props.activeId && rows.value.length) {
      bootstrapped = true;
      emit("select", rows.value[0]!);
    }
  } catch (e: any) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}
let bootstrapped = false;
onMounted(loadMore);

const select = (row: SessionView) => emit("select", row);
</script>
<template>
  <div class="session-list" data-kind="session-list">
    <div class="list-head">
      <span class="muted">会话（本人）</span>
      <el-button size="small" type="primary" plain @click="emit('create')">新建会话</el-button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <ul class="rows">
      <li v-for="row in allRows" :key="row.sessionId">
        <button
          type="button"
          :class="['row-item', { active: row.sessionId === props.activeId }]"
          @click="select(row)"
        >
          <span class="title">{{ row.title }}</span>
          <span class="meta muted font-mono">{{ timeLabel(row.lastActiveAt) }} · {{ row.state }}</span>
        </button>
        <!-- 重命名/删除占位：接口契约后置（前端稿 §5.4），禁用+tooltip，不静默。 -->
        <span class="row-ops">
          <el-tooltip content="重命名：后续批次" placement="top">
            <span><el-button size="small" link disabled>重命名</el-button></span>
          </el-tooltip>
          <el-tooltip content="删除：后续批次" placement="top">
            <span><el-button size="small" link disabled>删除</el-button></span>
          </el-tooltip>
        </span>
      </li>
    </ul>
    <p v-if="!allRows.length && !loading" class="muted empty">暂无本人会话</p>
    <el-button
      v-if="nextBefore"
      class="more"
      size="small"
      :loading="loading"
      @click="loadMore"
    >
      加载更多
    </el-button>
  </div>
</template>
<style scoped>
.session-list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  font-size: var(--text-meta);
}
.list-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--sp-1);
}
.rows li {
  position: relative;
}
.row-item {
  width: 100%;
  display: grid;
  gap: var(--sp-1);
  padding: var(--sp-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
  text-align: left;
}
.row-item.active {
  border-color: var(--brand-primary);
  background: var(--nav-active-bg);
}
.row-item .title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-item .meta {
  font-size: var(--text-meta);
}
.row-ops {
  position: absolute;
  right: var(--sp-2);
  top: var(--sp-2);
  display: none;
  gap: var(--sp-1);
  background: var(--surface);
}
.rows li:hover .row-ops {
  display: inline-flex;
}
.more {
  align-self: flex-start;
}
.empty,
.error {
  margin: 0;
}
.error {
  color: var(--st-fail-text);
}
</style>
