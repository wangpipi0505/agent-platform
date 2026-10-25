<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from "vue";
import {
  rowsFromMap,
  mapFromRows,
  nodeTypes,
  type MappingRow,
} from "./node-metadata";
import MappingEditor from "./MappingEditor.vue";
import GraphFields from "./GraphFields.vue";
const props = defineProps<{
  value: any;
  definition: any;
  catalogs?: { models: any[]; skills: any[]; templates: any[] };
  readOnly?: boolean;
}>();
const emit = defineEmits<{
  update: [value: any];
  inputs: [value: any[]];
  outputs: [value: any[]];
}>();
const data = ref<any>({});
const args = ref<MappingRow[]>([]);
const output = ref<MappingRow[]>([]);
const issue = ref("");
const raw = ref("");
const advanced = ref(false);
let timer: ReturnType<typeof setTimeout>;
let resetting = false;
function reset() {
  resetting = true;
  data.value = JSON.parse(JSON.stringify(props.value));
  args.value = rowsFromMap(data.value.args);
  output.value = rowsFromMap(data.value.output);
  issue.value = "";
  advanced.value = false;
  resetting = false;
}
watch(() => props.value, reset, { immediate: true });
function flush() {
  clearTimeout(timer);
  if (props.readOnly) return true;
  try {
    const v = JSON.parse(JSON.stringify(data.value));
    // Absent args/output has interpreter meaning (especially END); opening a form must not add {}.
    if (args.value.length || Object.hasOwn(props.value, "args"))
      v.args = mapFromRows(args.value);
    if (output.value.length || Object.hasOwn(props.value, "output"))
      v.output = mapFromRows(output.value);
    if (v.errorPolicy && !Object.keys(v.errorPolicy).length)
      delete v.errorPolicy;
    emit("update", v);
    issue.value = "";
    return true;
  } catch (e: any) {
    issue.value = e.message;
    return false;
  }
}
watch(
  [data, args, output],
  () => {
    if (!resetting && !advanced.value) {
      clearTimeout(timer);
      timer = setTimeout(flush, 300);
    }
  },
  { deep: true },
);
function skill(code: string) {
  data.value.skillRef = {
    ...data.value.skillRef,
    code,
    version:
      props.catalogs?.skills.find((s) => s.code === code)?.currentVersion || 1,
  };
}
function openAdvanced() {
  try {
  raw.value = JSON.stringify(
    {
      ...data.value,
      ...(args.value.length ? { args: mapFromRows(args.value) } : {}),
      ...(output.value.length ? { output: mapFromRows(output.value) } : {}),
    },
    null,
    2,
  );
  advanced.value = true;
  } catch(e:any) {issue.value=e.message;raw.value=JSON.stringify(data.value,null,2);advanced.value=true;}
}
function applyRaw() {
  try {
    const v = JSON.parse(raw.value);
    if (v.id !== props.value.id) throw new Error("节点编码不可修改");
    data.value = v;
    args.value = rowsFromMap(v.args);
    output.value = rowsFromMap(v.output);
    advanced.value = false;
    flush();
  } catch (e: any) {
    issue.value = e.message;
  }
}
function policy() {
  data.value.errorPolicy ||= {};
}
defineExpose({ flush });
onBeforeUnmount(() => clearTimeout(timer));
</script>
<template>
  <div>
    <div class="row">
      <h2>
        {{ nodeTypes.find((n) => n.type === data.type)?.label || data.type }}
      </h2>
      <span class="muted">{{ data.id }}</span>
    </div>
    <el-form label-position="top" :disabled="readOnly"
      ><el-form-item label="节点名称"
        ><el-input
          v-model="data.name"
          placeholder="填写便于识别的名称" /></el-form-item
      ><el-form-item label="节点类型"
        ><el-select v-model="data.type" aria-label="节点类型"
          ><el-option
            v-for="n in nodeTypes"
            :key="n.type"
            :label="n.label + ' · ' + n.type"
            :value="n.type" /></el-select
      ></el-form-item>
      <template v-if="data.type === 'START'"
        ><h2>运行输入</h2>
        <p class="muted">在这里声明的字段会自动成为正式运行表单。</p>
        <GraphFields
          :model-value="definition.inputs || []"
          kind="inputs"
          :disabled="readOnly"
          @update:model-value="emit('inputs', $event)"
      /></template>
      <template v-if="data.type === 'END'"
        ><h2>流程输出</h2>
        <GraphFields
          :model-value="definition.outputs || []"
          kind="outputs"
          :definition="definition"
          :disabled="readOnly"
          @update:model-value="emit('outputs', $event)"
      /></template>
      <template v-if="['SKILL', 'RETRIEVE'].includes(data.type)"
        ><el-form-item label="调用技能"
          ><el-select
            :model-value="data.skillRef?.code"
            filterable
            allow-create
            placeholder="选择技能编码"
            aria-label="调用技能"
            @update:model-value="skill"
            ><el-option
              v-for="s in catalogs?.skills || []"
              :key="s.code"
              :label="s.name || s.code"
              :value="s.code" /></el-select></el-form-item
        ><el-form-item v-if="data.skillRef" label="技能发布版本"
          ><el-input-number
            v-model="data.skillRef.version"
            :min="1"
            aria-label="技能发布版本" /></el-form-item
      ></template>
      <template v-if="data.type === 'LLM'"
        ><el-form-item label="模型"
          ><el-select
            :model-value="data.modelOverride?.modelBinding?.modelEntryId"
            clearable
            filterable
            placeholder="继承应用配置"
            aria-label="节点模型"
            @update:model-value="
              (v: any) => {
                if (v)
                  data.modelOverride = {
                    ...data.modelOverride,
                    modelBinding: { modelEntryId: v },
                  };
                else delete data.modelOverride;
              }
            "
            ><el-option
              v-for="m in catalogs?.models || []"
              :key="m.id"
              :value="m.id"
              :label="m.displayName || m.modelName" /></el-select></el-form-item
        ><el-form-item label="提示词模板"
          ><el-select
            :model-value="data.promptRef?.templateCode || data.promptRef?.code"
            clearable
            filterable
            placeholder="继承应用配置"
            @update:model-value="
              (v: any) => {
                if (v) data.promptRef = { templateCode: v, version: 1 };
                else delete data.promptRef;
              }
            "
            ><el-option
              v-for="t in catalogs?.templates || []"
              :key="t.code"
              :value="t.code"
              :label="t.name || t.code" /></el-select></el-form-item
        ><el-form-item v-if="data.promptRef" label="模板发布版本"
          ><el-input-number
            v-model="data.promptRef.version"
            :min="1" /></el-form-item
      ></template>
      <el-form-item v-if="data.type === 'CODE'" label="转换表达式"
        ><el-input
          v-model="data.expression"
          type="textarea"
          :rows="3"
          placeholder="例如 size(${input.items})"
        />
        <p class="muted">使用受限表达式，不在浏览器执行代码。</p></el-form-item
      >
      <template v-if="data.type === 'CONDITION'"
        ><h2>按顺序匹配分支</h2>
        <div v-for="(branch, i) in data.branches || []" :key="i" class="branch">
          <el-checkbox
            :model-value="Object.hasOwn(branch, 'else')"
            @update:model-value="
              (v: any) => {
                data.branches[i] = v
                  ? { else: branch.to || branch.else || '' }
                  : { when: '', to: branch.else || branch.to || '' };
              }
            "
            >兜底分支</el-checkbox
          ><el-input
            v-if="!Object.hasOwn(branch, 'else')"
            v-model="branch.when"
            placeholder="条件，例如 ${input.count} > 0"
            :aria-label="'分支条件 ' + (i + 1)"
          /><el-select
            :model-value="branch.to || branch.else"
            placeholder="目标节点"
            :aria-label="'分支目标 ' + (i + 1)"
            @update:model-value="
              (v: any) =>
                Object.hasOwn(branch, 'else')
                  ? (branch.else = v)
                  : (branch.to = v)
            "
            ><el-option
              v-for="n in definition.nodes.filter((n: any) => n.id !== data.id)"
              :key="n.id"
              :value="n.id"
              :label="n.name || n.id" /></el-select
          ><el-button text type="danger" @click="data.branches.splice(i, 1)"
            >移除分支</el-button
          >
        </div>
        <el-button
          @click="
            data.branches ||= [];
            data.branches.push({ when: '', to: '' });
          "
          >添加分支</el-button
        ></template
      >
      <template v-if="data.type === 'CONFIRM'"
        ><el-form-item label="允许确认的角色"
          ><el-select
            v-model="data.confirmRoles"
            multiple
            filterable
            allow-create
            placeholder="输入角色后回车"
            aria-label="确认角色"
            ><el-option
              v-for="r in ['APPROVER', 'EDITOR', 'ADMIN']"
              :key="r"
              :value="r" /></el-select></el-form-item
        ><el-form-item label="确认超时（分钟）"
          ><el-input-number
            v-model="data.timeoutMin"
            :min="1"
            placeholder="继承默认值" /></el-form-item
        ><el-form-item label="超时后处理"
          ><el-select
            v-model="data.onTimeout"
            clearable
            placeholder="继承默认值"
            ><el-option
              value="TERMINATE"
              label="终止运行" /></el-select></el-form-item
      ></template>
      <template v-if="!['START', 'CONDITION', 'CONFIRM'].includes(data.type)"
        ><h2>输入参数</h2>
        <MappingEditor
          v-model="args"
          :definition="definition"
          :disabled="readOnly" />
        <h2>输出字段映射</h2>
        <MappingEditor
          v-model="output"
          :definition="definition"
          output
          :disabled="readOnly"
      /></template>
      <el-collapse @change="policy"
        ><el-collapse-item title="超时与失败处理" name="policy"
          ><template v-if="data.errorPolicy"
            ><el-form-item label="超时（毫秒）"
              ><el-input-number
                v-model="data.errorPolicy.timeoutMs"
                :min="1"
                placeholder="继承默认值" /></el-form-item
            ><el-form-item label="重试次数"
              ><el-input-number
                v-model="data.errorPolicy.retries"
                :min="0"
                placeholder="继承默认值" /></el-form-item
            ><el-form-item label="失败处理"
              ><el-select
                v-model="data.errorPolicy.onFail"
                clearable
                placeholder="继承默认值"
                ><el-option label="终止运行" value="TERMINATE" /><el-option
                  label="走错误分支"
                  value="ERROR_EDGE" /><el-option
                  label="使用默认输出"
                  value="DEFAULT_VALUE" /></el-select></el-form-item
            ><el-select
              v-if="data.errorPolicy.onFail === 'ERROR_EDGE'"
              v-model="data.errorPolicy.errorEdgeTo"
              placeholder="错误目标节点"
              ><el-option
                v-for="n in definition.nodes.filter(
                  (n: any) => n.id !== data.id,
                )"
                :key="n.id"
                :value="n.id"
                :label="n.name || n.id" /></el-select
            ><el-input
              v-if="data.errorPolicy.onFail === 'DEFAULT_VALUE'"
              :model-value="JSON.stringify(data.errorPolicy.defaultValue)"
              type="textarea"
              placeholder="默认输出 JSON"
              @change="
                (v: any) => {
                  try {
                    data.errorPolicy.defaultValue = JSON.parse(v);
                  } catch {
                    issue = '默认输出必须是有效JSON';
                  }
                }
              " /></template></el-collapse-item
      ></el-collapse>
    </el-form>
    <div v-if="issue" class="error" role="alert">{{ issue }}</div>
    <details
      @toggle="
        (e) => {
          if ((e.target as HTMLDetailsElement).open) openAdvanced();
          else advanced = false;
        }
      "
    >
      <summary>高级 JSON（特殊配置）</summary>
      <el-input
        v-model="raw"
        type="textarea"
        :rows="12"
        :readonly="readOnly"
        aria-label="高级节点 JSON"
      /><el-button v-if="!readOnly" @click="applyRaw">应用高级 JSON</el-button>
    </details>
    <p class="muted">字段修改会应用到画布，保存编排后写入服务端。</p>
  </div>
</template>
<style scoped>
.branch {
  padding: 0.75rem;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  margin-bottom: 0.75rem;
  display: grid;
  gap: 0.5rem;
}
details {
  margin-top: 1rem;
}
summary {
  cursor: pointer;
  color: var(--brand-primary);
}
</style>
