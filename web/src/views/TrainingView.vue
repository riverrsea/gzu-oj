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
  Medal,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  Trophy,
  Users
} from "@lucide/vue";
import {promptAction, toast} from "../lib/notify";
import {APP_TIME_ZONE, chinaDateTimeInputToIso, toChinaDateTimeInputValue} from "../lib/time";
import {useRouter} from "vue-router";
import {api} from "../api/client";
import type {Contest, ContestSummary, ContestVisibility, ProblemSummary, TimedAttempt, TimedPaper} from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiDialog from "../components/ui/Dialog.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiSelect from "../components/ui/Select.vue";
import UiLabel from "../components/ui/Label.vue";
import ProblemPicker from "../components/ProblemPicker.vue";

/** 训练中心的当前标签。 */
const tab = ref<"contest" | "paper">("contest");
/** 页面加载状态。 */
const loading = ref(false);
/** 比赛列表独立查询状态，避免筛选时阻塞个人计时数据。 */
const contestLoading = ref(false);
/** 比赛详情独立加载状态。 */
const contestDetailLoading = ref(false);
/** 当前公开赛和口令赛摘要列表。 */
const contests = ref<ContestSummary[]>([]);
/** 当前用户的个人套卷。 */
const papers = ref<TimedPaper[]>([]);
/** 可供组题的发布题目。 */
const problems = ref<ProblemSummary[]>([]);
/** 当前展开的比赛；未加入的口令赛只保存安全摘要。 */
const selectedContest = ref<Contest | ContestSummary>();
/** 当前是否打开训练赛详情遮罩。 */
const contestDetailOpen = ref(false);
/** 按套卷标识保存的既有作答，页面重进后由服务端恢复。 */
const attemptsByPaperId = ref<Record<string, TimedAttempt>>({});
/** 当前展开的个人计时套卷。 */
const selectedPaper = ref<TimedPaper>();
/** 当前是否打开个人套卷详情遮罩。 */
const paperDetailOpen = ref(false);
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

/** 训练赛查询表单，空可见性表示查询全部类型。 */
const contestQuery = reactive({
  keyword: "",
  visibility: "" as ContestVisibility | "",
});
/** 已应用到列表请求的训练赛查询条件。 */
const appliedContestQuery = ref<{keyword?: string; visibility?: ContestVisibility}>({});

/** 新建套卷表单。 */
const paperForm = reactive({title: "", durationMinutes: 120, problemIds: [] as string[]});

/** 当前选中套卷对应的既有作答。 */
const selectedAttempt = computed(() => {
  if (!selectedPaper.value) return undefined;
  return attemptsByPaperId.value[selectedPaper.value.id];
});
/** 当前是否已经拿到受权限保护的比赛完整详情。 */
const selectedContestDetail = computed<Contest | undefined>(() => {
  const selected = selectedContest.value;
  return selected && "problems" in selected ? selected : undefined;
});
/** 当前列表是否应用了任意查询条件。 */
const hasContestQuery = computed(() => Boolean(appliedContestQuery.value.keyword || appliedContestQuery.value.visibility));
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
function liveContestPhase(contest: Pick<Contest, "startsAt" | "endsAt">): Contest["phase"] {
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
function contestTotalSeconds(contest: Pick<Contest, "startsAt" | "endsAt">): number {
  return Math.max(0, (new Date(contest.endsAt).getTime() - new Date(contest.startsAt).getTime()) / 1000);
}

/** 返回比赛当前剩余比例；未开始时保持完整，结束后归零。 */
function contestRemainingRatio(contest: Pick<Contest, "startsAt" | "endsAt">): number {
  const phase = liveContestPhase(contest);
  if (phase === "UPCOMING") return 1;
  if (phase === "FINISHED") return 0;
  return boundedRatio((new Date(contest.endsAt).getTime() - now.value) / 1000, contestTotalSeconds(contest));
}

/** 返回比赛倒计时主文案。 */
function contestCountdown(contest: Pick<Contest, "startsAt" | "endsAt">): string {
  const phase = liveContestPhase(contest);
  if (phase === "UPCOMING") return "距开始 " + formatDuration((new Date(contest.startsAt).getTime() - now.value) / 1000);
  if (phase === "RUNNING") return "剩余 " + formatDuration((new Date(contest.endsAt).getTime() - now.value) / 1000);
  return "已结束";
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
      api.contests(appliedContestQuery.value),
      api.timedPapers(),
      api.problems({}),
      api.timedAttempts(),
    ]);
    contests.value = loadedContests;
    papers.value = loadedPapers;
    problems.value = loadedProblems;
    attemptsByPaperId.value = Object.fromEntries(loadedAttempts.map((attempt) => [attempt.paper.id, attempt]));
    if (selectedContest.value && !contests.value.some((contest) => contest.id === selectedContest.value?.id)) {
      selectedContest.value = undefined;
      contestDetailOpen.value = false;
    }
    if (selectedPaper.value && !papers.value.some((paper) => paper.id === selectedPaper.value?.id)) {
      selectedPaper.value = undefined;
      paperDetailOpen.value = false;
    }
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "训练中心加载失败");
  } finally {
    loading.value = false;
  }
}

/** 将查询表单转换成不携带空字符串的接口参数。 */
function normalizedContestQuery(): {keyword?: string; visibility?: ContestVisibility} {
  const keyword = contestQuery.keyword.trim();
  return {
    keyword: keyword || undefined,
    visibility: contestQuery.visibility || undefined,
  };
}

/** 仅刷新训练赛摘要，并在筛选移除当前项时关闭旧详情。 */
async function loadContestList(): Promise<void> {
  contestLoading.value = true;
  try {
    contests.value = await api.contests(appliedContestQuery.value);
    if (selectedContest.value && !contests.value.some((contest) => contest.id === selectedContest.value?.id)) {
      selectedContest.value = undefined;
      contestDetailOpen.value = false;
    }
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "训练赛查询失败");
  } finally {
    contestLoading.value = false;
  }
}

/** 应用比赛名称、创建者和可见性查询条件。 */
async function queryContests(): Promise<void> {
  appliedContestQuery.value = normalizedContestQuery();
  await loadContestList();
}

/** 清空训练赛查询条件并恢复全部比赛。 */
async function resetContestQuery(): Promise<void> {
  contestQuery.keyword = "";
  contestQuery.visibility = "";
  appliedContestQuery.value = {};
  await loadContestList();
}

/** 先切换当前比赛，再异步刷新详情，避免点击后右侧出现空白等待。 */
function selectContest(contest: ContestSummary): void {
  selectedContest.value = contest;
  contestDetailOpen.value = true;
  // 未加入的口令赛不能请求完整详情，直接使用列表安全摘要呈现邀请入口。
  if (contest.visibility === "PASSWORD" && !contest.joined) {
    contestDetailLoading.value = false;
    return;
  }
  void inspect(contest);
}

/** 关闭训练赛详情遮罩。 */
function closeContestDetail(): void {
  contestDetailOpen.value = false;
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
    contests.value.unshift(toContestSummary(created));
    selectedContest.value = created;
    contestDetailOpen.value = true;
    contestDialog.value = false;
    toast.success("训练赛已创建");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "训练赛创建失败");
  }
}

/** 加入一场公开或口令比赛。 */
async function join(contest: Contest | ContestSummary): Promise<void> {
  try {
    let password: string | undefined;
    if (contest.visibility === "PASSWORD") {
      password = await promptAction("输入邀请码（8 到 100 位）");
      if (!/^.{8,100}$/.test(password)) {
        toast.warning("邀请码长度需为 8 到 100 位");
        return;
      }
    }
    const joined = await api.joinContest(contest.id, password);
    replaceContest(joined);
    selectedContest.value = joined;
    contestDetailOpen.value = true;
    toast.success("已加入训练赛");
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    toast.error(error instanceof Error ? error.message : "加入失败");
  }
}

/** 刷新并展开比赛详情。 */
async function inspect(contest: ContestSummary): Promise<void> {
  contestDetailLoading.value = true;
  try {
    const detail = await api.contest(contest.id);
    replaceContest(detail);
    // 用户可能在请求返回前切换了比赛，不让旧响应覆盖当前抽屉。
    if (contestDetailOpen.value && selectedContest.value?.id === contest.id) {
      selectedContest.value = detail;
    }
  } catch (error) {
    if (selectedContest.value?.id === contest.id) {
      toast.error(error instanceof Error ? error.message : "比赛详情加载失败");
    }
  } finally {
    if (selectedContest.value?.id === contest.id) contestDetailLoading.value = false;
  }
}

/** 用最新比赛对象替换列表中的旧对象。 */
function replaceContest(contest: Contest): void {
  const index = contests.value.findIndex((item) => item.id === contest.id);
  const summary = toContestSummary(contest);
  if (index >= 0) contests.value[index] = summary;
  else contests.value.unshift(summary);
}

/** 将详情对象压缩为列表使用的比赛元数据，避免列表状态携带题目和排名。 */
function toContestSummary(contest: Contest): ContestSummary {
  return {
    id: contest.id,
    title: contest.title,
    visibility: contest.visibility,
    ownerUsername: contest.ownerUsername,
    createdAt: contest.createdAt,
    startsAt: contest.startsAt,
    endsAt: contest.endsAt,
    phase: contest.phase,
    maxParticipants: contest.maxParticipants,
    participantCount: contest.participantCount,
    joined: contest.joined,
  };
}

/** 打开比赛锁定版本的做题工作区。 */
function openContestProblem(contest: Contest, problemId: string, versionId: string): void {
  void router.push({path: "/problems/" + problemId, query: {versionId, contestId: contest.id}});
}

/** 从比赛详情进入该场比赛的独立排名页面。 */
function openContestRanking(contest: Contest): void {
  void router.push({path: "/rankings", query: {contestId: contest.id}});
}

/** 创建个人计时套卷模板。 */
async function createPaper(): Promise<void> {
  try {
    const created = await api.createTimedPaper(paperForm);
    papers.value.unshift(created);
    selectedPaper.value = created;
    paperDetailOpen.value = true;
    paperDialog.value = false;
    toast.success("计时套卷已创建");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "套卷创建失败");
  }
}

/** 首次进入套卷并启动独立计时。 */
async function startPaper(paper: TimedPaper): Promise<void> {
  selectedPaper.value = paper;
  paperDetailOpen.value = true;
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
  paperDetailOpen.value = true;
}

/** 关闭个人套卷详情遮罩。 */
function closePaperDetail(): void {
  paperDetailOpen.value = false;
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
           :aria-busy="loading || contestLoading">
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

    <nav class="training-tabs" role="tablist" aria-label="训练类型">
      <button type="button" role="tab" :aria-selected="tab === 'contest'" :class="{ active: tab === 'contest' }"
              @click="tab = 'contest'; paperDetailOpen = false">
        <Trophy :size="16"/>
        训练赛</button>
      <button type="button" role="tab" :aria-selected="tab === 'paper'" :class="{ active: tab === 'paper' }"
              @click="tab = 'paper'; contestDetailOpen = false">
        <Clock3 :size="16"/>
        个人计时</button>
    </nav>

    <div v-show="tab === 'contest'" class="training-workbench training-tab-panel training-workbench--contest" :class="{ 'training-workbench--detail-open': contestDetailOpen }">
      <Teleport to="body">
        <Transition name="training-backdrop">
          <div v-if="contestDetailOpen" class="training-detail-backdrop" @click="closeContestDetail" />
        </Transition>
      </Teleport>
      <aside class="training-browser" aria-label="训练赛列表">
        <header class="training-browser-header">
          <div><strong>训练赛</strong><span>查找公开赛或口令赛</span></div>
        </header>
        <form class="training-contest-filters" role="search" @submit.prevent="queryContests">
          <UiInput v-model="contestQuery.keyword" maxlength="120" aria-label="比赛关键词"
                   placeholder="搜索比赛名称或创建者"/>
          <UiSelect v-model="contestQuery.visibility" aria-label="比赛类型" placeholder="全部比赛">
            <option value="PUBLIC">公开赛</option>
            <option value="PASSWORD">口令赛</option>
          </UiSelect>
          <UiButton type="submit" size="sm" :loading="contestLoading">
            <Search :size="14"/>查询
          </UiButton>
          <UiButton type="button" size="sm" variant="outline" :disabled="contestLoading"
                    @click="resetContestQuery">
            <RotateCcw :size="14"/>重置
          </UiButton>
        </form>
        <div v-if="loading || contestLoading" class="training-browser-list training-browser-list--loading" aria-busy="true">
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
        <UiEmptyState v-else :description="hasContestQuery ? '没有符合条件的训练赛' : '暂无训练赛'"
                      class="training-browser-empty">
          <template #icon>
            <Trophy :size="24"/>
          </template>
        </UiEmptyState>
      </aside>

      <Teleport to="body">
        <Transition name="training-drawer">
          <section v-if="contestDetailOpen && selectedContest" class="training-inspector training-detail-drawer" aria-live="polite" role="dialog" aria-modal="true">
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
            <button class="icon-button training-detail-close" type="button" aria-label="关闭详情" @click="closeContestDetail">×</button>
            <div class="training-inspector-actions">
              <UiButton v-if="selectedContestDetail && selectedContest.visibility === 'PUBLIC'" variant="outline"
                        size="sm" @click="openContestRanking(selectedContestDetail)">
                <Medal :size="15"/>查看排名
              </UiButton>
              <UiButton v-if="selectedContest.visibility === 'PUBLIC' && !selectedContest.joined && liveContestPhase(selectedContest) !== 'FINISHED'" size="sm"
                        @click="join(selectedContest)">
                <Trophy :size="15"/>
                加入比赛
              </UiButton>
              <span v-else-if="selectedContest.joined" class="training-joined-badge"><Check :size="14"/>已加入</span>
            </div>
          </header>
          <div class="training-detail-meta"><span><CalendarDays :size="15"/>{{
              formatDateTime(selectedContest.startsAt)
            }} - {{ formatDateTime(selectedContest.endsAt) }}</span><span><Users
              :size="15"/>{{ selectedContest.participantCount }}/{{ selectedContest.maxParticipants }} 人</span><span
              v-if="selectedContestDetail"><ListChecks :size="15"/>{{ selectedContestDetail.problems.length }} 道题</span></div>
          <section v-if="selectedContest.visibility === 'PASSWORD' && !selectedContest.joined"
                   class="training-locked-panel">
            <span class="training-empty-icon training-empty-icon--locked"><LockKeyhole :size="26"/></span>
            <strong>这是一场口令赛</strong>
            <p v-if="liveContestPhase(selectedContest) !== 'FINISHED'">输入创建者提供的邀请码后，才能查看题目、成绩和比赛详情。</p>
            <p v-else>比赛已经结束，未加入用户无法查看题目、成绩和比赛详情。</p>
            <UiButton v-if="liveContestPhase(selectedContest) !== 'FINISHED'" @click="join(selectedContest)">
              <LockKeyhole :size="15"/>输入邀请码加入
            </UiButton>
          </section>
          <div v-else-if="contestDetailLoading" class="training-detail-loading" aria-label="正在加载比赛详情">
            <span class="loading-spinner"/><span>正在加载比赛详情</span>
          </div>
          <section v-else-if="selectedContestDetail" class="training-detail-section">
            <header class="training-section-heading">
              <div><h3>题目</h3></div>
              <span class="training-section-count">{{ selectedContestDetail.problems.length }} 题</span></header>
            <div class="training-problem-list">
              <button v-for="problem in selectedContestDetail.problems" :key="problem.versionId" type="button"
                      :disabled="!selectedContestDetail.joined || liveContestPhase(selectedContestDetail) !== 'RUNNING'"
                      @click="openContestProblem(selectedContestDetail, problem.problemId, problem.versionId)"><span
                  class="training-problem-ordinal">{{ formatOrdinal(problem.ordinal) }}</span><span
                  class="training-problem-copy"><strong>{{
                  problem.title
                }}</strong></span><span
                  class="training-problem-score">{{ selectedContestDetail.myScores?.[problem.problemId] ?? 0 }} 分</span>
                <ExternalLink :size="15" aria-hidden="true"/>
              </button>
            </div>
            <p v-if="!selectedContestDetail.joined" class="training-detail-hint">加入比赛后才能打开题目</p>
            <p v-else-if="liveContestPhase(selectedContestDetail) === 'UPCOMING'" class="training-detail-hint">
              比赛开始后可以进入题目</p>
            <p v-else-if="liveContestPhase(selectedContestDetail) === 'FINISHED'" class="training-detail-hint">
              比赛已经结束</p></section>
        </template>
        <div v-else class="training-empty-panel"><span class="training-empty-icon"><Trophy :size="26"/></span><strong>选择一场训练赛</strong>
          <p>查看比赛题目、参赛人数和排名</p></div>
          </section>
        </Transition>
      </Teleport>
    </div>

    <div v-show="tab === 'paper'" class="training-workbench training-tab-panel training-workbench--paper" :class="{ 'training-workbench--detail-open': paperDetailOpen }">
      <Teleport to="body">
        <Transition name="training-backdrop">
          <div v-if="paperDetailOpen" class="training-detail-backdrop" @click="closePaperDetail" />
        </Transition>
      </Teleport>
      <aside class="training-browser" aria-label="个人计时套卷列表">
        <header class="training-browser-header">
          <div><strong>个人计时套卷</strong><span>独立计时，随时继续</span></div>
        </header>
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

      <Teleport to="body">
        <Transition name="training-drawer">
          <section v-if="paperDetailOpen && selectedPaper" class="training-inspector training-detail-drawer" aria-live="polite" role="dialog" aria-modal="true">
        <template v-if="selectedPaper">
          <header class="training-inspector-header">
            <div><span class="training-phase-label training-phase-label--paper"><Clock3 :size="14"/>个人计时</span>
              <h2>{{ selectedPaper.title }}</h2>
              <p>{{ selectedPaper.problems.length }} 道题 · 首次进入后开始独立计时</p></div>
            <button class="icon-button training-detail-close" type="button" aria-label="关闭详情" @click="closePaperDetail">×</button>
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
                  }}</strong><small>{{
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
        </Transition>
      </Teleport>
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
            <UiLabel>邀请码</UiLabel>
            <UiInput v-model="contestForm.password" type="password" placeholder="设置 8 到 100 位邀请码" required/>
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
