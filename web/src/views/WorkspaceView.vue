<script setup lang="ts">
import DOMPurify from "dompurify";
import { marked } from "marked";
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch, watchEffect } from "vue";
import { useRoute, useRouter } from "vue-router";
import { DockviewVue, themeDark, themeLight } from "dockview-vue";
import type { DockviewApi, DockviewReadyEvent, VueComponent } from "dockview-vue";
import "dockview-vue/dist/styles/dockview.css";
import { toast } from "../lib/notify";
import { api, ApiError } from "../api/client";
import type { JudgeLanguage, JudgeStatus, ProblemDetail, Submission } from "../api/types";
import { session } from "../stores/session";
import { resetWorkspaceToolbar, workspaceToolbar } from "../stores/workspaceToolbar";
import WorkspaceDockPanel from "../components/WorkspaceDockPanel.vue";
import WorkspaceDockPanelAdapter from "../components/WorkspaceDockPanelAdapter.vue";
import type { CodeSaveState, WorkspacePanelContext, WorkspacePanelKind } from "./workspacePanel";
import UiButton from "../components/ui/Button.vue";

/** 移动端的六个工作区标签。桌面端由 Dockview 管理同名面板。 */
type MobileTab = WorkspacePanelKind;

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
let dockLayoutSubscription: { dispose: () => void } | undefined;
let dockLayoutSaveTimer: number | undefined;
let actionCooldownTimer: number | undefined;
let codeSaveTimer: number | undefined;
let suppressNextCodeAutosave = false;
let codeSaveErrorNotified = false;
const ACTION_COOLDOWN_MS = 1000;
/** 用户停止输入后的源码自动保存等待时间。 */
const CODE_AUTOSAVE_DELAY_MS = 700;

const terminalStatuses = new Set<JudgeStatus>(["AC", "PARTIAL", "WA", "CE", "TLE", "MLE", "RE", "OLE", "SYSTEM_ERROR", "CANCELED"]);
const renderedStatement = computed(() => {
  if (!problem.value) return "";
  return DOMPurify.sanitize(marked.parse(problem.value.statementMarkdown, { async: false }) as string);
});
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
  fullscreen: () => requestFullscreen(),
});

/** 当前语言对应的本地草稿键。 */
function draftKey(selected = language.value): string {
  const version = problem.value?.versionId ?? (typeof route.query.versionId === "string" ? route.query.versionId : route.params.id);
  return "gzu-oj.draft." + version + "." + selected;
}

/** 当前题目的版本书签键。 */
function versionBookmarkKey(): string {
  return "gzu-oj.problem-version." + String(route.params.id);
}

function saveVersionBookmark(versionId: string): void {
  localStorage.setItem(versionBookmarkKey(), JSON.stringify({ versionId, lastOpenedAt: new Date().toISOString() }));
}

function bookmarkedVersionId(): string | null {
  try {
    const value = JSON.parse(localStorage.getItem(versionBookmarkKey()) ?? "null") as { versionId?: string } | null;
    return value?.versionId ?? null;
  } catch {
    return null;
  }
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

/** 收藏当前题目；未登录时进入登录卡片。 */
async function favoriteProblem(): Promise<void> {
  if (!session.user) {
    await router.push({ path: "/login", query: { redirect: route.fullPath } });
    return;
  }
  try {
    await api.favorite(problem.value?.id ?? String(route.params.id));
    toast.success("已收藏");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "收藏失败");
  }
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

/** 从工作区打开提交详情，并保留当前题目版本作为返回列表筛选条件。 */
function openSubmissionDetail(submissionId: string): void {
  const current = problem.value;
  void router.push({
    path: "/submissions/" + submissionId,
    query: current ? { problemId: current.id, versionId: current.versionId } : undefined,
  });
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
  if (!problem.value || running.value || submitting.value || actionCoolingDown.value) return;
  running.value = true;
  submission.value = undefined;
  runSubmission.value = undefined;
  showResultPanel();
  try {
    const result = await api.run({
      problemId: problem.value.id,
      problemVersionId: problem.value.versionId,
      language: language.value,
      sourceCode: code.value,
      inputs: runInputs.value,
      expectedOutputs: runInputs.value.map((_, index) => problem.value?.samples[index]?.output ?? ""),
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
  if (!problem.value || running.value || submitting.value || actionCoolingDown.value) return;
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
      contestId: typeof route.query.contestId === "string" ? route.query.contestId : undefined,
      timedPaperAttemptId: typeof route.query.timedPaperAttemptId === "string" ? route.query.timedPaperAttemptId : undefined,
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
  if (terminalStatuses.has(current.status)) {
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
  try {
    if (typeof route.query.versionId === "string") {
      const locked = await api.problemVersion(route.query.versionId);
      if (locked.id !== String(route.params.id)) throw new Error("题目版本与当前题目不匹配");
      problem.value = locked;
    } else {
      const current = await api.problem(String(route.params.id));
      const bookmarked = bookmarkedVersionId();
      if (bookmarked && bookmarked !== current.versionId) {
        try {
          const previous = await api.problemVersion(bookmarked);
          if (previous.id !== current.id) throw new Error("本地保存的版本不属于当前题目");
          problem.value = previous;
          latestVersion.value = current;
          resumedPreviousVersion.value = true;
        } catch {
          problem.value = current;
          saveVersionBookmark(current.versionId);
        }
      } else {
        problem.value = current;
      }
    }
    saveVersionBookmark(problem.value.versionId);
    runInputs.value = problem.value.samples.length ? problem.value.samples.map((sample) => sample.input) : [""];
    activeCase.value = 0;
    loadDraft();
  } catch (error) {
    loadingError.value = error instanceof Error ? error.message : "题目加载失败";
    toast.error(loadingError.value);
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
  dockContext.submissionHistory = submissionHistory.value;
  dockContext.submissionHistoryLoading = submissionHistoryLoading.value;
  dockContext.submissionHistoryError = submissionHistoryError.value;
  dockContext.codeSaveState = codeSaveState.value;
  workspaceToolbar.active = true;
  workspaceToolbar.ready = Boolean(problem.value) && !loading.value && !loadingError.value;
  workspaceToolbar.running = running.value;
  workspaceToolbar.submitting = submitting.value;
  workspaceToolbar.coolingDown = actionCoolingDown.value;
  workspaceToolbar.run = runSamples;
  workspaceToolbar.submit = submit;
  workspaceToolbar.back = () => { void router.push("/problems"); };
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
});
function syncTheme(event: Event): void {
  dark.value = Boolean((event as CustomEvent<{ dark: boolean }>).detail.dark);
}

onMounted(() => {
  window.addEventListener("gzu-oj-theme-change", syncTheme);
  window.addEventListener("keydown", handleCodeSaveShortcut, true);
  window.addEventListener("beforeunload", handleCodeBeforeUnload);
  void loadProblem();
});
onBeforeUnmount(() => {
  Object.values(pollTimers).forEach((timer) => window.clearTimeout(timer));
  if (actionCooldownTimer !== undefined) window.clearTimeout(actionCooldownTimer);
  actionCooldownTimer = undefined;
  if (dockLayoutSaveTimer !== undefined) window.clearTimeout(dockLayoutSaveTimer);
  if (problem.value) saveCodeDraftNow(code.value, language.value, { notifyFailure: false });
  saveDockLayout();
  dockLayoutSubscription?.dispose();
  window.removeEventListener("gzu-oj-theme-change", syncTheme);
  window.removeEventListener("keydown", handleCodeSaveShortcut, true);
  window.removeEventListener("beforeunload", handleCodeBeforeUnload);
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
  </section>
</template>
