<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Bookmark, BookmarkCheck, CheckCircle2, CircleAlert, RefreshCw, RotateCcw } from "@lucide/vue";
import { useRouter } from "vue-router";
import { toast } from "../lib/notify";
import { api } from "../api/client";
import type { Difficulty, UserProblemSummary, WrongProblem } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";

const router = useRouter();
const tab = ref<"wrong" | "favorite">("wrong");
const wrongFilter = ref<"unresolved" | "solved" | "all">("unresolved");
const loading = ref(false);
const wrong = ref<WrongProblem[]>([]);
const favorites = ref<UserProblemSummary[]>([]);
const favoriteBusyIds = ref<Set<string>>(new Set());

const unresolvedWrong = computed(() => wrong.value.filter((row) => !row.solvedAt));
const solvedWrong = computed(() => wrong.value.filter((row) => Boolean(row.solvedAt)));
const visibleWrong = computed(() => {
  if (wrongFilter.value === "unresolved") return unresolvedWrong.value;
  if (wrongFilter.value === "solved") return solvedWrong.value;
  return wrong.value;
});

const difficultyText: Record<Difficulty, string> = { EASY: "简单", MEDIUM: "中等", HARD: "困难" };

/** 读取错题历史和收藏列表；错题记录即使已解决也保留。 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    [wrong.value, favorites.value] = await Promise.all([api.wrongProblems(), api.favorites()]);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "练习簿加载失败");
  } finally {
    loading.value = false;
  }
}

/** 使用题目 ID 进入工作区，同时保留列表接口返回的版本锁定信息。 */
function openProblem(problem: UserProblemSummary): void {
  void router.push({ path: "/problems/" + problem.problemId, query: { versionId: problem.versionId } });
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

/** 在练习簿内直接取消收藏，并同步收藏列表。 */
async function toggleFavorite(row: UserProblemSummary): Promise<void> {
  if (favoriteBusyIds.value.has(row.problemId)) return;
  favoriteBusyIds.value = new Set([...favoriteBusyIds.value, row.problemId]);
  try {
    await api.unfavorite(row.problemId);
    favorites.value = favorites.value.filter((item) => item.problemId !== row.problemId);
    toast.success("已取消收藏");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "取消收藏失败");
  } finally {
    const next = new Set(favoriteBusyIds.value);
    next.delete(row.problemId);
    favoriteBusyIds.value = next;
  }
}

onMounted(() => { void load(); });
</script>

<template>
  <section class="content-page content-page--modern oj-page practice-page">
    <header class="practice-heading">
      <div>
        <h1>练习簿</h1>
      </div>
      <UiButton variant="outline" size="sm" :loading="loading" @click="load"><RefreshCw :size="15" />刷新</UiButton>
    </header>

    <div class="practice-overview" aria-label="练习簿概览">
      <div class="practice-overview-item"><span class="practice-overview-icon practice-overview-icon--wrong"><CircleAlert :size="17" /></span><span><strong>{{ unresolvedWrong.length }}</strong><small>待解决错题</small></span></div>
      <div class="practice-overview-item"><span class="practice-overview-icon practice-overview-icon--solved"><CheckCircle2 :size="17" /></span><span><strong>{{ solvedWrong.length }}</strong><small>已解决历史</small></span></div>
      <div class="practice-overview-item"><span class="practice-overview-icon practice-overview-icon--favorite"><BookmarkCheck :size="17" /></span><span><strong>{{ favorites.length }}</strong><small>收藏题目</small></span></div>
    </div>

    <nav class="practice-tabs" role="tablist" aria-label="练习簿分类">
      <button type="button" role="tab" :aria-selected="tab === 'wrong'" :class="{ active: tab === 'wrong' }" @click="tab = 'wrong'"><CircleAlert :size="16" />错题本<span>{{ wrong.length }}</span></button>
      <button type="button" role="tab" :aria-selected="tab === 'favorite'" :class="{ active: tab === 'favorite' }" @click="tab = 'favorite'"><Bookmark :size="16" />收藏<span>{{ favorites.length }}</span></button>
    </nav>

    <div v-if="tab === 'wrong'" class="practice-section">
      <div class="practice-section-toolbar">
        <div class="practice-filter-tabs" role="tablist" aria-label="错题状态">
          <button type="button" :class="{ active: wrongFilter === 'unresolved' }" @click="wrongFilter = 'unresolved'">待解决 <span>{{ unresolvedWrong.length }}</span></button>
          <button type="button" :class="{ active: wrongFilter === 'solved' }" @click="wrongFilter = 'solved'">已解决 <span>{{ solvedWrong.length }}</span></button>
          <button type="button" :class="{ active: wrongFilter === 'all' }" @click="wrongFilter = 'all'">全部 <span>{{ wrong.length }}</span></button>
        </div>
      </div>
      <div v-if="loading" class="practice-list practice-list--loading" aria-busy="true" aria-label="正在加载错题本"><div v-for="index in 5" :key="index" class="practice-skeleton"><i /><span /><b /></div></div>
      <div v-else-if="visibleWrong.length" class="practice-list">
        <article v-for="row in visibleWrong" :key="row.problem.problemId" class="practice-row" role="link" tabindex="0" @click="openProblem(row.problem)" @keydown.enter="openProblem(row.problem)">
          <span :class="['practice-row-status', row.solvedAt ? 'practice-row-status--solved' : 'practice-row-status--wrong']"><CheckCircle2 v-if="row.solvedAt" :size="18" /><CircleAlert v-else :size="18" /></span>
          <span class="practice-row-main"><strong>{{ row.problem.title }}</strong><small>{{ row.problem.school || "未注明学校" }} · {{ row.problem.year }} · {{ difficultyText[row.problem.difficulty] }}</small></span>
          <span class="practice-row-score"><strong>{{ row.bestScore }}</strong><small>历史最高分</small></span>
          <span :class="['practice-row-state', row.solvedAt ? 'practice-row-state--solved' : 'practice-row-state--wrong']">{{ row.solvedAt ? "已解决" : "待解决" }}</span>
          <span class="practice-row-time"><small>最近练习</small>{{ formatDate(row.lastWrongAt) }}</span>
        </article>
      </div>
      <UiEmptyState v-else :description="wrongFilter === 'solved' ? '还没有已解决的错题' : wrongFilter === 'all' ? '错题本暂时为空' : '太棒了，暂无待解决错题'" class="practice-empty"><template #icon><CheckCircle2 v-if="wrongFilter !== 'all'" :size="26" /><RotateCcw v-else :size="26" /></template></UiEmptyState>
    </div>

    <div v-else class="practice-section">
      <div class="practice-section-toolbar"><div><strong>我的收藏</strong><span class="practice-section-count">{{ favorites.length }} 道题目</span></div></div>
      <div v-if="loading" class="practice-list practice-list--loading" aria-busy="true" aria-label="正在加载收藏"><div v-for="index in 5" :key="index" class="practice-skeleton"><i /><span /><b /></div></div>
      <div v-else-if="favorites.length" class="practice-list">
        <article v-for="row in favorites" :key="row.problemId" class="practice-row practice-row--favorite" role="link" tabindex="0" @click="openProblem(row)" @keydown.enter="openProblem(row)">
          <span class="practice-row-status practice-row-status--favorite"><BookmarkCheck :size="18" fill="currentColor" /></span>
          <span class="practice-row-main"><strong>{{ row.title }}</strong><small>{{ row.school || "未注明学校" }} · {{ row.year }} · {{ difficultyText[row.difficulty] }}</small></span>
          <span class="practice-row-version">版本 v{{ row.versionId.slice(0, 8) }}</span>
          <button type="button" class="practice-row-action" :disabled="favoriteBusyIds.has(row.problemId)" aria-label="取消收藏" title="取消收藏" @click.stop="toggleFavorite(row)"><BookmarkCheck :size="17" fill="currentColor" /></button>
        </article>
      </div>
      <UiEmptyState v-else description="还没有收藏题目" class="practice-empty"><template #icon><Bookmark :size="26" /></template></UiEmptyState>
    </div>
  </section>
</template>
