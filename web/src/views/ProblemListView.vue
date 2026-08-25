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

/** 切换题库页的难度 Tab，并复用现有题库查询接口。 */
function selectDifficulty(value?: Difficulty): void {
  filters.difficulty = value;
  void load();
}

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
  <section class="content-page content-page--modern oj-page oj-catalog-page">
    <div class="page-heading">
      <div><h1>题库</h1><p>按学校、年份、标签与难度筛选已发布题目</p></div>
      <span class="result-count">{{ problems.length }} 题</span>
    </div>
    <div class="problem-toolbar-leetrank">
      <div class="problem-difficulty-tabs" role="tablist" aria-label="按难度筛选">
        <button type="button" :class="{ active: !filters.difficulty }" @click="selectDifficulty()">全部</button>
        <button type="button" :class="{ active: filters.difficulty === 'EASY' }" @click="selectDifficulty('EASY')">简单</button>
        <button type="button" :class="{ active: filters.difficulty === 'MEDIUM' }" @click="selectDifficulty('MEDIUM')">中等</button>
        <button type="button" :class="{ active: filters.difficulty === 'HARD' }" @click="selectDifficulty('HARD')">困难</button>
      </div>
      <form class="filter-bar catalog-toolbar--modern" @submit.prevent="load">
        <UiInput v-model="filters.school" placeholder="学校" />
        <UiNumberField v-model="filters.year" :min="1900" :max="2200" placeholder="年份" />
        <UiInput v-model="filters.tag" placeholder="标签" />
        <UiButton type="submit" :loading="loading"><Search :size="16" />筛选</UiButton>
      </form>
    </div>
    <div class="problem-list-surface">
      <div v-if="problems.length" class="problem-list-leetrank">
        <article v-for="(row, index) in problems" :key="row.id" class="problem-row-leetrank" role="link" tabindex="0" @click="$router.push('/problems/' + row.id)" @keydown.enter="$router.push('/problems/' + row.id)">
          <div class="problem-row-index"><span>{{ String(index + 1).padStart(2, '0') }}</span><i :class="['problem-difficulty-dot', 'problem-difficulty-dot--' + row.difficulty.toLowerCase()]" aria-hidden="true" /></div>
          <div class="problem-row-main"><strong>{{ row.title }}</strong><span>{{ row.externalKey || '手工题目' }}</span><div class="problem-row-tags"><span v-for="tag in row.tags" :key="tag" class="plain-tag">{{ tag }}</span></div></div>
          <div class="problem-row-meta"><span>{{ row.school || '未注明学校' }}</span><span>{{ row.year }}</span><strong :class="['difficulty', 'difficulty--' + row.difficulty.toLowerCase()]">{{ difficultyText[row.difficulty as Difficulty] }}</strong></div>
        </article>
      </div>
      <div v-else-if="!loading" class="problem-list-empty">没有符合条件的题目</div>
      <div v-else class="problem-list-empty">正在加载题库…</div>
    </div>
  </section>
</template>
