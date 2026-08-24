<script setup lang="ts">
import * as monaco from "monaco-editor/esm/vs/editor/editor.api.js";
import "monaco-editor/esm/vs/languages/definitions/cpp/register.js";
import "monaco-editor/esm/vs/languages/definitions/java/register.js";
import "monaco-editor/esm/vs/languages/definitions/python/register.js";
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import type { JudgeLanguage } from "../api/types";

const props = defineProps<{ modelValue: string; language: JudgeLanguage; fontSize: number; dark: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
const host = ref<HTMLDivElement>();
let editor: monaco.editor.IStandaloneCodeEditor | null = null;
let model: monaco.editor.ITextModel | null = null;

/** Monaco 使用稳定的语言 ID；题目语言枚举保留后端的判题名称。 */
function languageId(language: JudgeLanguage): string {
  if (language === "JAVA21") return "java";
  if (language === "PYTHON3") return "python";
  return language === "C17" ? "c" : "cpp";
}

/** 为 Monaco 注册与项目主题令牌一致的浅色和深色编辑器主题。 */
function defineThemes(): void {
  monaco.editor.defineTheme("gzu-light", {
    base: "vs",
    inherit: true,
    rules: [
      { token: "comment", foreground: "72817B", fontStyle: "italic" },
      { token: "keyword", foreground: "176B5B" },
      { token: "string", foreground: "A15C18" },
      { token: "number", foreground: "8A3E85" },
    ],
    colors: {
      "editor.background": "#FFFFFF",
      "editor.foreground": "#17201D",
      "editorLineNumber.foreground": "#9AA7A1",
      "editorLineNumber.activeForeground": "#176B5B",
      "editor.lineHighlightBackground": "#F0F6F3",
      "editorCursor.foreground": "#176B5B",
      "editor.selectionBackground": "#CDE9E0",
      "editorIndentGuide.background": "#E5ECE9",
      "editorIndentGuide.activeBackground": "#B9D5CC",
    },
  });
  monaco.editor.defineTheme("gzu-dark", {
    base: "vs-dark",
    inherit: true,
    rules: [
      { token: "comment", foreground: "849891", fontStyle: "italic" },
      { token: "keyword", foreground: "65C8AF" },
      { token: "string", foreground: "E4B86B" },
      { token: "number", foreground: "D99AD5" },
    ],
    colors: {
      "editor.background": "#171B1A",
      "editor.foreground": "#E8ECEA",
      "editorLineNumber.foreground": "#68756F",
      "editorLineNumber.activeForeground": "#70C6B1",
      "editor.lineHighlightBackground": "#202725",
      "editorCursor.foreground": "#70C6B1",
      "editor.selectionBackground": "#315A50",
      "editorIndentGuide.background": "#2A3531",
      "editorIndentGuide.activeBackground": "#45645A",
    },
  });
}

/** 配置 Monaco 的 Worker，避免将编辑器核心代码打进主线程。 */
function configureWorker(): void {
  const runtime = globalThis as typeof globalThis & {
    MonacoEnvironment?: { getWorker: (_workerId: string, _label: string) => Worker };
  };
  runtime.MonacoEnvironment = {
    getWorker: () => new Worker(new URL("../monaco-editor.worker.ts", import.meta.url), { type: "module" }),
  };
}

/** 创建编辑器实例，并开启适合在线判题的编辑体验。 */
onMounted(() => {
  if (!host.value) return;
  configureWorker();
  defineThemes();
  model = monaco.editor.createModel(props.modelValue, languageId(props.language));
  editor = monaco.editor.create(host.value, {
    model,
    theme: props.dark ? "gzu-dark" : "gzu-light",
    automaticLayout: true,
    fontSize: props.fontSize,
    fontFamily: "JetBrains Mono, SFMono-Regular, Consolas, monospace",
    fontLigatures: true,
    lineNumbers: "on",
    minimap: { enabled: false },
    scrollBeyondLastLine: false,
    smoothScrolling: true,
    cursorBlinking: "smooth",
    cursorSmoothCaretAnimation: "on",
    bracketPairColorization: { enabled: true },
    guides: { bracketPairs: true, indentation: true },
    folding: true,
    renderWhitespace: "selection",
    wordWrap: "off",
    tabSize: 4,
    insertSpaces: true,
    padding: { top: 14, bottom: 22 },
    contextmenu: true,
    quickSuggestions: true,
    suggestOnTriggerCharacters: true,
    scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 },
    ariaLabel: "代码编辑器",
  });
  editor.onDidChangeModelContent(() => {
    const value = editor?.getValue();
    if (value !== undefined && value !== props.modelValue) emit("update:modelValue", value);
  });
});

watch(() => props.modelValue, (value) => {
  if (editor && value !== editor.getValue()) editor.executeEdits("external-value", [{ range: editor.getModel()!.getFullModelRange(), text: value }]);
});

watch(() => props.language, (value) => {
  if (model) monaco.editor.setModelLanguage(model, languageId(value));
});

watch(() => props.dark, (dark) => {
  if (editor) monaco.editor.setTheme(dark ? "gzu-dark" : "gzu-light");
});

watch(() => props.fontSize, (fontSize) => editor?.updateOptions({ fontSize }));

onBeforeUnmount(() => {
  editor?.dispose();
  model?.dispose();
  editor = null;
  model = null;
});
</script>

<template><div ref="host" class="code-editor code-editor--monaco" /></template>
