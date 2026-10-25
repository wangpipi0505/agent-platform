<script setup lang="ts">
import { ref } from "vue";
import Chat from "../../shared/Chat.vue";
import { useAuth, login } from "../../shared/api";
const auth = useAuth();
const error = ref("");
const code = decodeURIComponent(
  location.pathname.split("/").filter(Boolean).pop() || "",
);
async function enter() {
  try {
    auth.token = await login();
  } catch (e: any) {
    error.value = e.message;
  }
}
</script>
<template>
  <header class="topbar">
    <strong>{{ code }} · 业务对话</strong><span class="muted">智能体平台</span>
  </header>
  <main class="content">
    <div v-if="!auth.token" class="panel">
      <h1>开始对话</h1>
      <p class="muted">请先获取当前环境的身份票据。</p>
      <el-button type="primary" @click="enter">开发登录</el-button>
      <p v-if="error" role="alert">{{ error }}</p>
    </div>
    <Chat v-else :app-code="code" />
  </main>
</template>
