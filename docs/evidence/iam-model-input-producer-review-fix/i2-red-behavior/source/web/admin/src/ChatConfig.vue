<script setup lang="ts">
import { ref, onMounted, watch } from "vue";
import { readDraft, writeDraft } from "../../shared/drafts";
import { createApi, useApi, useAuth } from "../../shared/api";
const props = defineProps<{
  config: any;
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
value.value.modelBinding ||= {};
value.value.promptRefs ||= [];
value.value.skillRefs ||= [];
value.value.guard ||= { roles: [], attributes: {} };
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
      绑定模型、提示词与技能。已有配置会完整保留，发布时检查引用版本。
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
          <div v-for="(p, i) in value.promptRefs" :key="i" class="row">
            <el-select v-model="p.code" filterable placeholder="选择模板"
              ><el-option
                v-for="t in templates"
                :key="t.code"
                :value="t.code"
                :label="t.name || t.code" /></el-select
            ><el-input-number
              v-model="p.version"
              :min="1"
              aria-label="模板版本"
            /><el-button text @click="value.promptRefs.splice(i, 1)"
              >移除</el-button
            >
          </div>
          <el-button @click="value.promptRefs.push({ code: '', version: 1 })"
            >添加模板</el-button
          >
        </div></el-form-item
      ><el-form-item label="可调用技能"
        ><div style="width: 100%">
          <div v-for="(s, i) in value.skillRefs" :key="i" class="row">
            <el-select v-model="s.code" filterable placeholder="选择技能"
              ><el-option
                v-for="t in skills"
                :key="t.code"
                :value="t.code"
                :label="t.name || t.code" /></el-select
            ><el-input-number
              v-model="s.version"
              :min="1"
              aria-label="技能版本"
            /><el-button text @click="value.skillRefs.splice(i, 1)"
              >移除</el-button
            >
          </div>
          <el-button @click="value.skillRefs.push({ code: '', version: 1 })"
            >添加技能</el-button
          >
        </div></el-form-item
      ><el-form-item label="允许角色"
        ><el-select
          v-model="value.guard.roles"
          multiple
          allow-create
          filterable
          placeholder="输入角色名称并回车"
          ><el-option
            v-for="r in ['ADMIN', 'EDITOR', 'APPROVER']"
            :key="r"
            :value="r" /></el-select></el-form-item></el-form
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
