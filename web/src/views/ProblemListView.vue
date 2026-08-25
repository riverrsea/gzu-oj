<script setup lang="ts">
import { onMounted, ref } from "vue";
import { toast } from "../lib/notify";
import { api } from "../api/client";
import type { Difficulty, ProblemSummary } from "../api/types";

const loading = ref(false);
const problems = ref<ProblemSummary[]>([]);
const difficultyText: Record<Difficulty, string> = { EASY: "简单", MEDIUM: "中等", HARD: "困难" };

async function load(): Promise<void> {
  loading.value = true;
  try {
    problems.value = await api.problems({});
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "题库加载失败");
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="content-page content-page--modern oj-page oj-catalog-page">
    <div class="page-heading">
      <h1>题库</h1>
    </div>
    <div class="problem-list-surface">
      <div v-if="problems.length" class="problem-list-leetrank">
        <article v-for="(row, index) in problems" :key="row.id" class="problem-row-leetrank" role="link" tabindex="0" @click="$router.push('/problems/' + row.id)" @keydown.enter="$router.push('/problems/' + row.id)">
          <div class="problem-row-index"><span>{{ String(index + 1).padStart(2, '0') }}</span><i :class="['problem-difficulty-dot', 'problem-difficulty-dot--' + row.difficulty.toLowerCase()]" aria-hidden="true" /></div>
          <div class="problem-row-main"><strong>{{ row.title }}</strong><div class="problem-row-tags"><span v-for="tag in row.tags" :key="tag" class="plain-tag">{{ tag }}</span></div></div>
          <div class="problem-row-meta"><span>{{ row.school || '未注明学校' }}</span><span>{{ row.year }}</span><strong :class="['difficulty', 'difficulty--' + row.difficulty.toLowerCase()]">{{ difficultyText[row.difficulty as Difficulty] }}</strong></div>
        </article>
      </div>
      <div v-else-if="!loading" class="problem-list-empty">没有符合条件的题目</div>
      <div v-else class="problem-list-empty">正在加载题库…</div>
    </div>
  </section>
</template>
