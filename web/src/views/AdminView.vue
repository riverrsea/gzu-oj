<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { CopyPlus, Edit3, ExternalLink, Plus, Search, Send } from "@lucide/vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRouter } from "vue-router";
import { api } from "../api/client";
import type { AdminProblemSummary, Difficulty, ProblemVersionStatus } from "../api/types";

/** 管理员题库的分页大小。 */
const pageSize = 20;
const router = useRouter();
/** 管理员题库加载状态。 */
const loading = ref(false);
/** 当前页的题目版本。 */
const problems = ref<AdminProblemSummary[]>([]);
/** 符合筛选条件的题目版本总数。 */
const total = ref(0);
/** 当前页码，从一开始计数。 */
const page = ref(1);
/** 题库筛选条件。 */
const filters = reactive<{
  keyword: string;
  school: string;
  year?: number;
  tag: string;
  difficulty?: Difficulty;
  status?: ProblemVersionStatus;
}>({ keyword: "", school: "", year: undefined, tag: "", difficulty: undefined, status: undefined });

/** 难度显示文本。 */
const difficultyText: Record<Difficulty, string> = { EASY: "简单", MEDIUM: "中等", HARD: "困难" };
/** 题目版本状态显示文本。 */
const statusText: Record<ProblemVersionStatus, string> = { DRAFT: "草稿", PUBLISHED: "已发布", WITHDRAWN: "已撤回" };

/** 将后端状态转换为管理员页面显示文本。 */
function statusLabel(status: string): string {
  return statusText[status as ProblemVersionStatus] ?? status;
}

/** 将后端难度转换为管理员页面显示文本。 */
function difficultyLabel(difficulty: string): string {
  return difficultyText[difficulty as Difficulty] ?? difficulty;
}

/** 格式化管理员列表中的时间。 */
function formatDate(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

/** 加载管理员可见的题目版本列表。 */
async function load(resetPage = false): Promise<void> {
  if (resetPage) page.value = 1;
  loading.value = true;
  try {
    const result = await api.adminProblems({ ...filters, page: page.value - 1, size: pageSize });
    problems.value = result.items;
    total.value = result.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "管理员题库加载失败");
  } finally {
    loading.value = false;
  }
}

/** 提交管理员题库筛选条件。 */
function search(): void {
  void load(true);
}

/** 切换管理员题库页码。 */
function changePage(nextPage: number): void {
  page.value = nextPage;
  void load();
}

/** 从列表进入草稿编辑页。 */
function editDraft(versionId: string): void {
  void router.push("/admin/problems/" + versionId + "/edit");
}

/** 复制一个已有版本，在相同逻辑题目下创建新草稿。 */
function createNextVersion(problem: AdminProblemSummary): void {
  void router.push({ path: "/admin/problems/new", query: { fromVersionId: problem.versionId } });
}

/** 发布已经校验过的草稿版本。 */
async function publishDraft(versionId: string): Promise<void> {
  try {
    await ElMessageBox.confirm("发布后该版本不可再编辑，并会成为当前公开版本。", "确认发布", { type: "warning", confirmButtonText: "发布", cancelButtonText: "取消" });
    await api.publishDraft(versionId);
    ElMessage.success("题目版本已发布");
    await load();
  } catch (error) {
    if (error !== "cancel" && error !== "close") ElMessage.error(error instanceof Error ? error.message : "题目发布失败");
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="content-page admin-page">
    <div class="page-heading">
      <div><h1>题库管理</h1><p>查看草稿、已发布题目和不可变历史版本</p></div>
      <div class="heading-actions"><span class="result-count">{{ total }} 个版本</span><el-button type="primary" @click="$router.push('/admin/problems/new')"><Plus :size="16" />新建题目</el-button></div>
    </div>

    <form class="admin-catalog-filters" @submit.prevent="search">
      <el-input v-model="filters.keyword" clearable placeholder="标题或外部题目标识" />
      <el-input v-model="filters.school" clearable placeholder="学校" />
      <el-input-number v-model="filters.year" :min="1900" :max="2200" :controls="false" placeholder="年份" />
      <el-input v-model="filters.tag" clearable placeholder="标签" />
      <el-select v-model="filters.status" clearable placeholder="版本状态"><el-option label="草稿" value="DRAFT" /><el-option label="已发布" value="PUBLISHED" /><el-option label="已撤回" value="WITHDRAWN" /></el-select>
      <el-select v-model="filters.difficulty" clearable placeholder="难度"><el-option label="简单" value="EASY" /><el-option label="中等" value="MEDIUM" /><el-option label="困难" value="HARD" /></el-select>
      <el-button native-type="submit" type="primary" :loading="loading"><Search :size="16" />筛选</el-button>
    </form>

    <el-table v-loading="loading" :data="problems" row-key="versionId" class="admin-catalog-table">
      <el-table-column label="题目" min-width="260"><template #default="{ row }"><div class="problem-title"><strong>{{ row.title }}</strong><span>{{ row.externalKey || '手工题目' }}</span></div></template></el-table-column>
      <el-table-column prop="school" label="学校" min-width="140" />
      <el-table-column prop="year" label="年份" width="76" />
      <el-table-column label="版本" width="72"><template #default="{ row }">v{{ row.versionNumber }}</template></el-table-column>
      <el-table-column label="状态" width="92"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PUBLISHED' ? 'success' : row.status === 'DRAFT' ? 'warning' : 'info'">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="测点" width="105"><template #default="{ row }">{{ row.testCaseCount }} 个 / {{ row.scoreSum }} 分</template></el-table-column>
      <el-table-column label="难度" width="76"><template #default="{ row }"><span :class="['difficulty', 'difficulty--' + row.difficulty.toLowerCase()]">{{ difficultyLabel(row.difficulty) }}</span></template></el-table-column>
      <el-table-column label="创建时间" width="165"><template #default="{ row }">{{ formatDate(row.createdAt) }}</template></el-table-column>
      <el-table-column label="操作" width="230" fixed="right"><template #default="{ row }"><template v-if="row.status === 'PUBLISHED'"><el-button text type="primary" @click.stop="$router.push('/problems/' + row.problemId)"><ExternalLink :size="14" />查看</el-button><el-button text @click.stop="createNextVersion(row)"><CopyPlus :size="14" />新版本</el-button></template><template v-else-if="row.status === 'DRAFT'"><el-button text type="primary" @click.stop="editDraft(row.versionId)"><Edit3 :size="14" />编辑</el-button><el-button text type="success" @click.stop="publishDraft(row.versionId)"><Send :size="14" />发布</el-button></template><el-button v-else text @click.stop="createNextVersion(row)"><CopyPlus :size="14" />新版本</el-button></template></el-table-column>
    </el-table>
    <el-empty v-if="!loading && problems.length === 0" description="没有符合条件的题目版本" />
    <div v-if="total > pageSize" class="admin-catalog-pagination"><el-pagination background layout="prev, pager, next" :current-page="page" :page-size="pageSize" :total="total" @current-change="changePage" /></div>
  </section>
</template>
