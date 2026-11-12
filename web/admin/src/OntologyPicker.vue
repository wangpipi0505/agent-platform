<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useApi } from '../../shared/api'

interface ReleaseRef { spaceCode: string; releaseId: string; version: string; contentDigest: string }
interface Choice { releaseRef: ReleaseRef; name?: string; spaceId?: string; projection?: { definitions?: { objects?: { ref?: { name?: string }; description?: string }[] } } }
const props = defineProps<{ modelValue?: unknown }>()
const emit = defineEmits<{ 'update:modelValue': [value: (ReleaseRef & { space: string }) | null] }>()
const api = useApi()
const choices = ref<Choice[]>([])
const error = ref('')
const loading = ref(false)
const selectedId = ref('')
watch(() => props.modelValue, value => { selectedId.value = value && typeof value === 'object' && 'releaseId' in value ? String(value.releaseId) : '' }, { immediate: true, deep: true })
const selected = computed(() => choices.value.find(choice => choice.releaseRef.releaseId === selectedId.value))
function select(id: string) {
  if (!id) { emit('update:modelValue', null); return }
  const choice = choices.value.find(item => item.releaseRef.releaseId === id)
  if (choice) emit('update:modelValue', { space: choice.spaceId ?? choice.releaseRef.spaceCode, ...choice.releaseRef })
}
onMounted(async () => {
  loading.value = true
  try {
    const catalog = await api<{ releases: Choice[]; errors?: string[] }>('/admin/skills/semantic-catalog')
    choices.value = catalog.releases ?? []
    if (catalog.errors?.length) error.value = catalog.errors.join('；')
  } catch (problem) { error.value = problem instanceof Error ? problem.message : '本体目录加载失败' }
  finally { loading.value = false }
})
</script>

<template>
  <div class="ontology-picker">
    <el-select v-model="selectedId" clearable filterable :loading="loading" placeholder="选择本体及正式版本" aria-label="选择本体正式版本" @change="select">
      <el-option v-for="choice in choices" :key="choice.releaseRef.releaseId" :value="choice.releaseRef.releaseId" :label="`${choice.name || choice.releaseRef.spaceCode} · ${choice.releaseRef.version}`" />
    </el-select>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div v-if="selected" class="ontology-info">
      <strong>{{ selected.name || selected.releaseRef.spaceCode }}</strong>
      <span>正式版本 {{ selected.releaseRef.version }} · {{ selected.releaseRef.releaseId }}</span>
      <p>提供领域对象、事实与关系、规划规则和结果出处。运行记录保留本次使用的本体版本。</p>
      <div class="ontology-types"><el-tag v-for="object in selected.projection?.definitions?.objects || []" :key="object.ref?.name" size="small">{{ object.description || object.ref?.name }}</el-tag></div>
    </div>
    <p v-else class="muted">未选择本体时，助手使用工具提供的数据回答，不附加本体事实与规则解释。</p>
  </div>
</template>

<style scoped>
.ontology-picker { display: grid; gap: .65rem; width: 100%; min-width: 0; }
.ontology-picker :deep(.el-select) { width: 100%; }
.ontology-info { display: grid; gap: .4rem; border: 1px solid var(--line, #334155); border-radius: .5rem; padding: .8rem; background: var(--surface-2, #152030); overflow-wrap: anywhere; }
.ontology-info p { margin: 0; }
.ontology-info > span { color: var(--muted, #94a3b8); font-size: .8rem; }
.ontology-types { display: flex; flex-wrap: wrap; gap: .4rem; }
</style>
