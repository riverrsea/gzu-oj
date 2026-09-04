<script setup lang="ts">
import {computed, onBeforeUnmount, onMounted, reactive, ref} from "vue";
import {
  CalendarDays,
  Check,
  ChevronRight,
  Clock3,
  ExternalLink,
  FileText,
  Gauge,
  Link2,
  ListChecks,
  LockKeyhole,
  Plus,
  RefreshCw,
  Trophy,
  Users
} from "@lucide/vue";
import {promptAction, toast} from "../lib/notify";
import {APP_TIME_ZONE, chinaDateTimeInputToIso, toChinaDateTimeInputValue} from "../lib/time";
import {useRouter} from "vue-router";
import {api} from "../api/client";
import type {Contest, ContestVisibility, ProblemSummary, TimedAttempt, TimedPaper} from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiDialog from "../components/ui/Dialog.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiSelect from "../components/ui/Select.vue";
import UiTable from "../components/ui/Table.vue";
import UiLabel from "../components/ui/Label.vue";
import ProblemPicker from "../components/ProblemPicker.vue";

/** 训练中心的当前标签。 */
const tab = ref<"contest" | "paper">("contest");
/** 页面加载状态。 */
const loading = ref(false);
/** 当前公开比赛列表。 */
const contests = ref<Contest[]>([]);
/** 当前用户的个人套卷。 */
const papers = ref<TimedPaper[]>([]);
/** 可供组题的发布题目。 */
const problems = ref<ProblemSummary[]>([]);
/** 当前展开的比赛。 */
const selectedContest = ref<Contest>();
/** 按套卷标识保存的既有作答，页面重进后由服务端恢复。 */
const attemptsByPaperId = ref<Record<string, TimedAttempt>>({});
/** 当前展开的个人计时套卷。 */
const selectedPaper = ref<TimedPaper>();
/** 新建比赛对话框状态。 */
const contestDialog = ref(false);
/** 新建套卷对话框状态。 */
const paperDialog = ref(false);
/** 当前时间，用于倒计时刷新。 */
const now = ref(Date.now());
/** Vue 路由器。 */
const router = useRouter();
/** 倒计时定时器。 */
let ticker: number | undefined;
/** 新建比赛表单。 */
const contestForm = reactive({
  title: "",
  visibility: "PUBLIC" as ContestVisibility,
  password: "",
  startsAt: toChinaDateTimeInputValue(new Date(Date.now() + 15 * 60_000)),
  durationMinutes: 120,
  problemIds: [] as string[],
});

/** 新建套卷表单。 */
const paperForm = reactive({title: "", durationMinutes: 120, problemIds: [] as string[]});

/** 训练中心顶部概览统计。 */
const runningContestCount = computed(() => contests.value.filter((contest) => liveContestPhase(contest) === "RUNNING").length);
const upcomingContestCount = computed(() => contests.value.filter((contest) => liveContestPhase(contest) === "UPCOMING").length);
const paperProblemCount = computed(() => papers.value.reduce((total, paper) => total + paper.problems.length, 0));
/** 当前选中套卷对应的既有作答。 */
const selectedAttempt = computed(() => {
  if (!selectedPaper.value) return undefined;
  return attemptsByPaperId.value[selectedPaper.value.id];
});
/** 顶部概览优先展示当前套卷，其次展示仍在进行的作答。 */
const summaryAttempt = computed(() => selectedAttempt.value
    ?? Object.values(attemptsByPaperId.value).find((attempt) => !attemptFinished(attempt))
    ?? Object.values(attemptsByPaperId.value)[0]);

/** 格式化分钟与秒倒计时。 */
function formatDuration(seconds: number): string {
  const safeSeconds = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(safeSeconds / 3600);
  const minutes = Math.floor((safeSeconds % 3600) / 60);
  const secondPart = safeSeconds % 60;
  if (hours > 0) {
    return String(hours).padStart(2, "0") + ":" + String(minutes).padStart(2, "0") + ":" + String(secondPart).padStart(2, "0");
  }
  return String(minutes).padStart(2, "0") + ":" + String(secondPart).padStart(2, "0");
}

/** 依据当前时间计算实时比赛阶段，避免页面停留时阶段冻结。 */
function liveContestPhase(contest: Contest): Contest["phase"] {
  if (now.value < new Date(contest.startsAt).getTime()) return "UPCOMING";
  if (now.value < new Date(contest.endsAt).getTime()) return "RUNNING";
  return "FINISHED";
}

/** 计算比赛或套卷剩余比例，并限制在有效区间。 */
function boundedRatio(remaining: number, total: number): number {
  if (total <= 0) return 0;
  return Math.min(1, Math.max(0, remaining / total));
}

/** 将剩余比例映射为绿、黄、红三个时间警示阶段。 */
function timeTone(ratio: number): "safe" | "warning" | "danger" {
  if (ratio > 0.5) return "safe";
  if (ratio > 0.2) return "warning";
  return "danger";
}

/** 返回进度线可直接绑定的百分比宽度。 */
function progressWidth(ratio: number): string {
  return (ratio * 100).toFixed(2) + "%";
}

/** 返回比赛的总时长秒数。 */
function contestTotalSeconds(contest: Contest): number {
  return Math.max(0, (new Date(contest.endsAt).getTime() - new Date(contest.startsAt).getTime()) / 1000);
}

/** 返回比赛当前剩余比例；未开始时保持完整，结束后归零。 */
function contestRemainingRatio(contest: Contest): number {
  const phase = liveContestPhase(contest);
  if (phase === "UPCOMING") return 1;
  if (phase === "FINISHED") return 0;
  return boundedRatio((new Date(contest.endsAt).getTime() - now.value) / 1000, contestTotalSeconds(contest));
}

/** 返回比赛倒计时主文案。 */
function contestCountdown(contest: Contest): string {
  const phase = liveContestPhase(contest);
  if (phase === "UPCOMING") return "距开始 " + formatDuration((new Date(contest.startsAt).getTime() - now.value) / 1000);
  if (phase === "RUNNING") return "剩余 " + formatDuration((new Date(contest.endsAt).getTime() - now.value) / 1000);
  return "已结束";
}

/** 返回比赛时间流逝说明。 */
function contestElapsed(contest: Contest): string {
  const total = contestTotalSeconds(contest);
  const phase = liveContestPhase(contest);
  if (phase === "UPCOMING") return "比赛时长 " + formatDuration(total);
  const elapsed = phase === "FINISHED" ? total : (now.value - new Date(contest.startsAt).getTime()) / 1000;
  return "已用 " + formatDuration(elapsed) + " / " + formatDuration(total);
}

/** 返回套卷已有作答。 */
function attemptFor(paper: TimedPaper): TimedAttempt | undefined {
  return attemptsByPaperId.value[paper.id];
}

/** 返回套卷列表项的剩余比例；尚未开始时保持完整。 */
function paperRemainingRatio(paper: TimedPaper): number {
  const attempt = attemptFor(paper);
  return attempt ? attemptRemainingRatio(attempt) : 1;
}

/** 返回套卷列表项的实时计时文案。 */
function paperCountdown(paper: TimedPaper): string {
  const attempt = attemptFor(paper);
  if (!attempt) return "尚未开始";
  return attemptFinished(attempt) ? "已结束" : "剩余 " + formatDuration(attemptRemainingSeconds(attempt));
}

/** 返回套卷作答的剩余秒数。 */
function attemptRemainingSeconds(attempt: TimedAttempt): number {
  return Math.max(0, Math.floor((new Date(attempt.expiresAt).getTime() - now.value) / 1000));
}

/** 截止时间到达后，前端立即视为结束，不等待下一次接口刷新。 */
function attemptFinished(attempt: TimedAttempt): boolean {
  return attempt.finished || attemptRemainingSeconds(attempt) <= 0;
}

/** 返回套卷作答当前剩余比例。 */
function attemptRemainingRatio(attempt: TimedAttempt): number {
  return boundedRatio(attemptRemainingSeconds(attempt), attempt.paper.durationMinutes * 60);
}

/** 返回套卷已经使用的时长。 */
function attemptElapsed(attempt: TimedAttempt): string {
  const total = attempt.paper.durationMinutes * 60;
  return "已用 " + formatDuration(total - attemptRemainingSeconds(attempt)) + " / " + formatDuration(total);
}

/** 将比赛阶段映射为用户可读的中文。 */
function phaseText(phase: Contest["phase"]): string {
  if (phase === "RUNNING") return "进行中";
  if (phase === "UPCOMING") return "即将开始";
  return "已结束";
}

/** 将比赛可见性映射为用户可读的中文。 */
function visibilityText(visibility: ContestVisibility): string {
  return visibility === "PASSWORD" ? "口令赛" : "公开赛";
}

/** 统一训练页的日期显示，避免列表中出现过长的本地化字符串。 */
function formatDateTime(value: string): string {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: APP_TIME_ZONE,
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(value));
}

/** 将题目序号统一为两位显示，便于在列表中快速定位。 */
function formatOrdinal(value: number): string {
  return String(value).padStart(2, "0");
}

/** 判断套卷是否已经创建过作答。 */
function hasPaperAttempt(paper: TimedPaper): boolean {
  return attemptFor(paper) !== undefined;
}

/** 判断套卷是否仍在独立计时。 */
function isPaperActive(paper: TimedPaper): boolean {
  const attempt = attemptFor(paper);
  return attempt !== undefined && !attemptFinished(attempt);
}

/** 加载训练中心所需数据。 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    const [loadedContests, loadedPapers, loadedProblems, loadedAttempts] = await Promise.all([
      api.contests(),
      api.timedPapers(),
      api.problems({}),
      api.timedAttempts(),
    ]);
    contests.value = loadedContests;
    papers.value = loadedPapers;
    problems.value = loadedProblems;
    attemptsByPaperId.value = Object.fromEntries(loadedAttempts.map((attempt) => [attempt.paper.id, attempt]));
    if (selectedContest.value && !contests.value.some((contest) => contest.id === selectedContest.value?.id)) selectedContest.value = undefined;
    if (selectedPaper.value && !papers.value.some((paper) => paper.id === selectedPaper.value?.id)) selectedPaper.value = undefined;
    if (!selectedContest.value && contests.value.length) selectedContest.value = contests.value[0];
    if (!selectedPaper.value) selectedPaper.value = loadedAttempts[0]?.paper ?? papers.value[0];
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "训练中心加载失败");
  } finally {
    loading.value = false;
  }
}

/** 先切换当前比赛，再异步刷新详情，避免点击后右侧出现空白等待。 */
function selectContest(contest: Contest): void {
  selectedContest.value = contest;
  void inspect(contest);
}

/** 创建公开或口令训练赛。 */
async function createContest(): Promise<void> {
  try {
    const created = await api.createContest({
      title: contestForm.title,
      visibility: contestForm.visibility,
      password: contestForm.visibility === "PASSWORD" ? contestForm.password : undefined,
      startsAt: chinaDateTimeInputToIso(contestForm.startsAt),
      durationMinutes: contestForm.durationMinutes,
      problemIds: contestForm.problemIds,
    });
    contests.value.unshift(created);
    selectedContest.value = created;
    contestDialog.value = false;
    toast.success("训练赛已创建");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "训练赛创建失败");
  }
}

/** 加入一场公开或口令比赛。 */
async function join(contest: Contest): Promise<void> {
  try {
    let password: string | undefined;
    if (contest.visibility === "PASSWORD") {
      password = await promptAction("输入比赛口令（8 到 100 位）");
      if (!/^.{8,100}$/.test(password)) {
        toast.warning("口令长度需为 8 到 100 位");
        return;
      }
    }
    const joined = await api.joinContest(contest.id, password);
    replaceContest(joined);
    selectedContest.value = joined;
    toast.success("已加入训练赛");
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    toast.error(error instanceof Error ? error.message : "加入失败");
  }
}

/** 刷新并展开比赛详情。 */
async function inspect(contest: Contest): Promise<void> {
  try {
    const detail = await api.contest(contest.id);
    replaceContest(detail);
    selectedContest.value = detail;
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "比赛详情加载失败");
  }
}

/** 用最新比赛对象替换列表中的旧对象。 */
function replaceContest(contest: Contest): void {
  const index = contests.value.findIndex((item) => item.id === contest.id);
  if (index >= 0) contests.value[index] = contest;
  else contests.value.unshift(contest);
}

/** 打开比赛锁定版本的做题工作区。 */
function openContestProblem(contest: Contest, problemId: string, versionId: string): void {
  void router.push({path: "/problems/" + problemId, query: {versionId, contestId: contest.id}});
}

/** 创建个人计时套卷模板。 */
async function createPaper(): Promise<void> {
  try {
    const created = await api.createTimedPaper(paperForm);
    papers.value.unshift(created);
    selectedPaper.value = created;
    paperDialog.value = false;
    toast.success("计时套卷已创建");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "套卷创建失败");
  }
}

/** 首次进入套卷并启动独立计时。 */
async function startPaper(paper: TimedPaper): Promise<void> {
  selectedPaper.value = paper;
  try {
    const attempt = await api.startTimedPaper(paper.id);
    attemptsByPaperId.value = {...attemptsByPaperId.value, [paper.id]: attempt};
    tab.value = "paper";
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "套卷启动失败");
  }
}

/** 只展开套卷详情，不触发计时。 */
function inspectPaper(paper: TimedPaper): void {
  selectedPaper.value = paper;
}

/** 打开套卷锁定版本的做题工作区。 */
function openTimedProblem(problemId: string, versionId: string): void {
  if (!selectedAttempt.value || attemptFinished(selectedAttempt.value)) return;
  void router.push({path: "/problems/" + problemId, query: {versionId, timedPaperAttemptId: selectedAttempt.value.id}});
}

/** 生成并复制只读分享链接。 */
async function shareAttempt(): Promise<void> {
  if (!selectedAttempt.value) return;
  try {
    const response = await api.shareTimedAttempt(selectedAttempt.value.id);
    const url = location.origin + "/shares/timed-papers/" + response.token;
    await navigator.clipboard.writeText(url);
    toast.success("只读分享链接已复制");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "分享链接生成失败");
  }
}

onMounted(() => {
  ticker = window.setInterval(() => {
    now.value = Date.now();
  }, 1000);
  void load();
});
onBeforeUnmount(() => window.clearInterval(ticker));
</script>

<template>
  <section class="content-page content-page--modern oj-page training-page training-page--refactored loading-shell"
           :aria-busy="loading">
    <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中"/></div>

    <header class="training-page-heading">
      <div class="training-page-title"><h1>训练</h1></div>
      <div class="training-page-actions"><span v-if="loading" class="training-sync-state"><span
          class="training-sync-dot"/>同步中</span>
        <UiButton variant="outline" size="sm" :loading="loading" @click="load">
          <RefreshCw :size="15"/>
          刷新
        </UiButton>
        <UiButton size="sm" @click="tab === 'contest' ? contestDialog = true : paperDialog = true">
          <Plus :size="15"/>
          {{ tab === 'contest' ? '创建比赛' : '创建套卷' }}
        </UiButton>
      </div>
    </header>

    <div class="training-metrics" aria-label="训练概览">
      <div class="training-metric"><span class="training-metric-icon training-metric-icon--contest"><Trophy :size="17"/></span><span><strong>{{
          contests.length
        }}</strong><small>训练赛</small></span><em>{{ runningContestCount }} 进行中</em></div>
      <div class="training-metric"><span class="training-metric-icon training-metric-icon--upcoming"><CalendarDays
          :size="17"/></span><span><strong>{{
          upcomingContestCount
        }}</strong><small>即将开始</small></span><em>等待开赛</em></div>
      <div class="training-metric"><span class="training-metric-icon training-metric-icon--paper"><FileText :size="17"/></span><span><strong>{{
          papers.length
        }}</strong><small>个人套卷</small></span><em>{{ paperProblemCount }} 道题</em></div>
      <div class="training-metric"><span class="training-metric-icon training-metric-icon--timer"><Gauge
          :size="17"/></span><span><strong>{{
          summaryAttempt ? summaryAttempt.totalScore : 0
        }}</strong><small>当前得分</small></span><em>{{
          summaryAttempt && !attemptFinished(summaryAttempt) ? "计时中" : summaryAttempt ? "已结束" : "未开始"
        }}</em></div>
    </div>

    <nav class="training-tabs" role="tablist" aria-label="训练类型">
      <button type="button" role="tab" :aria-selected="tab === 'contest'" :class="{ active: tab === 'contest' }"
              @click="tab = 'contest'">
        <Trophy :size="16"/>
        训练赛<span>{{ contests.length }}</span></button>
      <button type="button" role="tab" :aria-selected="tab === 'paper'" :class="{ active: tab === 'paper' }"
              @click="tab = 'paper'">
        <Clock3 :size="16"/>
        个人计时<span>{{ papers.length }}</span></button>
    </nav>

    <div v-if="tab === 'contest'" class="training-workbench training-workbench--contest">
      <aside class="training-browser" aria-label="训练赛列表">
        <header class="training-browser-header">
          <div><strong>公开训练赛</strong></div>
          <span class="training-browser-count">{{ contests.length }}</span></header>
        <div v-if="loading" class="training-browser-list training-browser-list--loading" aria-busy="true">
          <div v-for="index in 5" :key="index" class="training-browser-skeleton"><i/><span/><b/></div>
        </div>
        <div v-else-if="contests.length" class="training-browser-list">
          <article v-for="row in contests" :key="row.id" class="training-browser-item"
                   :class="{ active: selectedContest?.id === row.id }" role="button" tabindex="0"
                   @click="selectContest(row)" @keydown.enter="selectContest(row)">
            <div class="training-browser-item-heading"><span class="training-phase-dot"
                                                             :class="'training-phase-dot--' + liveContestPhase(row).toLowerCase()"/><strong>{{
                row.title
              }}</strong>
              <ChevronRight :size="15" aria-hidden="true"/>
            </div>
            <div class="training-browser-item-meta"><span>{{
                phaseText(liveContestPhase(row))
              }}</span><span>{{ formatDateTime(row.startsAt) }}</span></div>
            <div class="training-time-compact"
                 :class="'training-time-compact--' + timeTone(contestRemainingRatio(row))"><span
                class="training-time-track"><i
                :style="{ width: progressWidth(contestRemainingRatio(row)) }"/></span><small>{{
                contestCountdown(row)
              }}</small></div>
            <div class="training-browser-item-footer"><span><Users :size="13"/>{{
                row.participantCount
              }}/{{ row.maxParticipants }}</span><span><LockKeyhole v-if="row.visibility === 'PASSWORD'"
                                                                    :size="13"/><span v-else
                                                                                      class="training-public-dot"/>{{
                visibilityText(row.visibility)
              }}</span><span v-if="row.joined" class="training-joined-label"><Check :size="13"/>已加入</span></div>
          </article>
        </div>
        <UiEmptyState v-else description="暂无训练赛" class="training-browser-empty">
          <template #icon>
            <Trophy :size="24"/>
          </template>
        </UiEmptyState>
      </aside>

      <section class="training-inspector" aria-live="polite">
        <template v-if="selectedContest">
          <header class="training-inspector-header">
            <div><span class="training-phase-label"
                       :class="'training-phase-label--' + liveContestPhase(selectedContest).toLowerCase()"><span
                class="training-phase-dot"
                :class="'training-phase-dot--' + liveContestPhase(selectedContest).toLowerCase()"/>{{
                phaseText(liveContestPhase(selectedContest))
              }}</span>
              <h2>{{ selectedContest.title }}</h2>
              <p>由 {{ selectedContest.ownerUsername }} 创建 · {{ visibilityText(selectedContest.visibility) }}</p>
            </div>
            <UiButton v-if="!selectedContest.joined && liveContestPhase(selectedContest) !== 'FINISHED'" size="sm"
                      @click="join(selectedContest)">
              <Trophy :size="15"/>
              加入比赛
            </UiButton>
            <span v-else-if="selectedContest.joined" class="training-joined-badge"><Check :size="14"/>已加入</span>
          </header>
          <div class="training-detail-meta"><span><CalendarDays :size="15"/>{{
              formatDateTime(selectedContest.startsAt)
            }} - {{ formatDateTime(selectedContest.endsAt) }}</span><span><Users
              :size="15"/>{{ selectedContest.participantCount }}/{{ selectedContest.maxParticipants }} 人</span><span><ListChecks
              :size="15"/>{{ selectedContest.problems.length }} 道题</span></div>
          <section class="training-time-flow"
                   :class="'training-time-flow--' + timeTone(contestRemainingRatio(selectedContest))">
            <header><span><Clock3 :size="15"/>比赛计时</span><strong>{{ contestCountdown(selectedContest) }}</strong>
            </header>
            <div class="training-time-track" role="progressbar" aria-label="比赛剩余时间" aria-valuemin="0"
                 aria-valuemax="100" :aria-valuenow="Math.round(contestRemainingRatio(selectedContest) * 100)"><i
                :style="{ width: progressWidth(contestRemainingRatio(selectedContest)) }"/></div>
            <footer><span>{{
                contestElapsed(selectedContest)
              }}</span><span>{{ Math.round(contestRemainingRatio(selectedContest) * 100) }}% 剩余</span></footer>
          </section>
          <section class="training-detail-section">
            <header class="training-section-heading">
              <div><h3>题目</h3><span>比赛期间锁定版本</span></div>
              <span class="training-section-count">{{ selectedContest.problems.length }} 题</span></header>
            <div class="training-problem-list">
              <button v-for="problem in selectedContest.problems" :key="problem.versionId" type="button"
                      :disabled="!selectedContest.joined || liveContestPhase(selectedContest) !== 'RUNNING'"
                      @click="openContestProblem(selectedContest, problem.problemId, problem.versionId)"><span
                  class="training-problem-ordinal">{{ formatOrdinal(problem.ordinal) }}</span><span
                  class="training-problem-copy"><strong>{{
                  problem.title
                }}</strong><small>版本已锁定</small></span><span
                  class="training-problem-score">{{ selectedContest.myScores?.[problem.problemId] ?? 0 }} 分</span>
                <ExternalLink :size="15" aria-hidden="true"/>
              </button>
            </div>
            <p v-if="!selectedContest.joined" class="training-detail-hint">加入比赛后才能打开题目</p>
            <p v-else-if="liveContestPhase(selectedContest) === 'UPCOMING'" class="training-detail-hint">
              比赛开始后可以进入题目</p>
            <p v-else-if="liveContestPhase(selectedContest) === 'FINISHED'" class="training-detail-hint">
              比赛已经结束</p></section>
          <section v-if="selectedContest.ranking?.length" class="training-detail-section training-ranking">
            <header class="training-section-heading">
              <div><h3>实时排名</h3></div>
              <span class="training-section-count">{{ selectedContest.ranking.length }} 人</span></header>
            <UiTable>
              <thead>
              <tr>
                <th>#</th>
                <th>用户</th>
                <th>总分</th>
                <th>用时</th>
              </tr>
              </thead>
              <tbody>
              <tr v-for="row in selectedContest.ranking" :key="row.username">
                <td>{{ row.rank }}</td>
                <td>{{ row.username }}</td>
                <td class="training-ranking-score">{{ row.totalScore }}</td>
                <td>{{ formatDuration(row.elapsedSeconds) }}</td>
              </tr>
              </tbody>
            </UiTable>
          </section>
        </template>
        <div v-else class="training-empty-panel"><span class="training-empty-icon"><Trophy :size="26"/></span><strong>选择一场训练赛</strong>
          <p>查看比赛题目、参赛人数和排名</p></div>
      </section>
    </div>

    <div v-else class="training-workbench training-workbench--paper">
      <aside class="training-browser" aria-label="个人计时套卷列表">
        <header class="training-browser-header">
          <div><strong>个人计时套卷</strong><span>独立计时，随时继续</span></div>
          <span class="training-browser-count">{{ papers.length }}</span></header>
        <div v-if="loading" class="training-browser-list training-browser-list--loading" aria-busy="true">
          <div v-for="index in 4" :key="index" class="training-browser-skeleton"><i/><span/><b/></div>
        </div>
        <div v-else-if="papers.length" class="training-browser-list">
          <article v-for="paper in papers" :key="paper.id" class="training-browser-item"
                   :class="{ active: selectedPaper?.id === paper.id }" role="button" tabindex="0"
                   @click="inspectPaper(paper)" @keydown.enter="inspectPaper(paper)">
            <div class="training-browser-item-heading"><span class="training-paper-icon"><FileText
                :size="15"/></span><strong>{{ paper.title }}</strong>
              <ChevronRight :size="15" aria-hidden="true"/>
            </div>
            <div class="training-browser-item-meta"><span><Clock3 :size="13"/>{{
                paper.durationMinutes
              }} 分钟</span><span><ListChecks :size="13"/>{{ paper.problems.length }} 题</span></div>
            <div v-if="attemptFor(paper)" class="training-time-compact"
                 :class="'training-time-compact--' + timeTone(paperRemainingRatio(paper))"><span
                class="training-time-track"><i
                :style="{ width: progressWidth(paperRemainingRatio(paper)) }"/></span><small>{{
                paperCountdown(paper)
              }}</small></div>
            <div class="training-browser-item-footer"><span v-if="isPaperActive(paper)"
                                                            class="training-running-label"><span
                class="training-phase-dot training-phase-dot--running"/>进行中</span><span
                v-else-if="hasPaperAttempt(paper)">已结束</span><span v-else>尚未开始</span>
              <UiButton size="sm" variant="outline"
                        @click.stop="hasPaperAttempt(paper) ? inspectPaper(paper) : startPaper(paper)">
                {{ isPaperActive(paper) ? '继续作答' : hasPaperAttempt(paper) ? '查看结果' : '开始作答' }}
              </UiButton>
            </div>
          </article>
        </div>
        <UiEmptyState v-else description="暂无个人计时套卷" class="training-browser-empty">
          <template #icon>
            <FileText :size="24"/>
          </template>
        </UiEmptyState>
      </aside>

      <section class="training-inspector" aria-live="polite">
        <template v-if="selectedPaper">
          <header class="training-inspector-header">
            <div><span class="training-phase-label training-phase-label--paper"><Clock3 :size="14"/>个人计时</span>
              <h2>{{ selectedPaper.title }}</h2>
              <p>{{ selectedPaper.problems.length }} 道题 · 首次进入后开始独立计时</p></div>
            <UiButton v-if="!hasPaperAttempt(selectedPaper)" size="sm" @click="startPaper(selectedPaper)">
              <Clock3 :size="15"/>
              开始作答
            </UiButton>
            <span v-else-if="isPaperActive(selectedPaper)"
                  class="training-joined-badge training-joined-badge--active"><span
                class="training-phase-dot training-phase-dot--running"/>进行中</span><span v-else
                                                                                           class="training-joined-badge training-joined-badge--finished">已结束</span>
          </header>
          <div class="training-detail-meta"><span><Clock3 :size="15"/>限时 {{
              selectedPaper.durationMinutes
            }} 分钟</span><span><ListChecks :size="15"/>{{ selectedPaper.problems.length }} 道题</span><span
              v-if="selectedAttempt"><Gauge :size="15"/>当前 {{ selectedAttempt.totalScore }} 分</span></div>
          <template v-if="selectedAttempt">
            <section class="training-attempt-status">
              <div class="training-time-flow training-time-flow--embedded"
                   :class="'training-time-flow--' + timeTone(attemptRemainingRatio(selectedAttempt))">
                <header><span><Clock3 :size="15"/>{{
                    attemptFinished(selectedAttempt) ? '作答已结束' : '独立计时中'
                  }}</span><strong>{{
                    attemptFinished(selectedAttempt) ? '已完成' : '剩余 ' + formatDuration(attemptRemainingSeconds(selectedAttempt))
                  }}</strong></header>
                <div class="training-time-track" role="progressbar" aria-label="套卷剩余时间" aria-valuemin="0"
                     aria-valuemax="100" :aria-valuenow="Math.round(attemptRemainingRatio(selectedAttempt) * 100)"><i
                    :style="{ width: progressWidth(attemptRemainingRatio(selectedAttempt)) }"/></div>
                <footer><span>{{
                    attemptElapsed(selectedAttempt)
                  }}</span><span>{{ Math.round(attemptRemainingRatio(selectedAttempt) * 100) }}% 剩余</span></footer>
              </div>
              <div class="training-attempt-score"><small>当前得分</small><strong>{{ selectedAttempt.totalScore }}<em>/100</em></strong>
              </div>
              <UiButton variant="outline" size="sm" @click="shareAttempt">
                <Link2 :size="15"/>
                分享
              </UiButton>
            </section>
            <section class="training-detail-section">
              <header class="training-section-heading">
                <div><h3>题目</h3><span>{{
                    attemptFinished(selectedAttempt) ? '本次作答已经结束' : '点击题目继续作答'
                  }}</span></div>
                <span class="training-section-count">{{ selectedAttempt.paper.problems.length }} 题</span></header>
              <div class="training-problem-list">
                <button v-for="problem in selectedAttempt.paper.problems" :key="problem.versionId" type="button"
                        :disabled="attemptFinished(selectedAttempt)"
                        @click="openTimedProblem(problem.problemId, problem.versionId)"><span
                    class="training-problem-ordinal">{{ formatOrdinal(problem.ordinal) }}</span><span
                    class="training-problem-copy"><strong>{{
                    problem.title
                  }}</strong><small>锁定版本 · {{
                    selectedAttempt.scores[problem.problemId] ?? 0
                  }} 分</small></span><span
                    class="training-problem-score">{{ selectedAttempt.scores[problem.problemId] ?? 0 }} 分</span>
                  <ExternalLink :size="15" aria-hidden="true"/>
                </button>
              </div>
            </section>
          </template>
          <div v-else class="training-start-panel"><span class="training-empty-icon training-empty-icon--paper"><Clock3
              :size="26"/></span><strong>准备好后开始计时</strong>
            <p>开始后计时只属于这一次作答，可以从任意题目进入。</p>
            <UiButton @click="startPaper(selectedPaper)">
              <Clock3 :size="15"/>
              开始作答
            </UiButton>
          </div>
        </template>
        <div v-else class="training-empty-panel"><span class="training-empty-icon training-empty-icon--paper"><FileText
            :size="26"/></span><strong>选择一套计时套卷</strong>
          <p>从左侧查看套卷内容，开始一段独立练习。</p></div>
      </section>
    </div>

    <UiDialog v-model="contestDialog" title="创建训练赛" class="training-create-dialog">
      <form class="problem-form" @submit.prevent="createContest">
        <div class="form-field training-form-title">
          <UiLabel>比赛名称</UiLabel>
          <UiInput v-model="contestForm.title" maxlength="120" placeholder="输入比赛名称" required/>
        </div>
        <div class="training-form-grid">
          <div class="form-field">
            <UiLabel>可见性</UiLabel>
            <UiSelect v-model="contestForm.visibility" placeholder="">
              <option value="PUBLIC">公开</option>
              <option value="PASSWORD">口令</option>
            </UiSelect>
          </div>
          <div v-if="contestForm.visibility === 'PASSWORD'" class="form-field">
            <UiLabel>比赛口令</UiLabel>
            <UiInput v-model="contestForm.password" type="password" placeholder="8 到 100 位" required/>
          </div>
          <div class="form-field training-form-time">
            <UiLabel>开始时间</UiLabel>
            <UiInput v-model="contestForm.startsAt" type="datetime-local" required/>
          </div>
          <div class="form-field">
            <UiLabel>时长（分钟）</UiLabel>
            <UiNumberField v-model="contestForm.durationMinutes" :min="15" :max="300" required/>
          </div>
        </div>
        <div class="form-field">
          <UiLabel>选择题目</UiLabel>
          <ProblemPicker v-model="contestForm.problemIds" :problems="problems"/>
        </div>
        <div class="form-actions"><span class="training-form-selection">{{
            contestForm.problemIds.length ? `已选择 ${contestForm.problemIds.length} 道题` : "至少选择一道题"
          }}</span>
          <div>
            <UiButton variant="outline" type="button" @click="contestDialog = false">取消</UiButton>
            <UiButton type="submit" :disabled="!contestForm.problemIds.length">
              <Trophy :size="16"/>
              创建
            </UiButton>
          </div>
        </div>
      </form>
    </UiDialog>
    <UiDialog v-model="paperDialog" title="创建个人计时套卷" class="training-create-dialog">
      <form class="problem-form" @submit.prevent="createPaper">
        <div class="training-form-grid training-form-grid--paper">
          <div class="form-field training-form-title">
            <UiLabel>套卷名称</UiLabel>
            <UiInput v-model="paperForm.title" maxlength="120" placeholder="输入套卷名称" required/>
          </div>
          <div class="form-field">
            <UiLabel>时长（分钟）</UiLabel>
            <UiNumberField v-model="paperForm.durationMinutes" :min="15" :max="300" required/>
          </div>
        </div>
        <div class="form-field">
          <UiLabel>选择题目</UiLabel>
          <ProblemPicker v-model="paperForm.problemIds" :problems="problems"/>
        </div>
        <div class="form-actions"><span class="training-form-selection">{{
            paperForm.problemIds.length ? `已选择 ${paperForm.problemIds.length} 道题` : "至少选择一道题"
          }}</span>
          <div>
            <UiButton variant="outline" type="button" @click="paperDialog = false">取消</UiButton>
            <UiButton type="submit" :disabled="!paperForm.problemIds.length">
              <FileText :size="16"/>
              创建套卷
            </UiButton>
          </div>
        </div>
      </form>
    </UiDialog>
  </section>
</template>
