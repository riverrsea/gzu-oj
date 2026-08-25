<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Clock3 } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { useRoute } from "vue-router";
import { api } from "../api/client";
import type { TimedAttempt } from "../api/types";

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
    ElMessage.error(error instanceof Error ? error.message : "分享链接无效或已撤销");
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <section v-loading="loading" class="content-page content-page--modern oj-page share-page">
    <template v-if="attempt">
      <div class="page-heading"><div><h1>{{ attempt.paper.title }}</h1><p>个人计时套卷只读结果</p></div><strong class="share-score">{{ attempt.totalScore }} 分</strong></div>
      <div class="share-meta"><span><Clock3 :size="16" />{{ attempt.paper.durationMinutes }} 分钟</span><span>{{ attempt.finished ? '已结束' : '进行中' }}</span><span>{{ new Date(attempt.startedAt).toLocaleString() }}</span></div>
      <el-table :data="attempt.paper.problems" row-key="versionId"><el-table-column prop="ordinal" label="#" width="60" /><el-table-column prop="title" label="题目" /><el-table-column label="最高分" width="100"><template #default="{ row }">{{ attempt.scores[row.problemId] ?? 0 }}</template></el-table-column></el-table>
    </template>
    <el-empty v-else description="分享链接无效或已撤销" />
  </section>
</template>
