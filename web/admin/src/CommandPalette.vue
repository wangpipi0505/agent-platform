<script setup lang="ts">
// F1-2 全局命令面板（前端稿 §3.2/§4）：Ctrl+K/⌘K 唤起（AppShell 监听）、输入过滤
// pageAccess 条目（26 条全量，含非菜单参数页——按父模块路径跳转）、↑↓ 选择、
// Enter 跳转（router.push(path)）、Esc/点击遮罩关闭。样式只用 tokens 变量（F1-1 词表）。
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { pageAccess, type PageAccessEntry } from "./page-access";

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ close: [] }>();

const router = useRouter();
const query = ref("");
const active = ref(0);
const input = ref<HTMLInputElement | null>(null);

// Esc 关闭不依赖输入框焦点（键盘可达：焦点落在列表/遮罩时同样生效）
function onWindowKey(event: KeyboardEvent) {
  if (props.open && event.key === "Escape") emit("close");
}
onMounted(() => window.addEventListener("keydown", onWindowKey));
onUnmounted(() => window.removeEventListener("keydown", onWindowKey));

const results = computed<PageAccessEntry[]>(() => {
  const q = query.value.trim().toLowerCase();
  if (!q) return pageAccess;
  return pageAccess.filter(
    (entry) =>
      entry.title.toLowerCase().includes(q) ||
      entry.key.toLowerCase().includes(q) ||
      entry.path.toLowerCase().includes(q) ||
      entry.area.toLowerCase().includes(q),
  );
});

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    query.value = "";
    active.value = 0;
    void nextTick(() => input.value?.focus());
  },
);
watch(results, () => {
  active.value = 0;
});

function move(step: number) {
  const max = results.value.length;
  if (!max) return;
  active.value = (active.value + step + max) % max;
}

function go(index = active.value) {
  const entry = results.value[index];
  if (!entry) return;
  emit("close");
  void router.push(entry.path);
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="palette-mask" @click.self="emit('close')">
      <div class="palette" role="dialog" aria-label="命令面板" aria-modal="true">
        <input
          ref="input"
          v-model="query"
          class="palette-input"
          type="text"
          placeholder="搜索页面：智能体 / 技能 / 运行 / 菜单项…"
          aria-label="搜索页面"
          @keydown.down.prevent="move(1)"
          @keydown.up.prevent="move(-1)"
          @keydown.enter.prevent="go()"
          @keydown.esc.prevent="emit('close')"
        />
        <ul class="palette-list">
          <li
            v-for="(entry, i) in results"
            :key="entry.key"
            :class="{ active: i === active }"
            @mouseenter="active = i"
            @click="go(i)"
          >
            <span class="p-title">{{ entry.title }}</span>
            <span class="p-area">{{ entry.area }}</span>
            <span class="p-path">{{ entry.path }}</span>
          </li>
        </ul>
        <div class="palette-hint">
          {{ results.length ? "↑↓ 选择 · Enter 跳转 · Esc 关闭" : "无匹配条目" }}
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.palette-mask {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding: 15vh var(--sp-4) 0;
  background: var(--el-mask-color, color-mix(in srgb, var(--text-primary) 40%, transparent));
}
.palette {
  width: min(90vw, 40rem);
  background: var(--bg-surface-1);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius);
  box-shadow: var(--elev-3);
  overflow: hidden;
}
.palette-input {
  width: 100%;
  padding: var(--sp-3) var(--sp-4);
  font-size: var(--text-body);
  color: var(--text-primary);
  background: var(--bg-surface-2);
  border: 0;
  border-bottom: 1px solid var(--border-subtle);
}
.palette-input:focus {
  outline: none;
  border-bottom-color: var(--brand);
}
.palette-list {
  list-style: none;
  margin: 0;
  padding: var(--sp-2);
  max-height: 16rem;
  overflow-y: auto;
}
.palette-list li {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--radius);
  cursor: pointer;
}
.palette-list li.active {
  background: var(--brand-subtle);
}
.p-title {
  color: var(--text-primary);
}
.p-area,
.p-path {
  font-size: var(--text-meta);
  color: var(--text-tertiary);
}
.p-path {
  margin-left: auto;
  font-family: var(--font-mono);
}
.palette-hint {
  padding: var(--sp-2) var(--sp-4);
  font-size: var(--text-meta);
  color: var(--text-tertiary);
  border-top: 1px solid var(--border-subtle);
}
</style>
