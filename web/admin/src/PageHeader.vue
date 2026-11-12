<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { pageChrome, PAGE_ACTIONS_TARGET } from "./page-chrome";

// UI-02：props 与 refresh 事件与旧版完全兼容（title/eyebrow/subtitle/busy）。
const props = defineProps<{
  title: string;
  eyebrow?: string;
  subtitle?: string;
  busy?: boolean;
}>();
defineEmits<{ (e: "refresh"): void }>();
// UI-02：AppShell 顶栏提供固定挂载点，存在时本页操作以 Teleport 迁入顶栏——
// 插槽内容留在本组件子树内，事件、loading 与 v-permission 权限指令随之保留；
// 标题/副题同步给壳层上下文（顶栏左侧）。无壳层（独立页或无 AppShell 嵌入）时
// 保留本地页头回退，行为与旧版一致。
const shellMode = ref(false);

function syncChrome() {
  if (!shellMode.value) return;
  pageChrome.title = props.title;
  // 页面未声明 eyebrow 时沿用路由 afterEach 依据访问表写入的分组，不能再回退成固定“平台管理”。
  if (props.eyebrow) pageChrome.eyebrow = props.eyebrow;
  pageChrome.subtitle = props.subtitle || "";
}

onMounted(() => {
  shellMode.value = !!document.getElementById(PAGE_ACTIONS_TARGET);
  syncChrome();
});
watch(
  () => [props.title, props.eyebrow, props.subtitle],
  () => syncChrome(),
);
</script>
<template>
  <Teleport v-if="shellMode" :to="`#${PAGE_ACTIONS_TARGET}`">
    <div class="actions">
      <slot name="actions" />
      <el-button :loading="busy" @click="$emit('refresh')">刷新</el-button>
    </div>
  </Teleport>
  <div v-else class="pagehead">
    <div>
      <div class="muted">{{ eyebrow || "平台管理" }}</div>
      <h1>{{ title }}</h1>
      <span class="muted">{{ subtitle }}</span>
    </div>
    <div class="actions">
      <slot name="actions" />
      <el-button :loading="busy" @click="$emit('refresh')">刷新</el-button>
    </div>
  </div>
</template>
