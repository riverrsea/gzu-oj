<script setup lang="ts">
import {computed, onBeforeUnmount, onMounted, ref, watch} from "vue";
import {ArrowLeft, CheckCircle2, Clock3, List, Medal, RefreshCw, Trophy, Users, X} from "@lucide/vue";
import {useRoute, useRouter} from "vue-router";
import {api} from "../api/client";
import type {Contest, ContestRank, ContestSummary} from "../api/types";
import {APP_TIME_ZONE} from "../lib/time";
import UiButton from "../components/ui/Button.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";

/** 当前路由与导航器，用查询参数锁定具体比赛。 */
const route = useRoute();
const router = useRouter();
/** 未指定比赛时展示的公开比赛摘要。 */
const contests = ref<ContestSummary[]>([]);
/** 当前比赛的完整详情与排名。 */
const contest = ref<Contest>();
/** 页面首次加载状态。 */
const loading = ref(false);
/** 排名静默刷新状态。 */
const refreshing = ref(false);
/** 加载失败后的用户可读错误。 */
const errorMessage = ref("");
/** 题目抽屉是否打开。 */
const problemSidebarOpen = ref(false);
/** 当前时间，用于更新时间线和比赛阶段。 */
const now = ref(Date.now());
let ticker: number | undefined;
let rankingRefreshTimer: number | undefined;

/** 从查询参数读取唯一比赛标识，数组形式只采用第一个值。 */
const contestId = computed(() => {
  const value = route.query.contestId;
  return Array.isArray(value) ? value[0] ?? "" : value ?? "";
});
/** 详情接口返回的排名数据。 */
const ranking = computed<ContestRank[]>(() => contest.value?.ranking ?? []);
/** 比赛题目按锁定顺序对应 A、B、C 列。 */
const problems = computed(() => contest.value?.problems ?? []);

/** 读取排名行中某道题的最高分，缺少提交时按零分处理。 */
function problemScore(row: ContestRank, problemId: string): number {
  return row.problemScores?.[problemId] ?? 0;
}

/** 格式化比赛用时。 */
function formatDuration(seconds: number): string {
  const safeSeconds = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(safeSeconds / 3600);
  const minutes = Math.floor((safeSeconds % 3600) / 60);
  const rest = safeSeconds % 60;
  return hours > 0
    ? `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`
    : `${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`;
}

/** 所有比赛时间统一按中国标准时间显示。 */
function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: APP_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

/** 根据浏览器当前时间实时计算比赛阶段。 */
function phaseOf(value: Contest | ContestSummary): Contest["phase"] {
  if (now.value < new Date(value.startsAt).getTime()) return "UPCOMING";
  if (now.value < new Date(value.endsAt).getTime()) return "RUNNING";
  return "FINISHED";
}

/** 计算比赛已经流逝的比例。 */
function elapsedRatio(value: Contest | ContestSummary): number {
  const start = new Date(value.startsAt).getTime();
  const end = new Date(value.endsAt).getTime();
  if (end <= start) return 1;
  return Math.min(1, Math.max(0, (now.value - start) / (end - start)));
}

/** 返回比赛时间线的绿、黄、红警示色。 */
function timeTone(value: Contest | ContestSummary): "safe" | "warning" | "danger" {
  const phase = phaseOf(value);
  if (phase === "UPCOMING") return "warning";
  if (phase === "FINISHED") return "danger";
  const remaining = new Date(value.endsAt).getTime() - now.value;
  const total = new Date(value.endsAt).getTime() - new Date(value.startsAt).getTime();
  const ratio = remaining / Math.max(1, total);
  if (ratio > 0.5) return "safe";
  if (ratio > 0.2) return "warning";
  return "danger";
}

/** 返回比赛阶段文字。 */
function phaseLabel(value: Contest | ContestSummary): string {
  const phase = phaseOf(value);
  if (phase === "UPCOMING") return "即将开始";
  if (phase === "RUNNING") return "进行中";
  return "已结束";
}

/** 返回倒计时或结束状态。 */
function timeLabel(value: Contest | ContestSummary): string {
  const phase = phaseOf(value);
  if (phase === "UPCOMING") {
    return `距开始 ${formatDuration((new Date(value.startsAt).getTime() - now.value) / 1000)}`;
  }
  if (phase === "RUNNING") {
    return `剩余 ${formatDuration((new Date(value.endsAt).getTime() - now.value) / 1000)}`;
  }
  return "已结束";
}

/** 加载指定比赛；静默刷新不会清空已经显示的榜单。 */
async function loadContest(silent = false): Promise<void> {
  if (!contestId.value) return;
  if (silent) refreshing.value = true;
  else {
    loading.value = true;
    contest.value = undefined;
  }
  errorMessage.value = "";
  try {
    if (silent && contest.value) {
      const latestRanking = await api.contestRanking(contestId.value);
      contest.value = {...contest.value, ranking: latestRanking};
    } else {
      contest.value = await api.contest(contestId.value);
    }
  } catch (error) {
    if (!silent) contest.value = undefined;
    errorMessage.value = error instanceof Error ? error.message : "比赛排名加载失败";
  } finally {
    loading.value = false;
    refreshing.value = false;
  }
}

/** 未指定比赛时读取可选择的公开比赛元数据。 */
async function loadContestChoices(): Promise<void> {
  loading.value = true;
  errorMessage.value = "";
  contest.value = undefined;
  try {
    contests.value = await api.contests();
  } catch (error) {
    contests.value = [];
    errorMessage.value = error instanceof Error ? error.message : "比赛列表加载失败";
  } finally {
    loading.value = false;
  }
}

/** 按当前路由决定加载比赛详情还是比赛选择列表。 */
async function load(): Promise<void> {
  if (contestId.value) await loadContest();
  else await loadContestChoices();
}

/** 选择比赛后将比赛标识写入 URL。 */
function selectContest(value: ContestSummary): void {
  void router.push({path: "/rankings", query: {contestId: value.id}});
}

/** 返回训练页，排名页面不再是顶栏一级入口。 */
function backToTraining(): void {
  void router.push("/training");
}

/** 切换比赛题目抽屉。 */
function toggleProblemSidebar(): void {
  problemSidebarOpen.value = !problemSidebarOpen.value;
}

/** 关闭比赛题目抽屉。 */
function closeProblemSidebar(): void {
  problemSidebarOpen.value = false;
}

onMounted(() => {
  ticker = window.setInterval(() => { now.value = Date.now(); }, 1000);
  rankingRefreshTimer = window.setInterval(() => {
    if (contest.value && phaseOf(contest.value) === "RUNNING" && !loading.value && !refreshing.value) void loadContest(true);
  }, 10_000);
  void load();
});

watch(contestId, () => {
  problemSidebarOpen.value = false;
  void load();
});

onBeforeUnmount(() => {
  if (ticker !== undefined) window.clearInterval(ticker);
  if (rankingRefreshTimer !== undefined) window.clearInterval(rankingRefreshTimer);
});
</script>

<template>
  <section class="content-page content-page--modern oj-page rankings-page" :aria-busy="loading">
    <template v-if="contestId">
      <header class="ranking-page-toolbar">
        <UiButton variant="ghost" size="sm" @click="backToTraining"><ArrowLeft :size="16"/>返回训练</UiButton>
        <div class="ranking-page-toolbar-actions">
          <UiButton variant="outline" size="sm" :class="problemSidebarOpen ? 'is-active' : undefined" @click="toggleProblemSidebar"><List :size="15"/>题目</UiButton>
          <UiButton variant="outline" size="sm" :loading="loading || refreshing" @click="loadContest()"><RefreshCw :size="15"/>刷新</UiButton>
        </div>
      </header>

      <div v-if="loading && !contest" class="ranking-detail-card ranking-detail-loading" aria-label="正在加载比赛排名">
        <div v-for="index in 3" :key="index" class="ranking-contest-skeleton"><i/><span/><b/></div>
      </div>

      <UiEmptyState v-else-if="errorMessage && !contest" :description="errorMessage" class="ranking-empty">
        <template #icon><Trophy :size="24"/></template>
        <UiButton variant="outline" size="sm" @click="backToTraining">返回训练页</UiButton>
      </UiEmptyState>

      <div v-else-if="contest" class="ranking-detail-layout">
        <section class="ranking-detail-card ranking-detail-card--single ranking-contest-meta">
        <header class="ranking-detail-heading">
          <div>
            <span class="ranking-kicker" :class="`ranking-kicker--${phaseOf(contest).toLowerCase()}`">
              <CheckCircle2 v-if="phaseOf(contest) === 'FINISHED'" :size="14"/><Clock3 v-else :size="14"/>{{ phaseLabel(contest) }}
            </span>
            <h1>{{ contest.title }}</h1>
            <small>由 {{ contest.ownerUsername }} 创建 · {{ formatDateTime(contest.createdAt) }}</small>
          </div>
          <span class="ranking-participants"><Users :size="15"/>{{ contest.participantCount }} 人</span>
        </header>

        <div class="ranking-progress" :class="`ranking-time--${timeTone(contest)}`">
          <div class="ranking-time-flow-header"><span><Clock3 :size="15"/>比赛进度</span><strong>{{ timeLabel(contest) }}</strong></div>
          <div class="ranking-time-track ranking-time-track--large" role="progressbar" aria-label="比赛已流逝时间" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="Math.round(elapsedRatio(contest) * 100)"><i :style="{width: `${(elapsedRatio(contest) * 100).toFixed(2)}%`}"/></div>
          <div class="ranking-time-flow-footer"><span>{{ formatDateTime(contest.startsAt) }}</span><span>{{ formatDateTime(contest.endsAt) }}</span></div>
        </div>

        </section>

        <div class="ranking-content-grid">
          <section class="ranking-board">
          <div v-if="phaseOf(contest) === 'UPCOMING'" class="ranking-private-state">
            <Clock3 :size="20"/><strong>排名将在比赛开始后产生</strong><span>比赛开始后将展示参赛者的实时得分。</span>
          </div>
          <div v-else-if="ranking.length" class="ranking-user-list">
            <article v-for="row in ranking" :key="row.username" class="ranking-user-card">
              <header class="ranking-user-card-header">
                <span class="ranking-medal" :class="`ranking-medal--${row.rank}`">{{ row.rank }}</span>
                <strong>{{ row.username }}</strong>
                <span class="ranking-user-score"><b>{{ row.totalScore }}</b><small>分</small></span>
                <span class="ranking-user-time">{{ formatDuration(row.elapsedSeconds) }}</span>
              </header>
              <div class="ranking-user-problems" aria-label="题目得分">
                <div v-for="(problem, index) in problems" :key="problem.versionId" class="ranking-user-problem">
                  <span class="ranking-user-problem-label">{{ String.fromCharCode(65 + index) }}</span>
                  <span class="ranking-problem-cell" :class="problemScore(row, problem.problemId) === 100 ? 'ranking-problem-cell--accepted' : problemScore(row, problem.problemId) > 0 ? 'ranking-problem-cell--partial' : 'ranking-problem-cell--empty'">{{ problemScore(row, problem.problemId) === 100 ? '✓' : problemScore(row, problem.problemId) > 0 ? problemScore(row, problem.problemId) : '·' }}</span>
                </div>
              </div>
            </article>
          </div>
          <div v-else class="ranking-private-state"><Medal :size="20"/><strong>暂无排名数据</strong><span>该比赛还没有有效的参赛记录。</span></div>
          </section>
        </div>
      </div>

      <Teleport to="body">
        <Transition name="ranking-backdrop">
          <div v-if="problemSidebarOpen" class="ranking-sidebar-backdrop" @click="closeProblemSidebar" />
        </Transition>
        <Transition name="ranking-sidebar">
          <aside v-if="problemSidebarOpen" class="ranking-problems-sidebar" aria-label="比赛题目" role="dialog" aria-modal="true">
            <header class="ranking-sidebar-heading"><span><Trophy :size="15"/>比赛题目</span><button type="button" class="icon-button" aria-label="关闭题目侧栏" @click="closeProblemSidebar"><X :size="17"/></button></header>
            <div class="ranking-problem-meta-list">
              <div v-for="(problem, index) in problems" :key="problem.versionId" class="ranking-problem-meta-item">
                <span class="ranking-problem-letter">{{ String.fromCharCode(65 + index) }}</span>
                <strong>{{ problem.title }}</strong>
              </div>
            </div>
          </aside>
        </Transition>
      </Teleport>
    </template>

    <template v-else>
      <header class="page-heading rankings-heading">
        <div><h1>选择比赛</h1><p>从公开训练赛中选择一场查看排名</p></div>
        <UiButton variant="ghost" size="sm" @click="backToTraining"><ArrowLeft :size="16"/>返回训练</UiButton>
      </header>
      <div v-if="loading" class="ranking-contest-card" aria-label="正在加载比赛">
        <div v-for="index in 4" :key="index" class="ranking-contest-skeleton"><i/><span/><b/></div>
      </div>
      <UiEmptyState v-else-if="!contests.length" :description="errorMessage || '暂无可查看的比赛'" class="ranking-empty"/>
      <section v-else class="ranking-contest-card" aria-label="公开比赛列表">
        <header class="ranking-card-heading"><div><Trophy :size="17"/><h2>公开训练赛</h2></div><span>{{ contests.length }} 场</span></header>
        <div class="ranking-contest-list">
          <button v-for="item in contests" :key="item.id" type="button" class="ranking-contest-item" @click="selectContest(item)">
            <span class="ranking-contest-rank-icon"><Medal :size="17"/></span>
            <span class="ranking-contest-copy"><strong>{{ item.title }}</strong><small>{{ formatDateTime(item.startsAt) }} - {{ formatDateTime(item.endsAt) }}</small></span>
            <span class="ranking-contest-phase" :class="`ranking-contest-phase--${phaseOf(item).toLowerCase()}`">{{ phaseLabel(item) }}</span>
            <span class="ranking-contest-time" :class="`ranking-time--${timeTone(item)}`"><span class="ranking-time-track"><i :style="{width: `${(elapsedRatio(item) * 100).toFixed(2)}%`}"/></span><small>{{ timeLabel(item) }}</small></span>
          </button>
        </div>
      </section>
    </template>
  </section>
</template>
