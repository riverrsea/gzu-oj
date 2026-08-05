<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { Search } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { api } from "../api/client";
import type { Difficulty, ProblemSummary } from "../api/types";

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
  <section class="content-page">
    <div class="page-heading">
      <div><h1>题库</h1><p>按学校、年份、标签与难度筛选已发布题目</p></div>
      <span class="result-count">{{ problems.length }} 题</span>
    </div>
    <form class="filter-bar" @submit.prevent="load">
      <el-input v-model="filters.school" clearable placeholder="学校" />
      <el-input-number v-model="filters.year" :min="1900" :max="2200" :controls="false" placeholder="年份" />
      <el-input v-model="filters.tag" clearable placeholder="标签" />
      <el-select v-model="filters.difficulty" clearable placeholder="难度">
        <el-option label="简单" value="EASY" /><el-option label="中等" value="MEDIUM" /><el-option label="困难" value="HARD" />
      </el-select>
      <el-button native-type="submit" type="primary" :loading="loading"><Search :size="16" />筛选</el-button>
    </form>
    <el-table v-loading="loading" :data="problems" class="problem-table" row-key="id" @row-click="(row: ProblemSummary) => $router.push('/problems/' + row.id)">
      <el-table-column label="题目" min-width="280">
        <template #default="{ row }"><div class="problem-title"><strong>{{ row.title }}</strong><span>{{ row.sourceKey }}</span></div></template>
      </el-table-column>
      <el-table-column prop="school" label="学校" min-width="170" />
      <el-table-column prop="year" label="年份" width="90" />
      <el-table-column label="标签" min-width="180">
        <template #default="{ row }"><span v-for="tag in row.tags" :key="tag" class="plain-tag">{{ tag }}</span></template>
      </el-table-column>
      <el-table-column label="难度" width="100">
        <template #default="{ row }"><span :class="['difficulty', 'difficulty--' + row.difficulty.toLowerCase()]">{{ difficultyText[row.difficulty as Difficulty] }}</span></template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && problems.length === 0" description="没有符合条件的题目" />
  </section>
</template>
