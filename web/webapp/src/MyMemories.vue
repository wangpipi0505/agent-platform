<script setup lang="ts">
/**
 * F4-2 我的记忆（侧栏入口）：列表（listMemories 默认 ACTIVE，REVOKED 退出检索）→单条正文
 * （getMemory）→修改（updateMemory 版本 CAS）/删除（confirmDanger 替代原生 confirm，
 * deleteMemory 先置 REVOKED）；保存表单=会话消息「保存为记忆」入口回填
 * （POST memories 携带当前会话 ID，默认 PRIVATE——接口稿 §5.4）。
 */
import { computed, ref } from "vue";
import {
  createMemory,
  deleteMemory,
  getMemory,
  listMemories,
  updateMemory,
  type MemoryKind,
  type MockMemoryRow,
} from "../../shared/mock-memories";
import { confirmDanger } from "../../shared/components/DangerConfirm";

type MemoryListItem = Omit<MockMemoryRow, "demo">;
type MemoryDetail = { memoryId: string; content: string; kind: MemoryKind; scope: string; tags: string[]; version: number; status: string };

const props = defineProps<{ sessionId: string }>();

const rows = ref<MemoryListItem[]>([]);
const selected = ref<MemoryDetail | null>(null);
const error = ref("");
const notice = ref("");
const query = ref("");

const refresh = () => {
  const page = listMemories({ q: query.value });
  rows.value = page.memories;
  if (selected.value) {
    const fresh = getMemory(selected.value.memoryId);
    selected.value = "error" in fresh ? null : fresh;
  }
};
refresh();

/* 保存表单（从会话消息「保存为记忆」回填正文，提交当前会话 ID）。 */
const saveOpen = ref(false);
const saveForm = ref({ content: "", kind: "PREFERENCE" as MemoryKind, scope: "PRIVATE" as "PRIVATE" | "SHARED", tags: "" });
const saveSessionId = ref("");
const openSaveForm = (prefill: { content: string; sessionId: string }) => {
  saveForm.value = { content: prefill.content, kind: "PREFERENCE", scope: "PRIVATE", tags: "" };
  saveSessionId.value = prefill.sessionId;
  saveOpen.value = true;
  selected.value = null;
};
defineExpose({ openSaveForm });

const tagsOf = (text: string) =>
  text.split(/[,，;；\s]+/).map((t) => t.trim()).filter(Boolean);

async function submitSave() {
  error.value = "";
  notice.value = "";
  const res = createMemory({
    requestId: crypto.randomUUID(),
    content: saveForm.value.content,
    kind: saveForm.value.kind,
    scope: saveForm.value.scope,
    tags: tagsOf(saveForm.value.tags),
    sessionId: saveSessionId.value || undefined,
  });
  if ("error" in res) {
    error.value = res.userMessage || res.error;
    return;
  }
  saveOpen.value = false;
  notice.value = `已保存 ${res.memoryId}`;
  refresh();
}

/* 查看/修改/删除。 */
const editing = ref(false);
const editForm = ref({ content: "", tags: "" });
const showDetail = async (row: MemoryListItem) => {
  error.value = "";
  const res = getMemory(row.memoryId);
  if ("error" in res) {
    error.value = "正文读取失败：该条目不可见或已撤销";
    return;
  }
  selected.value = res;
  editing.value = false;
};
const startEdit = () => {
  if (!selected.value) return;
  editForm.value = { content: selected.value.content, tags: selected.value.tags.join(", ") };
  editing.value = true;
};
const submitEdit = () => {
  if (!selected.value) return;
  error.value = "";
  const res = updateMemory(selected.value.memoryId, {
    content: editForm.value.content,
    tags: tagsOf(editForm.value.tags),
    expectedVersion: selected.value.version,
  });
  if ("error" in res) {
    error.value = res.error === "MEMORY_VERSION_CONFLICT"
      ? "记忆已被其他修改更新（版本冲突），已重新加载，请基于最新内容再改。"
      : "修改失败";
    refresh();
    return;
  }
  selected.value = res;
  editing.value = false;
  notice.value = "已修改";
  refresh();
};
const remove = async (row: MemoryListItem) => {
  if (
    !(await confirmDanger({
      title: "删除记忆",
      message: `确认删除 ${row.memoryId}？删除后立即退出检索，不可恢复。`,
      confirmText: "删除",
    }))
  )
    return;
  error.value = "";
  const res = deleteMemory(row.memoryId, { requestId: crypto.randomUUID() });
  if ("error" in res) {
    error.value = "删除失败";
    return;
  }
  if (selected.value?.memoryId === row.memoryId) selected.value = null;
  notice.value = `已删除 ${row.memoryId}`;
  refresh();
};

const kindLabel = (kind: string) => (kind === "PREFERENCE" ? "偏好" : "经验");
const scopeLabel = computed(() => (props.sessionId ? props.sessionId : "未关联会话"));
</script>
<template>
  <div class="memories" data-kind="my-memories">
    <div class="head">
      <span class="muted">我的记忆（PRIVATE）</span>
      <el-button size="small" @click="saveOpen = !saveOpen">
        {{ saveOpen ? "收起保存" : "保存记忆" }}
      </el-button>
    </div>
    <p class="muted session-hint">保存将随当前会话提交：{{ scopeLabel }}</p>
    <form v-if="saveOpen" class="save-form" @submit.prevent="submitSave">
      <el-input v-model="saveForm.content" type="textarea" :rows="3" placeholder="记忆正文" />
      <div class="form-row">
        <el-select v-model="saveForm.kind" size="small" style="width: 7rem">
          <el-option value="PREFERENCE" label="偏好 PREFERENCE" />
          <el-option value="EXPERIENCE" label="经验 EXPERIENCE" />
        </el-select>
        <el-radio-group v-model="saveForm.scope" size="small">
          <el-radio-button value="PRIVATE">PRIVATE</el-radio-button>
          <el-tooltip content="USER 写范围默认仅 PRIVATE；共享写入依工作区策略（阶段二联调）">
            <span><el-radio-button value="SHARED" disabled>SHARED</el-radio-button></span>
          </el-tooltip>
        </el-radio-group>
      </div>
      <el-input v-model="saveForm.tags" size="small" placeholder="标签（逗号分隔）" />
      <el-button size="small" type="primary" native-type="submit">保存为记忆</el-button>
    </form>
    <el-input v-model="query" size="small" placeholder="查询关键词" clearable @input="refresh" />
    <ul class="rows">
      <li
        v-for="row in rows"
        :key="row.memoryId"
        :class="['row', { active: selected?.memoryId === row.memoryId }]"
        @click="showDetail(row)"
      >
        <span class="font-mono id">{{ row.memoryId }}</span>
        <span class="badge">{{ kindLabel(row.kind) }}</span>
        <span v-for="tag in row.tags" :key="tag" class="tag">{{ tag }}</span>
        <span class="meta muted">v{{ row.version }}</span>
        <el-button class="del" size="small" link type="danger" @click.stop="remove(row)">删除</el-button>
      </li>
    </ul>
    <p v-if="!rows.length" class="muted empty">暂无可见记忆（REVOKED 已退出检索）</p>
    <div v-if="selected" class="detail">
      <div class="detail-head">
        <span class="font-mono">{{ selected.memoryId }}</span>
        <span class="muted">v{{ selected.version }} · {{ selected.scope }}</span>
        <el-button size="small" link @click="editing ? (editing = false) : startEdit()">
          {{ editing ? "取消修改" : "修改" }}
        </el-button>
      </div>
      <p v-if="!editing" class="content">{{ selected.content }}</p>
      <form v-else class="save-form" @submit.prevent="submitEdit">
        <el-input v-model="editForm.content" type="textarea" :rows="3" />
        <el-input v-model="editForm.tags" size="small" placeholder="标签（逗号分隔）" />
        <el-button size="small" type="primary" native-type="submit">提交修改（v{{ selected.version }}）</el-button>
      </form>
    </div>
    <p v-if="notice" class="ok" role="status">{{ notice }}</p>
    <p v-if="error" class="err" role="alert">{{ error }}</p>
  </div>
</template>
<style scoped>
.memories {
  display: flex;
  flex-direction: column;
  gap: var(--sp-2);
  font-size: var(--text-meta);
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.session-hint {
  margin: 0;
}
.save-form {
  display: grid;
  gap: var(--sp-2);
  padding: var(--sp-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}
.form-row {
  display: flex;
  gap: var(--sp-2);
  align-items: center;
}
.rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--sp-1);
}
.row {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  padding: var(--sp-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  cursor: pointer;
  flex-wrap: wrap;
}
.row.active {
  border-color: var(--brand-primary);
}
.row .id {
  color: var(--ink);
}
.badge,
.tag {
  padding: 0 var(--sp-2);
  border-radius: 999px;
  border: 1px solid var(--line);
  color: var(--nav-muted);
}
.del {
  margin-left: auto;
}
.detail {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  padding: var(--sp-2);
  display: grid;
  gap: var(--sp-2);
}
.detail-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}
.content {
  margin: 0;
  line-height: 1.6;
  white-space: pre-wrap;
}
.empty,
.ok,
.err {
  margin: 0;
}
.ok {
  color: var(--st-ok-text);
}
.err {
  color: var(--st-fail-text);
}
</style>
