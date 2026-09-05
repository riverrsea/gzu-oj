<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import type { Component } from "vue";
import { ArrowLeft, CheckCircle2, CircleAlert, CircleX, Clock3, Code2, LoaderCircle, MemoryStick } from "@lucide/vue";
import { useRoute, useRouter } from "vue-router";
import { api } from "../api/client";
import { toast } from "../lib/notify";
import { formatChinaDateTime } from "../lib/time";
import type { JudgeLanguage, JudgeStatus, ProblemDetail, Submission } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";

const route = useRoute();
const router = useRouter();
const loading = ref(true);
const loadingError = ref("");
const submission = ref<Submission>();
const problem = ref<ProblemDetail>();

/** 将判题状态转换为提交详情中的完整文案。 */
function statusLabel(status: JudgeStatus): string {
  const labels: Record<JudgeStatus, string> = {
    QUEUED: "排队中",
    COMPILING: "编译中",
    JUDGING: "判题中",
    AC: "通过",
    PARTIAL: "部分通过",
    WA: "解答错误",
    CE: "编译错误",
    TLE: "超时",
    MLE: "内存超限",
    RE: "运行错误",
    OLE: "输出超限",
    SYSTEM_ERROR: "系统错误",
    CANCELED: "已取消",
  };
  return labels[status];
}

/** 为提交状态选择统一图标。 */
function statusIcon(status: JudgeStatus): Component {
  if (status === "AC") return CheckCircle2;
  if (["QUEUED", "COMPILING", "JUDGING"].includes(status)) return LoaderCircle;
  if (["PARTIAL", "TLE", "MLE", "OLE"].includes(status)) return CircleAlert;
  return CircleX;
}

/** 判断提交是否仍在排队或判题中。 */
function isPendingStatus(status: JudgeStatus): boolean {
  return ["QUEUED", "COMPILING", "JUDGING"].includes(status);
}

/** 将后端语言枚举转换为页面文案。 */
function languageLabel(language: JudgeLanguage): string {
  return { C17: "GNU C17", CPP17: "GNU C++17", JAVA21: "OpenJDK 21", PYTHON3: "CPython 3" }[language];
}

/** 格式化时间并保留到分钟，便于定位一次提交。 */
function formatDate(value: string): string {
  return formatChinaDateTime(value, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** 聚合脱敏测点中的最大内存占用。 */
const peakMemoryKiB = computed(() => submission.value?.testCases.reduce((max, item) => Math.max(max, item.memoryKiB), 0) ?? 0);

/** 聚合脱敏测点中的总执行时间。 */
const totalTimeMs = computed(() => submission.value?.testCases.reduce((sum, item) => sum + item.timeMs, 0) ?? 0);

/** 统计已经通过的测点数量；详情页只呈现汇总，避免结果卡片过长。 */
const passedTestCount = computed(() => submission.value?.testCases.filter((item) => item.status === "AC").length ?? 0);

/** 返回浏览器历史中的上一页。 */
function backToPreviousPage(): void {
  router.back();
}

/** 加载提交详情和对应版本标题。 */
async function load(): Promise<void> {
  loading.value = true;
  loadingError.value = "";
  try {
    const current = await api.submission(String(route.params.id));
    submission.value = current;
    try {
      problem.value = await api.problemVersion(current.problemVersionId);
    } catch {
      try {
        problem.value = await api.problem(current.problemId);
      } catch {
        // 旧版本可能已经撤回，详情仍可使用提交 ID 和题目 ID展示。
      }
    }
  } catch (error) {
    loadingError.value = error instanceof Error ? error.message : "提交详情加载失败";
    toast.error(loadingError.value);
  } finally {
    loading.value = false;
  }
}

onMounted(() => {
  void load();
});

watch(() => route.params.id, () => {
  void load();
});
</script>

<template>
  <section class="content-page content-page--modern oj-page submission-detail-page" :aria-busy="loading">
    <div v-if="loading" class="submission-detail-loading" aria-label="正在加载提交详情">
      <span class="submission-detail-skeleton submission-detail-skeleton--heading" />
      <span class="submission-detail-skeleton submission-detail-skeleton--meta" />
      <span class="submission-detail-skeleton submission-detail-skeleton--body" />
    </div>
    <div v-else-if="loadingError" class="submission-detail-error" role="alert">
      <UiEmptyState :description="loadingError">
        <UiButton variant="outline" size="sm" @click="load">重新加载</UiButton>
      </UiEmptyState>
    </div>
    <template v-else-if="submission">
      <header class="submission-detail-header">
        <button type="button" class="submission-detail-back" title="返回上一页" aria-label="返回上一页" @click="backToPreviousPage">
          <ArrowLeft :size="17" />
          <span>返回</span>
        </button>
        <div class="submission-detail-heading">
          <h1>{{ problem?.title ?? "题目 " + submission.problemId.slice(0, 8) }}</h1>
          <p class="submission-detail-meta">
            <span>提交于 {{ formatDate(submission.createdAt) }}</span>
            <span>完成于 {{ submission.finishedAt ? formatDate(submission.finishedAt) : "判题中" }}</span>
            <span>版本 v{{ problem?.versionNumber ?? "—" }}</span>
            <span>ID {{ submission.id.slice(0, 8) }}</span>
          </p>
        </div>
        <div :class="['submission-detail-verdict', 'submission-detail-verdict--' + submission.status.toLowerCase()]">
          <component :is="statusIcon(submission.status)" :class="{ 'status-icon--loading': isPendingStatus(submission.status) }" :size="26" aria-hidden="true" />
          <div>
            <strong>{{ statusLabel(submission.status) }}</strong>
            <span>{{ submission.score }} 分 · {{ submission.testCases.length ? passedTestCount + " / " + submission.testCases.length + " 个测试点通过" : "等待判题结果" }}</span>
          </div>
        </div>
      </header>

      <div class="submission-detail-metrics">
        <div><Code2 :size="16" aria-hidden="true" /><span>语言</span><strong>{{ languageLabel(submission.language) }}</strong></div>
        <div><Clock3 :size="16" aria-hidden="true" /><span>执行时间</span><strong>{{ totalTimeMs ? totalTimeMs + " ms" : "—" }}</strong></div>
        <div><MemoryStick :size="16" aria-hidden="true" /><span>峰值内存</span><strong>{{ peakMemoryKiB ? peakMemoryKiB + " KiB" : "—" }}</strong></div>
      </div>

      <div class="submission-detail-content">
        <section class="submission-detail-panel submission-detail-code">
          <header><h2>提交代码</h2><span>{{ languageLabel(submission.language) }}</span></header>
          <div v-if="submission.compileMessage" class="submission-detail-message submission-detail-message--compile">
            <strong>编译信息</strong>
            <pre>{{ submission.compileMessage }}</pre>
          </div>
          <pre class="submission-detail-code-body"><code>{{ submission.sourceCode ?? "该提交没有可显示的源码。" }}</code></pre>
        </section>
      </div>
    </template>
  </section>
</template>
