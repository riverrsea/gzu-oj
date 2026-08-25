<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { Clock3, ExternalLink, Link2, LockKeyhole, Plus, Trophy, Users } from "@lucide/vue";
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
  if (!activeAttempt.value) return 0;
  return Math.max(0, Math.floor((new Date(activeAttempt.value.expiresAt).getTime() - now.value) / 1000));
});

/** 格式化分钟与秒倒计时。 */
function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  return String(minutes).padStart(2, "0") + ":" + String(seconds % 60).padStart(2, "0");
}

/** 加载训练中心所需数据。 */
async function load(): Promise<void> {
  loading.value = true;
  try {
    [contests.value, papers.value, problems.value] = await Promise.all([
      api.contests(),
      api.timedPapers(),
      api.problems({}),
    ]);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "训练中心加载失败");
  } finally {
    loading.value = false;
  }
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
    paperDialog.value = false;
    toast.success("计时套卷已创建");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "套卷创建失败");
  }
}

/** 首次进入套卷并启动独立计时。 */
async function startPaper(paper: TimedPaper): Promise<void> {
  try {
    activeAttempt.value = await api.startTimedPaper(paper.id);
    tab.value = "paper";
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "套卷启动失败");
  }
}

/** 打开套卷锁定版本的做题工作区。 */
function openTimedProblem(problemId: string, versionId: string): void {
  if (!activeAttempt.value) return;
  void router.push({
    path: "/problems/" + problemId,
    query: { versionId, timedPaperAttemptId: activeAttempt.value.id },
  });
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
  <section class="content-page content-page--modern oj-page training-page loading-shell" :aria-busy="loading">
    <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>
    <div class="page-heading">
      <div><h1>训练中心</h1><p>公开训练赛采用 OI 计分，个人套卷首次进入后独立计时</p></div>
      <div class="heading-actions">
        <UiButton v-if="tab === 'contest'" @click="contestDialog = true"><Plus :size="16" />创建比赛</UiButton>
        <UiButton v-else @click="paperDialog = true"><Plus :size="16" />创建套卷</UiButton>
      </div>
    </div>

    <div class="ui-tabs">
      <div class="ui-tabs-list" role="tablist"><button type="button" :class="{ active: tab === 'contest' }" @click="tab = 'contest'">训练赛</button><button type="button" :class="{ active: tab === 'paper' }" @click="tab = 'paper'">个人计时</button></div>
      <div v-if="tab === 'contest'" class="ui-tab-panel">
        <div class="training-layout">
          <UiTable>
            <thead><tr><th>比赛</th><th>阶段</th><th>开始时间</th><th>人数</th><th>操作</th></tr></thead>
            <tbody v-if="contests.length"><tr v-for="row in contests" :key="row.id" class="interactive-table" @click="inspect(row)"><td><div class="problem-title"><strong>{{ row.title }}</strong><span>{{ row.ownerUsername }}</span></div></td><td><span :class="['phase-label', 'phase-label--' + row.phase.toLowerCase()]">{{ row.phase }}</span></td><td>{{ new Date(row.startsAt).toLocaleString() }}</td><td>{{ row.participantCount }}/{{ row.maxParticipants }}</td><td @click.stop><UiButton v-if="!row.joined && row.phase !== 'FINISHED'" size="sm" @click="join(row)">{{ row.visibility === 'PASSWORD' ? '输入口令' : '加入' }}</UiButton><span v-else>{{ row.joined ? '已加入' : '已结束' }}</span></td></tr></tbody>
            <tbody v-else><tr><td colspan="5"><UiEmptyState description="暂无训练赛" /></td></tr></tbody>
          </UiTable>

          <aside v-if="selectedContest" class="detail-panel">
            <header><div><h2>{{ selectedContest.title }}</h2><p><Users :size="15" /> {{ selectedContest.participantCount }}/{{ selectedContest.maxParticipants }} · {{ selectedContest.phase }}</p></div><LockKeyhole v-if="selectedContest.visibility === 'PASSWORD'" :size="18" /></header>
            <div class="locked-problem-list">
              <button v-for="problem in selectedContest.problems" :key="problem.versionId" :disabled="!selectedContest.joined || selectedContest.phase === 'UPCOMING'" @click="openContestProblem(selectedContest, problem.problemId, problem.versionId)"><span>{{ problem.ordinal }}</span><strong>{{ problem.title }}</strong><em>{{ selectedContest.myScores?.[problem.problemId] ?? 0 }} 分</em><ExternalLink :size="15" /></button>
            </div>
            <UiTable v-if="selectedContest.ranking"><thead><tr><th>#</th><th>用户</th><th>总分</th><th>用时</th></tr></thead><tbody><tr v-for="row in selectedContest.ranking" :key="row.username"><td>{{ row.rank }}</td><td>{{ row.username }}</td><td>{{ row.totalScore }}</td><td>{{ formatDuration(row.elapsedSeconds) }}</td></tr></tbody></UiTable>
          </aside>
        </div>
      </div>

      <div v-else class="ui-tab-panel">
        <section v-if="activeAttempt" class="attempt-band">
          <header><div><h2>{{ activeAttempt.paper.title }}</h2><p>{{ activeAttempt.finished ? '作答已结束' : '独立计时中' }} · {{ activeAttempt.totalScore }} 分</p></div><strong class="countdown"><Clock3 :size="18" />{{ formatDuration(remainingSeconds) }}</strong><UiButton @click="shareAttempt"><Link2 :size="16" />分享</UiButton></header>
          <div class="locked-problem-list"><button v-for="problem in activeAttempt.paper.problems" :key="problem.versionId" :disabled="activeAttempt.finished" @click="openTimedProblem(problem.problemId, problem.versionId)"><span>{{ problem.ordinal }}</span><strong>{{ problem.title }}</strong><em>{{ activeAttempt.scores[problem.problemId] ?? 0 }} 分</em><ExternalLink :size="15" /></button></div>
        </section>
        <UiTable><thead><tr><th>套卷</th><th>限时</th><th>题目数</th><th>操作</th></tr></thead><tbody v-if="papers.length"><tr v-for="row in papers" :key="row.id"><td>{{ row.title }}</td><td>{{ row.durationMinutes }} 分钟</td><td>{{ row.problems.length }}</td><td><UiButton size="sm" @click="startPaper(row)">开始作答</UiButton></td></tr></tbody><tbody v-else><tr><td colspan="4"><UiEmptyState description="暂无个人计时套卷" /></td></tr></tbody></UiTable>
      </div>
    </div>

    <UiDialog v-model="contestDialog" title="创建训练赛">
      <form class="problem-form" @submit.prevent="createContest"><div class="form-field"><UiLabel>标题</UiLabel><UiInput v-model="contestForm.title" maxlength="120" /></div><div class="form-grid"><div class="form-field"><UiLabel>可见性</UiLabel><UiSelect v-model="contestForm.visibility" placeholder=""><option value="PUBLIC">公开</option><option value="PASSWORD">口令</option></UiSelect></div><div v-if="contestForm.visibility === 'PASSWORD'" class="form-field"><UiLabel>口令</UiLabel><UiInput v-model="contestForm.password" type="password" /></div><div class="form-field"><UiLabel>开始时间</UiLabel><UiInput v-model="contestForm.startsAt" type="datetime-local" /></div><div class="form-field"><UiLabel>时长（分钟）</UiLabel><UiNumberField v-model="contestForm.durationMinutes" :min="15" :max="300" /></div></div><div class="form-field"><UiLabel>题目（可多选）</UiLabel><select v-model="contestForm.problemIds" class="multi-select" multiple><option v-for="problem in problems" :key="problem.id" :value="problem.id">{{ problem.title }}</option></select></div><div class="form-actions"><UiButton variant="outline" type="button" @click="contestDialog = false">取消</UiButton><UiButton type="submit"><Trophy :size="16" />创建</UiButton></div></form>
    </UiDialog>
    <UiDialog v-model="paperDialog" title="创建个人计时套卷">
      <form class="problem-form" @submit.prevent="createPaper"><div class="form-field"><UiLabel>标题</UiLabel><UiInput v-model="paperForm.title" maxlength="120" /></div><div class="form-field"><UiLabel>时长（分钟）</UiLabel><UiNumberField v-model="paperForm.durationMinutes" :min="15" :max="300" /></div><div class="form-field"><UiLabel>题目（可多选）</UiLabel><select v-model="paperForm.problemIds" class="multi-select" multiple><option v-for="problem in problems" :key="problem.id" :value="problem.id">{{ problem.title }}</option></select></div><div class="form-actions"><UiButton variant="outline" type="button" @click="paperDialog = false">取消</UiButton><UiButton type="submit">创建套卷</UiButton></div></form>
    </UiDialog>
  </section>
</template>
