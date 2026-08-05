<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { api } from "../api/client";
import type { UserProblemSummary, WrongProblem } from "../api/types";

const tab = ref("wrong");
const loading = ref(false);
const wrong = ref<WrongProblem[]>([]);
const favorites = ref<UserProblemSummary[]>([]);

async function load(): Promise<void> {
  loading.value = true;
  try { [wrong.value, favorites.value] = await Promise.all([api.wrongProblems(), api.favorites()]); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : "练习簿加载失败"); }
  finally { loading.value = false; }
}

onMounted(load);
</script>

<template>
  <section class="content-page">
    <div class="page-heading"><div><h1>练习簿</h1><p>错题历史在满分后仍保留，已解决状态会单独标记</p></div></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="错题本" name="wrong">
        <el-table v-loading="loading" :data="wrong" row-key="problem.problemId" @row-click="(row: WrongProblem) => $router.push('/problems/' + row.problem.problemId)">
          <el-table-column prop="problem.title" label="题目" min-width="260" />
          <el-table-column prop="problem.school" label="学校" min-width="160" />
          <el-table-column prop="bestScore" label="历史最高分" width="120" />
          <el-table-column label="状态" width="110"><template #default="{ row }"><span :class="row.solvedAt ? 'status-text--ac' : 'status-text--wa'">{{ row.solvedAt ? '已解决' : '待解决' }}</span></template></el-table-column>
          <el-table-column label="最近练习" width="190"><template #default="{ row }">{{ new Date(row.lastWrongAt).toLocaleString() }}</template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="收藏" name="favorite">
        <el-table v-loading="loading" :data="favorites" row-key="problemId" @row-click="(row: UserProblemSummary) => $router.push('/problems/' + row.problemId)">
          <el-table-column prop="title" label="题目" min-width="260" /><el-table-column prop="school" label="学校" min-width="160" /><el-table-column prop="year" label="年份" width="100" /><el-table-column prop="difficulty" label="难度" width="100" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>
