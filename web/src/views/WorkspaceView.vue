<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, watchEffect } from "vue";
import { useRoute, useRouter } from "vue-router";
import { DockviewVue, themeDark, themeLight } from "dockview-vue";
import type { DockviewApi, DockviewReadyEvent, VueComponent } from "dockview-vue";
import "dockview-vue/dist/styles/dockview.css";
import { toast } from "../lib/notify";
import { formatProblemOrdinal } from "../lib/problemOrdinal";
import { renderMarkdown } from "../lib/markdown";
import { api, ApiError } from "../api/client";
import type { JudgeLanguage, JudgeStatus, ProblemDetail, ProblemSummary, Submission, TimedAttempt } from "../api/types";
import { session } from "../stores/session";
import { resetWorkspaceToolbar, workspaceToolbar } from "../stores/workspaceToolbar";
import WorkspaceDockPanel from "../components/WorkspaceDockPanel.vue";
import WorkspaceDockPanelAdapter from "../components/WorkspaceDockPanelAdapter.vue";
import type { CodeSaveState, WorkspacePanelContext, WorkspacePanelKind } from "./workspacePanel";
import UiButton from "../components/ui/Button.vue";
import UiDialog from "../components/ui/Dialog.vue";
import { CheckCircle2, Circle, X } from "@lucide/vue";

/** 移动端的六个工作区标签。桌面端由 Dockview 管理同名面板。 */
type MobileTab = WorkspacePanelKind;

/** 做题页侧栏中的可导航题目；版本号用于保持题面不可变。 */
interface WorkspaceProblemItem {
  problemId: string;
  versionId: string;
  title: string;
  school?: string;
  year?: number;
  difficulty?: string;
  ordinal?: number;
}

type WorkspaceContext =
  | { kind: "problem"; problemId: string; versionId?: string }
  | { kind: "contest"; contestId: string; problemId: string; versionId?: string }
  | { kind: "timed-paper"; timedPaperAttemptId: string; problemId: string; versionId?: string };

const route = useRoute();
const router = useRouter();
const problem = ref<ProblemDetail>();
const latestVersion = ref<ProblemDetail>();
const resumedPreviousVersion = ref(false);
const loading = ref(true);
const loadingError = ref("");
const language = ref<JudgeLanguage>((localStorage.getItem("gzu-oj.language") as JudgeLanguage | null) ?? "CPP17");
const code = ref("");
const fontSize = ref(Number(localStorage.getItem("gzu-oj.font-size") ?? 14));
const mobileTab = ref<MobileTab>("statement");
const activeCase = ref(0);
const submitting = ref(false);
const running = ref(false);
const actionCoolingDown = ref(false);
/** 当前做题页所属的个人计时作答；普通题库和训练赛为空。 */
const timedAttempt = ref<TimedAttempt>();
/** 接收最近一次个人计时快照的本地时间。 */
const timedAttemptSyncedAt = ref(Date.now());
/** 驱动个人计时顶栏倒计时的本地时间。 */
const timedAttemptNow = ref(Date.now());
/** 暂停、继续或提前结束请求是否正在处理。 */
const timedAttemptActionLoading = ref(false);
/** 是否显示提前结束个人计时的确认对话框。 */
const finishConfirmOpen = ref(false);
const isSolved = ref(false);
const isFavorited = ref(false);
const favoriteLoading = ref(false);
const submission = ref<Submission>();
const runSubmission = ref<Submission>();
const submitSubmission = ref<Submission>();
const submissionHistory = ref<Submission[]>([]);
const submissionHistoryLoading = ref(false);
const submissionHistoryError = ref("");
const codeSaveState = ref<CodeSaveState>("saved");
const runInputs = ref<string[]>([]);
const dark = ref(document.documentElement.dataset.theme === "dark");
const settingsOpen = ref(false);
const dockviewApi = ref<DockviewApi>();
const dockLayoutError = ref("");
const notifiedResultKey = ref("");
const wrongBookProblemIds = ref<Set<string>>(new Set());
const wrongBookPrompt = ref<{ submissionId: string; problemId: string } | null>(null);
const wrongBookPromptLoading = ref(false);
const wrongBookPromptMessage = ref("");
/** 当前做题上下文中的题目序列。 */
const navigationItems = ref<WorkspaceProblemItem[]>([]);
/** 题目列表抽屉是否展开。 */
const problemListOpen = ref(false);
/** 题目序列加载状态。 */
const navigationLoading = ref(false);
/** 当前用户已解决的题目标识，用于侧栏状态标记。 */
const solvedProblemIds = ref<Set<string>>(new Set());
/** 当前题目序列的名称。 */
const navigationTitle = ref("题库");
const pollTimers: Record<"RUN" | "SUBMIT", number | undefined> = { RUN: undefined, SUBMIT: undefined };
const dockLayoutStorageKey = "gzu-oj.workspace-dock-layout.v1";
type DockLayoutSnapshot = ReturnType<DockviewApi["toJSON"]>;
const dockPanelKinds: Record<string, WorkspacePanelKind> = {
  statement: "statement",
  code: "code",
  cases: "cases",
  result: "result",
  submit: "submit",
  history: "history",
};

/** 从三类做题页路由统一解析逻辑题目、锁定版本和业务上下文。 */
const workspaceContext = computed<WorkspaceContext>(() => {
  const problemId = String(route.params.problemId ?? route.params.id ?? "");
  const versionId = typeof route.query.versionId === "string" ? route.query.versionId : undefined;
  if (typeof route.params.contestId === "string") {
    return { kind: "contest", contestId: route.params.contestId, problemId, versionId };
  }
  if (typeof route.params.timedPaperAttemptId === "string") {
    return { kind: "timed-paper", timedPaperAttemptId: route.params.timedPaperAttemptId, problemId, versionId };
  }
  return { kind: "problem", problemId, versionId };
});

/** 当前题目在侧栏序列中的位置。 */
const navigationIndex = computed(() => navigationItems.value.findIndex((item) => item.problemId === problem.value?.id && item.versionId === problem.value?.versionId));
/** 是否存在上一道题。 */
const canPreviousProblem = computed(() => navigationIndex.value > 0);
/** 是否存在下一道题。 */
const canNextProblem = computed(() => navigationIndex.value >= 0 && navigationIndex.value < navigationItems.value.length - 1);
/** 判题请求进行中时禁止切题，避免结果写入已经离开的工作区。 */
const navigationLocked = computed(() => loading.value || running.value || submitting.value);

/** 将难度枚举转换为题目侧栏中的简短文案。 */
function difficultyText(value?: string): string {
  return { EASY: "简单", MEDIUM: "中等", HARD: "困难" }[value ?? ""] ?? "";
}
let dockLayoutSubscription: { dispose: () => void } | undefined;
let dockLayoutSaveTimer: number | undefined;
let actionCooldownTimer: number | undefined;
let codeSaveTimer: number | undefined;
let timedAttemptTicker: number | undefined;
let timedAttemptPoller: number | undefined;
let suppressNextCodeAutosave = false;
let codeSaveErrorNotified = false;
const ACTION_COOLDOWN_MS = 1000;
/** 用户停止输入后的源码自动保存等待时间。 */
const CODE_AUTOSAVE_DELAY_MS = 700;

const terminalStatuses = new Set<JudgeStatus>(["AC", "PARTIAL", "WA", "CE", "TLE", "MLE", "RE", "OLE", "SYSTEM_ERROR", "CANCELED"]);
/** 会触发错题本询问的用户代码判题失败状态；基础设施错误和主动取消不计入。 */
const wrongBookCandidateStatuses = new Set<JudgeStatus>(["PARTIAL", "WA", "CE", "TLE", "MLE", "RE", "OLE"]);
/** 当前路由中的个人计时作答标识。 */
const timedAttemptId = computed(() => workspaceContext.value.kind === "timed-paper" ? workspaceContext.value.timedPaperAttemptId : undefined);
/** 使用后端剩余秒数和快照时间计算运行状态下的本地倒计时。 */
const timedAttemptRemainingSeconds = computed(() => {
  const attempt = timedAttempt.value;
  if (!attempt || attempt.status === "FINISHED") return 0;
  if (attempt.status === "PAUSED") return attempt.remainingSeconds;
  const elapsedSeconds = Math.max(0, Math.floor((timedAttemptNow.value - timedAttemptSyncedAt.value) / 1000));
  return Math.max(0, attempt.remainingSeconds - elapsedSeconds);
});
/** 截止时间到达后先在前端切换为结束，下一轮轮询再同步持久化状态。 */
const timedAttemptStatus = computed(() => {
  const status = timedAttempt.value?.status;
  return status === "RUNNING" && timedAttemptRemainingSeconds.value <= 0 ? "FINISHED" : status;
});
const contestRemainingSeconds = computed(() => {
  if (workspaceContext.value.kind !== "contest") return undefined;
  const contestEndsAt = contestEndAt.value;
  return contestEndsAt ? Math.max(0, Math.floor((contestEndsAt - timedAttemptNow.value) / 1000)) : undefined;
});
const contestEndAt = ref<number>();
const renderedStatement = computed(() => {
  if (!problem.value) return "";
  return renderMarkdown(problem.value.statementMarkdown);
});

/** 比赛和个人套卷使用独立的锁定题目序列，不直接复用题库的全局已解决状态。 */
function isScopedPracticeContext(): boolean {
  return workspaceContext.value.kind !== "problem";
}

/** 将后端作答响应保存为新的倒计时基准。 */
function replaceTimedAttempt(attempt: TimedAttempt): void {
  timedAttempt.value = attempt;
  timedAttemptSyncedAt.value = Date.now();
  timedAttemptNow.value = timedAttemptSyncedAt.value;
}

/** 静默刷新当前个人计时状态，使其他标签页的操作能在本页生效。 */
async function refreshTimedAttempt(): Promise<void> {
  const attemptId = timedAttemptId.value;
  if (!attemptId) {
    timedAttempt.value = undefined;
    return;
  }
  try {
    const updated = await api.timedAttempt(attemptId);
    if (timedAttemptId.value === attemptId) replaceTimedAttempt(updated);
  } catch (error) {
    console.warn("个人计时状态同步失败", error);
  }
}

/** 执行个人计时暂停或继续操作。 */
async function changeTimedAttemptState(action: "pause" | "resume"): Promise<void> {
  const attemptId = timedAttemptId.value;
  if (!attemptId || timedAttemptActionLoading.value) return;
  timedAttemptActionLoading.value = true;
  try {
    const updated = action === "pause"
      ? await api.pauseTimedAttempt(attemptId)
      : await api.resumeTimedAttempt(attemptId);
    replaceTimedAttempt(updated);
    toast.success(action === "pause" ? "计时已暂停" : "计时已继续");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "计时状态更新失败");
    await refreshTimedAttempt();
  } finally {
    timedAttemptActionLoading.value = false;
  }
}

/** 打开提前结束当前个人计时作答的确认对话框。 */
function requestFinishTimedAttempt(): void {
  if (!timedAttemptId.value || timedAttemptStatus.value === "FINISHED" || timedAttemptActionLoading.value) return;
  finishConfirmOpen.value = true;
}

/** 确认后不可逆地结束当前个人计时作答。 */
async function finishTimedAttempt(): Promise<void> {
  const attemptId = timedAttemptId.value;
  if (!attemptId || timedAttemptStatus.value === "FINISHED" || timedAttemptActionLoading.value) return;
  timedAttemptActionLoading.value = true;
  try {
    replaceTimedAttempt(await api.finishTimedAttempt(attemptId));
    finishConfirmOpen.value = false;
    toast.success("本次作答已结束");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "结束作答失败");
    await refreshTimedAttempt();
  } finally {
    timedAttemptActionLoading.value = false;
  }
}
const activeLimit = computed(() => problem.value?.languageLimits.find((item) => item.language === language.value));
const dockTheme = computed(() => dark.value ? themeDark : themeLight);
/** Dockview 必须通过组件表解析字符串形式的面板组件名。 */
const dockComponents: Record<string, VueComponent> = {
  workspacePanel: WorkspaceDockPanelAdapter as unknown as VueComponent,
};

/** 工作区标签只允许拖动和切换，不提供关闭入口，避免误关核心面板。 */
function suppressDockTabMenu(): never[] {
  return [];
}

/** 阻止键盘 Delete/Backspace 关闭工作区固定标签。 */
function preventDockTabClose(event: KeyboardEvent): void {
  const target = event.target;
  if ((event.key === "Delete" || event.key === "Backspace") && target instanceof HTMLElement && target.closest(".dv-tab")) {
    event.preventDefault();
    event.stopPropagation();
  }
}

/** 将 Dockview 布局中的运行时上下文替换为可恢复的业务参数。 */
function injectDockPanelParams(snapshot: DockLayoutSnapshot): boolean {
  if (!snapshot.grid || !snapshot.panels) return false;
  for (const [id, kind] of Object.entries(dockPanelKinds)) {
    const panel = snapshot.panels[id];
    if (!panel) return false;
    panel.params = { kind, context: dockContext };
  }
  return true;
}

/** 读取并恢复上次保存的 Dockview 布局。 */
function restoreDockLayout(api: DockviewApi): boolean {
  const raw = localStorage.getItem(dockLayoutStorageKey);
  if (!raw) return false;
  try {
    const snapshot = JSON.parse(raw) as DockLayoutSnapshot;
    if (!injectDockPanelParams(snapshot)) return false;
    api.fromJSON(snapshot);
    return true;
  } catch (error) {
    console.warn("忽略无法恢复的工作区布局", error);
    localStorage.removeItem(dockLayoutStorageKey);
    return false;
  }
}

/** 保存布局结构但不保存题面、源码和函数等运行时上下文。 */
function saveDockLayout(): void {
  const api = dockviewApi.value;
  if (!api) return;
  try {
    const layout = api.toJSON();
    const panels = Object.fromEntries(Object.entries(layout.panels).map(([id, panel]) => [
      id,
      { ...panel, params: { kind: dockPanelKinds[id] } },
    ]));
    localStorage.setItem(dockLayoutStorageKey, JSON.stringify({ ...layout, panels }));
  } catch (error) {
    console.warn("工作区布局保存失败", error);
  }
}

/** 合并连续的拖动和缩放事件，避免频繁写入本地存储。 */
function scheduleDockLayoutSave(): void {
  if (dockLayoutSaveTimer !== undefined) window.clearTimeout(dockLayoutSaveTimer);
  dockLayoutSaveTimer = window.setTimeout(() => {
    dockLayoutSaveTimer = undefined;
    saveDockLayout();
  }, 180);
}

/** 让左右 Dock 面板从中间向各自方向完成一次平滑展开。 */
function animateDockGroups(): void {
  const root = document.querySelector<HTMLElement>(".workspace-dockview");
  if (!root) return;
  const bounds = root.getBoundingClientRect();
  const center = bounds.left + bounds.width / 2;
  root.querySelectorAll<HTMLElement>(".dv-groupview").forEach((group) => {
    const rect = group.getBoundingClientRect();
    const groupCenter = rect.left + rect.width / 2;
    group.style.setProperty("--dock-enter-offset", groupCenter < center ? "52px" : "-52px");
    group.classList.add("workspace-dock-group-enter");
  });
}

const templates: Record<JudgeLanguage, string> = {
  C17: "#include <stdio.h>\n\nint main(void) {\n    return 0;\n}\n",
  CPP17: "#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    ios::sync_with_stdio(false);\n    cin.tie(nullptr);\n\n    return 0;\n}\n",
  JAVA21: "import java.io.*;\nimport java.util.*;\n\npublic class Main {\n    public static void main(String[] args) throws Exception {\n    }\n}\n",
  PYTHON3: "import sys\n\ndef main() -> None:\n    pass\n\nif __name__ == \"__main__\":\n    main()\n",
};

/** 面板共享上下文，确保 Dockview 拆分后的面板仍由同一份状态驱动。 */
const dockContext = reactive<WorkspacePanelContext>({
  problem: undefined,
  renderedStatement: "",
  activeLimit: undefined,
  language: language.value,
  code: "",
  fontSize: fontSize.value,
  dark: dark.value,
  settingsOpen: false,
  runInputs: [],
  activeCase: 0,
  runSubmission: undefined,
  submitSubmission: undefined,
  running: false,
  submitting: false,
  isSolved: false,
  isFavorited: false,
  favoriteLoading: false,
  wrongBookPrompt: null,
  wrongBookPromptLoading: false,
  wrongBookPromptMessage: "",
  submissionHistory: [],
  submissionHistoryLoading: false,
  submissionHistoryError: "",
  refreshSubmissionHistory: () => { void loadSubmissionHistory(); },
  openSubmissionDetail: (submissionId) => openSubmissionDetail(submissionId),
  codeSaveState: "saved",
  terminalStatuses,
  setCode: (value) => { code.value = value; },
  setLanguage: (value) => { language.value = value; },
  setFontSize: (value) => { fontSize.value = value; },
  toggleSettings: () => { settingsOpen.value = !settingsOpen.value; },
  resetCode: () => resetCode(),
  setRunInput: (index, value) => {
    if (runInputs.value[index] !== undefined) runInputs.value[index] = value;
  },
  addRunInput: () => {
    runInputs.value.push("");
    activeCase.value = runInputs.value.length - 1;
  },
  setActiveCase: (index) => { activeCase.value = Math.max(0, Math.min(index, runInputs.value.length - 1)); },
  favorite: () => { void favoriteProblem(); },
  addToWrongBook: () => { void addSubmissionToWrongBook(); },
  dismissWrongBookPrompt: () => dismissWrongBookPrompt(),
  fullscreen: () => requestFullscreen(),
});

/** 当前语言对应的本地草稿键。 */
function draftKey(selected = language.value): string {
  const version = problem.value?.versionId ?? workspaceContext.value.versionId ?? workspaceContext.value.problemId;
  return "gzu-oj.draft." + version + "." + selected;
}

/** 当前题目的版本书签键。 */
function versionBookmarkKey(): string {
  return "gzu-oj.problem-version." + workspaceContext.value.problemId;
}

function saveVersionBookmark(versionId: string): void {
  localStorage.setItem(versionBookmarkKey(), JSON.stringify({ versionId, lastOpenedAt: new Date().toISOString() }));
}

/** 把题目摘要转换为侧栏使用的导航项。 */
function toNavigationItem(problemItem: ProblemSummary, ordinal?: number): WorkspaceProblemItem {
  return {
    problemId: problemItem.id,
    versionId: problemItem.versionId,
    title: problemItem.title,
    school: problemItem.school,
    year: problemItem.year,
    difficulty: problemItem.difficulty,
    ordinal,
  };
}

/** 加载当前做题上下文中的题目序列。训练赛和套卷使用后端锁定版本。 */
async function loadNavigation(): Promise<void> {
  navigationLoading.value = true;
  try {
    const context = workspaceContext.value;
    const contestId = context.kind === "contest" ? context.contestId : undefined;
    const attemptId = context.kind === "timed-paper" ? context.timedPaperAttemptId : undefined;
    if (!attemptId) timedAttempt.value = undefined;
    if (contestId) {
      const contest = await api.contest(contestId);
      navigationTitle.value = contest.title;
      navigationItems.value = contest.problems.map((item) => ({
        problemId: item.problemId,
        versionId: item.versionId,
        title: item.title,
        ordinal: item.ordinal,
      }));
    } else if (attemptId) {
      const attempt = await api.timedAttempt(attemptId);
      replaceTimedAttempt(attempt);
      navigationTitle.value = attempt.paper.title;
      navigationItems.value = attempt.paper.problems.map((item) => ({
        problemId: item.problemId,
        versionId: item.versionId,
        title: item.title,
        ordinal: item.ordinal,
      }));
    } else {
      const catalog = await api.problems({});
      navigationTitle.value = "题库";
      navigationItems.value = catalog.map((item) => toNavigationItem(item));
    }
    // 历史版本可能不在当前公开题库中，仍保留当前题目作为可定位项。
    if (problem.value && !navigationItems.value.some((item) => item.problemId === problem.value?.id && item.versionId === problem.value?.versionId)) {
      navigationItems.value.unshift(toNavigationItem(problem.value));
    }
  } catch (error) {
    navigationItems.value = problem.value ? [toNavigationItem(problem.value)] : [];
    console.warn("题目侧栏加载失败", error);
  } finally {
    navigationLoading.value = false;
  }
}

/** 关闭题目侧栏。 */
function closeProblemList(): void {
  problemListOpen.value = false;
}

/** 在顶栏打开或关闭题目侧栏。 */
function toggleProblemList(): void {
  problemListOpen.value = !problemListOpen.value;
}

/** 进入侧栏选中的题目，并沿用比赛或套卷的上下文参数。 */
function openNavigationProblem(item: WorkspaceProblemItem): void {
  if (navigationLocked.value) return;
  saveCodeDraftNow(code.value, language.value, { notifyFailure: false });
  problemListOpen.value = false;
  const context = workspaceContext.value;
  if (context.kind === "contest") {
    void router.push({ path: "/contests/" + context.contestId + "/problems/" + item.problemId, query: { versionId: item.versionId } });
  } else if (context.kind === "timed-paper") {
    void router.push({ path: "/timed-papers/" + context.timedPaperAttemptId + "/problems/" + item.problemId, query: { versionId: item.versionId } });
  } else {
    void router.push({ path: "/problems/" + item.problemId, query: { versionId: item.versionId } });
  }
}

/** 切换到相邻题目；边界题目保持禁用，避免产生无效路由。 */
function moveToProblem(delta: -1 | 1): void {
  const target = navigationItems.value[navigationIndex.value + delta];
  if (target) openNavigationProblem(target);
}

/** 处理题目侧栏的 Escape 关闭操作。 */
function handleProblemListKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape" && problemListOpen.value) closeProblemList();
}

/** 清理尚未执行的源码自动保存任务。 */
function clearCodeSaveTimer(): void {
  if (codeSaveTimer !== undefined) window.clearTimeout(codeSaveTimer);
  codeSaveTimer = undefined;
}

/** 记录本地草稿保存失败，并避免同一故障持续刷屏。 */
function markCodeSaveFailure(notify = true): void {
  codeSaveState.value = "error";
  if (notify && !codeSaveErrorNotified) {
    toast.error("代码自动保存失败，请检查浏览器本地存储空间");
    codeSaveErrorNotified = true;
  }
}

/** 立即写入指定语言的源码草稿，供快捷键、切换版本和离开页面使用。 */
function saveCodeDraftNow(
  value = code.value,
  selected = language.value,
  options: { notifyFailure?: boolean } = {},
): boolean {
  clearCodeSaveTimer();
  codeSaveState.value = "saving";
  try {
    localStorage.setItem(draftKey(selected), value);
    codeSaveState.value = "saved";
    codeSaveErrorNotified = false;
    return true;
  } catch {
    markCodeSaveFailure(options.notifyFailure !== false);
    return false;
  }
}

/** 在用户停止输入一小段时间后保存源码，避免每次击键都写入存储。 */
function scheduleCodeDraftSave(value: string): void {
  clearCodeSaveTimer();
  codeSaveState.value = "pending";
  codeSaveTimer = window.setTimeout(() => {
    codeSaveTimer = undefined;
    saveCodeDraftNow(value, language.value, { notifyFailure: false });
  }, CODE_AUTOSAVE_DELAY_MS);
}

/** 拦截 Windows/Linux 的 Ctrl+S 和 macOS 的 Cmd+S，立即保存当前源码。 */
function handleCodeSaveShortcut(event: KeyboardEvent): void {
  if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== "s") return;
  event.preventDefault();
  if (!problem.value || loading.value || loadingError.value) return;
  if (saveCodeDraftNow(code.value, language.value)) {
    toast.success("代码已保存到本地");
  }
}

/** 浏览器刷新或关闭前同步写入当前源码，尽量避免最后一段输入丢失。 */
function handleCodeBeforeUnload(): void {
  if (problem.value) saveCodeDraftNow(code.value, language.value, { notifyFailure: false });
}

function loadDraft(): void {
  clearCodeSaveTimer();
  suppressNextCodeAutosave = false;
  let stored: string | null = null;
  let readFailed = false;
  try {
    stored = localStorage.getItem(draftKey());
  } catch {
    readFailed = true;
    markCodeSaveFailure(false);
  }
  const nextCode = stored ?? templates[language.value];
  if (code.value !== nextCode) {
    suppressNextCodeAutosave = true;
    code.value = nextCode;
  }
  if (!readFailed) codeSaveState.value = "saved";
}

/** 读取当前用户的错题本题目标识，用于判断是否需要首次失败提示。 */
async function loadWrongBookState(): Promise<void> {
  if (!session.user) {
    wrongBookProblemIds.value = new Set();
    return;
  }
  try {
    const rows = await api.wrongProblems();
    wrongBookProblemIds.value = new Set(rows.map((row) => row.problem.problemId));
  } catch {
    // 错题本读取失败不应阻塞做题，失败时只是不显示可选提示。
    wrongBookProblemIds.value = new Set();
  }
}

/** 读取当前题目的收藏状态；收藏是题目级数据，与锁定版本无关。 */
async function loadFavoriteState(problemId: string): Promise<void> {
  if (!session.user) {
    isFavorited.value = false;
    return;
  }
  try {
    const rows = await api.favorites();
    if (problem.value?.id === problemId) isFavorited.value = rows.some((row) => row.problemId === problemId);
  } catch {
    isFavorited.value = false;
  }
}

/** 读取当前题目是否存在用户正式通过记录，题目标识跨版本保持稳定。 */
async function loadSolvedState(problemId: string): Promise<void> {
  if (!session.user) {
    isSolved.value = false;
    solvedProblemIds.value = new Set();
    return;
  }
  if (isScopedPracticeContext()) {
    const context = workspaceContext.value;
    const contestId = context.kind === "contest" ? context.contestId : undefined;
    const attemptId = context.kind === "timed-paper" ? context.timedPaperAttemptId : undefined;
    isSolved.value = false;
    solvedProblemIds.value = new Set();
    try {
      const scores = contestId
        ? (await api.contest(contestId)).myScores ?? {}
        : attemptId
          ? (await api.timedAttempt(attemptId)).scores
          : {};
      // 请求期间可能已经切换题目或离开比赛，旧响应不能覆盖新工作区状态。
      const currentContext = workspaceContext.value;
      const sameContext = (currentContext.kind === "contest" ? currentContext.contestId : undefined) === contestId
        && (currentContext.kind === "timed-paper" ? currentContext.timedPaperAttemptId : undefined) === attemptId;
      if (!sameContext || problem.value?.id !== problemId) return;
      const scopedSolvedIds = new Set(Object.entries(scores).filter(([, score]) => score === 100).map(([id]) => id));
      solvedProblemIds.value = scopedSolvedIds;
      isSolved.value = scopedSolvedIds.has(problemId);
    } catch {
      // 比赛或套卷得分读取失败不阻塞做题，只隐藏上下文解决状态。
      isSolved.value = false;
      solvedProblemIds.value = new Set();
    }
    return;
  }
  try {
    const solvedIds = await api.solvedProblemIds();
    solvedProblemIds.value = new Set(solvedIds);
    if (problem.value?.id === problemId) isSolved.value = solvedProblemIds.value.has(problemId);
  } catch {
    isSolved.value = false;
  }
}

/** 收藏当前题目；再次点击同一图标即可取消收藏。 */
async function favoriteProblem(): Promise<void> {
  if (!session.user) {
    await router.push({ path: "/login", query: { redirect: route.fullPath } });
    return;
  }
  const problemId = problem.value?.id ?? workspaceContext.value.problemId;
  if (favoriteLoading.value) return;
  favoriteLoading.value = true;
  try {
    if (isFavorited.value) {
      await api.unfavorite(problemId);
      isFavorited.value = false;
      toast.success("已取消收藏");
    } else {
      await api.favorite(problemId);
      isFavorited.value = true;
      toast.success("已收藏");
    }
  } catch (error) {
    toast.error(error instanceof Error ? error.message : (isFavorited.value ? "取消收藏失败" : "收藏失败"));
  } finally {
    favoriteLoading.value = false;
  }
}

/** 为每道题保存一次“首次失败已询问”标记，避免连续提交反复打扰。 */
function wrongBookPromptKey(problemId: string): string {
  return "gzu-oj.wrong-book-prompted." + problemId;
}

function hasPromptedWrongBook(problemId: string): boolean {
  try { return localStorage.getItem(wrongBookPromptKey(problemId)) === "1"; } catch { return false; }
}

function markWrongBookPrompted(problemId: string): void {
  try { localStorage.setItem(wrongBookPromptKey(problemId), "1"); } catch { /* 本地存储不可用时不影响提交结果。 */ }
}

/** 仅在首次用户可归因的未通过正式提交完成后显示轻量内嵌提示。 */
function maybePromptWrongBook(value: Submission): void {
  if (value.executionMode !== "SUBMIT" || !wrongBookCandidateStatuses.has(value.status)) return;
  if (wrongBookPrompt.value || wrongBookProblemIds.value.has(value.problemId) || hasPromptedWrongBook(value.problemId)) return;
  markWrongBookPrompted(value.problemId);
  wrongBookPromptMessage.value = "";
  wrongBookPrompt.value = { submissionId: value.id, problemId: value.problemId };
}

/** 将提示中的失败提交加入错题本，反馈留在结果面板内而不是弹出消息。 */
async function addSubmissionToWrongBook(): Promise<void> {
  const prompt = wrongBookPrompt.value;
  if (!prompt || wrongBookPromptLoading.value) return;
  wrongBookPromptLoading.value = true;
  wrongBookPromptMessage.value = "";
  try {
    await api.addWrongProblem(prompt.problemId, prompt.submissionId);
    wrongBookProblemIds.value = new Set([...wrongBookProblemIds.value, prompt.problemId]);
    wrongBookPrompt.value = null;
    wrongBookPromptMessage.value = "已加入错题本";
  } catch (error) {
    wrongBookPromptMessage.value = error instanceof Error ? error.message : "加入错题本失败，请稍后重试";
  } finally {
    wrongBookPromptLoading.value = false;
  }
}

/** 关闭内嵌提示；询问标记已保存，不会在下一次失败时再次打扰。 */
function dismissWrongBookPrompt(): void {
  wrongBookPrompt.value = null;
  wrongBookPromptMessage.value = "";
}

/** 切换到当前公开版本，并同步样例、草稿和测试用例标签。 */
function switchToLatestVersion(): void {
  const latest = latestVersion.value;
  if (!latest) return;
  saveCodeDraftNow(code.value, language.value, { notifyFailure: false });
  Object.values(pollTimers).forEach((timer) => window.clearTimeout(timer));
  problem.value = latest;
  latestVersion.value = undefined;
  resumedPreviousVersion.value = false;
  submission.value = undefined;
  runSubmission.value = undefined;
  submitSubmission.value = undefined;
  runInputs.value = latest.samples.length ? latest.samples.map((sample) => sample.input) : [""];
  activeCase.value = 0;
  if (actionCooldownTimer !== undefined) window.clearTimeout(actionCooldownTimer);
  actionCooldownTimer = undefined;
  actionCoolingDown.value = false;
  running.value = false;
  submitting.value = false;
  wrongBookPrompt.value = null;
  wrongBookPromptMessage.value = "";
  saveVersionBookmark(latest.versionId);
  loadDraft();
}

function resetCode(): void {
  code.value = templates[language.value];
  if (saveCodeDraftNow(code.value, language.value, { notifyFailure: false })) {
    toast.success("代码已重置并保存到本地");
  } else {
    toast.warning("代码已重置，但本地保存失败");
  }
}

/** 将桌面端测试结果面板切换为当前标签；移动端使用独立标签状态。 */
function showResultPanel(): void {
  mobileTab.value = "result";
  dockviewApi.value?.getPanel("result")?.api.setActive();
}

/** 将桌面端提交结果面板切换为当前标签。 */
function showSubmitPanel(): void {
  mobileTab.value = "submit";
  dockviewApi.value?.getPanel("submit")?.api.setActive();
}

/** 读取当前锁定题目版本的正式提交历史；列表只显示脱敏后的判题指标。 */
async function loadSubmissionHistory(): Promise<void> {
  const current = problem.value;
  if (!current || !session.user) {
    submissionHistory.value = [];
    submissionHistoryError.value = session.user ? "题目尚未加载" : "登录后查看提交历史";
    submissionHistoryLoading.value = false;
    return;
  }
  submissionHistoryLoading.value = true;
  submissionHistoryError.value = "";
  try {
    const rows = await api.submissions({ limit: 50 });
    if (problem.value?.versionId !== current.versionId) return;
    submissionHistory.value = rows.filter((row) => row.problemId === current.id && row.problemVersionId === current.versionId);
  } catch (error) {
    submissionHistory.value = [];
    submissionHistoryError.value = error instanceof Error ? error.message : "提交历史加载失败";
  } finally {
    if (problem.value?.versionId === current.versionId) submissionHistoryLoading.value = false;
  }
}

/** 从工作区打开提交详情，返回按钮通过浏览器历史回到当前做题页。 */
function openSubmissionDetail(submissionId: string): void {
  void router.push("/submissions/" + submissionId);
}

/** 移动端切换工作区标签；提交历史直接在当前工作区展示。 */
function selectMobileTab(tab: MobileTab): void {
  mobileTab.value = tab;
}

/** 显示提交完成提示；源码和隐藏输入输出仍只留在后端判题记录。 */
function notifyTerminalResult(value: Submission): void {
  if (!terminalStatuses.has(value.status)) return;
  const key = value.id + ":" + value.status + ":" + (value.finishedAt ?? "");
  if (notifiedResultKey.value === key) return;
  notifiedResultKey.value = key;
  const prefix = value.executionMode === "SUBMIT" ? "提交结果" : "运行结果";
  const message = value.executionMode === "SUBMIT" && value.status === "AC"
    ? "提交结果：通过，" + value.score + " 分"
    : prefix + "：" + value.status + (value.executionMode === "SUBMIT" ? "，" + value.score + " 分" : "");
  if (value.status === "AC") toast.success(message);
  else if (value.status === "CANCELED") toast.warning(message || "判题已取消");
  else toast.error(message);
}

/** 判题任务结束或请求失败后短暂冷却，避免连续创建重复任务。 */
function startActionCooldown(): void {
  actionCoolingDown.value = true;
  if (actionCooldownTimer !== undefined) window.clearTimeout(actionCooldownTimer);
  actionCooldownTimer = window.setTimeout(() => {
    actionCoolingDown.value = false;
    actionCooldownTimer = undefined;
  }, ACTION_COOLDOWN_MS);
}

/** 根据任务执行模式释放对应的忙碌锁，并启动统一冷却。 */
function releaseActionLock(mode: Submission["executionMode"]): void {
  if (mode === "RUN") running.value = false;
  else submitting.value = false;
  startActionCooldown();
}

/** 执行公开测试用例。结果在测试结果面板中单独展示。 */
async function runSamples(): Promise<void> {
  if (!session.user) {
    await router.push({ path: "/login", query: { redirect: route.fullPath } });
    return;
  }
  if (!problem.value || running.value || submitting.value || actionCoolingDown.value || (timedAttemptId.value && timedAttemptStatus.value !== "RUNNING")) return;
  if (workspaceContext.value.kind === "contest" && contestRemainingSeconds.value === 0) return;
  running.value = true;
  submission.value = undefined;
  runSubmission.value = undefined;
  showResultPanel();
  try {
    const context = workspaceContext.value;
    const result = await api.run({
      problemId: problem.value.id,
      problemVersionId: problem.value.versionId,
      language: language.value,
      sourceCode: code.value,
      inputs: runInputs.value,
      expectedOutputs: runInputs.value.map((_, index) => problem.value?.samples[index]?.output ?? ""),
      timedPaperAttemptId: timedAttemptId.value,
      contestId: context.kind === "contest" ? context.contestId : undefined,
    });
    submission.value = result;
    runSubmission.value = result;
    schedulePoll(result);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "运行失败");
    releaseActionLock("RUN");
  }
}

/** 提交全部隐藏测试点，并在判题终态无论是否通过都提醒用户。 */
async function submit(): Promise<void> {
  if (!session.user) {
    await router.push({ path: "/login", query: { redirect: route.fullPath } });
    return;
  }
  if (!problem.value || running.value || submitting.value || actionCoolingDown.value || (timedAttemptId.value && timedAttemptStatus.value !== "RUNNING")) return;
  if (workspaceContext.value.kind === "contest" && contestRemainingSeconds.value === 0) return;
  submitting.value = true;
  submission.value = undefined;
  submitSubmission.value = undefined;
  showSubmitPanel();
  try {
    const result = await api.submit({
      problemId: problem.value.id,
      problemVersionId: problem.value.versionId,
      language: language.value,
      sourceCode: code.value,
      contestId: workspaceContext.value.kind === "contest" ? workspaceContext.value.contestId : undefined,
      timedPaperAttemptId: timedAttemptId.value,
    });
    submission.value = result;
    submitSubmission.value = result;
    schedulePoll(result);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "提交失败");
    releaseActionLock("SUBMIT");
  }
}

/** 轮询持久化判题队列，并在终态触发一次结果提醒。 */
function schedulePoll(current: Submission): void {
  notifyTerminalResult(current);
  if (current.executionMode === "SUBMIT" && current.status === "AC") {
    if (isScopedPracticeContext()) {
      const currentProblemId = problem.value?.id;
      if (currentProblemId) solvedProblemIds.value = new Set(solvedProblemIds.value).add(currentProblemId);
      isSolved.value = true;
    } else {
      isSolved.value = true;
    }
  }
  if (terminalStatuses.has(current.status)) {
    maybePromptWrongBook(current);
    if (current.executionMode === "SUBMIT") void loadSubmissionHistory();
    releaseActionLock(current.executionMode);
    return;
  }
  const mode = current.executionMode;
  window.clearTimeout(pollTimers[mode]);
  pollTimers[mode] = window.setTimeout(async () => {
    try {
      const refreshed = await api.submission(current.id);
      if (mode === "RUN") runSubmission.value = refreshed;
      else submitSubmission.value = refreshed;
      if (submission.value?.id === refreshed.id) submission.value = refreshed;
      if (mode === "SUBMIT" && terminalStatuses.has(refreshed.status)) void loadSubmissionHistory();
      schedulePoll(refreshed);
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 404)) console.error(error);
      pollTimers[mode] = window.setTimeout(() => schedulePoll(current), 3000);
    }
  }, 1500);
}

/** 加载题面、恢复本地版本书签和编辑器草稿。 */
async function loadProblem(): Promise<void> {
  loading.value = true;
  loadingError.value = "";
  // 路由复用时先清理上一题的运行态，避免收藏、版本提示和判题结果串题。
  latestVersion.value = undefined;
  resumedPreviousVersion.value = false;
  isSolved.value = false;
  solvedProblemIds.value = new Set();
  isFavorited.value = false;
  wrongBookPrompt.value = null;
  wrongBookPromptMessage.value = "";
  try {
    const context = workspaceContext.value;
    if (context.kind === "contest") {
      const contest = await api.contest(context.contestId);
      contestEndAt.value = Date.parse(contest.endsAt);
      const now = Date.now();
      if (!contest.joined) throw new Error("无权限访问该训练赛");
      if (now < Date.parse(contest.startsAt)) {
        toast.warning("训练赛尚未开始");
        await router.replace("/problems");
        return;
      }
      if (now >= Date.parse(contest.endsAt)) {
        toast.warning("训练赛已经结束");
        await router.replace("/problems");
        return;
      }
      const locked = contest.problems.find((item) => item.problemId === context.problemId);
      if (!locked || !context.versionId || locked.versionId !== context.versionId) throw new Error("题目不属于该比赛或版本不匹配");
      const detail = await api.problemVersion(context.versionId);
      if (detail.id !== context.problemId) throw new Error("题目版本与当前题目不匹配");
      problem.value = detail;
    } else if (context.kind === "timed-paper") {
      const attempt = await api.timedAttempt(context.timedPaperAttemptId);
      replaceTimedAttempt(attempt);
      const locked = attempt.paper.problems.find((item) => item.problemId === context.problemId);
      if (!locked || !context.versionId || locked.versionId !== context.versionId) throw new Error("题目不属于该套卷或版本不匹配");
      const detail = await api.problemVersion(context.versionId);
      if (detail.id !== context.problemId) throw new Error("题目版本与当前题目不匹配");
      problem.value = detail;
    } else if (context.versionId) {
      const locked = await api.problemVersion(context.versionId);
      if (locked.id !== context.problemId) throw new Error("题目版本与当前题目不匹配");
      problem.value = locked;
    } else {
      problem.value = await api.problem(context.problemId);
    }
    saveVersionBookmark(problem.value.versionId);
    runInputs.value = problem.value.samples.length ? problem.value.samples.map((sample) => sample.input) : [""];
    activeCase.value = 0;
    loadDraft();
    await loadFavoriteState(problem.value.id);
    await loadSolvedState(problem.value.id);
    await loadWrongBookState();
    await loadNavigation();
  } catch (error) {
    loadingError.value = error instanceof Error ? error.message : "题目加载失败";
    toast.error(loadingError.value);
    if (workspaceContext.value.kind !== "problem") await router.replace("/problems");
  } finally {
    loading.value = false;
  }
}

/** 初始化六个可拖动停靠面板；共享上下文由当前页面生命周期持有。 */
function onDockReady(event: DockviewReadyEvent): void {
  dockLayoutError.value = "";
  dockviewApi.value = event.api;
  dockLayoutSubscription?.dispose();
  dockLayoutSubscription = event.api.onDidLayoutChange(scheduleDockLayoutSave);
  try {
    const restored = restoreDockLayout(event.api);
    if (!restored) {
      event.api.addPanel({ id: "statement", component: "workspacePanel", title: "题目描述", params: { kind: "statement", context: dockContext }, initialWidth: 560 });
      event.api.addPanel({ id: "code", component: "workspacePanel", title: "代码", params: { kind: "code", context: dockContext }, position: { referencePanel: "statement", direction: "right" }, initialWidth: 720 });
      event.api.addPanel({ id: "cases", component: "workspacePanel", title: "测试用例", params: { kind: "cases", context: dockContext }, position: { referencePanel: "code", direction: "below" }, initialHeight: 280 });
      event.api.addPanel({ id: "result", component: "workspacePanel", title: "测试结果", params: { kind: "result", context: dockContext }, position: { referencePanel: "cases", direction: "within" }, inactive: true });
      event.api.addPanel({ id: "submit", component: "workspacePanel", title: "提交结果", params: { kind: "submit", context: dockContext }, position: { referencePanel: "result", direction: "within" }, inactive: true });
      event.api.addPanel({ id: "history", component: "workspacePanel", title: "提交历史", params: { kind: "history", context: dockContext }, position: { referencePanel: "statement", direction: "within" }, inactive: true });
    }
    void nextTick(() => window.requestAnimationFrame(animateDockGroups));
  } catch (error) {
    dockLayoutError.value = error instanceof Error ? error.message : "工作区布局初始化失败";
    toast.error("工作区布局初始化失败");
  }
}

/** 请求浏览器进入全屏工作区。 */
function requestFullscreen(): void {
  void document.documentElement.requestFullscreen?.();
}

watchEffect(() => {
  dockContext.problem = problem.value;
  dockContext.renderedStatement = renderedStatement.value;
  dockContext.activeLimit = activeLimit.value;
  dockContext.language = language.value;
  dockContext.code = code.value;
  dockContext.fontSize = fontSize.value;
  dockContext.dark = dark.value;
  dockContext.settingsOpen = settingsOpen.value;
  dockContext.runInputs = runInputs.value;
  dockContext.activeCase = activeCase.value;
  dockContext.runSubmission = runSubmission.value;
  dockContext.submitSubmission = submitSubmission.value;
  dockContext.running = running.value;
  dockContext.submitting = submitting.value;
  dockContext.isSolved = isSolved.value;
  dockContext.isFavorited = isFavorited.value;
  dockContext.favoriteLoading = favoriteLoading.value;
  dockContext.wrongBookPrompt = wrongBookPrompt.value;
  dockContext.wrongBookPromptLoading = wrongBookPromptLoading.value;
  dockContext.wrongBookPromptMessage = wrongBookPromptMessage.value;
  dockContext.submissionHistory = submissionHistory.value;
  dockContext.submissionHistoryLoading = submissionHistoryLoading.value;
  dockContext.submissionHistoryError = submissionHistoryError.value;
  dockContext.codeSaveState = codeSaveState.value;
  workspaceToolbar.active = true;
  workspaceToolbar.ready = Boolean(problem.value) && !loading.value && !loadingError.value;
  workspaceToolbar.running = running.value;
  workspaceToolbar.submitting = submitting.value;
  workspaceToolbar.coolingDown = actionCoolingDown.value;
  workspaceToolbar.timedAttemptStatus = timedAttemptStatus.value;
  workspaceToolbar.timedAttemptRemainingSeconds = timedAttemptRemainingSeconds.value;
  workspaceToolbar.timedAttemptActionLoading = timedAttemptActionLoading.value;
  workspaceToolbar.contestRemainingSeconds = contestRemainingSeconds.value;
  workspaceToolbar.run = runSamples;
  workspaceToolbar.submit = submit;
  workspaceToolbar.pauseTimedAttempt = () => changeTimedAttemptState("pause");
  workspaceToolbar.resumeTimedAttempt = () => changeTimedAttemptState("resume");
  workspaceToolbar.finishTimedAttempt = requestFinishTimedAttempt;
  workspaceToolbar.back = () => { void router.back(); };
  workspaceToolbar.toggleProblemList = toggleProblemList;
  workspaceToolbar.problemListOpen = problemListOpen.value;
  workspaceToolbar.previousProblem = () => moveToProblem(-1);
  workspaceToolbar.nextProblem = () => moveToProblem(1);
  workspaceToolbar.canPreviousProblem = canPreviousProblem.value && !navigationLocked.value;
  workspaceToolbar.canNextProblem = canNextProblem.value && !navigationLocked.value;
});

watch(code, (value) => {
  if (suppressNextCodeAutosave) {
    suppressNextCodeAutosave = false;
    return;
  }
  scheduleCodeDraftSave(value);
}, { flush: "sync" });
watch(language, (next, previous) => {
  localStorage.setItem("gzu-oj.language", next);
  if (previous) saveCodeDraftNow(code.value, previous, { notifyFailure: false });
  nextTick(loadDraft);
});
watch(fontSize, (value) => localStorage.setItem("gzu-oj.font-size", String(value)));
watch(() => [session.user?.id, problem.value?.versionId], () => {
  void loadSubmissionHistory();
  void loadWrongBookState();
  if (problem.value) {
    void loadFavoriteState(problem.value.id);
    void loadSolvedState(problem.value.id);
  }
});
watch(() => [
  workspaceContext.value.kind,
  workspaceContext.value.problemId,
  workspaceContext.value.versionId ?? "",
  workspaceContext.value.kind === "contest" ? workspaceContext.value.contestId : "",
  workspaceContext.value.kind === "timed-paper" ? workspaceContext.value.timedPaperAttemptId : "",
], (next, previous) => {
  if (previous && next.join("/") !== previous.join("/")) void loadProblem();
});
function syncTheme(event: Event): void {
  dark.value = Boolean((event as CustomEvent<{ dark: boolean }>).detail.dark);
}

onMounted(() => {
  window.addEventListener("gzu-oj-theme-change", syncTheme);
  window.addEventListener("keydown", handleCodeSaveShortcut, true);
  window.addEventListener("beforeunload", handleCodeBeforeUnload);
  window.addEventListener("keydown", handleProblemListKeydown);
  timedAttemptTicker = window.setInterval(() => { timedAttemptNow.value = Date.now(); }, 1_000);
  timedAttemptPoller = window.setInterval(() => { void refreshTimedAttempt(); }, 5_000);
  document.addEventListener("visibilitychange", refreshTimedAttempt);
  void loadProblem();
});
onBeforeUnmount(() => {
  Object.values(pollTimers).forEach((timer) => window.clearTimeout(timer));
  if (actionCooldownTimer !== undefined) window.clearTimeout(actionCooldownTimer);
  actionCooldownTimer = undefined;
  if (dockLayoutSaveTimer !== undefined) window.clearTimeout(dockLayoutSaveTimer);
  if (timedAttemptTicker !== undefined) window.clearInterval(timedAttemptTicker);
  if (timedAttemptPoller !== undefined) window.clearInterval(timedAttemptPoller);
  if (problem.value) saveCodeDraftNow(code.value, language.value, { notifyFailure: false });
  saveDockLayout();
  dockLayoutSubscription?.dispose();
  window.removeEventListener("gzu-oj-theme-change", syncTheme);
  window.removeEventListener("keydown", handleCodeSaveShortcut, true);
  window.removeEventListener("beforeunload", handleCodeBeforeUnload);
  window.removeEventListener("keydown", handleProblemListKeydown);
  document.removeEventListener("visibilitychange", refreshTimedAttempt);
  resetWorkspaceToolbar();
});
</script>

<template>
  <section class="workspace-page workspace-page--dock loading-shell" :class="{ 'workspace-page--versioned': resumedPreviousVersion && latestVersion }" :aria-busy="loading">
    <div v-if="resumedPreviousVersion && latestVersion" class="version-notice">
      正在继续你上次打开的版本 v{{ problem?.versionNumber }}；当前最新版本为 v{{ latestVersion.versionNumber }}。
      <button type="button" @click="switchToLatestVersion">开始最新版本</button>
    </div>

    <div v-if="loading" class="loading-overlay workspace-loading-overlay">
      <div class="workspace-loading-indicator" role="status" aria-label="题目加载中">
        <span class="loading-spinner" aria-hidden="true" />
        <span class="loading-dots" aria-hidden="true"><i /><i /><i /></span>
      </div>
    </div>
    <div v-else-if="loadingError" class="workspace-load-error">
      <strong>题目加载失败</strong>
      <p>{{ loadingError }}</p>
      <UiButton size="sm" @click="loadProblem">重新加载</UiButton>
    </div>

    <div v-if="problem && !loadingError" class="workspace-desktop-layout">
      <DockviewVue
        v-if="!dockLayoutError"
        class="workspace-dockview"
        :components="dockComponents"
        :theme="dockTheme"
        :get-tab-context-menu-items="suppressDockTabMenu"
        @keydown.capture="preventDockTabClose"
        @ready="onDockReady"
      />
      <div v-else class="workspace-load-error">
        <strong>工作区布局加载失败</strong>
        <p>{{ dockLayoutError }}</p>
        <UiButton size="sm" @click="dockLayoutError = ''">重新加载布局</UiButton>
      </div>
    </div>

    <div v-if="problem && !loadingError" class="workspace-mobile-layout">
      <div class="mobile-workspace-tabs" role="tablist" aria-label="做题工作区面板">
        <button v-for="tab in (['statement', 'code', 'cases', 'result', 'submit', 'history'] as MobileTab[])" :key="tab" type="button" role="tab" :aria-selected="mobileTab === tab" :class="{ active: mobileTab === tab }" @click="selectMobileTab(tab)">
          {{ tab === "statement" ? "题目描述" : tab === "code" ? "代码" : tab === "cases" ? "测试用例" : tab === "result" ? "测试结果" : tab === "submit" ? "提交结果" : "提交历史" }}
        </button>
      </div>
      <WorkspaceDockPanel :kind="mobileTab" :context="dockContext" />
    </div>

    <Transition name="workspace-problem-drawer-fade">
      <div v-if="problemListOpen" class="workspace-problem-drawer-layer" @click.self="closeProblemList">
        <aside class="workspace-problem-drawer" aria-label="题目列表" aria-modal="true" role="dialog">
          <header class="workspace-problem-drawer-header">
            <div>
              <strong>{{ navigationTitle }}</strong>
              <span>{{ navigationIndex >= 0 ? `${navigationIndex + 1} / ${navigationItems.length}` : `${navigationItems.length} 道题` }}</span>
            </div>
            <button class="icon-button" type="button" title="关闭题目列表" aria-label="关闭题目列表" @click="closeProblemList"><X :size="18" /></button>
          </header>
          <div v-if="navigationLoading" class="workspace-problem-drawer-state" role="status">正在加载题目…</div>
          <div v-else-if="navigationItems.length" class="workspace-problem-drawer-list">
            <button v-for="(item, index) in navigationItems" :key="item.problemId + ':' + item.versionId" type="button" class="workspace-problem-drawer-item" :class="{ active: index === navigationIndex }" :disabled="navigationLocked" @click="openNavigationProblem(item)">
              <span class="workspace-problem-drawer-status" :class="{ solved: solvedProblemIds.has(item.problemId) }">
                <CheckCircle2 v-if="solvedProblemIds.has(item.problemId)" :size="16" aria-label="已解决" />
                <Circle v-else :size="16" aria-hidden="true" />
              </span>
              <span class="workspace-problem-drawer-copy">
                <strong><small v-if="item.ordinal !== undefined">{{ formatProblemOrdinal(item.ordinal) }}.</small>{{ item.title }}</strong>
                <span>{{ [item.school, item.year, difficultyText(item.difficulty)].filter(Boolean).join(" · ") || "题目" }}</span>
              </span>
              <span class="workspace-problem-drawer-index">{{ item.ordinal === undefined ? index + 1 : formatProblemOrdinal(item.ordinal) }}</span>
            </button>
          </div>
          <div v-else class="workspace-problem-drawer-state">暂无可导航题目</div>
        </aside>
      </div>
    </Transition>
    <UiDialog
      v-model="finishConfirmOpen"
      title="提前结束作答"
      description="提前结束后不能继续运行或提交，且无法恢复。"
      class="workspace-finish-dialog"
    >
      <p>确定结束本次个人计时作答吗？</p>
      <template #footer>
        <UiButton variant="outline" :disabled="timedAttemptActionLoading" @click="finishConfirmOpen = false">取消</UiButton>
        <UiButton variant="destructive" :loading="timedAttemptActionLoading" @click="finishTimedAttempt">确认结束</UiButton>
      </template>
    </UiDialog>
  </section>
</template>
