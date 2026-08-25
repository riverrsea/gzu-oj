<script setup lang="ts">
import { onMounted, ref } from "vue";
import { toast } from "../lib/notify";
import { api } from "../api/client";
import type { Submission } from "../api/types";
import UiTable from "../components/ui/Table.vue";

const loading = ref(false);
const submissions = ref<Submission[]>([]);

async function load(): Promise<void> {
  loading.value = true;
  try { submissions.value = await api.submissions(); }
  catch (error) { toast.error(error instanceof Error ? error.message : "提交记录加载失败"); }
  finally { loading.value = false; }
}

onMounted(load);
</script>

<template>
  <section class="content-page content-page--modern oj-page submissions-page">
    <div class="page-heading"><div><h1>提交记录</h1><p>源码仅本人和管理员可见，测点结果不包含隐藏输入输出</p></div></div>
    <UiTable>
      <thead class="border-b border-line bg-canvas text-left text-[11px] font-bold uppercase text-quiet"><tr><th class="px-4 py-3">提交时间</th><th class="px-4 py-3">语言</th><th class="px-4 py-3">状态</th><th class="px-4 py-3">得分</th><th class="px-4 py-3">测点</th></tr></thead>
      <tbody v-if="submissions.length" class="divide-y divide-line">
        <tr v-for="row in submissions" :key="row.id" class="transition-colors hover:bg-canvas"><td class="px-4 py-4">{{ new Date(row.createdAt).toLocaleString() }}</td><td class="px-4 py-4">{{ row.language }}</td><td class="px-4 py-4"><span :class="['status-text', 'status-text--' + row.status.toLowerCase()]">{{ row.status }}</span></td><td class="px-4 py-4">{{ row.score }}</td><td class="px-4 py-4">{{ row.testCases.length ? row.testCases.map((item: Submission['testCases'][number]) => item.status).join(' · ') : '等待判题' }}</td></tr>
      </tbody>
      <tbody v-else><tr><td colspan="5" class="h-44 text-center text-sm text-quiet">{{ loading ? '加载中…' : '还没有提交记录' }}</td></tr></tbody>
    </UiTable>
  </section>
</template>
