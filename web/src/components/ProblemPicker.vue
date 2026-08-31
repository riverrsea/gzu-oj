<script setup lang="ts">
import { computed, ref } from "vue";
import { Check, ListChecks, Search, X } from "@lucide/vue";
import type { ProblemSummary } from "../api/types";

const props = withDefaults(defineProps<{
  /** 当前已选题目 ID。 */
  modelValue: string[];
  /** 可供选择的已发布题目。 */
  problems: ProblemSummary[];
  /** 没有可选题目时的提示。 */
  emptyText?: string;
  /** 最多允许选择的题目数量。 */
  max?: number;
}>(), { emptyText: "暂无可选题目", max: 20 });

const emit = defineEmits<{ "update:modelValue": [value: string[]] }>();
/** 题目筛选关键字。 */
const search = ref("");

/** 根据题名、学校、年份和标签过滤题目。 */
const visibleProblems = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase();
  if (!keyword) return props.problems;
  return props.problems.filter((problem) => [problem.title, problem.school, String(problem.year), ...problem.tags].join(" ").toLocaleLowerCase().includes(keyword));
});

/** 按用户选择顺序返回已选题目，与创建接口中的题目顺序保持一致。 */
const selectedProblems = computed(() => {
  const problemById = new Map(props.problems.map((problem) => [problem.id, problem]));
  return props.modelValue.map((id) => problemById.get(id)).filter((problem): problem is ProblemSummary => Boolean(problem));
});

/** 返回题目是否已选中。 */
function isSelected(problemId: string): boolean {
  return props.modelValue.includes(problemId);
}

/** 切换题目的选中状态。 */
function toggle(problemId: string, checked: boolean): void {
  const next = new Set(props.modelValue);
  if (checked && next.size < props.max) next.add(problemId);
  else next.delete(problemId);
  emit("update:modelValue", [...next]);
}

/** 从已选标签中移除题目。 */
function remove(problemId: string): void {
  emit("update:modelValue", props.modelValue.filter((id) => id !== problemId));
}

/** 清空当前选择。 */
function clear(): void {
  emit("update:modelValue", []);
}
</script>

<template>
  <div class="problem-picker">
    <section class="problem-picker-catalog">
      <header class="problem-picker-header"><strong>题库</strong><span>{{ visibleProblems.length }}/{{ problems.length }}</span></header>
      <label class="problem-picker-search"><Search :size="15" aria-hidden="true" /><input v-model="search" type="search" placeholder="搜索题目、学校、年份或标签" aria-label="搜索可选题目" /></label>
      <div v-if="visibleProblems.length" class="problem-picker-options" role="group" aria-label="可选题目">
        <label v-for="problem in visibleProblems" :key="problem.id" class="problem-picker-option" :class="{ selected: isSelected(problem.id) }">
          <input type="checkbox" :checked="isSelected(problem.id)" :disabled="!isSelected(problem.id) && modelValue.length >= max" @change="toggle(problem.id, ($event.target as HTMLInputElement).checked)" />
          <span class="problem-picker-check"><Check v-if="isSelected(problem.id)" :size="13" aria-hidden="true" /></span>
          <span class="problem-picker-option-copy"><strong>{{ problem.title }}</strong><small>{{ problem.school || "未注明学校" }} · {{ problem.year }} · {{ problem.tags.length ? problem.tags.join(" / ") : "无标签" }}</small></span>
          <span :class="['problem-picker-option-difficulty', 'problem-picker-option-difficulty--' + problem.difficulty.toLowerCase()]">{{ problem.difficulty === "EASY" ? "简单" : problem.difficulty === "MEDIUM" ? "中等" : "困难" }}</span>
        </label>
      </div>
      <div v-else class="problem-picker-empty"><Search :size="20" /><span>{{ search ? "没有匹配的题目" : emptyText }}</span></div>
    </section>
    <aside class="problem-picker-selection" aria-label="已选题目">
      <header class="problem-picker-header"><span><ListChecks :size="15" /><strong>已选题目</strong><em>{{ modelValue.length }}/{{ max }}</em></span><button v-if="modelValue.length" type="button" class="problem-picker-clear" @click="clear">清空</button></header>
      <div v-if="selectedProblems.length" class="problem-picker-selected">
        <div v-for="(problem, index) in selectedProblems" :key="problem.id" class="problem-picker-selected-row">
          <span>{{ String(index + 1).padStart(2, "0") }}</span><strong>{{ problem.title }}</strong><button type="button" :aria-label="`移除 ${problem.title}`" title="移除" @click="remove(problem.id)"><X :size="14" aria-hidden="true" /></button>
        </div>
      </div>
      <div v-else class="problem-picker-selected-empty"><ListChecks :size="21" /><span>尚未选择题目</span></div>
    </aside>
  </div>
</template>
