<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { CopyPlus, Edit3, ExternalLink, Plus, Search, Send } from "@lucide/vue";
import { confirmAction, toast } from "../lib/notify";
import { useRouter } from "vue-router";
import { api } from "../api/client";
import type { AdminProblemSummary, Difficulty, ProblemVersionStatus } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiPagination from "../components/ui/Pagination.vue";
import UiSelect from "../components/ui/Select.vue";

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
    toast.error(error instanceof Error ? error.message : "管理员题库加载失败");
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
    await confirmAction("发布后该版本不可再编辑，并会成为当前公开版本。\n\n确认发布吗？");
    await api.publishDraft(versionId);
    toast.success("题目版本已发布");
    await load();
  } catch (error) {
    if (error !== "cancel" && error !== "close") toast.error(error instanceof Error ? error.message : "题目发布失败");
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="content-page content-page--modern admin-page admin-page--modern">
    <div class="page-heading">
      <h1>题库管理</h1>
      <div class="heading-actions"><span class="result-count">{{ total }} 个版本</span><UiButton @click="$router.push('/admin/problems/new')"><Plus :size="16" />新建题目</UiButton></div>
    </div>

    <form class="admin-catalog-filters admin-catalog-filters--modern" @submit.prevent="search">
      <UiInput v-model="filters.keyword" placeholder="标题或外部题目标识" />
      <UiInput v-model="filters.school" placeholder="学校" />
      <UiNumberField v-model="filters.year" :min="1900" :max="2200" placeholder="年份" />
      <UiInput v-model="filters.tag" placeholder="标签" />
      <UiSelect v-model="filters.status" placeholder="版本状态"><option value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option><option value="WITHDRAWN">已撤回</option></UiSelect>
      <UiSelect v-model="filters.difficulty" placeholder="难度"><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></UiSelect>
      <UiButton type="submit" :loading="loading"><Search :size="16" />筛选</UiButton>
    </form>

    <div class="admin-catalog-table admin-catalog-table--modern loading-shell" :aria-busy="loading">
      <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>
      <div class="w-full overflow-x-auto"><table class="w-full min-w-[1120px] text-left text-sm"><thead><tr><th>题目</th><th>学校</th><th>年份</th><th>版本</th><th>状态</th><th>测点</th><th>难度</th><th>创建时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in problems" :key="row.versionId"><td><div class="problem-title"><strong>{{ row.title }}</strong></div></td><td>{{ row.school }}</td><td>{{ row.year }}</td><td>v{{ row.versionNumber }}</td><td><span :class="['status-pill', 'status-pill--' + row.status.toLowerCase()]">{{ statusLabel(row.status) }}</span></td><td>{{ row.testCaseCount }} 个</td><td><span :class="['difficulty', 'difficulty--' + row.difficulty.toLowerCase()]">{{ difficultyLabel(row.difficulty) }}</span></td><td>{{ formatDate(row.createdAt) }}</td><td><div class="table-actions"><template v-if="row.status === 'PUBLISHED'"><UiButton variant="ghost" size="sm" @click.stop="$router.push('/problems/' + row.problemId)"><ExternalLink :size="14" />查看</UiButton><UiButton variant="ghost" size="sm" @click.stop="createNextVersion(row)"><CopyPlus :size="14" />新版本</UiButton></template><template v-else-if="row.status === 'DRAFT'"><UiButton variant="ghost" size="sm" @click.stop="editDraft(row.versionId)"><Edit3 :size="14" />编辑</UiButton><UiButton variant="ghost" size="sm" @click.stop="publishDraft(row.versionId)"><Send :size="14" />发布</UiButton></template><UiButton v-else variant="ghost" size="sm" @click.stop="createNextVersion(row)"><CopyPlus :size="14" />新版本</UiButton></div></td></tr></tbody></table></div>
      <UiEmptyState v-if="!loading && problems.length === 0" description="没有符合条件的题目版本" />
    </div>
    <div v-if="total > pageSize" class="admin-catalog-pagination"><UiPagination :page="page" :page-size="pageSize" :total="total" @change="changePage" /></div>
  </section>
</template>
