<script setup lang="ts">
import { Compartment, EditorState } from "@codemirror/state";
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import { cpp } from "@codemirror/lang-cpp";
import { java } from "@codemirror/lang-java";
import { python } from "@codemirror/lang-python";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { JudgeLanguage } from "../api/types";

const props = defineProps<{ modelValue: string; language: JudgeLanguage; fontSize: number; dark: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const host = ref<HTMLDivElement>();
const languageCompartment = new Compartment();
const themeCompartment = new Compartment();
let editor: EditorView | null = null;

function languageExtension(language: JudgeLanguage) {
  if (language === "JAVA21") return java();
  if (language === "PYTHON3") return python();
  return cpp();
}

function editorTheme(dark: boolean, fontSize: number) {
  return EditorView.theme(
    {
      "&": { height: "100%", backgroundColor: dark ? "#171b1a" : "#ffffff", color: dark ? "#e8ecea" : "#17201d" },
      ".cm-scroller": { overflow: "auto", fontFamily: "JetBrains Mono, SFMono-Regular, Consolas, monospace", fontSize: fontSize + "px" },
      ".cm-gutters": { backgroundColor: dark ? "#171b1a" : "#f7f8f7", color: dark ? "#7e8b86" : "#78827e", border: "0" },
      ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: dark ? "#202725" : "#f0f6f3" },
      ".cm-cursor": { borderLeftColor: dark ? "#ffffff" : "#17201d" },
      ".cm-selectionBackground, &.cm-focused .cm-selectionBackground": { backgroundColor: dark ? "#315a50" : "#cce7df" },
    },
    { dark },
  );
}

onMounted(() => {
  editor = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        lineNumbers(),
        history(),
        keymap.of([...defaultKeymap, ...historyKeymap, indentWithTab]),
        languageCompartment.of(languageExtension(props.language)),
        themeCompartment.of(editorTheme(props.dark, props.fontSize)),
        EditorView.lineWrapping,
        EditorView.updateListener.of((update) => {
          if (update.docChanged) emit("update:modelValue", update.state.doc.toString());
        }),
      ],
    }),
  });
});

watch(() => props.modelValue, (value) => {
  if (editor && value !== editor.state.doc.toString()) {
    editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } });
  }
});
watch(() => props.language, (value) => editor?.dispatch({ effects: languageCompartment.reconfigure(languageExtension(value)) }));
watch([() => props.dark, () => props.fontSize], ([dark, size]) => {
  editor?.dispatch({ effects: themeCompartment.reconfigure(editorTheme(dark, size)) });
});
onBeforeUnmount(() => editor?.destroy());
</script>

<template><div ref="host" class="code-editor" /></template>
