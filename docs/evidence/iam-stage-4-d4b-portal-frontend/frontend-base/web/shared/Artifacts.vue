<script setup lang="ts">
import { ref } from "vue";
import { useAuth } from "./api";
import { downloadArtifact } from "./artifact";
const props = defineProps<{ files: any[]; runId: string; endpoint?: string; admin?:boolean }>();
const error = ref("");
const busy = ref(false);
async function download(file: any) {
  busy.value = true;
  error.value = "";
  try {
    await downloadArtifact(file, props.runId, useAuth().token, props.endpoint,props.admin);
  } catch (e: any) {
    error.value = e.message;
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
