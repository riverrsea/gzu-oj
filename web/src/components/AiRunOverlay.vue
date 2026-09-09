<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Bot, CheckCircle2, LoaderCircle, RefreshCw, X, XCircle } from "@lucide/vue";
import { formatChinaDateTime } from "../lib/time";
import type { AiMajorState, AiRun, AiStepResponse } from "../api/types";
import UiAlert from "./ui/Alert.vue";
import UiBadge from "./ui/Badge.vue";
import UiButton from "./ui/Button.vue";
import UiCard from "./ui/Card.vue";
import UiCheckbox from "./ui/Checkbox.vue";
import UiInput from "./ui/Input.vue";
import UiLabel from "./ui/Label.vue";
import UiNumberField from "./ui/NumberField.vue";

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

/** Agent 角色顺序与中文标签。 */
const roleOrder = ["analyze", "solutions", "design", "review", "artifacts"] as const;
const roleLabels: Record<string, string> = {
  analyze: "题意分析",
  solutions: "标程生成",
  design: "测试设计",
  review: "对抗审查",
  artifacts: "生成器",
};

/** 步骤返回字段的展示标签（Agent 以 model_dump() 回传，键为 snake_case）。 */
const fieldLabels: Record<string, string> = {
  summary: "题意概述",
  constraints: "约束",
  ambiguities: "歧义",
  findings: "审查发现",
  test_plan: "测试计划",
  seeds: "固定种子",
  source_code: "源码",
  solution_a: "标程 A",
  solution_b: "标程 B",
  generator_source: "生成器源码",
  validator_source: "校验器源码",
  brute_force_source: "暴力解源码",
};

const props = defineProps<{
  open: boolean;
  run: AiRun | null;
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

/** 当前正在执行的步骤角色（由运行小状态推断，仅用于运行中提示）。 */
const currentRole = computed(() => {
  const state = run.value?.state;
  if (state === "ANALYZING") return "analyze";
  if (state === "GENERATING_SOLUTIONS") return "solutions";
  if (state === "REVIEWING") return "review";
  if (state === "GENERATING_TESTS") return "artifacts";
  return null;
});
/** 运行是否处于活动状态（非终态且非人工接管）。 */
const running = computed(() => Boolean(run.value && !isTerminal.value && run.value.state !== "NEEDS_REVIEW"));

/** 每个角色按 role 取最新一条返回（UPSERT 后通常只有一条）。 */
function stepForRole(role: string): AiStepResponse | undefined {
  return steps.value.find((step) => step.role === role);
}
const stepTabs = computed(() =>
  roleOrder.map((role) => ({ role, label: roleLabels[role] ?? role, step: stepForRole(role) })),
);
/** 当前选中的步骤角色。 */
const selectedRole = ref<string>("analyze");
watch(
  () => steps.value.length,
  () => {
    const last = steps.value.length ? steps.value[steps.value.length - 1] : undefined;
    if (last) selectedRole.value = last.role;
  },
);
const selectedStep = computed(() => stepTabs.value.find((tab) => tab.role === selectedRole.value)?.step ?? null);

/** 是否正在重新执行当前选中的步骤角色。 */
const selectedRunning = computed(() => running.value && currentRole.value === selectedRole.value);

/** 把一条步骤的结构化返回展开成便于结构化展示的条目。 */
interface DisplayItem {
  label: string;
  kind: "text" | "list" | "code" | "scalar";
  value: string | string[];
}
function isCodeKey(key: string): boolean {
  return /source|code/i.test(key);
}
function fieldLabel(key: string): string {
  return fieldLabels[key] ?? key;
}
function flattenResponse(obj: Record<string, unknown>, prefix = ""): DisplayItem[] {
  const items: DisplayItem[] = [];
  for (const [key, value] of Object.entries(obj)) {
    const label = prefix ? `${prefix} · ${fieldLabel(key)}` : fieldLabel(key);
    if (typeof value === "string") {
      items.push({ label, kind: isCodeKey(key) ? "code" : "text", value });
    } else if (Array.isArray(value)) {
      items.push({ label, kind: "list", value: value.map(String) });
    } else if (value && typeof value === "object") {
      items.push(...flattenResponse(value as Record<string, unknown>, label));
    } else if (value !== null && value !== undefined) {
      items.push({ label, kind: "scalar", value: String(value) });
    }
  }
  return items;
}
function responseItems(step: AiStepResponse | null): DisplayItem[] {
  if (!step?.response) return [];
  return flattenResponse(step.response as unknown as Record<string, unknown>);
}

/** 进度步骤点状态。 */
function stepState(stage: AiMajorState): string {
  const stageIndex = majorStages.indexOf(stage);
  if (stageIndex < currentIndex.value) return "done";
  if (stageIndex === currentIndex.value) return "current";
  return "pending";
}

/** 步骤 tab 状态。 */
function tabState(tab: { role: string; step?: AiStepResponse | undefined }): string {
  if (running.value && currentRole.value === tab.role) return "running";
  if (tab.step) return tab.step.failureReason ? "failed" : "done";
  return "pending";
}

function submitStart(): void {
  emit("start", { testCaseCount: testCaseCount.value, autoPublish: autoPublish.value, sampleCount: sampleCount.value });
}

/** 人工澄清说明，普通输入框，提交时包装为 { note }。 */
const clarification = ref("");
function submitResume(): void {
  const action = resumeAction.value;
  if (!action) return;
  const note = clarification.value.trim() || "已澄清";
  emit("resume", { action, correction: { note } });
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

        <template v-if="run">
          <div class="ai-overlay-status">
            <span class="ai-overlay-state">
              {{ majorLabels[run.majorState] }}<template v-if="run.majorState !== run.state"> · {{ minorLabels[run.state] ?? run.state }}</template>
              <LoaderCircle v-if="running" class="ai-overlay-spin" :size="15" />
            </span>
            <UiAlert v-if="['NEEDS_REVIEW', 'FAILED', 'CANCELED'].includes(run.state)" :variant="run.state === 'NEEDS_REVIEW' ? 'warning' : 'error'" :title="run.failureReason || majorLabels[run.majorState]" />
            <UiAlert v-if="run.state === 'VALIDATING' && !run.autoPublish" variant="success" title="差分已通过，请在下方确认测试点与公开样例后保存/发布。" />
          </div>

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
              <details v-for="tc in generatedTestCases" :key="tc.ordinal" class="ai-overlay-item" :open="tc.sample">
                <summary><strong>测试点 {{ tc.ordinal }}</strong><small>种子 {{ tc.seed }} · {{ tc.score }} 分<template v-if="tc.sample"> · 公开样例</template></small></summary>
                <div class="ai-overlay-item-cols"><div><strong>输入</strong><pre>{{ tc.input }}</pre></div><div><strong>标准输出</strong><pre>{{ tc.output }}</pre></div></div>
              </details>
            </div>
          </section>

          <!-- Agent 各角色返回：点切换，单步展示 -->
          <section class="ai-overlay-section">
            <header class="ai-overlay-section-head"><strong>Agent 步骤返回</strong><span>点击切换对应步骤</span></header>
            <div class="ai-step-tabs">
              <button
                v-for="tab in stepTabs"
                :key="tab.role"
                type="button"
                :class="['ai-step-tab', `ai-step-tab--${tabState(tab)}`, { 'ai-step-tab--active': selectedRole === tab.role }]"
                @click="selectedRole = tab.role"
              >
                <LoaderCircle v-if="tabState(tab) === 'running'" :size="12" class="ai-step-tab-spin" />
                <CheckCircle2 v-else-if="tabState(tab) === 'done'" :size="13" />
                <XCircle v-else-if="tabState(tab) === 'failed'" :size="13" />
                <span>{{ tab.label }}</span>
              </button>
            </div>

            <UiCard class="ai-step-detail">
              <template v-if="selectedRunning">
                <div class="ai-step-waiting"><LoaderCircle :size="18" class="ai-overlay-spin" /><strong>正在执行「{{ selectedStep ? stepTabs.find((t) => t.role === selectedRole)?.label : roleLabels[selectedRole] ?? selectedRole }}」</strong><span>该步骤正在重新运行，完成后将显示最新返回。</span></div>
              </template>
              <template v-else-if="selectedStep">
                <div class="ai-step-detail-head">
                  <strong>{{ stepTabs.find((t) => t.role === selectedRole)?.label ?? selectedRole }}</strong>
                  <UiBadge variant="outline">{{ minorLabels[selectedStep.state] ?? selectedStep.state }}</UiBadge>
                  <time v-if="selectedStep.finishedAt">{{ formatChinaDateTime(selectedStep.finishedAt, { dateStyle: "medium", timeStyle: "short" }) }}</time>
                </div>
                <div v-if="responseItems(selectedStep).length === 0" class="ai-overlay-empty">该步骤没有可展示的结构化返回。</div>
                <div v-else class="ai-step-fields">
                  <div v-for="item in responseItems(selectedStep)" :key="item.label" class="ai-step-field" :class="{ 'ai-step-field--warning': item.label.includes('歧义') || item.label.includes('审查') }">
                    <span class="ai-step-label">{{ item.label }}</span>
                    <template v-if="item.kind === 'code'"><pre class="ai-step-code">{{ item.value }}</pre></template>
                    <ul v-else-if="item.kind === 'list'"><li v-for="v in item.value" :key="v">{{ v }}</li></ul>
                    <p v-else-if="item.kind === 'text'">{{ item.value }}</p>
                    <span v-else>{{ item.value }}</span>
                  </div>
                </div>
                <p v-if="selectedStep.failureReason" class="ai-response-error">{{ selectedStep.failureReason }}</p>
                <details v-if="selectedStep.rawResponse" class="ai-step-raw"><summary>查看原始模型返回</summary><pre>{{ selectedStep.rawResponse }}</pre></details>
                <small v-if="selectedStep.contentSha256" class="ai-response-hash">返回哈希：{{ selectedStep.contentSha256 }}</small>
              </template>
              <div v-else class="ai-overlay-empty">该步骤尚未执行。</div>
            </UiCard>
          </section>

          <!-- 人工接管：输入澄清说明并恢复 -->
          <section v-if="run.state === 'NEEDS_REVIEW'" class="ai-overlay-section ai-overlay-resume">
            <header class="ai-overlay-section-head"><strong>人工接管：补充澄清并恢复</strong></header>
            <p v-if="resumeAction" class="ai-overlay-hint">
              当前失败阶段：<code>{{ run.resumeTarget }}</code>（{{ resumeAction === "reanalyze" ? "重新分析题意" : "重新对抗审查" }}）。
              填写澄清说明后，会把内容作为上下文回传给 Agent 重新执行对应节点。
            </p>
            <p v-else class="ai-overlay-hint ai-overlay-hint--blocked">当前失败阶段不支持自动恢复（通常为沙箱校验或基础设施连续失败），需人工处理后重新启动。</p>
            <template v-if="resumeAction">
              <div class="form-field"><UiLabel>澄清说明</UiLabel><UiInput v-model="clarification" placeholder="例如：d=0 时输出应为 0；数字无需前缀零" /></div>
              <div class="ai-overlay-actions"><UiButton type="button" :loading="loading" @click="submitResume"><Bot :size="16" />保存并恢复</UiButton></div>
            </template>
          </section>

          <footer class="ai-overlay-actions">
            <UiButton v-if="!isTerminal" :loading="loading" variant="destructive" @click="emit('cancel')"><XCircle :size="16" />取消流程</UiButton>
            <UiButton v-if="canRestart" :loading="loading" @click="submitStart"><Bot :size="16" />重新启动</UiButton>
            <UiButton :loading="loading" variant="ghost" @click="emit('refresh')"><RefreshCw :size="16" />刷新状态</UiButton>
            <UiButton v-if="isTerminal || canCloseForDraft" variant="outline" @click="emit('close')">{{ isTerminal ? "完成" : "关闭（去编辑草稿）" }}</UiButton>
          </footer>
        </template>

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
.ai-case-list {
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
.ai-step-code,
.ai-step-raw pre {
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
.ai-step-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}
.ai-step-tab {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.4rem 0.7rem;
  border-radius: 0.5rem;
  border: 1px solid var(--border-1, #e5e7eb);
  background: var(--surface-1, #fff);
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--text-2, #6b7280);
  cursor: pointer;
}
.ai-step-tab--active {
  border-color: transparent;
  background: var(--brand, #2563eb);
  color: #fff;
}
.ai-step-tab--done {
  color: var(--success, #16a34a);
}
.ai-step-tab--failed {
  color: var(--danger, #dc2626);
}
.ai-step-tab--pending {
  opacity: 0.55;
}
.ai-step-tab-spin {
  animation: ai-spin 1s linear infinite;
}
.ai-step-detail {
  padding: 0.9rem 1rem;
}
.ai-step-detail-head {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.6rem;
  flex-wrap: wrap;
}
.ai-step-detail-head time {
  margin-left: auto;
  font-size: 0.72rem;
  color: var(--text-2, #6b7280);
}
.ai-step-waiting {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.25rem;
  color: var(--text-2, #6b7280);
}
.ai-step-waiting strong {
  color: var(--text-1, #111827);
}
.ai-step-fields {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}
.ai-step-field {
  border-top: 1px solid var(--border-1, #e5e7eb);
  padding-top: 0.5rem;
}
.ai-step-field--warning .ai-step-label {
  color: var(--warning, #d97706);
}
.ai-step-label {
  display: block;
  font-size: 0.75rem;
  font-weight: 700;
  color: var(--text-2, #6b7280);
  margin-bottom: 0.2rem;
}
.ai-step-field ul {
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
.ai-step-raw {
  margin-top: 0.5rem;
}
.ai-step-raw summary {
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
