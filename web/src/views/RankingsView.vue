<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { CheckCircle2, Clock3, Medal, RefreshCw, Trophy, Users } from "@lucide/vue";
import { api } from "../api/client";
import type { Contest, ContestRank } from "../api/types";
import { APP_TIME_ZONE } from "../lib/time";
import { toast } from "../lib/notify";
import UiButton from "../components/ui/Button.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";

/** 排名页面的比赛列表与当前选中的比赛。 */
const contests = ref<Contest[]>([]);
const selectedContestId = ref("");
const loading = ref(false);
const now = ref(Date.now());
let ticker: number | undefined;
let rankingRefreshTimer: number | undefined;

/** 当前选中的比赛。 */
const selectedContest = computed(() => contests.value.find((contest) => contest.id === selectedContestId.value));
/** 排名只对已经结束且返回排名数据的比赛开放。 */
const selectedRanking = computed<ContestRank[]>(() => selectedContest.value?.ranking ?? []);
/** 当前比赛按 A、B、C 顺序显示题目列。 */
const selectedProblems = computed(() => selectedContest.value?.problems ?? []);

/** 读取排名行中某道题的最高分，缺少提交时按 0 分处理。 */
function problemScore(row: ContestRank, problemId: string): number {
  return row.problemScores?.[problemId] ?? 0;
}

/** 格式化倒计时，保持 OJ 页面常见的时分秒显示。 */
function formatDuration(seconds: number): string {
  const safeSeconds = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(safeSeconds / 3600);
  const minutes = Math.floor((safeSeconds % 3600) / 60);
  const rest = safeSeconds % 60;
  return hours > 0
    ? `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`
    : `${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`;
}

/** 统一显示中国标准时间，避免比赛时间按 UTC 显示。 */
function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: APP_TIME_ZONE,
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

/** 根据当前时间计算比赛阶段，页面停留时也会实时更新。 */
function phaseOf(contest: Contest): Contest["phase"] {
  if (now.value < new Date(contest.startsAt).getTime()) return "UPCOMING";
  if (now.value < new Date(contest.endsAt).getTime()) return "RUNNING";
  return "FINISHED";
}

/** 计算比赛已流逝比例，用于卡片中的时间线。 */
function elapsedRatio(contest: Contest): number {
  const start = new Date(contest.startsAt).getTime();
  const end = new Date(contest.endsAt).getTime();
  if (end <= start) return 1;
  return Math.min(1, Math.max(0, (now.value - start) / (end - start)));
}

/** 计算比赛剩余秒数。 */
function remainingSeconds(contest: Contest): number {
  return Math.max(0, Math.floor((new Date(contest.endsAt).getTime() - now.value) / 1000));
}

/** 将剩余时间映射为绿、黄、红三种线条颜色。 */
function timeTone(contest: Contest): "safe" | "warning" | "danger" {
  const phase = phaseOf(contest);
  if (phase === "UPCOMING") return "warning";
  if (phase === "FINISHED") return "danger";
  const ratio = remainingSeconds(contest) / Math.max(1, (new Date(contest.endsAt).getTime() - new Date(contest.startsAt).getTime()) / 1000);
  if (ratio > 0.5) return "safe";
  if (ratio > 0.2) return "warning";
  return "danger";
}

/** 返回卡片中的倒计时文案。 */
function timeLabel(contest: Contest): string {
  const phase = phaseOf(contest);
  if (phase === "UPCOMING") return `距开始 ${formatDuration(Math.max(0, Math.floor((new Date(contest.startsAt).getTime() - now.value) / 1000)))}`;
  if (phase === "RUNNING") return `剩余 ${formatDuration(remainingSeconds(contest))}`;
  return "已结束";
}

/** 返回排名页的阶段文案。 */
function phaseLabel(contest: Contest): string {
  const phase = phaseOf(contest);
  if (phase === "UPCOMING") return "即将开始";
  if (phase === "RUNNING") return "进行中";
  return "已结束";
}

/** 选择比赛并滚动到排名卡片。 */
function selectContest(contest: Contest): void {
  selectedContestId.value = contest.id;
  void refreshSelectedContest();
  requestAnimationFrame(() => document.querySelector(".ranking-detail-card")?.scrollIntoView({ behavior: "smooth", block: "start" }));
}

/** 刷新当前比赛的详情与实时排名。 */
async function refreshSelectedContest(): Promise<void> {
  if (!selectedContestId.value) return;
  try {
    const detail = await api.contest(selectedContestId.value);
    const index = contests.value.findIndex((contest) => contest.id === detail.id);
    if (index >= 0) contests.value[index] = detail;
    else contests.value.unshift(detail);
  } catch {
    // 刷新失败时保留上一次排名，避免短暂网络波动清空榜单。
  }
}

/** 读取公开比赛；排名数据由结束后的比赛详情一并返回。 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    const rows = await api.contests();
    contests.value = rows;
    const currentStillExists = rows.some((contest) => contest.id === selectedContestId.value);
    if (!currentStillExists) {
      selectedContestId.value = rows.find((contest) => contest.phase === "FINISHED" && contest.ranking?.length)?.id
        ?? rows[0]?.id
        ?? "";
    }
  } catch (error) {
    contests.value = [];
    toast.error(error instanceof Error ? error.message : "排名加载失败");
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  ticker = window.setInterval(() => { now.value = Date.now(); }, 1000);
  rankingRefreshTimer = window.setInterval(() => { void refreshSelectedContest(); }, 10_000);
  void load();
});

onBeforeUnmount(() => {
  if (ticker !== undefined) window.clearInterval(ticker);
  if (rankingRefreshTimer !== undefined) window.clearInterval(rankingRefreshTimer);
});
</script>

<template>
  <section class="content-page content-page--modern oj-page rankings-page" :aria-busy="loading">
    <header class="page-heading rankings-heading">
      <div>
        <h1>排名</h1>
        <p>按比赛总分排序，同分按达到最终总分的用时排序</p>
      </div>
      <UiButton variant="outline" size="sm" :loading="loading" @click="load"><RefreshCw :size="15" />刷新</UiButton>
    </header>

    <div v-if="loading" class="ranking-contest-list ranking-contest-list--loading" aria-label="正在加载比赛">
      <div v-for="index in 4" :key="index" class="ranking-contest-skeleton"><i /><span /><b /></div>
    </div>
    <UiEmptyState v-else-if="!contests.length" description="暂无可查看的比赛" class="ranking-empty" />
    <template v-else>
      <section class="ranking-contest-card" aria-label="比赛列表">
        <header class="ranking-card-heading">
          <div><Trophy :size="17" /><h2>比赛</h2></div>
          <span>{{ contests.length }} 场</span>
        </header>
        <div class="ranking-contest-list">
          <button v-for="contest in contests" :key="contest.id" type="button" class="ranking-contest-item" :class="{ active: contest.id === selectedContestId }" @click="selectContest(contest)">
            <span class="ranking-contest-rank-icon"><Medal :size="17" /></span>
            <span class="ranking-contest-copy"><strong>{{ contest.title }}</strong><small>{{ formatDateTime(contest.startsAt) }} - {{ formatDateTime(contest.endsAt) }}</small></span>
            <span class="ranking-contest-phase" :class="`ranking-contest-phase--${phaseOf(contest).toLowerCase()}`">{{ phaseLabel(contest) }}</span>
            <span class="ranking-contest-time" :class="`ranking-time--${timeTone(contest)}`"><span class="ranking-time-track"><i :style="{ width: `${(elapsedRatio(contest) * 100).toFixed(2)}%` }" /></span><small>{{ timeLabel(contest) }}</small></span>
          </button>
        </div>
      </section>

      <section v-if="selectedContest" class="ranking-detail-card">
        <header class="ranking-detail-heading">
          <div><span class="ranking-kicker"><CheckCircle2 v-if="phaseOf(selectedContest) === 'FINISHED'" :size="14" /><Clock3 v-else :size="14" />{{ phaseLabel(selectedContest) }}</span><h2>{{ selectedContest.title }}</h2><small class="ranking-created-at">创建于 {{ formatDateTime(selectedContest.createdAt) }}</small></div>
          <span class="ranking-participants"><Users :size="15" />{{ selectedContest.participantCount }} 人</span>
        </header>
        <div class="ranking-time-flow" :class="`ranking-time--${timeTone(selectedContest)}`">
          <div class="ranking-time-flow-header"><span><Clock3 :size="15" />比赛进度</span><strong>{{ timeLabel(selectedContest) }}</strong></div>
          <div class="ranking-time-track ranking-time-track--large" role="progressbar" aria-label="比赛已流逝时间" aria-valuemin="0" aria-valuemax="100" :aria-valuenow="Math.round(elapsedRatio(selectedContest) * 100)"><i :style="{ width: `${(elapsedRatio(selectedContest) * 100).toFixed(2)}%` }" /></div>
          <div class="ranking-time-flow-footer"><span>{{ formatDateTime(selectedContest.startsAt) }}</span><span>{{ formatDateTime(selectedContest.endsAt) }}</span></div>
        </div>

        <div v-if="phaseOf(selectedContest) !== 'FINISHED'" class="ranking-private-state"><Clock3 :size="20" /><strong>排名将在比赛结束后公开</strong><span>比赛进行中仅显示参赛者自己的得分。</span></div>
        <div v-else-if="selectedRanking.length" class="ranking-table-wrap">
            <table class="ranking-table">
              <thead><tr><th class="ranking-table-rank">排名</th><th class="ranking-table-user">用户</th><th v-for="(problem, index) in selectedProblems" :key="problem.versionId" class="ranking-table-problem" :title="problem.title">{{ String.fromCharCode(65 + index) }}</th><th class="ranking-table-score">总分</th><th class="ranking-table-time">用时</th></tr></thead>
              <tbody>
                <tr v-for="row in selectedRanking" :key="row.username"><td class="ranking-table-rank"><span :class="`ranking-medal ranking-medal--${row.rank}`">{{ row.rank }}</span></td><td class="ranking-user">{{ row.username }}</td><td v-for="problem in selectedProblems" :key="problem.versionId" class="ranking-table-problem"><span class="ranking-problem-cell" :class="problemScore(row, problem.problemId) === 100 ? 'ranking-problem-cell--accepted' : problemScore(row, problem.problemId) > 0 ? 'ranking-problem-cell--partial' : 'ranking-problem-cell--empty'">{{ problemScore(row, problem.problemId) === 100 ? '✓' : problemScore(row, problem.problemId) > 0 ? problemScore(row, problem.problemId) : '·' }}</span></td><td class="ranking-table-score"><strong>{{ row.totalScore }}</strong><small>分</small></td><td class="ranking-table-time">{{ formatDuration(row.elapsedSeconds) }}</td></tr>
              </tbody>
            </table>
        </div>
        <div v-else class="ranking-private-state"><Medal :size="20" /><strong>暂无排名数据</strong><span>该比赛还没有可公开的参赛记录。</span></div>
      </section>
    </template>
  </section>
</template>
