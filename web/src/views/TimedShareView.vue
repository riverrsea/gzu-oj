<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Clock3 } from "@lucide/vue";
import { toast } from "../lib/notify";
import { formatChinaDateTime } from "../lib/time";
import { useRoute } from "vue-router";
import { api } from "../api/client";
import type { TimedAttempt } from "../api/types";
import UiEmptyState from "../components/ui/EmptyState.vue";
import UiTable from "../components/ui/Table.vue";

/** 当前只读分享作答。 */
const attempt = ref<TimedAttempt>();
/** 页面加载状态。 */
const loading = ref(true);
/** 当前路由。 */
const route = useRoute();

/** 加载不可撤销哈希匹配后的只读结果。 */
async function load(): Promise<void> {
  try {
    attempt.value = await api.sharedTimedAttempt(String(route.params.token));
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "分享链接无效或已撤销");
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section class="content-page content-page--modern oj-page share-page loading-shell" :aria-busy="loading">
    <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>
    <template v-if="attempt">
      <div class="page-heading"><div><h1>{{ attempt.paper.title }}</h1><p>个人计时套卷只读结果</p></div><strong class="share-score">{{ attempt.totalScore }}/{{ attempt.maximumScore }} 分</strong></div>
      <div class="share-meta"><span><Clock3 :size="16" />{{ attempt.paper.durationMinutes }} 分钟</span><span>{{ attempt.finished ? '已结束' : '进行中' }}</span><span>{{ formatChinaDateTime(attempt.startedAt, { dateStyle: 'medium', timeStyle: 'short' }) }}</span></div>
      <UiTable>
        <thead><tr><th>#</th><th>题目</th><th>最高分</th></tr></thead>
        <tbody><tr v-for="problem in attempt.paper.problems" :key="problem.versionId"><td>{{ problem.ordinal }}</td><td>{{ problem.title }}</td><td>{{ attempt.scores[problem.problemId] ?? 0 }}</td></tr></tbody>
      </UiTable>
    </template>
    <UiEmptyState v-else-if="!loading" description="分享链接无效或已撤销" />
  </section>
</template>
