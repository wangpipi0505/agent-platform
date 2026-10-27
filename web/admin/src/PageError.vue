<script setup lang="ts">
defineProps<{ error?: string; trace?: string }>();
</script>

<template>
  <div v-if="error" class="error" role="alert">
    {{ error }}
    <p class="muted">请检查输入与服务状态后重试；业务数据以服务端为准。</p>
    <details v-if="trace">
      <summary>追踪信息</summary>
      {{ trace }}
    </details>
  </div>
</template>
