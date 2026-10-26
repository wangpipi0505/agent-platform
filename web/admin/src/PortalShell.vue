<script setup lang="ts">
import {onMounted,onBeforeUnmount} from 'vue';
import App from './App.vue';
import {useAuth} from '../../shared/api';
import {createPortalSession,type PortalTokenProvider} from '../../shared/portal-auth';
const props=defineProps<{provider?:PortalTokenProvider;mode:'portal'|'dev';configurationError?:string}>();
const auth=useAuth();
// A28/Mock：演示模式下 dev 登录与身份读取改由 mock 传输器应答；正常构建保持原生 fetch。
const mock=typeof import.meta.env?.VITE_MOCK==='string'&&import.meta.env.VITE_MOCK!=='false';
const transport=mock?async(...args:Parameters<typeof fetch>)=>{
  const {mockTransport}=await import('../../shared/mock-transport');
  return mockTransport(...args);
}:undefined;
const portal=createPortalSession(auth,props.provider,{mode:props.mode,transport});
onMounted(()=>{if(!props.configurationError&&props.mode==='portal')void portal.connect();});
onBeforeUnmount(portal.dispose);
</script>
<template>
  <App v-if="auth.token" :key="auth.generation" />
  <div v-else class="login" data-testid="portal-entry">
    <section class="panel portal-entry">
      <div class="muted">AGENT PLATFORM · V1.2</div>
      <h1>智能体构建工作台</h1>
      <p class="muted">从模型与技能出发，将业务流程编排为可调试、可评测、可发布的智能体。</p>
      <p :role="auth.connection==='connecting'?'status':'alert'" data-testid="portal-notice">{{configurationError||auth.notice||(mode==='dev'?'开发登录仅用于显式配置的隔离开发环境':'正在连接门户会话…')}}</p>
      <el-button v-if="!configurationError && (mode==='dev'||auth.connection!=='missing')" type="primary" :aria-busy="auth.connection==='connecting'" @click="portal.connect" data-testid="portal-reconnect">{{mode==='dev'?'进入开发工作台':'重新连接'}}</el-button>
      <p class="muted">身份变化会清除本页未保存内容。</p>
    </section>
  </div>
</template>
<style scoped>
.portal-entry{width:min(32rem,calc(100vw - 2rem));overflow-wrap:anywhere}
.portal-entry h1{margin-top:1rem}
</style>
