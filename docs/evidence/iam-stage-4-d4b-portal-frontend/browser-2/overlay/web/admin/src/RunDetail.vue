<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from "vue";
import { useAuth, useApi } from "../../shared/api";
import { terminal, streamEvents,authorityFailure } from "../../debug-panel/events";
import Artifacts from '../../shared/Artifacts.vue';
import { outputWarning } from '../../shared/content';
const props = defineProps<{ runId: string }>();
const api = useApi();
const auth=useAuth(),session=auth.snapshot();
const blocked=ref(false);
const current=()=>!stopped&&session.current();
const value = ref<any>({});
const error = ref("");
const active = ref("summary");
const selected = ref<any>();
const replay = ref(0);
let stopped = false;
let controller: AbortController;
let timer: ReturnType<typeof setTimeout>;
async function refresh() {
  value.value = await api("/admin/runs/" + encodeURIComponent(props.runId));
}
async function connect() {
  if(!current()||blocked.value)return;
  try {
    await refresh();
    if (!current()||blocked.value||terminal(value.value.status)) return;
    controller = new AbortController();
    await streamEvents(
      "/admin/runs/" + encodeURIComponent(props.runId) + "/events",
      () => useAuth().token,
      value.value.eventsWatermark || 0,
      () => {if(current()&&!blocked.value)void refresh().catch((e:any)=>{if(current()){if(authorityFailure(e))blocked.value=true;if(e.name!=='AbortError')error.value=e.message;}});},
      controller.signal,
      () => {
        auth.invalidate(session.generation);
      },
      session,
    );
  } catch (e: any) {
    if(current()&&authorityFailure(e)){blocked.value=true;controller?.abort();clearTimeout(timer);}
    if (current()&&e.name !== "AbortError") error.value = e.message;
  } finally {
    if (current()&&!blocked.value && !terminal(value.value.status))
      timer = setTimeout(connect, 2000);
  }
}
function reconnect(){if(!current())return;blocked.value=false;error.value='';void connect();}
async function cancel() {
  if (!confirm("确认取消本次正式运行？已产生的外部效果不会自动撤销。")) return;
  try {
    await api(
      "/admin/runs/" + encodeURIComponent(props.runId) + "/cancel",
      "POST",
    );
    await refresh();
  } catch (e: any) {
    error.value = e.message;
  }
}
onMounted(connect);
onBeforeUnmount(() => {
  stopped = true;
  controller?.abort();
  clearTimeout(timer);
});
</script>
<template>
  <div v-if="error" class="error" role="alert">{{ error }}</div>
  <el-button v-if="blocked" @click="reconnect">重新连接观察</el-button>
  <div class="panel">
    <div class="row">
      <div>
        <h2>{{ runId }}</h2>
        <span :class="['status', value.status]">{{ value.status }}</span>
      </div>
      <el-button
        v-if="value.status && !terminal(value.status)"
        type="danger"
        plain
        @click="cancel"
        >取消运行</el-button
      >
    </div>
    <el-tabs v-model="active"
      ><el-tab-pane label="节点摘要" name="summary"
        ><el-table :data="value.nodes || []" @row-click="selected = $event"
          ><el-table-column prop="nodeDefId" label="节点" /><el-table-column
            prop="node_type"
            label="类型" /><el-table-column label="状态"
            ><template #default="{ row }"
              ><span :class="['status', row.status]">{{
                row.status
              }}</span></template
            ></el-table-column
          ><el-table-column
            prop="started_at"
            label="开始时间" /><el-table-column
            prop="ended_at"
            label="结束时间"
        /></el-table>
        <h2>运行输出</h2>
        <p v-if="outputWarning(value.output)" class="output-warning" role="status">{{ outputWarning(value.output) }}</p>
        <Artifacts :files="value.fileRefs||value.output?.fileRefs||[]" :run-id="runId" admin/>
        <pre>{{
          JSON.stringify(
            value.output ??
              (terminal(value.status)
                ? "运行完成，未返回输出"
                : "运行尚无输出"),
            null,
            2,
          )
        }}</pre>
        <pre
          v-if="
            value.error &&
            (typeof value.error === 'string' || Object.keys(value.error).length)
          "
          class="error"
          >{{ JSON.stringify(value.error, null, 2) }}</pre></el-tab-pane
      ><el-tab-pane label="版本与依赖" name="versions">
        <pre>{{ JSON.stringify(value.versionRefs || {}, null, 2) }}</pre>
        <p class="muted">
          本体调用版本：{{
            (value.versionRefs?.ontologyVersions || value.versionRefs?.ontology)
              ?.length
              ? "见以上版本链"
              : "不适用（服务端未报告本体调用）"
          }}
        </p></el-tab-pane
      ><el-tab-pane label="事件回放" name="replay"
        ><p class="muted">历史事件来自持久化执行记录，不会重新执行技能。</p>
        <el-slider
          v-model="replay"
          :max="Math.max((value.events?.length || 1) - 1, 0)"
          :show-tooltip="false"
        />
        <pre>{{
          JSON.stringify(value.events?.[replay] || "暂无事件", null, 2)
        }}</pre>
      </el-tab-pane></el-tabs
    ><el-collapse v-if="selected"
      ><el-collapse-item
        :title="'节点检查器 · ' + selected.nodeDefId"
        name="node"
      >
        <pre>{{ JSON.stringify(selected, null, 2) }}</pre>
      </el-collapse-item></el-collapse
    >
  </div>
</template>
