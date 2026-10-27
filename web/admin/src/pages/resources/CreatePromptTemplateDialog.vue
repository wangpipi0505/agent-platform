<script setup lang="ts">
defineProps<{ modelValue: boolean; form: any; busy: boolean }>();
const emit = defineEmits<{
  (e: "update:modelValue", value: boolean): void;
  (e: "save"): void;
}>();
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="创建提示词模板"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <el-form-item label="模板编码">
        <el-input v-model="form.code" />
      </el-form-item>
      <el-form-item label="模板名称">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="模板内容">
        <el-input v-model="form.content" type="textarea" :rows="10" />
      </el-form-item>
      <el-form-item label="模板变量">
        <div style="width: 100%">
          <div
            v-for="(variable, index) in form.variables"
            :key="index"
            class="row"
          >
            <el-input v-model="variable.name" placeholder="变量名称" />
            <el-select v-model="variable.type">
              <el-option
                v-for="type in [
                  'string',
                  'integer',
                  'number',
                  'boolean',
                  'object',
                  'array',
                ]"
                :key="type"
                :value="type"
              />
            </el-select>
            <el-checkbox v-model="variable.required">必填</el-checkbox>
            <el-button text @click="form.variables.splice(index, 1)"
              >移除</el-button
            >
          </div>
          <el-button
            @click="
              form.variables.push({
                name: '',
                type: 'string',
                required: true,
              })
            "
            >添加变量</el-button
          >
        </div>
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
