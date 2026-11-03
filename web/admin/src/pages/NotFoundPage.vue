<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
const route = useRoute();
const router = useRouter();
// MNT-02子步1：未知路径404；路由守卫拒绝访问时以 denied 参数给出403入口，均提供返回工作台。
const denied = computed(() =>
  typeof route.query.denied === "string" ? route.query.denied : "",
);
</script>
<template>
  <div class="panel login">
    <div class="muted">{{ denied ? "403 · 无权访问" : "404 · 页面不存在" }}</div>
    <h1>{{ denied ? `没有访问「${denied}」的权限` : "链接指向的页面不存在或已下线" }}</h1>
    <p class="muted">
      {{
        denied
          ? "如需访问请联系管理员确认角色授权；权限由后端独立鉴权，此处仅为导航提示。"
          : "请检查链接是否完整，或返回工作台从菜单重新进入。"
      }}
    </p>
    <el-button type="primary" @click="router.push('/apps')">返回工作台</el-button>
  </div>
</template>
