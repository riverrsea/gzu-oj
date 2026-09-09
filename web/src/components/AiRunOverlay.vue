<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Bot, CheckCircle2, LoaderCircle, RefreshCw, X, XCircle } from "@lucide/vue";
import { toast } from "../lib/notify";
import { formatChinaDateTime } from "../lib/time";
import type { AiMajorState, AiRun, AiStepResponse } from "../api/types";
import UiAlert from "./ui/Alert.vue";
import UiButton from "./ui/Button.vue";
import UiCheckbox from "./ui/Checkbox.vue";
import UiLabel from "./ui/Label.vue";
import UiNumberField from "./ui/NumberField.vue";
import UiTextarea from "./ui/Textarea.vue";

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
  STATEMENT_ANALYST: "题意分析",
  SOLUTION_A: "标程 A",
  SOLUTION_B: "标程 B",
  TEST_DESIGNER: "测试设计",
  ADVERSARIAL_REVIEWER: "对抗审查",
  GENERATOR: "测试生成器",
  BRUTE_FORCE: "暴力校验",
};

const props = defineProps<{
  /** 是否显示遮罩。 */
  open: boolean;
  /** 当前 AI 运行；没有运行时为 null。 */
  run: AiRun | null;
  /** 启动/取消/刷新等操作的加载态。 */
  loading?: boolean;
}>();
const emit = defineEmits<{
  (e: "close"): void;
  (e: "start", config: { testCaseCount: number; autoPublish: boolean; sampleCount: number }): void;
  (e: "cancel"): void;
  (e: "refresh"): void;
  (e: "resume", payload: { action: "reanalyze" | "rereview"; correction: unknown }): void;
}>();

const steps = computed(() => props.run?.steps ?? []);
const generatedTestCases = computed(() => props.run?.generatedTestCases ?? []);
const run = computed(() => props.run);

/** AI 运行是否已进入不可继续的终态。 */
const isTerminal = computed(() => Boolean(run.value && ["PUBLISHED", "FAILED", "CANCELED"].includes(run.value.state)));
/** 只有没有运行、或失败/取消/人工接管的运行可以重新启动。 */
const canRestart = computed(() => !run.value || ["NEEDS_REVIEW", "FAILED", "CANCELED"].includes(run.value.state));
/** 人工接管状态下允许关闭遮罩去编辑草稿。 */
const canCloseForDraft = computed(() => run.value?.state === "NEEDS_REVIEW");

/** 由可恢复失败阶段映射出的恢复动作；不可恢复时为 null。 */
const resumeAction = computed<"reanalyze" | "rereview" | null>(() => {
  const target = run.value?.resumeTarget;
  if (target === "ANALYZING") return "reanalyze";
  if (target === "REVIEWING") return "rereview";
  return null;
});

const currentMajor = computed<AiMajorState>(() => run.value?.majorState ?? "DRAFT");
const currentIndex = computed(() => {
  if (majorStages.includes(currentMajor.value)) return majorStages.indexOf(currentMajor.value);
  const hist = [...(run.value?.history ?? [])].reverse().find((entry) => majorStages.includes(entry.majorState));
  return majorStages.indexOf(hist?.majorState ?? "DRAFT");
});
const progressPercent = computed(() => Math.round((currentIndex.value / (majorStages.length - 1)) * 100));

/** 启动配置（无运行或可重启时使用），并随 run 变化同步。 */
const testCaseCount = ref(10);
const autoPublish = ref(false);
const sampleCount = ref(0);
watch(run, (value) => {
  if (value) {
    testCaseCount.value = value.requestedTestCaseCount;
    autoPublish.value = value.autoPublish;
    sampleCount.value = value.requestedSampleCount;
  }
});

/** 人工接管修改区的预填内容。 */
const correction = ref("{}");

/** 预填修改内容时最相关的失败步骤（优先匹配失败阶段，否则取最新一步）。 */
function failedStep(): AiStepResponse | undefined {
  const target = run.value?.resumeTarget;
  if (target && steps.value.length) {
    const match = [...steps.value].reverse().find((step) => step.state === target);
    if (match) return match;
  }
  return steps.value.length ? steps.value[steps.value.length - 1] : undefined;
}

/** 用失败步骤的模型返回预填修改区。 */
function prefillCorrection(): void {
  const step = failedStep();
  if (!step) {
    correction.value = "{}";
    return;
  }
  if (step.rawResponse) {
    try {
      correction.value = JSON.stringify(JSON.parse(step.rawResponse), null, 2);
      return;
    } catch {
      correction.value = step.rawResponse;
      return;
    }
  }
  if (step.response) {
    correction.value = JSON.stringify(step.response, null, 2);
    return;
  }
  correction.value = "{}";
}

// 进入可恢复的人工接管状态时预填修改区。
watch(resumeAction, (action) => {
  if (action) prefillCorrection();
});

/** 步骤点状态：完成/进行中/等待。 */
function stepState(stage: AiMajorState): string {
  const stageIndex = majorStages.indexOf(stage);
  if (stageIndex < currentIndex.value) return "done";
  if (stageIndex === currentIndex.value) return "current";
  return "pending";
}

/** 将原始模型 JSON 格式化，便于排查结构化解析问题。 */
function formatAiResponse(step: AiStepResponse): string {
  if (!step.rawResponse) return step.response ? JSON.stringify(step.response, null, 2) : "模型没有返回内容";
  try {
    return JSON.stringify(JSON.parse(step.rawResponse), null, 2);
  } catch {
    return step.rawResponse;
  }
}

function submitStart(): void {
  emit("start", { testCaseCount: testCaseCount.value, autoPublish: autoPublish.value, sampleCount: sampleCount.value });
}

function submitResume(): void {
  const action = resumeAction.value;
  if (!action) return;
  let parsed: unknown = {};
  const text = correction.value.trim();
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch {
      toast.error("修改内容不是合法 JSON，请检查格式");
      return;
    }
  }
  emit("resume", { action, correction: parsed });
}

// 点击遮罩背景：仅人工接管时允许关闭。
function onBackdrop(): void {
  if (canCloseForDraft.value) emit("close");
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="ai-overlay" role="dialog" aria-modal="true">
      <div class="ai-overlay-backdrop" @click="onBackdrop" />
      <div class="ai-overlay-panel">
        <header class="ai-overlay-head">
          <div class="ai-overlay-title"><Bot :size="20" /><div><h2>AI 生成测试点</h2><p>题目版本：{{ run?.problemVersionId ?? "尚未启动" }}</p></div></div>
          <div class="ai-overlay-head-actions">
            <span v-if="run" class="ai-overlay-run-id">{{ run.id }}</span>
            <button class="icon-button" type="button" title="关闭" @click="emit('close')"><X :size="18" /></button>
          </div>
        </header>

        <!-- 有运行：状态 + 进度 + 结果 -->
        <template v-if="run">
          <div class="ai-overlay-status">
            <span class="ai-overlay-state">
              {{ majorLabels[run.majorState] }}<template v-if="run.majorState !== run.state"> · {{ minorLabels[run.state] ?? run.state }}</template>
              <LoaderCircle v-if="!isTerminal && run.state !== 'NEEDS_REVIEW'" class="ai-overlay-spin" :size="15" />
            </span>
            <UiAlert v-if="['NEEDS_REVIEW', 'FAILED', 'CANCELED'].includes(run.state)" :variant="run.state === 'NEEDS_REVIEW' ? 'warning' : 'error'" :title="run.failureReason || majorLabels[run.majorState]" />
            <UiAlert v-if="run.state === 'VALIDATING' && !run.autoPublish" variant="success" title="差分已通过，请在下方确认测试点与公开样例后保存/发布。" />
          </div>

          <!-- 进度条 + 步骤点 -->
          <div class="ai-progress">
            <div class="ai-progress-track">
              <template v-for="(stage, i) in majorStages" :key="stage">
                <div class="ai-progress-step" :class="`ai-progress-step--${stepState(stage)}`">
                  <span class="ai-progress-dot">
                    <CheckCircle2 v-if="stepState(stage) === 'done'" :size="14" />
                    <span v-else>{{ i + 1 }}</span>
                  </span>
                  <span class="ai-progress-label">{{ majorLabels[stage] }}</span>
                </div>
                <div v-if="i < majorStages.length - 1" class="ai-progress-seg" :class="{ 'ai-progress-seg--fill': i < currentIndex }" />
              </template>
            </div>
            <div class="ai-progress-meta"><span>阶段 {{ currentIndex + 1 }} / {{ majorStages.length }}</span><span>{{ progressPercent }}%</span></div>
          </div>

          <!-- 生成的测试点 -->
          <section class="ai-overlay-section">
            <header class="ai-overlay-section-head"><strong>生成的测试点</strong><span>{{ generatedTestCases.length }} / {{ run.requestedTestCaseCount }} 个</span></header>
            <p v-if="generatedTestCases.length === 0" class="ai-overlay-empty">尚未生成通过校验的测试点。</p>
            <div v-else class="ai-case-list">
              <details v-for="tc in generatedTestCases" :key="tc.ordinal" class="ai-overlay-item">
                <summary><strong>测试点 {{ tc.ordinal }}</strong><small>种子 {{ tc.seed }} · {{ tc.score }} 分<template v-if="tc.sample"> · 公开样例</template></small></summary>
                <div class="ai-overlay-item-cols"><div><strong>输入</strong><pre>{{ tc.input }}</pre></div><div><strong>标准输出</strong><pre>{{ tc.output }}</pre></div></div>
              </details>
            </div>
          </section>

          <!-- Agent 各角色返回：按角色分开，不挤在一起 -->
          <section class="ai-overlay-section">
            <header class="ai-overlay-section-head"><strong>Agent 步骤返回</strong><span>{{ steps.length }} 步</span></header>
            <p v-if="steps.length === 0" class="ai-overlay-empty">还没有收到 Agent 返回；如果流程失败，请查看上方错误原因。</p>
            <div v-else class="ai-step-list">
              <details v-for="(step, index) in steps" :key="step.id" class="ai-overlay-item ai-step-card" :open="index === steps.length - 1">
                <summary>
                  <span><strong>{{ roleLabels[step.role] ?? step.role }}</strong><small>{{ minorLabels[step.state] ?? step.state }}</small></span>
                  <time v-if="step.finishedAt">{{ formatChinaDateTime(step.finishedAt, { dateStyle: "medium", timeStyle: "short" }) }}</time>
                </summary>
                <div class="ai-step-body">
                  <p v-if="step.response?.summary" class="ai-step-summary">{{ step.response.summary }}</p>
                  <div v-if="step.response?.ambiguities?.length" class="ai-response-box ai-response-box--warning"><strong>题意歧义</strong><ul><li v-for="item in step.response.ambiguities" :key="item">{{ item }}</li></ul></div>
                  <div v-if="step.response?.findings?.length" class="ai-response-box ai-response-box--warning"><strong>审查发现</strong><ul><li v-for="item in step.response.findings" :key="item">{{ item }}</li></ul></div>
                  <div v-if="step.response?.testPlan?.length" class="ai-response-box"><strong>测试计划</strong><ul><li v-for="item in step.response.testPlan" :key="item">{{ item }}</li></ul></div>
                  <div v-if="step.response?.seeds?.length" class="ai-response-box"><strong>固定种子</strong><span>{{ step.response.seeds.join(", ") }}</span></div>
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

          <!-- 人工接管：修改模型返回并恢复 -->
          <section v-if="run.state === 'NEEDS_REVIEW'" class="ai-overlay-section ai-overlay-resume">
            <header class="ai-overlay-section-head"><strong>人工接管：修改模型返回并恢复</strong></header>
            <p v-if="resumeAction" class="ai-overlay-hint">当前失败阶段：<code>{{ run.resumeTarget }}</code>（{{ resumeAction === "reanalyze" ? "重新分析题意" : "重新对抗审查" }}）。下方已预填失败步骤的模型返回，可直接修改后回传重跑。</p>
            <p v-else class="ai-overlay-hint ai-overlay-hint--blocked">当前失败阶段不支持自动恢复（通常为沙箱校验或基础设施连续失败），需人工处理后重新启动。</p>
            <template v-if="resumeAction">
              <div class="form-field"><UiLabel>修改后的结构化内容（JSON）</UiLabel><UiTextarea v-model="correction" :rows="8" /></div>
              <div class="ai-overlay-actions"><UiButton type="button" :loading="loading" @click="submitResume"><Bot :size="16" />保存修改并恢复</UiButton></div>
            </template>
          </section>

          <footer class="ai-overlay-actions">
            <UiButton v-if="!isTerminal" :loading="loading" variant="destructive" @click="emit('cancel')"><XCircle :size="16" />取消流程</UiButton>
            <UiButton v-if="canRestart" :loading="loading" @click="submitStart"><Bot :size="16" />重新启动</UiButton>
            <UiButton :loading="loading" variant="ghost" @click="emit('refresh')"><RefreshCw :size="16" />刷新状态</UiButton>
            <UiButton v-if="isTerminal || canCloseForDraft" variant="outline" @click="emit('close')">{{ isTerminal ? "完成" : "关闭（去编辑草稿）" }}</UiButton>
          </footer>
        </template>

        <!-- 没有运行：启动配置 -->
        <template v-else>
          <section class="ai-overlay-start">
            <header class="ai-overlay-section-head"><strong>生成测试点</strong><span>配置本次 AI 流程</span></header>
            <p class="ai-overlay-hint">AI 会分析题意、生成两份独立标程并差分，最后写入通过校验的测试点。</p>
            <div class="form-field"><UiLabel>生成测试点数量</UiLabel><UiNumberField v-model="testCaseCount" :min="1" :max="200" :step="1" /></div>
            <label class="checkbox-field"><UiCheckbox v-model="autoPublish" />差分通过后自动发布</label>
            <div v-if="autoPublish" class="form-field"><UiLabel>公开样例数量</UiLabel><UiNumberField v-model="sampleCount" :min="0" :max="testCaseCount" :step="1" /></div>
            <div class="ai-overlay-actions"><UiButton type="button" :loading="loading" @click="submitStart"><Bot :size="16" />启动 AI</UiButton></div>
          </section>
        </template>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.ai-overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 3rem 1.5rem;
}
.ai-overlay-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(15, 18, 24, 0.55);
  backdrop-filter: blur(2px);
}
.ai-overlay-panel {
  position: relative;
  width: min(76rem, 100%);
  max-height: 88vh;
  overflow: auto;
  background: var(--surface-1, #fff);
  border: 1px solid var(--border-1, #e5e7eb);
  border-radius: 1rem;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  padding: 1.5rem 1.75rem;
}
.ai-overlay-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1rem;
}
.ai-overlay-title {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  font-weight: 700;
}
.ai-overlay-title h2 {
  font-size: 1.1rem;
}
.ai-overlay-title p {
  font-size: 0.8rem;
  color: var(--text-2, #6b7280);
}
.ai-overlay-head-actions {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}
.ai-overlay-run-id {
  font-size: 0.75rem;
  color: var(--text-2, #6b7280);
  font-variant-numeric: tabular-nums;
}
.ai-overlay-status {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
  margin-bottom: 1rem;
}
.ai-overlay-state {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-weight: 600;
}
.ai-overlay-spin {
  animation: ai-spin 1s linear infinite;
  color: var(--text-2, #6b7280);
}
@keyframes ai-spin {
  to {
    transform: rotate(360deg);
  }
}
.ai-progress {
  margin-bottom: 1.25rem;
}
.ai-progress-track {
  display: flex;
  align-items: flex-start;
  gap: 0.25rem;
}
.ai-progress-step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.35rem;
  width: 4.5rem;
  min-width: 4.5rem;
}
.ai-progress-dot {
  display: grid;
  place-items: center;
  width: 1.6rem;
  height: 1.6rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 700;
  background: var(--surface-2, #f3f4f6);
  color: var(--text-2, #6b7280);
  border: 1px solid var(--border-1, #e5e7eb);
}
.ai-progress-step--done .ai-progress-dot {
  background: var(--success, #16a34a);
  color: #fff;
  border-color: transparent;
}
.ai-progress-step--current .ai-progress-dot {
  background: var(--brand, #2563eb);
  color: #fff;
  border-color: transparent;
}
.ai-progress-label {
  font-size: 0.7rem;
  font-weight: 600;
  color: var(--text-2, #6b7280);
  text-align: center;
}
.ai-progress-step--current .ai-progress-label {
  color: var(--brand, #2563eb);
}
.ai-progress-seg {
  flex: 1;
  height: 2px;
  margin-top: 0.8rem;
  background: var(--surface-2, #e5e7eb);
  border-radius: 999px;
}
.ai-progress-seg--fill {
  background: var(--brand, #2563eb);
}
.ai-progress-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 0.5rem;
  font-size: 0.75rem;
  color: var(--text-2, #6b7280);
  font-variant-numeric: tabular-nums;
}
.ai-overlay-section {
  margin-bottom: 1.25rem;
}
.ai-overlay-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  font-weight: 600;
  margin-bottom: 0.6rem;
}
.ai-overlay-section-head span {
  font-size: 0.75rem;
  font-weight: 500;
  color: var(--text-2, #6b7280);
}
.ai-overlay-empty {
  color: var(--text-2, #6b7280);
  font-size: 0.9rem;
  padding: 0.75rem;
  background: var(--surface-2, #f3f4f6);
  border-radius: 0.5rem;
}
.ai-case-list,
.ai-step-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
.ai-overlay-item {
  border: 1px solid var(--border-1, #e5e7eb);
  border-radius: 0.5rem;
  padding: 0.5rem 0.75rem;
}
.ai-overlay-item summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  cursor: pointer;
  list-style: none;
}
.ai-overlay-item summary::-webkit-details-marker {
  display: none;
}
.ai-overlay-item summary strong {
  font-size: 0.85rem;
}
.ai-overlay-item summary small {
  font-size: 0.7rem;
  color: var(--text-2, #6b7280);
}
.ai-overlay-item-cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.75rem;
  margin-top: 0.5rem;
}
.ai-overlay-item-cols pre,
.ai-response-code,
.ai-response-raw pre {
  margin: 0.25rem 0 0;
  padding: 0.5rem;
  background: var(--surface-2, #f3f4f6);
  border-radius: 0.4rem;
  font-size: 0.72rem;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 12rem;
  overflow: auto;
}
.ai-step-body {
  margin-top: 0.5rem;
}
.ai-step-summary {
  font-size: 0.85rem;
  margin-bottom: 0.5rem;
}
.ai-response-box {
  margin: 0.5rem 0;
}
.ai-response-box strong {
  font-size: 0.75rem;
  color: var(--text-2, #6b7280);
}
.ai-response-box--warning strong {
  color: var(--warning, #d97706);
}
.ai-response-box ul {
  margin: 0.25rem 0 0;
  padding-left: 1.1rem;
}
.ai-response-error {
  color: var(--danger, #dc2626);
  font-size: 0.8rem;
  margin: 0.4rem 0;
}
.ai-response-hash {
  color: var(--text-2, #6b7280);
  font-size: 0.7rem;
}
.ai-response-raw {
  margin-top: 0.5rem;
}
.ai-response-raw summary {
  cursor: pointer;
  font-size: 0.75rem;
  color: var(--text-2, #6b7280);
}
.ai-overlay-hint {
  color: var(--text-2, #6b7280);
  font-size: 0.85rem;
  margin-bottom: 0.6rem;
}
.ai-overlay-hint code {
  background: var(--surface-2, #f3f4f6);
  padding: 0.1rem 0.35rem;
  border-radius: 0.25rem;
}
.ai-overlay-hint--blocked {
  color: var(--danger, #dc2626);
}
.ai-overlay-resume {
  border-top: 1px solid var(--border-1, #e5e7eb);
  padding-top: 1rem;
}
.ai-overlay-start {
  max-width: 26rem;
}
.ai-overlay-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-top: 1rem;
}
</style>
