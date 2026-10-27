<script setup lang="ts">
defineProps<{
  title: string;
  eyebrow?: string;
  subtitle?: string;
  busy?: boolean;
}>();
defineEmits<{ (e: "refresh"): void }>();
</script>

<template>
  <div class="pagehead">
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
