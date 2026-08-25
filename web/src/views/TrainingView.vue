<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { Clock3, ExternalLink, Link2, LockKeyhole, Plus, Trophy, Users } from "@lucide/vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useRouter } from "vue-router";
import { api } from "../api/client";
import type { Contest, ContestVisibility, ProblemSummary, TimedAttempt, TimedPaper } from "../api/types";

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
    ElMessage.error(error instanceof Error ? error.message : "训练中心加载失败");
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
    ElMessage.success("训练赛已创建");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "训练赛创建失败");
  }
}

/** 加入一场公开或口令比赛。 */
async function join(contest: Contest): Promise<void> {
  try {
    let password: string | undefined;
    if (contest.visibility === "PASSWORD") {
      const result = await ElMessageBox.prompt("输入比赛口令", "加入训练赛", {
        inputType: "password",
        inputPattern: /^.{8,100}$/,
        inputErrorMessage: "口令长度需为 8 到 100 位",
      });
      password = result.value;
    }
    const joined = await api.joinContest(contest.id, password);
    replaceContest(joined);
    selectedContest.value = joined;
    ElMessage.success("已加入训练赛");
  } catch (error) {
    if (error === "cancel" || error === "close") return;
    ElMessage.error(error instanceof Error ? error.message : "加入失败");
  }
}

/** 刷新并展开比赛详情。 */
async function inspect(contest: Contest): Promise<void> {
  try {
    const detail = await api.contest(contest.id);
    replaceContest(detail);
    selectedContest.value = detail;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "比赛详情加载失败");
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
    ElMessage.success("计时套卷已创建");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "套卷创建失败");
  }
}

/** 首次进入套卷并启动独立计时。 */
async function startPaper(paper: TimedPaper): Promise<void> {
  try {
    activeAttempt.value = await api.startTimedPaper(paper.id);
    tab.value = "paper";
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "套卷启动失败");
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
    ElMessage.success("只读分享链接已复制");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "分享链接生成失败");
  }
}

onMounted(() => {
  ticker = window.setInterval(() => { now.value = Date.now(); }, 1000);
  void load();
});
onBeforeUnmount(() => window.clearInterval(ticker));
</script>

<template>
  <section v-loading="loading" class="content-page content-page--modern oj-page training-page">
    <div class="page-heading">
      <div><h1>训练中心</h1><p>公开训练赛采用 OI 计分，个人套卷首次进入后独立计时</p></div>
      <div class="heading-actions">
        <el-button v-if="tab === 'contest'" @click="contestDialog = true"><Plus :size="16" />创建比赛</el-button>
        <el-button v-else @click="paperDialog = true"><Plus :size="16" />创建套卷</el-button>
      </div>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="训练赛" name="contest">
        <div class="training-layout">
          <el-table :data="contests" row-key="id" class="interactive-table" @row-click="inspect">
            <el-table-column label="比赛" min-width="230"><template #default="{ row }"><div class="problem-title"><strong>{{ row.title }}</strong><span>{{ row.ownerUsername }}</span></div></template></el-table-column>
            <el-table-column label="阶段" width="100"><template #default="{ row }"><span :class="['phase-label', 'phase-label--' + row.phase.toLowerCase()]">{{ row.phase }}</span></template></el-table-column>
            <el-table-column label="开始时间" width="180"><template #default="{ row }">{{ new Date(row.startsAt).toLocaleString() }}</template></el-table-column>
            <el-table-column label="人数" width="100"><template #default="{ row }">{{ row.participantCount }}/{{ row.maxParticipants }}</template></el-table-column>
            <el-table-column width="110"><template #default="{ row }"><el-button v-if="!row.joined && row.phase !== 'FINISHED'" size="small" @click.stop="join(row)">{{ row.visibility === 'PASSWORD' ? '输入口令' : '加入' }}</el-button><span v-else>{{ row.joined ? '已加入' : '已结束' }}</span></template></el-table-column>
          </el-table>

          <aside v-if="selectedContest" class="detail-panel">
            <header><div><h2>{{ selectedContest.title }}</h2><p><Users :size="15" /> {{ selectedContest.participantCount }}/{{ selectedContest.maxParticipants }} · {{ selectedContest.phase }}</p></div><LockKeyhole v-if="selectedContest.visibility === 'PASSWORD'" :size="18" /></header>
            <div class="locked-problem-list">
              <button v-for="problem in selectedContest.problems" :key="problem.versionId" :disabled="!selectedContest.joined || selectedContest.phase === 'UPCOMING'" @click="openContestProblem(selectedContest, problem.problemId, problem.versionId)"><span>{{ problem.ordinal }}</span><strong>{{ problem.title }}</strong><em>{{ selectedContest.myScores?.[problem.problemId] ?? 0 }} 分</em><ExternalLink :size="15" /></button>
            </div>
            <el-table v-if="selectedContest.ranking" :data="selectedContest.ranking" size="small"><el-table-column prop="rank" label="#" width="48" /><el-table-column prop="username" label="用户" /><el-table-column prop="totalScore" label="总分" width="70" /><el-table-column label="用时" width="90"><template #default="{ row }">{{ formatDuration(row.elapsedSeconds) }}</template></el-table-column></el-table>
          </aside>
        </div>
      </el-tab-pane>

      <el-tab-pane label="个人计时" name="paper">
        <section v-if="activeAttempt" class="attempt-band">
          <header><div><h2>{{ activeAttempt.paper.title }}</h2><p>{{ activeAttempt.finished ? '作答已结束' : '独立计时中' }} · {{ activeAttempt.totalScore }} 分</p></div><strong class="countdown"><Clock3 :size="18" />{{ formatDuration(remainingSeconds) }}</strong><el-button @click="shareAttempt"><Link2 :size="16" />分享</el-button></header>
          <div class="locked-problem-list"><button v-for="problem in activeAttempt.paper.problems" :key="problem.versionId" :disabled="activeAttempt.finished" @click="openTimedProblem(problem.problemId, problem.versionId)"><span>{{ problem.ordinal }}</span><strong>{{ problem.title }}</strong><em>{{ activeAttempt.scores[problem.problemId] ?? 0 }} 分</em><ExternalLink :size="15" /></button></div>
        </section>
        <el-table :data="papers" row-key="id" class="interactive-table">
          <el-table-column prop="title" label="套卷" min-width="250" /><el-table-column prop="durationMinutes" label="限时" width="100"><template #default="{ row }">{{ row.durationMinutes }} 分钟</template></el-table-column><el-table-column label="题目数" width="100"><template #default="{ row }">{{ row.problems.length }}</template></el-table-column><el-table-column width="110"><template #default="{ row }"><el-button size="small" type="primary" @click="startPaper(row)">开始作答</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="contestDialog" title="创建训练赛" width="min(560px, 92vw)">
      <el-form label-position="top"><el-form-item label="标题"><el-input v-model="contestForm.title" maxlength="120" /></el-form-item><div class="form-grid"><el-form-item label="可见性"><el-segmented v-model="contestForm.visibility" :options="[{ label: '公开', value: 'PUBLIC' }, { label: '口令', value: 'PASSWORD' }]" /></el-form-item><el-form-item v-if="contestForm.visibility === 'PASSWORD'" label="口令"><el-input v-model="contestForm.password" type="password" show-password /></el-form-item><el-form-item label="开始时间"><el-input v-model="contestForm.startsAt" type="datetime-local" /></el-form-item><el-form-item label="时长"><el-input-number v-model="contestForm.durationMinutes" :min="15" :max="300" /></el-form-item></div><el-form-item label="题目"><el-select v-model="contestForm.problemIds" multiple filterable><el-option v-for="problem in problems" :key="problem.id" :label="problem.title" :value="problem.id" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="contestDialog = false">取消</el-button><el-button type="primary" @click="createContest"><Trophy :size="16" />创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="paperDialog" title="创建个人计时套卷" width="min(520px, 92vw)">
      <el-form label-position="top"><el-form-item label="标题"><el-input v-model="paperForm.title" maxlength="120" /></el-form-item><el-form-item label="时长"><el-input-number v-model="paperForm.durationMinutes" :min="15" :max="300" /></el-form-item><el-form-item label="题目"><el-select v-model="paperForm.problemIds" multiple filterable><el-option v-for="problem in problems" :key="problem.id" :label="problem.title" :value="problem.id" /></el-select></el-form-item></el-form>
      <template #footer><el-button @click="paperDialog = false">取消</el-button><el-button type="primary" @click="createPaper">创建套卷</el-button></template>
    </el-dialog>
  </section>
</template>
