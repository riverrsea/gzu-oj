<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, Bot, Plus, RefreshCw, Save, Send, Trash2, XCircle } from "@lucide/vue";
import { confirmAction, toast } from "../lib/notify";
import { api } from "../api/client";
import type { AdminProblemVersionDetail, AiMajorState, AiRun, AiStepResponse, Difficulty } from "../api/types";
import ProblemStatementEditor from "../components/ProblemStatementEditor.vue";
import UiAlert from "../components/ui/Alert.vue";
import UiButton from "../components/ui/Button.vue";
import UiCheckbox from "../components/ui/Checkbox.vue";
import UiInput from "../components/ui/Input.vue";
import UiNumberField from "../components/ui/NumberField.vue";
import UiSelect from "../components/ui/Select.vue";
import UiLabel from "../components/ui/Label.vue";
import UiTextarea from "../components/ui/Textarea.vue";

interface TestCaseForm {
  input: string;
  output: string;
  score: number;
  sample: boolean;
}

const route = useRoute();
const router = useRouter();
const detail = ref<AdminProblemVersionDetail>();
const loading = ref(true);
const saving = ref(false);
const aiLoading = ref(false);
const tagText = ref("");
const aiRun = ref<AiRun>();
/** 启动 AI 时由管理员明确锁定的目标测试点数量。 */
const aiTestCaseCount = ref(10);
/** 差分门禁通过后是否自动发布当前版本。 */
const aiAutoPublish = ref(false);
/** 自动发布时从生成结果开头选作公开样例的数量。 */
const aiSampleCount = ref(0);
let aiTimer: number | undefined;
const form = reactive({
  title: "",
  school: "",
  year: new Date().getFullYear(),
  difficulty: "MEDIUM" as Difficulty,
  sourceUrl: "",
  statementMarkdown: "",
  timeLimitMs: 1000,
  memoryLimitMiB: 256,
  dataNotice: "",
  testCases: [] as TestCaseForm[],
});

const totalScore = computed(() => form.testCases.reduce((sum, item) => sum + Number(item.score || 0), 0));

/** AI 运行期间锁定草稿内容；人工接管状态允许继续编辑。 */
const aiLocked = computed(() => {
  const state = aiRun.value?.state;
  if (state === "PUBLISHED") return true;
  // 关闭自动发布时，差分门禁通过后停在 VALIDATING，管理员需要检查样例并手动发布。
  if (state === "VALIDATING" && aiRun.value && !aiRun.value.autoPublish) return false;
  if (!detail.value?.activeAiRun) return false;
  // 详情接口已确认存在运行，但状态请求还未返回时也必须保持锁定。
  if (!state) return true;
  return !["NEEDS_REVIEW", "FAILED", "CANCELED"].includes(state);
});
/** AI 运行是否已经进入不可继续的终态。 */
const aiTerminal = computed(() => Boolean(aiRun.value && ["PUBLISHED", "FAILED", "CANCELED"].includes(aiRun.value.state)));
/** 只有失败或取消的运行可以从同一草稿重新启动。 */
const aiCanRestart = computed(() => !aiRun.value || ["NEEDS_REVIEW", "FAILED", "CANCELED"].includes(aiRun.value.state));
/** 兼容 API 重启前的旧响应；旧运行没有 steps 时仍可查看状态。 */
const aiSteps = computed(() => aiRun.value?.steps ?? []);
/** 已通过真实沙箱差分并写入草稿的测试点。 */
const aiGeneratedTestCases = computed(() => aiRun.value?.generatedTestCases ?? []);
/** 页面时间线中的正常大状态顺序。 */
const majorStages: AiMajorState[] = [
  "DRAFT",
  "ANALYZING",
  "GENERATING_SOLUTIONS",
  "REVIEWING",
  "TESTS_GENERATING",
  "VALIDATING",
  "PASSING",
  "PUBLISHED",
];
const majorLabels: Record<AiMajorState, string> = {
  DRAFT: "草稿",
  ANALYZING: "分析题意",
  GENERATING_SOLUTIONS: "生成标程",
  REVIEWING: "审查标程",
  TESTS_GENERATING: "生成测试",
  VALIDATING: "校验门禁",
  PASSING: "门禁通过",
  PUBLISHED: "已发布",
  NEEDS_REVIEW: "人工接管",
  FAILED: "失败",
  CANCELED: "已取消",
};
const minorLabels: Record<string, string> = {
  DRAFT: "草稿",
  ANALYZING: "分析题意",
  GENERATING_SOLUTIONS: "生成独立标程",
  REVIEWING: "对抗审查",
  GENERATING_TESTS: "生成测试生成器",
  DIFFERENTIAL_TESTING: "差分测试",
  VALIDATING: "校验发布门禁",
  PUBLISHED: "已发布",
  NEEDS_REVIEW: "等待人工接管",
  FAILED: "流程失败",
  CANCELED: "流程已取消",
};
const roleLabels: Record<string, string> = {
  STATEMENT_ANALYST: "题意分析 Agent",
  SOLUTION_A: "标程 Agent A",
  SOLUTION_B: "标程 Agent B",
  TEST_DESIGNER: "测试设计 Agent",
  ADVERSARIAL_REVIEWER: "对抗审查 Agent",
  GENERATOR: "测试生成器 Agent",
  BRUTE_FORCE: "暴力校验 Agent",
};
const currentMajor = computed<AiMajorState>(() => aiRun.value?.majorState ?? "DRAFT");

/** 返回时间线节点当前的完成、进行中或等待状态。 */
function stageClass(stage: AiMajorState): string {
  const normalCurrent = majorStages.includes(currentMajor.value)
    ? currentMajor.value
    : [...(aiRun.value?.history ?? [])].reverse().find((entry) => majorStages.includes(entry.majorState))?.majorState ?? "DRAFT";
  const currentIndex = majorStages.indexOf(normalCurrent);
  const stageIndex = majorStages.indexOf(stage);
  if (stageIndex < currentIndex) return "done";
  if (stageIndex === currentIndex) return "current";
  return "pending";
}

/** 读取指定大状态最近一次变更，用于在时间线上展示时间和说明。 */
function latestHistory(stage: AiMajorState) {
  return [...(aiRun.value?.history ?? [])].reverse().find((entry) => entry.majorState === stage);
}

/** 将数据库中的原始模型 JSON 格式化，便于管理员排查结构化解析问题。 */
function formatAiResponse(step: AiStepResponse): string {
  if (!step.rawResponse) return step.response ? JSON.stringify(step.response, null, 2) : "模型没有返回内容";
  try {
    return JSON.stringify(JSON.parse(step.rawResponse), null, 2);
  } catch {
    return step.rawResponse;
  }
}

function addCase(): void {
  form.testCases.push({ input: "", output: "", score: 0, sample: false });
}

function removeCase(index: number): void {
  if (form.testCases.length === 1) {
    toast.warning("至少需要一个测试点");
    return;
  }
  form.testCases.splice(index, 1);
}

async function load(): Promise<void> {
  try {
    detail.value = await api.adminProblemVersion(String(route.params.versionId));
    if (detail.value.status !== "DRAFT") {
    toast.warning("只有草稿版本可以编辑");
      await router.replace("/admin/problems");
      return;
    }
    form.title = detail.value.title;
    form.school = detail.value.school;
    form.year = detail.value.year;
    form.difficulty = detail.value.difficulty;
    form.sourceUrl = detail.value.sourceUrl ?? "";
    form.statementMarkdown = detail.value.statementMarkdown;
    form.timeLimitMs = detail.value.timeLimitMs;
    form.memoryLimitMiB = detail.value.memoryLimitMiB;
    form.dataNotice = detail.value.dataNotice ?? "";
    tagText.value = detail.value.tags.join(", ");
    form.testCases = detail.value.testCases.map(({ input, output, score, sample }) => ({ input, output, score, sample }));
    await loadAiRun();
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "草稿加载失败");
    await router.replace("/admin/problems");
  } finally {
    loading.value = false;
  }
}

/** 按草稿版本恢复当前 AI 运行，避免刷新页面后丢失状态时间线。 */
async function loadAiRun(): Promise<void> {
  if (!detail.value) return;
  try {
    aiRun.value = await api.activeAiRun(detail.value.versionId);
    if (aiRun.value) {
      aiTestCaseCount.value = aiRun.value.requestedTestCaseCount;
      aiAutoPublish.value = aiRun.value.autoPublish;
      aiSampleCount.value = aiRun.value.requestedSampleCount;
    }
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "AI 状态加载失败");
  }
}

/** 刷新当前 AI 运行；没有运行时重新查询草稿绑定的运行。 */
async function refreshAi(): Promise<void> {
  if (!detail.value || aiLoading.value) return;
  aiLoading.value = true;
  try {
    const next = aiRun.value
      ? await api.aiRun(aiRun.value.id)
      : await api.activeAiRun(detail.value.versionId);
    const previousGeneratedCount = aiGeneratedTestCases.value.length;
    aiRun.value = next;
    if (next) {
      aiTestCaseCount.value = next.requestedTestCaseCount;
      aiAutoPublish.value = next.autoPublish;
      aiSampleCount.value = next.requestedSampleCount;
    }
    if (!next || ["PUBLISHED", "FAILED", "CANCELED"].includes(next.state)) detail.value.activeAiRun = false;
    // AI 写入测试点会改变草稿内容哈希；人工接管前重新加载，避免后续保存覆盖新数据。
    if (next && next.state !== "PUBLISHED" && next.generatedTestCases.length > previousGeneratedCount) await load();
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "AI 状态刷新失败");
  } finally {
    aiLoading.value = false;
  }
}

/** 从当前草稿编辑页启动 AI 流程。管理员应先保存当前草稿再启动。 */
async function startAi(): Promise<void> {
  if (!detail.value || aiLoading.value || !aiCanRestart.value) return;
  if (!Number.isInteger(aiTestCaseCount.value) || aiTestCaseCount.value < 1 || aiTestCaseCount.value > 200) {
      toast.warning("AI 生成测试点数量必须位于 1 到 200");
    return;
  }
  if (!Number.isInteger(aiSampleCount.value) || aiSampleCount.value < 0 || aiSampleCount.value > aiTestCaseCount.value) {
      toast.warning("公开样例数量必须位于 0 到生成测试点数量之间");
    return;
  }
  if (!aiAutoPublish.value && aiSampleCount.value > 0) {
      toast.warning("关闭自动发布时请将公开样例数量设为 0，生成后可在测试点列表中手动勾选");
    return;
  }
  if (form.testCases.length > 0) {
    try {
      await confirmAction(
        aiAutoPublish.value
          ? `当前草稿已有 ${form.testCases.length} 个测试点。AI 差分全部通过后，将用新生成的 ${aiTestCaseCount.value} 个测试点替换它们，并自动发布；前 ${aiSampleCount.value} 个测试点会作为公开样例。`
          : `当前草稿已有 ${form.testCases.length} 个测试点。AI 差分全部通过后，将用新生成的 ${aiTestCaseCount.value} 个测试点替换它们，但不会自动发布；你可以检查并手动勾选公开样例。` + "\n\n确认启动 AI 吗？",
      );
    } catch {
      return;
    }
  }
  aiLoading.value = true;
  try {
    aiRun.value = await api.startAiRun(detail.value.versionId, aiTestCaseCount.value, aiAutoPublish.value, aiSampleCount.value);
    detail.value.activeAiRun = true;
    toast.success("AI 录题流程已启动");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "AI 流程启动失败");
  } finally {
    aiLoading.value = false;
  }
}

/** 取消当前 AI 运行并恢复人工编辑。 */
async function cancelAi(): Promise<void> {
  if (!aiRun.value || !detail.value || aiLoading.value) return;
  try {
    await confirmAction("取消后可以继续手工编辑草稿，已保存的 AI 步骤仍会保留。\n\n确认取消 AI 流程吗？");
  } catch {
    return;
  }
  aiLoading.value = true;
  try {
    aiRun.value = await api.cancelAiRun(aiRun.value.id);
    detail.value.activeAiRun = false;
    toast.success("AI 流程已取消，可继续人工编辑");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "AI 流程取消失败");
  } finally {
    aiLoading.value = false;
  }
}

async function save(publish: boolean): Promise<void> {
  if (!detail.value || saving.value || aiLocked.value) return;
  if (publish && totalScore.value !== 100) {
    toast.error("发布时测试点分值之和必须为 100");
    return;
  }
  if (publish) {
    try {
      await confirmAction("发布后该版本将不可再编辑，并会成为这道题的当前公开版本。\n\n确认发布吗？");
    } catch {
      return;
    }
  }
  saving.value = true;
  try {
    await api.updateDraftProblem(detail.value.versionId, {
      expectedContentSha256: detail.value.contentSha256,
      ...form,
      sourceUrl: form.sourceUrl.trim() || null,
      dataNotice: form.dataNotice.trim() || null,
      tags: tagText.value.split(/[，,]/).map((tag) => tag.trim()).filter(Boolean),
      publish,
    });
    toast.success(publish ? "题目版本已发布" : "草稿已保存");
    if (publish) {
      await router.replace("/admin/problems");
    } else {
      await load();
    }
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "草稿保存失败");
  } finally {
    saving.value = false;
  }
}

onMounted(async () => {
  await load();
  aiTimer = window.setInterval(() => void refreshAi(), 5_000);
});

onUnmounted(() => {
  if (aiTimer !== undefined) window.clearInterval(aiTimer);
});
</script>

<template>
  <section class="content-page content-page--modern oj-page admin-problem-page loading-shell" :aria-busy="loading">
    <div v-if="loading" class="loading-overlay"><span class="loading-spinner" aria-label="加载中" /></div>
    <div class="page-heading">
      <div><h1>编辑题目草稿</h1><p>版本 v{{ detail?.versionNumber }} · 外部题目标识 {{ detail?.externalKey || '手工题目' }}（只读）· 第二阶段录入测试点</p></div>
      <UiButton variant="ghost" @click="router.push('/admin/problems')"><ArrowLeft :size="16" />返回题库</UiButton>
    </div>

    <section class="ai-flow-panel">
      <header class="ai-flow-header">
        <div><h2><Bot :size="19" />AI 录题流程</h2><p v-if="aiRun">状态：{{ majorLabels[aiRun.majorState] }}<template v-if="aiRun.majorState !== aiRun.state"> · {{ minorLabels[aiRun.state] ?? aiRun.state }}</template></p><p v-else>人工录题、批量导入和 AI 都从当前草稿继续。</p></div>
        <span v-if="aiRun" class="ai-flow-run-id">{{ aiRun.id }}</span>
      </header>
      <div class="ai-flow-timeline" aria-label="AI 录题状态时间线">
        <article v-for="stage in majorStages" :key="stage" :class="['ai-flow-step', 'ai-flow-step--' + stageClass(stage)]">
          <span class="ai-flow-dot" aria-hidden="true" />
          <div><strong>{{ majorLabels[stage] }}</strong><small v-if="stage === currentMajor && aiRun && aiRun.majorState !== aiRun.state">{{ minorLabels[aiRun.state] ?? aiRun.state }}</small><time v-if="latestHistory(stage)">{{ new Date(latestHistory(stage)!.createdAt).toLocaleString() }}</time></div>
        </article>
      </div>
      <UiAlert v-if="aiRun && ['NEEDS_REVIEW', 'FAILED', 'CANCELED'].includes(aiRun.state)" :variant="aiRun.state === 'NEEDS_REVIEW' ? 'warning' : 'error'" :title="aiRun.failureReason || majorLabels[aiRun.majorState]" />
      <UiAlert v-if="aiRun && aiRun.state === 'VALIDATING' && !aiRun.autoPublish" variant="success" title="AI 测试点已生成并通过门禁。请检查下方测试点，勾选公开样例后保存并发布；当前不会自动发布。" />
      <footer class="ai-flow-actions">
        <label v-if="aiCanRestart" class="ai-case-count-control">
          <span>生成测试点数量</span>
          <UiNumberField v-model="aiTestCaseCount" :min="1" :max="200" :step="1" />
        </label>
        <label v-if="aiCanRestart" class="ai-case-count-control ai-publish-control">
          <span class="checkbox-field"><UiCheckbox v-model="aiAutoPublish" />差分通过后自动发布</span>
        </label>
        <label v-if="aiCanRestart && aiAutoPublish" class="ai-case-count-control">
          <span>公开样例数量</span>
          <UiNumberField v-model="aiSampleCount" :min="0" :max="aiTestCaseCount" :step="1" />
        </label>
        <span v-else-if="aiRun" class="ai-case-count-summary">计划 {{ aiRun.requestedTestCaseCount }} 个 · 已生成 {{ aiGeneratedTestCases.length }} 个 · {{ aiRun.autoPublish ? `自动发布 · 样例 ${aiRun.requestedSampleCount} 个` : "人工检查后发布" }}</span>
        <UiButton v-if="aiCanRestart" :loading="aiLoading" @click="startAi"><Bot :size="16" />启动 AI</UiButton>
        <UiButton v-if="aiRun && !aiTerminal" :loading="aiLoading" @click="cancelAi"><XCircle :size="16" />取消流程</UiButton>
        <UiButton v-if="aiRun" :loading="aiLoading" @click="refreshAi"><RefreshCw :size="16" />刷新状态</UiButton>
      </footer>
    </section>

    <section v-if="aiRun" class="ai-response-panel">
      <header class="ai-response-header">
        <div><h2>AI 返回</h2><p>每个已完成步骤的结构化结果都会保存，可展开查看原始 JSON。</p></div>
        <strong>{{ aiGeneratedTestCases.length }} / {{ aiRun.requestedTestCaseCount }} 个测试点 · {{ aiSteps.length }} 步</strong>
      </header>
      <section class="ai-generated-cases">
        <header><strong>沙箱生成结果</strong><span>计划 {{ aiRun.requestedTestCaseCount }} 个，已生成 {{ aiGeneratedTestCases.length }} 个</span></header>
        <p v-if="aiGeneratedTestCases.length === 0" class="ai-response-empty">生成器、输入校验和差分尚未全部通过，当前没有测试点写入草稿。</p>
        <div v-else class="ai-generated-case-list">
          <details v-for="testCase in aiGeneratedTestCases" :key="testCase.ordinal" class="ai-response-item">
            <summary><span><strong>测试点 {{ testCase.ordinal }}</strong><small>种子 {{ testCase.seed }}<template v-if="testCase.sample"> · 公开样例</template></small></span><span>{{ testCase.score }} 分</span></summary>
            <div class="ai-generated-case-content">
              <div><strong>输入</strong><pre>{{ testCase.input }}</pre></div>
              <div><strong>标准输出</strong><pre>{{ testCase.output }}</pre></div>
            </div>
          </details>
        </div>
      </section>
      <p v-if="aiSteps.length === 0" class="ai-response-empty">当前还没有收到 Agent 返回；如果流程失败，请查看上方错误原因。</p>
      <div v-else class="ai-response-list">
        <details v-for="(step, index) in aiSteps" :key="step.id" class="ai-response-item" :open="index === aiSteps.length - 1">
          <summary>
            <span><strong>{{ roleLabels[step.role] ?? step.role }}</strong><small>{{ minorLabels[step.state] ?? step.state }}</small></span>
            <time v-if="step.finishedAt">{{ new Date(step.finishedAt).toLocaleString() }}</time>
          </summary>
          <div class="ai-response-content">
            <p v-if="step.response?.summary" class="ai-response-summary">{{ step.response.summary }}</p>
            <div v-if="step.response?.ambiguities?.length" class="ai-response-box ai-response-box--warning"><strong>题意歧义</strong><ul><li v-for="item in step.response.ambiguities" :key="item">{{ item }}</li></ul></div>
            <div v-if="step.response?.findings?.length" class="ai-response-box ai-response-box--warning"><strong>审查发现</strong><ul><li v-for="item in step.response.findings" :key="item">{{ item }}</li></ul></div>
            <div v-if="step.response?.testPlan?.length" class="ai-response-box"><strong>测试计划</strong><ul><li v-for="item in step.response.testPlan" :key="item">{{ item }}</li></ul></div>
            <div v-if="step.response?.seeds?.length" class="ai-response-box"><strong>固定种子</strong><span>{{ step.response.seeds.join(', ') }}</span></div>
            <div v-if="step.response?.sourceCode" class="ai-response-box"><strong>候选标程</strong><pre class="ai-response-code">{{ step.response.sourceCode }}</pre></div>
            <div v-if="step.response?.generatorSource" class="ai-response-box"><strong>生成器源码</strong><pre class="ai-response-code">{{ step.response.generatorSource }}</pre></div>
            <div v-if="step.response?.validatorSource" class="ai-response-box"><strong>校验器源码</strong><pre class="ai-response-code">{{ step.response.validatorSource }}</pre></div>
            <p v-if="step.rawResponse && !step.response" class="ai-response-error">返回没有匹配预期结构，已保留原始模型内容。</p>
            <p v-else-if="!step.rawResponse" class="ai-response-error">该步骤没有写入模型返回。</p>
            <p v-if="step.failureReason" class="ai-response-error">{{ step.failureReason }}</p>
            <details v-if="step.rawResponse" class="ai-response-raw"><summary>查看原始模型返回</summary><pre>{{ formatAiResponse(step) }}</pre></details>
            <small v-if="step.contentSha256" class="ai-response-hash">返回哈希：{{ step.contentSha256 }}</small>
          </div>
        </details>
      </div>
    </section>

    <UiAlert v-if="aiLocked" variant="warning" title="该草稿存在进行中的 AI 流程，内容暂时锁定；流程结束或取消后可继续编辑。" />
    <UiAlert v-else-if="detail && form.testCases.length === 0" variant="info" title="当前草稿还没有测试点；请添加测试点并保存，分值合计 100 后才能发布。" />
    <form class="problem-form" @submit.prevent="save(false)">
      <section class="form-section">
        <h2>题目元数据</h2>
        <div class="form-grid form-grid--three">
          <div class="form-field"><UiLabel>外部题目标识</UiLabel><UiInput :model-value="detail?.externalKey || '手工题目'" disabled /></div>
          <div class="form-field"><UiLabel>学校</UiLabel><UiInput v-model="form.school" maxlength="200" :disabled="aiLocked" /></div>
          <div class="form-field"><UiLabel>年份</UiLabel><UiNumberField v-model="form.year" :min="1900" :max="2200" :disabled="aiLocked" /></div>
        </div>
        <div class="form-field"><UiLabel>标题</UiLabel><UiInput v-model="form.title" maxlength="200" :disabled="aiLocked" /></div>
        <div class="form-grid form-grid--three">
          <div class="form-field"><UiLabel>难度</UiLabel><UiSelect v-model="form.difficulty" placeholder="" :disabled="aiLocked"><option value="EASY">基础</option><option value="MEDIUM">综合</option><option value="HARD">高难</option></UiSelect></div>
          <div class="form-field"><UiLabel>标签（逗号分隔）</UiLabel><UiInput v-model="tagText" :disabled="aiLocked" /></div>
          <div class="form-field"><UiLabel>来源链接</UiLabel><UiInput v-model="form.sourceUrl" placeholder="https://..." :disabled="aiLocked" /></div>
        </div>
      </section>

      <section class="form-section">
        <h2>题面与限制</h2>
        <div class="form-field statement-form-item"><UiLabel>题面内容</UiLabel><ProblemStatementEditor v-model="form.statementMarkdown" :disabled="aiLocked" /></div>
        <div class="form-grid form-grid--three">
          <div class="form-field"><UiLabel>基准时间限制（ms）</UiLabel><UiNumberField v-model="form.timeLimitMs" :min="100" :max="60000" :step="100" :disabled="aiLocked" /></div>
          <div class="form-field"><UiLabel>基准内存限制（MiB）</UiLabel><UiNumberField v-model="form.memoryLimitMiB" :min="16" :max="2048" :step="16" :disabled="aiLocked" /></div>
          <div class="form-field"><UiLabel>数据声明</UiLabel><UiInput v-model="form.dataNotice" maxlength="200" :disabled="aiLocked" /></div>
        </div>
      </section>

      <section class="form-section">
        <header class="section-heading"><div><h2>测试点</h2><p :class="{ 'score-invalid': totalScore !== 100 }">总分 {{ totalScore }} / 100</p></div><UiButton :disabled="aiLocked" @click="addCase"><Plus :size="16" />添加测试点</UiButton></header>
        <div class="test-case-editor">
          <article v-for="(item, index) in form.testCases" :key="index" class="test-case-card">
            <header><strong>测试点 {{ index + 1 }}</strong><button class="icon-button" type="button" title="删除测试点" :disabled="aiLocked" @click="removeCase(index)"><Trash2 :size="17" /></button></header>
            <div class="test-case-columns"><div class="form-field"><UiLabel>输入</UiLabel><UiTextarea v-model="item.input" :rows="5" :disabled="aiLocked" /></div><div class="form-field"><UiLabel>标准输出</UiLabel><UiTextarea v-model="item.output" :rows="5" :disabled="aiLocked" /></div></div>
            <footer><label class="checkbox-field"><UiCheckbox v-model="item.sample" :disabled="aiLocked" />公开样例</label><div class="form-field"><UiLabel>分值</UiLabel><UiNumberField v-model="item.score" :min="0" :max="100" :disabled="aiLocked" /></div></footer>
          </article>
        </div>
      </section>

      <footer class="form-actions">
        <UiButton :disabled="aiLocked" :loading="saving" @click="save(false)"><Save :size="16" />保存草稿</UiButton>
        <UiButton :disabled="aiLocked" :loading="saving" @click="save(true)"><Send :size="16" />保存并发布</UiButton>
      </footer>
    </form>
  </section>
</template>
