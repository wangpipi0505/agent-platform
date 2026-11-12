<script setup lang="ts">
import { computed, ref, watch } from "vue";
import SkillBusinessFields from "./SkillBusinessFields.vue";
import OpenApiImport from "./OpenApiImport.vue";
import { analyzeSchema } from "../../shared/schema-dialect";
const props = defineProps<{ initial: any }>();
const emit = defineEmits<{ save: [value: any]; "open-imported": [code: string] }>();
const mode = ref("manual");
const step = ref(0);
const value = ref(JSON.parse(JSON.stringify(props.initial)));
const input = ref(JSON.stringify(value.value.inputSchema, null, 2) ?? "");
const output = ref(JSON.stringify(value.value.outputSchema, null, 2) ?? "");
// 只读方言提示：不改变 input/output 的解析时机与提交路径，只把可空写法与明显问题显示出来。
const inputAnalysis = computed(() => analyzeSchema(input.value));
const outputAnalysis = computed(() => analyzeSchema(output.value));
const processJson = ref(JSON.stringify(value.value.process ?? {}, null, 2));
// R10/SCN-004：PROCESS登记走process子契约（ProcessContract.parse），不提交endpoint/httpMapping；
// 切换类型清除不相容隐藏字段，避免隐藏值随表单进入其他类型的草稿。
watch(
  () => value.value.type,
  (type) => {
    if (type === "PROCESS") {
      for (const field of ["endpoint", "httpMapping", "toolName", "authRef"])
        delete value.value[field];
    } else {
      delete value.value.process;
      if (type !== "MCP") delete value.value.toolName;
    }
  },
);
const roles = ref<string>((value.value.guard?.roles || []).join(", "));
const attributes = ref(JSON.stringify(value.value.guard?.attributes || {}, null, 2));
const steps = [
  "基本信息",
  "接口连接",
  "输入输出",
  "执行属性",
  "权限守卫",
  "检查注册",
];
const error = ref("");
function next() {
  error.value = "";
  try {
    if (
      step.value === 0 &&
      (!/^[a-z][a-z0-9_]{1,63}$/.test(value.value.code) || !value.value.name)
    )
      throw new Error("请填写名称及合法编码（小写字母开头，2–64 位）");
    if (step.value === 1 && value.value.type === "PROCESS") {
      let parsed: any;
      try {
        parsed = JSON.parse(processJson.value);
      } catch (e: any) {
        throw new Error("process 子契约必须是合法 JSON 对象");
      }
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed))
        throw new Error("字段 process 必须是 JSON 对象（command/workDirRoot/timeoutMs 等）");
      value.value.process = parsed;
    } else if (step.value === 1) {
      if (!/^https?:\/\//.test(value.value.endpoint))
        throw new Error("请填写 http/https 服务地址");
      if (value.value.type === "MCP" &&
          (typeof value.value.toolName !== "string" || !value.value.toolName.trim()))
        throw new Error("请填写远端准确工具名（区分大小写）");
    }
    if (step.value === 2) {
      value.value.inputSchema = JSON.parse(input.value);
      value.value.outputSchema = JSON.parse(output.value);
    }
    if (step.value === 4)
      value.value.guard = {
        roles: roles.value
          .split(",")
          .map((x) => x.trim())
          .filter(Boolean),
        attributes: JSON.parse(attributes.value),
      };
    step.value++;
  } catch (e: any) {
    error.value = e.message;
  }
}
</script>
<template>
  <el-radio-group v-model="mode" aria-label="注册方式" style="margin-bottom: 1rem">
    <el-radio-button value="manual">手工注册</el-radio-button>
    <el-radio-button value="openapi">OpenAPI 导入</el-radio-button>
  </el-radio-group>
  <OpenApiImport v-if="mode === 'openapi'" @open-imported="emit('open-imported', $event)" />
  <template v-else>
  <el-steps :active="step" finish-status="success"
    ><el-step v-for="s in steps" :key="s" :title="s" /></el-steps
  ><el-form label-position="top" style="margin-top: 1.5rem"
    ><template v-if="step === 0"
      ><SkillBusinessFields :value="value" /><el-form-item label="技能编码" required
        ><el-input v-model="value.code" /></el-form-item
      ><el-form-item label="技能名称" required
        ><el-input v-model="value.name" /></el-form-item
      ><el-form-item label="技能类型"
        ><el-radio-group v-model="value.type"
          ><el-radio-button
            v-for="t in ['HTTP', 'MCP', 'ALGORITHM', 'PROCESS']"
            :key="t"
            :value="t"
            >{{ t }}</el-radio-button
          ></el-radio-group
        ></el-form-item
      ><el-form-item label="说明（帮助模型理解何时使用）"
        ><el-input
          v-model="value.description"
          type="textarea" /></el-form-item></template
    ><template v-if="step === 1 && value.type === 'PROCESS'"
      ><el-form-item label="process 子契约 JSON（遵循 ProcessContract）" required
        ><el-input v-model="processJson" type="textarea" :rows="8"
          /><p class="muted"
            >command 为参数数组且首元素是绝对路径（不做 shell 拼接）；endpoint/httpMapping
            与 PROCESS 互斥。登记通过不代表 worker 可执行，运行出口由正式配置与预算准入决定。</p
        ></el-form-item
      ></template
    ><template v-if="step === 1 && value.type !== 'PROCESS'"
      ><el-form-item :label="value.type === 'MCP' ? 'MCP SSE 地址' : '调用端点'" required
        ><el-input
          v-model="value.endpoint"
          placeholder="https://..." /></el-form-item
      ><p v-if="value.type === 'MCP'" class="muted">填写远端 SSE 地址；当前版本使用 SSE 连接。</p>
      <el-form-item v-if="value.type === 'MCP'" label="准确工具名（区分大小写）" required
        ><el-input v-model="value.toolName" placeholder="与远端 tools/list 的 name 完全一致" />
        <p class="muted">保存草稿后可检查远端工具。检查仅列取工具，不执行调用或替换合同。</p></el-form-item
      ><el-form-item label="凭据引用"
        ><el-input
          v-model="value.authRef"
          placeholder="secret_ref，不填写凭据明文" /></el-form-item
      ><el-form-item label="超时毫秒"
        ><el-input-number
          v-model="value.timeoutMs"
          :min="100"
          :max="300000" /></el-form-item></template
    ><template v-if="step === 2"
      ><el-form-item label="输入 JSON Schema"
        ><el-input v-model="input" type="textarea" :rows="7" />
        <div style="width: 100%; margin-top: 0.5rem">
          <el-alert
            v-for="(problem, index) in inputAnalysis.problems.slice(0, 5)"
            :key="index"
            :title="problem.path ? `${problem.path}: ${problem.message}` : problem.message"
            :type="inputAnalysis.ok ? 'warning' : 'error'"
            :closable="false"
            show-icon
          />
          <el-text v-if="inputAnalysis.nullable.length" size="small" type="info"
            >已识别 {{ inputAnalysis.nullable.length }} 个可空字段</el-text
          >
        </div></el-form-item
      ><el-form-item label="输出 JSON Schema"
        ><el-input
          v-model="output"
          type="textarea"
          :rows="7" />
        <div style="width: 100%; margin-top: 0.5rem">
          <el-alert
            v-for="(problem, index) in outputAnalysis.problems.slice(0, 5)"
            :key="index"
            :title="problem.path ? `${problem.path}: ${problem.message}` : problem.message"
            :type="outputAnalysis.ok ? 'warning' : 'error'"
            :closable="false"
            show-icon
          />
          <el-text v-if="outputAnalysis.nullable.length" size="small" type="info"
            >已识别 {{ outputAnalysis.nullable.length }} 个可空字段</el-text
          >
        </div></el-form-item></template
    ><template v-if="step === 3"
      ><el-form-item
        v-for="[k, label] in [
          ['readonly', '只读操作'],
          ['sideEffect', '存在写入或外部副作用'],
          ['idempotent', '支持幂等'],
          ['cancellable', '支持取消'],
        ]"
        :key="k"
        :label="label"
        ><el-switch v-model="value.execution[k]" /></el-form-item
      ><el-alert
        v-if="!value.execution.readonly"
        title="写操作必须声明副作用与幂等语义；运行时仍执行权限检查与确认。"
        :closable="false"
        type="warning" /></template
    ><template v-if="step === 4"
      ><el-form-item label="允许角色（逗号分隔）"
        ><el-input v-model="roles" /></el-form-item
      ><el-form-item label="属性限制 JSON"
        ><el-input
          v-model="attributes"
          type="textarea"
          :rows="6" /></el-form-item></template
    ><template v-if="step === 5"
      ><el-alert
        title="注册产生待验收版本。发布仍由服务端验收与门禁决定。"
        :closable="false"
      />
      <pre>{{ JSON.stringify(value, null, 2) }}</pre>
    </template>
    <p v-if="error" class="error">{{ error }}</p></el-form
  >
  <div class="row">
    <el-button :disabled="step === 0" @click="step--">上一步</el-button
    ><el-button v-if="step < 5" type="primary" @click="next">下一步</el-button
    ><el-button v-else type="primary" @click="emit('save', value)"
      >确认注册</el-button
    >
  </div>
  </template>
</template>
<style scoped>
/* UI-03：向导在弹层内的JSON预览溢出保护；不改注册流程与提交路径。 */
pre { max-height: 20rem; overflow: auto; white-space: pre-wrap; word-break: break-word; background: var(--el-fill-color-light, #f5f7fa); padding: .75rem; border-radius: .45rem; }
</style>
