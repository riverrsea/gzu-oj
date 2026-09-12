<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { Eye } from "@lucide/vue";
import UiEmptyState from "./ui/EmptyState.vue";
import UiTextarea from "./ui/Textarea.vue";
import { renderMarkdown } from "../lib/markdown";

/** 结构化题面的章节字段。内容仍使用 Markdown，便于保留公式、代码和列表。 */
type StatementSectionKey = "description" | "inputFormat" | "outputFormat" | "constraints" | "notes";

interface StatementSections {
  description: string;
  inputFormat: string;
  outputFormat: string;
  constraints: string;
  notes: string;
}

/** 父组件传入的题面和锁定状态。 */
const props = defineProps<{ modelValue: string; disabled?: boolean }>();
/** 将结构化字段重新组合成后端现有的 Markdown 字段。 */
const emit = defineEmits<{ "update:modelValue": [value: string] }>();

const sections = reactive<StatementSections>({
  description: "",
  inputFormat: "",
  outputFormat: "",
  constraints: "",
  notes: "",
});
const initialized = ref(false);

const aliases: Record<string, StatementSectionKey> = {
  "题目描述": "description",
  "问题描述": "description",
  "描述": "description",
  "输入格式": "inputFormat",
  "输入描述": "inputFormat",
  "输入": "inputFormat",
  "输出格式": "outputFormat",
  "输出描述": "outputFormat",
  "输出": "outputFormat",
  "数据范围": "constraints",
  "数据约束": "constraints",
  "约束": "constraints",
  "限制": "constraints",
  "补充说明": "notes",
  "说明": "notes",
};

/** 清空结构化字段，供 Markdown 重新解析。 */
function resetSections(): void {
  sections.description = "";
  sections.inputFormat = "";
  sections.outputFormat = "";
  sections.constraints = "";
  sections.notes = "";
}

/** 将标题规范化，兼容旧题面中的中英文冒号和多余空格。 */
function normalizeHeading(value: string): string {
  return value.replace(/[：:]$/, "").replace(/\s+/g, "").trim();
}

/** 读取旧 Markdown 题面并拆分到结构化章节；未识别内容归入补充说明。 */
function parseStatement(markdown: string): void {
  resetSections();
  const normalized = markdown.replace(/\r\n/g, "\n").trim();
  if (!normalized) return;
  const lines = normalized.split("\n");
  const buffers: Record<StatementSectionKey, string[]> = {
    description: [],
    inputFormat: [],
    outputFormat: [],
    constraints: [],
    notes: [],
  };
  let current: StatementSectionKey | null = null;
  let recognizedHeading = false;
  const fallback: string[] = [];

  for (const line of lines) {
    const heading = line.match(/^#{1,6}\s+(.+?)\s*$/);
    if (heading) {
      const section = aliases[normalizeHeading(heading[1])];
      if (section) {
        recognizedHeading = true;
        current = section;
        continue;
      }
      current = null;
    }
    if (current) buffers[current].push(line);
    else fallback.push(line);
  }

  if (!recognizedHeading) {
    sections.description = normalized;
  } else {
    (Object.keys(buffers) as StatementSectionKey[]).forEach((key) => {
      sections[key] = buffers[key].join("\n").trim();
    });
    sections.notes = [sections.notes, fallback.join("\n").trim()].filter(Boolean).join("\n\n");
  }
}

/** 生成统一标题层级的 Markdown，供预览和后端保存。 */
const serializedStatement = computed(() => {
  const blocks = [
    ["题目描述", sections.description],
    ["输入格式", sections.inputFormat],
    ["输出格式", sections.outputFormat],
    ["数据范围", sections.constraints],
    ["补充说明", sections.notes],
  ].filter(([, value]) => value.trim())
    .map(([heading, value]) => `## ${heading}\n\n${value.trim()}`);
  return blocks.length > 0 ? `${blocks.join("\n\n\n")}\n` : "";
});

/** 使用与题目详情页相同的 Markdown 清洗和渲染规则（含 KaTeX 公式）。 */
const renderedStatement = computed(() => renderMarkdown(serializedStatement.value));

watch(() => props.modelValue, (value) => {
  if (value !== serializedStatement.value) parseStatement(value);
  initialized.value = true;
}, { immediate: true });

watch(serializedStatement, (value) => {
  if (initialized.value && value !== props.modelValue) emit("update:modelValue", value);
});
</script>

<template>
  <section class="statement-builder" :class="{ 'statement-builder--disabled': disabled }">
    <header class="statement-builder-header">
      <div>
        <h3>结构化题面</h3>
        <p>按章节填写内容，右侧会实时预览最终题面；每个章节支持 Markdown。</p>
      </div>
      <span class="statement-preview-label"><Eye :size="16" />实时预览</span>
    </header>

    <div class="statement-builder-layout">
      <div class="statement-fields">
        <section class="statement-field statement-field--wide">
          <header><strong>题目描述</strong><span>说明题目背景、目标和需要完成的任务</span></header>
          <UiTextarea v-model="sections.description" :rows="7" :disabled="disabled" placeholder="例如：给定两个整数 A、B，请计算它们的和。" />
        </section>
        <section class="statement-field">
          <header><strong>输入格式</strong><span>输入数据的结构、顺序和分隔方式</span></header>
          <UiTextarea v-model="sections.inputFormat" :rows="7" :disabled="disabled" placeholder="例如：一行包含两个整数 A 和 B，用空格分隔。" />
        </section>
        <section class="statement-field">
          <header><strong>输出格式</strong><span>输出内容、格式和精度要求</span></header>
          <UiTextarea v-model="sections.outputFormat" :rows="7" :disabled="disabled" placeholder="例如：输出一个整数，表示 A+B 的值。" />
        </section>
        <section class="statement-field">
          <header><strong>数据范围</strong><span>约束、边界和特殊条件</span></header>
          <UiTextarea v-model="sections.constraints" :rows="5" :disabled="disabled" placeholder="例如：-10^9 ≤ A,B ≤ 10^9。" />
        </section>
        <section class="statement-field">
          <header><strong>补充说明</strong><span>题解提示、特殊说明或未归类的 Markdown</span></header>
          <UiTextarea v-model="sections.notes" :rows="5" :disabled="disabled" placeholder="可选，例如多组数据说明、输出精度说明等。" />
        </section>
      </div>

      <aside class="statement-preview">
        <header><strong>题面预览</strong><span>{{ serializedStatement.length }} 字符</span></header>
        <article v-if="serializedStatement.trim()" class="markdown-body" v-html="renderedStatement" />
        <UiEmptyState v-else description="填写左侧内容后预览题面" />
      </aside>
    </div>

    <details class="statement-source">
      <summary>查看生成的 Markdown</summary>
      <pre>{{ serializedStatement || "暂无内容" }}</pre>
    </details>
  </section>
</template>
