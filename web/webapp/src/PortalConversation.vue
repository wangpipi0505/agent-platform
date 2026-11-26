<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { useApi, useAuth } from '../../shared/api';
import { streamEvents } from '../../debug-panel/events';
import { readTranscript, followTranscript } from '../../shared/portal-conversation';

const props = defineProps<{ appCode: string; sessionId: string }>();
const emit = defineEmits<{ created: [id: string, title: string] }>();
const auth = useAuth();
const transcript = ref(readTranscript({ eventWatermark: 0, messages: [] }));
const draft = ref('');
const error = ref('');
const disconnected = ref(false);
const sending = ref(false);
let activeId = '';
let pending: { text: string; id: string } | undefined;
let selection = new AbortController();

const collection = () => '/v1/apps/' + encodeURIComponent(props.appCode) + '/sessions';

async function observe(id: string, signal: AbortSignal) {
  const api = useApi();
  const path = collection() + '/' + encodeURIComponent(id);
  try {
    const snapshot = await api(path, 'GET', undefined, signal);
    signal.throwIfAborted();
    transcript.value = readTranscript(snapshot);
    await followTranscript(transcript.value, signal, (last, receive) =>
      streamEvents(path + '/events/stream', () => auth.token, last, receive, signal, () => auth.invalidate()));
  } catch (cause) {
    if (signal.aborted) return;
    error.value = cause instanceof Error ? cause.message : '读取会话失败';
    disconnected.value = true;
  }
}

function select(id: string, resetDraft = true) {
  selection.abort();
  selection = new AbortController();
  activeId = id;
  if (resetDraft) { draft.value = ''; pending = undefined; }
  transcript.value = readTranscript({ eventWatermark: 0, messages: [] });
  disconnected.value = false;
  sending.value = false;
  error.value = '';
  if (id) void observe(id, AbortSignal.any([selection.signal, auth.snapshot().signal]));
}

watch(() => [props.appCode, props.sessionId] as const, ([, id], old) => {
  if (!old || old[0] !== props.appCode || activeId !== id) select(id);
}, { immediate: true, flush: 'sync' });
onBeforeUnmount(() => selection.abort());

async function send() {
  const text = draft.value.trim();
  if (!text || sending.value || disconnected.value) return;
  const signal = AbortSignal.any([selection.signal, auth.snapshot().signal]);
  const api = useApi();
  sending.value = true;
  error.value = '';
  try {
    if (!activeId) {
      const created = await api(collection(), 'POST', undefined, signal);
      signal.throwIfAborted();
      if (typeof created?.sessionId !== 'string' || !created.sessionId) throw Error('缺少会话回执');
      activeId = created.sessionId;
      emit('created', activeId, text);
      void observe(activeId, signal);
    }
    if (!pending || pending.text !== text) pending = { text, id: crypto.randomUUID() };
    await api(collection() + '/' + encodeURIComponent(activeId) + '/turns', 'POST',
      {requestId: pending.id, inputs: {message: text}}, signal);
    signal.throwIfAborted();
    draft.value = '';
    pending = undefined;
  } catch (cause) {
    if (!signal.aborted) error.value = cause instanceof Error ? cause.message : '提交轮次失败';
  } finally {
    if (!signal.aborted) sending.value = false;
  }
}
</script>
<template>
  <section class="conversation chat-pane" aria-label="会话对话">
    <div class="transcript" role="log" aria-live="polite">
      <article v-for="m in transcript.messages" :key="m.seq">
        <strong>{{ m.role === 'user' ? '你' : '智能体' }}</strong>
        <p>{{ m.text }}</p>
      </article>
    </div>
    <p v-if="transcript.failure || error" role="alert">{{ error || transcript.failure }}</p>
    <el-button v-if="disconnected" @click="select(activeId, false)">重新连接会话</el-button>
    <el-input v-model="draft" type="textarea" :rows="3" aria-label="对话内容"
      :disabled="sending" @keydown.enter.exact.prevent="send" />
    <el-button type="primary" :disabled="sending || disconnected || !draft.trim()" @click="send">发送</el-button>
  </section>
</template>
<style scoped>
.conversation { display: flex; flex-direction: column; gap: var(--sp-3); padding: var(--sp-4); min-height: 0; }
.transcript { flex: 1; overflow-y: auto; }
article p { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
