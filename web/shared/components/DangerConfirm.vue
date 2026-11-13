<script setup lang="ts">
// F1-3（台账 §7.5）：DangerConfirm 声明式组件——页面内嵌确认场景使用
// （v-model 控制 + confirm 事件放行）；流程式调用请直接用本目录 DangerConfirm.ts
// 导出的 confirmDanger()（替代原生 confirm 的主通道）。
defineOptions({ name: "DangerConfirm" });
withDefaults(
  defineProps<{
    modelValue?: boolean;
    title?: string;
    description?: string;
    confirmText?: string;
    cancelText?: string;
    /** 危险态：确认钮红色主题（默认 true） */
    danger?: boolean;
  }>(),
  {
    modelValue: false,
    title: "请确认",
    description: "",
    confirmText: "确认",
    cancelText: "取消",
    danger: true,
  },
);
const emit = defineEmits<{
  (e: "update:modelValue", value: boolean): void;
  (e: "confirm"): void;
}>();
function close() {
  emit("update:modelValue", false);
}
function onConfirm() {
  emit("confirm");
  close();
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    :class="danger ? 'danger-confirm-panel is-danger' : 'danger-confirm-panel'"
    width="26rem"
    append-to-body
    @update:model-value="close()"
  >
    <p class="danger-confirm-text">{{ description }}</p>
    <slot />
    <template #footer>
      <el-button @click="close()">{{ cancelText }}</el-button>
      <el-button type="primary" @click="onConfirm()">{{ confirmText }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.danger-confirm-text {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.6;
}
</style>

<style>
/* ElMessageBox/ElDialog 均 Teleport 到 body：红色主题类必须是全局样式。
   取色沿用 tokens.css 危险按钮既有深红实底口径（#b91c1c，保白字对比度）。 */
.danger-confirm-dialog.is-danger .el-message-box__btns .el-button--primary,
.danger-confirm-panel.is-danger .el-dialog__footer .el-button--primary {
  --el-button-bg-color: #b91c1c;
  --el-button-border-color: #b91c1c;
  --el-button-hover-bg-color: #dc2626;
  --el-button-hover-border-color: #dc2626;
  --el-button-active-bg-color: #991b1b;
  --el-button-active-border-color: #991b1b;
  --el-button-text-color: #ffffff;
  --el-button-hover-text-color: #ffffff;
}
html.light .danger-confirm-dialog.is-danger .el-message-box__btns .el-button--primary,
html.light .danger-confirm-panel.is-danger .el-dialog__footer .el-button--primary {
  --el-button-bg-color: #b91c1c;
  --el-button-border-color: #b91c1c;
}
</style>
