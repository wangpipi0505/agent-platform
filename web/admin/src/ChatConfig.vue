<script setup lang="ts">
import { ref, onMounted, watch } from "vue";
import { readDraft, writeDraft } from "../../shared/drafts";
import { createApi, useApi, useAuth } from "../../shared/api";
import SkillReferencePicker from "./SkillReferencePicker.vue";
import OntologyPicker from './OntologyPicker.vue';
const props = defineProps<{
  config: any;
  appCode?: string;
  bizCategory?: string;
  draftKey?: string;
  draftRev?: number;
}>();
const emit = defineEmits<{ save: [config: any] }>();
const auth = useAuth();
const api = useApi();
const value = ref(JSON.parse(JSON.stringify(props.config || {})));
const recoverable = ref(readDraft(props.draftKey, props.draftRev));
watch(
  value,
  (v) => {
    if (props.draftKey)
      try {
        writeDraft(props.draftKey, props.draftRev!, v);
      } catch {
        error.value = "本地草稿空间不足，请及时保存配置";
      }
  },
  { deep: true },
);
watch(
  () => props.draftRev,
  () => {
    if (props.draftKey) localStorage.removeItem("draft:" + props.draftKey);
    recoverable.value = null;
  },
);
const models = ref<any[]>([]);
const templates = ref<any[]>([]);
const skills = ref<any[]>([]);
const error = ref("");
const advanced = ref(JSON.stringify(props.config || {}, null, 2));
const showAdvanced = ref(false);
function applyConfig(source: any) {
  value.value = JSON.parse(JSON.stringify(source || {}));
  advanced.value = JSON.stringify(source || {}, null, 2);
  value.value.modelBinding ||= {};
  value.value.promptRefs ||= [];
  value.value.skillRefs ||= [];
  value.value.guard ||= { roles: [], attributes: {} };
  value.value.requiredLabels ||= [];
}
applyConfig(props.config);
// 壳层先挂空的 app 详情、再异步载入配置；只初始化一次会让表单永远为空。
// 以 draftRev 或配置对象变化为准重建，避免覆盖用户正在编辑但尚未保存的内容。
watch(
  () => [props.draftRev, props.config] as const,
  () => applyConfig(props.config),
);
onMounted(async () => {
  try {
    const results = await Promise.allSettled([
      api("/admin/model-entries"),
      api("/admin/prompt-templates"),
      api("/admin/skills"),
    ]);
    for (const [i, r] of results.entries()) {
      if (r.status === "fulfilled")
        [models, templates, skills][i].value = r.value;
      else error.value += " " + r.reason.message;
    }
  } catch (e: any) {
    error.value = e.message;
  }
});
function save() {
  try {
    emit("save", showAdvanced.value ? JSON.parse(advanced.value) : value.value);
  } catch (e: any) {
    error.value = e.message;
  }
}
function clearPromptSha(reference: { sha256?: unknown }) {
  delete reference.sha256;
}
</script>
<template>
  <div class="panel form">
    <div v-if="recoverable" class="row">
      <span>发现同一修订的未提交配置。</span
      ><el-button
        @click="
          value = recoverable;
          recoverable = null;
        "
        >恢复配置草稿</el-button
      >
    </div>
    <h2>对话配置</h2>
    <p class="muted">
      绑定模型、提示词、技能与本体。已有配置会完整保留，发布时检查引用版本。
    </p>
    <p v-if="error" class="error">配置选项加载失败：{{ error }}</p>
    <el-form label-position="left" label-width="8rem"
      ><el-form-item label="推理模型"
        ><el-select
          v-model="value.modelBinding.modelEntryId"
          clearable
          filterable
          placeholder="选择模型条目"
          ><el-option
            v-for="m in models"
            :key="m.id"
            :value="m.id"
            :label="m.displayName || m.modelName" /></el-select></el-form-item
      ><el-form-item label="提示词模板"
        ><div style="width: 100%">
          <p v-if="value.promptSource === 'TEMPLATE'" class="muted">所选模板是提示词主来源。修改编码或版本后，保存时由服务端重新固定摘要；原运行快照保持不变。</p>
          <div v-for="(p, i) in value.promptRefs" :key="i" class="row">
            <el-select v-model="p.code" filterable placeholder="选择模板" @change="clearPromptSha(p)"
              ><el-option
                v-for="t in templates"
                :key="t.code"
                :value="t.code"
                :label="t.name || t.code" /></el-select
            ><el-input-number
              v-model="p.version"
              :min="1"
              aria-label="模板版本"
              @change="clearPromptSha(p)"
            /><el-button v-if="value.promptSource !== 'TEMPLATE'" text @click="value.promptRefs.splice(i, 1)"
              >移除</el-button
            >
          </div>
          <el-button v-if="value.promptSource !== 'TEMPLATE'" @click="value.promptRefs.push({ code: '', version: 1 })"
            >添加模板</el-button
          >
        </div></el-form-item
      ><el-form-item label="关联本体"><OntologyPicker v-model="value.ontology" /></el-form-item>
      <el-form-item label="可调用技能"
        ><div style="width: 100%">
          <div v-for="(s, i) in value.skillRefs" :key="i" class="row">
            <SkillReferencePicker v-model="value.skillRefs[i]" :skills="skills" :app-code="appCode" :biz-category="bizCategory" /><el-button text @click="value.skillRefs.splice(i, 1)"
              >移除</el-button
            >
          </div>
          <el-button @click="value.skillRefs.push({ code: '' })"
            >添加技能</el-button
          >
        </div></el-form-item
      ><el-form-item v-if="value.systemPrompt" label="系统提示词">
        <div style="width:100%">
          <el-input v-model="value.systemPrompt" type="textarea" :rows="7" aria-label="系统提示词" />
          <p class="muted">明确目标解析、工具顺序、本体规则、人工确认与结果表达；演示问法可在对话页直接选用。</p>
        </div>
      </el-form-item>
      <el-form-item label="允许角色"
        ><el-select
          v-model="value.guard.roles"
          multiple
          allow-create
          filterable
          placeholder="输入角色名称并回车"
          ><el-option
            v-for="r in ['ADMIN', 'EDITOR', 'APPROVER']"
            :key="r"
            :value="r" /></el-select></el-form-item
      ><el-form-item label="数据分类要求"
        ><el-select
          v-model="value.requiredLabels"
          multiple
          allow-create
          filterable
          placeholder="留空时仅允许 LOCAL 数据"
          ><el-option
            v-for="l in ['LOCAL', 'INTERNAL', 'PUBLIC']"
            :key="l"
            :value="l" /></el-select></el-form-item
      ><p class="muted">
        所选模型的供应商信任标签必须覆盖这里的要求。云端端点通常配置 PUBLIC；留空时服务端只允许
        LOCAL 端点，云端调用会返回 SYS-EGRESS-4031。
      </p></el-form
    ><el-collapse @change="advanced = JSON.stringify(value, null, 2)"
      ><el-collapse-item title="高级策略与完整配置"
        ><el-switch
          v-model="showAdvanced"
          active-text="使用完整配置编辑" /><el-input
          v-if="showAdvanced"
          v-model="advanced"
          type="textarea"
          :rows="12" /></el-collapse-item></el-collapse
    ><el-button
      type="primary"
      style="margin-top: 1rem"
      v-permission="'edit'"
      @click="save"
      >保存配置</el-button
    >
  </div>
</template>
