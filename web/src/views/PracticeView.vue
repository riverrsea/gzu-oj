<script setup lang="ts">
import { onMounted, ref } from "vue";
import { toast } from "../lib/notify";
import { api } from "../api/client";
import type { UserProblemSummary, WrongProblem } from "../api/types";
import UiTable from "../components/ui/Table.vue";

const tab = ref("wrong");
const loading = ref(false);
const wrong = ref<WrongProblem[]>([]);
const favorites = ref<UserProblemSummary[]>([]);

async function load(): Promise<void> {
  loading.value = true;
  try { [wrong.value, favorites.value] = await Promise.all([api.wrongProblems(), api.favorites()]); }
  catch (error) { toast.error(error instanceof Error ? error.message : "练习簿加载失败"); }
  finally { loading.value = false; }
}

onMounted(load);
</script>

<template>
  <section class="content-page content-page--modern oj-page practice-page">
    <div class="page-heading"><h1>练习簿</h1></div>
    <div class="ui-tabs">
      <div class="ui-tabs-list" role="tablist">
        <button type="button" :class="{ active: tab === 'wrong' }" @click="tab = 'wrong'">错题本</button>
        <button type="button" :class="{ active: tab === 'favorite' }" @click="tab = 'favorite'">收藏</button>
      </div>
      <div v-if="tab === 'wrong'" class="ui-tab-panel">
        <UiTable>
          <thead class="border-b border-line bg-canvas text-left text-[11px] font-bold uppercase text-quiet"><tr><th class="px-4 py-3">题目</th><th class="px-4 py-3">学校</th><th class="px-4 py-3">历史最高分</th><th class="px-4 py-3">状态</th><th class="px-4 py-3">最近练习</th></tr></thead>
          <tbody v-if="wrong.length" class="divide-y divide-line"><tr v-for="row in wrong" :key="row.problem.problemId" class="cursor-pointer transition-colors hover:bg-canvas" @click="$router.push('/problems/' + row.problem.problemId)"><td class="px-4 py-4">{{ row.problem.title }}</td><td class="px-4 py-4">{{ row.problem.school }}</td><td class="px-4 py-4">{{ row.bestScore }}</td><td class="px-4 py-4"><span :class="['status-text', row.solvedAt ? 'status-text--ac' : 'status-text--wa']">{{ row.solvedAt ? '已解决' : '待解决' }}</span></td><td class="px-4 py-4">{{ new Date(row.lastWrongAt).toLocaleString() }}</td></tr></tbody>
          <tbody v-else><tr><td colspan="5" class="h-44 text-center text-sm text-quiet">{{ loading ? '加载中…' : '错题本暂时为空' }}</td></tr></tbody>
        </UiTable>
      </div>
      <div v-else class="ui-tab-panel">
        <UiTable>
          <thead class="border-b border-line bg-canvas text-left text-[11px] font-bold uppercase text-quiet"><tr><th class="px-4 py-3">题目</th><th class="px-4 py-3">学校</th><th class="px-4 py-3">年份</th><th class="px-4 py-3">难度</th></tr></thead>
          <tbody v-if="favorites.length" class="divide-y divide-line"><tr v-for="row in favorites" :key="row.problemId" class="cursor-pointer transition-colors hover:bg-canvas" @click="$router.push('/problems/' + row.problemId)"><td class="px-4 py-4">{{ row.title }}</td><td class="px-4 py-4">{{ row.school }}</td><td class="px-4 py-4">{{ row.year }}</td><td class="px-4 py-4">{{ row.difficulty }}</td></tr></tbody>
          <tbody v-else><tr><td colspan="4" class="h-44 text-center text-sm text-quiet">{{ loading ? '加载中…' : '还没有收藏题目' }}</td></tr></tbody>
        </UiTable>
      </div>
    </div>
  </section>
</template>
