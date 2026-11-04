<script setup lang="ts">
/** 可嵌入原向导/集成页；实际草稿是唯一配置权威，每次保存携带服务端draftRev。 */
import { computed, ref, watch } from 'vue';
import { useApi, useAuth } from '../../../../shared/api';
import {
  object,
  releaseSelection,
  bindingWrite,
  mergeGoal,
  addSkillRefs,
  type SkillChoice,
  type SemanticCatalog,
  type SemanticApp
} from '../../../../shared/semantic-configuration';
import type { GoalDeclaration, SemanticBindingRequest } from '../../../../shared/semantic-runtime';
import SemanticBindingStep from './SemanticBindingStep.vue';
import GoalCriteriaStep from './GoalCriteriaStep.vue';
const props = defineProps<{
  appCode: string;
  disabled?: boolean;
}>();
const emit = defineEmits<{
  saved: [
    app: SemanticApp
  ];
}>();
const api = useApi();
const auth = useAuth();
const app = ref<SemanticApp>();
const catalog = ref<SemanticCatalog>();
const skills = ref<SkillChoice[]>([]);
const releaseId = ref('');
const busy = ref(false);
const error = ref('');
const notice = ref('');
let generation = 0;
const selected = computed(() => catalog.value?.releases.find(item => item.releaseRef.releaseId === releaseId.value));
const selection = computed(() => selected.value ? releaseSelection(selected.value) : undefined);
const readonlySkills = computed(() => skills.value.filter(item => item.readonly && !item.sideEffect));
const writable = computed(() => auth.can('edit') && !props.disabled && !busy.value);
async function load() {
  // 读目录与技能可以并行；只有当前页面/已验主体仍有效时才能采用回执。
  const ticket = ++generation;
  const code = props.appCode;
  const session = auth.snapshot();
  app.value = undefined;
  catalog.value = undefined;
  skills.value = [];
  releaseId.value = '';
  error.value = '';
  notice.value = '';
  busy.value = false;
  if (!code || !auth.token)
    return;
  busy.value = true;
  try {
    const [loaded, directory, rows] = await Promise.all([
      api<SemanticApp>(`/admin/apps/${encodeURIComponent(code)}`),
      api<SemanticCatalog>('/admin/skills/semantic-catalog'),
      api<JsonRecord[]>('/admin/skills')
    ]);
    const choices = await Promise.all(rows.filter(item => item.status !== 'DISABLED').slice(0, 64).map(async (item) => {
      const detail = await api<JsonRecord>(`/admin/skills/${encodeURIComponent(String(item.code))}`);
      return (Array.isArray(detail.versions) ? detail.versions : []).map(object).filter(version => version.status === 'PUBLISHED').map(version => {
        const definition = object(version.definition);
        const execution = object(version.execution ?? definition.execution);
        return {
          code: String(item.code),
          version: Number(version.versionNo ?? version.version),
          sha256: String(version.sha256 ?? version.digest ?? ''),
          inputSchema: version.inputSchema ?? definition.inputSchema,
          outputSchema: version.outputSchema ?? definition.outputSchema,
          readonly: execution.readonly === true,
          sideEffect: execution.sideEffect !== false
        };
      });
    }));
    if (ticket !== generation || !session.current())
      return;
    if (!loaded.code || loaded.code !== code || !Number.isInteger(loaded.draftRev))
      throw Error('应用草稿回执不完整，请刷新重试');
    const rawRefs = object(loaded.config).skillRefs;
    const configuredRefs = Array.isArray(rawRefs) ? rawRefs.map(object) : [];
    app.value = {
      ...loaded,
      config: object(loaded.config)
    };
    catalog.value = directory;
    skills.value = choices.flat().filter((item, index, all) => {
      const ref = configuredRefs.find(ref => ref.code === item.code);
      return ref ? ref.enabled !== false && ref.version === item.version : all.findIndex(other => other.code === item.code) === index;
    });
    releaseId.value = directory.releases[0]?.releaseRef.releaseId ?? '';
  }
  catch (problem) {
    if (ticket === generation && session.current())
      error.value = problem instanceof Error ? problem.message : '语义配置读取失败';
  }
  finally {
    if (ticket === generation)
      busy.value = false;
  }
}
type JsonRecord = Record<string, unknown>;
async function save(next: Record<string, unknown>, current = app.value) {
  if (!current || current !== app.value)
    throw Error('应用上下文已切换，请重新加载');
  // 服务端draftRev是唯一覆盖基线，冲突不自动重读后重试写入。
  const saved = await api<SemanticApp>(`/admin/apps/${encodeURIComponent(current.code)}/config`, 'PUT', {
    draftRev: current.draftRev,
    config: next
  });
  if (saved.code !== current.code || saved.draftRev <= current.draftRev)
    throw Error('应用配置回执未递增修订，请刷新核对');
  return saved;
}
async function write(build: () => Promise<SemanticApp>) {
  if (!writable.value)
    return;
  const ticket = generation;
  const session = auth.snapshot();
  busy.value = true;
  error.value = '';
  notice.value = '';
  try {
    const saved = await build();
    if (ticket !== generation || !session.current())
      return;
    app.value = saved;
    emit('saved', saved);
    notice.value = '已保存应用草稿；请继续评测并发布当前修订。';
  }
  catch (problem) {
    if (ticket === generation && session.current())
      error.value = (problem instanceof Error ? problem.message : '保存失败') + '；如修订冲突请刷新后重新选择，未覆盖现有配置。';
  }
  finally {
    if (ticket === generation)
      busy.value = false;
  }
}
function goalSubmitted(goal: GoalDeclaration) {
  void write(async () => {
    const refs = [goal.criterion.readback.skillCode, ...(goal.criterion.action ? [goal.criterion.action.skillCode] : [])];
    const chosen = refs.map(code => skills.value.find(item => item.code === code));
    if (chosen.some(item => !item))
      throw Error('目标技能已不在当前选择集');
    return save(mergeGoal(addSkillRefs(app.value!.config, chosen as SkillChoice[]), goal));
  });
}
function bindingSubmitted(request: SemanticBindingRequest) {
  void write(async () => {
    if (!auth.can('admin') || !selected.value)
      throw Error('语义绑定登记需要ADMIN及当前正式版本');
    const skill = readonlySkills.value.find(item => item.code === request.skill.skillCode && item.version === request.skill.skillVersion);
    if (!skill)
      throw Error('只读技能版本已不在选择集');
    const current = app.value!;
    const release = selected.value;
    const session = auth.snapshot();
    const ticket = generation;
    const body = bindingWrite(current.code, request, release, skill, crypto.randomUUID());
    const heads = await api<Record<string, unknown>[]>(`/admin/semantic-bindings?appCode=${encodeURIComponent(current.code)}`);
    if (heads.length > 64)
      throw Error('语义绑定目录超过本页64项预算，请先整理已停用绑定');
    const existing = await Promise.all(
      heads
        .filter(item => item.status === 'ACTIVE')
        .map(item => api<Record<string, unknown>>(
          '/admin/semantic-bindings/' + encodeURIComponent(String(item.bindingId)),
        )),
    );
    if (ticket !== generation || !session.current())
      throw Error('配置上下文已切换');
    // 目录写入和应用草稿是两次独立提交；失败重试优先复用已创建的相同绑定。
    const previous = existing.find(item => {
      const revisions = Array.isArray(item.revisions) ? item.revisions.map(object) : [];
      const latest = revisions.find(revision => revision.bindingRev === item.currentRev) ?? {};
      const ref = object(latest.releaseRef);
      const bound = object(latest.skill);
      return item.status === 'ACTIVE'
        && ref.releaseId === release.releaseRef.releaseId
        && latest.extensionId === request.extensionId
        && latest.extensionDigest === body.extensionDigest
        && bound.skillSha256 === skill.sha256
        && bound.skillCode === skill.code
        && bound.skillVersion === skill.version;
    });
    const binding = previous ? {
      bindingId: String(previous.bindingId),
      currentRev: Number(previous.currentRev)
    } : await api<{
      bindingId: string;
      currentRev: number;
    }>('/admin/semantic-bindings', 'POST', body);
    if (ticket !== generation || !session.current())
      throw Error('配置上下文已切换');
    const config = addSkillRefs(current.config, [skill]);
    const refs = Array.isArray(config.semanticBindings) ? config.semanticBindings : [];
    return save({
      ...config,
      semanticBindings: [
        ...refs.filter(item => object(item).bindingId !== binding.bindingId),
        { bindingId: binding.bindingId, bindingRev: binding.currentRev },
      ]
    }, current);
  });
}
watch([
  () => props.appCode,
  () => auth.generation,
  () => auth.roles.join(',')
], () => {
  void load();
}, {
  immediate: true,
  flush: 'sync'
});
</script>
<template>
  <section>
    <p v-if="app">应用 {{ app.code }} · 草稿修订 {{ app.draftRev }}</p>
    <el-button :loading="busy" @click="load">刷新正式选择集与应用草稿</el-button>
    <el-alert v-if="error" type="error" :closable="false" :title="error" />
    <el-alert v-if="notice" type="success" :closable="false" :title="notice" />
    <el-alert v-for="item in catalog?.errors??[]" :key="item.releaseId" type="warning" :closable="false" :title="`${item.releaseId}：${item.message}`" />
    <el-empty v-if="catalog&&!catalog.releases.length" description="当前主体没有可用正式版本，请先登记受权消费身份并在本体工作台发布" />
    <el-select v-if="catalog?.releases.length" v-model="releaseId" aria-label="正式语义版本">
      <el-option v-for="item in catalog.releases" :key="item.releaseRef.releaseId" :value="item.releaseRef.releaseId" :label="`${item.releaseRef.spaceCode} / ${item.releaseRef.version}`" />
    </el-select>
    <template v-if="selection&&selected&&app">
      <el-alert v-if="selected.projection.truncated" type="warning" :closable="false" title="定义已按预算截断；列表只包含服务端返回的范围" />
      <SemanticBindingStep
        :key="releaseId + ':binding'"
        :selection="selection"
        :projection="selected.projection"
        :roles="auth.roles"
        :skill-options="readonlySkills"
        :disabled="!writable"
        @submit="bindingSubmitted"
      />
      <p v-if="!auth.can('admin')">语义绑定登记由ADMIN维护；目标配置按当前应用编辑权限保存。</p>
      <GoalCriteriaStep
        :key="releaseId + ':goals'"
        :approved-skills="readonlySkills.map(item => item.code)"
        :action-skills="skills.map(item => item.code)"
        :skill-options="skills"
        :object-types="selection.objectTypes"
        :disabled="!writable"
        @submit="goalSubmitted"
      />
      <p>当前目标 {{ Array.isArray(app.config.goals)?app.config.goals.length:0 }} 项。语义绑定还需在配置发布中选择本修订后生效。</p>
    </template>
  </section>
</template>
