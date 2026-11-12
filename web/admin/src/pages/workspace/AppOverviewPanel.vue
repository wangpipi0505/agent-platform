<script setup lang="ts">
import BusinessCategorySelect from "../../BusinessCategorySelect.vue";
import { computed, onMounted, ref } from "vue";
import ApplicationHistory from "../../ApplicationHistory.vue";
import { useApi } from "../../../../shared/api";
import { displayTime, statusLabel } from "../resources/resource-format";

const props = defineProps<{
  app: any;
  code: string;
  versions: any[];
  busy: boolean;
  refreshKey: number;
}>();

const api = useApi();
const skills = ref<any[]>([]);
const models = ref<any[]>([]);
const templates = ref<any[]>([]);
onMounted(async () => {
  const results = await Promise.allSettled([
    api<any[]>("/admin/skills"),
    api<any[]>("/admin/model-entries"),
    api<any[]>("/admin/prompt-templates"),
  ]);
  if (results[0].status === "fulfilled") skills.value = results[0].value || [];
  if (results[1].status === "fulfilled") models.value = results[1].value || [];
  if (results[2].status === "fulfilled") templates.value = results[2].value || [];
});

// 概览直接回答「这个智能体用了什么」：模型、提示词、技能/工具各自解析成可读名称。
const bindingRows = computed(() => {
  const config = props.app?.config ?? {};
  const rows: any[] = [];
  const modelEntryId = config.modelBinding?.modelEntryId;
  if (modelEntryId) {
    const model = models.value.find((item) => item.id === modelEntryId);
    rows.push({
      kind: "推理模型",
      name: model?.displayName ?? model?.name ?? modelEntryId,
      code: model?.modelId ?? modelEntryId,
      version: model?.version ? `v${model.version}` : "—",
    });
  }
  for (const ref of config.promptRefs ?? []) {
    const template = templates.value.find((item) => item.code === ref.code);
    rows.push({
      kind: "提示词模板",
      name: template?.name ?? ref.code,
      code: ref.code,
      version: `v${ref.version ?? template?.currentVersion ?? 1}`,
    });
  }
  for (const ref of config.skillRefs ?? []) {
    const skill = skills.value.find((item) => item.code === ref.code);
    rows.push({
      kind: "技能 / 工具",
      name: skill?.name ?? ref.code,
      code: ref.code,
      version: `v${ref.version ?? skill?.currentVersion ?? 1}`,
    });
  }
  return rows;
});
const knowledgeBases = computed(() => props.app?.config?.knowledgeBases ?? []);
const flowNodeCount = computed(() => props.app?.definition?.nodes?.length ?? 0);
const emit = defineEmits<{
  (e: "lifecycle", action: string): void;
  (e: "save-metadata"): void;
  (e: "reload"): void;
}>();
const versionBusy=ref(false),versionError=ref('');
async function versionAction(versionNo:number,action:'restore'|'activate') {
  versionBusy.value=true;versionError.value='';
  try {await api(`/admin/apps/${encodeURIComponent(props.code)}/versions/${versionNo}/${action}`,'POST',{});emit('reload');}
  catch(problem){versionError.value=problem instanceof Error?problem.message:'版本操作失败';}
  finally {versionBusy.value=false;}
}
</script>

<template>
  <div>
    <div class="row">
      <span class="muted">应用信息与生命周期</span>
      <div class="actions">
        <el-button
          v-if="app.currentVersionNo && !['DISABLED', 'ARCHIVED'].includes(app.status)"
          v-permission="'edit'"
          @click="emit('lifecycle', 'disable')"
          >停用应用</el-button
        >
        <el-button
          v-if="app.status === 'DISABLED'"
          v-permission="'edit'"
          @click="emit('lifecycle', 'enable')"
          >启用应用</el-button
        >
        <el-button
          v-if="app.status === 'DISABLED'"
          type="danger"
          plain
          v-permission="'edit'"
          @click="emit('lifecycle', 'archive')"
          >归档应用</el-button
        >
      </div>
    </div>
    <div class="grid">
      <div class="panel">
        <div class="muted">生命周期</div>
        <h2>
          <span :class="['status', app.status]">{{
            app.statusLabel || statusLabel(app.status)
          }}</span>
          <small v-if="app.currentVersionNo && app.status === 'DRAFT'"
            >已发布 v{{ app.currentVersionNo }}，新草稿待发布</small
          >
        </h2>
      </div>
      <div class="panel">
        <div class="muted">草稿修订</div>
        <h2>{{ app.draftRev }}</h2>
      </div>
      <div class="panel">
        <div class="muted">当前发布版本</div>
        <h2>{{ app.currentVersionNo || "尚未发布" }}</h2>
      </div>
    </div>
    <div class="panel">
      <h2>这个智能体使用的模型、提示词与工具</h2>
      <el-table :data="bindingRows">
        <el-table-column prop="kind" label="类型" width="140" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="code" label="编码" width="220" />
        <el-table-column prop="version" label="版本" width="90" />
        <template #empty>尚未绑定模型、提示词或技能，请到「编排」页配置。</template>
      </el-table>
      <p class="muted">
        知识库：{{ knowledgeBases.length ? knowledgeBases.join("、") : "未绑定" }}
        <template v-if="app.type === 'FLOW'">
          · 流程节点 {{ flowNodeCount }} 个（见「编排」页）
        </template>
      </p>
    </div>
    <div class="panel">
      <el-collapse>
        <el-collapse-item title="编辑智能体名称与分类">
          <el-form label-position="left" label-width="6rem">
            <el-form-item label="名称"><el-input v-model="app.name" /></el-form-item>
            <el-form-item label="说明">
              <el-input v-model="app.description" type="textarea" />
            </el-form-item>
            <el-form-item label="专业领域">
              <el-input v-model="app.domain" />
            </el-form-item>
            <el-form-item label="业务分类">
              <BusinessCategorySelect v-model="app.bizCategory" />
            </el-form-item>
            <el-form-item label="标签">
              <el-select v-model="app.tags" multiple filterable allow-create>
                <el-option v-for="tag in app.tags || []" :key="tag" :value="tag" />
              </el-select>
            </el-form-item>
          </el-form>
          <el-button
            type="primary"
            :loading="busy"
            v-permission="'edit'"
            @click="emit('save-metadata')"
            >保存应用信息</el-button
          >
        </el-collapse-item>
      </el-collapse>
      <h2>版本记录</h2><p v-if="versionError" class="error" role="alert">{{versionError}}</p>
      <el-table :data="versions">
        <el-table-column prop="versionNo" label="版本" />
        <el-table-column prop="candidateSha256" label="候选摘要" />
        <el-table-column label="发布时间">
          <template #default="{ row }">{{ displayTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="版本操作" min-width="240"><template #default="{row}"><el-button v-permission="'edit'" text :disabled="busy||versionBusy" @click="versionAction(row.versionNo,'restore')">恢复为草稿</el-button><el-button v-permission="'edit'" text :disabled="busy||versionBusy||row.versionNo===app.currentVersionNo" @click="versionAction(row.versionNo,'activate')">{{row.versionNo===app.currentVersionNo?'当前默认版本':'设为默认版本'}}</el-button></template></el-table-column>
        <template #empty>尚无发布版本，请先完成配置、调试与评测。</template>
      </el-table>
    </div>
    <ApplicationHistory
      :app-code="code"
      :versions="versions"
      :revision="refreshKey"
    />
  </div>
</template>
