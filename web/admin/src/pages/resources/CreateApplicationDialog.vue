<script setup lang="ts">
import BusinessCategorySelect from "../../BusinessCategorySelect.vue";
import { ref, watch } from "vue";
import { useApi } from "../../../../shared/api";
const props = defineProps<{
  modelValue: boolean;
  form: any;
  busy: boolean;
  templateSelectionEnabled: boolean;
}>();
const api = useApi();
type Template = { code: string; name: string };
type Version = { versionNo: number; sha256: string };
const templates = ref<Template[]>([]);
const versions = ref<Version[]>([]);
const loading = ref(false);
const templateError = ref("");
let sequence = 0;
let versionSequence = 0;
async function loadTemplates() {
  const current = ++sequence;
  loading.value = true;
  templateError.value = "";
  try {
    const rows = await api<Template[]>("/admin/prompt-templates");
    if (!Array.isArray(rows)) throw new Error("模板列表响应无效");
    if (current === sequence) templates.value = rows.filter((row) => typeof row.code === "string");
  } catch (error: unknown) {
    if (current === sequence) templateError.value = error instanceof Error ? error.message : "模板加载失败，请重试";
  } finally { if (current === sequence) loading.value = false; }
}
async function loadVersions(code: string) {
  const current = ++versionSequence;
  versions.value = [];
  templateError.value = "";
  if (!code) return;
  loading.value = true;
  try {
    const rows = await api<Array<Version & { status: string }>>(`/admin/prompt-templates/${encodeURIComponent(code)}/versions`);
    if (!Array.isArray(rows)) throw new Error("模板版本响应无效");
    if (current === versionSequence) versions.value = rows.filter((row) => row.status === "PUBLISHED" && Number.isInteger(row.versionNo) && /^[0-9a-f]{64}$/.test(row.sha256));
  } catch (error: unknown) {
    if (current === versionSequence) templateError.value = error instanceof Error ? error.message : "版本加载失败，请重试";
  } finally { if (current === versionSequence) loading.value = false; }
}
watch(() => props.modelValue, (open) => {
  sequence++; versionSequence++;
  templates.value = []; versions.value = []; loading.value = false; templateError.value = "";
  if (open && props.templateSelectionEnabled) void loadTemplates();
});
watch(() => props.form.promptTemplateRef?.code, (code: string | undefined) => {
  if (props.form.promptTemplateRef) props.form.promptTemplateRef.version = undefined;
  if (props.modelValue && props.templateSelectionEnabled) void loadVersions(code || "");
});
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
      <el-form-item label="智能体名称" required>
        <el-input v-model="form.name" maxlength="100" />
      </el-form-item>
      <el-form-item label="唯一编码" required>
        <el-input v-model="form.code" placeholder="小写字母、数字与下划线" />
      </el-form-item>
      <el-form-item label="应用说明">
        <el-input v-model="form.description" type="textarea" />
      </el-form-item>
      <template v-if="templateSelectionEnabled">
        <el-form-item label="提示词来源">
          <el-radio-group v-model="form.promptMode">
            <el-radio value="TEMPLATE">选择已发布模板</el-radio>
            <el-radio value="INLINE">稍后配置内联提示词</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="form.promptMode === 'TEMPLATE'">
          <el-form-item label="模板" required>
            <el-select v-model="form.promptTemplateRef.code" :loading="loading" clearable filterable placeholder="请选择模板">
              <el-option v-for="item in templates" :key="item.code" :value="item.code" :label="item.name || item.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="精确版本" required>
            <el-select v-model="form.promptTemplateRef.version" :disabled="!form.promptTemplateRef.code || loading" clearable placeholder="请选择已发布版本">
              <el-option v-for="item in versions" :key="item.versionNo" :value="item.versionNo" :label="`v${item.versionNo} · ${item.sha256.slice(0, 12)}`" />
            </el-select>
          </el-form-item>
          <p class="muted">模板作为提示词主来源。服务端固定所选版本与摘要；后续模板版本不会替换这次选择。</p>
          <p v-if="form.promptTemplateRef.code && !loading && !templateError && !versions.length" class="muted">此模板暂无可用的已发布版本，可选择其他模板或稍后配置。</p>
          <p v-if="templateError" class="error" role="alert">{{ templateError }} <el-button link @click="form.promptTemplateRef.code ? loadVersions(form.promptTemplateRef.code) : loadTemplates()">重试</el-button></p>
        </template>
      </template>
      <el-form-item label="专业领域">
        <el-input v-model="form.domain" />
      </el-form-item>
      <el-form-item label="业务分类">
        <BusinessCategorySelect v-model="form.bizCategory" />
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
