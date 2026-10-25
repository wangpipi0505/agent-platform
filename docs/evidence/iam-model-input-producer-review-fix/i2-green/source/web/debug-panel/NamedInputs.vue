<script setup lang="ts">
import { ref, watch, onBeforeUnmount, nextTick } from 'vue';
import SchemaInputs from '../shared/SchemaInputs.vue';
import { useApi, useAuth } from '../shared/api';
const props = defineProps<{ appCode: string; draftId: number; declaration?: any }>();
const api = useApi(), auth = useAuth();
const form = ref<InstanceType<typeof SchemaInputs>>(), editForm = ref<InstanceType<typeof SchemaInputs>>();
const raw = ref('{}'), error = ref(''), notice = ref(''), loading = ref(false), busy = ref(false);
const items = ref<any[]>([]), selected = ref<number>(), hasMore = ref(false), nextAfter = ref(0), after = ref(0), history = ref<number[]>([]);
const editor = ref(false), editing = ref<any>(), name = ref(''), editRaw = ref('{}');
const repairAvailable = ref(false);
const promotion = ref(false), source = ref<any>(), expected = ref(''), safety = ref<boolean>(), datasetVersion = ref(''), caseId = ref('');
let scope = 0, listSequence = 0, detailSequence = 0, edits = 0, alive = true;
let requests = new AbortController();
let promotionAttempt: { fingerprint: string; body: any } | undefined;
function collect() {
  const value = props.declaration !== undefined ? form.value!.collect() : JSON.parse(raw.value);
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error('输入必须是 JSON 对象');
  return value;
}
function failure(e: any) {
  if (e.name === 'AbortError') return;
  error.value = e.status === 403 ? '当前账号没有管理这些测试输入的权限。' : e.status === 409 ? '保存版本已变化，请刷新列表后重新载入；你的输入仍保留。' : e.message;
}
async function list(cursor = 0) {
  const ticket = ++listSequence, current = scope;
  loading.value = true; error.value = '';
  try {
    const data = await api(`/admin/debug-inputs?appDraftId=${props.draftId}&afterId=${cursor}&limit=20`, 'GET', undefined, requests.signal);
    if (!alive || current !== scope || ticket !== listSequence) return;
    items.value = data.items; hasMore.value = data.hasMore; nextAfter.value = data.nextAfterId; after.value = cursor;
    if (!items.value.some(item => item.id === selected.value)) selected.value = undefined;
  } catch (e) { if (alive && current === scope && ticket === listSequence) failure(e); }
  finally { if (alive && current === scope && ticket === listSequence) loading.value = false; }
}
async function page(forward: boolean) {
  if (loading.value) return;
  const prior = after.value;
  const target = forward ? nextAfter.value : history.value.at(-1) ?? 0;
  await list(target);
  if (after.value === target) forward ? history.value.push(prior) : history.value.pop();
}
async function detail(action: 'use' | 'edit') {
  if (!selected.value || busy.value) return;
  const id = selected.value, ticket = ++detailSequence, current = scope, revision = edits;
  busy.value = true; error.value = ''; notice.value = '';
  try {
    const data = await api(`/admin/debug-inputs/${id}`, 'GET', undefined, requests.signal);
    if (!alive || current !== scope || ticket !== detailSequence || selected.value !== id) return;
    if (action === 'use') {
      if (edits !== revision) { notice.value = '载入期间输入已修改，未替换当前输入。请确认后重新使用。'; return; }
      if (props.declaration !== undefined) form.value!.replace(data.inputs); else raw.value = JSON.stringify(data.inputs, null, 2);
      notice.value = `已使用「${data.name}」版本 ${data.revision}`;
    } else await openEditor(data, data.inputs);
  } catch (e: any) { if (alive && current === scope && ticket === detailSequence) { repairAvailable.value = e.status === 400; failure(e); } }
  finally { if (alive && current === scope && ticket === detailSequence) busy.value = false; }
}
async function openEditor(item?: any, input?: any) {
  try {
    const value = input ?? collect();
    editing.value = item; name.value = item?.name || ''; editRaw.value = JSON.stringify(value, null, 2); editor.value = true;
    const current = scope; await nextTick();
    if (alive && current === scope && editor.value && props.declaration !== undefined) editForm.value!.replace(value);
  } catch (e) { failure(e); }
}
async function repair() { const item = items.value.find(item => item.id === selected.value); if (item) await openEditor(item); }
async function save() {
  if (busy.value || !auth.can('edit')) return;
  const current = scope; busy.value = true; error.value = '';
  try {
    if (!name.value.trim() || name.value.length > 128) throw Error('请填写 1–128 字的测试输入名称');
    const inputs = props.declaration !== undefined ? editForm.value!.collect() : JSON.parse(editRaw.value);
    const item = editing.value;
    await api(item ? `/admin/debug-inputs/${item.id}` : `/admin/debug-inputs?appDraftId=${props.draftId}`, item ? 'PUT' : 'POST', {name: name.value.trim(), inputs, ...(item ? {revision: item.revision} : {})}, requests.signal);
    if (!alive || current !== scope) return;
    editor.value = false; repairAvailable.value = false; notice.value = '测试输入已保存'; history.value = []; await list();
  } catch (e) { if (alive && current === scope) failure(e); }
  finally { if (alive && current === scope) busy.value = false; }
}
async function remove() {
  const item = items.value.find(item => item.id === selected.value);
  if (!item || busy.value || !auth.can('edit') || !confirm(`删除「${item.name}」？已提升的数据集版本会保留。`)) return;
  const current = scope; busy.value = true; error.value = '';
  try { await api(`/admin/debug-inputs/${item.id}?revision=${item.revision}`, 'DELETE', undefined, requests.signal); if (alive && current === scope) { selected.value = undefined; history.value = []; await list(); } }
  catch(e) { if (alive && current === scope) failure(e); }
  finally { if (alive && current === scope) busy.value = false; }
}
function openPromotion() {
  const item = items.value.find(item => item.id === selected.value); if (!item) return;
  source.value = {...item}; expected.value = ''; safety.value = undefined;
  const suffix = crypto.randomUUID().slice(0,8);
  datasetVersion.value = `debug-${new Date().toISOString().slice(0,10)}-${suffix}`; caseId.value = `case-${suffix}`;
  promotionAttempt = undefined; error.value = ''; promotion.value = true;
}
async function promote() {
  if (busy.value || !auth.can('edit')) return;
  const current = scope; busy.value = true; error.value = '';
  try {
    if (!expected.value.trim()) throw Error('请独立填写预期值，不能使用运行实际输出自动评分');
    if (typeof safety.value !== 'boolean') throw Error('请选择普通用例或安全用例');
    if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/.test(datasetVersion.value) || !caseId.value.trim()) throw Error('请填写有效数据集版本与用例标识');
    const payload = {revision: source.value.revision, datasetVersion: datasetVersion.value, caseId: caseId.value.trim(), expected: JSON.parse(expected.value), safety: safety.value};
    const fingerprint = JSON.stringify({id:source.value.id,...payload});
    // A failed delivery retains this exact body, even if the saved source later changes.
    if (promotionAttempt?.fingerprint !== fingerprint) promotionAttempt = {fingerprint, body:{requestId: crypto.randomUUID(), ...payload}};
    const result = await api(`/admin/debug-inputs/${source.value.id}/promote`, 'POST', promotionAttempt.body, requests.signal);
    if (!alive || current !== scope) return;
    promotion.value = false; notice.value = `已创建不可变数据集版本 ${result.datasetVersion}，可在候选评测中选择。`;
  } catch(e) { if (alive && current === scope) failure(e); }
  finally { if (alive && current === scope) busy.value = false; }
}
watch(() => [props.appCode, props.draftId, props.declaration], () => {
  scope++; requests.abort(); requests = new AbortController(); detailSequence++; listSequence++; edits++;
  items.value = []; selected.value = undefined; history.value = []; after.value = 0; hasMore.value = false;
  editor.value = false; promotion.value = false; busy.value = false; notice.value = ''; raw.value = '{}'; repairAvailable.value = false;
  if (props.draftId) void list();
}, {immediate:true});
watch(selected, () => { detailSequence++; busy.value = false; repairAvailable.value = false; });
watch(raw, () => edits++, {flush:'sync'});
watch([expected, safety, datasetVersion, caseId], () => { if (promotion.value) error.value = ''; });
onBeforeUnmount(() => { alive = false; scope++; requests.abort(); });
defineExpose({collect});
</script>
<template>
  <SchemaInputs v-if="declaration !== undefined" :key="draftId" ref="form" :declaration="declaration" @change="edits++" />
  <el-form v-else label-position="top"><el-form-item label="高级输入 JSON（草稿未声明输入 Schema）"><el-input v-model="raw" type="textarea" :rows="4" aria-label="调试输入 JSON" /></el-form-item></el-form>
  <details class="named-inputs" open><summary>命名测试输入</summary><p class="muted">保存完整输入供反复调试；选择后点击“使用输入”才会替换当前表单。</p>
    <div class="actions named-actions"><el-select v-model="selected" aria-label="已保存测试输入" placeholder="选择已保存的输入" :disabled="loading" @change="error=''">
      <el-option v-for="item in items" :key="item.id" :value="item.id" :label="item.name + ' · 版本 ' + item.revision" /></el-select>
      <el-button :disabled="!selected || busy" :loading="busy" @click="detail('use')">使用输入</el-button><el-button :disabled="busy" @click="list(after)">刷新列表</el-button>
      <el-button v-if="auth.can('edit')" :disabled="busy" @click="openEditor()">保存为命名输入</el-button></div>
    <p v-if="loading" class="muted" role="status">正在载入测试输入…</p><p v-else-if="!items.length && !error" class="muted">此草稿还没有命名输入。填写上方表单后可保存。</p>
    <div v-if="selected && auth.can('edit')" class="actions named-actions"><el-button :disabled="busy" @click="detail('edit')">编辑保存项</el-button><el-button v-if="repairAvailable" :disabled="busy" @click="repair">用当前输入修正保存项</el-button><el-button :disabled="busy" @click="openPromotion">提升为评测用例</el-button><el-button type="danger" text :disabled="busy" @click="remove">删除保存项</el-button></div>
    <div v-if="history.length || hasMore" class="actions"><el-button :disabled="!history.length || loading" @click="page(false)">上一页</el-button><el-button :disabled="!hasMore || loading" @click="page(true)">下一页</el-button></div>
  </details>
  <p v-if="notice" class="muted" role="status">{{notice}}</p><div v-if="error && !editor && !promotion" class="error" role="alert">{{error}}</div>
  <el-dialog v-model="editor" class="named-input-dialog" :title="editing ? '编辑命名输入' : '保存命名输入'" width="min(560px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy" :close-on-press-escape="!busy" destroy-on-close>
    <el-form label-position="top"><el-form-item label="测试输入名称" required><el-input v-model="name" maxlength="128" aria-label="测试输入名称" /></el-form-item></el-form>
    <SchemaInputs v-if="declaration !== undefined" ref="editForm" :declaration="declaration" /><el-input v-else v-model="editRaw" type="textarea" :rows="5" aria-label="保存项输入 JSON" />
    <div v-if="error" class="error" role="alert">{{error}}</div><template #footer><el-button :disabled="busy" @click="editor=false">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存测试输入</el-button></template>
  </el-dialog>
  <el-dialog v-model="promotion" class="named-input-dialog" title="提升为评测用例" width="min(560px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy" :close-on-press-escape="!busy" destroy-on-close>
    <p>来源：{{source?.name}} · 保存版本 {{source?.revision}}</p><p class="muted">使用已保存的输入版本。按完整输出精确比较，请独立填写预期值；不会自动采用运行实际输出。</p>
    <el-form label-position="top"><el-form-item label="预期完整输出 JSON" required><el-input v-model="expected" type="textarea" :rows="6" aria-label="预期完整输出 JSON" placeholder="请独立填写预期值" /></el-form-item><el-form-item label="用例类型" required><el-select v-model="safety" aria-label="用例类型" placeholder="请选择"><el-option :value="false" label="普通用例"/><el-option :value="true" label="安全用例（失败阻断发布）"/></el-select></el-form-item>
      <details><summary>版本与用例标识</summary><el-form-item label="数据集版本"><el-input v-model="datasetVersion" aria-label="数据集版本" maxlength="128"/></el-form-item><el-form-item label="用例标识"><el-input v-model="caseId" aria-label="用例标识" maxlength="128"/></el-form-item></details></el-form>
    <div v-if="error" class="error" role="alert">{{error}}</div><template #footer><el-button :disabled="busy" @click="promotion=false">取消</el-button><el-button type="primary" :loading="busy" @click="promote">创建评测版本</el-button></template>
  </el-dialog>
</template>
<style scoped>:global(.named-input-dialog){width:min(560px,94vw)!important}.named-inputs{margin:1rem 0}.named-actions{flex-wrap:wrap;margin:.75rem 0}.named-actions .el-select{width:280px;max-width:100%}@media(max-width:600px){.named-actions{display:flex;gap:.5rem}.named-actions .el-select{width:100%}.named-actions .el-button{margin-left:0;min-height:44px}}</style>
