<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";

/**
 * 宿主只需：一个按钮、一个短票函数、一个事件回调。
 * - 票据来自同源 BFF，不落 localStorage/URL；
 * - pageContext 只放业务字段，不放 tenant/user/roles；
 * - result 只触发宿主刷新，业务状态以宿主后端为准。
 */

const ENDPOINT = "https://assistant.example.com";
const APP_CODE = "vue_demo";
const INTEGRATION_ID = "vue-demo";

const recordId = ref("");
const status = ref("未初始化");
const answer = ref("");
const errorText = ref("");
const events = ref<string[]>([]);

let handle: AssistantHandle | null = null;

interface AssistantHandle {
  open(options?: { pageContext?: Record<string, string | number | boolean> }): void;
  close(): void;
  updateContext(context: Record<string, string | number | boolean>): void;
  onHostEvent(listener: (event: HostEvent) => void): () => void;
  destroy(): void;
}

interface HostEvent {
  event: string;
  status?: string;
  runId?: string;
  traceId?: string;
  structured?: { answer?: string };
  businessRecordRefs?: Array<{ source: string; recordId: string }>;
  error?: { code?: string; message?: string };
}

async function ticket(): Promise<string> {
  const response = await fetch("/assistant/api/ticket", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ appCode: APP_CODE }),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || body.code !== "OK" || !body.data?.ticket) {
    throw new Error(body.message || `取票失败（${response.status}）`);
  }
  return body.data.ticket as string;
}

function onHostEvent(event: HostEvent) {
  events.value.push(event.event + (event.runId ? ` ${event.runId}` : ""));
  if (events.value.length > 30) events.value.shift();

  switch (event.event) {
    case "ready":
      status.value = "助手已就绪";
      break;
    case "run-started":
      status.value = "正在处理…";
      errorText.value = "";
      break;
    case "approval-required":
      status.value = "等待人工确认";
      break;
    case "result":
      status.value = `运行状态：${event.status ?? "UNKNOWN"}`;
      answer.value = event.structured?.answer ?? "";
      // businessRecordRefs 只交给宿主路由；是否可读由宿主后端重新鉴权。
      break;
    case "error":
      errorText.value = `${event.error?.code ?? "UNKNOWN"} · ${event.error?.message ?? "请求失败"}`;
      break;
    default:
      break;
  }
}

onMounted(() => {
  const api = window.AgentPlatformAssistant ?? window.AIAssistant;
  if (!api) {
    status.value = "未加载 SDK";
    return;
  }
  handle = api.init({
    endpoint: ENDPOINT,
    appCode: APP_CODE,
    integrationId: INTEGRATION_ID,
    autoLauncher: false,
    ticket,
    onHostEvent,
  });
  status.value = "已初始化";
});

onBeforeUnmount(() => handle?.destroy());

function open() {
  const id = recordId.value.trim();
  handle?.open({ pageContext: id ? { recordId: id } : {} });
}

function updateContext() {
  const id = recordId.value.trim();
  handle?.updateContext(id ? { recordId: id } : {});
}
</script>

<template>
  <main>
    <h1>Vue 宿主示例</h1>
    <label>
      当前记录号
      <input v-model="recordId" @change="updateContext" placeholder="例如 R-2026-0001" />
    </label>
    <button @click="open">打开智能助手</button>
    <button @click="handle?.close()">关闭</button>

    <p>状态：{{ status }}</p>
    <p v-if="errorText" role="alert">{{ errorText }}</p>
    <p v-if="answer" style="white-space: pre-wrap">{{ answer }}</p>

    <pre v-if="events.length">{{ events.join("\n") }}</pre>
  </main>
</template>

<style scoped>
main { font: 15px/1.7 system-ui, sans-serif; max-width: 760px; margin: 40px auto; padding: 0 16px; }
input { margin-left: 8px; padding: 8px 10px; }
button { margin-right: 8px; padding: 8px 14px; }
pre { background: #0f172a; color: #e2e8f0; padding: 12px; border-radius: 8px; overflow: auto; }
</style>
