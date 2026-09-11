<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { BookOpen, CopyPlus, Edit3, ExternalLink, ListFilter, Plus, RotateCcw, Search, Send } from "@lucide/vue";
import { confirmAction, toast } from "../lib/notify";
import { formatChinaDateTime } from "../lib/time";
import { useRouter } from "vue-router";
import { api } from "../api/client";
import type { AdminProblemSummary, Difficulty, ProblemVersionStatus } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";
import UiInput from "../components/ui/Input.vue";
import UiLabel from "../components/ui/Label.vue";
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

/** 总页数，用于表脚的分页信息展示。 */
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));

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
  <section class="admin-page">
    <div class="admin-page-head">
      <div>
        <h1>题库管理</h1>
        <p>筛选、编辑并发布题目版本；草稿发布后对选手可见且不可再编辑。</p>
      </div>
      <div class="admin-page-actions">
        <span class="admin-count-pill">{{ total }} 个版本</span>
        <UiButton @click="$router.push('/admin/problems/new')"><Plus :size="16" />新建题目</UiButton>
      </div>
    </div>

    <form class="admin-panel" @submit.prevent="search">
      <header class="admin-panel-head">
        <span class="admin-panel-icon"><ListFilter :size="17" /></span>
        <div class="admin-panel-titles"><h2>筛选条件</h2><p>按标题、来源或状态缩小版本范围</p></div>
      </header>
      <div class="admin-panel-body">
        <div class="admin-filters-grid">
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
            <UiNumberField id="filter-year" v-model="filters.year" :min="1900" :max="2200" placeholder="全部" />
          </div>
          <div class="admin-filter-field">
            <UiLabel for="filter-tag">标签</UiLabel>
            <UiInput id="filter-tag" v-model="filters.tag" placeholder="全部标签" />
          </div>
          <div class="admin-filter-field">
            <UiLabel for="filter-status">版本状态</UiLabel>
            <UiSelect id="filter-status" v-model="filters.status" placeholder="全部状态"><option value="DRAFT">草稿</option><option value="PUBLISHED">已发布</option><option value="WITHDRAWN">已撤回</option></UiSelect>
          </div>
          <div class="admin-filter-field">
            <UiLabel for="filter-difficulty">难度</UiLabel>
            <UiSelect id="filter-difficulty" v-model="filters.difficulty" placeholder="全部难度"><option value="EASY">简单</option><option value="MEDIUM">中等</option><option value="HARD">困难</option></UiSelect>
          </div>
        </div>
        <div class="admin-filters-actions">
          <UiButton variant="ghost" :disabled="loading" @click="resetFilters"><RotateCcw :size="15" />重置</UiButton>
          <UiButton type="submit" :loading="loading"><Search :size="16" />筛选</UiButton>
        </div>
      </div>
    </form>

    <div class="admin-panel admin-table-card loading-shell" :aria-busy="loading">
      <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>
      <header class="admin-panel-head">
        <span class="admin-panel-icon"><BookOpen :size="17" /></span>
        <div class="admin-panel-titles"><h2>版本列表</h2><p>每道题目可包含多个不可变版本</p></div>
      </header>
      <div class="admin-table-scroll">
        <table class="admin-table">
          <thead>
            <tr><th>题目</th><th>学校</th><th>年份</th><th>版本</th><th>状态</th><th>测点</th><th>难度</th><th>创建时间</th><th>操作</th></tr>
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
              <td>{{ row.year }}</td>
              <td>v{{ row.versionNumber }}</td>
              <td><span :class="['admin-status', 'admin-status--' + row.status.toLowerCase()]">{{ statusLabel(row.status) }}</span></td>
              <td>{{ row.testCaseCount }} 个</td>
              <td><span :class="['admin-difficulty', 'admin-difficulty--' + row.difficulty.toLowerCase()]">{{ difficultyLabel(row.difficulty) }}</span></td>
              <td>{{ formatDate(row.createdAt) }}</td>
              <td>
                <div class="admin-table-actions">
                  <template v-if="row.status === 'PUBLISHED'">
                    <UiButton variant="ghost" size="sm" @click.stop="$router.push('/problems/' + row.problemId)"><ExternalLink :size="14" />查看</UiButton>
                    <UiButton variant="ghost" size="sm" @click.stop="createNextVersion(row)"><CopyPlus :size="14" />新版本</UiButton>
                  </template>
                  <template v-else-if="row.status === 'DRAFT'">
                    <UiButton variant="ghost" size="sm" @click.stop="editDraft(row.versionId)"><Edit3 :size="14" />编辑</UiButton>
                    <UiButton variant="ghost" size="sm" @click.stop="publishDraft(row.versionId)"><Send :size="14" />发布</UiButton>
                  </template>
                  <UiButton v-else variant="ghost" size="sm" @click.stop="createNextVersion(row)"><CopyPlus :size="14" />新版本</UiButton>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <UiEmptyState v-if="!loading && problems.length === 0" description="没有符合条件的题目版本" />
      <footer v-if="total > 0" class="admin-table-foot">
        <span class="admin-table-total">第 {{ page }} / {{ pageCount }} 页 · 共 {{ total }} 个版本</span>
        <UiPagination :page="page" :page-size="pageSize" :total="total" @change="changePage" />
      </footer>
    </div>
  </section>
</template>
