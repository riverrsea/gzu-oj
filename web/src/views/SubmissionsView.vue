<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { api } from "../api/client";
import type { Submission } from "../api/types";

const loading = ref(false);
const submissions = ref<Submission[]>([]);

async function load(): Promise<void> {
  loading.value = true;
  try { submissions.value = await api.submissions(); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : "提交记录加载失败"); }
  finally { loading.value = false; }
}

onMounted(load);
</script>

<template>
  <section class="content-page">
    <div class="page-heading"><div><h1>提交记录</h1><p>源码仅本人和管理员可见，测点结果不包含隐藏输入输出</p></div></div>
    <el-table v-loading="loading" :data="submissions" row-key="id">
      <el-table-column prop="createdAt" label="提交时间" min-width="190"><template #default="{ row }">{{ new Date(row.createdAt).toLocaleString() }}</template></el-table-column>
      <el-table-column prop="language" label="语言" width="110" />
      <el-table-column label="状态" width="130"><template #default="{ row }"><span :class="['status-text', 'status-text--' + row.status.toLowerCase()]">{{ row.status }}</span></template></el-table-column>
      <el-table-column prop="score" label="得分" width="90" />
      <el-table-column label="测点" min-width="240"><template #default="{ row }">{{ row.testCases.length ? row.testCases.map((item: Submission['testCases'][number]) => item.status).join(' · ') : '等待判题' }}</template></el-table-column>
    </el-table>
  </section>
</template>
