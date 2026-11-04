<script setup lang="ts">
/**
 * SEM-09 接入向导·语义绑定步骤。
 * 业务人员只在“当前正式发布已经允许的对象/动作/扩展”中勾选，不写稳定 JSON。
 * 表单不提供授权、范围或来源字段；勾选动作不会扩大源权限。
 */
import { computed, reactive, ref, watch } from "vue";
import {
  buildSemanticBindingRequest,
  canReadSemantics,
  canWriteSemantics,
  filterProjection,
  SemanticFormError,
  type DefinitionProjectionLike,
  type SemanticBindingRequest,
  type SemanticFactField,
  type SemanticSelection,
} from "../../../../shared/semantic-runtime";

const props = defineProps<{
  selection: SemanticSelection;
  projection?: DefinitionProjectionLike | null;
  roles?: string[];
}>();
const emit = defineEmits<{ submit: [request: SemanticBindingRequest] }>();

const roles = computed(() => props.roles ?? []);
const readable = computed(() => canReadSemantics(roles.value));
const writable = computed(() => canWriteSemantics(roles.value));
const visible = computed(() =>
  filterProjection(props.projection, {
    canRead: readable.value,
    allowedActions: props.selection.actions,
    allowedObjectTypes: props.selection.objectTypes,
  }),
);

const form = reactive({
  objectType: "",
  opCode: "",
  extensionId: "",
  skillCode: "",
  skillVersion: 1,
  selectedFacts: [] as string[],
});
const error = ref("");

const extensions = computed(() =>
  props.selection.extensions.filter(
    (item) =>
      item.status === "ACTIVE" &&
      item.profile === "read-only@1.0" &&
      (!form.opCode || item.opCode === form.opCode),
  ),
);
const currentExtension = computed(() => extensions.value.find((item) => item.id === form.extensionId));
const availableFacts = computed<SemanticFactField[]>(
  () => currentExtension.value?.descriptor.factFields ?? [],
);

watch(
  () => props.selection,
  (selection) => {
    form.objectType = selection.objectTypes[0] ?? "";
    form.opCode = selection.actions[0] ?? "";
    form.extensionId = "";
    form.selectedFacts = [];
    error.value = "";
  },
  { immediate: true },
);
watch(
  () => form.opCode,
  () => {
    if (!extensions.value.some((item) => item.id === form.extensionId)) form.extensionId = "";
    form.selectedFacts = [];
  },
);

function toggleFact(key: string) {
  form.selectedFacts = form.selectedFacts.includes(key)
    ? form.selectedFacts.filter((item) => item !== key)
    : [...form.selectedFacts, key];
}

function submit() {
  error.value = "";
  try {
    const extension = currentExtension.value;
    if (!extension) throw new SemanticFormError("extensionId", "请选择一个已激活的语义扩展");
    const factFields: SemanticFactField[] = availableFacts.value.filter((fact) =>
      form.selectedFacts.includes(`${fact.kind}:${fact.field}`),
    );
    const request = buildSemanticBindingRequest(
      {
        releaseRef: props.selection.releaseRef,
        objectType: form.objectType,
        opCode: form.opCode,
        extensionId: form.extensionId,
        skillCode: form.skillCode,
        skillVersion: form.skillVersion,
        factFields: factFields.length ? factFields : extension.descriptor.factFields,
        ruleRefs: extension.descriptor.ruleRefs,
      },
      props.selection,
    );
    emit("submit", request);
  } catch (e) {
    error.value = e instanceof SemanticFormError ? e.message : "绑定配置未通过校验";
  }
}
</script>

<template>
  <el-form v-if="readable" label-position="top" class="semantic-binding-step">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="只读语义绑定"
      description="这里选择的对象、动作与扩展都来自当前正式发布；绑定不会改变源系统权限，实际读取仍按服务端授权执行。"
    />
    <el-form-item label="对象类型" required>
      <el-select v-model="form.objectType" placeholder="选择发布中允许的对象">
        <el-option
          v-for="object in visible.objects"
          :key="object.ref.id"
          :value="object.ref.name"
          :label="`${object.ref.name}（${object.ref.id}）`"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="动作（仅显示已授权操作）" required>
      <el-select v-model="form.opCode" placeholder="选择动作">
        <el-option v-for="code in props.selection.actions" :key="code" :value="code" :label="code" />
      </el-select>
    </el-form-item>
    <el-form-item label="语义扩展（仅 ACTIVE）" required>
      <el-select v-model="form.extensionId" placeholder="选择已激活扩展">
        <el-option
          v-for="extension in extensions"
          :key="extension.id"
          :value="extension.id"
          :label="`${extension.id}（${extension.opCode}）`"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="可投影事实字段">
      <el-checkbox-group>
        <el-checkbox
          v-for="fact in availableFacts"
          :key="`${fact.kind}:${fact.field}`"
          :model-value="form.selectedFacts.includes(`${fact.kind}:${fact.field}`)"
          :label="`${fact.kind}:${fact.field}`"
          @change="toggleFact(`${fact.kind}:${fact.field}`)"
        >
          {{ fact.field }}（{{ fact.kind === "attribute" ? "属性" : "关系" }}）
        </el-checkbox>
      </el-checkbox-group>
      <p class="muted">不勾选时使用扩展声明的字段集合；事实仍由服务端按授权回源读取。</p>
    </el-form-item>
    <el-form-item label="技能编码" required>
      <el-input v-model="form.skillCode" placeholder="例如 customers.read" maxlength="64" />
    </el-form-item>
    <el-form-item label="技能版本" required>
      <el-input-number v-model="form.skillVersion" :min="1" :step="1" :precision="0" />
    </el-form-item>
    <el-form-item label="校验 profile">
      <el-tag type="info">read-only@1.0（固定）</el-tag>
    </el-form-item>
    <el-alert v-if="visible.attention.length" type="warning" :closable="false" :title="visible.attention.join('；')" />
    <el-alert v-if="!writable" type="warning" :closable="false" title="当前账号只能查看，不能保存语义绑定" />
    <el-alert v-if="error" type="error" :closable="false" :title="error" />
    <el-button type="primary" :disabled="!writable" @click="submit">保存只读绑定</el-button>
  </el-form>
  <el-alert v-else type="warning" :closable="false" :title="visible.reason" />
</template>

<style scoped>
.semantic-binding-step { display: grid; gap: 8px; max-width: 640px; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; margin: 4px 0 0; }
</style>
