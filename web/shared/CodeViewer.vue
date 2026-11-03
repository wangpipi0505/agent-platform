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
      // UI-01：暗色主题（dark:true 使 CodeMirror 启用暗色默认高亮），配色取主题令牌。
      EditorView.theme(
        {
          "&": {
            backgroundColor: "var(--surface-2)",
            color: "var(--ink)",
            fontSize: "var(--text-meta)",
            border: "1px solid var(--line)",
            borderRadius: "6px",
          },
          ".cm-scroller": { maxHeight: "45vh", overflow: "auto", fontFamily: "Consolas, monospace" },
          ".cm-gutters": {
            backgroundColor: "var(--surface)",
            color: "var(--muted)",
            border: "none",
          },
          ".cm-activeLine": { backgroundColor: "rgba(14, 165, 233, 0.08)" },
          ".cm-activeLineGutter": { backgroundColor: "rgba(14, 165, 233, 0.12)" },
          "&.cm-focused": { outline: "none" },
          ".cm-selectionBackground, &.cm-focused .cm-selectionBackground": {
            backgroundColor: "rgba(14, 165, 233, 0.25) !important",
          },
          ".cm-cursor": { borderLeftColor: "#38bdf8" },
        },
        { dark: true },
      ),
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
