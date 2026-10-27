<script setup lang="ts">
defineProps<{
  modelValue: boolean;
  form: any;
  busy: boolean;
}>();
const emit = defineEmits<{
  (e: "update:modelValue", value: boolean): void;
  (e: "save"): void;
}>();
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="创建智能体"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <el-form-item label="应用类型">
        <el-radio-group v-model="form.type">
          <el-radio-button value="CHAT">对话智能体</el-radio-button>
          <el-radio-button value="FLOW">流程智能体</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <p class="muted">
        对话型面向问答与工具调用；流程型通过画布确定步骤与分支。
      </p>
      <el-form-item label="应用名称" required>
        <el-input v-model="form.name" maxlength="100" />
      </el-form-item>
      <el-form-item label="唯一编码" required>
        <el-input v-model="form.code" placeholder="小写字母、数字与下划线" />
      </el-form-item>
      <el-form-item label="应用说明">
        <el-input v-model="form.description" type="textarea" />
      </el-form-item>
      <el-form-item label="专业领域">
        <el-input v-model="form.domain" />
      </el-form-item>
      <el-form-item label="业务分类">
        <el-input v-model="form.bizCategory" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="emit('save')"
        >保存</el-button
      >
    </template>
  </el-dialog>
</template>
