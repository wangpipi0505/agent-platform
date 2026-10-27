<script setup lang="ts">
const props = defineProps<{
  source: string;
  code: string;
  name: string;
  busy: boolean;
  error: string;
}>();
const emit = defineEmits<{
  (e: "update:source", value: string): void;
  (e: "update:code", value: string): void;
  (e: "update:name", value: string): void;
  (e: "copy"): void;
}>();

function close() {
  if (!props.busy) emit("update:source", "");
}
</script>

<template>
  <el-dialog
    :model-value="!!source"
    class="application-copy-dialog"
    title="复制为新草稿"
    width="min(480px, 94vw)"
    :close-on-click-modal="!busy"
    :show-close="!busy"
    @update:model-value="close"
  >
    <p class="muted">
      复制 {{ source }} 的当前草稿配置，创建独立的新应用。
    </p>
    <div v-if="error" class="error" role="alert">{{ error }}</div>
    <el-form label-position="top" @submit.prevent="emit('copy')">
      <el-form-item label="新应用编码" required>
        <el-input
          :model-value="code"
          placeholder="小写字母、数字与下划线"
          @update:model-value="emit('update:code', $event)"
        />
      </el-form-item>
      <el-form-item label="新应用名称" required>
        <el-input
          :model-value="name"
          maxlength="100"
          @update:model-value="emit('update:name', $event)"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="busy" @click="close">取消</el-button>
      <el-button type="primary" :loading="busy" @click="emit('copy')"
        >创建新草稿</el-button
      >
    </template>
  </el-dialog>
</template>
