<script setup lang="ts">
import { useAuth } from "../../../../shared/api";

defineProps<{ modelValue: boolean; form: any; busy: boolean }>();
const emit = defineEmits<{
  (e: "update:modelValue", value: boolean): void;
  (e: "save"): void;
}>();
const auth = useAuth();
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    title="添加供应商"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-form label-position="top">
      <el-form-item
        v-for="key in ['code', 'name', 'protocol', 'baseUrl', 'secretRef']"
        :key="key"
        :label="
          {
            code: '编码',
            name: '名称',
            protocol: '协议',
            baseUrl: '服务地址',
            secretRef: '凭据引用（仅引用，不填密钥）',
          }[key]
        "
        :required="key !== 'secretRef'"
      >
        <el-input v-model="form[key]" />
      </el-form-item>
      <el-form-item v-if="auth.can('admin')" label="端点信任标签" required>
        <el-select
          v-model="form.trustLabels"
          multiple
          filterable
          allow-create
          placeholder="由管理员确认端点允许处理的数据分类"
        >
          <el-option
            v-for="label in ['LOCAL', 'INTERNAL', 'PUBLIC']"
            :key="label"
            :value="label"
          />
        </el-select>
      </el-form-item>
      <p class="muted">
        缺少数据分类时服务端仅允许具备 LOCAL
        信任标签的端点；请按真实部署声明。
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
