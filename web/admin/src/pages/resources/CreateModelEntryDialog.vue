<script setup lang="ts">
defineProps<{
  modelValue: boolean;
  form: any;
  providers: any[];
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
    title="添加模型条目"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <el-form-item label="所属供应商" required>
        <el-select
          v-model="form.providerId"
          filterable
          placeholder="选择已登记的供应商"
        >
          <el-option
            v-for="provider in providers"
            :key="provider.id"
            :value="provider.id"
            :label="provider.name"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="模型调用名称" required>
        <el-input
          v-model="form.modelName"
          placeholder="供应商接口使用的模型名称"
        />
      </el-form-item>
      <el-form-item label="显示名称">
        <el-input v-model="form.displayName" />
      </el-form-item>
      <el-form-item label="上下文窗口">
        <el-input-number v-model="form.contextWindow" :min="1" />
      </el-form-item>
      <el-form-item label="最大输出 Token">
        <el-input-number v-model="form.maxOutputTokens" :min="1" />
      </el-form-item>
      <el-form-item label="模型能力" required>
        <el-checkbox-group v-model="form.capabilities">
          <el-checkbox label="chat">对话</el-checkbox>
          <el-checkbox label="tools">工具调用</el-checkbox>
          <el-checkbox label="json_mode">JSON 输出</el-checkbox>
          <el-checkbox label="vision">视觉</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <p class="muted">
        能力值必须为小写；至少包含 chat。启用技能工具或结构化输出时，按实际能力勾选
        tools / json_mode，否则发布或运行会被服务端拒绝。
      </p>
      <el-form-item label="Tokenizer 地址">
        <el-input
          v-model="form.tokenizerUrl"
          placeholder="可选，例如 https://模型网关/tokenize"
        />
      </el-form-item>
      <p class="muted">
        留空时仅支持 OpenAI 官方模型名。云端 Qwen/DeepSeek
        等自定义模型名需要提供与模型网关同源、同协议的 /tokenize
        服务，否则受管对话无法启动。
      </p>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="busy" @click="emit('save')"
        >保存</el-button
      >
    </template>
  </el-dialog>
</template>
