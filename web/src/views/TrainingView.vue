<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { CalendarClock, CalendarDays, Check, ChevronRight, Clock3, ExternalLink, FileText, Gauge, Link2, ListChecks, LockKeyhole, Plus, RefreshCw, Trophy, Users } from "@lucide/vue";
import { promptAction, toast } from "../lib/notify";
import { useRouter } from "vue-router";
import { api } from "../api/client";
import type { Contest, ContestVisibility, ProblemSummary, TimedAttempt, TimedPaper } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiDialog from "../components/ui/Dialog.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiSelect from "../components/ui/Select.vue";
import UiTable from "../components/ui/Table.vue";
import UiLabel from "../components/ui/Label.vue";

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
/** 当前正在作答的计时套卷。 */
const activeAttempt = ref<TimedAttempt>();
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
  startsAt: new Date(Date.now() + 15 * 60_000).toISOString().slice(0, 16),
  durationMinutes: 120,
  problemIds: [] as string[],
});

/** 新建套卷表单。 */
const paperForm = reactive({ title: "", durationMinutes: 120, problemIds: [] as string[] });

/** 当前套卷剩余秒数。 */
const remainingSeconds = computed(() => {
  if (!activeAttempt.value || activeAttempt.value.finished) return 0;
  return Math.max(0, Math.floor((new Date(activeAttempt.value.expiresAt).getTime() - now.value) / 1000));
});

/** 训练中心顶部概览统计。 */
const runningContestCount = computed(() => contests.value.filter((contest) => contest.phase === "RUNNING").length);
const upcomingContestCount = computed(() => contests.value.filter((contest) => contest.phase === "UPCOMING").length);
const paperProblemCount = computed(() => papers.value.reduce((total, paper) => total + paper.problems.length, 0));
/** 当前选中的套卷是否对应正在进行的作答。 */
const selectedAttempt = computed(() => {
  if (!selectedPaper.value || activeAttempt.value?.paper.id !== selectedPaper.value.id) return undefined;
  return activeAttempt.value;
});

/** 格式化分钟与秒倒计时。 */
function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  return String(minutes).padStart(2, "0") + ":" + String(seconds % 60).padStart(2, "0");
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
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

/** 将题目序号统一为两位显示，便于在列表中快速定位。 */
function formatOrdinal(value: number): string {
  return String(value).padStart(2, "0");
}

/** 判断套卷是否存在当前页面内的进行中作答。 */
function isPaperActive(paper: TimedPaper): boolean {
  return activeAttempt.value?.paper.id === paper.id;
}

/** 加载训练中心所需数据。 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    [contests.value, papers.value, problems.value] = await Promise.all([api.contests(), api.timedPapers(), api.problems({})]);
    if (selectedContest.value && !contests.value.some((contest) => contest.id === selectedContest.value?.id)) selectedContest.value = undefined;
    if (selectedPaper.value && !papers.value.some((paper) => paper.id === selectedPaper.value?.id)) selectedPaper.value = undefined;
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
      startsAt: new Date(contestForm.startsAt).toISOString(),
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
  void router.push({ path: "/problems/" + problemId, query: { versionId, contestId: contest.id } });
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
    activeAttempt.value = await api.startTimedPaper(paper.id);
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
  if (!activeAttempt.value) return;
  void router.push({ path: "/problems/" + problemId, query: { versionId, timedPaperAttemptId: activeAttempt.value.id } });
}

/** 生成并复制只读分享链接。 */
async function shareAttempt(): Promise<void> {
  if (!activeAttempt.value) return;
  try {
    const response = await api.shareTimedAttempt(activeAttempt.value.id);
    const url = location.origin + "/shares/timed-papers/" + response.token;
    await navigator.clipboard.writeText(url);
    toast.success("只读分享链接已复制");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "分享链接生成失败");
  }
}

onMounted(() => {
  ticker = window.setInterval(() => { now.value = Date.now(); }, 1000);
  void load();
});
onBeforeUnmount(() => window.clearInterval(ticker));
</script>

<template>
  <section class="content-page content-page--modern oj-page training-page training-page--refactored loading-shell" :aria-busy="loading">
    <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>

    <header class="training-page-heading">
      <div class="training-page-title"><span class="training-page-kicker"><CalendarClock :size="15" />训练中心</span><h1>训练</h1></div>
      <div class="training-page-actions"><span v-if="loading" class="training-sync-state"><span class="training-sync-dot" />同步中</span><UiButton variant="outline" size="sm" :loading="loading" @click="load"><RefreshCw :size="15" />刷新</UiButton><UiButton size="sm" @click="tab === 'contest' ? contestDialog = true : paperDialog = true"><Plus :size="15" />{{ tab === 'contest' ? '创建比赛' : '创建套卷' }}</UiButton></div>
    </header>

    <div class="training-metrics" aria-label="训练概览">
      <div class="training-metric"><span class="training-metric-icon training-metric-icon--contest"><Trophy :size="17" /></span><span><strong>{{ contests.length }}</strong><small>训练赛</small></span><em>{{ runningContestCount }} 进行中</em></div>
      <div class="training-metric"><span class="training-metric-icon training-metric-icon--upcoming"><CalendarDays :size="17" /></span><span><strong>{{ upcomingContestCount }}</strong><small>即将开始</small></span><em>等待开赛</em></div>
      <div class="training-metric"><span class="training-metric-icon training-metric-icon--paper"><FileText :size="17" /></span><span><strong>{{ papers.length }}</strong><small>个人套卷</small></span><em>{{ paperProblemCount }} 道题</em></div>
      <div class="training-metric"><span class="training-metric-icon training-metric-icon--timer"><Gauge :size="17" /></span><span><strong>{{ activeAttempt ? activeAttempt.totalScore : 0 }}</strong><small>当前得分</small></span><em>{{ activeAttempt && !activeAttempt.finished ? "计时中" : "未开始" }}</em></div>
    </div>

    <nav class="training-tabs" role="tablist" aria-label="训练类型">
      <button type="button" role="tab" :aria-selected="tab === 'contest'" :class="{ active: tab === 'contest' }" @click="tab = 'contest'"><Trophy :size="16" />训练赛<span>{{ contests.length }}</span></button>
      <button type="button" role="tab" :aria-selected="tab === 'paper'" :class="{ active: tab === 'paper' }" @click="tab = 'paper'"><Clock3 :size="16" />个人计时<span>{{ papers.length }}</span></button>
    </nav>

    <div v-if="tab === 'contest'" class="training-workbench training-workbench--contest">
      <aside class="training-browser" aria-label="训练赛列表">
        <header class="training-browser-header"><div><strong>公开训练赛</strong><span>按开始时间排列</span></div><span class="training-browser-count">{{ contests.length }}</span></header>
        <div v-if="loading" class="training-browser-list training-browser-list--loading" aria-busy="true"><div v-for="index in 5" :key="index" class="training-browser-skeleton"><i /><span /><b /></div></div>
        <div v-else-if="contests.length" class="training-browser-list">
          <article v-for="row in contests" :key="row.id" class="training-browser-item" :class="{ active: selectedContest?.id === row.id }" role="button" tabindex="0" @click="selectContest(row)" @keydown.enter="selectContest(row)">
            <div class="training-browser-item-heading"><span class="training-phase-dot" :class="'training-phase-dot--' + row.phase.toLowerCase()" /><strong>{{ row.title }}</strong><ChevronRight :size="15" aria-hidden="true" /></div>
            <div class="training-browser-item-meta"><span>{{ phaseText(row.phase) }}</span><span>{{ formatDateTime(row.startsAt) }}</span></div>
            <div class="training-browser-item-footer"><span><Users :size="13" />{{ row.participantCount }}/{{ row.maxParticipants }}</span><span><LockKeyhole v-if="row.visibility === 'PASSWORD'" :size="13" /><span v-else class="training-public-dot" />{{ visibilityText(row.visibility) }}</span><span v-if="row.joined" class="training-joined-label"><Check :size="13" />已加入</span></div>
          </article>
        </div>
        <UiEmptyState v-else description="暂无训练赛" class="training-browser-empty"><template #icon><Trophy :size="24" /></template></UiEmptyState>
      </aside>

      <section class="training-inspector" aria-live="polite">
        <template v-if="selectedContest">
          <header class="training-inspector-header"><div><span class="training-phase-label" :class="'training-phase-label--' + selectedContest.phase.toLowerCase()"><span class="training-phase-dot" :class="'training-phase-dot--' + selectedContest.phase.toLowerCase()" />{{ phaseText(selectedContest.phase) }}</span><h2>{{ selectedContest.title }}</h2><p>由 {{ selectedContest.ownerUsername }} 创建 · {{ visibilityText(selectedContest.visibility) }}</p></div><UiButton v-if="!selectedContest.joined && selectedContest.phase !== 'FINISHED'" size="sm" @click="join(selectedContest)"><Trophy :size="15" />加入比赛</UiButton><span v-else-if="selectedContest.joined" class="training-joined-badge"><Check :size="14" />已加入</span></header>
          <div class="training-detail-meta"><span><CalendarDays :size="15" />{{ formatDateTime(selectedContest.startsAt) }} - {{ formatDateTime(selectedContest.endsAt) }}</span><span><Users :size="15" />{{ selectedContest.participantCount }}/{{ selectedContest.maxParticipants }} 人</span><span><ListChecks :size="15" />{{ selectedContest.problems.length }} 道题</span></div>
          <section class="training-detail-section"><header class="training-section-heading"><div><h3>题目</h3><span>比赛期间锁定版本</span></div><span class="training-section-count">{{ selectedContest.problems.length }} 题</span></header><div class="training-problem-list"><button v-for="problem in selectedContest.problems" :key="problem.versionId" type="button" :disabled="!selectedContest.joined || selectedContest.phase === 'UPCOMING'" @click="openContestProblem(selectedContest, problem.problemId, problem.versionId)"><span class="training-problem-ordinal">{{ formatOrdinal(problem.ordinal) }}</span><span class="training-problem-copy"><strong>{{ problem.title }}</strong><small>版本已锁定</small></span><span class="training-problem-score">{{ selectedContest.myScores?.[problem.problemId] ?? 0 }} 分</span><ExternalLink :size="15" aria-hidden="true" /></button></div><p v-if="!selectedContest.joined" class="training-detail-hint">加入比赛后才能打开题目</p><p v-else-if="selectedContest.phase === 'UPCOMING'" class="training-detail-hint">比赛开始后可以进入题目</p></section>
          <section v-if="selectedContest.ranking?.length" class="training-detail-section training-ranking"><header class="training-section-heading"><div><h3>实时排名</h3><span>OI 计分 · 同分按用时排序</span></div><span class="training-section-count">{{ selectedContest.ranking.length }} 人</span></header><UiTable><thead><tr><th>#</th><th>用户</th><th>总分</th><th>用时</th></tr></thead><tbody><tr v-for="row in selectedContest.ranking" :key="row.username"><td>{{ row.rank }}</td><td>{{ row.username }}</td><td class="training-ranking-score">{{ row.totalScore }}</td><td>{{ formatDuration(row.elapsedSeconds) }}</td></tr></tbody></UiTable></section>
        </template>
        <div v-else class="training-empty-panel"><span class="training-empty-icon"><Trophy :size="26" /></span><strong>选择一场训练赛</strong><p>查看比赛题目、参赛人数和排名</p></div>
      </section>
    </div>

    <div v-else class="training-workbench training-workbench--paper">
      <aside class="training-browser" aria-label="个人计时套卷列表">
        <header class="training-browser-header"><div><strong>个人计时套卷</strong><span>独立计时，随时继续</span></div><span class="training-browser-count">{{ papers.length }}</span></header>
        <div v-if="loading" class="training-browser-list training-browser-list--loading" aria-busy="true"><div v-for="index in 4" :key="index" class="training-browser-skeleton"><i /><span /><b /></div></div>
        <div v-else-if="papers.length" class="training-browser-list">
          <article v-for="paper in papers" :key="paper.id" class="training-browser-item" :class="{ active: selectedPaper?.id === paper.id }" role="button" tabindex="0" @click="inspectPaper(paper)" @keydown.enter="inspectPaper(paper)">
            <div class="training-browser-item-heading"><span class="training-paper-icon"><FileText :size="15" /></span><strong>{{ paper.title }}</strong><ChevronRight :size="15" aria-hidden="true" /></div>
            <div class="training-browser-item-meta"><span><Clock3 :size="13" />{{ paper.durationMinutes }} 分钟</span><span><ListChecks :size="13" />{{ paper.problems.length }} 题</span></div>
            <div class="training-browser-item-footer"><span v-if="isPaperActive(paper)" class="training-running-label"><span class="training-phase-dot training-phase-dot--running" />进行中</span><span v-else>尚未开始</span><UiButton size="sm" variant="outline" @click.stop="isPaperActive(paper) ? inspectPaper(paper) : startPaper(paper)">{{ isPaperActive(paper) ? '继续作答' : '开始作答' }}</UiButton></div>
          </article>
        </div>
        <UiEmptyState v-else description="暂无个人计时套卷" class="training-browser-empty"><template #icon><FileText :size="24" /></template></UiEmptyState>
      </aside>

      <section class="training-inspector" aria-live="polite">
        <template v-if="selectedPaper">
          <header class="training-inspector-header"><div><span class="training-phase-label training-phase-label--paper"><Clock3 :size="14" />个人计时</span><h2>{{ selectedPaper.title }}</h2><p>{{ selectedPaper.problems.length }} 道题 · 首次进入后开始独立计时</p></div><UiButton v-if="!isPaperActive(selectedPaper)" size="sm" @click="startPaper(selectedPaper)"><Clock3 :size="15" />开始作答</UiButton><span v-else class="training-joined-badge training-joined-badge--active"><span class="training-phase-dot training-phase-dot--running" />进行中</span></header>
          <div class="training-detail-meta"><span><Clock3 :size="15" />限时 {{ selectedPaper.durationMinutes }} 分钟</span><span><ListChecks :size="15" />{{ selectedPaper.problems.length }} 道题</span><span v-if="selectedAttempt"><Gauge :size="15" />当前 {{ selectedAttempt.totalScore }} 分</span></div>
          <template v-if="selectedAttempt">
            <section class="training-attempt-status"><div><span class="training-attempt-label">{{ selectedAttempt.finished ? '作答已结束' : '独立计时中' }}</span><strong>{{ selectedAttempt.finished ? '已完成' : formatDuration(remainingSeconds) }}</strong></div><div class="training-attempt-score"><small>当前得分</small><strong>{{ selectedAttempt.totalScore }}<em>/100</em></strong></div><UiButton variant="outline" size="sm" @click="shareAttempt"><Link2 :size="15" />分享</UiButton></section>
            <section class="training-detail-section"><header class="training-section-heading"><div><h3>题目</h3><span>点击题目继续作答</span></div><span class="training-section-count">{{ selectedAttempt.paper.problems.length }} 题</span></header><div class="training-problem-list"><button v-for="problem in selectedAttempt.paper.problems" :key="problem.versionId" type="button" :disabled="selectedAttempt.finished" @click="openTimedProblem(problem.problemId, problem.versionId)"><span class="training-problem-ordinal">{{ formatOrdinal(problem.ordinal) }}</span><span class="training-problem-copy"><strong>{{ problem.title }}</strong><small>锁定版本 · {{ selectedAttempt.scores[problem.problemId] ?? 0 }} 分</small></span><span class="training-problem-score">{{ selectedAttempt.scores[problem.problemId] ?? 0 }} 分</span><ExternalLink :size="15" aria-hidden="true" /></button></div></section>
          </template>
          <div v-else class="training-start-panel"><span class="training-empty-icon training-empty-icon--paper"><Clock3 :size="26" /></span><strong>准备好后开始计时</strong><p>开始后计时只属于这一次作答，可以从任意题目进入。</p><UiButton @click="startPaper(selectedPaper)"><Clock3 :size="15" />开始作答</UiButton></div>
        </template>
        <div v-else class="training-empty-panel"><span class="training-empty-icon training-empty-icon--paper"><FileText :size="26" /></span><strong>选择一套计时套卷</strong><p>从左侧查看套卷内容，开始一段独立练习。</p></div>
      </section>
    </div>

    <UiDialog v-model="contestDialog" title="创建训练赛">
      <form class="problem-form" @submit.prevent="createContest">
        <div class="form-field"><UiLabel>标题</UiLabel><UiInput v-model="contestForm.title" maxlength="120" /></div>
        <div class="form-grid"><div class="form-field"><UiLabel>可见性</UiLabel><UiSelect v-model="contestForm.visibility" placeholder=""><option value="PUBLIC">公开</option><option value="PASSWORD">口令</option></UiSelect></div><div v-if="contestForm.visibility === 'PASSWORD'" class="form-field"><UiLabel>口令</UiLabel><UiInput v-model="contestForm.password" type="password" /></div><div class="form-field"><UiLabel>开始时间</UiLabel><UiInput v-model="contestForm.startsAt" type="datetime-local" /></div><div class="form-field"><UiLabel>时长（分钟）</UiLabel><UiNumberField v-model="contestForm.durationMinutes" :min="15" :max="300" /></div></div>
        <div class="form-field"><UiLabel>题目（可多选）</UiLabel><select v-model="contestForm.problemIds" class="multi-select" multiple><option v-for="problem in problems" :key="problem.id" :value="problem.id">{{ problem.title }}</option></select></div>
        <div class="form-actions"><UiButton variant="outline" type="button" @click="contestDialog = false">取消</UiButton><UiButton type="submit"><Trophy :size="16" />创建</UiButton></div>
      </form>
    </UiDialog>
    <UiDialog v-model="paperDialog" title="创建个人计时套卷">
      <form class="problem-form" @submit.prevent="createPaper">
        <div class="form-field"><UiLabel>标题</UiLabel><UiInput v-model="paperForm.title" maxlength="120" /></div><div class="form-field"><UiLabel>时长（分钟）</UiLabel><UiNumberField v-model="paperForm.durationMinutes" :min="15" :max="300" /></div><div class="form-field"><UiLabel>题目（可多选）</UiLabel><select v-model="paperForm.problemIds" class="multi-select" multiple><option v-for="problem in problems" :key="problem.id" :value="problem.id">{{ problem.title }}</option></select></div>
        <div class="form-actions"><UiButton variant="outline" type="button" @click="paperDialog = false">取消</UiButton><UiButton type="submit"><FileText :size="16" />创建套卷</UiButton></div>
      </form>
    </UiDialog>
  </section>
</template>
