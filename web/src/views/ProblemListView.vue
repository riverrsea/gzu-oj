<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { Bookmark, BookmarkCheck, Building2, CalendarDays, CheckCircle2, Circle, Search, SearchX } from "@lucide/vue";
import { toast } from "../lib/notify";
import { api } from "../api/client";
import type { Difficulty, ProblemSummary } from "../api/types";
import UiInput from "../components/ui/Input.vue";
import { session } from "../stores/session";

const router = useRouter();

/** 题库请求是否仍在加载，用于控制骨架屏。 */
const loading = ref(false);
/** 当前从公开题库接口加载的题目列表。 */
const problems = ref<ProblemSummary[]>([]);
/** 用户输入的题目标题关键字。 */
const search = ref("");
/** 用户输入的学校关键字。 */
const schoolSearch = ref("");
/** 用户输入的年份关键字，保留字符串以支持输入年份前缀。 */
const yearSearch = ref("");
/** 当前选中的难度筛选项，空字符串代表全部难度。 */
const difficulty = ref<Difficulty | "">("");
/** 后端难度枚举对应的中文显示文本。 */
const difficultyText: Record<Difficulty, string> = { EASY: "简单", MEDIUM: "中等", HARD: "困难" };
/** 题库顶部可选择的难度筛选项。 */
const difficultyOptions: Array<{ value: Difficulty | ""; label: string }> = [
  { value: "", label: "全部" },
  { value: "EASY", label: "简单" },
  { value: "MEDIUM", label: "中等" },
  { value: "HARD", label: "困难" },
];
/** 当前用户已经收藏的题目标识。收藏属于题目而不是题目版本。 */
const favoriteIds = ref<Set<string>>(new Set());
/** 正在切换收藏的题目，避免连续点击产生重复请求。 */
const favoriteBusyIds = ref<Set<string>>(new Set());
/** 当前用户已经正式通过的题目标识，跨版本合并。 */
const solvedIds = ref<Set<string>>(new Set());

/** 按标题、学校、年份和难度筛选已加载的公开题目，不改变后端题库接口。 */
const visibleProblems = computed(() => {
  const keyword = search.value.trim().toLocaleLowerCase();
  const schoolKeyword = schoolSearch.value.trim().toLocaleLowerCase();
  const yearKeyword = yearSearch.value.trim();
  return problems.value.filter((problem) => {
    const matchesDifficulty = !difficulty.value || problem.difficulty === difficulty.value;
    const matchesTitle = !keyword || problem.title.toLocaleLowerCase().includes(keyword);
    const matchesSchool = !schoolKeyword || problem.school.toLocaleLowerCase().includes(schoolKeyword);
    const matchesYear = !yearKeyword || String(problem.year).includes(yearKeyword);
    return matchesDifficulty && matchesTitle && matchesSchool && matchesYear;
  });
});

/** 加载所有已发布题目，失败时显示统一错误提示。 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    problems.value = await api.problems({});
    await Promise.all([loadFavorites(), loadSolvedProblems()]);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "题库加载失败");
  } finally {
    loading.value = false;
  }
}

/** 读取已解决题目标识；未登录时保持空集合。 */
async function loadSolvedProblems(): Promise<void> {
  if (!session.user) {
    solvedIds.value = new Set();
    return;
  }
  try {
    solvedIds.value = new Set(await api.solvedProblemIds());
  } catch {
    solvedIds.value = new Set();
  }
}

/** 读取收藏列表；未登录时保持空集合，不请求受保护接口。 */
async function loadFavorites(): Promise<void> {
  if (!session.user) {
    favoriteIds.value = new Set();
    return;
  }
  try {
    favoriteIds.value = new Set((await api.favorites()).map((item) => item.problemId));
  } catch {
    favoriteIds.value = new Set();
  }
}

/** 返回题目当前收藏状态。 */
function isFavorite(problem: ProblemSummary): boolean {
  return favoriteIds.value.has(problem.id);
}

/** 返回题目是否曾经有正式通过记录。 */
function isSolved(problem: ProblemSummary): boolean {
  return solvedIds.value.has(problem.id);
}

/** 切换题库中的收藏状态。 */
async function toggleFavorite(problem: ProblemSummary): Promise<void> {
  if (!session.user) {
    await router.push({ path: "/login", query: { redirect: router.currentRoute.value.fullPath } });
    return;
  }
  if (favoriteBusyIds.value.has(problem.id)) return;
  favoriteBusyIds.value = new Set([...favoriteBusyIds.value, problem.id]);
  const next = !isFavorite(problem);
  try {
    if (next) await api.favorite(problem.id);
    else await api.unfavorite(problem.id);
    const updated = new Set(favoriteIds.value);
    if (next) updated.add(problem.id);
    else updated.delete(problem.id);
    favoriteIds.value = updated;
    toast.success(next ? "已收藏" : "已取消收藏");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : (next ? "收藏失败" : "取消收藏失败"));
  } finally {
    const updated = new Set(favoriteBusyIds.value);
    updated.delete(problem.id);
    favoriteBusyIds.value = updated;
  }
}

onMounted(() => {
  void load();
});
watch(() => session.user?.id, () => {
  void loadFavorites();
  void loadSolvedProblems();
});
</script>

<template>
  <section class="content-page content-page--modern oj-page problem-catalog-page">
    <div class="page-heading problem-catalog-heading">
      <h1>题库</h1>
    </div>
    <div class="problem-catalog-toolbar">
      <div class="problem-catalog-tabs" role="tablist" aria-label="难度">
        <button
          v-for="option in difficultyOptions"
          :key="option.value || 'all'"
          type="button"
          role="tab"
          :aria-selected="difficulty === option.value"
          :class="{ active: difficulty === option.value }"
          @click="difficulty = option.value"
        >
          {{ option.label }}
        </button>
      </div>
      <div class="problem-catalog-searches">
        <label class="problem-catalog-search problem-catalog-search--title">
          <Search :size="16" aria-hidden="true" />
          <UiInput v-model="search" type="search" placeholder="搜索题目" aria-label="搜索题目" />
        </label>
        <label class="problem-catalog-search problem-catalog-search--school">
          <Building2 :size="16" aria-hidden="true" />
          <UiInput v-model="schoolSearch" type="search" placeholder="搜索学校" aria-label="搜索学校" />
        </label>
        <label class="problem-catalog-search problem-catalog-search--year">
          <CalendarDays :size="16" aria-hidden="true" />
          <UiInput v-model="yearSearch" type="number" min="1900" max="2200" placeholder="年份" aria-label="搜索年份" />
        </label>
      </div>
    </div>
    <div v-if="loading" class="problem-catalog-list" aria-busy="true" aria-label="正在加载题库">
      <div v-for="index in 8" :key="index" class="problem-catalog-skeleton">
        <span />
        <div><i /><i /></div>
        <b />
      </div>
    </div>
    <div v-else-if="visibleProblems.length" class="problem-catalog-list">
      <article
        v-for="row in visibleProblems"
        :key="row.id"
        class="problem-catalog-row"
        role="link"
        tabindex="0"
        @click="router.push('/problems/' + row.id)"
        @keydown.enter="router.push('/problems/' + row.id)"
      >
        <CheckCircle2 v-if="isSolved(row)" class="problem-catalog-status problem-catalog-status--solved" :size="20" aria-label="已解决" />
        <Circle v-else class="problem-catalog-status" :size="20" aria-hidden="true" />
        <div class="problem-catalog-main">
          <strong>{{ row.title }}</strong>
          <div v-if="row.tags.length" class="problem-catalog-tags">
            <span v-for="tag in row.tags.slice(0, 3)" :key="tag">{{ tag }}</span>
            <em v-if="row.tags.length > 3">+{{ row.tags.length - 3 }}</em>
          </div>
        </div>
        <div class="problem-catalog-context">
          <span>{{ row.school || '未注明学校' }}</span>
          <span>{{ row.year }}</span>
        </div>
        <span :class="['problem-catalog-difficulty', 'problem-catalog-difficulty--' + row.difficulty.toLowerCase()]">
          <i aria-hidden="true" />{{ difficultyText[row.difficulty] }}
        </span>
        <button
          type="button"
          class="problem-catalog-favorite"
          :class="{ 'is-favorited': isFavorite(row) }"
          :aria-label="isFavorite(row) ? '取消收藏' : '收藏题目'"
          :aria-pressed="isFavorite(row)"
          :title="isFavorite(row) ? '取消收藏' : '收藏题目'"
          :disabled="favoriteBusyIds.has(row.id)"
          @click.stop="toggleFavorite(row)"
        >
          <BookmarkCheck v-if="isFavorite(row)" :size="17" fill="currentColor" aria-hidden="true" />
          <Bookmark v-else :size="17" aria-hidden="true" />
        </button>
      </article>
    </div>
    <div v-else class="problem-catalog-empty">
      <SearchX :size="28" aria-hidden="true" />
      <strong>没有找到题目</strong>
      <button v-if="search || schoolSearch || yearSearch || difficulty" type="button" @click="search = ''; schoolSearch = ''; yearSearch = ''; difficulty = ''">清除筛选</button>
    </div>
  </section>
</template>
