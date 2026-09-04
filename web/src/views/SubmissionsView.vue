<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import type { Component } from "vue";
import { CheckCircle2, ChevronRight, CircleAlert, CircleX, Clock3, LoaderCircle, RefreshCw, X } from "@lucide/vue";
import { useRoute, useRouter } from "vue-router";
import { toast } from "../lib/notify";
import { formatChinaDateTime } from "../lib/time";
import { api } from "../api/client";
import type { JudgeLanguage, JudgeStatus, ProblemSummary, Submission } from "../api/types";
import UiButton from "../components/ui/Button.vue";
import UiEmptyState from "../components/ui/EmptyState.vue";

/** 提交历史列表的筛选范围。 */
type SubmissionFilter = "ALL" | "AC" | "FAILED" | "PENDING";

/** 每次从服务端读取的历史记录数量。 */
const PAGE_SIZE = 30;

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const loadingMore = ref(false);
const submissions = ref<Submission[]>([]);
const problemCatalog = ref<Record<string, ProblemSummary>>({});
const activeFilter = ref<SubmissionFilter>("ALL");
const cursor = ref<string>();
const hasMore = ref(true);

/** 从做题页带入的稳定题目筛选，只影响当前列表，不改变后端查询契约。 */
const problemFilter = computed(() => typeof route.query.problemId === "string" ? route.query.problemId : "");
/** 从做题页带入的不可变版本筛选，避免不同版本的提交混在一起。 */
const versionFilter = computed(() => typeof route.query.versionId === "string" ? route.query.versionId : "");

const baseVisibleSubmissions = computed(() => submissions.value.filter((row) =>
  (!problemFilter.value || row.problemId === problemFilter.value)
  && (!versionFilter.value || row.problemVersionId === versionFilter.value),
));
const visibleSubmissions = computed(() => baseVisibleSubmissions.value.filter((row) => {
  if (activeFilter.value === "ALL") return true;
  if (activeFilter.value === "AC") return row.status === "AC";
  if (activeFilter.value === "PENDING") return isPendingStatus(row.status);
  return row.status !== "AC" && !isPendingStatus(row.status);
}));

const filterOptions = computed(() => [
  { key: "ALL" as const, label: "全部", count: baseVisibleSubmissions.value.length },
  { key: "AC" as const, label: "通过", count: baseVisibleSubmissions.value.filter((row) => row.status === "AC").length },
  { key: "FAILED" as const, label: "未通过", count: baseVisibleSubmissions.value.filter((row) => row.status !== "AC" && !isPendingStatus(row.status)).length },
  { key: "PENDING" as const, label: "判题中", count: baseVisibleSubmissions.value.filter((row) => isPendingStatus(row.status)).length },
] satisfies Array<{ key: SubmissionFilter; label: string; count: number }>);

/** 将判题状态转换为提交历史中的简短文案。 */
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

/** 为状态选择语义颜色和图标样式。 */
function statusClass(status: JudgeStatus): string {
  return "submission-status submission-status--" + status.toLowerCase();
}

/** 为提交状态选择统一图标。 */
function statusIcon(status: JudgeStatus): Component {
  if (status === "AC") return CheckCircle2;
  if (isPendingStatus(status)) return LoaderCircle;
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

/** 格式化提交时间，保证桌面和移动端都能完整显示。 */
function formatDate(value: string): string {
  return formatChinaDateTime(value, {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** 优先显示题目标题；历史版本已经下线时回退到稳定题目标识。 */
function problemTitle(row: Submission): string {
  return problemCatalog.value[row.problemId]?.title ?? "题目 " + row.problemId.slice(0, 8);
}

/** 显示题目的学校和年份，避免在历史列表中暴露过长 UUID。 */
function problemMeta(row: Submission): string {
  const summary = problemCatalog.value[row.problemId];
  if (!summary) return "题目标识 " + row.problemId.slice(0, 8);
  return [summary.school, summary.year].filter(Boolean).join(" · ") || "已发布题目";
}

/** 合并一页提交记录并更新下一页游标。 */
function appendRows(rows: Submission[], reset: boolean): void {
  if (reset) submissions.value = [];
  const existing = new Set(submissions.value.map((row) => row.id));
  submissions.value.push(...rows.filter((row) => !existing.has(row.id)));
  cursor.value = rows[rows.length - 1]?.createdAt;
  hasMore.value = rows.length >= PAGE_SIZE;
}

/** 尽力加载题目摘要，为历史记录提供标题；题目接口失败不影响提交列表。 */
async function loadProblemCatalog(): Promise<void> {
  try {
    const rows = await api.problems({});
    problemCatalog.value = Object.fromEntries(rows.map((row) => [row.id, row]));
  } catch {
    // 题目可能已撤回或当前服务只开放提交接口，此时使用题目 ID 回退文案。
  }
}

/** 加载第一页提交历史。 */
async function load(): Promise<void> {
  loading.value = true;
  activeFilter.value = "ALL";
  cursor.value = undefined;
  hasMore.value = true;
  try {
    const rows = await api.submissions({ limit: PAGE_SIZE });
    appendRows(rows, true);
    await loadProblemCatalog();
  } catch (error) {
    submissions.value = [];
    toast.error(error instanceof Error ? error.message : "提交历史加载失败");
  } finally {
    loading.value = false;
  }
}

/** 加载更早的一页提交历史。 */
async function loadMore(): Promise<void> {
  if (loadingMore.value || !hasMore.value || !cursor.value) return;
  loadingMore.value = true;
  try {
    const rows = await api.submissions({ limit: PAGE_SIZE, before: cursor.value });
    appendRows(rows, false);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "更多提交记录加载失败");
  } finally {
    loadingMore.value = false;
  }
}

/** 清除从做题页带入的题目筛选。 */
function clearProblemFilter(): void {
  void router.replace({ path: "/submissions" });
}

/** 历史记录点击后进入提交详情页，不跳回做题编辑器。 */
function openSubmission(row: Submission): void {
  void router.push({ path: "/submissions/" + row.id, query: route.query });
}

watch(() => [route.query.problemId, route.query.versionId], () => {
  void load();
});

onMounted(() => {
  void load();
});
</script>

<template>
  <section class="content-page content-page--modern oj-page submission-history-page" :aria-busy="loading">
    <div class="page-heading">
      <div>
        <h1>提交历史</h1>
        <p v-if="problemFilter">{{ versionFilter ? "当前版本" : "当前题目" }}：{{ problemCatalog[problemFilter]?.title ?? problemFilter.slice(0, 8) }}</p>
      </div>
      <div class="heading-actions">
        <UiButton variant="outline" size="sm" :loading="loading" @click="load"><RefreshCw :size="15" />刷新</UiButton>
      </div>
    </div>

    <div class="submission-history-surface">
      <div class="submission-history-toolbar">
        <div class="submission-filter-tabs" role="tablist" aria-label="提交状态筛选">
          <button v-for="option in filterOptions" :key="option.key" type="button" role="tab" :aria-selected="activeFilter === option.key" :class="{ active: activeFilter === option.key }" @click="activeFilter = option.key">
            {{ option.label }} <span>{{ option.count }}</span>
          </button>
        </div>
        <div class="submission-toolbar-meta">
          <span v-if="problemFilter || versionFilter" class="submission-filter-chip">
            <Clock3 :size="13" />{{ versionFilter ? "当前版本" : "当前题目" }}
            <button type="button" aria-label="清除题目筛选" title="清除题目筛选" @click="clearProblemFilter"><X :size="13" /></button>
          </span>
          <span>{{ visibleSubmissions.length }} 条提交</span>
        </div>
      </div>

      <div v-if="loading" class="submission-history-list submission-history-list--loading" aria-label="正在加载提交历史">
        <div v-for="index in 6" :key="index" class="submission-history-skeleton"><i /><span /><b /><em /></div>
      </div>
      <div v-else-if="visibleSubmissions.length" class="submission-history-list" role="list">
        <button v-for="row in visibleSubmissions" :key="row.id" type="button" class="submission-history-row" @click="openSubmission(row)">
          <span :class="statusClass(row.status)">
            <component :is="statusIcon(row.status)" :class="{ 'status-icon--loading': isPendingStatus(row.status) }" :size="17" aria-hidden="true" />
            <strong>{{ statusLabel(row.status) }}</strong>
          </span>
          <span class="submission-row-problem">
            <strong>{{ problemTitle(row) }}</strong>
            <small>{{ problemMeta(row) }}</small>
          </span>
          <span class="submission-row-language">{{ languageLabel(row.language) }}</span>
          <span class="submission-row-score"><strong>{{ row.score }}</strong><small>分</small></span>
          <time :datetime="row.createdAt" class="submission-row-time">{{ formatDate(row.createdAt) }}</time>
          <ChevronRight class="submission-row-chevron" :size="17" aria-hidden="true" />
        </button>
      </div>
      <UiEmptyState v-else :description="versionFilter ? '当前版本还没有符合条件的提交' : problemFilter ? '当前题目还没有符合条件的提交' : '还没有提交记录'" class="submission-history-empty" />

      <div v-if="hasMore && !loading" class="submission-history-pagination">
        <UiButton variant="outline" size="sm" :loading="loadingMore" @click="loadMore">加载更早记录</UiButton>
      </div>
    </div>
  </section>
</template>
