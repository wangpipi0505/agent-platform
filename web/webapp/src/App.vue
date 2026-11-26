<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useAuth, login } from "../../shared/api";
import { truncateTitle } from "../../shared/api-session";
import { connectHostChannel, type HostChannelHandle } from "./host-channel";
import { useTheme } from "../../shared/theme";
import SessionList from "./SessionList.vue";
import MyMemories from "./MyMemories.vue";
import AssistantChat from "./AssistantChat.vue";
import PortalConversation from "../../shared/PortalConversation.vue";
import { createPortalSession, trustedPortal } from "../../shared/portal-auth";
import type { SessionView } from "./demo-flow";

const auth = useAuth();
const provider = trustedPortal(window as Window & { agentPlatformPortal?: unknown });
const portal = provider || import.meta.env.VITE_AUTH_MODE === 'portal'
  ? createPortalSession(auth, provider, {mode: 'portal'}) : undefined;
onMounted(() => { if (portal) void portal.connect(); });
onBeforeUnmount(() => portal?.dispose());
// F4-4 亮色切换（方案 §7.3）：顶栏按钮翻转 dark/light 并持久 localStorage，初始跟随系统。
const { resolved: themeResolved, toggleTheme, dispose: disposeTheme } = useTheme();
onBeforeUnmount(disposeTheme);
const error = ref("");
const code = decodeURIComponent(
  location.pathname.split("/").filter(Boolean).pop() || "",
);
let hostChannel: HostChannelHandle | null = null;
onMounted(() => {
  hostChannel = connectHostChannel({ appCode: code });
});
watch(
  () => auth.token,
  (token) => {
    if (token) hostChannel?.notifyOpened();
  },
);
onBeforeUnmount(() => hostChannel?.close());
async function enter() {
  if (portal) { await portal.connect(); return; }
  try {
    auth.token = await login();
  } catch (e: any) {
    error.value = e.message;
  }
}

/** F4-1/F4-2 侧栏：会话列表（切换/新建）与我的记忆两 tab；<768px 侧栏收抽屉（前端稿 §5.4 断点）。 */
const sideTab = ref<"sessions" | "memories">("sessions");
const drawerOpen = ref(false);
const narrow = ref(false);
let mediaQuery: MediaQueryList | null = null;
function onMediaChange(e: MediaQueryListEvent) {
  narrow.value = e.matches;
  if (!e.matches) drawerOpen.value = false;
}
const stopMedia = () => mediaQuery?.removeEventListener("change", onMediaChange);

/** 当前会话（sessionId=""=新建草稿）；新建会话首次发送后由 session-created 回填侧栏。 */
const current = ref<SessionView | null>(null);
const localRows = ref<SessionView[]>([]);
const surface = ref<'task' | 'chat'>('task');
function openChat() {
  if (current.value?.sessionId && !current.value.sessionId.startsWith('s_')) createSession();
  surface.value = 'chat';
}
watch(() => auth.generation, () => {
  current.value = null;
  localRows.value = [];
});
function selectRow(row: SessionView) {
  if (row.sessionId.startsWith('s_')) surface.value = 'chat';
  current.value = row;
  drawerOpen.value = false;
}
function createSession() {
  current.value = { sessionId: "", title: "新会话", lastActiveAt: "", state: "活跃", appCode: code };
  sideTab.value = "sessions";
  drawerOpen.value = false;
}
function onSessionCreated(sessionId: string, title: string) {
  const row: SessionView = {
    sessionId,
    title: truncateTitle(title),
    lastActiveAt: new Date().toISOString().slice(0, 16),
    state: "活跃",
    appCode: code,
  };
  localRows.value.unshift(row);
  current.value = row;
}

/** 消息操作「保存为记忆」→侧栏我的记忆保存表单（回填正文+当前会话 ID）。 */
const memoriesRef = ref<InstanceType<typeof MyMemories>>();
async function openMemoryForm(text: string) {
  sideTab.value = "memories";
  if (narrow.value) drawerOpen.value = true;
  await nextTick();
  memoriesRef.value?.openSaveForm({ content: text, sessionId: current.value?.sessionId ?? "" });
}

onMounted(() => {
  mediaQuery = matchMedia("(max-width: 768px)");
  narrow.value = mediaQuery.matches;
  mediaQuery.addEventListener("change", onMediaChange);
});
onBeforeUnmount(stopMedia);
</script>
<template>
  <div class="app">
    <header class="topbar">
      <span class="topbar-left">
        <el-button
          v-if="narrow && auth.token"
          class="menu-btn"
          size="small"
          @click="drawerOpen = true"
        >
          会话/记忆
        </el-button>
        <el-button v-if="auth.token" :aria-pressed="surface === 'chat'" @click="openChat">会话对话</el-button>
        <el-button v-if="auth.token" :aria-pressed="surface === 'task'" @click="surface = 'task'">执行任务</el-button>
        <strong>{{ code }} · 业务对话</strong><span class="muted">智能体平台</span>
      </span>
      <!-- F4-4 亮色切换：与 admin 顶栏同源（shared/theme.ts），解析结果间翻转并持久覆盖 -->
      <el-button
        text
        size="small"
        class="theme-toggle"
        data-testid="theme-toggle"
        :title="themeResolved === 'dark' ? '切换为亮色主题' : '切换为暗色主题'"
        :aria-label="'切换主题，当前' + (themeResolved === 'dark' ? '暗色' : '亮色')"
        @click="toggleTheme"
        >{{ themeResolved === "dark" ? "☾ 暗色" : "☀ 亮色" }}</el-button
      >
    </header>
    <main class="assistant-shell">
      <div v-if="!auth.token" class="panel">
        <h1>开始对话</h1>
        <p class="muted">请先获取当前环境的身份票据。</p>
        <el-button type="primary" @click="enter">{{ portal ? '连接门户' : '开发登录' }}</el-button>
        <p v-if="error || auth.notice" role="alert">{{ error || auth.notice }}</p>
      </div>
      <template v-else>
        <aside :class="['sidebar', { open: drawerOpen }]">
          <nav class="side-tabs" aria-label="侧栏切换">
            <button
              type="button"
              :class="['side-tab', { active: sideTab === 'sessions' }]"
              @click="sideTab = 'sessions'"
            >
              会话
            </button>
            <button
              type="button"
              :class="['side-tab', { active: sideTab === 'memories' }]"
              @click="sideTab = 'memories'"
            >
              我的记忆
            </button>
          </nav>
          <SessionList
            v-show="sideTab === 'sessions'"
            :app-code="code"
            :active-id="current?.sessionId ?? ''"
            :local-rows="localRows"
            @select="selectRow"
            @create="createSession"
          />
          <MyMemories
            v-show="sideTab === 'memories'"
            ref="memoriesRef"
            :session-id="current?.sessionId ?? ''"
          />
        </aside>
        <div
          v-if="narrow && drawerOpen"
          class="scrim"
          @click="drawerOpen = false"
        />
        <PortalConversation v-if="surface === 'chat'" :key="auth.generation" :app-code="code"
          :session-id="current?.sessionId || ''" @created="onSessionCreated" />
        <AssistantChat v-else
          class="chat-pane"
          :session="current"
          @save-memory="openMemoryForm"
          @session-created="onSessionCreated"
        />
      </template>
    </main>
  </div>
</template>
<style scoped>
.app {
  height: 100dvh;
  display: grid;
  grid-template-rows: auto 1fr;
}
.topbar-left {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}
.assistant-shell {
  min-height: 0;
  display: grid;
  grid-template-columns: 17rem minmax(0, 1fr);
}
.sidebar {
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-3);
  background: var(--nav);
  border-right: 1px solid var(--line);
}
.side-tabs {
  display: flex;
  gap: var(--sp-1);
}
.side-tab {
  flex: 1;
  padding: var(--sp-2);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--nav-text);
  cursor: pointer;
  font-size: var(--text-meta);
}
.side-tab.active {
  background: var(--nav-active-bg);
  color: var(--nav-text-active);
  border-color: var(--brand-primary);
}
.chat-pane {
  min-width: 0;
  min-height: 0;
}
.scrim {
  position: fixed;
  inset: 0;
  background: var(--el-mask-color);
  z-index: 20;
}
.menu-btn {
  display: none;
}
/* 窄屏断点（前端稿 §5.4）：<768px 会话列表收抽屉，主列占满、无 clamp 固定宽。 */
@media (max-width: 768px) {
  .assistant-shell {
    grid-template-columns: minmax(0, 1fr);
  }
  .sidebar {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    width: min(80vw, 20rem);
    z-index: 30;
    transform: translateX(-100%);
    transition: transform 0.2s ease;
  }
  .sidebar.open {
    transform: translateX(0);
  }
  .menu-btn {
    display: inline-flex;
  }
}
</style>
