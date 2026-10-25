<script setup lang="ts">
import { ref, onBeforeUnmount } from "vue";
import { useAuth } from "./api";
import { downloadArtifact } from "./artifact";
const props = defineProps<{ files: any[]; runId: string; endpoint?: string; admin?:boolean }>();
const error = ref("");
const busy = ref(false);
const auth=useAuth(),session=auth.snapshot(),controller=new AbortController();
const scope={signal:AbortSignal.any([session.signal,controller.signal]),current:session.current};
onBeforeUnmount(()=>controller.abort());
async function download(file: any) {
  busy.value = true;
  error.value = "";
  try {
    await downloadArtifact(file, props.runId, auth.token, props.endpoint,props.admin,scope);
  } catch (e: any) {
    if(!scope.signal.aborted&&scope.current()&&e.name!=='AbortError')error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <section v-if="files.length" class="panel">
    <h2>结果附件</h2>
    <div v-for="file in files" :key="file.artifactId" class="row">
      <span
        >{{ file.summary || file.name || file.artifactId }} ·
        {{ file.bytes }} 字节</span
      ><el-button :loading="busy" @click="download(file)">下载附件</el-button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>
