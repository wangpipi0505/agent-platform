<script setup lang="ts">
import { ref, computed, onMounted, watch, onBeforeUnmount } from "vue";
import SchemaInputs from "../../shared/SchemaInputs.vue";
import { completedControlledCall } from "../../shared/controlled-test";
import { useAuth, createApi, useApi } from "../../shared/api";
const props = defineProps<{ resource: any; kind: "skill" | "template" }>();
const api = useApi();
const value = ref(props.resource);
const versions = ref<any[]>([]);
const error = ref("");
const result = ref<any>();
const vars = ref("{}");
const content = ref(props.resource.content || "");
const selected = ref<number>();
const busy = ref(false);
const models = ref<any[]>([]);
const modelId = ref<number>();
const connectionInput = ref("{}");
const connectionForm = ref<InstanceType<typeof SchemaInputs>>();
const advancedInput = ref(false);
const connectionSchema = computed(
  () =>
    versions.value.find((v) => (v.versionNo || v.version) === selected.value)
      ?.inputSchema ?? value.value.inputSchema,
);
const controlledCallId = ref("");
const testRun = ref<any>();
let pollTimer: ReturnType<typeof setTimeout>;
let disposed = false;
async function checkTest() {
  if (!testRun.value?.runId) return;
  try {
    testRun.value = await api(
      "/admin/debug-runs/" + testRun.value.runId + "/status",
    );
    // The call ID is reserved before approval. Only a successful real run is an acceptance fact.
    if (completedControlledCall(testRun.value)) {
      controlledCallId.value = testRun.value.controlledCallId;
      result.value = await api(
        base() + "/drafts/" + selected.value + "/connection-test",
        "POST",
        { controlledCallId: controlledCallId.value },
      );
      return;
    }
    if (
      !["FAILED", "CANCELLED", "SUCCEEDED"].includes(testRun.value.status) &&
      !disposed
    )
      pollTimer = setTimeout(checkTest, 1500);
  } catch (e: any) {
    error.value = e.message;
  }
}
onBeforeUnmount(() => {
  disposed = true;
  clearTimeout(pollTimer);
});
const acceptanceCases = ref("[]");
const base = () =>
  "/admin/" +
  (props.kind === "skill" ? "skills" : "prompt-templates") +
  "/" +
  encodeURIComponent(props.resource.code);
async function load() {
  try {
    if (props.kind === "skill") {
      value.value = await api(base());
      versions.value = value.value.versions || [];
      models.value = await api("/admin/model-entries");
    } else versions.value = await api(base() + "/versions");
    selected.value = versions.value[0]?.versionNo || value.value.currentVersion;
    if (props.kind === "template")
      content.value =
        versions.value.find(
          (v) => (v.versionNo || v.version) === selected.value,
        )?.content || "";
  } catch (e: any) {
    error.value = e.message;
  }
}
async function execute(action: string) {
  busy.value = true;
  error.value = "";
  try {
    if (!selected.value && action !== "save") throw new Error("请选择真实版本");
    if (action === "publish") {
      if (!confirm("确认发布此版本？发布后内容不可修改。")) return;
      result.value = await api(
        base() + "/versions/" + selected.value + "/publish",
        "POST",
      );
      await load();
    }
    if (action === "render")
      result.value = await api(
        base() + "/versions/" + selected.value + "/render",
        "POST",
        { variables: JSON.parse(vars.value) },
      );
    if (action === "connection" || action === "recheck") {
      clearTimeout(pollTimer);
      result.value = await api(
        base() + "/drafts/" + selected.value + "/connection-test",
        "POST",
        action === "recheck"
          ? { controlledCallId: controlledCallId.value }
          : {
              inputs:
                connectionSchema.value && !advancedInput.value
                  ? connectionForm.value!.collect()
                  : JSON.parse(connectionInput.value),
              requestId: crypto.randomUUID(),
            },
      );
      if (result.value.runId) {
        testRun.value = { ...result.value };
        void checkTest();
      }
    }
    if (action === "acceptance") {
      const cases = JSON.parse(acceptanceCases.value);
      if (!modelId.value || !Array.isArray(cases) || !cases.length)
        throw new Error("请选择模型并填写非空验收用例数组");
      result.value = await api(
        base() + "/drafts/" + selected.value + "/acceptance",
        "POST",
        { modelEntryId: modelId.value, cases },
      );
    }
    if (action === "save") {
      result.value = await api(base(), "PUT", {
        ...versions.value.find(
          (v) => (v.versionNo || v.version) === selected.value,
        ),
        ...props.resource,
        content: content.value,
      });
      await load();
    }
  } catch (e: any) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
watch(selected, (version) => {
  if (props.kind === "template")
    content.value =
      versions.value.find((v) => (v.versionNo || v.version) === version)
        ?.content || "";
});
onMounted(load);
</script>
<template>
  <div class="panel">
    <div class="row">
      <div>
        <h2>{{ resource.name || resource.code }}</h2>
        <span class="muted"
          >{{ resource.code }} · {{ resource.description }}</span
        >
      </div>
      <div class="actions">
        <el-select
          v-model="selected"
          placeholder="选择版本"
          style="width: 10rem"
          ><el-option
            v-for="v in versions"
            :key="v.versionNo || v.version"
            :value="v.versionNo || v.version"
            :label="
              '版本 ' + (v.versionNo || v.version) + ' · ' + v.status
            " /></el-select
        ><el-button
          :loading="busy"
          v-permission="'edit'"
          @click="execute('publish')"
          >发布所选版本</el-button
        >
      </div>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <template v-if="kind === 'template'"
      ><el-form label-position="top"
        ><el-form-item label="模板正文（变量使用双花括号）"
          ><el-input
            v-model="content"
            type="textarea"
            :rows="10" /></el-form-item></el-form
      ><el-button
        type="primary"
        :loading="busy"
        v-permission="'edit'"
        @click="execute('save')"
        >保存为草稿版本</el-button
      >
      <h2>试渲染</h2>
      <el-input
        v-model="vars"
        type="textarea"
        :rows="4"
        placeholder="变量 JSON"
      /><el-button
        style="margin-top: 0.75rem"
        :loading="busy"
        v-permission="'edit'"
        @click="execute('render')"
        >试渲染所选版本</el-button
      ></template
    ><template v-else
      ><h2>执行属性与权限守卫</h2>
      <pre>{{
        JSON.stringify(
          {
            execution: value.execution,
            guard: value.guard,
            inputSchema: value.inputSchema,
            outputSchema: value.outputSchema,
          },
          null,
          2,
        )
      }}</pre>
      <el-collapse
        ><el-collapse-item title="连接测试与选型验收（发布前必需）">
          <SchemaInputs
            v-if="connectionSchema && !advancedInput"
            ref="connectionForm"
            :declaration="connectionSchema"
          />
          <el-checkbox v-model="advancedInput">高级 JSON 输入</el-checkbox>
          <el-form
            v-if="!connectionSchema || advancedInput"
            label-position="top"
            ><el-form-item label="测试输入参数 JSON"
              ><el-input
                v-model="connectionInput"
                type="textarea"
                :rows="4" /></el-form-item></el-form
          ><el-button
            :loading="busy"
            v-permission="'edit'"
            @click="execute('connection')"
            >发起受控测试</el-button
          >
          <div v-if="testRun" class="panel">
            <strong>{{ testRun.runId }} · {{ testRun.status }}</strong>
            <p v-if="testRun.status === 'WAITING_APPROVAL'">
              请到确认中心核对参数快照并批准；完成后本页自动读取事实并重检。
            </p>
            <a href="#/approvals" target="_blank" rel="noopener"
              >打开确认中心</a
            >
            ·
            <a :href="'#/runs/' + testRun.runId" target="_blank" rel="noopener"
              >查看调试详情</a
            ><el-button @click="checkTest">刷新测试结果</el-button>
          </div>
          <details>
            <summary>高级：已有受控调用标识</summary>
            <el-input
              v-model="controlledCallId"
              placeholder="已有真实调用标识"
            /><el-button @click="execute('recheck')">重检已有调用</el-button>
          </details>
          <h2>模型选型验收</h2>
          <el-form label-position="top"
            ><el-form-item label="选型模型"
              ><el-select
                v-model="modelId"
                filterable
                placeholder="选择真实模型条目"
                ><el-option
                  v-for="m in models"
                  :key="m.id"
                  :value="m.id"
                  :label="
                    m.displayName || m.modelName
                  " /></el-select></el-form-item
            ><el-form-item
              label="验收用例数组（question / expectSelected / expectArgs）"
              ><el-input
                v-model="acceptanceCases"
                type="textarea"
                :rows="6" /></el-form-item></el-form
          ><el-button
            :loading="busy"
            v-permission="'edit'"
            @click="execute('acceptance')"
            >运行选型验收</el-button
          >
          <p class="muted">
            报告绑定当前版本摘要；模型不可用会显示真实失败，不能直接发布绕过门禁。
          </p>
        </el-collapse-item></el-collapse
      >
      <h2>版本与验收记录</h2>
      <pre>{{ JSON.stringify(versions, null, 2) }}</pre>
      <p class="muted">
        无验收报告或引用统计时不构造指标；发布门禁由技能服务执行。
      </p></template
    >
    <pre v-if="result">{{ JSON.stringify(result, null, 2) }}</pre>
  </div>
</template>
