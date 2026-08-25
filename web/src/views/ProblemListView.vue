<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { Search } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { api } from "../api/client";
import type { Difficulty, ProblemSummary } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";

const loading = ref(false);
const problems = ref<ProblemSummary[]>([]);
const filters = reactive<{ school: string; year?: number; tag: string; difficulty?: Difficulty }>({
  school: "",
  year: undefined,
  tag: "",
  difficulty: undefined,
});

const difficultyText: Record<Difficulty, string> = { EASY: "简单", MEDIUM: "中等", HARD: "困难" };

async function load(): Promise<void> {
  loading.value = true;
  try {
    problems.value = await api.problems(filters);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "题库加载失败");
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="content-page content-page--modern">
    <div class="page-heading">
      <div><h1>题库</h1><p>按学校、年份、标签与难度筛选已发布题目</p></div>
      <span class="result-count">{{ problems.length }} 题</span>
    </div>
    <form class="filter-bar catalog-toolbar--modern" @submit.prevent="load">
      <UiInput v-model="filters.school" placeholder="学校" />
      <UiNumberField v-model="filters.year" :min="1900" :max="2200" placeholder="年份" />
      <UiInput v-model="filters.tag" placeholder="标签" />
      <select v-model="filters.difficulty" class="h-10 rounded-md border border-line bg-paper px-3 text-sm text-ink outline-none focus:border-brand focus:ring-2 focus:ring-brand/20">
        <option :value="undefined">难度</option><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option>
      </select>
      <UiButton type="submit" :loading="loading"><Search :size="16" />筛选</UiButton>
    </form>
    <div class="overflow-x-auto rounded-lg border border-line bg-paper shadow-panel">
      <table class="w-full min-w-[720px] text-left text-sm">
        <thead class="border-b border-line bg-canvas text-[11px] font-bold uppercase text-quiet"><tr><th class="px-4 py-3">题目</th><th class="px-4 py-3">学校</th><th class="px-4 py-3">年份</th><th class="px-4 py-3">标签</th><th class="px-4 py-3">难度</th></tr></thead>
        <tbody v-if="problems.length" class="divide-y divide-line">
          <tr v-for="row in problems" :key="row.id" class="cursor-pointer transition-colors hover:bg-canvas" @click="$router.push('/problems/' + row.id)"><td class="px-4 py-4"><div class="problem-title"><strong>{{ row.title }}</strong><span>{{ row.externalKey || '手工题目' }}</span></div></td><td class="px-4 py-4">{{ row.school }}</td><td class="px-4 py-4">{{ row.year }}</td><td class="px-4 py-4"><span v-for="tag in row.tags" :key="tag" class="plain-tag">{{ tag }}</span></td><td class="px-4 py-4"><span :class="['difficulty', 'difficulty--' + row.difficulty.toLowerCase()]">{{ difficultyText[row.difficulty as Difficulty] }}</span></td></tr>
        </tbody>
      </table>
      <div v-if="!loading && problems.length === 0" class="flex min-h-44 items-center justify-center text-sm text-quiet">没有符合条件的题目</div>
    </div>
  </section>
</template>
