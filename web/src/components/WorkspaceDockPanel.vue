<script setup lang="ts">
import type { Component } from "vue";
import { Bookmark, CheckCircle2, ChevronRight, CircleAlert, CircleX, ClipboardList, Clock3, Info, LoaderCircle, Maximize2, MemoryStick, RefreshCw, RotateCcw, Settings2 } from "@lucide/vue";
import type { WorkspacePanelContext, WorkspacePanelKind } from "../views/workspacePanel";
import CodeEditor from "./CodeEditor.vue";
import UiButton from "./ui/Button.vue";
import UiNumberField from "./ui/NumberField.vue";
import UiEmptyState from "./ui/EmptyState.vue";

const props = defineProps<{
  /** 当前停靠面板类型。 */
  kind: WorkspacePanelKind;
  /** 与主工作区共享的状态。 */
  context: WorkspacePanelContext;
}>();

/** 将判题状态转换为用户可读文本。 */
function statusText(status: string): string {
  const labels: Record<string, string> = {
    READY: "就绪", QUEUED: "排队中", COMPILING: "编译中", JUDGING: "判题中", AC: "通过",
    PARTIAL: "部分通过", WA: "解答错误", CE: "编译错误", TLE: "超时", MLE: "内存超限",
    RE: "运行错误", OLE: "输出超限", SYSTEM_ERROR: "系统错误", CANCELED: "已取消",
  };
  return labels[status] ?? status;
}

/** 返回状态徽标样式。 */
function statusClass(status: string): string {
  return "status-badge status-badge--" + status.toLowerCase();
}

/** 为判题状态选择统一的结果图标。 */
function statusIcon(status: string): Component {
  if (status === "AC") return CheckCircle2;
  if (["QUEUED", "COMPILING", "JUDGING"].includes(status)) return LoaderCircle;
  if (["PARTIAL", "TLE", "MLE", "OLE"].includes(status)) return CircleAlert;
  if (["WA", "CE", "RE", "SYSTEM_ERROR", "CANCELED"].includes(status)) return CircleX;
  return Info;
}

/** 判断状态图标是否需要展示持续运行的加载动画。 */
function isPendingStatus(status: string): boolean {
  return ["QUEUED", "COMPILING", "JUDGING"].includes(status);
}

/** 将提交时间转换为提交历史列表中的相对文案。 */
function relativeSubmissionTime(value: string): string {
  const elapsed = Math.max(0, Date.now() - new Date(value).getTime());
  const minutes = Math.floor(elapsed / 60_000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return minutes + " 分钟前";
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return hours + " 小时前";
  const days = Math.floor(hours / 24);
  if (days < 7) return days + " 天前";
  return new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date(value));
}

/** 聚合正式提交的总执行时间；判题未完成时返回空值。 */
function submissionTimeMs(row: WorkspacePanelContext["submissionHistory"][number]): number | null {
  if (!row.testCases.length) return null;
  return row.testCases.reduce((sum, item) => sum + item.timeMs, 0);
}

/** 聚合正式提交的峰值内存；判题未完成时返回空值。 */
function submissionMemoryKiB(row: WorkspacePanelContext["submissionHistory"][number]): number | null {
  if (!row.testCases.length) return null;
  return row.testCases.reduce((max, item) => Math.max(max, item.memoryKiB), 0);
}

/** 将后端语言枚举转换为提交历史中的短文案。 */
function historyLanguageLabel(language: string): string {
  return { C17: "C", CPP17: "C++", JAVA21: "Java", PYTHON3: "Python" }[language] ?? language;
}

/** 将源码草稿保存状态转换为用户可读文本。 */
function codeSaveStatusText(state: WorkspacePanelContext["codeSaveState"]): string {
  if (state === "pending") return "等待保存";
  if (state === "saving") return "保存中";
  if (state === "error") return "保存失败";
  return "已自动保存";
}

/** 为源码草稿保存状态选择对应图标。 */
function codeSaveStatusIcon(state: WorkspacePanelContext["codeSaveState"]): Component {
  if (state === "error") return CircleAlert;
  if (state === "pending" || state === "saving") return LoaderCircle;
  return CheckCircle2;
}
</script>

<template>
  <article v-if="kind === 'statement'" class="dock-panel dock-panel--statement">
    <header class="dock-panel-heading">
      <div v-if="context.problem">
        <h1>{{ context.problem.title }}</h1>
        <p>{{ context.problem.school }} · {{ context.problem.year }} · 版本 {{ context.problem.versionNumber }}</p>
      </div>
      <button class="icon-button" type="button" title="收藏题目" aria-label="收藏题目" @click="context.favorite"><Bookmark :size="18" /></button>
    </header>
    <div v-if="context.problem" class="problem-meta">
      <span v-for="tag in context.problem.tags" :key="tag" class="plain-tag">{{ tag }}</span>
      <span v-if="context.problem.dataNotice" class="data-notice">{{ context.problem.dataNotice }}</span>
    </div>
    <div class="markdown-body" v-html="context.renderedStatement" />
    <section v-if="context.problem?.samples.length" class="samples">
      <h2>公开样例</h2>
      <div v-for="sample in context.problem.samples" :key="sample.ordinal" class="sample-block">
        <strong>样例 {{ sample.ordinal }}</strong>
        <div class="sample-columns">
          <div><span>输入</span><pre>{{ sample.input }}</pre></div>
          <div><span>输出</span><pre>{{ sample.output }}</pre></div>
        </div>
      </div>
    </section>
  </article>

  <article v-else-if="kind === 'code'" class="dock-panel dock-panel--code">
    <header class="dock-code-toolbar">
      <div class="toolbar-group">
        <select :value="context.language" class="compact-select" aria-label="编程语言" @change="context.setLanguage(($event.target as HTMLSelectElement).value as typeof context.language)">
          <option value="C17">GNU C17</option>
          <option value="CPP17">GNU C++17</option>
          <option value="JAVA21">OpenJDK 21</option>
          <option value="PYTHON3">CPython 3</option>
        </select>
        <span v-if="context.activeLimit" class="limit-text">{{ context.activeLimit.timeLimitMs }} ms · {{ context.activeLimit.memoryLimitMiB }} MiB</span>
        <span :class="['code-save-status', 'code-save-status--' + context.codeSaveState]" role="status" aria-live="polite">
          <component :is="codeSaveStatusIcon(context.codeSaveState)" :class="{ 'status-icon--loading': context.codeSaveState === 'pending' || context.codeSaveState === 'saving' }" :size="14" aria-hidden="true" />
          {{ codeSaveStatusText(context.codeSaveState) }}
        </span>
      </div>
      <div class="toolbar-group">
        <div class="editor-settings">
          <button class="icon-button" type="button" title="编辑器设置" aria-label="编辑器设置" :aria-expanded="context.settingsOpen" @click="context.toggleSettings"><Settings2 :size="17" /></button>
          <div v-if="context.settingsOpen" class="editor-settings-popover">
            <label class="font-setting">字号 <UiNumberField :model-value="context.fontSize" :min="12" :max="22" @update:model-value="context.setFontSize(Number($event))" /></label>
          </div>
        </div>
        <button class="icon-button" type="button" title="重置代码" aria-label="重置代码" @click="context.resetCode"><RotateCcw :size="17" /></button>
        <button class="icon-button" type="button" title="全屏工作区" aria-label="全屏工作区" @click="context.fullscreen"><Maximize2 :size="17" /></button>
      </div>
    </header>
    <div class="dock-code-editor">
      <CodeEditor :model-value="context.code" :language="context.language" :font-size="context.fontSize" :dark="context.dark" @update:model-value="context.setCode" />
    </div>
  </article>

  <article v-else-if="kind === 'cases'" class="dock-panel dock-panel--cases">
    <div class="case-tab-list" role="tablist" aria-label="公开测试用例">
      <button v-for="(_, index) in context.runInputs" :key="index" type="button" role="tab" :aria-selected="index === context.activeCase" class="case-tab" :class="{ active: index === context.activeCase }" @click="context.setActiveCase(index)">Case {{ index + 1 }}</button>
    </div>
    <div class="case-readonly-list">
      <div v-if="context.runInputs[context.activeCase] !== undefined" class="case-editor-item">
        <div class="case-readonly-columns">
          <div class="case-readonly-block"><span>输入</span><pre>{{ context.runInputs[context.activeCase] }}</pre></div>
          <div class="case-readonly-block"><span>输出</span><pre>{{ context.problem?.samples[context.activeCase]?.output ?? "暂无样例输出" }}</pre></div>
        </div>
      </div>
    </div>
  </article>

  <article v-else-if="kind === 'history'" class="dock-panel dock-panel--history">
    <div class="workspace-history-panel" :aria-busy="context.submissionHistoryLoading">
      <div class="workspace-history-toolbar">
        <UiButton variant="ghost" size="icon" title="刷新提交历史" aria-label="刷新提交历史" :loading="context.submissionHistoryLoading" @click="context.refreshSubmissionHistory"><RefreshCw :size="15" /></UiButton>
      </div>
      <div class="workspace-history-columns" aria-hidden="true">
        <span />
        <span>状态</span>
        <span>语言</span>
        <span><Clock3 :size="13" />执行用时</span>
        <span><MemoryStick :size="13" />消耗内存</span>
        <span>得分</span>
        <span />
      </div>
      <div v-if="context.submissionHistoryLoading" class="workspace-history-skeleton-list" aria-label="正在加载提交历史">
        <div v-for="index in 5" :key="index" class="workspace-history-skeleton"><i /><span /><b /><em /><u /><small /></div>
      </div>
      <UiEmptyState v-else-if="context.submissionHistoryError" :description="context.submissionHistoryError" class="workspace-history-empty">
        <template #icon><ClipboardList :size="24" aria-hidden="true" /></template>
      </UiEmptyState>
      <div v-else-if="context.submissionHistory.length" class="workspace-history-list" role="list">
        <button v-for="(row, index) in context.submissionHistory" :key="row.id" type="button" class="workspace-history-row" @click="context.openSubmissionDetail(row.id)">
          <span class="workspace-history-index">{{ context.submissionHistory.length - index }}</span>
          <span :class="['workspace-history-status', 'submission-status--' + row.status.toLowerCase()]">
            <span><component :is="statusIcon(row.status)" :class="{ 'status-icon--loading': isPendingStatus(row.status) }" :size="16" aria-hidden="true" /><strong>{{ statusText(row.status) }}</strong></span>
            <time :datetime="row.createdAt">{{ relativeSubmissionTime(row.createdAt) }}</time>
          </span>
          <span class="workspace-history-language">{{ historyLanguageLabel(row.language) }}</span>
          <span class="workspace-history-metric">{{ submissionTimeMs(row) === null ? "N/A" : submissionTimeMs(row) + " ms" }}</span>
          <span class="workspace-history-metric">{{ submissionMemoryKiB(row) === null ? "N/A" : (submissionMemoryKiB(row)! / 1024).toFixed(1) + " MiB" }}</span>
          <span class="workspace-history-score">{{ row.score }}</span>
          <ChevronRight class="workspace-history-chevron" :size="16" aria-hidden="true" />
        </button>
      </div>
      <UiEmptyState v-else description="还没有提交记录" class="workspace-history-empty">
        <template #icon><ClipboardList :size="24" aria-hidden="true" /></template>
      </UiEmptyState>
    </div>
  </article>

  <article v-else-if="kind === 'result'" class="dock-panel dock-panel--result">
    <div class="dock-result-content">
      <template v-if="context.runSubmission">
        <div class="result-summary result-summary--run">
          <div :class="['result-status-title', 'result-status-title--' + context.runSubmission.status.toLowerCase()]">
            <component :is="statusIcon(context.runSubmission.status)" :class="{ 'status-icon--loading': isPendingStatus(context.runSubmission.status) }" :size="22" aria-hidden="true" />
            <strong>{{ statusText(context.runSubmission.status) }}</strong>
          </div>
          <span v-if="context.runSubmission.testCases.length" class="execution-time"><Clock3 :size="14" />执行用时：{{ context.runSubmission.testCases.reduce((sum, item) => sum + item.timeMs, 0) }} ms</span>
        </div>
        <pre v-if="context.runSubmission.compileMessage" class="compile-output">{{ context.runSubmission.compileMessage }}</pre>
        <div v-if="context.runSubmission.testCases.length" class="result-case-tabs" role="tablist" aria-label="运行结果测试点">
          <button v-for="item in context.runSubmission.testCases" :key="item.ordinal" type="button" role="tab" :aria-selected="item.ordinal - 1 === context.activeCase" :class="['result-case-tab', { active: item.ordinal - 1 === context.activeCase, ['result-case-tab--' + item.status.toLowerCase()]: true }]" @click="context.setActiveCase(item.ordinal - 1)">
            <component :is="statusIcon(item.status)" :class="{ 'status-icon--loading': isPendingStatus(item.status) }" :size="15" aria-hidden="true" />Case {{ item.ordinal }}
          </button>
        </div>
        <div v-if="context.runSubmission.testCases[context.activeCase]" class="result-case-detail">
          <template v-for="item in [context.runSubmission.testCases[context.activeCase]]" :key="item.ordinal">
            <div class="result-case-detail-heading"><span>{{ item.timeMs }} ms · {{ item.memoryKiB }} KiB</span></div>
            <div class="run-output-columns">
              <div><span>输入</span><pre>{{ item.input ?? "—" }}</pre></div>
              <div><span>输出</span><pre>{{ item.actualOutput ?? "等待运行结果" }}</pre></div>
              <div><span>预期结果</span><pre>{{ context.problem?.samples[item.ordinal - 1]?.output ?? "—" }}</pre></div>
            </div>
          </template>
        </div>
        <p v-else class="waiting-text">{{ context.terminalStatuses.has(context.runSubmission.status) ? "没有可展示的测试点结果" : "任务已进入持久化队列" }}</p>
      </template>
      <UiEmptyState v-else description="运行或提交后在此查看结果" />
    </div>
  </article>

  <article v-else class="dock-panel dock-panel--submit-result">
    <div class="dock-result-content">
      <template v-if="context.submitSubmission">
        <div :class="['submit-result-summary', 'submit-result-summary--' + context.submitSubmission.status.toLowerCase()]">
          <component :is="statusIcon(context.submitSubmission.status)" :class="{ 'status-icon--loading': isPendingStatus(context.submitSubmission.status) }" :size="26" aria-hidden="true" />
          <div><strong>{{ statusText(context.submitSubmission.status) }}</strong><span>{{ context.submitSubmission.score }} 分</span></div>
        </div>
        <pre v-if="context.submitSubmission.compileMessage" class="compile-output">{{ context.submitSubmission.compileMessage }}</pre>
        <div v-if="context.submitSubmission.testCases.length" class="submit-case-list">
          <div v-for="item in context.submitSubmission.testCases" :key="item.ordinal" class="submit-case-row">
            <span class="submit-case-name"><component :is="statusIcon(item.status)" :class="{ 'status-icon--loading': isPendingStatus(item.status) }" :size="15" aria-hidden="true" />测试点 {{ item.ordinal }}</span>
            <span :class="statusClass(item.status)">{{ statusText(item.status) }}</span>
            <span>{{ item.score }} 分</span>
            <span>{{ item.timeMs }} ms</span>
          </div>
        </div>
        <p v-else class="waiting-text">{{ context.terminalStatuses.has(context.submitSubmission.status) ? "没有可展示的测点结果" : "任务已进入持久化队列" }}</p>
      </template>
      <UiEmptyState v-else description="提交后在此查看判题结果" />
    </div>
  </article>
</template>
