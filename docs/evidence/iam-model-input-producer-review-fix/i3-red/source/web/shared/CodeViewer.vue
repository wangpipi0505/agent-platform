<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from "vue";
import { EditorView, basicSetup } from "codemirror";
import { EditorState } from "@codemirror/state";
const props = defineProps<{ content: string }>();
const host = ref<HTMLElement>();
let view: EditorView;
onMounted(() => {
  view = new EditorView({
    doc: props.content,
    parent: host.value,
    extensions: [
      basicSetup,
      EditorState.readOnly.of(true),
      EditorView.editable.of(false),
      EditorView.theme({
        "&": { backgroundColor: "var(--bg)", fontSize: ".8125rem" },
        ".cm-scroller": { maxHeight: "45vh", overflow: "auto" },
      }),
    ],
  });
});
watch(
  () => props.content,
  (text) => {
    if (view)
      view.dispatch({
        changes: { from: 0, to: view.state.doc.length, insert: text },
      });
  },
);
onBeforeUnmount(() => view?.destroy());
</script>
<template>
  <p class="muted">只读全文 · Ctrl+F 搜索</p>
  <div ref="host" />
</template>
