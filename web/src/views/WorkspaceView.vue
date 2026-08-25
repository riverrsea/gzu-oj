<script setup lang="ts">
import DOMPurify from "dompurify";
import { marked } from "marked";
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ChevronDown, ChevronUp, Heart, Play, RotateCcw, Send, Settings2 } from "@lucide/vue";
import { toast } from "../lib/notify";
import { api, ApiError } from "../api/client";
import type { JudgeLanguage, JudgeStatus, ProblemDetail, Submission } from "../api/types";
import { session } from "../stores/session";
import CodeEditor from "../components/CodeEditor.vue";
import UiButton from "../components/ui/Button.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";
import UiNumberField from "../components/ui/NumberField.vue";

type MobileTab = "problem" | "code" | "result";
type DrawerTab = "cases" | "result";

const route = useRoute();
const router = useRouter();
const problem = ref<ProblemDetail>();
const latestVersion = ref<ProblemDetail>();
const resumedPreviousVersion = ref(false);
const loading = ref(true);
const language = ref<JudgeLanguage>((localStorage.getItem("gzu-oj.language") as JudgeLanguage | null) ?? "CPP17");
const code = ref("");
const fontSize = ref(Number(localStorage.getItem("gzu-oj.font-size") ?? 14));
const split = ref(Number(localStorage.getItem("gzu-oj.workspace-split") ?? 45));
const drawerHeight = ref(Number(localStorage.getItem("gzu-oj.drawer-height") ?? 34));
const drawerOpen = ref(false);
const drawerTab = ref<DrawerTab>("cases");
const mobileTab = ref<MobileTab>("problem");
const submitting = ref(false);
const running = ref(false);
const submission = ref<Submission>();
const runInputs = ref<string[]>([]);
const dark = ref(document.documentElement.dataset.theme === "dark");
const settingsOpen = ref(false);
const workspace = ref<HTMLElement>();
const editorPanel = ref<HTMLElement>();
let pollTimer: number | undefined;

const terminalStatuses = new Set<JudgeStatus>(["AC", "PARTIAL", "WA", "CE", "TLE", "MLE", "RE", "OLE", "SYSTEM_ERROR", "CANCELED"]);
const renderedStatement = computed(() => {
  if (!problem.value) return "";
  return DOMPurify.sanitize(marked.parse(problem.value.statementMarkdown, { async: false }) as string);
});
const activeLimit = computed(() => problem.value?.languageLimits.find((item) => item.language === language.value));
const statusLabel = computed(() => submission.value?.status ?? "READY");

const templates: Record<JudgeLanguage, string> = {
  C17: "#include <stdio.h>\n\nint main(void) {\n    return 0;\n}\n",
  CPP17: "#include <bits/stdc++.h>\nusing namespace std;\n\nint main() {\n    ios::sync_with_stdio(false);\n    cin.tie(nullptr);\n\n    return 0;\n}\n",
  JAVA21: "import java.io.*;\nimport java.util.*;\n\npublic class Main {\n    public static void main(String[] args) throws Exception {\n    }\n}\n",
  PYTHON3: "import sys\n\ndef main() -> None:\n    pass\n\nif __name__ == \"__main__\":\n    main()\n",
};

function draftKey(selected = language.value): string {
  const version = problem.value?.versionId ?? (typeof route.query.versionId === "string" ? route.query.versionId : route.params.id);
  return "gzu-oj.draft." + version + "." + selected;
}

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

function loadDraft(): void {
  code.value = localStorage.getItem(draftKey()) ?? templates[language.value];
}

/** 切换到当前公开版本，并同步该版本的样例、草稿和判题状态。 */
function switchToLatestVersion(): void {
  const latest = latestVersion.value;
  if (!latest) return;
  window.clearTimeout(pollTimer);
  problem.value = latest;
  latestVersion.value = undefined;
  resumedPreviousVersion.value = false;
  submission.value = undefined;
  runInputs.value = latest.samples.length ? latest.samples.map((sample) => sample.input) : [""];
  saveVersionBookmark(latest.versionId);
  loadDraft();
}

function resetCode(): void {
  code.value = templates[language.value];
  localStorage.setItem(draftKey(), code.value);
  toast.success("代码已重置");
}

function startHorizontalResize(event: PointerEvent): void {
  const bounds = workspace.value?.getBoundingClientRect();
  if (!bounds) return;
  const move = (current: PointerEvent) => {
    split.value = Math.min(70, Math.max(30, ((current.clientX - bounds.left) / bounds.width) * 100));
  };
  const stop = () => {
    localStorage.setItem("gzu-oj.workspace-split", String(split.value));
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", stop);
  };
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", stop);
  event.preventDefault();
}

function startDrawerResize(event: PointerEvent): void {
  const bounds = editorPanel.value?.getBoundingClientRect();
  if (!bounds) return;
  const move = (current: PointerEvent) => {
    drawerHeight.value = Math.min(70, Math.max(20, ((bounds.bottom - current.clientY) / bounds.height) * 100));
  };
  const stop = () => {
    localStorage.setItem("gzu-oj.drawer-height", String(drawerHeight.value));
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerup", stop);
  };
  window.addEventListener("pointermove", move);
  window.addEventListener("pointerup", stop);
  event.preventDefault();
}

function showCases(): void {
  drawerOpen.value = true;
  drawerTab.value = "cases";
  mobileTab.value = "result";
}

async function runSamples(): Promise<void> {
  if (!session.user) {
    await router.push({ path: "/login", query: { redirect: route.fullPath } });
    return;
  }
  if (!problem.value || running.value) return;
  running.value = true;
  drawerOpen.value = true;
  drawerTab.value = "result";
  mobileTab.value = "result";
  try {
    submission.value = await api.run({
      problemId: problem.value.id,
      problemVersionId: problem.value.versionId,
      language: language.value,
      sourceCode: code.value,
      inputs: runInputs.value,
    });
    schedulePoll();
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "运行失败");
  } finally {
    running.value = false;
  }
}

async function submit(): Promise<void> {
  if (!session.user) {
    await router.push({ path: "/login", query: { redirect: route.fullPath } });
    return;
  }
  if (!problem.value || submitting.value) return;
  submitting.value = true;
  drawerOpen.value = true;
  drawerTab.value = "result";
  mobileTab.value = "result";
  try {
    submission.value = await api.submit({
      problemId: problem.value.id,
      problemVersionId: problem.value.versionId,
      language: language.value,
      sourceCode: code.value,
      contestId: typeof route.query.contestId === "string" ? route.query.contestId : undefined,
      timedPaperAttemptId: typeof route.query.timedPaperAttemptId === "string" ? route.query.timedPaperAttemptId : undefined,
    });
    schedulePoll();
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "提交失败");
  } finally {
    submitting.value = false;
  }
}

function schedulePoll(): void {
  if (!submission.value || terminalStatuses.has(submission.value.status)) return;
  window.clearTimeout(pollTimer);
  pollTimer = window.setTimeout(async () => {
    if (!submission.value) return;
    try {
      submission.value = await api.submission(submission.value.id);
      schedulePoll();
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 404)) console.error(error);
      pollTimer = window.setTimeout(schedulePoll, 3000);
    }
  }, 1500);
}

async function loadProblem(): Promise<void> {
  loading.value = true;
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
    runInputs.value = problem.value.samples.length
      ? problem.value.samples.map((sample) => sample.input)
      : [""];
    loadDraft();
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "题目加载失败");
    await router.push("/problems");
  } finally {
    loading.value = false;
  }
}

watch(code, (value) => localStorage.setItem(draftKey(), value));
watch(language, (next, previous) => {
  localStorage.setItem("gzu-oj.language", next);
  if (previous) localStorage.setItem(draftKey(previous), code.value);
  nextTick(loadDraft);
});
watch(fontSize, (value) => localStorage.setItem("gzu-oj.font-size", String(value)));
function syncTheme(event: Event): void {
  dark.value = Boolean((event as CustomEvent<{ dark: boolean }>).detail.dark);
}

onMounted(() => {
  window.addEventListener("gzu-oj-theme-change", syncTheme);
  void loadProblem();
});
onBeforeUnmount(() => {
  window.clearTimeout(pollTimer);
  window.removeEventListener("gzu-oj-theme-change", syncTheme);
});
</script>

<template>
  <section class="workspace-page workspace-page--leetrank loading-shell" :aria-busy="loading">
    <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>
    <div class="mobile-workspace-tabs" role="tablist">
      <button :class="{ active: mobileTab === 'problem' }" @click="mobileTab = 'problem'">题目</button>
      <button :class="{ active: mobileTab === 'code' }" @click="mobileTab = 'code'">代码</button>
      <button :class="{ active: mobileTab === 'result' }" @click="mobileTab = 'result'">结果</button>
    </div>
    <div v-if="problem" ref="workspace" class="workspace-grid" :style="{ '--problem-width': split + '%' }">
      <div v-if="resumedPreviousVersion && latestVersion" class="version-notice">
        正在继续你上次打开的版本 v{{ problem.versionNumber }}；当前最新版本为 v{{ latestVersion.versionNumber }}。
        <button type="button" @click="switchToLatestVersion">开始最新版本</button>
      </div>
      <article class="statement-panel statement-panel--card" :class="{ 'mobile-hidden': mobileTab !== 'problem' }">
        <header class="statement-header statement-header--card">
          <div><span class="source-key">{{ problem.externalKey || '手工题目' }}</span><h1>{{ problem.title }}</h1><p>{{ problem.school }} · {{ problem.year }} · 版本 {{ problem.versionNumber }}</p></div>
          <button class="icon-button" type="button" title="收藏题目" @click="session.user ? api.favorite(problem.id).then(() => toast.success('已收藏')) : router.push('/login')"><Heart :size="19" /></button>
        </header>
        <div class="problem-meta"><span v-for="tag in problem.tags" :key="tag" class="plain-tag">{{ tag }}</span><span v-if="problem.dataNotice" class="data-notice">{{ problem.dataNotice }}</span></div>
        <div class="markdown-body" v-html="renderedStatement" />
        <section v-if="problem.samples.length" class="samples"><h2>公开样例</h2><div v-for="sample in problem.samples" :key="sample.ordinal" class="sample-block"><strong>样例 {{ sample.ordinal }}</strong><div class="sample-columns"><div><span>输入</span><pre>{{ sample.input }}</pre></div><div><span>输出</span><pre>{{ sample.output }}</pre></div></div></div></section>
      </article>
      <div class="split-handle" role="separator" aria-orientation="vertical" title="拖动调整题面宽度" @pointerdown="startHorizontalResize" />
      <section ref="editorPanel" class="editor-panel editor-panel--card" :class="{ 'mobile-hidden': mobileTab === 'problem' || mobileTab === 'result' }">
        <header class="editor-toolbar editor-toolbar--card">
          <div class="toolbar-group"><select v-model="language" class="compact-select" aria-label="编程语言"><option value="C17">GNU C17</option><option value="CPP17">GNU C++17</option><option value="JAVA21">OpenJDK 21</option><option value="PYTHON3">CPython 3</option></select><span v-if="activeLimit" class="limit-text">{{ activeLimit.timeLimitMs }} ms · {{ activeLimit.memoryLimitMiB }} MiB</span></div>
          <div class="toolbar-group"><div class="editor-settings"><button class="icon-button" type="button" title="编辑器设置" :aria-expanded="settingsOpen" @click="settingsOpen = !settingsOpen"><Settings2 :size="18" /></button><div v-if="settingsOpen" class="editor-settings-popover"><label class="font-setting">字号 <UiNumberField v-model="fontSize" :min="12" :max="22" /></label></div></div><button class="icon-button" type="button" title="重置代码" @click="resetCode"><RotateCcw :size="18" /></button></div>
        </header>
        <div class="editor-stage" :style="{ '--drawer-height': drawerOpen ? drawerHeight + '%' : '0%' }">
          <div class="editor-host"><CodeEditor v-model="code" :language="language" :font-size="fontSize" :dark="dark" /></div>
          <section v-if="drawerOpen" class="result-drawer result-drawer--card">
            <div class="drawer-resize" title="拖动调整结果区高度" @pointerdown="startDrawerResize" />
            <header class="drawer-tabs"><button :class="{ active: drawerTab === 'cases' }" @click="drawerTab = 'cases'">测试用例</button><button :class="{ active: drawerTab === 'result' }" @click="drawerTab = 'result'">测试结果</button><button class="drawer-collapse" type="button" title="收起结果" @click="drawerOpen = false"><ChevronDown :size="18" /></button></header>
            <div class="drawer-content">
              <template v-if="drawerTab === 'cases'"><div class="case-tabs"><div v-for="(_, index) in runInputs" :key="index" class="editable-case"><strong>用例 {{ index + 1 }}</strong><textarea v-model="runInputs[index]" :aria-label="'公开用例 ' + (index + 1) + ' 输入'" /></div></div></template>
              <template v-else><div v-if="submission" class="submission-result"><div class="result-summary"><span :class="['status-badge', 'status-badge--' + submission.status.toLowerCase()]">{{ submission.status }}</span><strong v-if="submission.executionMode === 'SUBMIT'">{{ submission.score }} 分</strong><strong v-else>公开运行</strong></div><pre v-if="submission.compileMessage" class="compile-output">{{ submission.compileMessage }}</pre><div v-if="submission.testCases.length" :class="submission.executionMode === 'RUN' ? 'run-results' : 'case-results'"><div v-for="item in submission.testCases" :key="item.ordinal"><template v-if="submission.executionMode === 'RUN'"><header><strong>用例 {{ item.ordinal }} · {{ item.status }}</strong><span>{{ item.timeMs }} ms · {{ item.memoryKiB }} KiB</span></header><div class="run-output-columns"><div><span>输入</span><pre>{{ item.input }}</pre></div><div><span>实际输出</span><pre>{{ item.actualOutput ?? '等待运行结果' }}</pre></div></div></template><template v-else><span>#{{ item.ordinal }}</span><strong>{{ item.status }}</strong><span>{{ item.score }} 分</span><span>{{ item.timeMs }} ms</span><span>{{ item.memoryKiB }} KiB</span></template></div></div><p v-else class="waiting-text">{{ terminalStatuses.has(submission.status) ? '没有测点结果' : '任务已进入持久化队列' }}</p></div><UiEmptyState v-else description="运行或提交后在此查看结果" /></template>
            </div>
          </section>
        </div>
        <footer class="workspace-actions"><span :class="['live-status', 'live-status--' + statusLabel.toLowerCase()]">{{ statusLabel }}</span><button v-if="!drawerOpen" class="icon-button" type="button" title="展开测试与结果" @click="showCases"><ChevronUp :size="18" /></button><UiButton :loading="running" @click="runSamples"><Play :size="16" />运行</UiButton><UiButton :loading="submitting" @click="submit"><Send :size="16" />提交</UiButton></footer>
      </section>
      <section class="mobile-result-panel" :class="{ 'mobile-hidden': mobileTab !== 'result' }"><header><strong>测试与结果</strong><span>{{ statusLabel }}</span></header><div v-if="submission" class="submission-result"><div class="result-summary"><span :class="['status-badge', 'status-badge--' + submission.status.toLowerCase()]">{{ submission.status }}</span><strong v-if="submission.executionMode === 'SUBMIT'">{{ submission.score }} 分</strong><strong v-else>公开运行</strong></div><pre v-if="submission.compileMessage" class="compile-output">{{ submission.compileMessage }}</pre><div v-if="submission.executionMode === 'RUN'" class="run-results"><div v-for="item in submission.testCases" :key="item.ordinal"><header><strong>用例 {{ item.ordinal }} · {{ item.status }}</strong><span>{{ item.timeMs }} ms</span></header><div class="run-output-columns"><div><span>输入</span><pre>{{ item.input }}</pre></div><div><span>实际输出</span><pre>{{ item.actualOutput ?? '等待运行结果' }}</pre></div></div></div></div><div v-else class="case-results"><div v-for="item in submission.testCases" :key="item.ordinal"><span>#{{ item.ordinal }}</span><strong>{{ item.status }}</strong><span>{{ item.score }} 分</span></div></div></div><div v-else class="mobile-run-actions"><p>选择运行公开样例或提交全部隐藏测试点。</p><UiButton @click="runSamples"><Play :size="16" />运行</UiButton><UiButton @click="submit"><Send :size="16" />提交</UiButton></div></section>
    </div>
  </section>
</template>
