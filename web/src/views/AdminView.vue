<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { CopyPlus, Edit3, ExternalLink, RotateCcw, Search, Send } from "@lucide/vue";
import { confirmAction, toast } from "../lib/notify";
import { formatChinaDateTime } from "../lib/time";
import { useRouter } from "vue-router";
import { api } from "../api/client";
import type { AdminProblemSummary, Difficulty, ProblemVersionStatus } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";
import UiInput from "../components/ui/Input.vue";
import UiLabel from "../components/ui/Label.vue";
import UiPagination from "../components/ui/Pagination.vue";
import UiSelectMenu from "../components/ui/SelectMenu.vue";

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

/** 年份筛选项：从当年回溯到 2000 年，倒序排列。 */
const yearOptions = computed(() => {
  const current = new Date().getFullYear();
  return Array.from({ length: current - 1999 }, (_, index) => {
    const year = current - index;
    return { value: String(year), label: String(year) };
  });
});

/** 年份筛选的下拉桥接：UiSelectMenu 使用字符串值，空串表示不限年份。 */
const yearFilter = computed<string>({
  get: () => (filters.year === undefined ? "" : String(filters.year)),
  set: (value) => {
    filters.year = value === "" ? undefined : Number(value);
  },
});

/** 难度显示文本。 */
const difficultyText: Record<Difficulty, string> = { EASY: "简单", MEDIUM: "中等", HARD: "困难" };
/** 题目版本状态显示文本。 */
const statusText: Record<ProblemVersionStatus, string> = { DRAFT: "草稿", PUBLISHED: "已发布", WITHDRAWN: "已撤回" };

/** 状态与难度筛选的下拉选项，标签与列表中的徽标文案保持一致。 */
const statusOptions = Object.entries(statusText).map(([value, label]) => ({ value, label }));
const difficultyOptions = Object.entries(difficultyText).map(([value, label]) => ({ value, label }));

/** 当前是否有生效的筛选条件，用于空状态展示清除动作。 */
const hasActiveFilters = computed(() =>
  Boolean(filters.keyword || filters.school || filters.year || filters.tag || filters.difficulty || filters.status),
);

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
  return formatChinaDateTime(value, { dateStyle: "medium", timeStyle: "short" });
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

/** 清空全部筛选条件并重新加载第一页。 */
function resetFilters(): void {
  filters.keyword = "";
  filters.school = "";
  filters.year = undefined;
  filters.tag = "";
  filters.difficulty = undefined;
  filters.status = undefined;
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

/** 正在发布的版本标识，用于行内发布按钮的加载态。 */
const publishingId = ref<string | null>(null);

/** 发布已经校验过的草稿版本。 */
async function publishDraft(versionId: string): Promise<void> {
  try {
    await confirmAction("发布后该版本不可再编辑，并会成为当前公开版本。\n\n确认发布吗？");
  } catch {
    // 用户在确认对话框中取消或关闭。
    return;
  }
  publishingId.value = versionId;
  try {
    await api.publishDraft(versionId);
    toast.success("题目版本已发布");
    await load();
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "题目发布失败");
  } finally {
    publishingId.value = null;
  }
}

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="admin-page">
    <!-- 筛选卡片：单行弹性排布，操作按钮与输入框底对齐 -->
    <form class="admin-panel admin-filters" @submit.prevent="search">
      <div class="admin-filter-field admin-filter-field--keyword">
        <UiLabel for="filter-keyword">关键词</UiLabel>
        <UiInput id="filter-keyword" v-model="filters.keyword" placeholder="标题或外部题目标识" />
      </div>
      <div class="admin-filter-field">
        <UiLabel for="filter-school">学校</UiLabel>
        <UiInput id="filter-school" v-model="filters.school" placeholder="全部学校" />
      </div>
      <div class="admin-filter-field admin-filter-field--year">
        <UiLabel for="filter-year">年份</UiLabel>
        <UiSelectMenu id="filter-year" v-model="yearFilter" :options="yearOptions" placeholder="全部年份" aria-label="年份" />
      </div>
      <div class="admin-filter-field">
        <UiLabel for="filter-tag">标签</UiLabel>
        <UiInput id="filter-tag" v-model="filters.tag" placeholder="全部标签" />
      </div>
      <div class="admin-filter-field">
        <UiLabel for="filter-status">版本状态</UiLabel>
        <UiSelectMenu id="filter-status" v-model="filters.status" :options="statusOptions" placeholder="全部状态" aria-label="版本状态" />
      </div>
      <div class="admin-filter-field">
        <UiLabel for="filter-difficulty">难度</UiLabel>
        <UiSelectMenu id="filter-difficulty" v-model="filters.difficulty" :options="difficultyOptions" placeholder="全部难度" aria-label="难度" />
      </div>
      <div class="admin-filters-actions">
        <UiButton variant="ghost" :disabled="loading" @click="resetFilters"><RotateCcw :size="15" />重置</UiButton>
        <UiButton type="submit" :loading="loading"><Search :size="16" />筛选</UiButton>
      </div>
    </form>

    <div class="admin-panel admin-table-card loading-shell" :aria-busy="loading">
      <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>
      <!-- 分页条：多于一页时居中显示在列表最上方 -->
      <div v-if="total > pageSize" class="admin-table-bar">
        <UiPagination :page="page" :page-size="pageSize" :total="total" @change="changePage" />
      </div>
      <div class="admin-table-scroll">
        <table class="admin-table">
          <thead>
            <tr><th>题目</th><th>学校</th><th class="admin-cell-center">年份</th><th class="admin-cell-center">版本</th><th>状态</th><th class="admin-cell-center">测点</th><th>难度</th><th>创建时间</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in problems" :key="row.versionId">
              <td>
                <div class="admin-cell-title">
                  <strong>{{ row.title }}</strong>
                  <div class="admin-cell-sub">
                    <span v-if="row.externalKey" class="admin-cell-key">{{ row.externalKey }}</span>
                    <span v-for="tag in row.tags" :key="tag" class="admin-tag">{{ tag }}</span>
                  </div>
                </div>
              </td>
              <td>{{ row.school }}</td>
              <td class="admin-cell-center">{{ row.year }}</td>
              <td class="admin-cell-center">{{ row.versionNumber }}</td>
              <td><span :class="['admin-status', 'admin-status--' + row.status.toLowerCase()]">{{ statusLabel(row.status) }}</span></td>
              <td class="admin-cell-center">{{ row.testCaseCount }}</td>
              <td><span :class="['admin-difficulty', 'admin-difficulty--' + row.difficulty.toLowerCase()]">{{ difficultyLabel(row.difficulty) }}</span></td>
              <td>{{ formatDate(row.createdAt) }}</td>
              <td>
                <div class="admin-table-actions">
                  <template v-if="row.status === 'PUBLISHED'">
                    <UiButton variant="ghost" size="icon" title="查看" aria-label="查看" @click.stop="$router.push('/problems/' + row.problemId)"><ExternalLink :size="15" /></UiButton>
                    <UiButton variant="ghost" size="icon" title="基于此版本新建草稿" aria-label="基于此版本新建草稿" @click.stop="createNextVersion(row)"><CopyPlus :size="15" /></UiButton>
                  </template>
                  <template v-else-if="row.status === 'DRAFT'">
                    <UiButton variant="ghost" size="icon" title="编辑" aria-label="编辑" @click.stop="editDraft(row.versionId)"><Edit3 :size="15" /></UiButton>
                    <UiButton variant="ghost" size="icon" title="发布" aria-label="发布" :loading="publishingId === row.versionId" :disabled="publishingId === row.versionId" @click.stop="publishDraft(row.versionId)"><Send :size="15" /></UiButton>
                  </template>
                  <UiButton v-else variant="ghost" size="icon" title="基于此版本新建草稿" aria-label="基于此版本新建草稿" @click.stop="createNextVersion(row)"><CopyPlus :size="15" /></UiButton>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <UiEmptyState v-if="!loading && problems.length === 0" description="没有符合条件的题目版本">
        <button v-if="hasActiveFilters" type="button" class="admin-empty-reset" @click="resetFilters">清除全部筛选</button>
      </UiEmptyState>
    </div>
  </section>
</template>
