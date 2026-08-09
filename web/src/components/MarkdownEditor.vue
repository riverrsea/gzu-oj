<script setup lang="ts">
import DOMPurify from "dompurify";
import { marked } from "marked";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import {
  Bold,
  Code2,
  Columns2,
  Eye,
  Heading2,
  Italic,
  Link,
  List as ListIcon,
  ListOrdered,
  Pencil,
  Quote,
  Redo2,
  Undo2,
} from "@lucide/vue";
import { defaultHighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { markdown } from "@codemirror/lang-markdown";
import { defaultKeymap, history, historyKeymap, indentWithTab, redo, undo } from "@codemirror/commands";
import { Compartment, EditorSelection, EditorState } from "@codemirror/state";
import { EditorView, keymap, lineNumbers, placeholder } from "@codemirror/view";

/** Markdown 编辑器显示模式。 */
type EditorMode = "edit" | "split" | "preview";

/** 父组件传入的 Markdown 文本和编辑锁定状态。 */
const props = defineProps<{ modelValue: string; disabled?: boolean }>();
/** 向父组件同步编辑后的 Markdown 文本。 */
const emit = defineEmits<{ "update:modelValue": [value: string] }>();
/** CodeMirror 宿主节点。 */
const host = ref<HTMLDivElement>();
/** 当前主题是否为深色。 */
const dark = ref(document.documentElement.dataset.theme === "dark");
/** 编辑器主题动态配置槽。 */
const themeCompartment = new Compartment();
/** 编辑只读状态动态配置槽。 */
const readOnlyCompartment = new Compartment();
/** CodeMirror 编辑器实例。 */
let editor: EditorView | null = null;

/** 从本地偏好恢复编辑、分屏或预览模式。 */
const savedMode = localStorage.getItem("gzu-oj.markdown-mode");
const mode = ref<EditorMode>(savedMode === "edit" || savedMode === "preview" || savedMode === "split" ? savedMode : "split");

/** 将 Markdown 渲染为经过清洗的预览 HTML。 */
const renderedMarkdown = computed(() =>
  DOMPurify.sanitize(marked.parse(props.modelValue, { async: false }) as string),
);

/** 构造与站点主题一致的 Markdown 编辑器样式。 */
function editorTheme(isDark: boolean) {
  return EditorView.theme(
    {
      "&": {
        height: "100%",
        backgroundColor: isDark ? "#171b1a" : "#ffffff",
        color: isDark ? "#e8ecea" : "#17201d",
      },
      ".cm-scroller": {
        overflow: "auto",
        fontFamily: "JetBrains Mono, SFMono-Regular, Consolas, monospace",
        fontSize: "14px",
        lineHeight: "1.65",
      },
      ".cm-content": { padding: "14px 0 40px" },
      ".cm-line": { padding: "0 14px" },
      ".cm-gutters": {
        backgroundColor: isDark ? "#171b1a" : "#f7f8f7",
        color: isDark ? "#7e8b86" : "#78827e",
        border: "0",
      },
      ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: isDark ? "#202725" : "#f0f6f3" },
      ".cm-cursor": { borderLeftColor: isDark ? "#ffffff" : "#17201d" },
      ".cm-selectionBackground, &.cm-focused .cm-selectionBackground": { backgroundColor: isDark ? "#315a50" : "#cce7df" },
      ".cm-placeholder": { color: isDark ? "#7e8b86" : "#89938f" },
    },
    { dark: isDark },
  );
}

/** 替换当前选区，并把新选区定位到插入内容内部。 */
function replaceSelection(insert: string, selectionStart: number, selectionEnd: number): void {
  if (!editor || props.disabled) return;
  const range = editor.state.selection.main;
  editor.dispatch({
    changes: { from: range.from, to: range.to, insert },
    selection: EditorSelection.single(range.from + selectionStart, range.from + selectionEnd),
    scrollIntoView: true,
  });
  editor.focus();
}

/** 使用成对 Markdown 标记包裹当前选区。 */
function wrapSelection(before: string, after: string, fallback: string): void {
  if (!editor || props.disabled) return;
  const range = editor.state.selection.main;
  const selected = editor.state.doc.sliceString(range.from, range.to) || fallback;
  replaceSelection(before + selected + after, before.length, before.length + selected.length);
}

/** 对当前选区覆盖到的完整行应用 Markdown 行级标记。 */
function transformLines(transform: (line: string, index: number) => string, fallback: string): void {
  if (!editor || props.disabled) return;
  const range = editor.state.selection.main;
  const firstLine = editor.state.doc.lineAt(range.from);
  const adjustedEnd = range.to > range.from && range.to === editor.state.doc.lineAt(range.to).from ? range.to - 1 : range.to;
  const lastLine = editor.state.doc.lineAt(Math.max(range.from, adjustedEnd));
  const source = editor.state.doc.sliceString(firstLine.from, lastLine.to);
  const content = source.trim().length === 0 ? fallback : source;
  const insert = content.split("\n").map(transform).join("\n");
  editor.dispatch({
    changes: { from: firstLine.from, to: lastLine.to, insert },
    selection: EditorSelection.single(firstLine.from, firstLine.from + insert.length),
    scrollIntoView: true,
  });
  editor.focus();
}

/** 插入二级标题。 */
function insertHeading(): void {
  transformLines((line) => "## " + line.replace(/^#{1,6}\s+/, ""), "二级标题");
}

/** 插入无序列表。 */
function insertBulletList(): void {
  transformLines((line) => "- " + line.replace(/^[-*+]\s+/, ""), "列表项");
}

/** 插入有序列表。 */
function insertOrderedList(): void {
  transformLines((line, index) => `${index + 1}. ${line.replace(/^\d+\.\s+/, "")}`, "列表项");
}

/** 插入引用块。 */
function insertQuote(): void {
  transformLines((line) => "> " + line.replace(/^>\s?/, ""), "引用内容");
}

/** 根据选区内容插入行内代码或围栏代码块。 */
function insertCode(): void {
  if (!editor || props.disabled) return;
  const range = editor.state.selection.main;
  const selected = editor.state.doc.sliceString(range.from, range.to);
  if (selected.includes("\n")) {
    wrapSelection("```\n", "\n```", "代码");
  } else {
    wrapSelection("`", "`", "代码");
  }
}

/** 插入 Markdown 链接并选中 URL 位置。 */
function insertLink(): void {
  if (!editor || props.disabled) return;
  const range = editor.state.selection.main;
  const label = editor.state.doc.sliceString(range.from, range.to) || "链接文字";
  const url = "https://";
  replaceSelection(`[${label}](${url})`, label.length + 3, label.length + 3 + url.length);
}

/** 执行编辑器撤销操作。 */
function undoEdit(): void {
  if (editor && !props.disabled) undo(editor);
}

/** 执行编辑器重做操作。 */
function redoEdit(): void {
  if (editor && !props.disabled) redo(editor);
}

/** 切换编辑器显示模式并持久化偏好。 */
function setMode(nextMode: EditorMode): void {
  mode.value = nextMode;
  localStorage.setItem("gzu-oj.markdown-mode", nextMode);
  void nextTick(() => editor?.requestMeasure());
}

/** 同步站点主题变化。 */
function syncTheme(event: Event): void {
  const detail = (event as CustomEvent<{ dark?: boolean }>).detail;
  dark.value = detail?.dark ?? document.documentElement.dataset.theme === "dark";
  editor?.dispatch({ effects: themeCompartment.reconfigure(editorTheme(dark.value)) });
}

onMounted(() => {
  if (!host.value) return;
  editor = new EditorView({
    parent: host.value,
    state: EditorState.create({
      doc: props.modelValue,
      extensions: [
        lineNumbers(),
        history(),
        markdown(),
        syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
        placeholder("请输入 Markdown 题面"),
        keymap.of([
          { key: "Mod-b", run: () => { wrapSelection("**", "**", "粗体文本"); return true; } },
          { key: "Mod-i", run: () => { wrapSelection("_", "_", "斜体文本"); return true; } },
          ...defaultKeymap,
          ...historyKeymap,
          indentWithTab,
        ]),
        themeCompartment.of(editorTheme(dark.value)),
        readOnlyCompartment.of([
          EditorState.readOnly.of(Boolean(props.disabled)),
          EditorView.editable.of(!props.disabled),
        ]),
        EditorView.lineWrapping,
        EditorView.updateListener.of((update) => {
          if (update.docChanged) emit("update:modelValue", update.state.doc.toString());
        }),
      ],
    }),
  });
  window.addEventListener("gzu-oj-theme-change", syncTheme);
});

watch(() => props.modelValue, (value) => {
  if (editor && value !== editor.state.doc.toString()) {
    editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } });
  }
});

watch(() => props.disabled, (disabled) => {
  editor?.dispatch({
    effects: readOnlyCompartment.reconfigure([
      EditorState.readOnly.of(Boolean(disabled)),
      EditorView.editable.of(!disabled),
    ]),
  });
});

onBeforeUnmount(() => {
  window.removeEventListener("gzu-oj-theme-change", syncTheme);
  editor?.destroy();
});
</script>

<template>
  <section class="markdown-editor">
    <header class="markdown-toolbar">
      <div class="markdown-command-group" role="toolbar" aria-label="Markdown 格式">
        <button class="icon-button" type="button" title="撤销" :disabled="disabled" @click="undoEdit"><Undo2 :size="17" /></button>
        <button class="icon-button" type="button" title="重做" :disabled="disabled" @click="redoEdit"><Redo2 :size="17" /></button>
        <span class="markdown-toolbar-divider" />
        <button class="icon-button" type="button" title="二级标题" :disabled="disabled" @click="insertHeading"><Heading2 :size="17" /></button>
        <button class="icon-button markdown-bold" type="button" title="粗体" :disabled="disabled" @click="wrapSelection('**', '**', '粗体文本')"><Bold :size="17" /></button>
        <button class="icon-button" type="button" title="斜体" :disabled="disabled" @click="wrapSelection('_', '_', '斜体文本')"><Italic :size="17" /></button>
        <button class="icon-button" type="button" title="行内代码或代码块" :disabled="disabled" @click="insertCode"><Code2 :size="17" /></button>
        <button class="icon-button" type="button" title="链接" :disabled="disabled" @click="insertLink"><Link :size="17" /></button>
        <span class="markdown-toolbar-divider" />
        <button class="icon-button" type="button" title="无序列表" :disabled="disabled" @click="insertBulletList"><ListIcon :size="17" /></button>
        <button class="icon-button" type="button" title="有序列表" :disabled="disabled" @click="insertOrderedList"><ListOrdered :size="17" /></button>
        <button class="icon-button" type="button" title="引用" :disabled="disabled" @click="insertQuote"><Quote :size="17" /></button>
      </div>
      <div class="markdown-toolbar-end">
        <span>{{ modelValue.length }} 字符</span>
        <div class="markdown-mode-switch" role="tablist" aria-label="Markdown 编辑模式">
          <button type="button" title="仅编辑" :class="{ active: mode === 'edit' }" :aria-pressed="mode === 'edit'" @click="setMode('edit')"><Pencil :size="16" /></button>
          <button type="button" title="分屏预览" :class="{ active: mode === 'split' }" :aria-pressed="mode === 'split'" @click="setMode('split')"><Columns2 :size="16" /></button>
          <button type="button" title="仅预览" :class="{ active: mode === 'preview' }" :aria-pressed="mode === 'preview'" @click="setMode('preview')"><Eye :size="16" /></button>
        </div>
      </div>
    </header>
    <div :class="['markdown-editor-body', 'markdown-editor-body--' + mode]">
      <div v-show="mode !== 'preview'" class="markdown-editor-pane"><div ref="host" class="markdown-editor-host" /></div>
      <article v-show="mode !== 'edit'" class="markdown-preview-pane">
        <div v-if="modelValue.trim()" class="markdown-body" v-html="renderedMarkdown" />
        <el-empty v-else description="暂无题面内容" :image-size="54" />
      </article>
    </div>
  </section>
</template>
